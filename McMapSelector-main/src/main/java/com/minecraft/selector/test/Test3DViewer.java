package com.minecraft.selector.test;

import com.minecraft.selector.core.MapRenderer;
import com.minecraft.selector.gui.Viewer3D;
import javax.swing.*;

/**
 * 3D 查看器测试程序
 */
public class Test3DViewer {
    public static void main(String[] args) {
        // 创建测试数据
        MapRenderer.BlockInfo[][] testData = generateTestData(256, 256);
        
        // 启动 3D 查看器
        SwingUtilities.invokeLater(() -> {
            Viewer3D viewer = new Viewer3D(testData, 0, 0);
            viewer.setVisible(true);
        });
    }
    
    /**
     * 生成测试数据
     */
    private static MapRenderer.BlockInfo[][] generateTestData(int width, int height) {
        MapRenderer.BlockInfo[][] data = new MapRenderer.BlockInfo[height][width];
        
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                // 生成简单的地形
                int blockHeight = 64 + (int) (Math.sin(x * 0.01) * 10 + Math.cos(z * 0.01) * 10);
                
                String blockType;
                if (blockHeight < 62) {
                    blockType = "sand";
                } else if (blockHeight < 64) {
                    blockType = "grass_block";
                } else if (blockHeight < 70) {
                    blockType = "stone";
                } else {
                    blockType = "snow_block";
                }
                
                data[z][x] = new MapRenderer.BlockInfo(blockType, blockHeight);
            }
        }
        
        return data;
    }
}
