package com.minecraft.selector.render3d;

import com.minecraft.selector.core.BlockColors;
import com.minecraft.selector.core.MapRenderer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;

/**
 * 简化的 3D 渲染器，使用 Java 2D 投影
 * 不依赖 LWJGL，直接在 Swing 中渲染
 */
public class Simple3DRenderer {
    private MapRenderer.BlockInfo[][] blockData;
    private int worldMinX = 0;
    private int worldMinZ = 0;
    private BlockColors blockColors;
    
    // 相机参数
    private float cameraX = 0;
    private float cameraY = 100;
    private float cameraZ = 0;
    private float yaw = 45;
    private float pitch = -30;
    
    // 渲染参数
    private int width = 1200;
    private int height = 800;
    private float fov = 45;
    
    public Simple3DRenderer(MapRenderer.BlockInfo[][] blockData, int worldMinX, int worldMinZ) {
        this.blockData = blockData;
        this.worldMinX = worldMinX;
        this.worldMinZ = worldMinZ;
        this.blockColors = new BlockColors();
    }
    
    /**
     * 渲染到 BufferedImage
     */
    public BufferedImage render() {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        
        // 填充背景（天空蓝）
        g2d.setColor(new Color(135, 206, 235));
        g2d.fillRect(0, 0, width, height);
        
        // 收集所有方块并排序（从远到近）
        java.util.List<BlockProjection> blocks = new ArrayList<>();
        
        int blockCount = 0;
        int renderedCount = 0;
        
        for (int z = 0; z < blockData.length; z++) {
            for (int x = 0; x < blockData[z].length; x++) {
                MapRenderer.BlockInfo blockInfo = blockData[z][x];
                if (blockInfo != null && !blockInfo.getBlockId().equals("air")) {
                    blockCount++;
                    float worldX = worldMinX + x;
                    float worldZ = worldMinZ + z;
                    int blockHeight = blockInfo.getHeight();
                    
                    // 投影到屏幕
                    BlockProjection proj = projectBlock(worldX, worldZ, blockHeight, blockInfo);
                    if (proj != null && proj.screenX >= -100 && proj.screenX <= width + 100 && 
                        proj.screenY >= -100 && proj.screenY <= height + 100) {
                        blocks.add(proj);
                        renderedCount++;
                    }
                }
            }
        }
        
        // 按深度排序（从远到近）
        blocks.sort((a, b) -> Float.compare(b.depth, a.depth));
        
        // 绘制方块
        for (BlockProjection proj : blocks) {
            drawBlock(g2d, proj);
        }
        
        // 绘制调试信息
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.PLAIN, 12));
        g2d.drawString(String.format("方块总数: %d, 渲染: %d, 相机: (%.1f, %.1f, %.1f)", 
            blockCount, renderedCount, cameraX, cameraY, cameraZ), 10, 20);
        
        g2d.dispose();
        return image;
    }
    
    /**
     * 投影单个方块
     */
    private BlockProjection projectBlock(float worldX, float worldZ, int blockHeight, MapRenderer.BlockInfo blockInfo) {
        // 相对于相机的坐标
        float relX = worldX - cameraX;
        float relY = blockHeight - cameraY;
        float relZ = worldZ - cameraZ;
        
        // 旋转（简化版，只考虑 yaw）
        float yawRad = (float) Math.toRadians(yaw);
        float cosY = (float) Math.cos(yawRad);
        float sinY = (float) Math.sin(yawRad);
        
        float rotX = relX * cosY - relZ * sinY;
        float rotZ = relX * sinY + relZ * cosY;
        float rotY = relY;
        
        // 如果在相机后面，不渲染
        if (rotZ <= 0.5f) {
            return null;
        }
        
        // 透视投影 - 改进版本
        float fovRad = (float) Math.toRadians(fov);
        float scale = (float) (1.0f / Math.tan(fovRad / 2.0f));
        
        float screenX = (rotX * scale * width / 2.0f) / rotZ + width / 2.0f;
        float screenY = -(rotY * scale * height / 2.0f) / rotZ + height / 2.0f;
        
        // 计算方块大小（透视）- 改进版本
        float blockSize = Math.max(3, 200 / rotZ);
        
        // 获取颜色
        Color color = BlockColors.getBlockColor(blockInfo.getBlockId());
        
        return new BlockProjection(screenX, screenY, blockSize, color, rotZ);
    }
    
    /**
     * 绘制方块
     */
    private void drawBlock(Graphics2D g2d, BlockProjection proj) {
        int size = 15; // 固定大小，不用透视缩放
        int x = (int) (proj.screenX - size / 2);
        int y = (int) (proj.screenY - size / 2);
        
        if (size < 1) return;
        
        // 绘制方块
        g2d.setColor(proj.color);
        g2d.fillRect(x, y, size, size);
        
        // 绘制边框
        g2d.setColor(proj.color.darker());
        g2d.setStroke(new BasicStroke(1));
        g2d.drawRect(x, y, size, size);
    }
    
    /**
     * 移动相机
     */
    public void moveCamera(float dx, float dy, float dz) {
        float yawRad = (float) Math.toRadians(yaw);
        cameraX += (float) (dx * Math.cos(yawRad) - dz * Math.sin(yawRad));
        cameraZ += (float) (dx * Math.sin(yawRad) + dz * Math.cos(yawRad));
        cameraY += dy;
    }
    
    /**
     * 旋转相机
     */
    public void rotateCamera(float dyaw, float dpitch) {
        yaw += dyaw;
        pitch += dpitch;
        pitch = Math.max(-89, Math.min(89, pitch));
    }
    
    /**
     * 设置相机位置
     */
    public void setCameraPosition(float x, float y, float z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
    }
    
    /**
     * 获取相机位置
     */
    public float[] getCameraPosition() {
        return new float[]{cameraX, cameraY, cameraZ};
    }
    
    /**
     * 设置渲染大小
     */
    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
    }
    
    /**
     * 方块投影信息
     */
    private static class BlockProjection {
        float screenX, screenY;
        float size;
        Color color;
        float depth;
        
        BlockProjection(float screenX, float screenY, float size, Color color, float depth) {
            this.screenX = screenX;
            this.screenY = screenY;
            this.size = size;
            this.color = color;
            this.depth = depth;
        }
    }
}
