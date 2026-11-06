package com.luca.geelykeyradar;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简无感钥匙前台服务。
 *
 * 只保留：
 * - 息屏 BLE 扫描 / 连接态 RSSI 读取；
 * - 自动学习本车蓝牙身份；
 * - 靠近一次解锁、持续远离上锁；
 * - 通过银河官方通知栏按钮完成车控。
 *
 * 不包含摇一摇、电源键、快捷磁贴、桌面组件、里程、哨兵、语音、手动解闭锁等功能。
 */
public class PassiveKeyService extends Service implements SensorEventListener {

    private static final String TAG = "PassiveKey";
    public static final String ACTION_START = "com.luca.geelykeyradar.action.START_PASSIVE_KEY";

    private static final String CHANNEL_ID = "passive_key_channel";
    private static final int NOTIFICATION_ID = 2027;
    private static final String ACTION_GATT_CONN_CHANGE =
            "android.bluetooth.gatt.profile.action.CONNECTION_STATE_CHANGED";
    private static final long CANDIDATE_TTL_MS = 20000L;
    private static final long GATT_ATTACH_ADV_STOP_MS = 2200L;
    private static final long GATT_RETRY_MS = 4500L;
    private static final long GATT_CONNECT_TIMEOUT_MS = 3500L;
    private static final long GATT_POLL_MS = 500L;
    private static final long SIGNAL_EXPIRE_UI_MS = 6000L;
    private static final long SCAN_RESTART_AFTER_MS = 45000L;
    private static final long SCAN_RESTART_THROTTLE_MS = 90000L;
    private static final long WAKE_LOCK_NEAR_MS = 5 * 60 * 1000L;
    private static final long WAKE_LOCK_BOOTSTRAP_MS = 45000L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final int MAX_UI_LOG_LINES = 10;
    private static final long LOG_FILE_MAX_BYTES = 32 * 1024L;

    private static volatile boolean running = false;

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, GeelyAdvertisement> candidates = new ConcurrentHashMap<>();
    private final ArrayDeque<String> logBuffer = new ArrayDeque<>();
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor();

    private VehicleIdentityStore identityStore;
    private volatile VehicleIdentity identity;
    private volatile PassiveKeyLogic logic = new PassiveKeyLogic();
    private volatile RssiFilter rssiFilter = new RssiFilter();
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private SensorManager sensorManager;
    private Sensor proximitySensor;

    private volatile boolean autoUnlock = true;
    private volatile boolean autoLock = true;
    private volatile boolean serviceEnabled = true;
    private volatile boolean blockWhenCharging = true;
    private volatile boolean wiredCharging = false;
    private volatile float unlockDbm = VehicleIdentityStore.DEFAULT_UNLOCK_DBM;
    private volatile float lockDbm = VehicleIdentityStore.DEFAULT_LOCK_DBM;
    private volatile long lockDwellMs = VehicleIdentityStore.DEFAULT_LOCK_DWELL_MS;
    private volatile boolean isScanning = false;
    private boolean screenOn = true;
    private volatile boolean proximityCovered = false;
    private volatile boolean geelyConnected = false;
    private volatile boolean gattConnected = false;
    private volatile boolean waitForDisconnectToLearn = false;
    private boolean receiverRegistered = false;

    private volatile long lastTargetAdvertAtMs = 0L;
    private long lastScreenOffAtMs = 0L;
    private long lastScanStartAtMs = 0L;
    private long lastScanRestartAtMs = 0L;
    private volatile long lastSampleAtMs = 0L;
    private volatile long lastWakeAttemptAtMs = 0L;
    private long lastGattAttemptAtMs = 0L;
    private volatile long lastGeelyConnectedAtMs = 0L;
    private volatile long lastNotificationUpdateAtMs = 0L;
    private long lastListenerRebindAtMs = 0L;
    private volatile long lastZoneLogAtMs = 0L;
    private volatile long lastLearnAttemptAtMs = 0L;
    private volatile int wakeAttempts = 0;
    private volatile int unlockCount = 0;
    private volatile int lockCount = 0;
    private volatile int lastRawRssi = -127;
    private volatile String lastSource = "";
    private volatile String lastAction = "等待车辆";
    private volatile String lastZone = "等待车辆蓝牙信号";
    private volatile String lastZoneCategory = "";

    private volatile GeelyAdvertisement currentAd;
    private volatile GeelyAdvertisement lastWakeAd;
    private volatile BluetoothDevice lastObservedCarDevice;
    private volatile String lastObservedBleMac = "";
    private volatile long lastAclConnectedAtMs = 0L;
    private volatile long lastSelfGattCloseAtMs = 0L;
    private volatile String lastSelfGattAddress = "";
    private volatile int controlSeq = 0;
    private volatile String primaryCandidateKey = "";
    private volatile BluetoothGatt activeGatt;

    private File logFile;

    public final class LocalBinder extends Binder {
        public PassiveKeyService getService() {
            return PassiveKeyService.this;
        }
    }

    /**
     * 给界面轮询的只读状态快照。
     */
    public static final class Status {
        public boolean running;
        public boolean serviceEnabled;
        public boolean bluetoothOn;
        public boolean scanning;
        public boolean notificationAccess;
        public boolean geelyInstalled;
        public boolean geelyConnected;
        public boolean identityLearned;
        public boolean gattConnected;
        public boolean autoUnlock;
        public boolean autoLock;
        public boolean blockWhenCharging;
        public boolean wiredCharging;
        public float unlockDbm;
        public float lockDbm;
        public long lockDwellMs;
        public int rssi = -127;
        public int unlockCount;
        public int lockCount;
        public long lastSampleAgeMs = -1L;
        public String vehicleName = "";
        public String vehicleAddress = "";
        public String zone = "";
        public String lastAction = "";
        public String source = "";
        public String buttonDiag = "";
    }

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        logFile = new File(getFilesDir(), "passive_key.log");
        createNotificationChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification("正在启动"));
        } catch (Throwable t) {
            Log.w(TAG, "startForeground 延后等待权限: " + t.getMessage());
        }

        identityStore = new VehicleIdentityStore(this);
        identity = identityStore.load();
        serviceEnabled = identityStore.isServiceEnabled();
        blockWhenCharging = identityStore.isBlockWhenChargingEnabled();
        autoUnlock = identityStore.isAutoUnlockEnabled();
        autoLock = identityStore.isAutoLockEnabled();
        unlockDbm = identityStore.getUnlockDbm();
        lockDbm = identityStore.getLockDbm();
        lockDwellMs = identityStore.getLockDwellMs();
        logic.setThresholds(unlockDbm, lockDbm, lockDwellMs);

        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        screenOn = powerManager == null || powerManager.isInteractive();
        if (!screenOn) {
            lastScreenOffAtMs = System.currentTimeMillis();
        }
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeelyPassiveKey:radar");
            wakeLock.setReferenceCounted(false);
        }

        initProximitySensor();
        refreshWiredChargingState(null);
        registerSystemReceiver();
        if (serviceEnabled) {
            GeelyBridge.bindKeepAlive(this);
            KeyNotificationBridgeService.ensureRebound(this);
            startBleScan();
        } else {
            lastZone = "无感进出服务已关闭";
            lastAction = "服务已由总开关暂停";
        }
        handler.post(tickRunnable);

        appendLog(String.format(Locale.US,
                "极简无感钥匙启动 (总开关=%s | 解锁≥%.0fdBm | 上锁≤%.0fdBm / %.1fs)",
                serviceEnabled ? "开" : "关", unlockDbm, lockDbm, lockDwellMs / 1000.0f));
        if (identity != null) {
            appendLog("已加载本车身份: " + identity.displayDetail());
        } else {
            appendLog("本车身份未学习：先靠近车辆，让银河 App 连接一次，本应用会自动记住");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (serviceEnabled && intent != null && ACTION_START.equals(intent.getAction()) && !isScanning) {
            startBleScan();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopBleScan();
        closeGatt("服务停止");
        releaseWakeLock();
        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(this);
            } catch (Throwable ignored) {
            }
        }
        if (receiverRegistered) {
            try {
                unregisterReceiver(systemReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        appendLog("极简无感钥匙停止");
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        logExecutor.shutdown();
        super.onDestroy();
    }

    // ==================== UI 状态 ====================

    public Status getStatus() {
        long now = System.currentTimeMillis();
        boolean signalFresh = lastSampleAtMs > 0L && (now - lastSampleAtMs) <= SIGNAL_EXPIRE_UI_MS;
        Status status = new Status();
        status.running = running;
        status.serviceEnabled = serviceEnabled;
        status.bluetoothOn = bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        status.scanning = isScanning;
        status.notificationAccess = KeyNotificationBridgeService.isNotificationListenerEnabled(this);
        status.geelyInstalled = GeelyBridge.isGeelyInstalled(this);
        status.geelyConnected = geelyConnected;
        status.identityLearned = identity != null;
        status.gattConnected = gattConnected;
        status.autoUnlock = autoUnlock;
        status.autoLock = autoLock;
        status.blockWhenCharging = blockWhenCharging;
        status.wiredCharging = wiredCharging;
        status.unlockDbm = unlockDbm;
        status.lockDbm = lockDbm;
        status.lockDwellMs = lockDwellMs;
        status.rssi = signalFresh ? lastRawRssi : -127;
        status.unlockCount = unlockCount;
        status.lockCount = lockCount;
        status.lastSampleAgeMs = lastSampleAtMs > 0L
                ? Math.max(0L, now - lastSampleAtMs) : -1L;
        status.vehicleName = identity != null ? identity.displayName() : "";
        status.vehicleAddress = identity != null ? identity.address : "";
        status.zone = !serviceEnabled ? "无感进出服务已关闭"
                : (isChargingBlocked() ? "已插充电线 · 禁止自动解闭锁"
                : (signalFresh ? lastZone : "不在车辆附近 (无信号)"));
        status.lastAction = lastAction;
        status.source = signalFresh ? lastSource : "";
        status.buttonDiag = KeyNotificationBridgeService.getLastButtonCaptureDiag();
        return status;
    }

    public String getLogText() {
        synchronized (logBuffer) {
            if (logBuffer.isEmpty()) {
                return "暂无日志";
            }
            StringBuilder sb = new StringBuilder();
            Iterator<String> it = logBuffer.descendingIterator();
            while (it.hasNext()) {
                sb.append(it.next());
                if (it.hasNext()) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }
    }

    /**
     * 清空内存与本地日志。
     */
    public void clearLogs() {
        synchronized (logBuffer) {
            logBuffer.clear();
        }
        try {
            if (logFile != null && logFile.exists()) {
                boolean ignored = logFile.delete();
            }
        } catch (Throwable ignored) {
        }
        appendLog("日志已清空");
    }

    public void setAutoUnlockEnabled(boolean enabled) {
        autoUnlock = enabled;
        identityStore.setAutoUnlockEnabled(enabled);
        appendLog("靠近自动解锁: " + (enabled ? "开" : "关"));
    }

    public void setAutoLockEnabled(boolean enabled) {
        autoLock = enabled;
        identityStore.setAutoLockEnabled(enabled);
        appendLog("远离自动上锁: " + (enabled ? "开" : "关"));
    }

    /**
     * 插充电线后禁止解闭锁开关（USB/AC 有线充电认为人在驾驶室或调试中，无线充电不拦截）。
     */
    public void setBlockWhenChargingEnabled(boolean enabled) {
        blockWhenCharging = enabled;
        identityStore.setBlockWhenChargingEnabled(enabled);
        if (isChargingBlocked()) {
            controlSeq++;
            KeyNotificationBridgeService.cancelPendingActions();
        }
        appendLog("插充电线禁止解闭锁: " + (enabled ? "开" : "关")
                + (wiredCharging ? " (当前已插线)" : ""));
        updateNotificationIfNeeded(true);
    }

    /**
     * 是否因插着 USB/AC 有线充电线而禁止操作车锁。
     */
    private boolean isChargingBlocked() {
        return blockWhenCharging && wiredCharging;
    }

    /**
     * 读取或更新当前是否处于 USB/AC 有线充电状态（无线充电不触发）。
     */
    private void refreshWiredChargingState(Intent batteryIntent) {
        try {
            Intent sticky = batteryIntent;
            if (sticky == null) {
                sticky = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            }
            if (sticky == null) return;
            int plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            boolean wired = (plugged & (BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB)) != 0;
            if (wired != wiredCharging) {
                wiredCharging = wired;
                if (wired && blockWhenCharging) {
                    controlSeq++;
                    KeyNotificationBridgeService.cancelPendingActions();
                    appendLog("🔌 检测到已插充电线：已激活车内禁控保护，禁止自动解锁/上锁");
                } else if (!wired) {
                    appendLog("🔌 充电线已拔出：恢复正常无感解锁/上锁");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 无感进出服务总开关：关闭时立即停止扫描、断开 GATT、取消一切重发队列，防止任何后台操作。
     */
    public void setServiceEnabled(boolean enabled) {
        serviceEnabled = enabled;
        identityStore.setServiceEnabled(enabled);
        controlSeq++;
        KeyNotificationBridgeService.cancelPendingActions();
        if (enabled) {
            logic.reset();
            rssiFilter = new RssiFilter();
            lastAction = "服务已启用，等待车辆";
            lastZone = "不在车辆附近 (无信号)";
            appendLog("✓ 已开启无感进出总开关，恢复 BLE 扫描与自动解闭锁");
            GeelyBridge.bindKeepAlive(this);
            KeyNotificationBridgeService.ensureRebound(this);
            startBleScan();
        } else {
            stopBleScan();
            closeGatt("用户关闭无感进出总开关");
            releaseWakeLock();
            logic.reset();
            lastRawRssi = -127;
            lastSampleAtMs = 0L;
            lastZone = "无感进出服务已关闭";
            lastAction = "已手动停止无感进出服务";
            appendLog("🛑 已关闭无感进出总开关：已停止 BLE 扫描、GATT 与一切自动解闭锁操作");
        }
        updateNotificationIfNeeded(true);
    }

    /**
     * 更新自定义阈值（靠近解锁 dBm、离开上锁 dBm、离开确认毫秒数）。
     */
    public void updateThresholds(float newUnlockDbm, float newLockDbm, long newLockDwellMs) {
        float clampedUnlock = Math.max(-90.0f, Math.min(-40.0f, newUnlockDbm));
        float clampedLock = Math.max(-98.0f, Math.min(clampedUnlock - 6.0f, newLockDbm));
        long clampedDwell = Math.max(1500L, Math.min(20000L, newLockDwellMs));
        this.unlockDbm = clampedUnlock;
        this.lockDbm = clampedLock;
        this.lockDwellMs = clampedDwell;
        identityStore.saveThresholds(clampedUnlock, clampedLock, clampedDwell);
        logic.setThresholds(clampedUnlock, clampedLock, clampedDwell);
        appendLog(String.format(Locale.US,
                "✓ 已更新自定义阈值：靠近解锁 ≥ %.0f dBm | 离开上锁 ≤ %.0f dBm (连续 %.1fs)",
                clampedUnlock, clampedLock, clampedDwell / 1000.0f));
        updateNotificationIfNeeded(true);
    }

    /**
     * 允许用户手动填入车架号后 3 位（例如 "123"）辅助锁定本车，或留空完全走自动识别。
     */
    public void bindByVinSuffix(String vinTail3Input) {
        if (vinTail3Input == null) return;
        String digits = vinTail3Input.replaceAll("[^0-9A-Za-z]", "").trim();
        if (digits.length() > 3) {
            digits = digits.substring(digits.length() - 3);
        }
        if (digits.length() < 2) {
            appendLog("车架号尾号至少需要 2~3 位字符");
            return;
        }
        String tail3 = digits.length() == 3 ? digits : "";
        String tail2 = digits.substring(digits.length() - 2);
        String name = !tail3.isEmpty() ? ("GeelyVehicle" + tail3) : "";
        String addr = (identity != null && !identity.address.isEmpty())
                ? identity.address : lastObservedBleMac;
        identity = new VehicleIdentity(addr, name, tail2, tail3, "", System.currentTimeMillis());
        identityStore.save(identity);
        waitForDisconnectToLearn = false;
        appendLog("✓ 已绑定本车特征: " + identity.displayDetail());
        stopBleScan();
        startBleScan();
        updateNotificationIfNeeded(true);
    }

    public void resetLearnedVehicle() {
        identityStore.clear();
        identity = null;
        currentAd = null;
        lastWakeAd = null;
        lastObservedCarDevice = null;
        lastObservedBleMac = "";
        closeGatt("重新学习本车");
        candidates.clear();
        primaryCandidateKey = "";
        logic.reset();
        rssiFilter = new RssiFilter();
        wakeAttempts = 0;
        lastGeelyConnectedAtMs = 0L;
        lastLearnAttemptAtMs = 0L;
        waitForDisconnectToLearn = geelyConnected;
        lastAction = "已清除本车身份，等待重新学习";
        appendLog("已清除本车蓝牙身份；请靠近车辆并用银河 App 连接一次");
        startBleScan();
    }

    // ==================== BLE 扫描 ====================

    private final Runnable restartScanRunnable = new Runnable() {
        @Override
        public void run() {
            startBleScan();
        }
    };

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startBleScan() {
        if (!serviceEnabled) {
            return;
        }
        if (!hasBluetoothPermission()) {
            appendLog("缺少蓝牙扫描/连接权限，等待授权");
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            appendLog("蓝牙未开启，等待蓝牙可用");
            return;
        }
        try {
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bleScanner == null) {
                handler.removeCallbacks(restartScanRunnable);
                handler.postDelayed(restartScanRunnable, 1000L);
                return;
            }
            if (isScanning) return;
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Throwable ignored) {
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setReportDelay(0L)
                    .build();
            List<ScanFilter> filters = buildHardwareScanFilters();
            try {
                bleScanner.startScan(filters, settings, scanCallback);
            } catch (Throwable filterErr) {
                bleScanner.startScan(null, settings, scanCallback);
            }
            isScanning = true;
            lastScanStartAtMs = System.currentTimeMillis();
            appendLog("开始硬件过滤低延迟扫描（支持息屏扫描与本车自动识别）");
            updateNotificationIfNeeded(true);
        } catch (SecurityException se) {
            appendLog("蓝牙扫描权限不足: " + se.getMessage());
        } catch (Throwable t) {
            appendLog("启动 BLE 扫描失败: " + t.getMessage());
            handler.removeCallbacks(restartScanRunnable);
            handler.postDelayed(restartScanRunnable, 2000L);
        }
    }

    /**
     * 构建硬件级 BLE ScanFilter 列表（OR 关系）。
     *
     * Android 在息屏时会挂起无 Filter 的扫描（Cannot start unfiltered scan in screen-off）。
     * 使用吉利/极氪通用广播特征作为硬件过滤，既能对所有吉利银河车型通用，又能保证息屏持续扫描。
     */
    private List<ScanFilter> buildHardwareScanFilters() {
        List<ScanFilter> filters = new ArrayList<>();
        try {
            // 1. 吉利/UAES V2 厂商广播 Company ID 0x01FE (510)
            filters.add(new ScanFilter.Builder().setManufacturerData(510, new byte[]{}).build());
            // 2. 吉利/极氪通用广播 Service UUID 0xFDFD
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString("0000fdfd-0000-1000-8000-00805f9b34fb"))
                    .build());
            // 3. 吉利/极氪 0x06FE (1790) 厂商数据兼容
            filters.add(new ScanFilter.Builder().setManufacturerData(1790, new byte[]{}).build());
            // 4. 若已学习本车名称或 MAC，加入精确硬件过滤
            VehicleIdentity id = this.identity;
            if (id != null) {
                if (!id.name.isEmpty()) {
                    filters.add(new ScanFilter.Builder().setDeviceName(id.name).build());
                }
                if (!id.address.isEmpty() && BluetoothAdapter.checkBluetoothAddress(id.address)) {
                    filters.add(new ScanFilter.Builder().setDeviceAddress(id.address).build());
                }
            }
        } catch (Throwable ignored) {
        }
        return filters.isEmpty() ? null : filters;
    }

    private void stopBleScan() {
        handler.removeCallbacks(restartScanRunnable);
        if (bleScanner != null && isScanning) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Throwable ignored) {
            }
        }
        isScanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!serviceEnabled) return;
            GeelyAdvertisement ad = GeelyAdvertisement.from(result);
            if (ad == null) return;
            long now = System.currentTimeMillis();
            pruneCandidates(now);
            candidates.put(ad.stableKey(), ad);

            if (identity != null) {
                if (identity.matches(ad)) {
                    VehicleIdentity merged = identity.mergeWith(ad);
                    if (merged != identity) {
                        identity = merged;
                        identityStore.save(identity);
                        appendLog("已更新本车蓝牙特征: " + identity.displayDetail());
                    }
                    if (result.getDevice() != null) {
                        lastObservedCarDevice = result.getDevice();
                    }
                    if (!ad.address.isEmpty()) {
                        lastObservedBleMac = ad.address;
                    }
                    handleTargetAdvertisement(ad, now);
                }
                return;
            }

            // 尚未学习本车时：如果只有单一吉利车广播且带明确车名或 01FE 帧，直接自动学习并接管！
            if (!waitForDisconnectToLearn
                    && (!ad.name.isEmpty() || !ad.vinTail2.isEmpty())
                    && candidates.size() == 1
                    && ad.rssi >= -88) {
                identity = VehicleIdentity.from(ad);
                identityStore.save(identity);
                if (result.getDevice() != null) {
                    lastObservedCarDevice = result.getDevice();
                }
                lastObservedBleMac = ad.address;
                appendLog("✓ 扫到本车特征广播，已自动锁定: " + identity.displayDetail());
            }

            GeelyAdvertisement primary = choosePrimaryCandidate(now);
            if (primary != null && primary.stableKey().equals(ad.stableKey())) {
                if (result.getDevice() != null) {
                    lastObservedCarDevice = result.getDevice();
                }
                if (!ad.address.isEmpty()) {
                    lastObservedBleMac = ad.address;
                }
                handleTargetAdvertisement(ad, now);
            }

            if (geelyConnected || (lastGeelyConnectedAtMs > 0L && (now - lastGeelyConnectedAtMs) < 60000L)) {
                maybeLearnVehicle(now);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                isScanning = true;
                return;
            }
            isScanning = false;
            logic.onSignalLost(System.currentTimeMillis());
            long delay = (errorCode == ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY) ? 6500L : 1800L;
            appendLog("BLE 扫描失败 errorCode=" + errorCode + "，" + delay + "ms 后重试");
            handler.removeCallbacks(restartScanRunnable);
            handler.postDelayed(restartScanRunnable, delay);
        }
    };

    private void pruneCandidates(long now) {
        Iterator<Map.Entry<String, GeelyAdvertisement>> iterator = candidates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, GeelyAdvertisement> entry = iterator.next();
            GeelyAdvertisement ad = entry.getValue();
            if (ad == null || (now - ad.seenAtMs) > CANDIDATE_TTL_MS) {
                iterator.remove();
            }
        }
    }

    private GeelyAdvertisement strongestCandidate(long now) {
        pruneCandidates(now);
        GeelyAdvertisement best = null;
        for (GeelyAdvertisement ad : candidates.values()) {
            if (ad == null) continue;
            if (best == null || ad.rssi > best.rssi
                    || (ad.rssi == best.rssi && ad.seenAtMs > best.seenAtMs)) {
                best = ad;
            }
        }
        return best;
    }

    /**
     * 身份尚未学习时固定一个主候选 12 秒。
     *
     * 附近同时有两辆吉利车时，不能把两辆车的 RSSI 混入同一个滤波器；先跟随最强的一辆，
     * 等官方 App 真正连上后再由连接事件决定哪一辆是本车。
     */
    private GeelyAdvertisement choosePrimaryCandidate(long now) {
        pruneCandidates(now);
        if (!primaryCandidateKey.isEmpty()) {
            GeelyAdvertisement current = candidates.get(primaryCandidateKey);
            if (current != null && (now - current.seenAtMs) <= 12000L) {
                return current;
            }
        }
        GeelyAdvertisement best = strongestCandidate(now);
        primaryCandidateKey = best != null ? best.stableKey() : "";
        return best;
    }

    private void handleTargetAdvertisement(GeelyAdvertisement ad, long now) {
        currentAd = ad;
        lastTargetAdvertAtMs = now;
        onFreshRssi(ad.rssi, "广播 " + ad.sourceTag, ad);
        if (!geelyConnected) {
            maybeWakeGeely(ad, now);
        }
        if (identity == null && geelyConnected) {
            maybeLearnVehicle(now);
        }
    }

    // ==================== 唤醒官方 App ====================

    private void maybeWakeGeely(GeelyAdvertisement ad, long now) {
        if (ad == null || geelyConnected) return;
        if (ad.rssi < -95) return;
        long throttle = ad.rssi >= -88 ? 1500L : 2500L;
        if ((now - lastWakeAttemptAtMs) < throttle) return;
        if (wakeAttempts >= 6 && (now - lastWakeAttemptAtMs) < 5000L) return;

        lastWakeAttemptAtMs = now;
        lastWakeAd = ad;
        wakeAttempts++;
        GeelyBridge.wakeAndConnect(this, ad.scanResult, ad.carId());
        appendLog("注入广播唤醒银河 #" + wakeAttempts + " · " + ad.displayDetail()
                + " · " + ad.rssi + "dBm");
    }

    private void maybeLearnVehicle(long now) {
        if (identity != null) return;
        if (waitForDisconnectToLearn && geelyConnected) return;
        if ((now - lastLearnAttemptAtMs) < 10000L) return;
        lastLearnAttemptAtMs = now;

        // GATT 连接列表最接近“官方 App 真正连上的那辆车”，优先使用。
        VehicleIdentity gattIdentity = findIdentityFromConnectedGatt();
        if (gattIdentity != null) {
            identity = gattIdentity;
            identityStore.save(identity);
            primaryCandidateKey = "";
            appendLog("✓ 已从系统 GATT 连接识别本车蓝牙: " + identity.displayDetail());
            updateNotificationIfNeeded(true);
            return;
        }

        GeelyAdvertisement ad = null;
        if (lastWakeAd != null && (now - lastWakeAd.seenAtMs) <= 15000L) {
            ad = lastWakeAd;
        }
        if (ad != null) {
            identity = VehicleIdentity.from(ad);
            identityStore.save(identity);
            primaryCandidateKey = "";
            appendLog("✓ 已自动识别本车蓝牙: " + identity.displayDetail());
            updateNotificationIfNeeded(true);
            return;
        }

        pruneCandidates(now);
        if (candidates.size() > 1) {
            appendLog("附近发现 " + candidates.size()
                    + " 辆吉利车，暂不自动绑定；请靠近本车，或手动点“重新学习本车”");
            return;
        }
        ad = strongestCandidate(now);
        if (ad != null) {
            identity = VehicleIdentity.from(ad);
            identityStore.save(identity);
            primaryCandidateKey = "";
            appendLog("✓ 已自动识别本车蓝牙: " + identity.displayDetail());
            updateNotificationIfNeeded(true);
            return;
        }

        if (!lastObservedBleMac.isEmpty()) {
            String devName = "";
            try {
                if (lastObservedCarDevice != null && lastObservedCarDevice.getName() != null) {
                    devName = lastObservedCarDevice.getName();
                }
            } catch (Throwable ignored) {
            }
            String tail3 = GeelyAdvertisement.vinTailFromName(devName);
            String tail2 = tail3.length() >= 2 ? tail3.substring(tail3.length() - 2) : "";
            identity = new VehicleIdentity(lastObservedBleMac, devName, tail2, tail3, "", now);
            identityStore.save(identity);
            primaryCandidateKey = "";
            appendLog("✓ 已从系统 BLE 连接事件自动识别本车: " + identity.displayDetail());
            updateNotificationIfNeeded(true);
            return;
        }

        appendLog("银河已连接，但暂未捕获本车广播；下次靠近会自动学习");
    }

    private VehicleIdentity findIdentityFromConnectedGatt() {
        List<BluetoothDevice> devices = GeelyBridge.getConnectedGattDevices(this);
        for (BluetoothDevice device : devices) {
            if (device == null) continue;
            String address = device.getAddress();
            String name = "";
            try {
                name = device.getName();
            } catch (Throwable ignored) {
            }
            if (!name.isEmpty() && GeelyAdvertisement.isGeelyVehicleName(name)) {
                return new VehicleIdentity(
                        address,
                        name,
                        "",
                        GeelyAdvertisement.vinTailFromName(name),
                        "",
                        System.currentTimeMillis()
                );
            }
            for (GeelyAdvertisement ad : candidates.values()) {
                if (ad != null && ad.address.equalsIgnoreCase(address)) {
                    return VehicleIdentity.from(ad);
                }
            }
        }
        return null;
    }

    // ==================== RSSI 与状态机 ====================

    private void onFreshRssi(int rawDbm, String source, GeelyAdvertisement ad) {
        if (!serviceEnabled) return;
        if (rawDbm <= -105 || rawDbm >= 0) return;
        long now = System.currentTimeMillis();
        float smooth = rssiFilter.update(rawDbm, now);
        float effective = smooth + (proximityCovered ? 8.0f : 0.0f);

        lastRawRssi = rawDbm;
        lastSampleAtMs = now;
        lastSource = source;
        if (ad != null) currentAd = ad;

        boolean chargingBlocked = isChargingBlocked();
        boolean driving = isDriving(now) || chargingBlocked;
        PassiveKeyLogic.Action action = logic.onSample(
                effective,
                now,
                geelyConnected,
                driving,
                autoUnlock && !chargingBlocked,
                autoLock && !chargingBlocked
        );

        if (action == PassiveKeyLogic.Action.UNLOCK) {
            handleUnlock(effective, source, ad);
        } else if (action == PassiveKeyLogic.Action.LOCK) {
            handleLock(effective, source, ad);
        }

        String zone = zoneText(effective);
        String category = zoneCategory(effective);
        boolean categoryChanged = !category.equals(lastZoneCategory);
        lastZoneCategory = category;
        lastZone = zone;
        // 仅在信号真正跨越区间边界时记录一条日志，不在同一区间内无限刷屏
        if (categoryChanged) {
            lastZoneLogAtMs = now;
            appendLog(String.format(Locale.US,
                    "进入%s | 原始 %d dBm / 有效 %.1f dBm | %s%s",
                    category,
                    rawDbm,
                    effective,
                    geelyConnected ? "银河已连接" : "银河未连接",
                    driving ? " | 行驶中禁锁" : ""));
        }
        updateNotificationIfNeeded(false);
    }

    private String zoneCategory(float effectiveDbm) {
        if (effectiveDbm >= unlockDbm) return "靠近解锁区";
        if (effectiveDbm <= lockDbm) return "远离上锁区";
        return "车旁安全区";
    }

    private String zoneText(float effectiveDbm) {
        if (isChargingBlocked()) {
            return "已插充电线 · 禁止解闭锁";
        }
        if (lastSampleAtMs == 0L || (System.currentTimeMillis() - lastSampleAtMs) > SIGNAL_EXPIRE_UI_MS) {
            return "不在车辆附近 (无信号)";
        }
        if (effectiveDbm >= unlockDbm) {
            return String.format(Locale.US, "靠近解锁区 (%.1f dBm)", effectiveDbm);
        }
        if (effectiveDbm <= lockDbm) {
            return String.format(Locale.US, "远离上锁区 (%.1f dBm)", effectiveDbm);
        }
        return String.format(Locale.US, "车旁安全区 (%.1f dBm)", effectiveDbm);
    }

    private void handleUnlock(float effectiveDbm, String source, GeelyAdvertisement ad) {
        if (!serviceEnabled) return;
        if (isChargingBlocked()) {
            appendLog("🔌 已插充电线，拦截本次自动解锁");
            return;
        }
        unlockCount++;
        final int seq = ++controlSeq;
        // 清掉上一轮可能残留的通知栏挂起动作，防止重复触发
        KeyNotificationBridgeService.cancelPendingActions();
        boolean fired = dispatchControlOnce(GeelyBridge.CTRL_UNLOCK, ad);
        if (!fired) {
            // 只有首次未点中通知栏（银河尚未连上，等待 V2 3.07s 鉴权）时才启动护航重发
            scheduleControlRetries(seq, GeelyBridge.CTRL_UNLOCK, ad);
        }
        lastAction = fired ? "已发送解锁" : "解锁已挂起，正在唤醒银河并等待V2鉴权";
        appendLog(String.format(Locale.US,
                "★ 靠近自动解锁 #%d · 有效 %.1f dBm · %s · %s",
                unlockCount, effectiveDbm, source, lastAction));
        updateNotificationIfNeeded(true);
    }

    private void handleLock(float effectiveDbm, String source, GeelyAdvertisement ad) {
        if (!serviceEnabled) return;
        if (isChargingBlocked()) {
            appendLog("🔌 已插充电线，拦截本次自动上锁");
            return;
        }
        long now = System.currentTimeMillis();
        if (lastSampleAtMs == 0L || (now - lastSampleAtMs) > PassiveKeyLogic.SIGNAL_STALE_MS) {
            appendLog("自动上锁已取消：没有新鲜 RSSI 样本");
            return;
        }
        if (isDriving(now)) {
            appendLog("自动上锁已取消：银河通知显示行驶中");
            return;
        }

        lockCount++;
        final int seq = ++controlSeq;
        // 清掉上一轮可能残留的通知栏挂起动作，防止重复触发二次上锁
        KeyNotificationBridgeService.cancelPendingActions();
        boolean fired = dispatchControlOnce(GeelyBridge.CTRL_LOCK, ad);
        if (!fired) {
            // 只有当首次点击时银河已断连（未能直接点中通知栏按钮），才启动重连护航；
            // 若首次点击已在连接态成功点中通知栏按钮，绝不再重发，避免出现两次上锁！
            scheduleControlRetries(seq, GeelyBridge.CTRL_LOCK, ad);
        }
        lastAction = fired ? "已发送上锁" : "上锁已挂起，正在唤醒银河并等待V2鉴权";
        appendLog(String.format(Locale.US,
                "★ 远离自动上锁 #%d · 有效 %.1f dBm · %s · %s",
                lockCount, effectiveDbm, source, lastAction));
        updateNotificationIfNeeded(true);
    }

    /**
     * 单次执行解锁或上锁：优先点击银河通知栏按钮；若按钮尚未就绪，则发送 DK1.5 广播并唤醒银河。
     */
    private boolean dispatchControlOnce(int ctrlType, GeelyAdvertisement ad) {
        KeyNotificationBridgeService.ensureRebound(this);
        boolean fired = (ctrlType == GeelyBridge.CTRL_UNLOCK)
                ? KeyNotificationBridgeService.triggerUnlockViaNotification()
                : KeyNotificationBridgeService.triggerLockViaNotification();
        if (!fired) {
            GeelyBridge.sendControlFallback(this, ctrlType);
            if (ad != null) {
                maybeWakeGeely(ad, System.currentTimeMillis());
            } else if (currentAd != null) {
                maybeWakeGeely(currentAd, System.currentTimeMillis());
            }
        }
        return fired;
    }

    /**
     * 跨越吉利银河 V2 蓝牙 3.07 秒鉴权窗口的多阶护航重发。
     * 仅在首次触发时银河尚未连接（通知栏按钮尚未就绪）的情况下调用；
     * 一旦某次重发成功点中通知栏按钮，立即终止后续重发，杜绝二次解闭锁。
     */
    private void scheduleControlRetries(final int seq, final int ctrlType, final GeelyAdvertisement ad) {
        long[] delays = new long[]{1200L, 2500L, 3600L, 4800L, 6200L};
        final String label = (ctrlType == GeelyBridge.CTRL_UNLOCK) ? "解锁" : "上锁";
        for (int i = 0; i < delays.length; i++) {
            final int attempt = i + 1;
            final long delayMs = delays[i];
            handler.postDelayed(() -> {
                if (!serviceEnabled || seq != controlSeq) return;
                if (isChargingBlocked()
                        || (ctrlType == GeelyBridge.CTRL_LOCK && isDriving(System.currentTimeMillis()))) {
                    controlSeq++;
                    return;
                }
                boolean ok = dispatchControlOnce(ctrlType, ad);
                appendLog("🔁 V2鉴权窗口护航重发 #" + attempt + " (+" + (delayMs / 1000.0f) + "s): "
                        + label + (ok ? " [通知栏已点，完成]" : " [等待连接/广播兜底]"));
                if (ok) {
                    // 已成功点击通知栏按钮，立即取消后续重发，防止触发两次
                    controlSeq++;
                }
            }, delayMs);
        }
    }

    private boolean isDriving(long now) {
        String park = KeyNotificationBridgeService.getLastObservedParkStatus();
        long observedAt = KeyNotificationBridgeService.getLastObservedParkStatusAtMs();
        return "行驶中".equals(park)
                && observedAt > 0L
                && (now - observedAt) < 90000L;
    }

    // ==================== 官方连接状态 ====================

    private void onGeelyConnectionChanged(boolean connected, long now) {
        geelyConnected = connected;
        if (connected) {
            lastGeelyConnectedAtMs = now;
            wakeAttempts = 0;
            appendLog("✓ 银河蓝牙钥匙已连接");
            maybeLearnVehicle(now);
            boolean blocked = isDriving(now) || isChargingBlocked();
            PassiveKeyLogic.Action action = logic.onOfficialConnected(now, blocked, autoUnlock && !blocked);
            if (action == PassiveKeyLogic.Action.UNLOCK) {
                handleUnlock(lastRawRssi > -127 ? lastRawRssi : -60.0f, "银河蓝牙连接即刻解锁", currentAd);
            }
        } else {
            appendLog("银河蓝牙钥匙已断开");
            waitForDisconnectToLearn = false;
            closeGatt("银河蓝牙已断开");
            logic.onOfficialDisconnected(now);
        }
        updateNotificationIfNeeded(true);
    }

    // ==================== GATT 连接态 RSSI ====================

    private final Runnable gattConnectTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeGatt != null && !gattConnected) {
                closeGatt("3.5s 未连上已建立的 ACL，释放连接尝试");
            }
        }
    };

    private void ensureGattAttached(long now) {
        if (!serviceEnabled) return;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        if (activeGatt != null) {
            if (gattConnected) {
                try {
                    activeGatt.readRemoteRssi();
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        if ((now - lastGattAttemptAtMs) < 8000L) return;

        // 严格限制挂载连接态 RSSI 的条件（防止在车外盲目 connectGatt 抢占车机唯一广播槽位）：
        // 只有当银河通知栏明确已连接，或刚刚收到真实 ACL_CONNECTED 事件时，才挂载共享 ACL！
        boolean aclRecent = lastAclConnectedAtMs > 0L
                && (now - lastAclConnectedAtMs) >= 350L
                && (now - lastAclConnectedAtMs) <= 15000L;
        if (!geelyConnected && !aclRecent) {
            return;
        }

        String address = resolveGattAddress();
        BluetoothDevice device = null;
        if (lastObservedCarDevice != null
                && (address.isEmpty() || address.equalsIgnoreCase(lastObservedCarDevice.getAddress()))) {
            device = lastObservedCarDevice;
            address = device.getAddress();
        }
        if (device == null) {
            if (address.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(address)) return;
            device = bluetoothAdapter.getRemoteDevice(address);
        }
        try {
            lastSelfGattAddress = address;
            activeGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            lastGattAttemptAtMs = now;
            handler.removeCallbacks(gattConnectTimeoutRunnable);
            handler.postDelayed(gattConnectTimeoutRunnable, GATT_CONNECT_TIMEOUT_MS);
            appendLog("挂载连接态 RSSI 读取通道: " + address);
        } catch (Throwable t) {
            appendLog("挂载 GATT RSSI 通道失败: " + t.getMessage());
        }
    }

    private String resolveGattAddress() {
        // 车机蓝牙地址可能轮换。先用系统 GATT 连接列表按名称/候选地址校正，
        // 再回退到已保存地址；否则地址变化后会一直挂不上 RSSI 通道。
        for (BluetoothDevice device : GeelyBridge.getConnectedGattDevices(this)) {
            if (device == null) continue;
            String address = device.getAddress();
            String name = "";
            try {
                name = device.getName();
            } catch (Throwable ignored) {
            }
            if (!name.isEmpty() && GeelyAdvertisement.isGeelyVehicleName(name)) {
                if (identity == null) {
                    String t3 = GeelyAdvertisement.vinTailFromName(name);
                    String t2 = t3.length() >= 2 ? t3.substring(t3.length() - 2) : "";
                    identity = new VehicleIdentity(address, name, t2, t3, "", System.currentTimeMillis());
                    identityStore.save(identity);
                } else if (identity.name.isEmpty() || name.equalsIgnoreCase(identity.name)) {
                    identity = identity.withAddress(address);
                    identityStore.save(identity);
                }
                lastObservedCarDevice = device;
                lastObservedBleMac = address;
                return address;
            }
            for (GeelyAdvertisement ad : candidates.values()) {
                if (ad != null && ad.address.equalsIgnoreCase(address)) {
                    if (identity != null) {
                        identity = identity.withAddress(address);
                        identityStore.save(identity);
                    }
                    lastObservedCarDevice = device;
                    lastObservedBleMac = address;
                    return address;
                }
            }
        }
        if (!lastObservedBleMac.isEmpty()) return lastObservedBleMac;
        if (identity == null) return "";
        if (!identity.address.isEmpty()) return identity.address;
        return "";
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.removeCallbacks(gattConnectTimeoutRunnable);
                gattConnected = true;
                geelyConnected = true;
                appendLog("✓ 已挂载车机 ACL，开始读取连接态 RSSI");
                handler.removeCallbacks(gattRssiPollRunnable);
                handler.post(gattRssiPollRunnable);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(gattConnectTimeoutRunnable);
                gattConnected = false;
                handler.removeCallbacks(gattRssiPollRunnable);
                // 官方钥匙仍在线时，GATT 断开更像链路闪断，短暂禁止误锁；
                // 官方钥匙已断开则很可能是人已走远，让广播重新接管，避免无谓延迟上锁。
                if (KeyNotificationBridgeService.isGeelyBleConnected()) {
                    logic.onSignalLost(System.currentTimeMillis());
                }
                closeGatt("GATT 连接断开");
            }
            updateNotificationIfNeeded(true);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onFreshRssi(rssi, "GATT连接态", currentAd);
            }
        }
    };

    private final Runnable gattRssiPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeGatt == null || !gattConnected) return;
            try {
                activeGatt.readRemoteRssi();
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, GATT_POLL_MS);
        }
    };

    private void closeGatt(String reason) {
        handler.removeCallbacks(gattConnectTimeoutRunnable);
        handler.removeCallbacks(gattRssiPollRunnable);
        gattConnected = false;
        lastAclConnectedAtMs = 0L;
        lastSelfGattCloseAtMs = System.currentTimeMillis();
        if (activeGatt != null) {
            try {
                activeGatt.disconnect();
            } catch (Throwable ignored) {
            }
            try {
                activeGatt.close();
            } catch (Throwable ignored) {
            }
            activeGatt = null;
        }
        if (!reason.isEmpty()) {
            Log.i(TAG, "关闭 GATT: " + reason);
        }
    }

    // ==================== 息屏保活 ====================

    private void refreshWakeLock(long now) {
        if (wakeLock == null) return;
        boolean need = !screenOn && (
                geelyConnected
                        || gattConnected
                        || (lastTargetAdvertAtMs > 0L && (now - lastTargetAdvertAtMs) < WAKE_LOCK_NEAR_MS)
                        || (lastScreenOffAtMs > 0L && (now - lastScreenOffAtMs) < WAKE_LOCK_BOOTSTRAP_MS)
        );
        try {
            if (need) {
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
                    appendLog("息屏保活：已持有 PARTIAL_WAKE_LOCK");
                }
            } else if (wakeLock.isHeld()) {
                wakeLock.release();
                appendLog("息屏保活：已释放 PARTIAL_WAKE_LOCK");
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void runScreenOffScanWatchdog(long now) {
        if (screenOn || !isScanning || geelyConnected) return;
        long lastSignalAt = Math.max(lastTargetAdvertAtMs, lastScanStartAtMs);
        if ((now - lastSignalAt) < SCAN_RESTART_AFTER_MS) return;
        if ((now - lastScanRestartAtMs) < SCAN_RESTART_THROTTLE_MS) return;
        lastScanRestartAtMs = now;
        appendLog("息屏扫描看门狗：45s 无车机广播，重建 BLE 扫描器");
        stopBleScan();
        startBleScan();
    }

    // ==================== 系统广播 / 传感器 / 定时 ====================

    private void initProximitySensor() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return;
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximitySensor != null) {
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null) return;
        if (event.sensor.getType() != Sensor.TYPE_PROXIMITY) return;
        float maxRange = proximitySensor != null ? proximitySensor.getMaximumRange() : 5.0f;
        boolean covered = event.values[0] < Math.min(3.0f, maxRange);
        if (covered != proximityCovered) {
            proximityCovered = covered;
            appendLog(covered ? "检测到手机遮挡/揣兜，启用 +8dBm 补偿" : "手机遮挡解除");
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void registerSystemReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            filter.addAction(ACTION_GATT_CONN_CHANGE);
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(systemReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            long now = System.currentTimeMillis();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    appendLog("系统蓝牙已开启，重建扫描");
                    // 蓝牙重新开启后允许重新判定一次靠近；冷启动远处仍不会补发上锁。
                    logic.reset();
                    startBleScan();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    appendLog("系统蓝牙已关闭，暂停扫描并禁止误锁");
                    stopBleScan();
                    closeGatt("蓝牙关闭");
                    logic.onBluetoothReset(now);
                }
            } else if (ACTION_GATT_CONN_CHANGE.equals(action)) {
                handleGattProfileBroadcast(intent, now);
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                handleAclBroadcast(device, true, now);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                handleAclBroadcast(device, false, now);
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                refreshWiredChargingState(intent);
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)
                    || Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                refreshWiredChargingState(null);
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOn = false;
                lastScreenOffAtMs = now;
                refreshWakeLock(now);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOn = true;
                releaseWakeLock();
                if (!isScanning && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    startBleScan();
                }
            }
            updateNotificationIfNeeded(true);
        }
    };

    /**
     * 处理系统级 BLE GATT 连接状态广播 (android.bluetooth.gatt.profile.action.CONNECTION_STATE_CHANGED)。
     *
     * 当银河 App 的 :dkservice 连接或断开车机 BLE 时，系统会广播：
     * device_type="ble", bd_addr="<车机当前MAC>", status=<状态>。
     * 即使车机蓝牙模块重启轮换了 MAC（且因已连接而停止广播），也能 0 秒捕获最新车机 MAC。
     */
    private void handleGattProfileBroadcast(Intent intent, long now) {
        if (!serviceEnabled) return;
        try {
            String devType = intent.getStringExtra("device_type");
            if (devType != null && !"ble".equalsIgnoreCase(devType)) return;
            String bdAddr = intent.getStringExtra("bd_addr");
            if (bdAddr == null || !BluetoothAdapter.checkBluetoothAddress(bdAddr)) return;
            // 忽略我们自己刚刚主动关闭/超时释放的 GATT 触发的回声广播，彻底杜绝自触发死循环！
            if (bdAddr.equalsIgnoreCase(lastSelfGattAddress) && (now - lastSelfGattCloseAtMs) < 5000L) {
                return;
            }
            // 只在银河通知栏处于已连接状态时，才采信该 BLE GATT 地址为本车新地址
            if (!KeyNotificationBridgeService.isGeelyBleConnected()) {
                return;
            }
            BluetoothDevice dev = null;
            try {
                if (bluetoothAdapter != null) {
                    dev = bluetoothAdapter.getRemoteDevice(bdAddr);
                }
            } catch (Throwable ignored) {
            }
            String devName = "";
            try {
                if (dev != null && dev.getName() != null) {
                    devName = dev.getName();
                }
            } catch (Throwable ignored) {
            }
            lastObservedBleMac = bdAddr;
            if (dev != null) lastObservedCarDevice = dev;
            lastAclConnectedAtMs = now;

            if (identity == null && !waitForDisconnectToLearn) {
                String t3 = GeelyAdvertisement.vinTailFromName(devName);
                String t2 = t3.length() >= 2 ? t3.substring(t3.length() - 2) : "";
                identity = new VehicleIdentity(bdAddr, devName, t2, t3, "", now);
                identityStore.save(identity);
                appendLog("✓ 捕获系统 BLE GATT 车机地址: " + identity.displayDetail());
            } else if (identity != null && !bdAddr.equalsIgnoreCase(identity.address)) {
                if (devName.isEmpty() || identity.name.isEmpty() || devName.equalsIgnoreCase(identity.name)) {
                    identity = identity.withAddress(bdAddr);
                    identityStore.save(identity);
                    appendLog("✓ 捕获车机 BLE 新地址: " + identity.displayDetail());
                }
            }
            handler.postDelayed(() -> ensureGattAttached(System.currentTimeMillis()), 500L);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 处理系统 ACL 连接/断开广播。
     */
    private void handleAclBroadcast(BluetoothDevice device, boolean connected, long now) {
        if (!serviceEnabled) return;
        if (device == null) return;
        String addr = device.getAddress();
        if (addr == null || addr.isEmpty()) return;
        String name = "";
        try {
            if (device.getName() != null) name = device.getName();
        } catch (Throwable ignored) {
        }
        boolean isGeelyName = GeelyAdvertisement.isGeelyVehicleName(name);
        boolean isKnownId = identity != null
                && ((!identity.address.isEmpty() && addr.equalsIgnoreCase(identity.address))
                || (!identity.name.isEmpty() && name.equalsIgnoreCase(identity.name)));
        boolean inCandidates = false;
        for (GeelyAdvertisement ad : candidates.values()) {
            if (ad != null && addr.equalsIgnoreCase(ad.address)) {
                inCandidates = true;
                break;
            }
        }
        if (!isGeelyName && !isKnownId && !inCandidates) {
            return;
        }
        lastObservedCarDevice = device;
        lastObservedBleMac = addr;
        if (connected) {
            lastAclConnectedAtMs = now;
            if (identity == null && !waitForDisconnectToLearn) {
                String t3 = GeelyAdvertisement.vinTailFromName(name);
                String t2 = t3.length() >= 2 ? t3.substring(t3.length() - 2) : "";
                identity = new VehicleIdentity(addr, name, t2, t3, "", now);
                identityStore.save(identity);
                appendLog("✓ 从 ACL 连接自动锁定本车: " + identity.displayDetail());
            } else if (identity != null && !addr.equalsIgnoreCase(identity.address)) {
                identity = identity.withAddress(addr);
                identityStore.save(identity);
            }
            appendLog("✓ 车机底层 ACL 已连接: " + (name.isEmpty() ? addr : (name + " · " + addr)));
            handler.postDelayed(() -> ensureGattAttached(System.currentTimeMillis()), 550L);
        } else {
            appendLog("车机底层 ACL 已断开: " + (name.isEmpty() ? addr : (name + " · " + addr)));
            logic.onSignalLost(now);
            closeGatt("底层 ACL 断开");
            lastRawRssi = -127;
            lastSampleAtMs = 0L;
            lastZone = "不在车辆附近 (无信号)";
        }
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (!serviceEnabled) {
                lastRawRssi = -127;
                lastZone = "无感进出服务已关闭";
                updateNotificationIfNeeded(false);
                handler.postDelayed(this, 1000L);
                return;
            }
            // 超过 6 秒无新鲜 RSSI 样本时，立即清除陈旧信号值，避免离开车辆后仍显示 -93 dBm
            if (lastSampleAtMs > 0L && (now - lastSampleAtMs) > SIGNAL_EXPIRE_UI_MS) {
                lastRawRssi = -127;
                lastZone = "不在车辆附近 (无信号)";
                lastZoneCategory = "";
                lastSource = "";
            }
            if ((now - lastListenerRebindAtMs) > 60000L) {
                lastListenerRebindAtMs = now;
                if (KeyNotificationBridgeService.isNotificationListenerEnabled(PassiveKeyService.this)
                        && KeyNotificationBridgeService.getInstance() == null) {
                    appendLog("通知监听服务未绑定，主动请求系统重绑");
                    KeyNotificationBridgeService.ensureRebound(PassiveKeyService.this);
                }
            }
            boolean notifConnected = KeyNotificationBridgeService.isGeelyBleConnected()
                    && KeyNotificationBridgeService.isGeelyNotificationPresent();
            boolean connectedNow = notifConnected || gattConnected;
            if (connectedNow != geelyConnected) {
                onGeelyConnectionChanged(connectedNow, now);
            }
            if (logic.onTick(now, connectedNow, isDriving(now) || isChargingBlocked())) {
                appendLog("🔄 已离开车辆且蓝牙断开满 12s，已重新武装下一次靠近自动解锁");
            }
            ensureGattAttached(now);
            if (connectedNow && identity == null) {
                maybeLearnVehicle(now);
            }
            refreshWakeLock(now);
            runScreenOffScanWatchdog(now);
            updateNotificationIfNeeded(false);
            handler.postDelayed(this, 500L);
        }
    };

    // ==================== 前台通知 / 日志 ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "无感钥匙常驻服务",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("息屏靠近解锁 / 离开上锁");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("银河无感钥匙")
                .setContentText(text == null ? "" : text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotificationIfNeeded(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastNotificationUpdateAtMs) < 2000L) return;
        lastNotificationUpdateAtMs = now;
        String text;
        if (!serviceEnabled) {
            text = "无感进出服务已手动关闭";
        } else if (!geelyConnected) {
            text = identity == null ? "正在学习本车蓝牙身份" : "等待银河蓝牙连接 · " + lastZone;
        } else {
            text = "银河已连接 · " + lastZone;
        }
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(text));
            }
        } catch (Throwable ignored) {
        }
    }

    private void appendLog(String message) {
        if (message == null) return;
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = time + " " + message;
        synchronized (logBuffer) {
            logBuffer.addLast(line);
            while (logBuffer.size() > MAX_UI_LOG_LINES) {
                logBuffer.removeFirst();
            }
        }
        Log.i(TAG, message);
        final String fileLine = line;
        try {
            logExecutor.execute(() -> {
                try {
                    if (logFile != null && logFile.length() > LOG_FILE_MAX_BYTES) {
                        boolean ignored = logFile.delete();
                    }
                    try (FileWriter writer = new FileWriter(logFile, true)) {
                        writer.write(fileLine);
                        writer.write('\n');
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
            // 服务销毁后可能已经关闭线程池，日志仍保留在内存/Logcat。
        }
    }
}
