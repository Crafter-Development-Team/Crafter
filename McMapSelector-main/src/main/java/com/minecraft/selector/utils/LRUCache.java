package com.minecraft.selector.utils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU (Least Recently Used) 缓存实现
 * 用于缓存Region、Chunk等对象，提高渲染性能
 */
public class LRUCache<K, V> extends LinkedHashMap<K, V> {
    private final int maxSize;
    private volatile long hits = 0;
    private volatile long misses = 0;
    
    public LRUCache(int maxSize) {
        super(16, 0.75f, true); // accessOrder = true for LRU behavior
        this.maxSize = maxSize;
    }
    
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxSize;
    }
    
    @Override
    public V get(Object key) {
        V value = super.get(key);
        if (value != null) {
            hits++;
        } else {
            misses++;
        }
        return value;
    }
    
    /**
     * 获取缓存命中率
     */
    public double getHitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }
    
    /**
     * 获取缓存统计信息
     */
    public String getStats() {
        long total = hits + misses;
        return String.format("Cache Stats: Size=%d/%d, Hits=%d, Misses=%d, Hit Rate=%.2f%%", 
                           size(), maxSize, hits, misses, getHitRate() * 100);
    }
    
    /**
     * 重置统计信息
     */
    public void resetStats() {
        hits = 0;
        misses = 0;
    }
    
    /**
     * 线程安全的put操作
     */
    public synchronized V putSafe(K key, V value) {
        return super.put(key, value);
    }
    
    /**
     * 线程安全的get操作
     */
    public synchronized V getSafe(Object key) {
        return get(key);
    }
}
