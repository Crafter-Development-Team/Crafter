package com.minecraft.selector.core;

import com.minecraft.selector.utils.LogManager;
import com.minecraft.selector.utils.WorldPathResolver;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * 动态区域管理器
 * 负责根据视口动态加载和卸载地图区域
 */
public class DynamicRegionManager {
    private final LogManager logManager;
    private final String savePath;
    private final File regionDir;
    
    // 区域缓存
    private final Map<Point, RegionTile> loadedRegions = new ConcurrentHashMap<>();
    private final Map<Point, Future<RegionTile>> loadingRegions = new ConcurrentHashMap<>();
    
    // 可用区域列表
    private final Set<Point> availableRegions = new HashSet<>();
    
    // 线程池
    private final ThreadPoolExecutor loadingExecutor;
    private final TileRenderer tileRenderer;
    private final Object stateLock = new Object();
    private final Map<Point, Long> failedUntil = new HashMap<>();
    private Set<Point> desiredRegions = Collections.emptySet();
    private long generation = 0;
    private volatile boolean closed;
    private static final int MAX_REQUIRED_REGIONS = 64;

    interface TileRenderer { BufferedImage render(Point coordinate) throws Exception; }

    
    // 配置参数
    // private static final int MAX_LOADED_REGIONS = 25; // 移除最大加载限制
    private static final int REGION_SIZE = 512; // 每个区域的像素大小
    private static final int PRELOAD_RADIUS = 1; // 预加载半径
    
    // 监听器
    private final List<RegionLoadListener> listeners = new CopyOnWriteArrayList<>();
    
    /**
     * 区域瓦片
     */
    public static class RegionTile {
        private final Point regionCoord;
        private final BufferedImage image;
        private final long loadTime;
        private long lastAccessTime;
        
        public RegionTile(Point regionCoord, BufferedImage image) {
            this.regionCoord = regionCoord;
            this.image = image;
            this.loadTime = System.currentTimeMillis();
            this.lastAccessTime = loadTime;
        }
        
        public Point getRegionCoord() { return regionCoord; }
        public BufferedImage getImage() { 
            lastAccessTime = System.currentTimeMillis();
            return image; 
        }
        public long getLoadTime() { return loadTime; }
        public long getLastAccessTime() { return lastAccessTime; }
    }
    
    /**
     * 区域加载监听器
     */
    public interface RegionLoadListener {
        void onRegionLoaded(Point regionCoord, RegionTile tile);
        void onRegionUnloaded(Point regionCoord);
        void onLoadingStarted(Point regionCoord);
        void onLoadingFailed(Point regionCoord, Exception error);
    }
    
    public DynamicRegionManager(String savePath) {
        this(savePath, WorldPathResolver.getOverworldRegionDir(savePath));
    }

    /** Create a manager for an explicitly selected dimension. */
    public DynamicRegionManager(String savePath, File regionDir) {
        this.savePath = savePath;
        this.regionDir = regionDir;
        this.logManager = LogManager.getInstance();
        this.loadingExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "map-region-loader"); t.setDaemon(true); return t;
        });
        this.tileRenderer = coordinate -> {
            File file = new File(regionDir, String.format("r.%d.%d.mca", coordinate.x, coordinate.y));
            // Independent progress counters per render: a shared MapRenderer was
            // concurrently resetting counters and scheduling blocking waiter tasks.
            MapRenderer local = new MapRenderer(2, null);
            try { return local.renderToPng(local.getTopBlocks(file.getAbsolutePath(),32,1),1); }
            finally { local.shutdown(); }
        };
        
        // 扫描可用区域
        scanAvailableRegions();
        
        logManager.info("动态区域管理器已初始化，发现 " + availableRegions.size() + " 个可用区域", "DynamicRegionManager");
    }
    
    // Package-private injection avoids Minecraft I/O in scheduling regression tests.
    DynamicRegionManager(File regionDir, TileRenderer tileRenderer) {
        this.savePath = regionDir.getAbsolutePath();
        this.regionDir = regionDir;
        this.logManager = LogManager.getInstance();
        this.tileRenderer = tileRenderer;
        this.loadingExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(3);
        scanAvailableRegions();
    }

    /**
     * 扫描可用的区域文件
     */
    private void scanAvailableRegions() {
        if (!regionDir.exists()) {
            logManager.warn("区域目录不存在: " + regionDir.getAbsolutePath(), "DynamicRegionManager");
            return;
        }
        
        File[] mcaFiles = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
        if (mcaFiles == null) return;
        
        for (File mcaFile : mcaFiles) {
            // 跳过0字节空文件，避免渲染无意义区域
            if (mcaFile.length() == 0) {
                logManager.debug("跳过空区域文件: " + mcaFile.getName(), "DynamicRegionManager");
                continue;
            }
            String fileName = mcaFile.getName();
            if (fileName.matches("r\\.-?\\d+\\.-?\\d+\\.mca")) {
                String[] parts = fileName.replace(".mca", "").split("\\.");
                try {
                    int regionX = Integer.parseInt(parts[1]);
                    int regionZ = Integer.parseInt(parts[2]);
                    availableRegions.add(new Point(regionX, regionZ));
                } catch (NumberFormatException e) {
                    logManager.warn("无法解析区域文件名: " + fileName, "DynamicRegionManager");
                }
            }
        }
        
        logManager.info("扫描完成，发现 " + availableRegions.size() + " 个区域文件", "DynamicRegionManager");
    }
    
    /**
     * 更新视口，加载/卸载相应区域
     */
    public void updateViewport(Rectangle worldViewport) {
        if (worldViewport == null || worldViewport.width <= 0 || worldViewport.height <= 0 || closed) return;
        // Rectangle bounds are half-open; use long arithmetic before addition.
        long minX = Math.floorDiv((long)worldViewport.x, REGION_SIZE) - PRELOAD_RADIUS;
        long maxX = Math.floorDiv((long)worldViewport.x + worldViewport.width - 1, REGION_SIZE) + PRELOAD_RADIUS;
        long minZ = Math.floorDiv((long)worldViewport.y, REGION_SIZE) - PRELOAD_RADIUS;
        long maxZ = Math.floorDiv((long)worldViewport.y + worldViewport.height - 1, REGION_SIZE) + PRELOAD_RADIUS;
        List<Point> candidates = new ArrayList<>();
        // Iterate existing region files, not potentially millions of empty coordinates.
        for (Point point : availableRegions)
            if (point.x >= minX && point.x <= maxX && point.y >= minZ && point.y <= maxZ)
                candidates.add(point);
        final double centerX = (worldViewport.x + worldViewport.width / 2.0) / REGION_SIZE;
        final double centerZ = (worldViewport.y + worldViewport.height / 2.0) / REGION_SIZE;
        candidates.sort(Comparator.comparingDouble(p -> Math.pow(p.x-centerX,2)+Math.pow(p.y-centerZ,2)));
        if (candidates.size() > MAX_REQUIRED_REGIONS)
            candidates = new ArrayList<>(candidates.subList(0, MAX_REQUIRED_REGIONS));
        synchronized (stateLock) {
            if (closed) return;
            desiredRegions = new HashSet<>(candidates);
            unloadUnneededRegions(desiredRegions);
            for (Point point : candidates) startLoadingRegion(point);
        }
    }

    /**
     * 卸载不需要的区域
     */
    private void unloadUnneededRegions(Set<Point> requiredRegions) {
        List<Point> toUnload = new ArrayList<>();
        
        // 只有当区域真正远离当前需要的区域时才卸载
        for (Point regionCoord : loadedRegions.keySet()) {
            if (!requiredRegions.contains(regionCoord)) {
                // 检查是否距离当前需要的区域太远
                boolean isTooFar = true;
                for (Point required : requiredRegions) {
                    int distance = Math.max(Math.abs(regionCoord.x - required.x), 
                                          Math.abs(regionCoord.y - required.y));
                    if (distance <= PRELOAD_RADIUS + 1) { // 给一些缓冲
                        isTooFar = false;
                        break;
                    }
                }
                
                if (isTooFar) {
                    toUnload.add(regionCoord);
                }
            }
        }
        
        for (Point regionCoord : toUnload) {
            RegionTile tile = loadedRegions.remove(regionCoord);
            if (tile != null) {
                logManager.debug("卸载区域: r." + regionCoord.x + "." + regionCoord.y + ".mca", "DynamicRegionManager");
                notifyRegionUnloaded(regionCoord);
            }
        }
        
        // 取消不需要的加载任务
        List<Point> toCancel = new ArrayList<>();
        for (Point regionCoord : loadingRegions.keySet()) {
            if (!requiredRegions.contains(regionCoord)) {
                toCancel.add(regionCoord);
            }
        }
        
        for (Point regionCoord : toCancel) {
            Future<RegionTile> future = loadingRegions.get(regionCoord);
            if (future != null) {
                future.cancel(true);
                // 立即从加载中移除，避免竞态条件
                loadingRegions.remove(regionCoord, future);
                loadingExecutor.purge();
                logManager.debug("取消加载区域: r." + regionCoord.x + "." + regionCoord.y + ".mca", "DynamicRegionManager");
            }
        }
    }
    
    /**
     * 加载需要的区域
     */
    private void loadRequiredRegions(Set<Point> requiredRegions) {
        for (Point regionCoord : requiredRegions) {
            if (!loadedRegions.containsKey(regionCoord) && !loadingRegions.containsKey(regionCoord)) {
                // 移除最大加载数量限制 - 允许加载所有需要的区域
                // if (loadedRegions.size() + loadingRegions.size() >= MAX_LOADED_REGIONS) {
                //     unloadLeastRecentlyUsed();
                // }
                
                startLoadingRegion(regionCoord);
            }
        }
    }
    
    /**
     * 卸载最久未访问的区域
     */
    private void unloadLeastRecentlyUsed() {
        Point oldestRegion = null;
        long oldestTime = Long.MAX_VALUE;
        
        for (Map.Entry<Point, RegionTile> entry : loadedRegions.entrySet()) {
            long lastAccess = entry.getValue().getLastAccessTime();
            if (lastAccess < oldestTime) {
                oldestTime = lastAccess;
                oldestRegion = entry.getKey();
            }
        }
        
        if (oldestRegion != null) {
            RegionTile tile = loadedRegions.remove(oldestRegion);
            if (tile != null) {
                logManager.debug("LRU卸载区域: r." + oldestRegion.x + "." + oldestRegion.y + ".mca", "DynamicRegionManager");
                notifyRegionUnloaded(oldestRegion);
            }
        }
    }
    
    /**
     * 开始加载区域
     */
    private void startLoadingRegion(Point regionCoord) {
        // Called under stateLock: register before execute, publish only the current task.
        if (closed || loadedRegions.containsKey(regionCoord) || loadingRegions.containsKey(regionCoord)) return;
        Long retry = failedUntil.get(regionCoord);
        if (retry != null && retry > System.currentTimeMillis()) return;
        final long requestGeneration = generation;
        FutureTask<RegionTile> task = new FutureTask<RegionTile>(() -> {
            BufferedImage image = tileRenderer.render(regionCoord);
            return image == null ? null : new RegionTile(new Point(regionCoord), image);
        }) {
            @Override protected void done() {
                synchronized (stateLock) {
                    if (closed || generation != requestGeneration || loadingRegions.get(regionCoord) != this) return;
                    try {
                        RegionTile tile = get();
                        if (tile != null && desiredRegions.contains(regionCoord)) {
                            loadedRegions.put(regionCoord, tile);
                            // Keep current view + a bounded warm cache when browsing.
                            while (loadedRegions.size() > 96) unloadLeastRecentlyUsed();
                            notifyRegionLoaded(regionCoord, tile);
                        } else if (tile == null) failedUntil.put(regionCoord, System.currentTimeMillis()+5000);
                    } catch (CancellationException ignored) {
                    } catch (Exception error) {
                        failedUntil.put(regionCoord, System.currentTimeMillis()+5000);
                        notifyLoadingFailed(regionCoord,error);
                    } finally { loadingRegions.remove(regionCoord, this); }
                }
            }
        };
        loadingRegions.put(new Point(regionCoord), task);
        notifyLoadingStarted(regionCoord);
        loadingExecutor.execute(task);
    }

    void awaitIdle(long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (loadingExecutor.getActiveCount() != 0 || !loadingExecutor.getQueue().isEmpty() || !loadingRegions.isEmpty()) {
            if (System.nanoTime() >= deadline) throw new TimeoutException("Region loading did not finish");
            Thread.sleep(5);
        }
    }

    /**
     * 获取已加载的区域
     */
    public RegionTile getLoadedRegion(Point regionCoord) {
        return loadedRegions.get(regionCoord);
    }
    
    /**
     * 检查区域是否正在加载
     */
    public boolean isRegionLoading(Point regionCoord) {
        return loadingRegions.containsKey(regionCoord);
    }
    
    /**
     * 获取所有已加载的区域
     */
    public Map<Point, RegionTile> getAllLoadedRegions() {
        return new HashMap<>(loadedRegions);
    }
    
    /**
     * 获取可用区域列表
     */
    public Set<Point> getAvailableRegions() {
        return new HashSet<>(availableRegions);
    }
    
    /**
     * 添加区域加载监听器
     */
    public void addRegionLoadListener(RegionLoadListener listener) {
        listeners.add(listener);
    }
    
    /**
     * 移除区域加载监听器
     */
    public void removeRegionLoadListener(RegionLoadListener listener) {
        listeners.remove(listener);
    }
    
    // 通知方法
    private void notifyRegionLoaded(Point regionCoord, RegionTile tile) {
        for (RegionLoadListener listener : listeners) {
            try {
                listener.onRegionLoaded(regionCoord, tile);
            } catch (Exception e) {
                logManager.error("区域加载监听器出错", "DynamicRegionManager", e);
            }
        }
    }
    
    private void notifyRegionUnloaded(Point regionCoord) {
        for (RegionLoadListener listener : listeners) {
            try {
                listener.onRegionUnloaded(regionCoord);
            } catch (Exception e) {
                logManager.error("区域卸载监听器出错", "DynamicRegionManager", e);
            }
        }
    }
    
    private void notifyLoadingStarted(Point regionCoord) {
        for (RegionLoadListener listener : listeners) {
            try {
                listener.onLoadingStarted(regionCoord);
            } catch (Exception e) {
                logManager.error("区域加载开始监听器出错", "DynamicRegionManager", e);
            }
        }
    }
    
    private void notifyLoadingFailed(Point regionCoord, Exception error) {
        for (RegionLoadListener listener : listeners) {
            try {
                listener.onLoadingFailed(regionCoord, error);
            } catch (Exception e) {
                logManager.error("区域加载失败监听器出错", "DynamicRegionManager", e);
            }
        }
    }
    
    /**
     * 手动清理所有已加载的区域（释放内存）
     */
    public void clearAllLoadedRegions() {
        synchronized (stateLock) {
            ++generation; // late completion from a previous world/view is invalid
            desiredRegions = Collections.emptySet();
            for (Future<RegionTile> task : loadingRegions.values()) task.cancel(true);
            loadingRegions.clear();
            loadingExecutor.purge();
            for (Point coordinate : loadedRegions.keySet()) notifyRegionUnloaded(coordinate);
            loadedRegions.clear();
            failedUntil.clear();
        }
    }

    /**
     * 获取状态信息
     */
    public String getStatusInfo() {
        return String.format("已加载: %d, 加载中: %d, 可用: %d", 
            loadedRegions.size(), loadingRegions.size(), availableRegions.size());
    }
    
    /**
     * 清理所有资源
     */
    public void shutdown() {
        synchronized (stateLock) {
            closed = true;
            clearAllLoadedRegions();
            listeners.clear();
            loadingExecutor.shutdownNow();
        }
        // Do not await termination on Swing's EDT (dimension changes/window close).
    }
}
