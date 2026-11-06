package com.luca.geelykeyradar;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * 吉利银河通知栏桥接。
 *
 * 极简版只保留两件事：
 * 1. 读取官方通知栏的“已连接/未连接”状态，用于判断蓝牙钥匙是否在线；
 * 2. 直接触发通知栏里官方的“解锁/上锁”按钮，等价于用户手动点击，不依赖车机被动 PE 开关。
 *
 * 旧版注释中关于 DK1.5 指令反转、RemoteViews.apply 兼容 Android 14-16 的逆向结论仍然有效。
 */
public class KeyNotificationBridgeService extends NotificationListenerService {

    private static final String TAG = "KeyNotifBridge";
    public static final String GEELY_PKG = "com.geelygalaxy.customer";
    public static final int GEELY_DK_NOTIFY_ID = 5;

    // Resource IDs from com.geely.snc.digitalkey.base.R$id (verified via dexdump of DkRemoteView.buildRemoteView @ 0x5011e8)
    public static final int ID_NOTIF_LAYOUT_LOCK_UNLOCK = 0x7f0a10c6;
    public static final int ID_NOTIF_TV_LOCK            = 0x7f0a10c7;
    public static final int ID_NOTIF_TV_STATUS_CONN     = 0x7f0a10c8;
    public static final int ID_NOTIF_TV_STATUS_DISCONN  = 0x7f0a10c9;
    public static final int ID_NOTIF_TV_STATUS_NO       = 0x7f0a10ca;
    public static final int ID_NOTIF_TV_UNLOCK          = 0x7f0a10cb;

    private static volatile KeyNotificationBridgeService instance;
    private static volatile boolean geelyBleConnected = false;
    private static volatile boolean geelyNotificationPresent = false;
    private static volatile PendingIntent cachedLockPi = null;
    private static volatile PendingIntent cachedUnlockPi = null;
    private static volatile PendingIntent cachedContentPi = null;
    private static volatile View cachedLockView = null;
    private static volatile View cachedUnlockView = null;
    private static volatile long lastUpdateTimestamp = 0L;
    private static volatile String lastObservedParkStatus = "";
    private static volatile long lastObservedParkStatusAtMs = 0L;
    private static volatile String lastObservedLockHint = "";
    private static volatile String lastButtonCaptureDiag = "等待解析吉利银河通知栏...";
    private static volatile Boolean textConnectionHint = null;

    // Pending action queue when unlock/lock is requested while Geely's notification is still transitioning to "已连接"
    private static volatile int pendingActionOnConnect = 0; // 0=none, 1=unlock, 2=lock

    private static volatile long pendingActionExpireMs = 0L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Context geelyPkgContext = null;

    public static KeyNotificationBridgeService getInstance() {
        return instance;
    }

    public static String getLastObservedParkStatus() {
        return lastObservedParkStatus;
    }

    public static long getLastObservedParkStatusAtMs() {
        return lastObservedParkStatusAtMs;
    }

    public static String getLastObservedLockHint() {
        return lastObservedLockHint;
    }

    public static String getLastButtonCaptureDiag() {
        return lastButtonCaptureDiag;
    }

    public static boolean isNotificationListenerEnabled(Context context) {
        if (context == null) return false;
        String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) return false;
        ComponentName cn = new ComponentName(context, KeyNotificationBridgeService.class);
        return flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString());
    }

    /**
     * Ensures Android's NotificationManagerService actively binds KeyNotificationBridgeService
     * (recovering from any prior force-stop or package reinstall) and immediately scans Geely's notification.
     */
    public static void ensureRebound(Context context) {
        if (context == null) return;
        try {
            KeyNotificationBridgeService cur = instance;
            if (cur != null) {
                cur.scanExistingNotifications();
                return;
            }
            if (!isNotificationListenerEnabled(context)) {
                return;
            }
            ComponentName cn = new ComponentName(context, KeyNotificationBridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationListenerService.requestRebind(cn);
            }
            // Also toggle component enabled state if needed to force NMS re-registration after APK update
            PackageManager pm = context.getPackageManager();
            if (pm != null) {
                pm.setComponentEnabledSetting(cn,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureRebound warning: " + t.getMessage());
        }
    }

    public static boolean isGeelyBleConnected() {
        return geelyBleConnected;
    }

    public static boolean isGeelyNotificationPresent() {
        return geelyNotificationPresent;
    }

    public static boolean hasControlPendingIntents() {
        return (cachedUnlockPi != null || cachedUnlockView != null)
                && (cachedLockPi != null || cachedLockView != null);
    }

    public static long getLastUpdateTimestamp() {
        return lastUpdateTimestamp;
    }

    /**
     * 取消任何挂起的连接后自动点击动作（例如用户关闭了服务总开关，或指令已由主流程完成）。
     */
    public static void cancelPendingActions() {
        pendingActionOnConnect = 0;
        pendingActionExpireMs = 0L;
    }

    /**
     * 检查当前是否因插着 USB/AC 有线充电线（或关闭了服务总开关）而禁止操作车锁。
     */
    private static boolean isControlBlocked() {
        KeyNotificationBridgeService svc = instance;
        if (svc == null) return false;
        try {
            VehicleIdentityStore store = new VehicleIdentityStore(svc);
            if (!store.isServiceEnabled()) {
                return true;
            }
            if (!store.isBlockWhenChargingEnabled()) {
                return false;
            }
            Intent sticky = svc.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky == null) return false;
            int plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            return (plugged & (BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB)) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Triggers the exact '解锁' (Unlock) button in Geely Galaxy's notification bar (id=5, notif_tv_unlock=0x7f0a10cb).
     * If Geely's notification is currently still in "未连接" state and hasn't bound the button yet,
     * queues the unlock request for 10 seconds so the instant the notification flips to "已连接", it fires immediately!
     */
    public static boolean triggerUnlockViaNotification() {
        if (isControlBlocked()) {
            pendingActionOnConnect = 0;
            Log.i(TAG, "🔌 插充电线或总开关关闭，忽略通知栏【解锁】");
            return false;
        }
        KeyNotificationBridgeService svc = instance;
        if (svc != null && cachedUnlockPi == null && cachedUnlockView == null) {
            svc.scanExistingNotifications();
        }
        boolean fired = fireNotificationButton(cachedUnlockPi, cachedUnlockView, "解锁");
        if (!fired) {
            // Queue for immediate auto-fire as soon as Geely notification transitions to "已连接"
            pendingActionOnConnect = 1;
            pendingActionExpireMs = System.currentTimeMillis() + 15000L;
            Log.i(TAG, "通知栏【解锁】按钮暂未就绪(等待银河BLE通知变为已连接)，已挂起并在连接瞬间自动点击");
        } else {
            if (pendingActionOnConnect == 1) {
                pendingActionOnConnect = 0;
            }
        }
        return fired;
    }

    /**
     * Triggers the exact '上锁' (Lock) button in Geely Galaxy's notification bar (id=5, notif_tv_lock=0x7f0a10c7).
     */
    public static boolean triggerLockViaNotification() {
        if (isControlBlocked()) {
            pendingActionOnConnect = 0;
            Log.i(TAG, "🔌 插充电线或总开关关闭，忽略通知栏【上锁】");
            return false;
        }
        KeyNotificationBridgeService svc = instance;
        if (svc != null && cachedLockPi == null && cachedLockView == null) {
            svc.scanExistingNotifications();
        }
        boolean fired = fireNotificationButton(cachedLockPi, cachedLockView, "上锁");
        if (!fired) {
            pendingActionOnConnect = 2;
            pendingActionExpireMs = System.currentTimeMillis() + 15000L;
            Log.i(TAG, "通知栏【上锁】按钮暂未就绪(等待银河BLE通知变为已连接)，已挂起并在连接瞬间自动点击");
        } else {
            if (pendingActionOnConnect == 2) {
                pendingActionOnConnect = 0;
            }
        }
        return fired;
    }

    private static boolean fireNotificationButton(final PendingIntent pi, final View buttonView, final String actionLabel) {
        boolean sent = false;
        // 1. Primary: Send the extracted PendingIntent directly (with Android 14-16 background activity/broadcast start options)
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions opts = ActivityOptions.makeBasic();
                    opts.setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    pi.send(null, 0, null, null, null, null, opts.toBundle());
                } else {
                    pi.send();
                }
                sent = true;
                Log.i(TAG, "✓ 已通过通知栏 PendingIntent.send() 触发银河【" + actionLabel + "】按钮");
            } catch (Throwable t1) {
                try {
                    pi.send();
                    sent = true;
                    Log.i(TAG, "✓ 已通过通知栏 PendingIntent.send() 触发银河【" + actionLabel + "】按钮 (fallback)");
                } catch (Throwable t2) {
                    Log.w(TAG, "PendingIntent.send() 异常 (" + actionLabel + "): " + t2.getMessage());
                }
            }
        }

        // 2. Secondary / Dual-assurance: Invoke performClick() on the inflated RemoteViews button View on the main thread
        if (buttonView != null) {
            KeyNotificationBridgeService svc = instance;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                try {
                    boolean clicked = buttonView.performClick();
                    sent = sent || clicked;
                    Log.i(TAG, "✓ 已在主线程调用通知栏按钮 View.performClick() 触发银河【" + actionLabel + "】 -> " + clicked);
                } catch (Throwable t) {
                    Log.w(TAG, "buttonView.performClick() 异常: " + t.getMessage());
                }
            } else if (svc != null) {
                svc.mainHandler.post(() -> {
                    try {
                        boolean clicked = buttonView.performClick();
                        Log.i(TAG, "✓ 已异步调用通知栏按钮 View.performClick() 触发银河【" + actionLabel + "】 -> " + clicked);
                    } catch (Throwable t) {
                        Log.w(TAG, "async buttonView.performClick() 异常: " + t.getMessage());
                    }
                });
                sent = true;
            }
        }
        return sent;
    }

    public static boolean triggerContentIntentWakeup() {
        PendingIntent pi = cachedContentPi;
        if (pi != null) {
            try {
                pi.send();
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "KeyNotificationBridgeService onCreate");
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Log.i(TAG, "KeyNotificationBridgeService onListenerConnected -> scanning active notifications");
        ensureRadarServiceRunning();
        scanExistingNotifications();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.w(TAG, "KeyNotificationBridgeService onListenerDisconnected -> requesting rebind");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestRebind(new ComponentName(this, KeyNotificationBridgeService.class));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        ensureRadarServiceRunning();
        if (sbn != null && GEELY_PKG.equals(sbn.getPackageName())) {
            parseGeelyNotification(sbn);
        }
    }

    private void ensureRadarServiceRunning() {
        if (!new VehicleIdentityStore(this).isServiceEnabled()) {
            return;
        }
        if (!PassiveKeyService.isRunning()) {
            try {
                Intent svc = new Intent(this, PassiveKeyService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && GEELY_PKG.equals(sbn.getPackageName())) {
            geelyNotificationPresent = false;
            geelyBleConnected = false;
            textConnectionHint = null;
            cachedUnlockPi = null;
            cachedLockPi = null;
            cachedUnlockView = null;
            cachedLockView = null;
            mainHandler.post(this::scanExistingNotifications);
        }
    }

    public void scanExistingNotifications() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification sbn : active) {
                if (sbn != null && GEELY_PKG.equals(sbn.getPackageName())) {
                    parseGeelyNotification(sbn);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "scanExistingNotifications error: " + t.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void parseGeelyNotification(final StatusBarNotification sbn) {
        final Notification notif = sbn.getNotification();
        if (notif == null) return;

        // Ignore empty group summary notifications (e.g. id=0 with FLAG_GROUP_SUMMARY and no RemoteViews)
        boolean isGroupSummary = (notif.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        if (isGroupSummary && notif.contentView == null && notif.bigContentView == null) {
            return;
        }
        if (sbn.getId() != GEELY_DK_NOTIFY_ID && notif.contentView == null && notif.bigContentView == null) {
            return;
        }

        geelyNotificationPresent = true;
        lastUpdateTimestamp = System.currentTimeMillis();
        if (notif.contentIntent != null) {
            cachedContentPi = notif.contentIntent;
        }

        // Inspect standard notification extras for vehicle state keywords ("已驻车", "行驶中", "充电中", etc.)
        if (notif.extras != null) {
            for (String key : new String[]{
                    Notification.EXTRA_TITLE,
                    Notification.EXTRA_TEXT,
                    Notification.EXTRA_SUB_TEXT,
                    Notification.EXTRA_BIG_TEXT}) {
                CharSequence cs = notif.extras.getCharSequence(key);
                if (cs != null) {
                    inspectTextForVehicleStatus(cs.toString());
                }
            }
        }

        final RemoteViews rv = notif.bigContentView != null ? notif.bigContentView : notif.contentView;
        if (rv == null) return;

        // Always inflate & inspect on the main thread so RemoteViews.apply() and View.performClick() are 100% safe
        if (Looper.myLooper() == Looper.getMainLooper()) {
            inflateAndExtractFromRemoteViews(rv);
        } else {
            mainHandler.post(() -> inflateAndExtractFromRemoteViews(rv));
        }
    }

    private Context getOrCreateGeelyContext() {
        if (geelyPkgContext == null) {
            try {
                geelyPkgContext = createPackageContext(GEELY_PKG, Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable ignored) {
            }
        }
        return geelyPkgContext != null ? geelyPkgContext : this;
    }

    /**
     * Inflates Geely Galaxy's RemoteViews (_aaa_ge_dk_notification_view = 0x7f0d0003) via public
     * RemoteViews.apply(context, parent) and extracts:
     * 1. Connection status (notif_tv_status_conn 0x7f0a10c8 / notif_layout_lock_unlock 0x7f0a10c6 vs notif_tv_status_disconn 0x7f0a10c9)
     * 2. The inflated '解锁' (0x7f0a10cb) and '上锁' (0x7f0a10c7) button Views + their attached PendingIntents
     */
    private void inflateAndExtractFromRemoteViews(RemoteViews rv) {
        Boolean connVisible = null;
        boolean foundUnlockBtn = false;
        boolean foundLockBtn = false;

        // Path 1: Official public RemoteViews.apply() inflation (Works on Android 14/15/16 without hidden API restrictions!)
        try {
            Context inflateCtx = getOrCreateGeelyContext();
            FrameLayout container = new FrameLayout(inflateCtx);
            View root = null;
            try {
                root = rv.apply(inflateCtx, container);
            } catch (Throwable t1) {
                root = rv.apply(this, new FrameLayout(this));
            }

            if (root != null) {
                View connView = root.findViewById(ID_NOTIF_TV_STATUS_CONN);
                View lockUnlockLayout = root.findViewById(ID_NOTIF_LAYOUT_LOCK_UNLOCK);
                View disconnView = root.findViewById(ID_NOTIF_TV_STATUS_DISCONN);

                if (connView != null && connView.getVisibility() == View.VISIBLE) {
                    connVisible = true;
                } else if (lockUnlockLayout != null && lockUnlockLayout.getVisibility() == View.VISIBLE) {
                    connVisible = true;
                } else if (disconnView != null && disconnView.getVisibility() == View.VISIBLE) {
                    connVisible = false;
                }

                View unlockView = root.findViewById(ID_NOTIF_TV_UNLOCK);
                View lockView = root.findViewById(ID_NOTIF_TV_LOCK);

                // Fallback: traverse tree by text ("解锁" / "上锁" / "已连接" / "未连接") in case resource IDs shift across Geely versions
                View[] discoveredByText = new View[2]; // [0]=unlock, [1]=lock
                Boolean[] connByText = new Boolean[1];
                traverseInflatedTree(root, discoveredByText, connByText);

                if (unlockView == null) unlockView = discoveredByText[0];
                if (lockView == null) lockView = discoveredByText[1];
                if (connVisible == null && connByText[0] != null) {
                    connVisible = connByText[0];
                }

                // Extract PendingIntent or OnClickListener from unlockView & lockView
                // IMPORTANT: DkRemoteView.buildRemoteView only calls setOnClickPendingIntent when isConnect == true!
                // When isConnect == false, unlockView.hasOnClickListeners() is false, so we MUST NOT overwrite
                // a previously captured cachedUnlockPi / cachedUnlockView with an unclickable view!
                if (unlockView != null) {
                    PendingIntent pi = extractPendingIntentFromInflatedView(unlockView);
                    if (pi != null) {
                        cachedUnlockPi = pi;
                        foundUnlockBtn = true;
                    }
                    if (unlockView.hasOnClickListeners() || pi != null) {
                        cachedUnlockView = unlockView;
                        foundUnlockBtn = true;
                    }
                }

                if (lockView != null) {
                    PendingIntent pi = extractPendingIntentFromInflatedView(lockView);
                    if (pi != null) {
                        cachedLockPi = pi;
                        foundLockBtn = true;
                    }
                    if (lockView.hasOnClickListeners() || pi != null) {
                        cachedLockView = lockView;
                        foundLockBtn = true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "RemoteViews.apply inflation warning: " + t.getMessage());
        }

        // Path 2: Also attempt mActions reflection as supplementary fallback for older Android versions
        try {
            Field actionsField = RemoteViews.class.getDeclaredField("mActions");
            actionsField.setAccessible(true);
            ArrayList<?> actions = (ArrayList<?>) actionsField.get(rv);
            if (actions != null) {
                for (Object action : actions) {
                    if (action == null) continue;
                    String className = action.getClass().getName();
                    int viewId = extractViewId(action);
                    extractAndInspectTextFromAction(action);

                    if (viewId == ID_NOTIF_TV_STATUS_CONN || viewId == ID_NOTIF_LAYOUT_LOCK_UNLOCK) {
                        Integer vis = extractVisibilityValue(action);
                        if (vis != null && connVisible == null) {
                            connVisible = (vis == View.VISIBLE);
                        }
                    } else if (viewId == ID_NOTIF_TV_STATUS_DISCONN) {
                        Integer vis = extractVisibilityValue(action);
                        if (vis != null && vis == View.VISIBLE && connVisible == null) {
                            connVisible = false;
                        }
                    }

                    if (viewId == ID_NOTIF_TV_LOCK || viewId == ID_NOTIF_TV_UNLOCK
                            || className.contains("SetOnClickResponse") || className.contains("SetOnClickPendingIntent")) {
                        PendingIntent pi = extractPendingIntentFromAction(action);
                        if (pi != null) {
                            if (viewId == ID_NOTIF_TV_LOCK) {
                                cachedLockPi = pi;
                                foundLockBtn = true;
                            } else if (viewId == ID_NOTIF_TV_UNLOCK) {
                                cachedUnlockPi = pi;
                                foundUnlockBtn = true;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        boolean prevConnected = geelyBleConnected;
        if (connVisible == null && textConnectionHint != null) {
            connVisible = textConnectionHint;
        }
        if (connVisible != null) {
            geelyBleConnected = connVisible;
        }
        // 未连接时银河通知里的按钮不可点；清掉上一轮连接留下的 PendingIntent，
        // 让 triggerUnlock/triggerLock 走“先排队、连接后立刻点击”的路径，
        // 避免把解锁指令提前发给尚未完成 V2 鉴权的车机。
        if (Boolean.FALSE.equals(connVisible)) {
            cachedUnlockPi = null;
            cachedLockPi = null;
            cachedUnlockView = null;
            cachedLockView = null;
        }

        lastButtonCaptureDiag = String.format(
                "通知栏状态=%s | 解锁按钮=%s | 上锁按钮=%s",
                geelyBleConnected ? "已连接" : "未连接",
                (cachedUnlockPi != null ? "PI已捕获" : (cachedUnlockView != null ? "View已就绪" : "待连接捕获")),
                (cachedLockPi != null ? "PI已捕获" : (cachedLockView != null ? "View已就绪" : "待连接捕获"))
        );
        Log.i(TAG, "已解析银河通知栏: " + lastButtonCaptureDiag
                + " (本次捕获: unlock=" + foundUnlockBtn + ", lock=" + foundLockBtn + ")");

        // If Geely notification just transitioned to "已连接" (or buttons just became ready) and there is a pending unlock/lock request, fire it NOW!
        if ((geelyBleConnected || foundUnlockBtn || foundLockBtn)
                && pendingActionOnConnect != 0
                && System.currentTimeMillis() <= pendingActionExpireMs) {
            final int act = pendingActionOnConnect;
            pendingActionOnConnect = 0;
            if (act == 1) {
                Log.i(TAG, "⚡ 检测到银河通知栏变为【已连接】，立即自动触发挂起的通知栏【解锁】按钮！");
                triggerUnlockViaNotification();
            } else if (act == 2) {
                Log.i(TAG, "⚡ 检测到银河通知栏变为【已连接】，立即自动触发挂起的通知栏【上锁】按钮！");
                triggerLockViaNotification();
            }
        } else if (!prevConnected && geelyBleConnected) {
            Log.i(TAG, "✓ 银河通知栏状态已切换为【已连接】");
        }
    }

    private void traverseInflatedTree(View view, View[] discoveredButtons, Boolean[] connByText) {
        if (view == null) return;
        if (view instanceof TextView) {
            CharSequence cs = ((TextView) view).getText();
            String text = (cs != null) ? cs.toString().trim() : "";
            if (!text.isEmpty()) {
                inspectTextForVehicleStatus(text);
                if ("解锁".equals(text)) {
                    discoveredButtons[0] = view;
                } else if ("上锁".equals(text) || "闭锁".equals(text)) {
                    discoveredButtons[1] = view;
                } else if (view.getVisibility() == View.VISIBLE) {
                    if (text.contains("已连接")) {
                        connByText[0] = true;
                    } else if (text.contains("未连接") && connByText[0] == null) {
                        connByText[0] = false;
                    }
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                traverseInflatedTree(vg.getChildAt(i), discoveredButtons, connByText);
            }
        }
    }

    /**
     * Extracts the PendingIntent stored by RemoteViews.SetOnClickResponse on an inflated View:
     * 1. Checks View.getTag(com.android.internal.R.id.pending_intent_tag)
     * 2. Inspects View's mKeyedTags SparseArray for any PendingIntent or RemoteResponse
     * 3. Inspects View's ListenerInfo.mOnClickListener fields for any PendingIntent or RemoteResponse
     */
    private PendingIntent extractPendingIntentFromInflatedView(View view) {
        if (view == null) return null;

        // 1. Check standard Android system tag "pending_intent_tag"
        try {
            int tagId = Resources.getSystem().getIdentifier("pending_intent_tag", "id", "android");
            if (tagId != 0) {
                Object tagVal = view.getTag(tagId);
                PendingIntent pi = unwrapPendingIntent(tagVal);
                if (pi != null) return pi;
            }
        } catch (Throwable ignored) {
        }

        // 2. Check View.mKeyedTags SparseArray if accessible
        try {
            Field keyedTagsField = View.class.getDeclaredField("mKeyedTags");
            keyedTagsField.setAccessible(true);
            Object sparseObj = keyedTagsField.get(view);
            if (sparseObj instanceof android.util.SparseArray) {
                android.util.SparseArray<?> sa = (android.util.SparseArray<?>) sparseObj;
                for (int i = 0; i < sa.size(); i++) {
                    PendingIntent pi = unwrapPendingIntent(sa.valueAt(i));
                    if (pi != null) return pi;
                }
            }
        } catch (Throwable ignored) {
        }

        // 3. Check View's OnClickListener inside ListenerInfo
        try {
            Method getListenerInfo = View.class.getDeclaredMethod("getListenerInfo");
            getListenerInfo.setAccessible(true);
            Object listenerInfo = getListenerInfo.invoke(view);
            if (listenerInfo != null) {
                Field onClickField = listenerInfo.getClass().getDeclaredField("mOnClickListener");
                onClickField.setAccessible(true);
                Object clickListener = onClickField.get(listenerInfo);
                if (clickListener != null) {
                    PendingIntent pi = unwrapPendingIntent(clickListener);
                    if (pi != null) return pi;
                    for (Field f : clickListener.getClass().getDeclaredFields()) {
                        try {
                            f.setAccessible(true);
                            PendingIntent innerPi = unwrapPendingIntent(f.get(clickListener));
                            if (innerPi != null) return innerPi;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private PendingIntent unwrapPendingIntent(Object obj) {
        if (obj == null) return null;
        if (obj instanceof PendingIntent) {
            return (PendingIntent) obj;
        }
        try {
            Class<?> cls = obj.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v instanceof PendingIntent) {
                            return (PendingIntent) v;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private int extractViewId(Object action) {
        Class<?> cls = action.getClass();
        while (cls != null && cls != Object.class) {
            for (String fieldName : new String[]{"viewId", "mViewId"}) {
                try {
                    Field f = cls.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f.getInt(action);
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return -1;
    }

    private Integer extractVisibilityValue(Object action) {
        Class<?> cls = action.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    String name = f.getName().toLowerCase();
                    if (name.contains("visib") && (f.getType() == int.class || f.getType() == Integer.class)) {
                        return (Integer) f.get(action);
                    }
                    if (name.equals("value") && (f.getType() == Object.class || f.getType() == Integer.class)) {
                        Object v = f.get(action);
                        if (v instanceof Integer) {
                            return (Integer) v;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private PendingIntent extractPendingIntentFromAction(Object action) {
        Class<?> cls = action.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(action);
                    PendingIntent pi = unwrapPendingIntent(val);
                    if (pi != null) return pi;
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private void extractAndInspectTextFromAction(Object action) {
        Class<?> cls = action.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(action);
                    if (val instanceof CharSequence) {
                        inspectTextForVehicleStatus(val.toString());
                    }
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
    }

    private void inspectTextForVehicleStatus(String text) {
        if (text == null || text.isEmpty()) return;
        String newPark = null;
        if (text.contains("行驶中")) {
            newPark = "行驶中";
        } else if (text.contains("充电中")) {
            newPark = "充电中";
        } else if (text.contains("放电中")) {
            newPark = "放电中";
        } else if (text.contains("已插枪")) {
            newPark = "已插枪";
        } else if (text.contains("已驻车")) {
            newPark = "已驻车";
        }
        if (newPark != null && !newPark.equals(lastObservedParkStatus)) {
            lastObservedParkStatus = newPark;
            lastObservedParkStatusAtMs = System.currentTimeMillis();
        }
        if (text.contains("已上锁") || text.contains("已闭锁") || text.contains("已锁车")) {
            lastObservedLockHint = "LOCKED";
        } else if (text.contains("已解锁") || text.contains("未上锁") || text.contains("未闭锁")) {
            lastObservedLockHint = "UNLOCKED";
        }
        if (text.contains("未连接") || text.contains("已断开")) {
            textConnectionHint = false;
        } else if (text.contains("已连接")) {
            textConnectionHint = true;
        }
    }
}
