package com.luca.geelykeyradar;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * BLE RSSI 平滑器：三点中值 + 按真实采样间隔计算的非对称 EMA。
 *
 * 目的不是算“精确距离”，而是在息屏靠近时足够快、在车旁抖动时足够稳。
 * 原中值限幅 + 低增益卡尔曼 + EMA 会叠加数秒滞后，因此不再串联两层低通。
 */
final class RssiFilter {

    private static final long GAP_RESET_MS = 3000L;
    private static final double APPROACH_TIME_CONSTANT_MS = 350.0;
    private static final double DEPARTURE_TIME_CONSTANT_MS = 750.0;
    private static final int WINDOW_MAX = 3;
    private static final long WINDOW_MS = 2500L;

    private final ArrayDeque<Sample> window = new ArrayDeque<>();
    private boolean initialized = false;
    private float smoothDbm = -75.0f;
    private long lastSampleAtMs = 0L;

    private static final class Sample {
        final long timeMs;
        final float dbm;

        /** 使用 long 保存单调时间，避免 float 时间戳丢失毫秒精度。 */
        Sample(long timeMs, float dbm) {
            this.timeMs = timeMs;
            this.dbm = dbm;
        }
    }

    /**
     * 送入一条原始 RSSI，返回平滑后的值。
     */
    synchronized float update(float rawDbm, long nowMs) {
        if (!Float.isFinite(rawDbm) || rawDbm <= -105f || rawDbm >= 0f) {
            return smoothDbm;
        }
        if (!initialized || (nowMs - lastSampleAtMs) > GAP_RESET_MS) {
            reset(rawDbm, nowMs);
            return smoothDbm;
        }
        if (nowMs <= lastSampleAtMs) {
            return smoothDbm;
        }

        addToWindow(nowMs, rawDbm);
        float measurement = median();
        double timeConstant = measurement >= smoothDbm
                ? APPROACH_TIME_CONSTANT_MS : DEPARTURE_TIME_CONSTANT_MS;
        float alpha = (float) (1.0 - Math.exp(-(nowMs - lastSampleAtMs) / timeConstant));
        smoothDbm += alpha * (measurement - smoothDbm);
        if (Math.abs(measurement - smoothDbm) < 0.05f) {
            smoothDbm = measurement;
        }
        lastSampleAtMs = nowMs;
        return smoothDbm;
    }

    /** 断档后以当前新鲜样本重建窗口，不继承上一次接近的旧信号。 */
    private void reset(float rawDbm, long nowMs) {
        window.clear();
        smoothDbm = rawDbm;
        initialized = true;
        lastSampleAtMs = nowMs;
        addToWindow(nowMs, rawDbm);
    }

    /** 返回最近滤波值。 */
    synchronized float getSmoothDbm() {
        return smoothDbm;
    }

    /** 返回最后一条有效样本的单调时间。 */
    synchronized long getLastSampleAtMs() {
        return lastSampleAtMs;
    }

    /** 同时限制窗口样本数和样本年龄。 */
    private void addToWindow(long nowMs, float rawDbm) {
        window.addLast(new Sample(nowMs, rawDbm));
        while (!window.isEmpty() && (nowMs - window.peekFirst().timeMs) > WINDOW_MS) {
            window.removeFirst();
        }
        while (window.size() > WINDOW_MAX) {
            window.removeFirst();
        }
    }

    /** 三点中值抑制单次强弱尖峰；两点时保留较强值，避免一次衰落误判离开。 */
    private float median() {
        int count = window.size();
        if (count == 0) return smoothDbm;
        float[] values = new float[count];
        int index = 0;
        for (Sample sample : window) {
            values[index++] = sample.dbm;
        }
        Arrays.sort(values);
        return values[count / 2];
    }
}
