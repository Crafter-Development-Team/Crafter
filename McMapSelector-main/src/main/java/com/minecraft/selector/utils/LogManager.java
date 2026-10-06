package com.minecraft.selector.utils;

import javax.swing.*;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 日志管理器
 * 用于收集、存储和显示应用程序日志
 */
public class LogManager {
    private static LogManager instance;
    private final List<LogEntry> logEntries;
    private final List<LogListener> listeners;
    private final SimpleDateFormat dateFormat;
    
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
     * 日志监听器接口
     */
    public interface LogListener {
        void onLogAdded(LogEntry entry);
    }
    
    private LogManager() {
        this.logEntries = new CopyOnWriteArrayList<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        
        // 添加初始日志
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
        logEntries.add(entry);
        
        // 同时输出到控制台
        System.out.println(entry.toString());
        
        // 通知所有监听器
        SwingUtilities.invokeLater(() -> {
            for (LogListener listener : listeners) {
                try {
                    listener.onLogAdded(entry);
                } catch (Exception e) {
                    System.err.println("日志监听器出错: " + e.getMessage());
                }
            }
        });
        
        // 限制日志条目数量，避免内存溢出
        if (logEntries.size() > 10000) {
            logEntries.remove(0);
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
     * 获取所有日志条目
     */
    public List<LogEntry> getAllLogs() {
        return new ArrayList<>(logEntries);
    }
    
    /**
     * 获取指定级别的日志条目
     */
    public List<LogEntry> getLogsByLevel(LogLevel level) {
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
        logEntries.clear();
        log(LogLevel.INFO, "日志已清空", "LogManager");
    }
    
    /**
     * 导出日志到文件
     */
    public void exportToFile(String filePath) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            writer.println("# Minecraft Map Selector 日志文件");
            writer.println("# 导出时间: " + dateFormat.format(new Date()));
            writer.println("# 总条目数: " + logEntries.size());
            writer.println();
            
            for (LogEntry entry : logEntries) {
                writer.println(entry.toString());
            }
        }
        log(LogLevel.INFO, "日志已导出到: " + filePath, "LogManager");
    }
    
    /**
     * 添加日志监听器
     */
    public void addLogListener(LogListener listener) {
        listeners.add(listener);
    }
    
    /**
     * 移除日志监听器
     */
    public void removeLogListener(LogListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * 获取日志统计信息
     */
    public String getLogStats() {
        int total = logEntries.size();
        int debug = getLogsByLevel(LogLevel.DEBUG).size();
        int info = getLogsByLevel(LogLevel.INFO).size();
        int warn = getLogsByLevel(LogLevel.WARN).size();
        int error = getLogsByLevel(LogLevel.ERROR).size();
        
        return String.format("总计: %d | DEBUG: %d | INFO: %d | WARN: %d | ERROR: %d", 
            total, debug, info, warn, error);
    }
}