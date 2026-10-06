package com.minecraft.selector.render3d;

import com.minecraft.selector.core.BlockColors;
import com.minecraft.selector.core.MapRenderer;
import java.util.*;

/**
 * 3D 地图管理器，将 2D 方块数据转换为 3D 场景
 */
public class Map3DManager {
    private MapRenderer.BlockInfo[][] blockData;
    private BlockColors blockColors;
    private int worldMinX = 0;
    private int worldMinZ = 0;
    private float blockSize = 1.0f;
    
    // 选择框
    private int selectionMinX = -1;
    private int selectionMinZ = -1;
    private int selectionMaxX = -1;
    private int selectionMaxZ = -1;
    
    public Map3DManager() {
        this.blockColors = new BlockColors();
    }
    
    /**
     * 加载方块数据
     */
    public void loadBlockData(MapRenderer.BlockInfo[][] blockData, int worldMinX, int worldMinZ) {
        this.blockData = blockData;
        this.worldMinX = worldMinX;
        this.worldMinZ = worldMinZ;
    }
    
    /**
     * 构建 3D 网格
     */
    public void buildMesh(BlockMesh mesh, int lodLevel) {
        if (blockData == null) {
            return;
        }
        
        mesh.clear();
        
        int step = 1 << lodLevel; // LOD 级别，2^lodLevel
        
        for (int z = 0; z < blockData.length; z += step) {
            for (int x = 0; x < blockData[z].length; x += step) {
                MapRenderer.BlockInfo blockInfo = blockData[z][x];
                if (blockInfo != null && !blockInfo.getBlockId().equals("air")) {
                    int height = blockInfo.getHeight();
                    java.awt.Color colorObj = BlockColors.getBlockColor(blockInfo.getBlockId());
                    int color = colorObj.getRGB() & 0xFFFFFF;
                    
                    // 计算世界坐标
                    float worldX = worldMinX + x;
                    float worldZ = worldMinZ + z;
                    
                    // 添加方块到网格
                    mesh.addBlock(worldX, height, worldZ, blockSize, color);
                }
            }
        }
        
        mesh.build();
    }
    
    /**
     * 设置选择框
     */
    public void setSelection(int minX, int minZ, int maxX, int maxZ) {
        this.selectionMinX = minX;
        this.selectionMinZ = minZ;
        this.selectionMaxX = maxX;
        this.selectionMaxZ = maxZ;
    }
    
    /**
     * 清除选择框
     */
    public void clearSelection() {
        this.selectionMinX = -1;
        this.selectionMinZ = -1;
        this.selectionMaxX = -1;
        this.selectionMaxZ = -1;
    }
    
    /**
     * 获取选择框
     */
    public int[] getSelection() {
        if (selectionMinX == -1) {
            return null;
        }
        return new int[]{selectionMinX, selectionMinZ, selectionMaxX, selectionMaxZ};
    }
    
    /**
     * 获取方块数据
     */
    public MapRenderer.BlockInfo[][] getBlockData() {
        return blockData;
    }
    
    /**
     * 设置方块大小
     */
    public void setBlockSize(float size) {
        this.blockSize = size;
    }
    
    /**
     * 获取世界坐标范围
     */
    public int[] getWorldBounds() {
        if (blockData == null) {
            return new int[]{0, 0, 0, 0};
        }
        return new int[]{
            worldMinX,
            worldMinZ,
            worldMinX + blockData[0].length,
            worldMinZ + blockData.length
        };
    }
}
