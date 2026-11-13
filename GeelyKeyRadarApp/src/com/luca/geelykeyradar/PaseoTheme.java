package com.luca.geelykeyradar;

/**
 * Paseo Dark 设计规范（DESIGN.md）集中 Token 与数值阶梯。
 * 纯 Java 常量类，可无损在纯逻辑单测与 Android 运行时双向复用。
 * 遵循《Refactoring UI》十二大系统阶梯与约束层体系。
 */
public final class PaseoTheme {

    private PaseoTheme() {}

    // --- 4.1 深色表面阶梯 (Dark Surfaces & Elevation) ---
    public static final int COLOR_BG = 0xFF181B1A;          // --paseo-bg-layout (surface-0)
    public static final int COLOR_CARD = 0xFF1E2120;        // --paseo-bg-container (surface-1)
    public static final int COLOR_SURFACE_2 = 0xFF272A29;   // --paseo-surface-2
    public static final int COLOR_SURFACE_3 = 0xFF434645;   // --paseo-surface-3 (modal)
    public static final int COLOR_SURFACE_4 = 0xFF595B5B;   // --paseo-surface-4 (muted controls)
    public static final int COLOR_BORDER = 0xFF252B2A;      // --paseo-border
    public static final int COLOR_BORDER_SECONDARY = 0xFF2F3534; // --paseo-border-secondary
    public static final int COLOR_BORDER_STRONG = 0xFF3D4643;    // --paseo-border-strong (次级按钮/高强调描边)

    // --- 4.2 品牌主色阶 (Paseo Primary Ramp) ---
    public static final int COLOR_PRIMARY = 0xFF20744A;        // --paseo-primary
    public static final int COLOR_PRIMARY_HOVER = 0xFF347552;  // --paseo-primary-hover
    public static final int COLOR_PRIMARY_ACTIVE = 0xFF1C5237; // --paseo-primary-active
    public static final int COLOR_PRIMARY_BG = 0xFF183124;     // --paseo-primary-bg
    public static final int COLOR_PRIMARY_TEXT = 0xFF7CCBA0;   // --paseo-primary-text

    // --- 4.3 文本三层级系统 (Text Tiers) ---
    public static final int COLOR_TEXT = 0xFFFAFAFA;           // --paseo-text (Tier 1)
    public static final int COLOR_TEXT_SECONDARY = 0xFFA1A5A4; // --paseo-text-secondary (Tier 2)
    public static final int COLOR_TEXT_TERTIARY = 0xFF8A8E8D;  // --paseo-text-tertiary (Tier 3)

    // --- 4.4 状态语义色阶 (Status Semantics) ---
    public static final int COLOR_SUCCESS = 0xFF6CB17B;           // 成功绿
    public static final int COLOR_SUCCESS_TEXT = 0xFFC4D0C6;
    public static final int COLOR_SUCCESS_CONTAINER = 0xFF19201B;
    public static final int COLOR_SUCCESS_DOT = 0xFF35C264;

    public static final int COLOR_WARNING = 0xFFC09664;           // 警告金
    public static final int COLOR_WARNING_TEXT = 0xFFE0DBD2;
    public static final int COLOR_WARNING_CONTAINER = 0xFF221D18;
    public static final int COLOR_WARNING_DOT = 0xFFDB932E;

    public static final int COLOR_ERROR = 0xFFD8847B;             // 危险红
    public static final int COLOR_ERROR_TEXT = 0xFFF8ECE9;
    public static final int COLOR_ERROR_CONTAINER = 0xFF261B1B;
    public static final int COLOR_ERROR_DOT = 0xFFF7796D;

    public static final int COLOR_INFO = 0xFF5CAAF6;              // 信息蓝 (冷场雷达)
    public static final int COLOR_INFO_TEXT = 0xFFD3E9F8;
    public static final int COLOR_INFO_CONTAINER = 0xFF18202A;

    public static final int COLOR_IDENTITY_ORANGE = 0xFFCC8F64;   // 身份橙 (暖场雷达)
    public static final int COLOR_IDENTITY_INK = 0xFF1F1E1D;

    // --- 5 & 6. 间距系统 (Spacing Scale) ---
    public static final int PADDING_PAGE_H = 20;      // 页面横向内边距 (dp)
    public static final int PADDING_PAGE_TOP = 48;    // 页面顶部安全距离 (dp)
    public static final int PADDING_PAGE_BOTTOM = 32; // 页面底部留白 (dp)
    public static final int PADDING_CARD = 16;        // 卡片全方向内边距 (dp)
    public static final int MARGIN_CARD_BOTTOM = 14;  // 卡片间距 (dp)
    public static final int SPACING_ELEMENT = 10;     // 卡片内组件垂直间距 (dp)
    public static final int SPACING_COMPACT = 4;      // 紧凑文本微间距 (dp)

    // --- 7 & 10. 尺寸与圆角系统 (Sizes & Radii) ---
    public static final int TARGET_MIN_DP = 46;       // 触控安全靶区 (dp)
    public static final int RADIUS_CARD = 12;         // 卡片标准圆角 (dp)
    public static final int RADIUS_BUTTON = 12;       // 主按钮标准圆角 (dp)
    public static final int RADIUS_INPUT = 8;         // 输入框/次级圆角 (dp)
    public static final int RADIUS_BADGE = 4;         // 状态微徽章圆角 (dp)
    public static final int BORDER_WIDTH_DP = 1;      // 容器标准描边 (dp)
}
