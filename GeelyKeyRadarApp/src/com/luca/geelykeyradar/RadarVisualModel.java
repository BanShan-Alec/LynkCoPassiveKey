package com.luca.geelykeyradar;

import java.util.Locale;

/**
 * 冷暖雷达视觉状态映射器。
 * 纯逻辑层：将底层 BLE/RSSI 信号及业务状态无损映射为雷达的色温、动效节奏、触觉震动及低层级读数。
 * 可在纯 Java 单元测试中 100% 覆盖。
 */
public final class RadarVisualModel {

    public enum TemperatureState {
        DISCONNECTED,
        COLD,
        WARM,
        HOT_UNLOCKED,
        CHARGING_PROTECTED
    }

    public static final long PULSE_SLOW_MS = 2400L;
    public static final long PULSE_MID_MS = 1500L;
    public static final long PULSE_FAST_MS = 900L;

    private boolean wasHot = false;

    /**
     * 计算当前状态下的雷达视觉与触觉输出。
     */
    public State evaluate(
            boolean serviceEnabled,
            boolean bluetoothOn,
            boolean geelyConnected,
            int rssi,
            boolean chargingProtected,
            float unlockDbm,
            float lockDbm
    ) {
        if (!serviceEnabled || !bluetoothOn) {
            wasHot = false;
            return new State(
                    TemperatureState.DISCONNECTED,
                    PaseoTheme.COLOR_SURFACE_4,
                    PaseoTheme.COLOR_SURFACE_2,
                    0L,
                    false,
                    !serviceEnabled ? "无感进出服务已关闭" : "手机蓝牙未开启",
                    "-- dBm · 门限 " + formatDbm(unlockDbm)
            );
        }

        if (chargingProtected) {
            wasHot = false;
            return new State(
                    TemperatureState.CHARGING_PROTECTED,
                    PaseoTheme.COLOR_WARNING,
                    PaseoTheme.COLOR_WARNING_CONTAINER,
                    PULSE_SLOW_MS,
                    false,
                    "车载充电中 · 锁定保护生效",
                    (rssi > -127 ? rssi + " dBm" : "--") + " · 门限 " + formatDbm(unlockDbm)
            );
        }

        if (rssi <= -127 || !geelyConnected && rssi < -100) {
            wasHot = false;
            return new State(
                    TemperatureState.DISCONNECTED,
                    PaseoTheme.COLOR_SURFACE_4,
                    PaseoTheme.COLOR_SURFACE_2,
                    0L,
                    false,
                    "正在搜索爱车钥匙...",
                    "-- dBm · 解锁门限 " + formatDbm(unlockDbm)
            );
        }

        if (rssi >= unlockDbm) {
            boolean triggerHaptic = !wasHot;
            wasHot = true;
            return new State(
                    TemperatureState.HOT_UNLOCKED,
                    PaseoTheme.COLOR_PRIMARY_TEXT,
                    PaseoTheme.COLOR_PRIMARY_BG,
                    PULSE_FAST_MS,
                    triggerHaptic,
                    "已在车旁 · 门锁已解开",
                    "实时 " + rssi + " dBm · 解锁门限 " + formatDbm(unlockDbm)
            );
        }

        wasHot = false;

        if (rssi >= -84) {
            // 中场渐温逼近
            return new State(
                    TemperatureState.WARM,
                    PaseoTheme.COLOR_IDENTITY_ORANGE,
                    PaseoTheme.COLOR_WARNING_CONTAINER,
                    PULSE_MID_MS,
                    false,
                    "正在靠近爱车...",
                    "实时 " + rssi + " dBm · 门限 " + formatDbm(unlockDbm)
            );
        }

        // 远场深蓝
        return new State(
                TemperatureState.COLD,
                PaseoTheme.COLOR_INFO,
                PaseoTheme.COLOR_INFO_CONTAINER,
                PULSE_SLOW_MS,
                false,
                "距离爱车较远",
                "实时 " + rssi + " dBm · 门限 " + formatDbm(unlockDbm)
        );
    }

    private static String formatDbm(float dbm) {
        return String.format(Locale.US, "%.0f dBm", dbm);
    }

    public static final class State {
        public final TemperatureState temperature;
        public final int primaryColor;
        public final int containerColor;
        public final long pulseDurationMs;
        public final boolean triggerHaptic;
        public final String statusText;
        public final String secondaryTechText;

        public State(
                TemperatureState temperature,
                int primaryColor,
                int containerColor,
                long pulseDurationMs,
                boolean triggerHaptic,
                String statusText,
                String secondaryTechText
        ) {
            this.temperature = temperature;
            this.primaryColor = primaryColor;
            this.containerColor = containerColor;
            this.pulseDurationMs = pulseDurationMs;
            this.triggerHaptic = triggerHaptic;
            this.statusText = statusText;
            this.secondaryTechText = secondaryTechText;
        }
    }
}
