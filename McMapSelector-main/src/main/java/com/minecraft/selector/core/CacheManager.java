package com.minecraft.selector.core;

import com.minecraft.selector.region.Region;
import com.minecraft.selector.region.Chunk;
import com.minecraft.selector.utils.LRUCache;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存管理器
 * 统一管理Region、Chunk等对象的缓存，提高渲染性能
 */
public class CacheManager {
    private static final CacheManager INSTANCE = new CacheManager();
    
    // 缓存配置
    private static final int REGION_CACHE_SIZE = 16;  // 最多缓存16个Region文件
    private static final int CHUNK_CACHE_SIZE = 256;  // 最多缓存256个Chunk
    
    // 缓存实例
    private final LRUCache<String, Region> regionCache;
    private final LRUCache<String, Chunk> chunkCache;
    private final ConcurrentHashMap<String, Object> locks;
    
    private CacheManager() {
        this.regionCache = new LRUCache<>(REGION_CACHE_SIZE);
        this.chunkCache = new LRUCache<>(CHUNK_CACHE_SIZE);
        this.locks = new ConcurrentHashMap<>();
    }
    
    public static CacheManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 获取Region，如果缓存中没有则加载
     */
    public Region getRegion(String regionPath) {
        Region region = regionCache.getSafe(regionPath);
        if (region != null) {
            return region;
        }
        
        // 使用文件路径作为锁，避免重复加载同一个文件
        Object lock = locks.computeIfAbsent(regionPath, k -> new Object());
        synchronized (lock) {
            // 双重检查
            region = regionCache.getSafe(regionPath);
            if (region != null) {
                return region;
            }
            
            try {
                region = Region.fromFile(regionPath);
                if (region != null) {
                    regionCache.putSafe(regionPath, region);
                }
                return region;
            } catch (Exception e) {
                System.err.println("加载Region文件失败: " + regionPath + " - " + e.getMessage());
                return null;
            } finally {
                locks.remove(regionPath);
            }
        }
    }
    
    /**
     * 获取Chunk，如果缓存中没有则从Region加载
     */
    public Chunk getChunk(Region region, int chunkX, int chunkZ) {
        String chunkKey = chunkX + "," + chunkZ;
        Chunk chunk = chunkCache.getSafe(chunkKey);
        if (chunk != null) {
            return chunk;
        }
        
        try {
            chunk = Chunk.fromRegion(region, chunkX, chunkZ);
            if (chunk != null) {
                chunkCache.putSafe(chunkKey, chunk);
            }
            return chunk;
        } catch (Exception e) {
            System.err.println("加载Chunk失败: " + chunkKey + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 预热缓存 - 预加载指定区域的Chunk
     */
    public void preloadChunks(Region region, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (region.chunkExists(chunkX, chunkZ)) {
                    getChunk(region, chunkX, chunkZ);
                }
            }
        }
    }
    
    /**
     * 清理缓存
     */
    public void clearCache() {
        regionCache.clear();
        chunkCache.clear();
        regionCache.resetStats();
        chunkCache.resetStats();
    }
    
    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        return "Region " + regionCache.getStats() + "\n" +
               "Chunk " + chunkCache.getStats();
    }
    
    /**
     * 获取内存使用估算
     */
    public String getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        return String.format("内存使用: %d MB / %d MB (%.1f%%), 缓存: Region=%d, Chunk=%d",
                           usedMemory / 1024 / 1024,
                           maxMemory / 1024 / 1024,
                           (double) usedMemory / maxMemory * 100,
                           regionCache.size(),
                           chunkCache.size());
    }
}
