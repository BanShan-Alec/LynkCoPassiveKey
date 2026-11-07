package com.luca.geelykeyradar;

import java.util.LinkedHashMap;
import java.util.Map;

/** 有界计数器，逐包只增加计数，周期输出增量与累计值，避免逐包写日志。 */
final class DiagnosticCounters {
    private final Map<String, Long> totals = new LinkedHashMap<>();
    private final Map<String, Long> previous = new LinkedHashMap<>();

    /** 统计固定命名的阶段；未知名称超过上限时合并到 other，防止无限增长。 */
    synchronized void increment(String name) {
        String key = totals.containsKey(name) || totals.size() < 64 ? name : "other";
        totals.put(key, totals.getOrDefault(key, 0L) + 1L);
    }

    /** 输出计数快照，resetWindow只推进观测窗口，不修改业务状态。 */
    synchronized String snapshot(boolean resetWindow) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Long> item : totals.entrySet()) {
            if (result.length() > 0) result.append(' ');
            result.append(item.getKey()).append('=').append(item.getValue())
                    .append("(+").append(item.getValue() - previous.getOrDefault(item.getKey(), 0L)).append(')');
        }
        if (resetWindow) {
            previous.clear();
            previous.putAll(totals);
        }
        return result.length() == 0 ? "counts=none" : result.toString();
    }
}
