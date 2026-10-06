package com.minecraft.selector.gui;

import java.awt.Rectangle;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视野管理器 - 管理可见区域的MCA文件加载和卸载
 */
public class ViewportManager {
    
    // 已加载的区域
    private final Set<RegionCoord> loadedRegions = ConcurrentHashMap.newKeySet();
    
    // 视野缓冲区大小（超出可见区域多少个区域仍保持加载）
    private final int bufferSize;
    
    // 回调接口
    private final MapCanvas.ViewportCallback callback;
    
    public ViewportManager(MapCanvas.ViewportCallback callback, int bufferSize) {
        this.callback = callback;
        this.bufferSize = bufferSize;
    }
    
    /**
     * 更新视野，自动加载和卸载区域
     */
    public void updateViewport(int minWorldX, int minWorldZ, int maxWorldX, int maxWorldZ) {
        // 计算当前视野需要的区域范围
        int minRegionX = (int) Math.floorDiv((long) minWorldX - (long) bufferSize * 512, 512);
        int maxRegionX = (int) Math.floorDiv((long) maxWorldX + (long) bufferSize * 512, 512);
        int minRegionZ = (int) Math.floorDiv((long) minWorldZ - (long) bufferSize * 512, 512);
        int maxRegionZ = (int) Math.floorDiv((long) maxWorldZ + (long) bufferSize * 512, 512);
        
        // 计算需要加载的区域
        Set<RegionCoord> requiredRegions = new HashSet<>();
        for (int x = minRegionX; x <= maxRegionX; x++) {
            for (int z = minRegionZ; z <= maxRegionZ; z++) {
                requiredRegions.add(new RegionCoord(x, z));
            }
        }
        
        // 加载新区域
        for (RegionCoord region : requiredRegions) {
            if (!loadedRegions.contains(region)) {
                loadedRegions.add(region);
                if (callback != null) {
                    callback.loadRegion(region.x, region.z);
                }
                System.out.printf("加载区域: r.%d.%d.mca\n", region.x, region.z);
            }
        }
        
        // 卸载不需要的区域
        Set<RegionCoord> toUnload = new HashSet<>();
        for (RegionCoord region : loadedRegions) {
            if (!requiredRegions.contains(region)) {
                toUnload.add(region);
            }
        }
        
        for (RegionCoord region : toUnload) {
            loadedRegions.remove(region);
            if (callback != null) {
                callback.unloadRegion(region.x, region.z);
            }
            System.out.printf("卸载区域: r.%d.%d.mca\n", region.x, region.z);
        }
        
        // 通知视野变化
        if (callback != null) {
            callback.onViewportChanged(minWorldX, minWorldZ, maxWorldX, maxWorldZ);
        }
    }
    
    /**
     * 获取已加载的区域数量
     */
    public int getLoadedRegionCount() {
        return loadedRegions.size();
    }
    
    /**
     * 清除所有已加载的区域
     */
    public void clearAll() {
        for (RegionCoord region : new HashSet<>(loadedRegions)) {
            loadedRegions.remove(region);
            if (callback != null) {
                callback.unloadRegion(region.x, region.z);
            }
        }
    }
    
    /**
     * 区域坐标类
     */
    private static class RegionCoord {
        final int x, z;
        
        RegionCoord(int x, int z) {
            this.x = x;
            this.z = z;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            RegionCoord that = (RegionCoord) obj;
            return x == that.x && z == that.z;
        }
        
        @Override
        public int hashCode() {
            return x * 31 + z;
        }
        
        @Override
        public String toString() {
            return String.format("Region(%d, %d)", x, z);
        }
    }
}
