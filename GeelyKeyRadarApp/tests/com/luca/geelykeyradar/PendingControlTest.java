package com.luca.geelykeyradar;

/** 验证通知就绪与定时重试共用一个请求，不重复执行车控。 */
public final class PendingControlTest {
    /** 覆盖即时就绪、固定超时、重复完成、旧序号和重试预算。 */
    public static void main(String[] args) {
        readyNotificationSendsOnce();
        cancellationInvalidatesOldCompletion();
        timeoutCannotBeExtendedByRetries();
        retryAttemptsAreBounded();
        System.out.println("PendingControlTest passed");
    }

    /** 官方三点一秒就绪时立即发送，随后的定时器和重复通知不能再发。 */
    private static void readyNotificationSendsOnce() {
        PendingControl pending = new PendingControl();
        int sequence = pending.begin(PassiveKeyLogic.Action.UNLOCK, 1_000L);
        check(pending.claimAttempt(4_100L, true), "就绪事件应立即领取发送机会");
        check(pending.complete(sequence), "当前请求应完成一次");
        check(!pending.claimAttempt(4_600L, true), "重复就绪通知不得再次发送");
        check(!pending.claimAttempt(5_000L, false), "后续定时器不得再次发送");
        check(!pending.complete(sequence), "同一请求不能重复计数");
    }

    /** 服务停止或插线后，旧回调不能完成新一轮动作。 */
    private static void cancellationInvalidatesOldCompletion() {
        PendingControl pending = new PendingControl();
        int oldSequence = pending.begin(PassiveKeyLogic.Action.LOCK, 1_000L);
        pending.cancel();
        int newSequence = pending.begin(PassiveKeyLogic.Action.UNLOCK, 2_000L);
        check(!pending.complete(oldSequence), "旧回调不能影响新请求");
        check(pending.getAction() == PassiveKeyLogic.Action.UNLOCK, "新请求仍然存在");
        check(pending.complete(newSequence), "新请求可以正常完成");
    }

    /** 重试不延长十二秒截止时间。 */
    private static void timeoutCannotBeExtendedByRetries() {
        PendingControl pending = new PendingControl();
        pending.begin(PassiveKeyLogic.Action.LOCK, 1_000L);
        check(pending.claimAttempt(11_000L, false), "期限内可以尝试");
        check(!pending.isExpired(12_999L), "截止前仍在期限内");
        check(pending.isExpired(13_000L), "必须在原始截止时刻超时");
        check(!pending.claimAttempt(13_000L, true), "超时通知不能重新激活请求");
    }

    /** 同一个请求仅允许两次确知未发送后的尝试，通知抖动不能突破预算。 */
    private static void retryAttemptsAreBounded() {
        PendingControl pending = new PendingControl();
        pending.begin(PassiveKeyLogic.Action.LOCK, 1_000L);
        check(pending.claimAttempt(1_000L, true), "第一次尝试");
        check(!pending.claimAttempt(1_000L, true), "同一时刻的重复回调不计新尝试");
        check(!pending.claimAttempt(1_500L, false), "定时重试必须节流");
        check(pending.claimAttempt(2_000L, false), "允许第二次明确失败后的重试");
        check(pending.isAttemptsExhausted(), "两次尝试后应停止");
        check(!pending.claimAttempt(2_500L, true), "再来就绪事件也不能突破预算");
    }

    /** 断言失败时保留预期语义。 */
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
