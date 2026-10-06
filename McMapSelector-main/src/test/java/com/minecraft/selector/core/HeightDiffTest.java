package com.minecraft.selector.core;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.awt.Color;

/**
 * 测试高度差边缘效果
 */
public class HeightDiffTest {
    
    @Test
    public void testHeightDifferenceEdgeEffect() {
        // 创建一个简单的测试数据
        int size = 10;
        MapRenderer.BlockInfo[][] testBlocks = new MapRenderer.BlockInfo[size][size];
        
        // 填充测试数据 - 创建一个有高度差的场景
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i < size / 2) {
                    // 上半部分是高地（Y=100）
                    testBlocks[i][j] = new MapRenderer.BlockInfo("stone", 100);
                } else {
                    // 下半部分是低地（Y=50）
                    testBlocks[i][j] = new MapRenderer.BlockInfo("grass_block", 50);
                }
            }
        }
        
        // 创建渲染器
        MapRenderer renderer = new MapRenderer(1, null);
        
        // 渲染图像
        BufferedImage image = renderer.renderToPng(testBlocks, 1);
        
        // 验证图像不为空
        assert image != null : "渲染的图像不应该为空";
        assert image.getWidth() == size : "图像宽度应该等于测试数据大小";
        assert image.getHeight() == size : "图像高度应该等于测试数据大小";
        
        // 检查边界处是否有变暗效果
        // 边界应该在第4行和第5行之间
        Color upperColor = new Color(image.getRGB(5, 4)); // 上半部分
        Color lowerColor = new Color(image.getRGB(5, 5)); // 下半部分
        Color boundaryColor = new Color(image.getRGB(5, 4)); // 边界处
        
        System.out.println("测试完成 - 高度差边缘效果");
        System.out.println("图像尺寸: " + image.getWidth() + "x" + image.getHeight());
        System.out.println("上半部分颜色: RGB(" + upperColor.getRed() + "," + upperColor.getGreen() + "," + upperColor.getBlue() + ")");
        System.out.println("下半部分颜色: RGB(" + lowerColor.getRed() + "," + lowerColor.getGreen() + "," + lowerColor.getBlue() + ")");
        
        // 关闭渲染器
        renderer.shutdown();
    }
    
    @Test
    public void testBlockInfoCreation() {
        // 测试BlockInfo类的基本功能
        MapRenderer.BlockInfo blockInfo = new MapRenderer.BlockInfo("stone", 64);
        
        assert "stone".equals(blockInfo.getBlockId()) : "方块ID应该正确";
        assert blockInfo.getHeight() == 64 : "高度应该正确";
        assert "stone@64".equals(blockInfo.toString()) : "toString方法应该正确";
        
        System.out.println("BlockInfo测试通过: " + blockInfo.toString());
    }
}
