package com.luca.geelykeyradar;

/** 验证通知替换、移除、系统重绑不会复活旧通知的可点击状态。 */
public final class NotificationEpochTest {
    /** 回放旧通知解析迟到以及旧移除晚于新通知两种顺序。 */
    public static void main(String[] args) {
        NotificationEpoch epoch = new NotificationEpoch();
        long first = epoch.begin("key", 1_000L);
        long removed = epoch.remove("key", 1_000L);
        check(!epoch.isCurrent(first), "移除后旧通知解析必须失效");
        check(epoch.isCurrent(removed), "当前移除任务可以清理状态");
        check(epoch.begin("key", 1_000L) == -1L, "旧快照不能复活已经移除的按钮");
        long replacement = epoch.begin("key", 2_000L);
        check(!epoch.isCurrent(removed), "新通知出现后，旧清理任务不得影响新状态");
        check(epoch.remove("key", 1_000L) == -1L, "旧通知的迟到移除应被忽略");
        check(epoch.isCurrent(replacement), "迟到事件之后新通知仍有效");
        epoch.invalidate();
        check(!epoch.isCurrent(replacement), "监听掉线必须撤销在途解析");
        check(epoch.begin("key", 2_000L) >= 0L, "重绑后仍可读取当前存在的同一通知");
        long queued = epoch.begin("key", 3_000L);
        epoch.removeCurrent();
        epoch.cancelPending();
        check(!epoch.isCurrent(queued), "活动通知为空时必须撤销之前排队的解析");
        check(epoch.begin("key", 3_000L) == -1L, "清理状态不得丢掉移除记录并复活旧快照");
        check(epoch.begin("key", 4_000L) >= 0L, "真正的新通知仍可正常恢复");
        System.out.println("NotificationEpochTest passed");
    }

    /** 断言失败时保留通知顺序说明。 */
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
