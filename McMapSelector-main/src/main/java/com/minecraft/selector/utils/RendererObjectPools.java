package com.minecraft.selector.utils;

/**
 * 专门用于MapRenderer的对象池管理器
 */
public class RendererObjectPools {
    private static final RendererObjectPools INSTANCE = new RendererObjectPools();
    
    // 各种对象池
    private final ObjectPool<int[]> intArrayPool;
    private final ObjectPool<java.util.HashSet<String>> hashSetPool;
    private final ObjectPool<java.util.ArrayList<int[]>> arrayListPool;
    private final ObjectPool<java.util.HashMap<String, Object>> hashMapPool;
    
    private RendererObjectPools() {
        // 初始化各种对象池
        intArrayPool = new ObjectPool<>(() -> new int[2], 1000);
        hashSetPool = new ObjectPool<>(java.util.HashSet::new, 100);
        arrayListPool = new ObjectPool<>(java.util.ArrayList::new, 100);
        hashMapPool = new ObjectPool<>(java.util.HashMap::new, 100);
    }
    
    public static RendererObjectPools getInstance() {
        return INSTANCE;
    }
    
    /**
     * 获取int数组（用于坐标）
     */
    public int[] borrowIntArray() {
        return intArrayPool.borrow();
    }
    
    /**
     * 归还int数组
     */
    public void returnIntArray(int[] array) {
        if (array != null) {
            // 清理数组内容
            java.util.Arrays.fill(array, 0);
            intArrayPool.returnObject(array);
        }
    }
    
    /**
     * 获取HashSet
     */
    @SuppressWarnings("unchecked")
    public java.util.HashSet<String> borrowHashSet() {
        java.util.HashSet<String> set = hashSetPool.borrow();
        set.clear(); // 确保是空的
        return set;
    }
    
    /**
     * 归还HashSet
     */
    public void returnHashSet(java.util.HashSet<String> set) {
        if (set != null) {
            set.clear();
            hashSetPool.returnObject(set);
        }
    }
    
    /**
     * 获取ArrayList
     */
    @SuppressWarnings("unchecked")
    public java.util.ArrayList<int[]> borrowArrayList() {
        java.util.ArrayList<int[]> list = arrayListPool.borrow();
        list.clear(); // 确保是空的
        return list;
    }
    
    /**
     * 归还ArrayList
     */
    public void returnArrayList(java.util.ArrayList<int[]> list) {
        if (list != null) {
            list.clear();
            arrayListPool.returnObject(list);
        }
    }
    
    /**
     * 获取HashMap
     */
    @SuppressWarnings("unchecked")
    public java.util.HashMap<String, Object> borrowHashMap() {
        java.util.HashMap<String, Object> map = hashMapPool.borrow();
        map.clear(); // 确保是空的
        return map;
    }
    
    /**
     * 归还HashMap
     */
    public void returnHashMap(java.util.HashMap<String, Object> map) {
        if (map != null) {
            map.clear();
            hashMapPool.returnObject(map);
        }
    }
    
    /**
     * 获取所有对象池的统计信息
     */
    public String getAllStats() {
        return "对象池统计:\n" +
               "  IntArray: " + intArrayPool.getStats() + "\n" +
               "  HashSet: " + hashSetPool.getStats() + "\n" +
               "  ArrayList: " + arrayListPool.getStats() + "\n" +
               "  HashMap: " + hashMapPool.getStats();
    }
    
    /**
     * 清空所有对象池
     */
    public void clearAll() {
        intArrayPool.clear();
        hashSetPool.clear();
        arrayListPool.clear();
        hashMapPool.clear();
    }
}
