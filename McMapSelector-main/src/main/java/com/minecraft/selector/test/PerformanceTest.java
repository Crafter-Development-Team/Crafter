package com.minecraft.selector.test;

import com.minecraft.selector.core.*;
import com.minecraft.selector.gui.MinecraftMapGUI;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Set;

/**
 * 性能测试类
 * 用于验证渲染优化的效果
 */
public class PerformanceTest {
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法: java PerformanceTest <mca文件路径>");
            System.out.println("示例: java PerformanceTest /path/to/region/r.0.0.mca");
            return;
        }
        
        String mcaFilePath = args[0];
        File mcaFile = new File(mcaFilePath);
        
        if (!mcaFile.exists()) {
            System.err.println("文件不存在: " + mcaFilePath);
            return;
        }
        
        System.out.println("=== Minecraft地图渲染性能测试 ===");
        System.out.println("测试文件: " + mcaFilePath);
        System.out.println("文件大小: " + (mcaFile.length() / 1024 / 1024) + " MB");
        
        // 运行性能测试
        runPerformanceTest(mcaFilePath);
    }
    
    private static void runPerformanceTest(String mcaFilePath) {
        // 创建进度回调
        MapRenderer.ProgressCallback callback = new MapRenderer.ProgressCallback() {
            private long lastTime = System.currentTimeMillis();
            
            @Override
            public void onProgress(int processed, int total, double speed, Set<String> foundBlocks) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastTime > 1000) { // 每秒更新一次
                    System.out.printf("进度: %d/%d (%.1f%%) - 速度: %.1f区块/秒 - 发现方块: %d种\n",
                        processed, total, (processed * 100.0 / total), speed, foundBlocks.size());
                    lastTime = currentTime;
                }
            }
            
            @Override
            public void onProgressiveRender(BufferedImage partialImage, int completedChunks, int totalChunks) {
                System.out.printf("渐进式渲染: %d/%d区块完成 (%.1f%%)\n", 
                    completedChunks, totalChunks, (completedChunks * 100.0 / totalChunks));
            }
        };
        
        // 测试不同的采样间隔
        int[] sampleIntervals = {1, 2, 4};
        
        for (int sampleInterval : sampleIntervals) {
            System.out.println("\n--- 测试采样间隔: " + sampleInterval + " ---");
            testRenderingPerformance(mcaFilePath, sampleInterval, callback);
        }
        
        // 测试渐进式渲染
        System.out.println("\n--- 测试渐进式渲染 ---");
        testProgressiveRendering(mcaFilePath, callback);
    }
    
    private static void testRenderingPerformance(String mcaFilePath, int sampleInterval, 
                                               MapRenderer.ProgressCallback callback) {
        MapRenderer renderer = new MapRenderer(0, callback);
        
        try {
            long startTime = System.currentTimeMillis();
            
            // 渲染区域
            MapRenderer.BlockInfo[][] topBlocks = renderer.getTopBlocks(mcaFilePath, 32, sampleInterval);
            
            long renderTime = System.currentTimeMillis() - startTime;
            
            if (topBlocks != null) {
                // 转换为图像
                long imageStartTime = System.currentTimeMillis();
                BufferedImage image = renderer.renderToPng(topBlocks, sampleInterval);
                long imageTime = System.currentTimeMillis() - imageStartTime;
                
                long totalTime = System.currentTimeMillis() - startTime;
                
                System.out.println("渲染结果:");
                System.out.println("  数据提取时间: " + renderTime + "ms");
                System.out.println("  图像生成时间: " + imageTime + "ms");
                System.out.println("  总时间: " + totalTime + "ms");
                System.out.println("  图像尺寸: " + image.getWidth() + "x" + image.getHeight());
                
                // 输出性能报告
                System.out.println(renderer.getPerformanceReport());
            } else {
                System.out.println("渲染失败");
            }
            
        } catch (Exception e) {
            System.err.println("渲染出错: " + e.getMessage());
            e.printStackTrace();
        } finally {
            renderer.shutdown();
        }
    }
    
    private static void testProgressiveRendering(String mcaFilePath, MapRenderer.ProgressCallback callback) {
        MapRenderer renderer = new MapRenderer(0, callback);
        
        try {
            long startTime = System.currentTimeMillis();
            
            // 渐进式渲染
            BufferedImage image = renderer.renderProgressively(mcaFilePath, 32, 1);
            
            long totalTime = System.currentTimeMillis() - startTime;
            
            if (image != null) {
                System.out.println("渐进式渲染结果:");
                System.out.println("  总时间: " + totalTime + "ms");
                System.out.println("  图像尺寸: " + image.getWidth() + "x" + image.getHeight());
                
                // 输出性能报告
                System.out.println(renderer.getPerformanceReport());
            } else {
                System.out.println("渐进式渲染失败");
            }
            
        } catch (Exception e) {
            System.err.println("渐进式渲染出错: " + e.getMessage());
            e.printStackTrace();
        } finally {
            renderer.shutdown();
        }
    }
    
    /**
     * 内存使用情况监控
     */
    private static void printMemoryUsage(String phase) {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        System.out.printf("[%s] 内存使用: %d MB / %d MB (%.1f%%), 最大: %d MB\n",
            phase,
            usedMemory / 1024 / 1024,
            totalMemory / 1024 / 1024,
            (double) usedMemory / totalMemory * 100,
            maxMemory / 1024 / 1024);
    }
    
    /**
     * 运行GC并等待完成
     */
    private static void runGC() {
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
