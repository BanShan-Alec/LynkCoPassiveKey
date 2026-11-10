package com.luca.geelykeyradar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简配置页。
 *
 * 只显示运行状态、本车识别、两个自动开关、必需权限和最近日志。
 */
public class MainActivity extends Activity {

    private static final int REQUEST_BT_PERMISSIONS = 1001;
    private static final int REQUEST_LOG_EXPORT = 1002;
    private static final ExecutorService LOG_EXPORT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean LOG_EXPORT_BUSY = new AtomicBoolean();
    // --- Paseo Dark Design Tokens ---
    // 主色 (Primary)
    private static final int COLOR_PRIMARY = Color.parseColor("#20744A");
    private static final int COLOR_PRIMARY_HOVER = Color.parseColor("#347552");
    private static final int COLOR_PRIMARY_ACTIVE = Color.parseColor("#1C5237");
    private static final int COLOR_PRIMARY_BG = Color.parseColor("#183124");
    private static final int COLOR_PRIMARY_TEXT = Color.parseColor("#7CCBA0");

    // 状态色 (Status)
    private static final int COLOR_SUCCESS = Color.parseColor("#6CB17B");
    private static final int COLOR_SUCCESS_TEXT = Color.parseColor("#C4D0C6");
    private static final int COLOR_WARNING = Color.parseColor("#C09664");
    private static final int COLOR_WARNING_TEXT = Color.parseColor("#E0DBD2");
    private static final int COLOR_ERROR = Color.parseColor("#D8847B");
    private static final int COLOR_ERROR_TEXT = Color.parseColor("#F8ECE9");
    private static final int COLOR_INFO = Color.parseColor("#5CAAF6");

    // 表面与背景 (Surfaces & Layout)
    private static final int COLOR_BG = Color.parseColor("#181B1A"); // --paseo-bg-layout
    private static final int COLOR_CARD = Color.parseColor("#1E2120"); // --paseo-bg-container
    private static final int COLOR_SURFACE_2 = Color.parseColor("#272A29"); // --paseo-surface-2
    private static final int COLOR_SURFACE_4 = Color.parseColor("#595B5B"); // --paseo-surface-4
    private static final int COLOR_BORDER = Color.parseColor("#252B2A"); // --paseo-border
    private static final int COLOR_BORDER_SECONDARY = Color.parseColor("#2F3534"); // --paseo-border-secondary

    // 文字 (Typography)
    private static final int COLOR_TEXT = Color.parseColor("#FAFAFA"); // --paseo-text
    private static final int COLOR_MUTED = Color.parseColor("#A1A5A4"); // --paseo-text-secondary
    private static final int COLOR_TEXT_TERTIARY = Color.parseColor("#8A8E8D"); // --paseo-text-tertiary

    // 身份色 (Identity)
    private static final int COLOR_IDENTITY_INK = Color.parseColor("#1F1E1D"); // --paseo-identity-ink
    private static final int COLOR_IDENTITY_ORANGE = Color.parseColor("#CC8F64"); // --paseo-identity-orange

    // 控件层语义映射
    private static final int COLOR_ACCENT = COLOR_PRIMARY_TEXT; // #7CCBA0
    private static final int COLOR_GREEN = COLOR_SUCCESS; // #6CB17B
    private static final int COLOR_RED = COLOR_ERROR; // #D8847B

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 500L);
        }
    };

    private PassiveKeyService service;
    private boolean bound = false;
    private boolean binding = false;
    private boolean settingSwitches = false;

    private TextView statusView;
    private TextView identityView;
    private TextView thresholdView;
    private TextView permissionView;
    private TextView logView;
    private Switch serviceMasterSwitch;
    private Switch autoUnlockSwitch;
    private Switch autoLockSwitch;
    private Switch blockChargingSwitch;
    private Switch blockWirelessChargingSwitch;
    private Button openOfficialAppButton;
    private Button repairNotificationButton;
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

    /** 初始化界面及日志仓库，恢复尚未完成的导出快照。 */
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

    /** 保持极简配置页，日志区只增加手动导出入口。 */
    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(48), dp(20), dp(32));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("领克无感钥匙", 26, COLOR_TEXT, Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text("只保留：息屏靠近解锁 / 离开上锁", 14, COLOR_MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleLp.topMargin = dp(4);
        subtitleLp.bottomMargin = dp(18);
        root.addView(subtitle, subtitleLp);

        LinearLayout statusCard = card();
        statusCard.addView(text("运行状态", 16, COLOR_ACCENT, Typeface.BOLD));
        statusView = text("", 14, COLOR_TEXT, Typeface.NORMAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bodyLp.topMargin = dp(10);
        statusCard.addView(statusView, bodyLp);

        openOfficialAppButton = button("打开领克官方 App");
        openOfficialAppButton.setOnClickListener(v -> launchOfficialApp());
        statusCard.addView(openOfficialAppButton, buttonLp());

        root.addView(statusCard, cardLp());

        LinearLayout identityCard = card();
        identityCard.addView(text("本车识别", 16, COLOR_ACCENT, Typeface.BOLD));
        identityView = text("", 14, COLOR_TEXT, Typeface.NORMAL);
        identityCard.addView(identityView, bodyLp);

        TextView identityHint = text(
                "首次使用：先用领克 App 连接一次车辆，本应用会自动记住这辆车的蓝牙身份。以后不需要再打开领克 App。",
                12, COLOR_MUTED, Typeface.NORMAL
        );
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        hintLp.topMargin = dp(10);
        identityCard.addView(identityHint, hintLp);

        Button bindSuffixButton = button("填入车架号后3位锁定本车（可选）");
        bindSuffixButton.setOnClickListener(v -> promptBindByVinSuffix());
        identityCard.addView(bindSuffixButton, buttonLp());

        Button resetButton = button("重新学习本车");
        resetButton.setOnClickListener(v -> confirmResetIdentity());
        identityCard.addView(resetButton, buttonLp());
        root.addView(identityCard, cardLp());

        LinearLayout switchCard = card();
        switchCard.addView(text("服务与自动动作", 16, COLOR_ACCENT, Typeface.BOLD));
        serviceMasterSwitch = switchRow("无感进出服务总开关", "关闭后立即停止扫描与一切解闭锁操作");
        autoUnlockSwitch = switchRow("靠近自动解锁", "距离车辆约 3 米达到解锁阈值时，只解锁一次");
        autoLockSwitch = switchRow("离开自动上锁", "持续远离达到确认秒数后上锁，断连不会误锁");
        blockChargingSwitch = switchRow("有线充电时禁止自动上锁", "USB/AC 有线充电时视为在车内，禁止自动上锁（仍允许靠近解锁）");
        blockWirelessChargingSwitch = switchRow("无线充电时禁止自动上锁", "车载无线充电时视为在车内，禁止自动上锁（含3秒断充防抖，仍允许靠近解锁）");
        switchCard.addView(serviceMasterSwitch);
        switchCard.addView(autoUnlockSwitch);
        switchCard.addView(autoLockSwitch);
        switchCard.addView(blockChargingSwitch);
        switchCard.addView(blockWirelessChargingSwitch);
        serviceMasterSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (settingSwitches) return;
                if (service != null) {
                    service.setServiceEnabled(isChecked);
                } else {
                    new VehicleIdentityStore(MainActivity.this).setServiceEnabled(isChecked);
                    if (isChecked) startPassiveKeyService();
                }
                refreshUi();
            }
        });
        autoUnlockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (settingSwitches) return;
                if (service != null) service.setAutoUnlockEnabled(isChecked);
            }
        });
        autoLockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (settingSwitches) return;
                if (service != null) service.setAutoLockEnabled(isChecked);
            }
        });
        blockChargingSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (settingSwitches) return;
                if (service != null) {
                    service.setBlockWhenChargingEnabled(isChecked);
                } else {
                    new VehicleIdentityStore(MainActivity.this).setBlockWhenChargingEnabled(isChecked);
                }
                refreshUi();
            }
        });
        blockWirelessChargingSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (settingSwitches) return;
                if (service != null) {
                    service.setBlockWhenWirelessChargingEnabled(isChecked);
                } else {
                    new VehicleIdentityStore(MainActivity.this).setBlockWhenWirelessChargingEnabled(isChecked);
                }
                refreshUi();
            }
        });

        thresholdView = text("", 13, COLOR_TEXT, Typeface.NORMAL);
        LinearLayout.LayoutParams threshLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        threshLp.topMargin = dp(8);
        switchCard.addView(thresholdView, threshLp);

        Button customThresholdBtn = button("自定义靠近/离开阈值");
        customThresholdBtn.setOnClickListener(v -> promptCustomThresholds());
        switchCard.addView(customThresholdBtn, buttonLp());
        root.addView(switchCard, cardLp());

        LinearLayout permissionCard = card();
        permissionCard.addView(text("权限与保活", 16, COLOR_ACCENT, Typeface.BOLD));
        permissionView = text("", 14, COLOR_TEXT, Typeface.NORMAL);
        permissionCard.addView(permissionView, bodyLp);

        Button btButton = button("授予蓝牙权限");
        btButton.setOnClickListener(v -> requestBluetoothPermissions());
        permissionCard.addView(btButton, buttonLp());

        Button notificationButton = button("开启通知使用权");
        notificationButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        permissionCard.addView(notificationButton, buttonLp());

        repairNotificationButton = button("⚡ 一键修复通知监听 (服务自愈)");
        repairNotificationButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_WARNING));
        repairNotificationButton.setTextColor(COLOR_IDENTITY_INK);
        repairNotificationButton.setVisibility(View.GONE);
        repairNotificationButton.setOnClickListener(v -> {
            KeyNotificationBridgeService.ensureRebound(MainActivity.this);
            Toast.makeText(MainActivity.this, "已触发通知监听重绑自愈，请等待 1-3 秒...", Toast.LENGTH_SHORT).show();
            refreshUi();
        });
        permissionCard.addView(repairNotificationButton, buttonLp());

        Button batteryButton = button("加入电池白名单");
        batteryButton.setOnClickListener(v -> requestBatteryWhitelist());
        permissionCard.addView(batteryButton, buttonLp());

        Button backgroundButton = button("打开后台高耗电设置");
        backgroundButton.setOnClickListener(v -> openAppDetails());
        permissionCard.addView(backgroundButton, buttonLp());
        root.addView(permissionCard, cardLp());

        if (isVivoDevice()) {
            LinearLayout vivoCard = card();
            vivoCard.addView(text("vivo / OriginOS 保活与防假死指南", 16, COLOR_IDENTITY_ORANGE, Typeface.BOLD));
            TextView vivoHelp = text(
                    "系统在应用被划卡强杀后会施加数分钟甚至更久的重绑限制，导致通知监听假死，人到车旁无法自动解锁。\n\n"
                            + "【OriginOS 关键配置】\n"
                            + "1. 后台卡片加锁：在多任务界面下拉本应用卡片加锁（显示锁头🔒），切勿在车门旁划卡杀进程；\n"
                            + "2. 允许后台高耗电：系统设置 -> 电池 -> 后台耗电管理 -> 本应用设为「允许高后台耗电」；\n"
                            + "3. 开启自启动权限：系统设置 -> 应用与权限 -> 权限管理 -> 自启动 -> 开启本应用；\n"
                            + "4. 假死快速自愈：若发现通知监听未连接，点击上方【一键修复通知监听】即可立即唤醒重绑。",
                    13, COLOR_TEXT, Typeface.NORMAL
            );
            LinearLayout.LayoutParams vivoHelpLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            vivoHelpLp.topMargin = dp(10);
            vivoCard.addView(vivoHelp, vivoHelpLp);

            Button vivoBatteryBtn = button("前往后台高耗电与自启动设置");
            vivoBatteryBtn.setOnClickListener(v -> openAppDetails());
            vivoCard.addView(vivoBatteryBtn, buttonLp());

            root.addView(vivoCard, cardLp());
        }

        LinearLayout helpCard = card();
        helpCard.addView(text("使用说明", 16, COLOR_ACCENT, Typeface.BOLD));
        TextView help = text(
                "1. 安装并登录领克 App，完成蓝牙钥匙绑定。\n"
                        + "2. 靠近车辆，让领克 App 成功连接一次。\n"
                        + "3. 本应用自动识别本车蓝牙，不需要填写 VIN 或 MAC。\n"
                        + "4. 保持本应用后台运行；息屏放口袋，靠近解锁，离开上锁。\n"
                        + "5. 若系统清理后台，请在多任务界面下拉加锁本应用卡片，并在电池设置里允许后台高耗电，切勿手动划卡清理后台。",
                13, COLOR_TEXT, Typeface.NORMAL
        );
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        helpLp.topMargin = dp(10);
        helpCard.addView(help, helpLp);
        root.addView(helpCard, cardLp());

        LinearLayout logCard = card();
        logCard.addView(text("最近 10 条关键日志 (最新在上)", 16, COLOR_ACCENT, Typeface.BOLD));
        logView = text("", 11, COLOR_MUTED, Typeface.NORMAL);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(0f, 1.15f);
        logView.setMaxLines(12);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        logLp.topMargin = dp(10);
        logCard.addView(logView, logLp);

        LinearLayout.LayoutParams logHintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        logHintLp.topMargin = dp(10);
        logCard.addView(text("后台自动记录判定原因、连接耗时及重启信息，最多 4 MiB；导出时隐藏车辆地址与尾号。",
                12, COLOR_MUTED, Typeface.NORMAL), logHintLp);
        exportLogButton = button("导出排查日志（ZIP）");
        exportLogButton.setOnClickListener(view -> chooseLogExportDestination());
        logCard.addView(exportLogButton, buttonLp());

        Button clearLogBtn = button("清空日志");
        clearLogBtn.setOnClickListener(v -> {
            if (service != null) {
                service.clearLogs();
                refreshUi();
            }
        });
        logCard.addView(clearLogBtn, buttonLp());
        root.addView(logCard, cardLp());

        setContentView(scrollView);
    }

    /** 刷新独立的官方连接、测距状态和已交付次数，不把尝试次数当作锁车成功。 */
    private void refreshUi() {
        if (exportLogButton != null) exportLogButton.setEnabled(!LOG_EXPORT_BUSY.get());
        PassiveKeyService.Status status = service != null ? service.getStatus() : null;
        boolean notificationAccess = KeyNotificationBridgeService.isNotificationListenerEnabled(this);
        boolean notificationConnected = KeyNotificationBridgeService.isListenerConnected();
        boolean batteryWhitelisted = isBatteryWhitelisted();
        boolean geelyInstalled = GeelyBridge.isGeelyInstalled(this);
        boolean btPermission = hasBluetoothPermissions();

        if (status == null) {
            statusView.setText("服务：未绑定\n"
                    + "蓝牙权限：" + yesNo(btPermission) + "\n"
                    + "通知使用权：" + formatNotificationStatus(notificationAccess, notificationConnected) + "\n"
                    + "电池白名单：" + yesNo(batteryWhitelisted) + "\n"
                    + "领克 App：" + (geelyInstalled ? "已安装" : "未安装"));
            identityView.setText("等待服务启动");
            VehicleIdentityStore store = new VehicleIdentityStore(this);
            thresholdView.setText(String.format(Locale.US,
                    "当前阈值：靠近解锁 ≥ %.0f dBm (约3米) | 离开上锁 ≤ %.0f dBm (连续 %.1fs)",
                    store.getUnlockDbm(), store.getLockDbm(), store.getLockDwellMs() / 1000.0f));
        } else {
            statusView.setText(
                    "服务总开关：" + (status.serviceEnabled ? "开启 (运行中)" : "已关闭 (完全停止操作)") + "\n"
                            + "蓝牙：" + (status.bluetoothOn ? "已开启" : "未开启")
                            + (status.scanning ? " · 扫描中" : "") + "\n"
                            + "通知使用权：" + formatNotificationStatus(notificationAccess, notificationConnected) + "\n"
                            + "领克钥匙：" + (status.geelyConnected ? "已连接" : "未连接") + "\n"
                            + "连接态 RSSI：" + (status.gattConnected ? "已挂载" : "未挂载") + "\n"
                            + "充电状态：" + (status.wiredCharging
                                    ? (status.blockWhenCharging ? "有线充电 (防锁保护中)" : "有线充电 (未禁控)")
                                    : status.wirelessCharging
                                    ? (status.blockWhenWirelessCharging ? "车载无线充电中 (防锁保护中)" : "车载无线充电中 (未禁控)")
                                    : "未充电") + "\n"
                            + "信号：" + (status.rssi > -127 ? status.rssi + " dBm" : "--")
                            + " · " + status.zone + "\n"
                            + "通知按钮：" + status.buttonDiag + "\n"
                            + "最近动作：" + status.lastAction + "\n"
                            + "指令已交付（解锁/上锁）：" + status.unlockCount + " / " + status.lockCount
            );

            if (status.identityLearned) {
                identityView.setText(
                        "已学习：" + status.vehicleName + "\n"
                                + "地址：" + (status.vehicleAddress.isEmpty() ? "--" : status.vehicleAddress)
                );
            } else {
                identityView.setText("未学习\n请先用领克 App 连接一次车辆；连接成功后本应用会自动记住它。");
            }

            settingSwitches = true;
            serviceMasterSwitch.setChecked(status.serviceEnabled);
            autoUnlockSwitch.setChecked(status.autoUnlock);
            autoLockSwitch.setChecked(status.autoLock);
            blockChargingSwitch.setChecked(status.blockWhenCharging);
            blockWirelessChargingSwitch.setChecked(status.blockWhenWirelessCharging);
            autoUnlockSwitch.setEnabled(status.serviceEnabled);
            autoLockSwitch.setEnabled(status.serviceEnabled);
            blockChargingSwitch.setEnabled(status.serviceEnabled);
            blockWirelessChargingSwitch.setEnabled(status.serviceEnabled);
            settingSwitches = false;

            thresholdView.setText(String.format(Locale.US,
                    "当前阈值：靠近解锁 ≥ %.0f dBm (约3米) | 离开上锁 ≤ %.0f dBm (连续 %.1fs)",
                    status.unlockDbm, status.lockDbm, status.lockDwellMs / 1000.0f));
            logView.setText(service.getLogText());
        }

        permissionView.setText(
                "蓝牙权限：" + yesNo(btPermission) + "\n"
                        + "通知使用权：" + formatNotificationStatus(notificationAccess, notificationConnected)
                        + (!notificationAccess ? "（必需，否则无法点官方通知栏按钮）" : "") + "\n"
                        + "电池白名单：" + yesNo(batteryWhitelisted) + "\n"
                        + "领克 App：" + (geelyInstalled ? "已安装" : "未安装")
        );

        if (repairNotificationButton != null) {
            if (notificationAccess && !notificationConnected) {
                repairNotificationButton.setVisibility(View.VISIBLE);
            } else {
                repairNotificationButton.setVisibility(View.GONE);
            }
        }

        updateOfficialAppButton(geelyInstalled,
                status != null ? status.geelyConnected : KeyNotificationBridgeService.isGeelyBleConnected(),
                KeyNotificationBridgeService.isGeelyNotificationPresent());
    }

    /** 根据领克官方 App 安装及通知桥接/连接状态，动态更新联动唤醒按钮文案与视觉状态。 */
    private void updateOfficialAppButton(boolean installed, boolean connected, boolean notificationPresent) {
        if (openOfficialAppButton == null) return;
        if (!installed) {
            openOfficialAppButton.setEnabled(false);
            openOfficialAppButton.setText("未安装领克官方 App");
            openOfficialAppButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_SURFACE_4));
            openOfficialAppButton.setTextColor(COLOR_TEXT_TERTIARY);
            return;
        }
        openOfficialAppButton.setEnabled(true);
        if (!notificationPresent || !connected) {
            openOfficialAppButton.setText("⚡ 启动领克官方 App (建立通知桥接)");
            openOfficialAppButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_IDENTITY_ORANGE));
            openOfficialAppButton.setTextColor(COLOR_IDENTITY_INK);
        } else {
            openOfficialAppButton.setText("打开领克官方 App");
            openOfficialAppButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_PRIMARY));
            openOfficialAppButton.setTextColor(COLOR_TEXT);
        }
    }

    /** 快捷启动领克官方应用，提供轻量交互反馈。 */
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
                Toast.makeText(this, "启动领克官方 App 失败，请尝试在手机桌面手动打开", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    /** 用户点击后通过系统文件选择器选择本地保存位置，无需存储权限，也不自动发送给他人。 */
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

    /** 保留导出快照的采集时刻，切换文件选择器时不丢失状态上下文。 */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("diagnostic_snapshot", exportSnapshot);
        super.onSaveInstanceState(outState);
    }

    /** 在后台生成白名单ZIP并写入用户指定文件，日志写入与车控线程保持分离。 */
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
        try {
            bindService(
                    new Intent(this, PassiveKeyService.class),
                    connection,
                    Context.BIND_AUTO_CREATE
            );
        } catch (Throwable ignored) {
            binding = false;
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
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.POST_NOTIFICATIONS
                }, REQUEST_BT_PERMISSIONS);
            } else {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                }, REQUEST_BT_PERMISSIONS);
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_BT_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT_PERMISSIONS) {
            startPassiveKeyService();
            refreshUi();
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

    private void promptBindByVinSuffix() {
        final EditText input = new EditText(this);
        input.setHint("例如 123（留空则走自动识别）");
        input.setSingleLine(true);
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(COLOR_MUTED);
        new AlertDialog.Builder(this)
                .setTitle("按车架号后3位锁定本车")
                .setMessage("领克蓝牙广播名为 LynkCo+车架号后3位（或自动识别）。填入后3位（如 123）可在首次靠近前直接锁定本车；不填也会在靠近车辆或领克连接时自动识别。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    String text = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!text.isEmpty() && service != null) {
                        service.bindByVinSuffix(text);
                        refreshUi();
                    }
                })
                .show();
    }

    /**
     * 弹出自定义信号阈值设置框：允许自定义靠近解锁阈值(dBm)、离开上锁阈值(dBm)与离开确认秒数。
     */
    private void promptCustomThresholds() {
        VehicleIdentityStore store = new VehicleIdentityStore(this);
        float curUnlock = service != null ? service.getStatus().unlockDbm : store.getUnlockDbm();
        float curLock = service != null ? service.getStatus().lockDbm : store.getLockDbm();
        float curDwellSec = (service != null ? service.getStatus().lockDwellMs : store.getLockDwellMs()) / 1000.0f;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        TextView tip = new TextView(this);
        tip.setText("信号越接近 0 表示距离越近：\n• -63 dBm ≈ 1.5米 (主驾门边)\n• -70 dBm ≈ 3.0米\n• -75 dBm ≈ 3.5~4.5米 (默认推荐提前解锁)\n• -82 dBm ≈ 7.0~8.0米 (默认推荐离开上锁)");
        tip.setTextSize(12);
        tip.setTextColor(COLOR_MUTED);
        box.addView(tip);

        final EditText unlockInput = new EditText(this);
        unlockInput.setHint("靠近解锁阈值 dBm (默认 -75)");
        unlockInput.setText(String.format(Locale.US, "%.0f", curUnlock));
        unlockInput.setSingleLine(true);
        unlockInput.setTextColor(COLOR_TEXT);
        unlockInput.setHintTextColor(COLOR_MUTED);
        box.addView(unlockInput);

        final EditText lockInput = new EditText(this);
        lockInput.setHint("离开上锁阈值 dBm (例如 -82)");
        lockInput.setText(String.format(Locale.US, "%.0f", curLock));
        lockInput.setSingleLine(true);
        lockInput.setTextColor(COLOR_TEXT);
        lockInput.setHintTextColor(COLOR_MUTED);
        box.addView(lockInput);

        final EditText dwellInput = new EditText(this);
        dwellInput.setHint("离开持续确认秒数 (例如 4.5)");
        dwellInput.setText(String.format(Locale.US, "%.1f", curDwellSec));
        dwellInput.setSingleLine(true);
        dwellInput.setTextColor(COLOR_TEXT);
        dwellInput.setHintTextColor(COLOR_MUTED);
        box.addView(dwellInput);

        new AlertDialog.Builder(this)
                .setTitle("自定义靠近/离开信号阈值")
                .setView(box)
                .setNeutralButton("恢复默认(-75/-82)", (dialog, which) -> {
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
                    Toast.makeText(this, "已恢复默认阈值 (-75dBm 解锁 / -82dBm 上锁 / 4.5s)", Toast.LENGTH_SHORT).show();
                    refreshUi();
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("保存生效", (dialog, which) -> {
                    try {
                        float u = Float.parseFloat(unlockInput.getText().toString().trim());
                        float l = Float.parseFloat(lockInput.getText().toString().trim());
                        float dSec = Float.parseFloat(dwellInput.getText().toString().trim());
                        if (u > 0) u = -u; // 兼容用户输入正数 70 自动转为 -70
                        if (l > 0) l = -l;
                        long dMs = Math.round(dSec * 1000.0f);
                        if (service != null) {
                            service.updateThresholds(u, l, dMs);
                        } else {
                            store.saveThresholds(u, l, dMs);
                        }
                        Toast.makeText(this, "自定义阈值已保存并立即生效", Toast.LENGTH_SHORT).show();
                        refreshUi();
                    } catch (Throwable e) {
                        Toast.makeText(this, "请输入有效数字，例如 -75、-82、4.5", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void confirmResetIdentity() {
        new AlertDialog.Builder(this)
                .setTitle("重新学习本车")
                .setMessage("清除已记住的蓝牙身份。下次用领克 App 连接车辆后，本应用会重新识别。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) -> {
                    if (service != null) {
                        service.resetLearnedVehicle();
                    } else {
                        new VehicleIdentityStore(this).clear();
                    }
                    refreshUi();
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
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), COLOR_BORDER);
        layout.setBackground(bg);
        return layout;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(14);
        return lp;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(COLOR_TEXT);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_PRIMARY));
        return button;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        lp.topMargin = dp(10);
        return lp;
    }

    private Switch switchRow(String title, String detail) {
        Switch toggle = new Switch(this);
        toggle.setText(title + "\n" + detail);
        toggle.setTextSize(14);
        toggle.setTextColor(COLOR_TEXT);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(0, dp(10), 0, dp(10));
        toggle.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}
                },
                new int[]{
                        COLOR_PRIMARY_TEXT,
                        COLOR_SURFACE_4
                }
        ));
        toggle.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}
                },
                new int[]{
                        COLOR_PRIMARY,
                        COLOR_SURFACE_2
                }
        ));
        return toggle;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String yesNo(boolean value) {
        return value ? "已开启" : "未开启";
    }

    private String formatNotificationStatus(boolean access, boolean connected) {
        if (!access) {
            return "未开启 (需要系统设置授权)";
        }
        if (!connected) {
            return "⚠️ 已开启但系统未绑定 (假死状态)";
        }
        return "正常监听中";
    }

    private static boolean isVivoDevice() {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase(Locale.ROOT) : "";
        String brand = Build.BRAND != null ? Build.BRAND.toLowerCase(Locale.ROOT) : "";
        return manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo");
    }
}
