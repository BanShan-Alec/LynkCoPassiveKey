package com.luca.geelykeyradar;

/**
 * 验证靠近车辆时的通知监听自愈判定门控与防抖机制。
 */
public final class ProximitySelfHealLogicTest {

    public static void main(String[] args) {
        testNoPermissionNeverTriggers();
        testConnectedNeverTriggers();
        testDisconnectedWithPermissionTriggersAndDebounces();
        testResetClearsDebounce();
        System.out.println("ProximitySelfHealLogicTest passed");
    }

    private static void testNoPermissionNeverTriggers() {
        ProximitySelfHealLogic logic = new ProximitySelfHealLogic(10000L);
        assert !logic.shouldTriggerRebound(1000L, false, false) : "无权限时不应触发重绑";
        assert !logic.shouldTriggerRebound(2000L, false, true) : "无权限时不应触发重绑";
    }

    private static void testConnectedNeverTriggers() {
        ProximitySelfHealLogic logic = new ProximitySelfHealLogic(10000L);
        assert !logic.shouldTriggerRebound(1000L, true, true) : "已连接正常状态不应触发重绑";
    }

    private static void testDisconnectedWithPermissionTriggersAndDebounces() {
        ProximitySelfHealLogic logic = new ProximitySelfHealLogic(10000L);
        // 第一次检测到假死：应立即触发
        assert logic.shouldTriggerRebound(1000L, true, false) : "首次检测到断开应立即触发自愈";
        assert logic.getLastReboundAtMs() == 1000L;

        // 连续扫描包（如 500ms 后、2000ms 后）：必须被防抖拦截
        assert !logic.shouldTriggerRebound(1500L, true, false) : "防抖期内应拒绝重复自愈";
        assert !logic.shouldTriggerRebound(10999L, true, false) : "未满 10s 应拒绝重复自愈";

        // 超过 10s 后仍处于假死态：应再次触发
        assert logic.shouldTriggerRebound(11001L, true, false) : "满 10s 后仍假死应允许再次自愈";
        assert logic.getLastReboundAtMs() == 11001L;
    }

    private static void testResetClearsDebounce() {
        ProximitySelfHealLogic logic = new ProximitySelfHealLogic(10000L);
        assert logic.shouldTriggerRebound(1000L, true, false);
        assert !logic.shouldTriggerRebound(2000L, true, false);

        logic.reset();
        assert logic.shouldTriggerRebound(2500L, true, false) : "重置后应能立即响应下一次自愈请求";
    }
}
