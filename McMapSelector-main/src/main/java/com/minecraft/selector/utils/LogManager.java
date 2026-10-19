package com.minecraft.selector.utils;

import javax.swing.*;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 日志管理器：收集、存储、显示应用程序日志。
 *
 * <p>性能要点（旧实现会在长时间渲染时把 EDT 拖死）：
 * <ul>
 *   <li>存储用有界 {@link ArrayDeque}，淘汰是 O(1)。旧版用 {@code CopyOnWriteArrayList}
 *       且 {@code size()>10000} 时 {@code remove(0)}，每次写入都要整表复制 → O(n²)。</li>
 *   <li>各级别计数增量维护，{@code getLogStats()} 是 O(1)。旧版每次调用都要四趟全表扫描
 *       并各建一个 List，而状态栏每条日志都刷新一次 → O(n²)。</li>
 *   <li>监听器通知合并到一次 EDT 任务里批量投递，不再每条日志一次 {@code invokeLater}。
 *       逐帧日志（换带性能）以前会往 EDT 里塞几千个任务。</li>
 * </ul>
 */
public class LogManager {
    private static LogManager instance;

    /** 最多保留的日志条数（超出从头部淘汰）。 */
    private static final int MAX_ENTRIES = 10000;

    private final Deque<LogEntry> logEntries = new ArrayDeque<>();
    private final List<LogListener> listeners = new ArrayList<>();
    private final SimpleDateFormat dateFormat;

    /** 各级别条数，增量维护，避免 getLogStats() 全表扫描。 */
    private final int[] levelCounts = new int[LogLevel.values().length];

    /** 待投递的条目 + 是否已排队，用于把通知合并成一次 EDT 任务。 */
    private final Deque<LogEntry> pendingNotifications = new ArrayDeque<>();
    private final AtomicBoolean notificationScheduled = new AtomicBoolean(false);

    /**
     * 日志条目
     */
    public static class LogEntry {
        private final Date timestamp;
        private final LogLevel level;
        private final String message;
        private final String source;

        public LogEntry(LogLevel level, String message, String source) {
            this.timestamp = new Date();
            this.level = level;
            this.message = message;
            this.source = source;
        }

        public Date getTimestamp() { return timestamp; }
        public LogLevel getLevel() { return level; }
        public String getMessage() { return message; }
        public String getSource() { return source; }

        @Override
        public String toString() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            return String.format("[%s] [%s] [%s] %s",
                sdf.format(timestamp), level, source, message);
        }
    }

    /**
     * 日志级别
     */
    public enum LogLevel {
        DEBUG("DEBUG"),
        INFO("INFO"),
        WARN("WARN"),
        ERROR("ERROR");

        private final String name;

        LogLevel(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 日志监听器接口。实现方会在 EDT 上被调用（可能一次收到多条）。
     */
    public interface LogListener {
        void onLogAdded(LogEntry entry);
    }

    private LogManager() {
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        log(LogLevel.INFO, "日志管理器已初始化", "LogManager");
    }

    /**
     * 获取单例实例
     */
    public static synchronized LogManager getInstance() {
        if (instance == null) {
            instance = new LogManager();
        }
        return instance;
    }

    /**
     * 添加日志条目
     */
    public void log(LogLevel level, String message, String source) {
        LogEntry entry = new LogEntry(level, message, source);

        synchronized (this) {
            logEntries.addLast(entry);
            levelCounts[level.ordinal()]++;
            while (logEntries.size() > MAX_ENTRIES) {
                LogEntry evicted = logEntries.removeFirst();
                levelCounts[evicted.getLevel().ordinal()]--;
            }
            // 没有监听器时不必排队通知（例如纯 CLI 运行）。
            if (!listeners.isEmpty()) pendingNotifications.addLast(entry);
        }

        // 同时输出到控制台
        System.out.println(entry.toString());

        // 合并通知：无论这一瞬间来了多少条日志，只排一次 EDT 任务。
        if (!listeners.isEmpty() && notificationScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(this::drainNotifications);
        }
    }

    /** 在 EDT 上把累积的待通知条目一次性发给所有监听器。 */
    private void drainNotifications() {
        LogEntry[] batch;
        synchronized (this) {
            batch = pendingNotifications.toArray(new LogEntry[0]);
            pendingNotifications.clear();
            notificationScheduled.set(false);
        }
        if (batch.length == 0) return;
        LogListener[] current;
        synchronized (this) {
            current = listeners.toArray(new LogListener[0]);
        }
        for (LogEntry entry : batch) {
            for (LogListener listener : current) {
                try {
                    listener.onLogAdded(entry);
                } catch (Exception e) {
                    System.err.println("日志监听器出错: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 便捷方法：记录INFO级别日志
     */
    public void info(String message, String source) {
        log(LogLevel.INFO, message, source);
    }

    /**
     * 便捷方法：记录DEBUG级别日志
     */
    public void debug(String message, String source) {
        log(LogLevel.DEBUG, message, source);
    }

    /**
     * 便捷方法：记录WARN级别日志
     */
    public void warn(String message, String source) {
        log(LogLevel.WARN, message, source);
    }

    /**
     * 便捷方法：记录ERROR级别日志
     */
    public void error(String message, String source) {
        log(LogLevel.ERROR, message, source);
    }

    /**
     * 便捷方法：记录异常
     */
    public void error(String message, String source, Throwable throwable) {
        StringBuilder sb = new StringBuilder(message);
        if (throwable != null) {
            sb.append(" - ").append(throwable.getClass().getSimpleName())
              .append(": ").append(throwable.getMessage());
        }
        log(LogLevel.ERROR, sb.toString(), source);
    }

    /**
     * 获取所有日志条目（快照）
     */
    public synchronized List<LogEntry> getAllLogs() {
        return new ArrayList<>(logEntries);
    }

    /**
     * 获取指定级别的日志条目
     */
    public synchronized List<LogEntry> getLogsByLevel(LogLevel level) {
        List<LogEntry> filtered = new ArrayList<>();
        for (LogEntry entry : logEntries) {
            if (entry.getLevel() == level) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * 清空所有日志
     */
    public void clearLogs() {
        synchronized (this) {
            logEntries.clear();
            pendingNotifications.clear();
            java.util.Arrays.fill(levelCounts, 0);
        }
        log(LogLevel.INFO, "日志已清空", "LogManager");
    }

    /**
     * 导出日志到文件
     */
    public void exportToFile(String filePath) throws IOException {
        List<LogEntry> snapshot = getAllLogs();
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            writer.println("# Minecraft Map Selector 日志文件");
            writer.println("# 导出时间: " + dateFormat.format(new Date()));
            writer.println("# 总条目数: " + snapshot.size());
            writer.println();

            for (LogEntry entry : snapshot) {
                writer.println(entry.toString());
            }
        }
        log(LogLevel.INFO, "日志已导出到: " + filePath, "LogManager");
    }

    /**
     * 添加日志监听器
     */
    public synchronized void addLogListener(LogListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    /**
     * 移除日志监听器
     */
    public synchronized void removeLogListener(LogListener listener) {
        listeners.remove(listener);
    }

    /** 该监听器当前是否已注册（窗口重新显示时要按需补注册）。 */
    public synchronized boolean hasLogListener(LogListener listener) {
        return listeners.contains(listener);
    }

    /**
     * 获取日志统计信息（O(1)：计数是增量维护的）
     */
    public synchronized String getLogStats() {
        return String.format("总计: %d | DEBUG: %d | INFO: %d | WARN: %d | ERROR: %d",
            logEntries.size(),
            levelCounts[LogLevel.DEBUG.ordinal()],
            levelCounts[LogLevel.INFO.ordinal()],
            levelCounts[LogLevel.WARN.ordinal()],
            levelCounts[LogLevel.ERROR.ordinal()]);
    }
}
