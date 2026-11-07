package com.luca.geelykeyradar;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** 验证日志固定容量、关键事件与诊断隔离，以及全量清空。 */
public final class RotatingFileLogTest {
    /** 使用临时目录压测轮转，不生成仓库内测试产物。 */
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("passive-log-test-");
        try {
            RotatingFileLog events = new RotatingFileLog(temporary.toFile(), "events.log", 256, 4);
            RotatingFileLog diagnostics = new RotatingFileLog(temporary.toFile(), "diagnostics.log", 256, 2);
            events.append("关键上锁事件");
            for (int index = 0; index < 300; index++) diagnostics.append("传感器诊断样本 " + index);
            check(Files.readString(temporary.resolve("events.log")).contains("关键上锁事件"),
                    "大量诊断日志不能挤掉关键事件");
            for (int index = 0; index < 200; index++) events.append("关键操作记录 " + index);
            File[] files = temporary.toFile().listFiles();
            check(files != null && files.length == 6, "日志必须限制为四份关键事件加两份诊断");
            for (File file : files) check(file.length() <= 256, "每份日志不能超出配置容量");
            check(Files.readString(temporary.resolve("events.log")).contains("199"), "最新事件必须保留");
            check(!Files.readString(temporary.resolve("events.log.1")).isEmpty(), "最近历史不能整批删除");
            diagnostics.append("长".repeat(300));
            check(Files.size(temporary.resolve("diagnostics.log")) <= 256, "单条超长日志仍须有界");
            events.clear();
            diagnostics.clear();
            try (Stream<Path> remaining = Files.list(temporary)) {
                check(remaining.count() == 0L, "清空必须移除所有轮转分段");
            }
            events.append("清空后新事件");
            check(Files.readString(temporary.resolve("events.log")).contains("清空后新事件"),
                    "清空后可以继续写日志");
            System.out.println("RotatingFileLogTest passed");
        } finally {
            try (Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /** 断言失败时标明日志容量或隔离规则。 */
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
