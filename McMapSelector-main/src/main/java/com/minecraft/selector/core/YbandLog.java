package com.minecraft.selector.core;

import java.io.BufferedWriter;
import java.io.IOException;
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
 *
 * <p>实现用常开的 {@link BufferedWriter}：旧版每行都 open → size → write → close，
 * 一次拖动几千帧就是几千次文件操作，本身成了卡顿源。现在按块缓冲，只在
 * "距上次落盘超过 {@link #FLUSH_INTERVAL_MS} 毫秒"或缓冲区满时才 flush。
 */
public final class YbandLog {
    private YbandLog() {}

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final long FLUSH_INTERVAL_MS = 500;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS");

    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
    private static Path path;
    private static long written;
    private static long lastFlush;
    /** 落盘失败后就不再重试，避免每条日志都去撞同一个错误。 */
    private static boolean disabled;

    public static void write(String message) {
        synchronized (LOCK) {
            if (disabled) return;
            try {
                if (writer == null) openLocked();
                if (writer == null) return;
                String line = TIME.format(new Date()) + "  " + message + System.lineSeparator();
                writer.write(line);
                written += line.length();
                long now = System.currentTimeMillis();
                if (written > MAX_BYTES) {
                    // 超限就重开一份新的，别让它无限增长。
                    closeLocked();
                    Files.deleteIfExists(path);
                    openLocked();
                } else if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    writer.flush();
                    lastFlush = now;
                }
            } catch (Exception error) {
                disabled = true;
                closeLocked();
            }
        }
    }

    /** 进程退出前把缓冲刷掉；由关闭钩子调用，也可以手动调。 */
    public static void flush() {
        synchronized (LOCK) {
            try {
                if (writer != null) { writer.flush(); lastFlush = System.currentTimeMillis(); }
            } catch (IOException ignored) {
            }
        }
    }

    private static void openLocked() throws IOException {
        path = Paths.get(System.getProperty("java.io.tmpdir"), "crafter-yband.log");
        if (Files.exists(path) && Files.size(path) > MAX_BYTES) Files.delete(path);
        boolean fresh = !Files.exists(path);
        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        written = fresh ? 0 : Files.size(path);
        lastFlush = System.currentTimeMillis();
    }

    private static void closeLocked() {
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {
        } finally {
            writer = null;
        }
    }
}
