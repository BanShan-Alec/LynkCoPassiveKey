package com.luca.geelykeyradar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** 固定文件数及字节上限的本地日志；调用方通过同一写入线程保证顺序。 */
final class RotatingFileLog {
    private final File directory;
    private final String name;
    private final int maxBytes;
    private final int fileCount;

    /** 创建日志配置，不在构造期间执行磁盘写入。 */
    RotatingFileLog(File directory, String name, int maxBytes, int fileCount) {
        if (maxBytes < 64 || fileCount < 1) throw new IllegalArgumentException("日志容量不合法");
        this.directory = directory;
        this.name = name;
        this.maxBytes = maxBytes;
        this.fileCount = fileCount;
    }

    /** 超过容量前轮转，只淘汰最老的一份，保留近期关键事件。 */
    synchronized void append(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            bytes = Arrays.copyOf(bytes, maxBytes);
            bytes[maxBytes - 1] = '\n';
        }
        File current = fileAt(0);
        if (current.length() + bytes.length > maxBytes) {
            Files.deleteIfExists(fileAt(fileCount - 1).toPath());
            for (int index = fileCount - 2; index >= 0; index--) {
                File source = fileAt(index);
                if (source.exists()) {
                    Files.move(source.toPath(), fileAt(index + 1).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        try (FileOutputStream output = new FileOutputStream(current, true)) {
            output.write(bytes);
        }
    }

    /** 在写入队列中清空所有分段，避免旧异步任务在清空后重新出现。 */
    synchronized void clear() throws IOException {
        for (int index = 0; index < fileCount; index++) {
            Files.deleteIfExists(fileAt(index).toPath());
        }
    }

    /** 获取当前文件或历史分段路径。 */
    private File fileAt(int index) {
        return new File(directory, index == 0 ? name : name + "." + index);
    }
}
