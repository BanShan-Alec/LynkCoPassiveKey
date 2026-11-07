package com.luca.geelykeyradar;

import java.util.Locale;

/** 从原始 RSSI 回放到状态机请求，防止单独验证阈值时遗漏滤波延迟。 */
public final class RssiPipelineTest {
    /** 覆盖不同回调频率、口袋补偿、短时多径衰落与无信号恢复。 */
    public static void main(String[] args) {
        for (long intervalMs : new long[]{250L, 500L, 1000L}) {
            long unlockAfterMs = approach(intervalMs, false);
            long pocketUnlockAfterMs = approach(intervalMs, true);
            long lockAfterMs = departure(intervalMs, false);
            long pocketLockAfterMs = departure(intervalMs, true);
            check(unlockAfterMs <= 2 * intervalMs + 500L, "持续增强的信号应在两秒内跟随");
            check(pocketUnlockAfterMs <= unlockAfterMs, "口袋补偿不能延迟接近解锁");
            check(lockAfterMs <= 8_000L, "连续远离不应叠加成十几秒的滤波等待");
            check(lockAfterMs == pocketLockAfterMs, "相同原始远场信号不应因口袋补偿延后上锁");
            System.out.printf(Locale.US,
                    "interval=%dms unlock=%.2fs pocketUnlock=%.2fs lock=%.2fs pocketLock=%.2fs%n",
                    intervalMs, unlockAfterMs / 1000.0, pocketUnlockAfterMs / 1000.0,
                    lockAfterMs / 1000.0, pocketLockAfterMs / 1000.0);
        }
        singleSpikeDoesNotUnlock();
        shortCabinFadeDoesNotLock();
        exactThresholdCanBeReached();
        invalidAndOutOfOrderSamplesAreIgnored();
        staleGapResetsTheFilter();
        System.out.println("RssiPipelineTest passed");
    }

    /** 远处稳定三十秒后走到强信号处，统计第一次解锁请求的实际时间。 */
    private static long approach(long intervalMs, boolean pocket) {
        RssiFilter filter = new RssiFilter();
        PassiveKeyLogic logic = new PassiveKeyLogic();
        long startMs = 1_000_000L;
        for (long elapsed = 0L; elapsed <= 30_000L; elapsed += intervalMs) {
            feed(logic, filter, -95f, startMs + elapsed, pocket, false);
        }
        for (long elapsed = intervalMs; elapsed <= 10_000L; elapsed += intervalMs) {
            if (feed(logic, filter, -63f, startMs + 30_000L + elapsed, pocket, false)
                    == PassiveKeyLogic.Action.UNLOCK) return elapsed;
        }
        throw new AssertionError("持续近场没有解锁请求");
    }

    /** 车内稳定三十秒后连续变弱，统计首次上锁请求；预热阶段确认解锁已经发送。 */
    private static long departure(long intervalMs, boolean pocket) {
        RssiFilter filter = new RssiFilter();
        PassiveKeyLogic logic = new PassiveKeyLogic();
        long startMs = 1_000_000L;
        for (long elapsed = 0L; elapsed <= 30_000L; elapsed += intervalMs) {
            long timeMs = startMs + elapsed;
            PassiveKeyLogic.Action action = feed(logic, filter, -60f, timeMs, pocket, true);
            if (action == PassiveKeyLogic.Action.UNLOCK) logic.onControlSent(action, timeMs);
        }
        for (long elapsed = intervalMs; elapsed <= 30_000L; elapsed += intervalMs) {
            if (feed(logic, filter, -85f, startMs + 30_000L + elapsed, pocket, true)
                    == PassiveKeyLogic.Action.LOCK) return elapsed;
        }
        throw new AssertionError("持续远场没有上锁请求，pocket=" + pocket);
    }

    /** 一个偶发强包不能把稳定远场变成接近解锁。 */
    private static void singleSpikeDoesNotUnlock() {
        RssiFilter filter = new RssiFilter();
        PassiveKeyLogic logic = new PassiveKeyLogic();
        for (long timeMs = 1_000L; timeMs <= 10_000L; timeMs += 500L) {
            check(feed(logic, filter, -95f, timeMs, false, false) == PassiveKeyLogic.Action.NONE,
                    "预热远场不应产生动作");
        }
        check(feed(logic, filter, -50f, 10_500L, false, false) == PassiveKeyLogic.Action.NONE,
                "单个强信号异常包不能触发解锁");
        check(feed(logic, filter, -95f, 11_000L, false, false) == PassiveKeyLogic.Action.NONE,
                "异常包之后恢复远场仍不应解锁");
    }

    /** 在车内发生两秒深衰落后恢复，近场保护和新滤波不能误锁。 */
    private static void shortCabinFadeDoesNotLock() {
        RssiFilter filter = new RssiFilter();
        PassiveKeyLogic logic = new PassiveKeyLogic();
        for (long timeMs = 1_000L; timeMs <= 10_000L; timeMs += 500L) {
            PassiveKeyLogic.Action action = feed(logic, filter, -60f, timeMs, true, true);
            if (action == PassiveKeyLogic.Action.UNLOCK) logic.onControlSent(action, timeMs);
        }
        for (long timeMs = 10_500L; timeMs <= 20_000L; timeMs += 500L) {
            float raw = timeMs <= 12_000L ? -98f : -60f;
            check(feed(logic, filter, raw, timeMs, true, true) == PassiveKeyLogic.Action.NONE,
                    "短时深衰落及恢复过程不能误锁或重复解锁");
        }
    }

    /** 恰好等于自定义阈值时，浮点渐近误差不能永远阻止达到阈值。 */
    private static void exactThresholdCanBeReached() {
        RssiFilter filter = new RssiFilter();
        filter.update(-60f, 1_000L);
        for (long timeMs = 1_500L; timeMs <= 20_000L; timeMs += 500L) filter.update(-82f, timeMs);
        check(filter.getSmoothDbm() == -82f, "滤波应能稳定到阈值本身");
    }

    /** 重复、乱序及非数值样本不得改变滤波历史。 */
    private static void invalidAndOutOfOrderSamplesAreIgnored() {
        RssiFilter filter = new RssiFilter();
        filter.update(-60f, 1_000L);
        filter.update(-90f, 1_000L);
        filter.update(-90f, 900L);
        filter.update(Float.NaN, 1_500L);
        filter.update(-127f, 2_000L);
        check(filter.getSmoothDbm() == -60f && filter.getLastSampleAtMs() == 1_000L,
                "无效回调不能污染信号和时间戳");
    }

    /** 长时间断档后以新样本重建滤波，不继承上一段行程的弱信号。 */
    private static void staleGapResetsTheFilter() {
        RssiFilter filter = new RssiFilter();
        filter.update(-95f, 1_000L);
        check(filter.update(-63f, 5_000L) == -63f, "断档后应使用新信号初始化");
    }

    /** 生产调用方式：同一原始样本，接近可以补偿，距离始终不加补偿。 */
    private static PassiveKeyLogic.Action feed(PassiveKeyLogic logic, RssiFilter filter,
                                               float raw, long timeMs, boolean pocket, boolean connected) {
        float distance = filter.update(raw, timeMs);
        return logic.onSample(distance + (pocket ? 8f : 0f), distance, timeMs, connected, false, true, true);
    }

    /** 断言失败时打印对应场景。 */
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
