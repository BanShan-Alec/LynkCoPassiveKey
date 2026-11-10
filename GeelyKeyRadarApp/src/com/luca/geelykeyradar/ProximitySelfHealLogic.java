package com.luca.geelykeyradar;

/**
 * 靠近感应时的通知通道自愈判定逻辑。
 * 纯逻辑层：无 Android 框架依赖，负责在扫描到爱车广播时判定是否需要毫秒级唤醒系统重绑通道。
 */
public final class ProximitySelfHealLogic {

    public static final long DEFAULT_DEBOUNCE_MS = 10000L;

    private final long debounceMs;
    private long lastReboundAtMs = 0L;

    public ProximitySelfHealLogic() {
        this(DEFAULT_DEBOUNCE_MS);
    }

    public ProximitySelfHealLogic(long debounceMs) {
        this.debounceMs = Math.max(1000L, debounceMs);
    }

    /**
     * 当扫描到目标车辆信号时调用，判定是否需要立即触发通道自愈。
     *
     * @param nowMs             当前单调时间毫秒
     * @param permissionGranted 用户是否已开启通知监听使用权
     * @param listenerConnected 当前通知监听服务是否处于活跃连接状态
     * @return true 表示应立即调用 ensureRebound() 进行自愈；false 表示无需自愈或处于防抖冷却中
     */
    public boolean shouldTriggerRebound(long nowMs, boolean permissionGranted, boolean listenerConnected) {
        if (!permissionGranted) {
            return false;
        }
        if (listenerConnected) {
            return false;
        }
        if (lastReboundAtMs > 0L && (nowMs - lastReboundAtMs) < debounceMs) {
            return false;
        }
        lastReboundAtMs = nowMs;
        return true;
    }

    /** 重置防抖状态（如服务重启或总开关重新打开时） */
    public void reset() {
        lastReboundAtMs = 0L;
    }

    public long getLastReboundAtMs() {
        return lastReboundAtMs;
    }
}
