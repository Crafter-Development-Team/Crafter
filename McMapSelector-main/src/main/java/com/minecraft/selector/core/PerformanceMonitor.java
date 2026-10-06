package com.minecraft.selector.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

/**
 * 性能监控器
 * 用于跟踪和分析渲染性能，识别瓶颈
 */
public class PerformanceMonitor {
    private static final PerformanceMonitor INSTANCE = new PerformanceMonitor();
    
    // 性能计数器
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> timers = new ConcurrentHashMap<>();
    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();
    
    private PerformanceMonitor() {}
    
    public static PerformanceMonitor getInstance() {
        return INSTANCE;
    }
    
    /**
     * 增加计数器
     */
    public void incrementCounter(String name) {
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * 增加计数器指定数量
     */
    public void addToCounter(String name, long value) {
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(value);
    }
    
    /**
     * 开始计时
     */
    public void startTimer(String name) {
        startTimes.put(name, System.nanoTime());
    }
    
    /**
     * 结束计时并记录
     */
    public long endTimer(String name) {
        Long startTime = startTimes.remove(name);
        if (startTime != null) {
            long duration = System.nanoTime() - startTime;
            timers.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(duration);
            return duration;
        }
        return 0;
    }
    
    /**
     * 记录时间（纳秒）
     */
    public void recordTime(String name, long nanos) {
        timers.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(nanos);
    }
    
    /**
     * 获取计数器值
     */
    public long getCounter(String name) {
        AtomicLong counter = counters.get(name);
        return counter != null ? counter.get() : 0;
    }
    
    /**
     * 获取总时间（毫秒）
     */
    public double getTotalTimeMs(String name) {
        AtomicLong timer = timers.get(name);
        return timer != null ? timer.get() / 1_000_000.0 : 0.0;
    }
    
    /**
     * 获取平均时间（毫秒）
     */
    public double getAverageTimeMs(String name) {
        long count = getCounter(name + "_count");
        if (count == 0) return 0.0;
        return getTotalTimeMs(name) / count;
    }
    
    /**
     * 重置所有统计
     */
    public void reset() {
        counters.clear();
        timers.clear();
        startTimes.clear();
    }
    
    /**
     * 获取性能报告
     */
    public String getPerformanceReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 性能监控报告 ===\n");
        
        // 计数器报告
        sb.append("\n计数器:\n");
        counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append(String.format("  %s: %d\n", 
                    entry.getKey(), entry.getValue().get())));
        
        // 计时器报告
        sb.append("\n计时器 (毫秒):\n");
        timers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String name = entry.getKey();
                    double totalMs = entry.getValue().get() / 1_000_000.0;
                    long count = getCounter(name + "_count");
                    double avgMs = count > 0 ? totalMs / count : 0.0;
                    sb.append(String.format("  %s: 总计=%.2fms, 次数=%d, 平均=%.2fms\n", 
                        name, totalMs, count, avgMs));
                });
        
        return sb.toString();
    }
    
    /**
     * 获取关键性能指标
     */
    public String getKeyMetrics() {
        long chunksProcessed = getCounter("chunks_processed");
        long blocksProcessed = getCounter("blocks_processed");
        double chunkProcessTime = getTotalTimeMs("chunk_processing");
        double blockLookupTime = getTotalTimeMs("block_lookup");
        
        StringBuilder sb = new StringBuilder();
        sb.append("关键性能指标:\n");
        sb.append(String.format("  处理区块数: %d\n", chunksProcessed));
        sb.append(String.format("  处理方块数: %d\n", blocksProcessed));
        
        if (chunksProcessed > 0) {
            sb.append(String.format("  平均区块处理时间: %.2fms\n", chunkProcessTime / chunksProcessed));
        }
        
        if (blocksProcessed > 0) {
            sb.append(String.format("  平均方块查找时间: %.3fms\n", blockLookupTime / blocksProcessed));
        }
        
        return sb.toString();
    }
}
