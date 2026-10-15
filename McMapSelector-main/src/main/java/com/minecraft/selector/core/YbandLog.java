package com.minecraft.selector.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Y 带相关日志的公共落盘口：除了日志窗口，再追加一份到 {@code %TEMP%\crafter-yband.log}，
 * 这样"换带走哪条路 / 每帧多久 / 有没有缺缓存重载"可以直接把文件回传，不必翻窗口。
 */
public final class YbandLog {
    private YbandLog() {}

    public static void write(String message) {
        try {
            Path log = Paths.get(System.getProperty("java.io.tmpdir"), "crafter-yband.log");
            if (Files.exists(log) && Files.size(log) > 2L * 1024 * 1024) Files.delete(log);   // 别无限长
            String line = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + "  " + message + System.lineSeparator();
            Files.write(log, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
