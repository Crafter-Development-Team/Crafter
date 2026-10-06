package com.minecraft.selector.utils;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 通用对象池
 * 用于减少对象创建和GC压力
 */
public class ObjectPool<T> {
    private final ConcurrentLinkedQueue<T> pool;
    private final Supplier<T> factory;
    private final int maxSize;
    private final AtomicInteger currentSize;
    private final AtomicInteger borrowCount;
    private final AtomicInteger returnCount;
    
    public ObjectPool(Supplier<T> factory, int maxSize) {
        this.pool = new ConcurrentLinkedQueue<>();
        this.factory = factory;
        this.maxSize = maxSize;
        this.currentSize = new AtomicInteger(0);
        this.borrowCount = new AtomicInteger(0);
        this.returnCount = new AtomicInteger(0);
    }
    
    /**
     * 从池中获取对象
     */
    public T borrow() {
        borrowCount.incrementAndGet();
        T object = pool.poll();
        if (object == null) {
            object = factory.get();
        } else {
            currentSize.decrementAndGet();
        }
        return object;
    }
    
    /**
     * 将对象归还到池中
     */
    public void returnObject(T object) {
        if (object != null && currentSize.get() < maxSize) {
            pool.offer(object);
            currentSize.incrementAndGet();
            returnCount.incrementAndGet();
        }
    }
    
    /**
     * 获取池中对象数量
     */
    public int size() {
        return currentSize.get();
    }
    
    /**
     * 获取统计信息
     */
    public String getStats() {
        return String.format("ObjectPool Stats: Size=%d/%d, Borrowed=%d, Returned=%d", 
                           currentSize.get(), maxSize, borrowCount.get(), returnCount.get());
    }
    
    /**
     * 清空池
     */
    public void clear() {
        pool.clear();
        currentSize.set(0);
    }
}


