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
        System.out.println("RadarVisualModelTest passed");
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
        assert s.temperature == RadarVisualModel.TemperatureState.COLD : "低于 -84dBm 应为深蓝冷场";
        assert s.primaryColor == PaseoTheme.COLOR_INFO;
        assert s.pulseDurationMs == RadarVisualModel.PULSE_SLOW_MS;
        assert !s.triggerHaptic;
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
