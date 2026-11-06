package com.luca.geelykeyradar;

/**
 * 极简无感钥匙状态机。
 *
 * 只做两件事：
 * 1. 有效信号进入近场阈值 -> 触发一次解锁；
 * 2. 有效信号持续处于远场阈值 -> 触发一次上锁。
 *
 * 额外保留的安全约束：
 * - 冷启动/一直在远处时，不主动补发上锁；
 * - 蓝牙断连、无新样本时不使用旧 RSSI 误锁；
 * - 同一接近会话只解锁一次，必须真正离开并持续远场后才重新武装；
 * - 行车中不自动上锁，也不重新武装。
 */
final class PassiveKeyLogic {

    enum Action {
        NONE,
        UNLOCK,
        LOCK
    }

    static final float UNLOCK_DBM = -75.0f; // 实车验证最佳提前解锁阈值
    static final float LOCK_DBM = -82.0f;
    static final float REARM_HYSTERESIS_DBM = 4.0f;
    static final float NEAR_GUARD_DBM = -78.0f;
    static final long LOCK_DWELL_MS = 4500L;
    static final long NEAR_GUARD_MS = 6000L;
    static final long UNLOCK_COOLDOWN_MS = 8000L;
    static final long REARM_FAR_MS = 8000L;
    static final long REARM_DISCONNECTED_SILENCE_MS = 12000L;
    static final long SIGNAL_STALE_MS = 3000L;
    static final long SIGNAL_LOST_INHIBIT_MS = 10000L;
    static final int LOCK_MIN_SAMPLES = 3;

    private float unlockDbm = UNLOCK_DBM;
    private float lockDbm = LOCK_DBM;
    private long lockDwellMs = LOCK_DWELL_MS;

    private boolean approachLatched = false;
    private boolean hasBeenNear = false;
    private long lastUnlockAtMs = 0L;
    private long lockDwellStartMs = 0L;
    private int lockDwellSamples = 0;
    private long farSinceMs = 0L;
    private long lastSampleAtMs = 0L;
    private long lastNearAtMs = 0L;
    private long officialDisconnectedAtMs = 0L;
    private long lockInhibitUntilMs = 0L;

    /**
     * 动态更新自定义阈值（靠近解锁阈值、离开上锁阈值、离开确认毫秒数）。
     */
    synchronized void setThresholds(float unlockDbm, float lockDbm, long lockDwellMs) {
        this.unlockDbm = unlockDbm;
        this.lockDbm = Math.min(lockDbm, unlockDbm - 6.0f);
        this.lockDwellMs = Math.max(1500L, lockDwellMs);
        resetLockDwell();
    }

    synchronized float getUnlockDbm() {
        return unlockDbm;
    }

    synchronized float getLockDbm() {
        return lockDbm;
    }

    synchronized long getLockDwellMs() {
        return lockDwellMs;
    }

    /**
     * 输入一条新鲜的有效 RSSI，返回本样本应执行的动作。
     *
     * @param effectiveDbm     经过滤波和口袋补偿后的有效信号
     * @param nowMs            当前时间
     * @param officialConnected 银河 App 的蓝牙钥匙是否已连接
     * @param driving          银河通知是否明确显示“行驶中”
     * @param autoUnlock       用户是否开启靠近自动解锁
     * @param autoLock         用户是否开启远离自动上锁
     */
    synchronized Action onSample(
            float effectiveDbm,
            long nowMs,
            boolean officialConnected,
            boolean driving,
            boolean autoUnlock,
            boolean autoLock
    ) {
        lastSampleAtMs = nowMs;
        if (officialConnected) {
            officialDisconnectedAtMs = 0L;
        }
        float nearGuard = Math.max(lockDbm + 3.0f, Math.min(unlockDbm - 3.0f, NEAR_GUARD_DBM));
        if (effectiveDbm >= nearGuard) {
            lastNearAtMs = nowMs;
        }

        if (effectiveDbm >= unlockDbm) {
            hasBeenNear = true;
            farSinceMs = 0L;
            resetLockDwell();

            // 行车中人在车内，不需要再发解锁指令；但标记本次会话已“接近”，
            // 停车下车后仍可正常进入远离上锁流程。
            if (driving) {
                approachLatched = true;
                return Action.NONE;
            }
            if (!autoUnlock) {
                return Action.NONE;
            }
            if (approachLatched) {
                return Action.NONE;
            }
            if (lastUnlockAtMs > 0L && (nowMs - lastUnlockAtMs) < UNLOCK_COOLDOWN_MS) {
                return Action.NONE;
            }

            approachLatched = true;
            lastUnlockAtMs = nowMs;
            return Action.UNLOCK;
        }

        if (effectiveDbm <= lockDbm) {
            // 冷启动就在远处，或本次会话从未接近过车辆：不要凭空补一条上锁指令。
            if (!hasBeenNear) {
                resetLockDwell();
                return Action.NONE;
            }

            if (driving) {
                resetLockDwell();
                farSinceMs = 0L;
                return Action.NONE;
            }

            if (nowMs < lockInhibitUntilMs) {
                resetLockDwell();
                return Action.NONE;
            }
            // 防止人在车内时偶发多径深衰落直接触发上锁：必须先离开近场至少 6 秒。
            if (lastNearAtMs > 0L && (nowMs - lastNearAtMs) < NEAR_GUARD_MS) {
                resetLockDwell();
                return Action.NONE;
            }

            if (lockDwellStartMs == 0L) {
                lockDwellStartMs = nowMs;
                lockDwellSamples = 0;
            }
            lockDwellSamples++;

            long elapsed = nowMs - lockDwellStartMs;
            if (autoLock
                    && lockDwellSamples >= LOCK_MIN_SAMPLES
                    && elapsed >= lockDwellMs) {
                approachLatched = false;
                hasBeenNear = false;
                farSinceMs = 0L;
                resetLockDwell();
                lastUnlockAtMs = nowMs;
                return Action.LOCK;
            }

            // 解锁后银河断开、但上锁命令没有成功送出时，也要允许下一次接近重新解锁。
            // 只有“银河已经断开”且持续远场，才重新武装，避免坐在车内时被重复解锁。
            if (approachLatched && !officialConnected) {
                if (farSinceMs == 0L) {
                    farSinceMs = nowMs;
                }
                if ((nowMs - farSinceMs) >= REARM_FAR_MS) {
                    approachLatched = false;
                    hasBeenNear = false;
                    farSinceMs = 0L;
                }
            } else {
                farSinceMs = 0L;
            }
            return Action.NONE;
        }

        // 中间缓冲带：禁止上锁，并清除远离计时。
        resetLockDwell();
        if (effectiveDbm >= (lockDbm + REARM_HYSTERESIS_DBM)) {
            farSinceMs = 0L;
        }
        return Action.NONE;
    }

    /**
     * 信号断连或扫描失败时调用：不使用旧样本完成上锁驻留。
     */
    synchronized void onSignalLost(long nowMs) {
        resetLockDwell();
        farSinceMs = 0L;
        lockInhibitUntilMs = nowMs + SIGNAL_LOST_INHIBIT_MS;
    }

    /**
     * 蓝牙关闭/重启后短暂禁止自动上锁，避免把端口重启当成“人已走远”。
     */
    synchronized void onBluetoothReset(long nowMs) {
        resetLockDwell();
        farSinceMs = 0L;
        lockInhibitUntilMs = nowMs + 15000L;
    }

    /**
     * 当银河官方蓝牙钥匙刚刚从“未连接”变为“已连接”时调用。
     *
     * 为什么需要这个入口：
     * 吉利车机只有 1 个 BLE 连接槽位。一旦银河 App（或 vivo 车联网）抢先连上车机，
     * 车机立即停止发送 BLE 广播；如果此时本应用还没来得及采到 >= -63dBm 的广播样本，
     * 单靠 RSSI 阈值就会因为“广播突然消失”而漏掉本次靠近解锁（造成车主在车脸前罚站）。
     *
     * 因此：当银河蓝牙钥匙刚连上（必然发生在车旁蓝牙范围内），且本次接近会话尚未解锁过时，
     * 直接触发一次解锁并闩锁本次接近会话，后续由连接态 GATT RSSI 负责监测离开自动上锁。
     */
    synchronized Action onOfficialConnected(long nowMs, boolean driving, boolean autoUnlock) {
        hasBeenNear = true;
        lastNearAtMs = nowMs;
        officialDisconnectedAtMs = 0L;
        farSinceMs = 0L;
        resetLockDwell();

        if (driving) {
            approachLatched = true;
            return Action.NONE;
        }
        if (!autoUnlock || approachLatched) {
            return Action.NONE;
        }
        if (lastUnlockAtMs > 0L && (nowMs - lastUnlockAtMs) < UNLOCK_COOLDOWN_MS) {
            return Action.NONE;
        }
        approachLatched = true;
        lastUnlockAtMs = nowMs;
        return Action.UNLOCK;
    }

    /**
     * 银河蓝牙断开时记录断开时刻并清空驻留计时。
     */
    synchronized void onOfficialDisconnected(long nowMs) {
        if (officialDisconnectedAtMs == 0L) {
            officialDisconnectedAtMs = nowMs;
        }
        resetLockDwell();
    }

    /**
     * 周期性维护状态（每 500ms 调用一次）：
     * 当人离开车辆超出蓝牙距离后，手机往往不再收到任何 BLE 样本（而不是持续收到 -85dBm 样本）。
     * 若银河已断开且连续 12 秒无近场信号，自动释放 approachLatched，重新武装下一次靠近自动解锁。
     *
     * @return 若本次调用重新武装了靠近解锁，返回 true
     */
    synchronized boolean onTick(long nowMs, boolean officialConnected, boolean driving) {
        if (officialConnected) {
            officialDisconnectedAtMs = 0L;
            return false;
        }
        if (driving || !approachLatched) {
            return false;
        }
        boolean noRecentNearSignal = (lastNearAtMs == 0L)
                || ((nowMs - lastNearAtMs) >= REARM_DISCONNECTED_SILENCE_MS);
        boolean disconnectedLongEnough = (officialDisconnectedAtMs == 0L)
                || ((nowMs - officialDisconnectedAtMs) >= REARM_FAR_MS);
        boolean signalGoneLongEnough = (lastSampleAtMs > 0L)
                && ((nowMs - lastSampleAtMs) >= REARM_DISCONNECTED_SILENCE_MS);
        if (noRecentNearSignal && (disconnectedLongEnough || signalGoneLongEnough)) {
            approachLatched = false;
            hasBeenNear = false;
            farSinceMs = 0L;
            resetLockDwell();
            return true;
        }
        return false;
    }

    /**
     * 用户点击“重新学习本车”或更换车辆时重置。
     */
    synchronized void reset() {
        approachLatched = false;
        hasBeenNear = false;
        lastUnlockAtMs = 0L;
        lastSampleAtMs = 0L;
        lastNearAtMs = 0L;
        officialDisconnectedAtMs = 0L;
        lockInhibitUntilMs = 0L;
        farSinceMs = 0L;
        resetLockDwell();
    }

    synchronized boolean isApproachLatched() {
        return approachLatched;
    }

    synchronized boolean hasBeenNear() {
        return hasBeenNear;
    }

    synchronized long getLastSampleAtMs() {
        return lastSampleAtMs;
    }

    synchronized long getFarSinceMs() {
        return farSinceMs;
    }

    synchronized long getLockDwellStartMs() {
        return lockDwellStartMs;
    }

    synchronized long getLastNearAtMs() {
        return lastNearAtMs;
    }

    synchronized int getLockDwellSamples() {
        return lockDwellSamples;
    }

    private void resetLockDwell() {
        lockDwellStartMs = 0L;
        lockDwellSamples = 0;
    }
}
