package com.luca.geelykeyradar;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 极简车主 HUD 首页。
 * 遵循 Paseo Dark 设计规范（DESIGN.md）与 Refactoring UI 十二大系统阶梯：
 * 1. 以「走动冷暖雷达 (HotColdRadarView)」为视觉中心，呈现直观的距离色温与门锁态势；
 * 2. 状态微徽章呈现守护状态，右上角提供 46dp 设置靶区直达二级设置页；
 * 3. 顶栏胶囊支持 46dp 点击交互，可随时拉起「钥匙就绪自检」底部抽屉浮层 (ReadinessBottomSheet)；
 * 4. 常驻打开领克App快捷操作，底部仅保留常用动作开关，彻底去除工程噪音。
 */
public class MainActivity extends Activity {

    private static final int REQUEST_BT_PERMISSIONS = 2001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 500L);
        }
    };

    private final ReadinessModel readinessModel = new ReadinessModel();
    private ReadinessBottomSheet readinessBottomSheet;

    private PassiveKeyService service;
    private boolean bound = false;
    private boolean binding = false;
    private boolean settingSwitches = false;

    private TextView titleVehicleNameView;
    private TextView statusBadgeView;
    private ImageView settingsButton;
    private HotColdRadarView radarView;
    private Button quickUnlockButton;
    private Button quickLockButton;
    private Button openOfficialAppButton;
    private Switch serviceMasterSwitch;
    private Switch autoUnlockSwitch;
    private Switch autoLockSwitch;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder instanceof PassiveKeyService.LocalBinder) {
                service = ((PassiveKeyService.LocalBinder) binder).getService();
            }
            bound = true;
            binding = false;
            refreshUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            binding = false;
            refreshUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticLog.initialize(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPassiveKeyService();
        bindPassiveKeyService();
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        if (bound || binding) {
            try {
                unbindService(connection);
            } catch (Throwable ignored) {
            }
            bound = false;
            binding = false;
            service = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (readinessBottomSheet != null && readinessBottomSheet.isShowing()) {
            try {
                readinessBottomSheet.dismiss();
            } catch (Throwable ignored) {
            }
            readinessBottomSheet = null;
        }
        super.onDestroy();
    }

    private void startPassiveKeyService() {
        try {
            Intent intent = new Intent(this, PassiveKeyService.class);
            intent.setAction(PassiveKeyService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable ignored) {
        }
    }

    private void bindPassiveKeyService() {
        if (bound || binding) return;
        binding = true;
        Intent intent = new Intent(this, PassiveKeyService.class);
        try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable ignored) {
            binding = false;
        }
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(PaseoTheme.COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(
                dp(PaseoTheme.PADDING_PAGE_H),
                dp(PaseoTheme.PADDING_PAGE_TOP),
                dp(PaseoTheme.PADDING_PAGE_H),
                dp(PaseoTheme.PADDING_PAGE_BOTTOM)
        );
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        // --- 1. 顶栏：标题 + 状态胶囊徽章 (46dp 靶区支持点击查看就绪清单) + 46dp 设置齿轮按钮 ---
        LinearLayout headerBar = new LinearLayout(this);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(PaseoTheme.TARGET_MIN_DP)
        );
        headerLp.bottomMargin = dp(PaseoTheme.MARGIN_CARD_BOTTOM);

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleGroupLp = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
        );

        TextView appTitle = text("领克无感钥匙", 20, PaseoTheme.COLOR_TEXT, Typeface.BOLD);
        titleGroup.addView(appTitle);

        titleVehicleNameView = text("正在加载车辆...", 12, PaseoTheme.COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleLp.topMargin = dp(2);
        titleGroup.addView(titleVehicleNameView, subtitleLp);
        headerBar.addView(titleGroup, titleGroupLp);

        // 状态徽章胶囊 (最小 46dp 靶区，可点击拉起就绪清单抽屉)
        statusBadgeView = new TextView(this);
        statusBadgeView.setText("● 守护中");
        statusBadgeView.setTextSize(12);
        statusBadgeView.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        statusBadgeView.setTextColor(PaseoTheme.COLOR_SUCCESS);
        statusBadgeView.setGravity(Gravity.CENTER);
        statusBadgeView.setMinHeight(dp(PaseoTheme.TARGET_MIN_DP));
        statusBadgeView.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(PaseoTheme.COLOR_SURFACE_2);
        badgeBg.setCornerRadius(dp(PaseoTheme.RADIUS_INPUT));
        badgeBg.setStroke(dp(PaseoTheme.BORDER_WIDTH_DP), PaseoTheme.COLOR_BORDER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            statusBadgeView.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), badgeBg, null));
        } else {
            statusBadgeView.setBackground(badgeBg);
        }
        statusBadgeView.setClickable(true);
        statusBadgeView.setFocusable(true);
        statusBadgeView.setOnClickListener(v -> showReadinessBottomSheet());

        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        badgeLp.rightMargin = dp(8);
        headerBar.addView(statusBadgeView, badgeLp);

        // 设置齿轮按钮 (46x46dp 靶区)
        settingsButton = new ImageView(this);
        settingsButton.setImageResource(R.drawable.ic_settings);
        settingsButton.setImageTintList(ColorStateList.valueOf(PaseoTheme.COLOR_TEXT));
        settingsButton.setPadding(dp(11), dp(11), dp(11), dp(11));
        settingsButton.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(
                dp(PaseoTheme.TARGET_MIN_DP),
                dp(PaseoTheme.TARGET_MIN_DP)
        );
        headerBar.addView(settingsButton, settingsLp);
        root.addView(headerBar, headerLp);

        // --- 2. 视觉核心：走动冷暖雷达 (HotColdRadarView) ---
        radarView = new HotColdRadarView(this);
        LinearLayout.LayoutParams radarLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(240)
        );
        radarLp.bottomMargin = dp(PaseoTheme.MARGIN_CARD_BOTTOM);
        root.addView(radarView, radarLp);

        // --- 2.5 快捷车控按钮组（【解锁】与【上锁】双按钮） ---
        LinearLayout quickControlRow = new LinearLayout(this);
        quickControlRow.setOrientation(LinearLayout.HORIZONTAL);

        quickUnlockButton = button("解锁", PaseoTheme.COLOR_PRIMARY, PaseoTheme.COLOR_TEXT);
        setButtonCenteredIcon(quickUnlockButton, R.drawable.ic_unlock, "解锁");
        quickUnlockButton.setOnClickListener(v -> performQuickUnlock());

        quickLockButton = button("上锁", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        setButtonCenteredIcon(quickLockButton, R.drawable.ic_lock, "上锁");
        quickLockButton.setOnClickListener(v -> performQuickLock());

        LinearLayout.LayoutParams unlockLp = new LinearLayout.LayoutParams(0, dp(PaseoTheme.TARGET_MIN_DP), 1f);
        unlockLp.rightMargin = dp(6);
        quickControlRow.addView(quickUnlockButton, unlockLp);

        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(0, dp(PaseoTheme.TARGET_MIN_DP), 1f);
        lockLp.leftMargin = dp(6);
        quickControlRow.addView(quickLockButton, lockLp);

        LinearLayout.LayoutParams quickRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        quickRowLp.bottomMargin = dp(PaseoTheme.MARGIN_CARD_BOTTOM);
        root.addView(quickControlRow, quickRowLp);

        // --- 3. 常驻主操作按钮：打开领克 App ---
        openOfficialAppButton = button("打开领克App", PaseoTheme.COLOR_PRIMARY, PaseoTheme.COLOR_TEXT);
        setButtonCenteredIcon(openOfficialAppButton, R.drawable.ic_launch, "打开领克App");
        openOfficialAppButton.setOnClickListener(v -> launchOfficialApp());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(PaseoTheme.TARGET_MIN_DP)
        );
        btnLp.bottomMargin = dp(PaseoTheme.MARGIN_CARD_BOTTOM);
        root.addView(openOfficialAppButton, btnLp);

        // --- 4. 常用动作控制卡片 (极简 3 开关) ---
        LinearLayout controlCard = card();
        controlCard.addView(text("常用动作控制", 16, PaseoTheme.COLOR_PRIMARY_TEXT, Typeface.BOLD));

        serviceMasterSwitch = switchRow("无感进出服务总开关", "关闭后停止一切自动扫描与解闭锁");
        autoUnlockSwitch = switchRow("靠近自动解锁", "到车旁约 2 米达到门限时自动解锁");
        autoLockSwitch = switchRow("离车自动上锁", "远离车辆持续数秒确认后自动上锁");

        controlCard.addView(serviceMasterSwitch);
        controlCard.addView(autoUnlockSwitch);
        controlCard.addView(autoLockSwitch);

        serviceMasterSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (settingSwitches) return;
            if (service != null) {
                service.setServiceEnabled(isChecked);
            } else {
                new VehicleIdentityStore(this).setServiceEnabled(isChecked);
                if (isChecked) startPassiveKeyService();
            }
            refreshUi();
        });

        autoUnlockSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (settingSwitches) return;
            if (service != null) service.setAutoUnlockEnabled(isChecked);
        });

        autoLockSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (settingSwitches) return;
            if (service != null) service.setAutoLockEnabled(isChecked);
        });

        root.addView(controlCard, cardLp());

        setContentView(scrollView);
    }

    private void showReadinessBottomSheet() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }
        if (readinessBottomSheet == null) {
            readinessBottomSheet = new ReadinessBottomSheet(this, new ReadinessBottomSheet.OnActionListener() {
                @Override
                public void onOpenOfficialApp() {
                    launchOfficialApp();
                }

                @Override
                public void onRequestBluetooth() {
                    requestBluetoothPermissions();
                }

                @Override
                public void onOpenNotificationSettings() {
                    try {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } catch (Throwable t) {
                        Toast.makeText(MainActivity.this, "请在系统设置中找到并开启通知使用权", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onRequestBatteryWhitelist() {
                    requestBatteryWhitelist();
                }
            });
        }
        refreshUi();
        if (!readinessBottomSheet.isShowing()) {
            readinessBottomSheet.show();
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQUEST_BT_PERMISSIONS);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_BT_PERMISSIONS);
        }
    }

    private boolean isBatteryWhitelisted() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void requestBatteryWhitelist() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private void refreshUi() {
        PassiveKeyService.Status status = service != null ? service.getStatus() : null;
        boolean geelyInstalled = GeelyBridge.isGeelyInstalled(this);
        boolean btScanGranted = hasBluetoothPermissions();
        boolean notifAccessGranted = KeyNotificationBridgeService.isNotificationListenerEnabled(this);
        boolean notifConnected = KeyNotificationBridgeService.isListenerConnected();
        boolean batteryWhitelisted = isBatteryWhitelisted();

        boolean identityLearned = status != null ? status.identityLearned : false;
        String vehicleName = status != null ? status.vehicleName : "";
        String vehicleAddress = status != null ? status.vehicleAddress : "";
        boolean serviceEnabled = status != null ? status.serviceEnabled : true;
        boolean bluetoothOn = status != null ? status.bluetoothOn : true;
        boolean chargingProtected = status != null ? status.chargingLockBlocked : false;
        boolean geelyConnected = status != null ? status.geelyConnected : false;

        VehicleIdentity fallbackSaved = null;
        if (status == null) {
            VehicleIdentityStore store = new VehicleIdentityStore(this);
            fallbackSaved = store.load();
            if (fallbackSaved != null) {
                identityLearned = true;
                vehicleName = fallbackSaved.name;
                vehicleAddress = fallbackSaved.address;
            }
            serviceEnabled = store.isServiceEnabled();
        }

        ReadinessModel.Snapshot snapshot = readinessModel.evaluate(
                geelyInstalled,
                btScanGranted,
                notifAccessGranted,
                notifConnected,
                identityLearned,
                vehicleName,
                vehicleAddress,
                batteryWhitelisted,
                bluetoothOn,
                serviceEnabled,
                chargingProtected,
                geelyConnected
        );

        // 同步雷达视图
        if (radarView != null) {
            radarView.updateState(status);
        }

        // 同步顶部标题与状态胶囊
        if (status != null) {
            if (status.identityLearned) {
                titleVehicleNameView.setText(status.vehicleName.isEmpty() ? "已识别本车蓝牙" : status.vehicleName);
            } else {
                titleVehicleNameView.setText("未绑定本车 · 点击右上胶囊查看就绪自检");
            }

            statusBadgeView.setText(snapshot.badgeText);
            statusBadgeView.setTextColor(snapshot.badgeColor);

            settingSwitches = true;
            serviceMasterSwitch.setChecked(status.serviceEnabled);
            autoUnlockSwitch.setChecked(status.autoUnlock);
            autoLockSwitch.setChecked(status.autoLock);
            autoUnlockSwitch.setEnabled(status.serviceEnabled);
            autoLockSwitch.setEnabled(status.serviceEnabled);
            settingSwitches = false;
        } else {
            titleVehicleNameView.setText(fallbackSaved != null ? fallbackSaved.name : "领克无感钥匙启动中");
            statusBadgeView.setText(snapshot.badgeText);
            statusBadgeView.setTextColor(snapshot.badgeColor);
        }

        // 抽屉展开时增量更新打勾状态
        if (readinessBottomSheet != null && readinessBottomSheet.isShowing()) {
            readinessBottomSheet.update(snapshot);
        }

        // 更新常驻官方应用按钮状态
        updateOfficialAppButton(
                geelyInstalled,
                status != null ? status.geelyConnected : KeyNotificationBridgeService.isGeelyBleConnected(),
                KeyNotificationBridgeService.isGeelyNotificationPresent()
        );

        // 同步快捷车控按钮可用性（官方钥匙已连接且具备通知通道时激活，未连接置灰）
        boolean canControl = (status != null ? status.geelyConnected : KeyNotificationBridgeService.isGeelyBleConnected())
                && KeyNotificationBridgeService.isNotificationListenerEnabled(this);
        if (quickUnlockButton != null && quickLockButton != null) {
            quickUnlockButton.setEnabled(canControl);
            quickUnlockButton.setAlpha(canControl ? 1.0f : 0.4f);
            quickLockButton.setEnabled(canControl);
            quickLockButton.setAlpha(canControl ? 1.0f : 0.4f);
        }
    }

    private void performQuickUnlock() {
        triggerHapticFeedback();
        KeyNotificationBridgeService.ControlResult result =
                KeyNotificationBridgeService.triggerUnlockViaNotification();
        if (result == KeyNotificationBridgeService.ControlResult.SENT) {
            Toast.makeText(this, "✓ 解锁指令已下发", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "官方钥匙未就绪，请稍候", Toast.LENGTH_SHORT).show();
        }
        refreshUi();
    }

    private void performQuickLock() {
        triggerHapticFeedback();
        KeyNotificationBridgeService.ControlResult result =
                KeyNotificationBridgeService.triggerLockViaNotification();
        if (result == KeyNotificationBridgeService.ControlResult.SENT) {
            if (service != null) service.reportManualLocked();
            Toast.makeText(this, "✓ 上锁指令已下发（走远不再重复闭锁）", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "官方钥匙未就绪，请稍候", Toast.LENGTH_SHORT).show();
        }
        refreshUi();
    }

    private void triggerHapticFeedback() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(30L);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateOfficialAppButton(boolean installed, boolean connected, boolean notificationPresent) {
        if (openOfficialAppButton == null) return;
        if (!installed) {
            openOfficialAppButton.setEnabled(false);
            setButtonCenteredIcon(openOfficialAppButton, 0, "未安装领克官方 App");
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(PaseoTheme.COLOR_SURFACE_4);
            bg.setCornerRadius(dp(PaseoTheme.RADIUS_BUTTON));
            openOfficialAppButton.setBackground(bg);
            openOfficialAppButton.setTextColor(PaseoTheme.COLOR_TEXT_TERTIARY);
            return;
        }
        openOfficialAppButton.setEnabled(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PaseoTheme.COLOR_PRIMARY);
        bg.setCornerRadius(dp(PaseoTheme.RADIUS_BUTTON));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            openOfficialAppButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), bg, null));
        } else {
            openOfficialAppButton.setBackground(bg);
        }
        openOfficialAppButton.setTextColor(PaseoTheme.COLOR_TEXT);
        setButtonCenteredIcon(openOfficialAppButton, R.drawable.ic_launch, "打开领克App");
    }

    private void setButtonCenteredIcon(Button button, int iconRes, String label) {
        if (button == null) return;
        button.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        button.setCompoundDrawablePadding(0);
        button.setAllCaps(false);
        if (iconRes == 0) {
            button.setText(label);
            return;
        }
        Drawable drawable = getDrawable(iconRes);
        if (drawable == null) {
            button.setText(label);
            return;
        }
        drawable = drawable.mutate();
        int size = dp(18);
        drawable.setBounds(0, 0, size, size);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            drawable.setTint(button.getCurrentTextColor());
        }
        SpannableStringBuilder ssb = new SpannableStringBuilder("  " + label);
        ImageSpan span = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ? new ImageSpan(drawable, ImageSpan.ALIGN_CENTER)
                : new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM);
        ssb.setSpan(span, 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(ssb);
    }

    private void launchOfficialApp() {
        GeelyBridge.LaunchResult result = GeelyBridge.launchOfficialApp(this);
        switch (result) {
            case SUCCESS:
                Toast.makeText(this, "正在启动领克官方 App...", Toast.LENGTH_SHORT).show();
                break;
            case NOT_INSTALLED:
                Toast.makeText(this, "未检测到领克官方 App，请先安装", Toast.LENGTH_SHORT).show();
                break;
            case FAILED:
                Toast.makeText(this, "启动失败，请在桌面手动打开领克 App", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(PaseoTheme.PADDING_CARD);
        layout.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PaseoTheme.COLOR_CARD);
        bg.setCornerRadius(dp(PaseoTheme.RADIUS_CARD));
        bg.setStroke(dp(PaseoTheme.BORDER_WIDTH_DP), PaseoTheme.COLOR_BORDER);
        layout.setBackground(bg);
        return layout;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(PaseoTheme.MARGIN_CARD_BOTTOM);
        return lp;
    }

    private Button button(String label, int bgColor, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        button.setTextColor(textColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        if (bgColor == PaseoTheme.COLOR_SURFACE_2) {
            bg.setStroke(dp(PaseoTheme.BORDER_WIDTH_DP), 0xFF3D4643);
        }
        bg.setCornerRadius(dp(PaseoTheme.RADIUS_BUTTON));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), bg, null));
        } else {
            button.setBackground(bg);
        }
        return button;
    }

    private Switch switchRow(String title, String detail) {
        Switch toggle = new Switch(this);
        toggle.setText(title + "\n" + detail);
        toggle.setTextSize(14);
        toggle.setTextColor(PaseoTheme.COLOR_TEXT);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(0, dp(8), 0, dp(8));
        toggle.setThumbTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}
                },
                new int[]{
                        PaseoTheme.COLOR_PRIMARY_TEXT,
                        PaseoTheme.COLOR_SURFACE_4
                }
        ));
        toggle.setTrackTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}
                },
                new int[]{
                        PaseoTheme.COLOR_PRIMARY,
                        PaseoTheme.COLOR_SURFACE_2
                }
        ));
        return toggle;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
