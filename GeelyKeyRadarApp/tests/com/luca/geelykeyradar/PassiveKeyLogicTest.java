package com.luca.geelykeyradar;

public final class PassiveKeyLogicTest {

    public static void main(String[] args) {
        unlockOnlyOncePerApproach();
        awayLockNeedsDwellAndSamples();
        coldStartWeakSignalNeverLocks();
        signalLossDoesNotLock();
        drivingDoesNotSendUnlock();
        rearmAllowsNextApproachUnlock();
        drivingBlocksLockAndRearmUntilParked();
        middleZoneCancelsLockDwell();
        nearGuardAvoidsCabinFalseLock();
        officialConnectedFiresUnlockAndSilenceRearms();
        System.out.println("PassiveKeyLogicTest passed");
    }

    private static void unlockOnlyOncePerApproach() {
        PassiveKeyLogic logic = new PassiveKeyLogic();
        check(logic.onSample(-60f, 1_000L, false, false, true, true) == PassiveKeyLogic.Action.UNLOCK,
                "首次进入近场应解锁");
        check(logic.onSample(-60f, 1_500L, false, false, true, true) == PassiveKeyLogic.Action.NONE,
                "同一接近会话不得重复解锁");
        check(logic.onSample(-65f, 5_000L, true, false, true, true) == PassiveKeyLogic.Action.NONE,
                "车内状态回写不得重复解锁");
    }

    private static void awayLockNeedsDwellAndSamples() {
        PassiveKeyLogic logic = new PassiveKeyLogic();
        check(logic.onSample(-60f, 1_000L, true, false, true, true) == PassiveKeyLogic.Action.UNLOCK,
                "先解锁");
        check(logic.onSample(-84f, 8_000L, true, false, true, true) == PassiveKeyLogic.Action.NONE,
                "第 1 个远场样本不应立刻上锁");
        check(logic.onSample(-84f, 9_000L, true, false, true, true) == PassiveKeyLogic.Action.NONE,
                "第 2 个远场样本不应立刻上锁");
        check(logic.onSample(-84f, 10_000L, true, false, true, true) == PassiveKeyLogic.Action.NONE,
                "驻留未满 4.5s 不应上锁");
        check(logic.onSample(-84f, 12_600L, true, false, true, true) == PassiveKeyLogic.Action.LOCK,
                "连续远场且样本足够后应上锁");
    }

    private static void coldStartWeakSignalNeverLocks() {
        PassiveKeyLogic logic = new PassiveKeyLogic();
        for (int i = 0; i < 30; i++) {
            check(logic.onSample(-90f, 1_000L + i * 1_000L, false, false, true, true)
                    == PassiveKeyLogic.Action.NONE, "冷启动一直在远处不得补发上锁");
        }
    }

    private static void signalLossDoesNotLock() {
        PassiveKeyLogic logic = new PassiveKeyLogic();
        check(logic.onSample(-60f, 1_000L, true, false, true, true) == PassiveKeyLogic.Action.UNLOCK,
                "先解锁");
        logic.onSignalLost(2_000L);
        for (int i = 0; i < 10; i++) {
            check(logic.onSample(-90f, 3_000L + i * 1_000L, false, false, true, true)
                    == PassiveKeyLogic.Action.NONE, "断连后的抑制窗口内不得上锁");
        }
    }

    private static void drivingDoesNotSendUnlock() {
        PassiveKeyLogic logic = new PassiveKeyLogic();
        check(logic.onSample(-55f, 1_000L, true, true, true, true) == PassiveKeyLogic.Action.NONE,
                "行驶中不得下发解锁");
        check(logic.isApproachLatched(), "行驶中应占用本次接近会话");
        check(logic.onSample(-90f, 10_000L, true, false, true, true) == PassiveKeyLogic.Action.NONE,
                "停车后刚开始远离不应立刻上锁");
    }

    private static void rearmAllowsNextApproachUnlock() {
        PassiveKeyLogic logic = new PassiveKeyLogic();
        check(logic.onSample(-60f, 1_000L, false, false, true, false) == PassiveKeyLogic.Action.UNLOCK,
                "第一次接近解锁");
        // 关闭远离上锁时，持续远场 8 秒后重新武装。
        for (int i = 0; i < 10; i++) {
            logic.onSample(-90f, 10_000L + i * 1_000L, false, false, true, false);
        }
        check(logic.onSample(-60f, 21_000L, false, false, true, false) == PassiveKeyLogic.Action.UNLOCK,
                "真正离开后再次接近应重新解锁");
    }

    private static void drivingBlocksLockAndRearmUntilParked() {
        PassiveKeyLogic logic = new PassiveKeyLogic();
        check(logic.onSample(-60f, 1_000L, true, false, true, true) == PassiveKeyLogic.Action.UNLOCK,
                "先解锁");
        for (int i = 0; i < 20; i++) {
            check(logic.onSample(-90f, 10_000L + i * 1_000L, true, true, true, true)
                    == PassiveKeyLogic.Action.NONE, "行驶中不得上锁");
        }
        // 停车后继续远场，仍然应允许上锁。
        boolean sawLock = false;
        for (int i = 0; i < 10; i++) {
            if (logic.onSample(-90f, 31_000L + i * 1_000L, true, false, true, true)
                    == PassiveKeyLogic.Action.LOCK) {
                sawLock = true;
                break;
            }
        }
        check(sawLock, "停车后持续远离应恢复正常上锁");
    }

    private static void middleZoneCancelsLockDwell() {
        PassiveKeyLogic logic = new PassiveKeyLogic();
        check(logic.onSample(-60f, 1_000L, true, false, true, true) == PassiveKeyLogic.Action.UNLOCK,
                "先解锁");
        check(logic.onSample(-84f, 8_000L, true, false, true, true) == PassiveKeyLogic.Action.NONE,
                "远场开始驻留");
        check(logic.onSample(-70f, 9_000L, true, false, true, true) == PassiveKeyLogic.Action.NONE,
                "回到缓冲带应取消驻留");
        check(logic.onSample(-84f, 10_000L, true, false, true, true) == PassiveKeyLogic.Action.NONE,
                "重新开始远场驻留");
        check(logic.onSample(-84f, 11_000L, true, false, true, true) == PassiveKeyLogic.Action.NONE,
                "驻留未满不得上锁");
    }

    private static void nearGuardAvoidsCabinFalseLock() {
        PassiveKeyLogic logic = new PassiveKeyLogic();
        check(logic.onSample(-60f, 1_000L, true, false, true, true) == PassiveKeyLogic.Action.UNLOCK,
                "先解锁");
        check(logic.onSample(-65f, 2_000L, true, false, true, true) == PassiveKeyLogic.Action.NONE,
                "车内近场保持");
        // 车内偶发深衰落 6 秒内不应开始上锁。
        for (int i = 0; i < 6; i++) {
            check(logic.onSample(-85f, 3_000L + i * 1_000L, true, false, true, true)
                    == PassiveKeyLogic.Action.NONE, "近场保护窗口内不得上锁");
        }
    }

    private static void officialConnectedFiresUnlockAndSilenceRearms() {
        PassiveKeyLogic logic = new PassiveKeyLogic();
        // 银河抢先连上车机导致广播停止时，onOfficialConnected 应立即触发一次解锁
        check(logic.onOfficialConnected(1_000L, false, true) == PassiveKeyLogic.Action.UNLOCK,
                "银河蓝牙刚连上时应立即触发解锁");
        check(logic.onOfficialConnected(2_000L, false, true) == PassiveKeyLogic.Action.NONE,
                "同一接近会话不得重复触发");
        // 用户离开车辆且完全走出蓝牙范围（无新 RSSI 样本），银河断开满 12s 后应自动重新武装
        logic.onOfficialDisconnected(5_000L);
        check(!logic.onTick(10_000L, false, false), "未满 12s 不应提前重置");
        check(logic.onTick(18_000L, false, false), "断开且无近场信号满 12s 应自动重新武装");
        check(logic.onOfficialConnected(20_000L, false, true) == PassiveKeyLogic.Action.UNLOCK,
                "重新武装后再次靠近连接应允许再次解锁");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
