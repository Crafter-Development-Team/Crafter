package com.minecraft.selector.core;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.Color;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

/**
 * 渐进式渲染管理器
 * 允许用户在渲染过程中看到部分结果，提升用户体验
 */
public class ProgressiveRenderer {
    
    /**
     * 渐进式渲染回调接口
     */
    public interface ProgressiveCallback {
        void onPartialRender(BufferedImage partialImage, int completedChunks, int totalChunks);
        void onRenderComplete(BufferedImage finalImage);
    }
    
    private final int imageWidth;
    private final int imageHeight;
    private final ProgressiveCallback callback;
    private final BufferedImage canvas;
    private final Graphics2D graphics;
    private final Map<String, Boolean> completedChunks;
    private final AtomicInteger completedCount;
    private final int totalChunks;
    private final Object renderLock = new Object();
    
    // 渲染配置
    private final int updateInterval; // 每完成多少个区块更新一次
    private long lastUpdateTime = 0;
    private final long minUpdateInterval = 500; // 最小更新间隔（毫秒）
    
    public ProgressiveRenderer(int imageWidth, int imageHeight, int totalChunks, 
                              ProgressiveCallback callback) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.totalChunks = totalChunks;
        this.callback = callback;
        this.completedChunks = new ConcurrentHashMap<>();
        this.completedCount = new AtomicInteger(0);
        
        // 计算更新间隔：总区块数的5%或最少1个
        this.updateInterval = Math.max(1, totalChunks / 20);
        
        // 创建画布
        this.canvas = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        this.graphics = canvas.createGraphics();
        
        // 初始化为灰色背景
        graphics.setColor(new Color(128, 128, 128));
        graphics.fillRect(0, 0, imageWidth, imageHeight);
        
        System.out.println(String.format("渐进式渲染初始化: %dx%d像素, %d个区块, 每%d个区块更新一次", 
                                        imageWidth, imageHeight, totalChunks, updateInterval));
    }
    
    /**
     * 更新区块渲染结果
     */
    public void updateChunk(int chunkX, int chunkZ, MapRenderer.BlockInfo[][] chunkBlocks) {
        if (chunkBlocks == null) return;
        
        String chunkKey = chunkX + "," + chunkZ;
        
        synchronized (renderLock) {
            // 检查是否已经渲染过这个区块
            if (completedChunks.containsKey(chunkKey)) {
                return;
            }
            
            // 渲染区块到画布
            renderChunkToCanvas(chunkX, chunkZ, chunkBlocks);
            
            // 标记为已完成
            completedChunks.put(chunkKey, true);
            int completed = completedCount.incrementAndGet();
            
            // 检查是否需要更新显示
            boolean shouldUpdate = false;
            long currentTime = System.currentTimeMillis();
            
            if (completed % updateInterval == 0 || 
                completed == totalChunks ||
                (currentTime - lastUpdateTime) > minUpdateInterval) {
                shouldUpdate = true;
                lastUpdateTime = currentTime;
            }
            
            if (shouldUpdate && callback != null) {
                // 创建当前画布的副本
                BufferedImage snapshot = createSnapshot();
                
                if (completed == totalChunks) {
                    callback.onRenderComplete(snapshot);
                } else {
                    callback.onPartialRender(snapshot, completed, totalChunks);
                }
            }
        }
    }
    
    /**
     * 将区块渲染到画布上
     */
    private void renderChunkToCanvas(int chunkX, int chunkZ, MapRenderer.BlockInfo[][] chunkBlocks) {
        int startX = chunkX * 16;
        int startZ = chunkZ * 16;
        
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int pixelX = startX + localX;
                int pixelZ = startZ + localZ;
                
                // 检查像素是否在画布范围内
                if (pixelX >= 0 && pixelX < imageWidth && pixelZ >= 0 && pixelZ < imageHeight) {
                    MapRenderer.BlockInfo blockInfo = chunkBlocks[localZ][localX];
                    Color color = getBlockColor(blockInfo);
                    canvas.setRGB(pixelX, pixelZ, color.getRGB());
                }
            }
        }
    }
    
    /**
     * 获取方块颜色
     */
    private Color getBlockColor(MapRenderer.BlockInfo blockInfo) {
        if (blockInfo == null) {
            return new Color(128, 128, 128); // 灰色
        }
        
        String blockId = blockInfo.getBlockId();
        
        // 简化的颜色映射（实际应该使用BlockColors类）
        switch (blockId) {
            case "grass_block": return new Color(124, 189, 107);
            case "stone": return new Color(125, 125, 125);
            case "dirt": return new Color(134, 96, 67);
            case "sand": return new Color(237, 201, 175);
            case "water": return new Color(64, 164, 223);
            case "oak_log": return new Color(102, 81, 51);
            case "oak_leaves": return new Color(79, 111, 82);
            case "air": return new Color(135, 206, 235); // 天空蓝
            default: return new Color(200, 200, 200); // 默认浅灰色
        }
    }
    
    /**
     * 创建当前画布的快照
     */
    private BufferedImage createSnapshot() {
        BufferedImage snapshot = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = snapshot.createGraphics();
        g.drawImage(canvas, 0, 0, null);
        g.dispose();
        return snapshot;
    }
    
    /**
     * 获取渲染进度
     */
    public double getProgress() {
        return (double) completedCount.get() / totalChunks;
    }
    
    /**
     * 获取已完成的区块数
     */
    public int getCompletedChunks() {
        return completedCount.get();
    }
    
    /**
     * 获取总区块数
     */
    public int getTotalChunks() {
        return totalChunks;
    }
    
    /**
     * 是否渲染完成
     */
    public boolean isComplete() {
        return completedCount.get() >= totalChunks;
    }
    
    /**
     * 获取最终图像
     */
    public BufferedImage getFinalImage() {
        synchronized (renderLock) {
            return createSnapshot();
        }
    }
    
    /**
     * 清理资源
     */
    public void dispose() {
        if (graphics != null) {
            graphics.dispose();
        }
    }
}
