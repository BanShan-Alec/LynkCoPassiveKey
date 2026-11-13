package com.luca.geelykeyradar;

/**
 * 验证冷暖雷达状态映射的颜色、波纹脉冲时长及触觉单次触发逻辑。
 */
public final class RadarVisualModelTest {

    public static void main(String[] args) {
        testDisconnected();
        testColdFarField();
        testWarmApproaching();
        testHotUnlockAndSingleHaptic();
        testChargingProtection();
        testDepartingDwellDisplaysLeavingAndCountdown();
        testLockedCompletedState();
        System.out.println("RadarVisualModelTest passed");
    }

    private static void testDepartingDwellDisplaysLeavingAndCountdown() {
        RadarVisualModel model = new RadarVisualModel();
        // 人在离开，驻留进行到 2.5 秒，总驻留 4.5 秒，信号 -85 dBm
        RadarVisualModel.State s = model.evaluate(true, true, true, -85, false, -75f, -82f, 2500L, 4500L, false);
        assert "正在远离爱车...".equals(s.statusText) : "处于离开驻留时文案必须为「正在远离爱车...」，实际为：" + s.statusText;
        assert s.secondaryTechText.contains("离开确认 2.5s / 4.5s") : "副文本必须包含离开确认倒计时，实际为：" + s.secondaryTechText;
    }

    private static void testLockedCompletedState() {
        RadarVisualModel model = new RadarVisualModel();
        // 1. 上锁已完成远场态
        RadarVisualModel.State s1 = model.evaluate(true, true, true, -88, false, -75f, -82f, 0L, 4500L, true);
        assert "已离开车辆 · 门锁已闭锁".equals(s1.statusText) : "闭锁完成后必须显示「已离开车辆 · 门锁已闭锁」，实际为：" + s1.statusText;

        // 2. 闭锁后信号轻微回弹（例如 -80 dBm，高于 lockDbm 但低于 unlockDbm），仍必须稳定显示闭锁态
        RadarVisualModel.State s2 = model.evaluate(true, true, true, -80, false, -75f, -82f, 0L, 4500L, true);
        assert "已离开车辆 · 门锁已闭锁".equals(s2.statusText) : "闭锁后信号回弹仍须稳定显示「已离开车辆 · 门锁已闭锁」，实际为：" + s2.statusText;
        assert s2.temperature == RadarVisualModel.TemperatureState.COLD;
    }

    private static void testDisconnected() {
        RadarVisualModel model = new RadarVisualModel();
        RadarVisualModel.State s1 = model.evaluate(false, true, false, -70, false, -75f, -82f);
        assert s1.temperature == RadarVisualModel.TemperatureState.DISCONNECTED;
        assert s1.primaryColor == PaseoTheme.COLOR_SURFACE_4;
        assert !s1.triggerHaptic;

        RadarVisualModel.State s2 = model.evaluate(true, false, false, -70, false, -75f, -82f);
        assert s2.temperature == RadarVisualModel.TemperatureState.DISCONNECTED;
    }

    private static void testColdFarField() {
        RadarVisualModel model = new RadarVisualModel();
        RadarVisualModel.State s = model.evaluate(true, true, true, -90, false, -75f, -82f);
        assert s.temperature == RadarVisualModel.TemperatureState.COLD : "低于 lockDbm 应为深蓝冷场";
        assert s.primaryColor == PaseoTheme.COLOR_INFO;
        assert s.pulseDurationMs == RadarVisualModel.PULSE_SLOW_MS;
        assert !s.triggerHaptic;
        assert "距离爱车较远".equals(s.statusText) : "无驻留倒计时的静态远场必须显示「距离爱车较远」，实际为：" + s.statusText;
    }

    private static void testWarmApproaching() {
        RadarVisualModel model = new RadarVisualModel();
        RadarVisualModel.State s = model.evaluate(true, true, true, -80, false, -75f, -82f);
        assert s.temperature == RadarVisualModel.TemperatureState.WARM : "-80dBm 应为暖金逼近态";
        assert s.primaryColor == PaseoTheme.COLOR_IDENTITY_ORANGE;
        assert s.pulseDurationMs == RadarVisualModel.PULSE_MID_MS;
        assert !s.triggerHaptic;
    }

    private static void testHotUnlockAndSingleHaptic() {
        RadarVisualModel model = new RadarVisualModel();
        // 首次跨过 -75 dBm: 触发极热翡翠绿，且 triggerHaptic 必须为 true
        RadarVisualModel.State s1 = model.evaluate(true, true, true, -72, false, -75f, -82f);
        assert s1.temperature == RadarVisualModel.TemperatureState.HOT_UNLOCKED : "≥ -75dBm 应为极热解锁";
        assert s1.primaryColor == PaseoTheme.COLOR_PRIMARY_TEXT;
        assert s1.pulseDurationMs == RadarVisualModel.PULSE_FAST_MS;
        assert s1.triggerHaptic : "首次跨入解锁区应触发触觉反馈";

        // 第二次仍在极热区：不得重复触发震动
        RadarVisualModel.State s2 = model.evaluate(true, true, true, -71, false, -75f, -82f);
        assert s2.temperature == RadarVisualModel.TemperatureState.HOT_UNLOCKED;
        assert !s2.triggerHaptic : "持续在解锁区不应重复震动";

        // 离开车辆退至冷场后再次走入：应再次触发单次震动
        model.evaluate(true, true, true, -88, false, -75f, -82f);
        RadarVisualModel.State s3 = model.evaluate(true, true, true, -74, false, -75f, -82f);
        assert s3.triggerHaptic : "重新靠近应能再次触发震动";
    }

    private static void testChargingProtection() {
        RadarVisualModel model = new RadarVisualModel();
        RadarVisualModel.State s = model.evaluate(true, true, true, -68, true, -75f, -82f);
        assert s.temperature == RadarVisualModel.TemperatureState.CHARGING_PROTECTED : "充电防锁优先级应高于普通解锁";
        assert s.primaryColor == PaseoTheme.COLOR_WARNING;
        assert !s.triggerHaptic;
    }
}
