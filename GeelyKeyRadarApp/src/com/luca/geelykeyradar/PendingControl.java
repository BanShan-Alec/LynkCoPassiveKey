package com.luca.geelykeyradar;

/** 由主线程持有的单一车控请求，统一通知就绪和定时重试，避免两套队列重复发送。 */
final class PendingControl {
    static final long TIMEOUT_MS = 12000L;
    static final long RETRY_INTERVAL_MS = 1000L;
    static final int MAX_SEND_ATTEMPTS = 2;

    private PassiveKeyLogic.Action action = PassiveKeyLogic.Action.NONE;
    private int sequence = 0;
    private long startedAtMs = 0L;
    private long nextAttemptAtMs = 0L;
    private long lastAttemptAtMs = -1L;
    private int attempts = 0;

    /** 创建新请求，固定截止时间，不因重复通知或重试无限续期。 */
    int begin(PassiveKeyLogic.Action requestedAction, long nowMs) {
        sequence++;
        action = requestedAction;
        startedAtMs = nowMs;
        nextAttemptAtMs = nowMs;
        lastAttemptAtMs = -1L;
        attempts = 0;
        return sequence;
    }

    /** 返回待发动作，没有请求时为 NONE。 */
    PassiveKeyLogic.Action getAction() {
        return action;
    }

    /** 返回请求序号，用于拦截旧请求回调。 */
    int getSequence() {
        return sequence;
    }

    /** 返回当前请求已经实际尝试发送的次数。 */
    int getAttempts() {
        return attempts;
    }

    /** 返回从创建请求开始的等待时间。 */
    long getAgeMs(long nowMs) {
        return Math.max(0L, nowMs - startedAtMs);
    }

    /** 判断固定等待预算是否耗尽。 */
    boolean isExpired(long nowMs) {
        return action != PassiveKeyLogic.Action.NONE && getAgeMs(nowMs) >= TIMEOUT_MS;
    }

    /** 领取一次发送机会；通知刚就绪时可立即发送，普通重试按间隔节流。 */
    boolean claimAttempt(long nowMs, boolean justReady) {
        if (action == PassiveKeyLogic.Action.NONE || isExpired(nowMs) || isAttemptsExhausted()
                || nowMs <= lastAttemptAtMs) return false;
        if (!justReady && nowMs < nextAttemptAtMs) return false;
        nextAttemptAtMs = nowMs + RETRY_INTERVAL_MS;
        lastAttemptAtMs = nowMs;
        attempts++;
        return true;
    }

    /** 两次确知未发送的尝试后停止本条请求，不随通知抖动无限重发。 */
    boolean isAttemptsExhausted() {
        return attempts >= MAX_SEND_ATTEMPTS;
    }

    /** 仅当前序号可完成一次请求，后续重复通知不能再次完成或发送。 */
    boolean complete(int completedSequence) {
        if (action == PassiveKeyLogic.Action.NONE || sequence != completedSequence) return false;
        action = PassiveKeyLogic.Action.NONE;
        return true;
    }

    /** 服务停止、插线或方向改变时，使当前及旧回调立即失效。 */
    void cancel() {
        sequence++;
        action = PassiveKeyLogic.Action.NONE;
    }

    /** 返回请求等待状态，完成或取消时使用相同请求号记录结果。 */
    String diagnosticSnapshot(long nowMs) {
        return "req_id=" + sequence + " action=" + action + " req_age_ms=" + getAgeMs(nowMs)
                + " attempts=" + attempts + "/" + MAX_SEND_ATTEMPTS
                + " next_attempt_ms=" + Math.max(0L, nextAttemptAtMs - nowMs)
                + " deadline_left_ms=" + Math.max(0L, TIMEOUT_MS - getAgeMs(nowMs));
    }
}
