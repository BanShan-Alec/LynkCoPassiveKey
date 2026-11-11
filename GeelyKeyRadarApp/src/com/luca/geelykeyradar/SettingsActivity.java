package com.luca.geelykeyradar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 偏好与系统设置二级页面。
 * 遵循 Paseo Dark 设计规范（DESIGN.md）与 Refactoring UI 十二大系统阶梯，
 * 将车辆重绑、车载充电防锁、后台保活自愈与故障排查日志等技术配置模块化收纳。
 */
public class SettingsActivity extends Activity {

    private static final int REQUEST_BT_PERMISSIONS = 2001;
    private static final int REQUEST_LOG_EXPORT = 2002;
    private static final ExecutorService LOG_EXPORT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean LOG_EXPORT_BUSY = new AtomicBoolean();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 1000L);
        }
    };

    private PassiveKeyService service;
    private boolean bound = false;
    private boolean binding = false;
    private boolean settingSwitches = false;

    private TextView vehicleIdentityView;
    private Switch blockChargingSwitch;
    private Switch blockWirelessChargingSwitch;
    private TextView thresholdView;
    private TextView permissionStatusView;
    private Button repairNotificationButton;
    private TextView logSummaryView;
    private Button exportLogButton;
    private String exportSnapshot = "service_snapshot=unavailable";

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
        if (savedInstanceState != null) {
            exportSnapshot = savedInstanceState.getString("diagnostic_snapshot", exportSnapshot);
        }
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("diagnostic_snapshot", exportSnapshot);
        super.onSaveInstanceState(outState);
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

        // 顶栏：返回按钮与主标题
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topBarLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(PaseoTheme.TARGET_MIN_DP)
        );
        topBarLp.bottomMargin = dp(PaseoTheme.MARGIN_CARD_BOTTOM);

        ImageView backBtn = new ImageView(this);
        backBtn.setImageResource(R.drawable.ic_back);
        backBtn.setImageTintList(ColorStateList.valueOf(PaseoTheme.COLOR_TEXT));
        backBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        backBtn.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        backLp.rightMargin = dp(8);
        topBar.addView(backBtn, backLp);

        TextView title = text("偏好与系统设置", 20, PaseoTheme.COLOR_TEXT, Typeface.BOLD);
        topBar.addView(title);
        root.addView(topBar, topBarLp);

        // 卡片 1：车辆与钥匙绑定
        LinearLayout identityCard = card();
        identityCard.addView(text("车辆与钥匙绑定", 16, PaseoTheme.COLOR_PRIMARY_TEXT, Typeface.BOLD));
        vehicleIdentityView = text("", 14, PaseoTheme.COLOR_TEXT, Typeface.NORMAL);
        identityCard.addView(vehicleIdentityView, bodyLp());

        TextView identityHint = text(
                "首次使用领克 App 成功连接车辆后，本应用会自动记住蓝牙身份。若需更换绑定的车辆，可点击下方重新学习。",
                12, PaseoTheme.COLOR_TEXT_SECONDARY, Typeface.NORMAL
        );
        identityCard.addView(identityHint, bodyLp());

        Button bindSuffixBtn = button("填入车架号后3位锁定本车（可选）", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        bindSuffixBtn.setOnClickListener(v -> promptBindByVinSuffix());
        identityCard.addView(bindSuffixBtn, buttonLp());

        Button resetBtn = button("重新学习本车", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        resetBtn.setOnClickListener(v -> confirmResetIdentity());
        identityCard.addView(resetBtn, buttonLp());
        root.addView(identityCard, cardLp());

        // 卡片 2：车载防误锁与高级偏好
        LinearLayout prefCard = card();
        prefCard.addView(text("车载防误锁与灵敏度", 16, PaseoTheme.COLOR_PRIMARY_TEXT, Typeface.BOLD));
        blockChargingSwitch = switchRow("有线充电时禁止自动上锁", "USB/AC 有线充电时视为在车内，禁止自动上锁（仍允许靠近解锁）");
        blockWirelessChargingSwitch = switchRow("无线充电时禁止自动上锁", "车载无线充电时视为在车内，禁止自动上锁（含3秒断充防抖，仍允许靠近解锁）");
        prefCard.addView(blockChargingSwitch);
        prefCard.addView(blockWirelessChargingSwitch);

        blockChargingSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (settingSwitches) return;
            if (service != null) {
                service.setBlockWhenChargingEnabled(isChecked);
            } else {
                new VehicleIdentityStore(this).setBlockWhenChargingEnabled(isChecked);
            }
            refreshUi();
        });

        blockWirelessChargingSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (settingSwitches) return;
            if (service != null) {
                service.setBlockWhenWirelessChargingEnabled(isChecked);
            } else {
                new VehicleIdentityStore(this).setBlockWhenWirelessChargingEnabled(isChecked);
            }
            refreshUi();
        });

        thresholdView = text("", 13, PaseoTheme.COLOR_TEXT, Typeface.NORMAL);
        prefCard.addView(thresholdView, bodyLp());

        Button customThresholdBtn = button("自定义靠近 / 离开门限参数", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        customThresholdBtn.setOnClickListener(v -> promptCustomThresholds());
        prefCard.addView(customThresholdBtn, buttonLp());
        root.addView(prefCard, cardLp());

        // 卡片 3：后台保活与服务自愈
        LinearLayout keepAliveCard = card();
        keepAliveCard.addView(text("后台保活与服务自愈", 16, PaseoTheme.COLOR_PRIMARY_TEXT, Typeface.BOLD));
        permissionStatusView = text("", 14, PaseoTheme.COLOR_TEXT, Typeface.NORMAL);
        keepAliveCard.addView(permissionStatusView, bodyLp());

        // 核心自愈按钮：Primary 主题绿色呈现，配以原生矢量刷新图标
        repairNotificationButton = button("一键修复钥匙通道", PaseoTheme.COLOR_PRIMARY, PaseoTheme.COLOR_TEXT);
        repairNotificationButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_refresh, 0, 0, 0);
        repairNotificationButton.setCompoundDrawablePadding(dp(8));
        repairNotificationButton.setOnClickListener(v -> {
            KeyNotificationBridgeService.ensureRebound(SettingsActivity.this);
            Toast.makeText(SettingsActivity.this, "已触发钥匙通道自愈重连，请等待 1-3 秒...", Toast.LENGTH_SHORT).show();
            refreshUi();
        });
        keepAliveCard.addView(repairNotificationButton, buttonLp());

        TextView healHint = text(
                "说明：当系统清理后台导致门锁通道断开（假死）时，靠近车辆时会自动毫秒级唤醒；也可点击上方按钮手动立即自愈。",
                12, PaseoTheme.COLOR_TEXT_SECONDARY, Typeface.NORMAL
        );
        keepAliveCard.addView(healHint, bodyLp());

        Button btPermissionBtn = button("授予蓝牙权限", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        btPermissionBtn.setOnClickListener(v -> requestBluetoothPermissions());
        keepAliveCard.addView(btPermissionBtn, buttonLp());

        Button notifAccessBtn = button("开启通知使用权", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        notifAccessBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        keepAliveCard.addView(notifAccessBtn, buttonLp());

        Button batteryBtn = button("加入电池白名单", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        batteryBtn.setOnClickListener(v -> requestBatteryWhitelist());
        keepAliveCard.addView(batteryBtn, buttonLp());

        Button appDetailsBtn = button("打开系统应用管理 (允许后台高耗电与自启动)", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        appDetailsBtn.setOnClickListener(v -> openAppDetails());
        keepAliveCard.addView(appDetailsBtn, buttonLp());
        root.addView(keepAliveCard, cardLp());

        // 卡片 4：故障排查与诊断日志
        LinearLayout diagCard = card();
        diagCard.addView(text("故障诊断与排查", 16, PaseoTheme.COLOR_PRIMARY_TEXT, Typeface.BOLD));
        logSummaryView = text("", 11, PaseoTheme.COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        logSummaryView.setTypeface(Typeface.MONOSPACE);
        logSummaryView.setLineSpacing(0f, 1.15f);
        logSummaryView.setMaxLines(8);
        diagCard.addView(logSummaryView, bodyLp());

        exportLogButton = button("导出排查日志 (ZIP)", PaseoTheme.COLOR_PRIMARY, PaseoTheme.COLOR_TEXT);
        exportLogButton.setOnClickListener(v -> chooseLogExportDestination());
        diagCard.addView(exportLogButton, buttonLp());

        Button clearLogBtn = button("清空诊断日志", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        clearLogBtn.setOnClickListener(v -> {
            if (service != null) {
                service.clearLogs();
                refreshUi();
            }
        });
        diagCard.addView(clearLogBtn, buttonLp());
        root.addView(diagCard, cardLp());

        setContentView(scrollView);
    }

    private void refreshUi() {
        if (exportLogButton != null) exportLogButton.setEnabled(!LOG_EXPORT_BUSY.get());
        PassiveKeyService.Status status = service != null ? service.getStatus() : null;
        boolean notificationAccess = KeyNotificationBridgeService.isNotificationListenerEnabled(this);
        boolean notificationConnected = KeyNotificationBridgeService.isListenerConnected();
        boolean batteryWhitelisted = isBatteryWhitelisted();
        boolean btPermission = hasBluetoothPermissions();

        if (status != null) {
            if (status.identityLearned) {
                vehicleIdentityView.setText("已绑定车辆：" + status.vehicleName + "\n蓝牙物理地址："
                        + (status.vehicleAddress.isEmpty() ? "--" : status.vehicleAddress));
            } else {
                vehicleIdentityView.setText("尚未绑定车辆\n请先使用领克官方 App 连接一次车辆。");
            }

            settingSwitches = true;
            blockChargingSwitch.setChecked(status.blockWhenCharging);
            blockWirelessChargingSwitch.setChecked(status.blockWhenWirelessCharging);
            settingSwitches = false;

            thresholdView.setText(String.format(Locale.US,
                    "当前门限：靠近解锁 ≥ %.0f dBm (约2米) | 离开上锁 ≤ %.0f dBm (确认 %.1f 秒)",
                    status.unlockDbm, status.lockDbm, status.lockDwellMs / 1000.0f));

            if (logSummaryView != null) {
                logSummaryView.setText(service.getLogText());
            }
        } else {
            VehicleIdentityStore store = new VehicleIdentityStore(this);
            VehicleIdentity saved = store.load();
            vehicleIdentityView.setText(saved != null ? "已保存车辆：" + saved.name + "\n蓝牙物理地址："
                    + (saved.address.isEmpty() ? "--" : saved.address) : "等待服务连接");
            thresholdView.setText(String.format(Locale.US,
                    "当前门限：靠近解锁 ≥ %.0f dBm | 离开上锁 ≤ %.0f dBm (确认 %.1f 秒)",
                    store.getUnlockDbm(), store.getLockDbm(), store.getLockDwellMs() / 1000.0f));
        }

        permissionStatusView.setText(
                "蓝牙权限：" + (btPermission ? "已授予" : "未授予 (影响感应)") + "\n"
                        + "通知监听：" + (notificationConnected ? "已连接 (就绪)" : (notificationAccess ? "已授权但未挂载 (可点自愈)" : "未开启 (无法解闭锁)")) + "\n"
                        + "后台白名单：" + (batteryWhitelisted ? "已加入" : "未加入 (可能被系统查杀)")
        );
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
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    private void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private void chooseLogExportDestination() {
        if (LOG_EXPORT_BUSY.get()) return;
        exportSnapshot = "snapshot_captured_mono_ms=" + android.os.SystemClock.elapsedRealtime() + "\n"
                + (service == null ? "service_snapshot=unavailable" : service.getDiagnosticSnapshot());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.putExtra(Intent.EXTRA_TITLE, "GeelyPassiveKey-logs-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".zip");
        try {
            startActivityForResult(intent, REQUEST_LOG_EXPORT);
        } catch (RuntimeException unavailable) {
            DiagnosticLog.bridgeError("Export", "FILE_PICKER_ERROR", unavailable);
            Toast.makeText(this, "无法打开系统文件保存器", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOG_EXPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            DiagnosticLog.detail("Export", "EXPORT_CANCELLED", "");
            return;
        }
        if (!LOG_EXPORT_BUSY.compareAndSet(false, true)) return;
        Context appContext = getApplicationContext();
        Uri destination = data.getData();
        String snapshot = service == null ? exportSnapshot
                : "snapshot_captured_mono_ms=" + android.os.SystemClock.elapsedRealtime() + "\n" + service.getDiagnosticSnapshot();
        LOG_EXPORT_EXECUTOR.execute(() -> {
            File temporary = null;
            String message;
            try {
                temporary = File.createTempFile("passive-key-diagnostics-", ".zip", appContext.getCacheDir());
                Future<File> archive = DiagnosticLog.export(temporary, snapshot);
                File ready = archive.get();
                try (FileInputStream input = new FileInputStream(ready);
                     OutputStream output = appContext.getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new java.io.IOException("输出文件不可用");
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                DiagnosticLog.event("Export", "EXPORT_SAVED", "archive_bytes=" + ready.length());
                message = "排查日志已保存，可将 ZIP 提供给排查人员";
            } catch (Exception error) {
                DiagnosticLog.bridgeError("Export", "EXPORT_FAILED", error);
                message = "日志导出失败，请重试（已有日志仍保留）";
            } finally {
                if (temporary != null && temporary.exists() && !temporary.delete()) {
                    DiagnosticLog.detail("Export", "CACHE_CLEANUP_FAILED", "temporary_archive=true");
                }
                LOG_EXPORT_BUSY.set(false);
            }
            String finalMessage = message;
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(appContext, finalMessage, Toast.LENGTH_LONG).show());
        });
        refreshUi();
    }

    private void promptBindByVinSuffix() {
        EditText input = new EditText(this);
        input.setHint("如: 789");
        input.setTextColor(PaseoTheme.COLOR_TEXT);
        input.setHintTextColor(PaseoTheme.COLOR_TEXT_TERTIARY);
        input.setMaxLines(1);
        new AlertDialog.Builder(this)
                .setTitle("车架号后3位锁定")
                .setMessage("填入车架号（VIN）后3位字符（数字或大写字母），防止周围其它吉利车辆干扰。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("锁定", (dialog, which) -> {
                    String tail = input.getText().toString().trim().toUpperCase(Locale.US);
                    if (tail.length() != 3) {
                        Toast.makeText(this, "请输入恰好 3 位车架号尾号", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (service != null) {
                        service.bindByVinSuffix(tail);
                    } else {
                        Toast.makeText(this, "正在连接服务，请稍后重试", Toast.LENGTH_SHORT).show();
                    }
                    Toast.makeText(this, "已锁定车架号后3位: " + tail, Toast.LENGTH_SHORT).show();
                    refreshUi();
                })
                .show();
    }

    private void confirmResetIdentity() {
        new AlertDialog.Builder(this)
                .setTitle("重新学习本车")
                .setMessage("清除已记住的蓝牙身份。下次用领克 App 连接车辆后，本应用会自动重新识别。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认清除", (dialog, which) -> {
                    if (service != null) {
                        service.resetLearnedVehicle();
                    } else {
                        new VehicleIdentityStore(this).clear();
                    }
                    Toast.makeText(this, "已重置本车记忆，请使用官方 App 重新识别", Toast.LENGTH_SHORT).show();
                    refreshUi();
                })
                .show();
    }

    private void promptCustomThresholds() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(10), dp(20), dp(10));

        VehicleIdentityStore store = new VehicleIdentityStore(this);
        float curUnlock = service != null ? service.getStatus().unlockDbm : store.getUnlockDbm();
        float curLock = service != null ? service.getStatus().lockDbm : store.getLockDbm();
        float curDwellSec = (service != null ? service.getStatus().lockDwellMs : store.getLockDwellMs()) / 1000.0f;

        EditText unlockInput = new EditText(this);
        unlockInput.setHint("靠近解锁阈值 dBm (默认 -75)");
        unlockInput.setText(String.format(Locale.US, "%.0f", curUnlock));
        unlockInput.setTextColor(PaseoTheme.COLOR_TEXT);
        unlockInput.setHintTextColor(PaseoTheme.COLOR_TEXT_TERTIARY);

        EditText lockInput = new EditText(this);
        lockInput.setHint("离开上锁阈值 dBm (默认 -82)");
        lockInput.setText(String.format(Locale.US, "%.0f", curLock));
        lockInput.setTextColor(PaseoTheme.COLOR_TEXT);
        lockInput.setHintTextColor(PaseoTheme.COLOR_TEXT_TERTIARY);

        EditText dwellInput = new EditText(this);
        dwellInput.setHint("离开确认秒数 (默认 4.5)");
        dwellInput.setText(String.format(Locale.US, "%.1f", curDwellSec));
        dwellInput.setTextColor(PaseoTheme.COLOR_TEXT);
        dwellInput.setHintTextColor(PaseoTheme.COLOR_TEXT_TERTIARY);

        layout.addView(text("靠近解锁阈值 (dBm):", 12, PaseoTheme.COLOR_PRIMARY_TEXT, Typeface.NORMAL));
        layout.addView(unlockInput);
        layout.addView(text("离开上锁阈值 (dBm):", 12, PaseoTheme.COLOR_PRIMARY_TEXT, Typeface.NORMAL));
        layout.addView(lockInput);
        layout.addView(text("离开持续确认时间 (秒):", 12, PaseoTheme.COLOR_PRIMARY_TEXT, Typeface.NORMAL));
        layout.addView(dwellInput);

        new AlertDialog.Builder(this)
                .setTitle("自定义门限参数")
                .setView(layout)
                .setNeutralButton("恢复默认", (dialog, which) -> {
                    if (service != null) {
                        service.updateThresholds(
                                VehicleIdentityStore.DEFAULT_UNLOCK_DBM,
                                VehicleIdentityStore.DEFAULT_LOCK_DBM,
                                VehicleIdentityStore.DEFAULT_LOCK_DWELL_MS
                        );
                    } else {
                        store.saveThresholds(
                                VehicleIdentityStore.DEFAULT_UNLOCK_DBM,
                                VehicleIdentityStore.DEFAULT_LOCK_DBM,
                                VehicleIdentityStore.DEFAULT_LOCK_DWELL_MS
                        );
                    }
                    Toast.makeText(this, "已恢复默认门限 (-75dBm / -82dBm / 4.5s)", Toast.LENGTH_SHORT).show();
                    refreshUi();
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("保存生效", (dialog, which) -> {
                    try {
                        float u = Float.parseFloat(unlockInput.getText().toString().trim());
                        float l = Float.parseFloat(lockInput.getText().toString().trim());
                        float dSec = Float.parseFloat(dwellInput.getText().toString().trim());
                        if (u > 0) u = -u;
                        if (l > 0) l = -l;
                        long dMs = Math.round(dSec * 1000.0f);
                        if (service != null) {
                            service.updateThresholds(u, l, dMs);
                        } else {
                            store.saveThresholds(u, l, dMs);
                        }
                        Toast.makeText(this, "门限参数已保存并立即生效", Toast.LENGTH_SHORT).show();
                        refreshUi();
                    } catch (Throwable e) {
                        Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
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

    private LinearLayout.LayoutParams bodyLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(PaseoTheme.SPACING_ELEMENT);
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
        bg.setCornerRadius(dp(PaseoTheme.RADIUS_BUTTON));
        if (bgColor == PaseoTheme.COLOR_PRIMARY || bgColor == PaseoTheme.COLOR_WARNING) {
            bg.setStroke(0, 0);
        } else {
            // 次级操作按钮：浮动于卡片 (#1E2120) 之上，采用清晰可见的次级边框 (#3D4643) 与 surface-2 (#272A29) 底色
            bg.setColor(PaseoTheme.COLOR_SURFACE_2);
            bg.setStroke(dp(PaseoTheme.BORDER_WIDTH_DP), 0xFF3D4643);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rippleColor = (bgColor == PaseoTheme.COLOR_WARNING) ? 0x22000000 : 0x22FFFFFF;
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null));
        } else {
            button.setBackground(bg);
        }
        return button;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(PaseoTheme.TARGET_MIN_DP)
        );
        lp.topMargin = dp(PaseoTheme.SPACING_ELEMENT);
        return lp;
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
