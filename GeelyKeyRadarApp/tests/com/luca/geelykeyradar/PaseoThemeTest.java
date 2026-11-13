package com.luca.geelykeyradar;

/**
 * 验证 Paseo Dark 设计规范（DESIGN.md）核心 Token 与空间/层级约束。
 */
public class PaseoThemeTest {

    public static void main(String[] args) {
        testDarkSurfaces();
        testPrimaryRamp();
        testTextTiers();
        testStatusSemantics();
        testSpacingScaleAndOuterInnerRule();
        testTouchTargetAndRadius();
        System.out.println("PaseoThemeTest passed");
    }

    private static void testDarkSurfaces() {
        assert PaseoTheme.COLOR_BG == 0xFF181B1A : "surface-0 must be #181B1A";
        assert PaseoTheme.COLOR_CARD == 0xFF1E2120 : "surface-1 must be #1E2120";
        assert PaseoTheme.COLOR_SURFACE_2 == 0xFF272A29 : "surface-2 must be #272A29";
        assert PaseoTheme.COLOR_SURFACE_3 == 0xFF434645 : "surface-3 must be #434645";
        assert PaseoTheme.COLOR_SURFACE_4 == 0xFF595B5B : "surface-4 must be #595B5B";
        assert PaseoTheme.COLOR_BORDER == 0xFF252B2A : "border must be #252B2A";
        assert PaseoTheme.COLOR_BORDER_STRONG == 0xFF3D4643 : "border strong must be #3D4643";
    }

    private static void testPrimaryRamp() {
        assert PaseoTheme.COLOR_PRIMARY == 0xFF20744A : "primary must be #20744A";
        assert PaseoTheme.COLOR_PRIMARY_HOVER == 0xFF347552 : "primary hover must be #347552";
        assert PaseoTheme.COLOR_PRIMARY_ACTIVE == 0xFF1C5237 : "primary active must be #1C5237";
        assert PaseoTheme.COLOR_PRIMARY_BG == 0xFF183124 : "primary bg container must be #183124";
        assert PaseoTheme.COLOR_PRIMARY_TEXT == 0xFF7CCBA0 : "primary text must be #7CCBA0";
    }

    private static void testTextTiers() {
        assert PaseoTheme.COLOR_TEXT == 0xFFFAFAFA : "Tier 1 text must be #FAFAFA";
        assert PaseoTheme.COLOR_TEXT_SECONDARY == 0xFFA1A5A4 : "Tier 2 text must be #A1A5A4";
        assert PaseoTheme.COLOR_TEXT_TERTIARY == 0xFF8A8E8D : "Tier 3 text must be #8A8E8D";
    }

    private static void testStatusSemantics() {
        assert PaseoTheme.COLOR_SUCCESS == 0xFF6CB17B : "success must be #6CB17B";
        assert PaseoTheme.COLOR_WARNING == 0xFFC09664 : "warning must be #C09664";
        assert PaseoTheme.COLOR_ERROR == 0xFFD8847B : "error must be #D8847B";
        assert PaseoTheme.COLOR_INFO == 0xFF5CAAF6 : "info must be #5CAAF6";
        assert PaseoTheme.COLOR_IDENTITY_ORANGE == 0xFFCC8F64 : "identity orange must be #CC8F64";
        assert PaseoTheme.COLOR_IDENTITY_INK == 0xFF1F1E1D : "identity ink must be #1F1E1D";
    }

    private static void testSpacingScaleAndOuterInnerRule() {
        // DESIGN.md §3.6 Outer > Inner 空间铁律
        assert PaseoTheme.PADDING_CARD > PaseoTheme.SPACING_ELEMENT : "Card padding must exceed element spacing";
        assert PaseoTheme.SPACING_ELEMENT > PaseoTheme.SPACING_COMPACT : "Element spacing must exceed compact spacing";
        assert PaseoTheme.MARGIN_CARD_BOTTOM == 14 : "Card bottom margin must be 14dp";
        assert PaseoTheme.PADDING_PAGE_H == 20 : "Page horizontal padding must be 20dp";
        assert PaseoTheme.PADDING_CARD == 16 : "Card padding must be 16dp";
        assert PaseoTheme.SPACING_ELEMENT == 10 : "Element spacing must be 10dp";
        assert PaseoTheme.SPACING_COMPACT == 4 : "Compact text spacing must be 4dp";
    }

    private static void testTouchTargetAndRadius() {
        assert PaseoTheme.TARGET_MIN_DP >= 46 : "Touch target must be at least 46dp";
        assert PaseoTheme.RADIUS_CARD == 12 : "Card radius must be 12dp";
        assert PaseoTheme.RADIUS_BUTTON == 12 : "Button radius must be 12dp";
        assert PaseoTheme.RADIUS_INPUT == 8 : "Input/secondary chip radius must be 8dp";
    }
}
