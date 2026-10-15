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
    private final java.util.concurrent.ExecutorService cacheExecutor;   // 层缓存单独一条线程，别拖慢瓦片加载
    private final TileRenderer tileRenderer;
    private final Object stateLock = new Object();
    private final Map<Point, Long> failedUntil = new HashMap<>();
    private Set<Point> desiredRegions = Collections.emptySet();
    private long generation = 0;
    private volatile boolean closed;
    private final MapRenderer.SurfaceMode mode;
    /** 当前 Y 带：可运行时改，新的瓦片用新带渲染 */
    private volatile Integer clipY, clipMinY;
    /** 兴趣带上沿：上界与它之间若有方块，画布会叠紫棋盘标出来 */
    private volatile Integer highlightTop;
    /** 每次换带自增：用来丢弃"用旧带算完的"瓦片 */
    private volatile long bandGeneration;

    // ── 换带性能计数：每次换带一行日志，GUI 收尾再取一次汇总 ──
    private final java.util.concurrent.atomic.AtomicInteger bandTilesDone = new java.util.concurrent.atomic.AtomicInteger();
    private volatile int bandTilesPlanned;
    private final java.util.concurrent.atomic.AtomicInteger bandCacheHits = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger bandCacheMisses = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong bandMillis = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong bandMaxMillis = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger bandFrames = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong bandFrameWorst = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong bandFrameTotal = new java.util.concurrent.atomic.AtomicLong();
    private volatile long bandFrameStartedAt;

    /** 拖动结束时取一次汇总并清零；没有数据返回 null。 */
    public String takeBandStats() {
        int frames = bandFrames.getAndSet(0);
        if (frames == 0) return null;
        String text = String.format("%d 帧, 平均 %d ms, 最大 %d ms",
            frames, bandFrameTotal.getAndSet(0) / frames, bandFrameWorst.getAndSet(0));
        bandFrameWorst.set(0);
        return text;
    }

    /** 记一块瓦片的耗时；返回 true 表示这一帧的所有瓦片都完成了。 */
    private boolean countBandTile(long millis, boolean cacheHit, long expectedBand) {
        if (expectedBand != bandGeneration) return false;   // 属于已被取代的那一帧
        bandMillis.addAndGet(millis);
        bandMaxMillis.accumulateAndGet(millis, Math::max);
        if (cacheHit) bandCacheHits.incrementAndGet(); else bandCacheMisses.incrementAndGet();
        int done = bandTilesDone.incrementAndGet();
        int planned = bandTilesPlanned;
        if (planned <= 0 || done < planned) return false;
        long frameMillis = System.currentTimeMillis() - bandFrameStartedAt;
        bandFrames.incrementAndGet();
        bandFrameTotal.addAndGet(frameMillis);
        bandFrameWorst.accumulateAndGet(frameMillis, Math::max);
        String line = String.format("换带 %d 块: 整帧 %d ms（单块最大 %d ms, 缓存命中 %d, 缺缓存重载 %d）",
            done, frameMillis, bandMaxMillis.get(), bandCacheHits.get(), bandCacheMisses.get());
        logManager.info(line, "DynamicRegionManager");
        YbandLog.write(line);
        return true;
    }
    /** 每个 region 的层缓存（换带时内存重合成，不再读盘） */
    private static final int BAND_CACHE_LIMIT =
        Math.max(1, Math.min(8, (int) (Runtime.getRuntime().maxMemory() / (64L * 1024 * 1024))));
    private static final class CacheEntry {
        final ColumnCache cache; final BlockColors.Palette palette;
        CacheEntry(ColumnCache cache, BlockColors.Palette palette) { this.cache = cache; this.palette = palette; }
    }
    private final Map<String, CacheEntry> bandCaches = new java.util.LinkedHashMap<String, CacheEntry>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) { return size() > BAND_CACHE_LIMIT; }
    };
    private volatile long progressRevision;
    public long getProgressRevision() {return progressRevision;}
    public MapRenderer.SurfaceMode getSurfaceMode() {return mode;}
    private static final int MAX_REQUIRED_REGIONS = 64;

    interface TileRenderer { BufferedImage render(Point coordinate) throws Exception; }

    
    // 配置参数
    // private static final int MAX_LOADED_REGIONS = 25; // 移除最大加载限制
    private static final int REGION_SIZE = 512; // 每个区域的像素大小
    private static final int PRELOAD_RADIUS = 0; // 预加载半径
    
    // 监听器
    private final List<RegionLoadListener> listeners = new CopyOnWriteArrayList<>();
    
    /**
     * 区域瓦片
     */
    public static class RegionTile {
        private final Point regionCoord;
        /** 这份图对应的 Y 带与兴趣带上沿；与管理器当前值不一致时要重渲染 */
        final Integer tileClipY, tileClipMinY, tileHighlightTop;
        private final BufferedImage image;
        private final long loadTime;
        private long lastAccessTime;
        
        public RegionTile(Point regionCoord, BufferedImage image) {
            this(regionCoord, image, null, null, null);
        }

        public RegionTile(Point regionCoord, BufferedImage image,
                          Integer tileClipY, Integer tileClipMinY, Integer tileHighlightTop) {
            this.regionCoord = regionCoord;
            this.image = image;
            this.tileClipY = tileClipY;
            this.tileClipMinY = tileClipMinY;
            this.tileHighlightTop = tileHighlightTop;
            this.loadTime = System.currentTimeMillis();
            this.lastAccessTime = loadTime;
        }

        boolean matchesBand(Integer clipY, Integer clipMinY, Integer highlightTop) {
            return java.util.Objects.equals(tileClipY, clipY)
                && java.util.Objects.equals(tileClipMinY, clipMinY)
                && java.util.Objects.equals(tileHighlightTop, highlightTop);
        }
        
        public Point getRegionCoord() { return regionCoord; }
        public BufferedImage getImage() { 
            lastAccessTime = System.currentTimeMillis();
            return image; 
        }
        public int getHeightAt(int x, int z) {
            return RenderedMap.heightAt(image, x, z);
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
        /** Settled includes failures; failed files must not leave the progress UI hanging. */
        default void onViewportProgress(int settled,int total,int failed) {}
        default void onViewportProgress(long revision,int settled,int total,int failed) {onViewportProgress(settled,total,failed);}
    }
    
    public DynamicRegionManager(String savePath) {
        this(savePath, WorldPathResolver.getOverworldRegionDir(savePath));
    }

    /** Create a manager for an explicitly selected dimension. */
    public DynamicRegionManager(String savePath, File regionDir) {
        this(savePath, regionDir, MapRenderer.SurfaceMode.AUTO);
    }

    public DynamicRegionManager(String savePath, File regionDir, MapRenderer.SurfaceMode mode) {
        this(savePath,regionDir,mode,null,null);
    }
    public DynamicRegionManager(String savePath,File regionDir,MapRenderer.SurfaceMode mode,Integer clipY) {
        this(savePath,regionDir,mode,clipY,null);
    }
    public DynamicRegionManager(String savePath,File regionDir,MapRenderer.SurfaceMode mode,Integer clipY,Integer clipMinY) {
        this.savePath = savePath;
        this.regionDir = regionDir;
        this.mode = mode;
        this.clipY = clipY;
        this.clipMinY = clipMinY;
        this.logManager = LogManager.getInstance();
        this.loadingExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "map-region-loader"); t.setDaemon(true); return t;
        });
        this.cacheExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "map-region-band-cache"); t.setDaemon(true); return t;
        });
        final BlockColors.Palette tilePalette=BlockColors.snapshot();
        this.tileRenderer = coordinate -> {
            File file = new File(regionDir, String.format("r.%d.%d.mca", coordinate.x, coordinate.y));
            // Independent progress counters per render: a shared MapRenderer was
            // concurrently resetting counters and scheduling blocking waiter tasks.
            MapRenderer local = new MapRenderer(2, null, this.mode,this.clipY,this.clipMinY,tilePalette);
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
        this.mode = MapRenderer.SurfaceMode.AUTO;
        this.tileRenderer = tileRenderer;
        this.loadingExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(3);
        this.cacheExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "map-region-band-cache"); t.setDaemon(true); return t;
        });
        scanAvailableRegions();
    }

    /**
     * 换 Y 带：已加载的瓦片优先用层缓存内存重合成（不再读盘），没缓存的才回落到重新加载。
     * 每块瓦片一个任务，完成一块就通知画布重绘一块。
     */
    public void setBand(Integer clipY, Integer clipMinY) {
        setBand(clipY, clipMinY, null);
    }

    public void setBand(Integer clipY, Integer clipMinY, Integer highlightTop) {
        this.clipY = clipY;
        this.clipMinY = clipMinY;
        this.highlightTop = highlightTop;
        long band = ++bandGeneration;
        java.util.List<Point> coords;
        synchronized (stateLock) {
            if (closed) return;
            // 只重合成"当前视口要的"瓦片；LRU 里那些看不见的等它们被再次需要时自然会用新带
            coords = new ArrayList<>();
            for (Point coord : loadedRegions.keySet()) if (desiredRegions.contains(coord)) coords.add(coord);
        }
        // 重置这一帧的计数
        bandTilesDone.set(0);
        bandTilesPlanned = coords.size();
        bandCacheHits.set(0);
        bandCacheMisses.set(0);
        bandMillis.set(0);
        bandMaxMillis.set(0);
        bandFrameStartedAt = System.currentTimeMillis();
        for (Point coord : coords) {
            final long expected = band;
            try {
                loadingExecutor.execute(() -> rerenderTileForBand(coord, expected, true));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                return;
            }
        }
    }

    private Point regionCoordOf(File file) {
        String name = file.getName();
        if (!name.startsWith("r.") || !name.endsWith(".mca")) return null;
        String[] parts = name.substring(2, name.length() - 4).split("\\.");
        if (parts.length != 2) return null;
        try {
            return new Point(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private File regionFile(Point coord) {
        return new File(regionDir, String.format("r.%d.%d.mca", coord.x, coord.y));
    }

    private void rerenderTileForBand(Point coord, long expectedBand, boolean countFrame) {
        if (expectedBand != bandGeneration) return;   // 已被更新的带取代：别白算
        final long requestGeneration = generation;
        final Integer top = clipY, bottom = clipMinY, highlight = highlightTop;
        final String key = regionFile(coord).getAbsolutePath();
        final long started = System.currentTimeMillis();
        CacheEntry entry;
        synchronized (bandCaches) { entry = bandCaches.get(key); }
        BufferedImage image = null;
        if (entry != null && entry.palette == BlockColors.snapshot()) {
            try {
                // 数组快路径出图：不建 BlockInfo、不建 MapRenderer（一帧垃圾量从几百 MB 降到几 MB）
                image = entry.cache.toImage(top, bottom, highlight);
            } catch (Exception error) {
                logManager.warn("换带重合成失败，回落重载: " + key + " - " + error.getMessage(), "DynamicRegionManager");
                image = null;
            }
        }
        boolean frameDone = !countFrame || countBandTile(System.currentTimeMillis() - started, image != null, expectedBand);
        if (image == null) {
            // 没有缓存（或颜色资源换了）：删掉瓦片走正常加载（要读盘，慢，这里记一笔）
            if (expectedBand == bandGeneration) {
                logManager.info("换带缺缓存，重载瓦片: r." + coord.x + "." + coord.y + ".mca", "DynamicRegionManager");
                YbandLog.write("换带缺缓存，重载瓦片: r." + coord.x + "." + coord.y + ".mca");
            }
            synchronized (stateLock) {
                if (closed) return;
                failedUntil.remove(coord);
                startLoadingRegion(coord, true);   // 保留旧瓦片，新图算好再替换（不闪 Loading 占位）
            }
            return;
        }
        RegionTile tile = new RegionTile(new Point(coord), image, top, bottom, highlight);
        synchronized (stateLock) {
            // 期间又换过带：这块交给更新的那次任务，别用旧带覆盖
            if (closed || generation != requestGeneration || expectedBand != bandGeneration) return;
            loadedRegions.put(coord, tile);
        }
        // 一帧只通知一次画布重绘（8 块瓦片 = 1 次 repaint，而不是 8 次）
        if (frameDone) notifyRegionLoaded(coord, tile);
    }

    /** 瓦片出来之后顺手把该 region 的层缓存建好，下次换带就是内存操作。 */
    private void buildBandCacheAsync(final File file) {
        final String key = file.getAbsolutePath();
        synchronized (bandCaches) { if (bandCaches.containsKey(key)) return; }
        try {
            cacheExecutor.execute(() -> {
                final BlockColors.Palette palette = BlockColors.snapshot();
                MapRenderer local = new MapRenderer(2, null, mode, (Integer) null, (Integer) null);
                ColumnCache cache;
                try {
                    cache = local.scanRegion(key, 32);
                } catch (Exception error) {
                    logManager.warn("层缓存建立失败: " + key + " - " + error.getMessage(), "DynamicRegionManager");
                    return;
                } finally {
                    local.shutdown();
                }
                if (cache == null) return;   // 下界去顶：不缓存
                synchronized (bandCaches) { bandCaches.put(key, new CacheEntry(cache, palette)); }
                logManager.info("Y 带缓存就绪: " + file.getName() + "（换带可实时）", "DynamicRegionManager");
                // 这块瓦片原本可能是读盘渲染的（没有兴趣带掩码）：立刻用缓存重渲一次
                Point coord = regionCoordOf(file);
                if (coord != null) requestTileRefresh(coord);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
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
            notifyViewportProgress();
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
    private void startLoadingRegion(Point regionCoord) { startLoadingRegion(regionCoord, false); }

    /**
     * replaceExisting=true 时允许在"已有旧瓦片"的情况下重载（换带缺缓存用）：
     * 旧图继续显示，新图算好再替换，用户不会看到 Loading 闪烁。
     */
    private void startLoadingRegion(Point regionCoord, boolean replaceExisting) {
        // Called under stateLock: register before execute, publish only the current task.
        if (closed || loadingRegions.containsKey(regionCoord)) return;
        if (!replaceExisting && loadedRegions.containsKey(regionCoord)) return;
        Long retry = failedUntil.get(regionCoord);
        if (retry != null && retry > System.currentTimeMillis()) return;
        final long requestGeneration = generation;
        final Integer renderClipY = clipY, renderClipMinY = clipMinY, renderHighlight = highlightTop;
        FutureTask<RegionTile> task = new FutureTask<RegionTile>(() -> {
            BufferedImage image = tileRenderer.render(regionCoord);
            return image == null ? null
                : new RegionTile(new Point(regionCoord), image, renderClipY, renderClipMinY, renderHighlight);
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
                            buildBandCacheAsync(regionFile(regionCoord));   // 顺手建层缓存，换带才实时
                        } else if (tile == null) {
                            failedUntil.put(regionCoord, System.currentTimeMillis()+5000);
                            notifyLoadingFailed(regionCoord,new IllegalStateException("Region renderer returned no image"));
                        }
                    } catch (CancellationException ignored) {
                    } catch (Exception error) {
                        failedUntil.put(regionCoord, System.currentTimeMillis()+5000);
                        notifyLoadingFailed(regionCoord,error);
                    } finally { loadingRegions.remove(regionCoord, this);notifyViewportProgress(); }
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
        return getLoadedRegion(regionCoord, true);
    }

    /** refreshIfStale=true 时，发现瓦片不是当前带/兴趣带渲染的，就排队重渲染（先返回旧图，不闪）。 */
    public RegionTile getLoadedRegion(Point regionCoord, boolean refreshIfStale) {
        RegionTile tile = loadedRegions.get(regionCoord);
        if (tile != null && refreshIfStale && !tile.matchesBand(clipY, clipMinY, highlightTop)) {
            requestTileRefresh(regionCoord);
        }
        return tile;
    }

    /** 把一块"带/兴趣带过期"的瓦片排进重渲染队列（同一块只允许一个在飞）。 */
    private void requestTileRefresh(Point regionCoord) {
        synchronized (stateLock) {
            if (closed || loadingRegions.containsKey(regionCoord)) return;
            if (!loadedRegions.containsKey(regionCoord)) return;
        }
        final long expected = bandGeneration;
        try {
            loadingExecutor.execute(() -> rerenderTileForBand(regionCoord, expected, false));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
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
    
    private void notifyViewportProgress() {
        long revision=++progressRevision;
        int settled=0,failed=0;
        for(Point point:desiredRegions) {
            if(loadedRegions.containsKey(point)) settled++;
            else if(!loadingRegions.containsKey(point) && failedUntil.containsKey(point)) {settled++;failed++;}
        }
        for(RegionLoadListener listener:listeners) {
            try {listener.onViewportProgress(revision,settled,desiredRegions.size(),failed);}
            catch(Exception e) {logManager.error("Viewport progress listener failed","DynamicRegionManager",e);}
        }
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
            notifyViewportProgress();
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
            cacheExecutor.shutdownNow();
            synchronized (bandCaches) { bandCaches.clear(); }
        }
        // Do not await termination on Swing's EDT (dimension changes/window close).
    }
}
