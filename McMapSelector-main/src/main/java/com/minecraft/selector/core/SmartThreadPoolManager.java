package com.minecraft.selector.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能线程池管理器
 * 根据系统资源和任务特性动态调整线程池配置
 */
public class SmartThreadPoolManager {
    private static final int MIN_THREADS = 2;
    private static final int MAX_THREADS = 16;
    
    private final ThreadPoolExecutor executor;
    private final int optimalThreadCount;
    private final PerformanceMonitor performanceMonitor;
    
    public SmartThreadPoolManager() {
        this.performanceMonitor = PerformanceMonitor.getInstance();
        this.optimalThreadCount = calculateOptimalThreadCount();
        this.executor = createOptimizedThreadPool();
    }
    
    /**
     * 计算最优线程数
     */
    private int calculateOptimalThreadCount() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory();
        
        // 基于CPU核心数和内存大小计算最优线程数
        int memoryBasedThreads = (int) Math.min(MAX_THREADS, maxMemory / (512 * 1024 * 1024)); // 每线程512MB
        int cpuBasedThreads = Math.min(MAX_THREADS, cpuCores * 2); // CPU密集型任务通常是核心数的1-2倍
        
        int optimalThreads = Math.min(memoryBasedThreads, cpuBasedThreads);
        optimalThreads = Math.max(MIN_THREADS, optimalThreads);
        
        System.out.println(String.format("系统配置: CPU核心=%d, 最大内存=%dMB", 
                                        cpuCores, maxMemory / 1024 / 1024));
        System.out.println(String.format("线程数计算: 基于内存=%d, 基于CPU=%d, 最终选择=%d", 
                                        memoryBasedThreads, cpuBasedThreads, optimalThreads));
        
        return optimalThreads;
    }
    
    /**
     * 创建优化的线程池
     */
    private ThreadPoolExecutor createOptimizedThreadPool() {
        // 使用自定义的线程工厂
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
            
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MapRenderer-Worker-" + threadNumber.getAndIncrement());
                t.setDaemon(false);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            }
        };
        
        // 使用有界队列防止内存溢出
        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(optimalThreadCount * 4);
        
        // 创建线程池
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            optimalThreadCount,           // 核心线程数
            optimalThreadCount,           // 最大线程数
            60L,                         // 空闲线程存活时间
            TimeUnit.SECONDS,            // 时间单位
            workQueue,                   // 工作队列
            threadFactory,               // 线程工厂
            new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：调用者执行
        );
        
        // 允许核心线程超时
        pool.allowCoreThreadTimeOut(true);
        
        return pool;
    }
    
    /**
     * 提交任务
     */
    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }
    
    /**
     * 提交任务
     */
    public Future<?> submit(Runnable task) {
        return executor.submit(task);
    }
    
    /**
     * 智能分批策略
     * 根据任务数量和线程数计算最优批次大小
     */
    public int calculateOptimalBatchSize(int totalTasks) {
        if (totalTasks <= optimalThreadCount) {
            return 1; // 任务数少于线程数，每个任务一个批次
        }
        
        // 计算基础批次大小
        int baseBatchSize = totalTasks / optimalThreadCount;
        
        // 根据任务复杂度调整批次大小
        // 对于I/O密集型任务（如读取区块），使用较小的批次以提高并行度
        int adjustedBatchSize = Math.max(1, baseBatchSize / 2);
        
        // 确保批次数不超过线程数的4倍（避免过度分片）
        int maxBatches = optimalThreadCount * 4;
        int minBatchSize = Math.max(1, totalTasks / maxBatches);
        
        return Math.max(minBatchSize, adjustedBatchSize);
    }
    
    /**
     * 获取线程池状态
     */
    public String getThreadPoolStatus() {
        return String.format("线程池状态: 活跃=%d/%d, 队列=%d, 已完成=%d", 
                           executor.getActiveCount(),
                           executor.getPoolSize(),
                           executor.getQueue().size(),
                           executor.getCompletedTaskCount());
    }
    
    /**
     * 获取最优线程数
     */
    public int getOptimalThreadCount() {
        return optimalThreadCount;
    }
    
    /**
     * 关闭线程池
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    System.err.println("线程池未能正常关闭");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 等待所有任务完成
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }
}
