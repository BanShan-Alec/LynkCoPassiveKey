package com.luca.geelykeyradar;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * 钥匙就绪自检底部抽屉浮层 (ReadinessBottomSheet).
 * 遵循 Paseo Dark 设计规范（DESIGN.md）与 Refactoring UI 十二大系统阶梯：
 * 1. 采用标准 android.app.Dialog 实现底部停靠、全宽、0.65 遮罩与平滑滑出动效；
 * 2. 零外部 AndroidX / Material 依赖，保持轻量级原生构建；
 * 3. 严格遵循 Paseo 字阶（20sp/16sp/14sp/12sp）与 46dp 最小触控靶区；
 * 4. 表面阶梯清晰：抽屉底色 surface-0 (#181B1A) -> 卡片 surface-1 (#1E2120) -> 按钮 surface-2 (#272A29 带 #3D4643 描边)；
 * 5. 全面遵循零 Emoji 规范，采用纯净状态圆点 (●) 与明确语义色；
 * 6. 结构化呈现 3 项必需（官方 App、核心权限、车辆自学习）与 1 项建议（后台高耗电白名单）；
 * 7. 支持向下滑动手柄收起与原地增量更新状态。
 */
public class ReadinessBottomSheet extends Dialog {

    public interface OnActionListener {
        void onOpenOfficialApp();
        void onRequestBluetooth();
        void onOpenNotificationSettings();
        void onRequestBatteryWhitelist();
    }

    private final OnActionListener listener;
    private ReadinessModel.Snapshot currentSnapshot;

    private TextView subtitleProgressView;

    // Item 1: 领克官方 App
    private TextView item1IconView;
    private TextView item1TitleView;
    private TextView item1DescView;
    private Button item1Button;

    // Item 2: 核心系统权限
    private TextView item2IconView;
    private TextView item2TitleView;
    private TextView item2DescView;
    private Button item2BtButton;
    private Button item2NotifButton;

    // Item 3: 车辆自学习与车机设置
    private TextView item3IconView;
    private TextView item3TitleView;
    private TextView item3DescView;
    private Button item3Button;

    // Item 4: 后台高耗电白名单 (建议)
    private TextView item4IconView;
    private TextView item4TitleView;
    private TextView item4DescView;
    private Button item4Button;

    public ReadinessBottomSheet(Context context, OnActionListener listener) {
        super(context);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        buildUi();

        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.65f); // DESIGN.md --paseo-bg-mask 0.65 不透明度
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        setCanceledOnTouchOutside(true);

        if (currentSnapshot != null) {
            applySnapshot(currentSnapshot);
        }
    }

    public void update(ReadinessModel.Snapshot snapshot) {
        this.currentSnapshot = snapshot;
        if (subtitleProgressView != null) {
            applySnapshot(snapshot);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(16));

        // Paseo Dark 容器底色 (#181B1A) + 顶部 12dp 圆角 + 精细描边 (#252B2A)
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PaseoTheme.COLOR_BG);
        float r = dp(PaseoTheme.RADIUS_CARD);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(dp(PaseoTheme.BORDER_WIDTH_DP), PaseoTheme.COLOR_BORDER);
        root.setBackground(bg);

        // 1. 顶部拖拽手柄 (Drag Handle) - 支持点击或向下滑动收起抽屉
        View dragHandle = new View(getContext());
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(PaseoTheme.COLOR_SURFACE_3);
        handleBg.setCornerRadius(dp(2));
        dragHandle.setBackground(handleBg);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(12);
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (event.getRawY() - startY > dp(20)) {
                            dismiss();
                            return true;
                        }
                        break;
                }
                return false;
            }
        });
        root.addView(dragHandle, handleLp);

        // 2. 标题区与就绪度进度 (Paseo 字阶: heading-sm 20sp, caption 12sp)
        LinearLayout headerGroup = new LinearLayout(getContext());
        headerGroup.setOrientation(LinearLayout.VERTICAL);
        headerGroup.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headerLp.bottomMargin = dp(16);

        TextView titleView = new TextView(getContext());
        titleView.setText("钥匙就绪自检");
        titleView.setTextSize(20);
        titleView.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titleView.setTextColor(PaseoTheme.COLOR_TEXT);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        headerGroup.addView(titleView);

        subtitleProgressView = new TextView(getContext());
        subtitleProgressView.setText("正在评估系统与车辆准备度...");
        subtitleProgressView.setTextSize(12);
        subtitleProgressView.setTextColor(PaseoTheme.COLOR_TEXT_SECONDARY);
        subtitleProgressView.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subLp.topMargin = dp(4);
        headerGroup.addView(subtitleProgressView, subLp);
        root.addView(headerGroup, headerLp);

        // 3. 可滚动卡片容器
        ScrollView scrollView = new ScrollView(getContext());
        scrollView.setFillViewport(true);
        LinearLayout itemsContainer = new LinearLayout(getContext());
        itemsContainer.setOrientation(LinearLayout.VERTICAL);

        // --- 卡片 1: 领克官方 App (必需) ---
        LinearLayout item1Card = itemCard();
        item1IconView = statusIcon();
        item1TitleView = text("1. 领克官方 App", 16, PaseoTheme.COLOR_TEXT, Typeface.BOLD);
        item1DescView = text("正在检测官方应用...", 12, PaseoTheme.COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        item1Button = smallButton("打开领克 App", PaseoTheme.COLOR_PRIMARY, PaseoTheme.COLOR_TEXT);
        item1Button.setOnClickListener(v -> {
            if (listener != null) listener.onOpenOfficialApp();
        });
        item1Card.addView(rowHeader(item1IconView, item1TitleView));
        item1Card.addView(item1DescView, itemDescLp());
        item1Card.addView(item1Button, buttonLp());
        itemsContainer.addView(item1Card, cardLp());

        // --- 卡片 2: 核心系统权限 (必需) ---
        LinearLayout item2Card = itemCard();
        item2IconView = statusIcon();
        item2TitleView = text("2. 核心系统权限 (附近设备 + 通知监听)", 16, PaseoTheme.COLOR_TEXT, Typeface.BOLD);
        item2DescView = text("附近设备扫描：检测中 | 通知监听：检测中", 12, PaseoTheme.COLOR_TEXT_SECONDARY, Typeface.NORMAL);

        LinearLayout item2BtnRow = new LinearLayout(getContext());
        item2BtnRow.setOrientation(LinearLayout.HORIZONTAL);
        item2BtButton = smallButton("授予蓝牙权限", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        item2BtButton.setOnClickListener(v -> {
            if (listener != null) listener.onRequestBluetooth();
        });
        item2NotifButton = smallButton("开启通知使用权", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        item2NotifButton.setOnClickListener(v -> {
            if (listener != null) listener.onOpenNotificationSettings();
        });

        LinearLayout.LayoutParams btnHalfLp = new LinearLayout.LayoutParams(0, dp(PaseoTheme.TARGET_MIN_DP), 1.0f);
        btnHalfLp.rightMargin = dp(8);
        item2BtnRow.addView(item2BtButton, btnHalfLp);
        item2BtnRow.addView(item2NotifButton, new LinearLayout.LayoutParams(0, dp(PaseoTheme.TARGET_MIN_DP), 1.0f));

        item2Card.addView(rowHeader(item2IconView, item2TitleView));
        item2Card.addView(item2DescView, itemDescLp());
        item2Card.addView(item2BtnRow, buttonLp());
        itemsContainer.addView(item2Card, cardLp());

        // --- 卡片 3: 车辆自学习与车机设置 (必需) ---
        LinearLayout item3Card = itemCard();
        item3IconView = statusIcon();
        item3TitleView = text("3. 车辆自学习与车机设置", 16, PaseoTheme.COLOR_TEXT, Typeface.BOLD);
        item3DescView = text("检测车辆绑定状态中...", 12, PaseoTheme.COLOR_TEXT_SECONDARY, Typeface.NORMAL);

        TextView carSettingTip = text(
                "★ 车机设置强烈建议：进入中控屏设置 → 车辆控制-门窗，请务必关闭「靠近解锁 / 离开上锁」两项原厂开关！彻底避免车端硬件与手机通知栏并发导致车锁重复解闭锁与误锁。",
                12, PaseoTheme.COLOR_WARNING_TEXT, Typeface.NORMAL
        );
        carSettingTip.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable tipBg = new GradientDrawable();
        tipBg.setColor(PaseoTheme.COLOR_WARNING_CONTAINER);
        tipBg.setCornerRadius(dp(PaseoTheme.RADIUS_INPUT));
        tipBg.setStroke(dp(1), PaseoTheme.COLOR_WARNING);
        carSettingTip.setBackground(tipBg);

        item3Button = smallButton("去车旁打开领克 App 连车", PaseoTheme.COLOR_PRIMARY, PaseoTheme.COLOR_TEXT);
        item3Button.setOnClickListener(v -> {
            if (listener != null) listener.onOpenOfficialApp();
        });

        item3Card.addView(rowHeader(item3IconView, item3TitleView));
        item3Card.addView(item3DescView, itemDescLp());
        item3Card.addView(carSettingTip, itemDescLp());
        item3Card.addView(item3Button, buttonLp());
        itemsContainer.addView(item3Card, cardLp());

        // --- 卡片 4: 手机后台高耗电白名单 (建议/可选) ---
        LinearLayout item4Card = itemCard();
        item4IconView = statusIcon();
        item4TitleView = text("4. 后台高耗电白名单 (建议 · 可选)", 16, PaseoTheme.COLOR_TEXT, Typeface.BOLD);
        item4DescView = text(
                "此项为可选增强项，不阻塞使用。若熄屏较长时间后被系统休眠，建议允许后台高耗电以维持长效常驻。",
                12, PaseoTheme.COLOR_TEXT_SECONDARY, Typeface.NORMAL
        );
        item4Button = smallButton("设为允许高耗电", PaseoTheme.COLOR_SURFACE_2, PaseoTheme.COLOR_TEXT);
        item4Button.setOnClickListener(v -> {
            if (listener != null) listener.onRequestBatteryWhitelist();
        });

        item4Card.addView(rowHeader(item4IconView, item4TitleView));
        item4Card.addView(item4DescView, itemDescLp());
        item4Card.addView(item4Button, buttonLp());
        itemsContainer.addView(item4Card, cardLp());

        scrollView.addView(itemsContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        ));

        // 4. 底部确定/关闭按钮 (46dp 触控靶区)
        Button closeButton = new Button(getContext());
        closeButton.setText("我知道了 · 关闭自检");
        closeButton.setAllCaps(false);
        closeButton.setTextSize(14);
        closeButton.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        closeButton.setTextColor(PaseoTheme.COLOR_TEXT);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(PaseoTheme.COLOR_PRIMARY);
        closeBg.setCornerRadius(dp(PaseoTheme.RADIUS_BUTTON));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            closeButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), closeBg, null));
        } else {
            closeButton.setBackground(closeBg);
        }
        closeButton.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(PaseoTheme.TARGET_MIN_DP)
        );
        closeLp.topMargin = dp(8);
        root.addView(closeButton, closeLp);

        int screenH = getContext().getResources().getDisplayMetrics().heightPixels;
        int dialogH = Math.min((int) (screenH * 0.72f), dp(560));
        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dialogH
        ));
    }

    private void applySnapshot(ReadinessModel.Snapshot snapshot) {
        if (subtitleProgressView == null || snapshot == null) return;

        // 顶部总进度 (零 Emoji，Paseo 语义色)
        if (snapshot.isFullyReady) {
            subtitleProgressView.setText("必需项已全部达标（3/3）· 无感钥匙已就绪");
            subtitleProgressView.setTextColor(PaseoTheme.COLOR_PRIMARY_TEXT);
        } else {
            subtitleProgressView.setText(String.format(Locale.ROOT,
                    "已完成 %d/%d 项必需配置 · 满足必需项即可正常使用",
                    snapshot.requiredCompleted, snapshot.requiredTotal));
            subtitleProgressView.setTextColor(PaseoTheme.COLOR_IDENTITY_ORANGE);
        }

        // 1. 官方 App 状态
        if (snapshot.officialAppInstalled) {
            item1IconView.setText("●");
            item1IconView.setTextColor(PaseoTheme.COLOR_PRIMARY_TEXT);
            item1DescView.setText("已安装领克官方 App，前台钥匙通道支持就绪。");
            item1DescView.setTextColor(PaseoTheme.COLOR_TEXT_SECONDARY);
            setButtonState(item1Button, true, "打开领克 App", true);
        } else {
            item1IconView.setText("●");
            item1IconView.setTextColor(PaseoTheme.COLOR_ERROR);
            item1DescView.setText("未安装领克官方 App！本应用依赖官方钥匙鉴权通道，请先在应用商店安装。");
            item1DescView.setTextColor(PaseoTheme.COLOR_ERROR);
            setButtonState(item1Button, false, "未检测到应用", false);
        }

        // 2. 核心权限状态
        boolean btOk = snapshot.btScanGranted;
        boolean notifOk = snapshot.notifAccessGranted;

        if (snapshot.corePermissionsGranted) {
            item2IconView.setText("●");
            item2IconView.setTextColor(PaseoTheme.COLOR_PRIMARY_TEXT);
            item2DescView.setText("附近设备/蓝牙权限：已授予\n通知监听权限：正常就绪");
            item2DescView.setTextColor(PaseoTheme.COLOR_TEXT_SECONDARY);
            setButtonState(item2BtButton, false, "蓝牙已授权", false);
            setButtonState(item2NotifButton, false, "通知已就绪", false);
        } else {
            item2IconView.setText("●");
            item2IconView.setTextColor(PaseoTheme.COLOR_IDENTITY_ORANGE);
            StringBuilder sb = new StringBuilder();
            sb.append("附近设备/蓝牙：").append(btOk ? "已授予" : "未授予 (无法扫描广播)").append("\n");
            sb.append("通知监听：").append(notifOk ? "正常" : "未开启 (无法解闭锁)");
            item2DescView.setText(sb.toString());
            item2DescView.setTextColor(PaseoTheme.COLOR_IDENTITY_ORANGE);

            setButtonState(item2BtButton, !btOk, btOk ? "蓝牙已授权" : "授予蓝牙权限", false);
            setButtonState(item2NotifButton, !notifOk, notifOk ? "通知已开启" : "开启通知使用权", false);
        }

        // 3. 车辆自学习状态
        if (snapshot.vehicleLearned) {
            item3IconView.setText("●");
            item3IconView.setTextColor(PaseoTheme.COLOR_PRIMARY_TEXT);
            item3DescView.setText(String.format(Locale.ROOT,
                    "已绑定车辆：%s\n蓝牙物理特征：%s",
                    snapshot.vehicleName.isEmpty() ? "领克爱车" : snapshot.vehicleName,
                    snapshot.vehicleAddress.isEmpty() ? "--" : snapshot.vehicleAddress));
            item3DescView.setTextColor(PaseoTheme.COLOR_TEXT_SECONDARY);
            setButtonState(item3Button, true, "重新学习车辆 (去车旁)", false);
        } else {
            item3IconView.setText("●");
            item3IconView.setTextColor(PaseoTheme.COLOR_IDENTITY_ORANGE);
            item3DescView.setText("尚未识别到本车蓝牙身份。\n请带手机到车旁，打开官方 App 连一次车，本应用将自动捕获并记住。");
            item3DescView.setTextColor(PaseoTheme.COLOR_IDENTITY_ORANGE);
            setButtonState(item3Button, true, "去车旁打开领克连车", true);
        }

        // 4. 后台高耗电白名单 (建议/可选)
        if (snapshot.batteryWhitelisted) {
            item4IconView.setText("●");
            item4IconView.setTextColor(PaseoTheme.COLOR_PRIMARY_TEXT);
            item4DescView.setText("已加入系统电池优化白名单，息屏常驻受保护。");
            item4DescView.setTextColor(PaseoTheme.COLOR_TEXT_SECONDARY);
            setButtonState(item4Button, false, "已设为允许", false);
        } else {
            item4IconView.setText("●");
            item4IconView.setTextColor(PaseoTheme.COLOR_TEXT_TERTIARY);
            item4DescView.setText("未加入电池白名单（可选项，不阻断使用）。若锁屏较长时间后感应变慢或休眠，建议设为允许。");
            item4DescView.setTextColor(PaseoTheme.COLOR_TEXT_SECONDARY);
            setButtonState(item4Button, true, "加入电池白名单", false);
        }
    }

    private LinearLayout itemCard() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        layout.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PaseoTheme.COLOR_CARD); // #1E2120
        bg.setCornerRadius(dp(PaseoTheme.RADIUS_CARD)); // 12dp
        bg.setStroke(dp(PaseoTheme.BORDER_WIDTH_DP), PaseoTheme.COLOR_BORDER); // #252B2A
        layout.setBackground(bg);
        return layout;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(10);
        return lp;
    }

    private LinearLayout rowHeader(TextView icon, TextView title) {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(icon);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleLp.leftMargin = dp(6);
        header.addView(title, titleLp);
        return header;
    }

    private TextView statusIcon() {
        TextView icon = new TextView(getContext());
        icon.setText("●");
        icon.setTextSize(14);
        icon.setTextColor(PaseoTheme.COLOR_TEXT_TERTIARY);
        return icon;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private LinearLayout.LayoutParams itemDescLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(PaseoTheme.TARGET_MIN_DP)
        );
        lp.bottomMargin = dp(4);
        return lp;
    }

    private Button smallButton(String label, int bgColor, int textColor) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setButtonState(button, true, label, bgColor == PaseoTheme.COLOR_PRIMARY);
        return button;
    }

    private void setButtonState(Button button, boolean enabled, String label, boolean isPrimary) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setText(label);
        int bgColor;
        int textColor;
        if (!enabled) {
            bgColor = 0xFF222524;
            textColor = PaseoTheme.COLOR_TEXT_TERTIARY;
        } else if (isPrimary) {
            bgColor = PaseoTheme.COLOR_PRIMARY;
            textColor = PaseoTheme.COLOR_TEXT;
        } else {
            bgColor = PaseoTheme.COLOR_SURFACE_2;
            textColor = PaseoTheme.COLOR_TEXT;
        }
        button.setTextColor(textColor);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(PaseoTheme.RADIUS_INPUT));
        if (!enabled) {
            bg.setStroke(dp(PaseoTheme.BORDER_WIDTH_DP), 0xFF2A2E2D);
        } else if (isPrimary) {
            bg.setStroke(0, 0);
        } else {
            bg.setStroke(dp(PaseoTheme.BORDER_WIDTH_DP), 0xFF3D4643);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rippleColor = enabled ? 0x22FFFFFF : 0;
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null));
        } else {
            button.setBackground(bg);
        }
    }

    private int dp(int value) {
        return (int) (value * getContext().getResources().getDisplayMetrics().density);
    }
}
