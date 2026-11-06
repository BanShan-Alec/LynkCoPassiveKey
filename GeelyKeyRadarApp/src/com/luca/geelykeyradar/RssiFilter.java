package com.luca.geelykeyradar;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * BLE RSSI 平滑器：中值限幅 + 一维卡尔曼 + 非对称 EMA。
 *
 * 目的不是算“精确距离”，而是在息屏靠近时足够快、在车旁抖动时足够稳。
 */
final class RssiFilter {

    private static final long GAP_RESET_MS = 8000L;
    private static final float KALMAN_Q = 0.08f;
    private static final float KALMAN_R = 2.20f;
    private static final float EMA_UP = 0.62f; // 走近车辆信号增强时快速跟随，3米处不滞后
    private static final float EMA_DOWN = 0.16f;
    private static final float MAX_SLEW_DB_PER_SEC = 40.0f;
    private static final int WINDOW_MAX = 15;
    private static final long WINDOW_MS = 4000L;

    private final ArrayDeque<float[]> window = new ArrayDeque<>();
    private boolean initialized = false;
    private float kalmanEstimate = -75.0f;
    private float kalmanErrorCov = 1.0f;
    private float smoothDbm = -75.0f;
    private float previousSmoothDbm = -75.0f;
    private long lastSampleAtMs = 0L;

    /**
     * 送入一条原始 RSSI，返回平滑后的值。
     */
    synchronized float update(float rawDbm, long nowMs) {
        if (!initialized || lastSampleAtMs == 0L || (nowMs - lastSampleAtMs) > GAP_RESET_MS) {
            reset(rawDbm);
            lastSampleAtMs = nowMs;
            return smoothDbm;
        }

        addToWindow(nowMs, rawDbm);
        float measurement = rawDbm;
        if (window.size() >= 3) {
            float median = median();
            if (Math.abs(rawDbm - median) > 14.0f) {
                measurement = median + Math.signum(rawDbm - median) * 14.0f;
            }
        }

        float predictedCov = kalmanErrorCov + KALMAN_Q;
        float gain = predictedCov / (predictedCov + KALMAN_R);
        kalmanEstimate = kalmanEstimate + gain * (measurement - kalmanEstimate);
        kalmanErrorCov = (1.0f - gain) * predictedCov;

        float dtSec = Math.max(0.05f, (nowMs - lastSampleAtMs) / 1000.0f);
        float alpha = (kalmanEstimate >= smoothDbm) ? EMA_UP : EMA_DOWN;
        previousSmoothDbm = smoothDbm;
        smoothDbm = alpha * kalmanEstimate + (1.0f - alpha) * smoothDbm;

        float maxStep = MAX_SLEW_DB_PER_SEC * Math.min(2.0f, dtSec);
        float delta = smoothDbm - previousSmoothDbm;
        if (Math.abs(delta) > maxStep) {
            smoothDbm = previousSmoothDbm + Math.signum(delta) * maxStep;
        }

        lastSampleAtMs = nowMs;
        return smoothDbm;
    }

    synchronized void reset(float rawDbm) {
        window.clear();
        kalmanEstimate = rawDbm;
        kalmanErrorCov = 1.0f;
        smoothDbm = rawDbm;
        previousSmoothDbm = rawDbm;
        initialized = true;
        lastSampleAtMs = 0L;
        addToWindow(System.currentTimeMillis(), rawDbm);
    }

    synchronized float getSmoothDbm() {
        return smoothDbm;
    }

    synchronized long getLastSampleAtMs() {
        return lastSampleAtMs;
    }

    private void addToWindow(long nowMs, float rawDbm) {
        window.addLast(new float[]{nowMs, rawDbm});
        while (!window.isEmpty() && (nowMs - window.peekFirst()[0]) > WINDOW_MS) {
            window.removeFirst();
        }
        while (window.size() > WINDOW_MAX) {
            window.removeFirst();
        }
    }

    private float median() {
        int n = window.size();
        if (n == 0) return -75.0f;
        float[] values = new float[n];
        int i = 0;
        for (float[] item : window) {
            values[i++] = item[1];
        }
        Arrays.sort(values);
        return values[n / 2];
    }
}
