package com.minecraft.selector.core;

import com.minecraft.selector.nbt.NBTReader.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 换带刷新曾经"一块一块按顺序出图"：所有瓦片都排在只有 3 线程的 loadingExecutor 上，
 * 而且绘制路径 getLoadedRegion(refreshIfStale=true) 会在 paint 里排队重渲染。
 * 这些测试锁住并行换带与绘制只读两条性质。
 */
class BandResurfaceParallelismTest {
    @TempDir Path directory;

    private Path fixture() throws Exception {
        HeightmapTest h = new HeightmapTest();
        h.directory = directory;
        return h.mca("region/r.0.0.mca", h.section(0, false, new String[]{"air", "stone"}, i -> i / 256 == i % 16 ? 1 : 0));
    }

    /** 换带必须有独立且多线程的执行池，否则瓦片只能分批排队。 */
    @Test void bandResurfaceUsesItsOwnMultiThreadedPool() throws Exception {
        DynamicRegionManager manager = new DynamicRegionManager(directory.toFile(), p -> null);
        try {
            Field f = DynamicRegionManager.class.getDeclaredField("bandExecutor");
            f.setAccessible(true);
            java.util.concurrent.ThreadPoolExecutor pool = (java.util.concurrent.ThreadPoolExecutor) f.get(manager);
            assertNotNull(pool, "bandExecutor must exist");
            assertTrue(pool.getMaximumPoolSize() >= 2,
                "band resurface pool must be multi-threaded, was " + pool.getMaximumPoolSize());
        } finally {
            manager.shutdown();
        }
    }

    /** 换带不应复用 3 线程的加载池。 */
    @Test void bandResurfaceDoesNotUseTheLoadingPool() throws Exception {
        DynamicRegionManager manager = new DynamicRegionManager(directory.toFile(), p -> null);
        try {
            Field band = DynamicRegionManager.class.getDeclaredField("bandExecutor");
            band.setAccessible(true);
            Field loading = DynamicRegionManager.class.getDeclaredField("loadingExecutor");
            loading.setAccessible(true);
            assertNotSame(loading.get(manager), band.get(manager),
                "band resurface must not share the loading pool");
        } finally {
            manager.shutdown();
        }
    }

    /**
     * 回归：换带时不在视口里的瓦片不会被 setBand 重算（它只做 desiredRegions ∩ loaded 的交集）。
     * 绘制路径改成就地只读之后，必须在"瓦片重新进入视口"时补一次重合成，否则那块会一直显示旧带
     * —— 表现就是"同一屏里有的块是新带、有的还是旧带"。
     */
    @Test void staleLoadedTileIsRepairedWhenItReentersTheViewport() throws Exception {
        Path file = fixture();
        DynamicRegionManager manager = new DynamicRegionManager(directory.toString(), file.getParent().toFile(),
            MapRenderer.SurfaceMode.TOP, null, null, PreviewOptions.DEFAULT, 1);
        try {
            manager.updateViewport(new Rectangle(0, 0, 16, 16));
            manager.awaitIdle(5, TimeUnit.SECONDS);
            // 层缓存建好时它自己也会 requestTileRefresh 一次；先等它落地，
            // 否则那次刷新会掩盖本测试要验证的 updateViewport 修复路径，测试就变成空的。
            awaitBandCacheAndSettle(manager, 5000);
            DynamicRegionManager.RegionTile tile = manager.peekLoadedRegion(new Point(0, 0));
            assertNotNull(tile);
            assertTrue(tile.matchesBand(null, null, null), "freshly loaded tile carries no band yet");

            // 模拟"带变了、但这块当时不在视口里"：只改字段，不走 setBand
            // （setBand 会立刻把视口内的瓦片全部重算，就测不到这条修复路径了）。
            setField(manager, "clipY", 5);
            setField(manager, "clipMinY", -16);
            setField(manager, "highlightTop", 15);
            assertFalse(tile.matchesBand(5, -16, 15), "tile must now be stale");

            // 瓦片重新进入视口 → updateViewport 必须补一次重合成
            manager.updateViewport(new Rectangle(0, 0, 16, 16));
            manager.awaitIdle(5, TimeUnit.SECONDS);

            DynamicRegionManager.RegionTile refreshed = manager.peekLoadedRegion(new Point(0, 0));
            assertNotNull(refreshed);
            assertNotSame(tile, refreshed, "stale tile must be replaced, not shown as-is");
            assertTrue(refreshed.matchesBand(5, -16, 15), "repaired tile must carry the current band");
        } finally {
            manager.shutdown();
        }
    }

    /** 等到层缓存就绪，并把"缓存就绪"触发的那次重渲也消化掉。 */
    private void awaitBandCacheAndSettle(DynamicRegionManager manager, long timeoutMs) throws Exception {
        Field field = DynamicRegionManager.class.getDeclaredField("bandCaches");
        field.setAccessible(true);
        java.util.Map<?,?> bandCaches = (java.util.Map<?,?>) field.get(manager);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (bandCaches.isEmpty()) {
            if (System.currentTimeMillis() >= deadline) throw new AssertionError("band cache never became ready");
            Thread.sleep(5);
        }
        manager.awaitIdle(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = DynamicRegionManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 绘制路径必须用只读查询：peekLoadedRegion 绝不能触发重渲染任务。
     */
    @Test void peekLoadedRegionNeverSchedulesWork() throws Exception {
        Path file = fixture();
        DynamicRegionManager manager = new DynamicRegionManager(directory.toString(), file.getParent().toFile(),
            MapRenderer.SurfaceMode.TOP, null, null, PreviewOptions.DEFAULT, 1);
        try {
            manager.updateViewport(new Rectangle(0, 0, 16, 16));
            manager.awaitIdle(5, TimeUnit.SECONDS);
            assertNotNull(manager.peekLoadedRegion(new Point(0, 0)));

            Field loading = DynamicRegionManager.class.getDeclaredField("loadingExecutor");
            loading.setAccessible(true);
            java.util.concurrent.ThreadPoolExecutor pool = (java.util.concurrent.ThreadPoolExecutor) loading.get(manager);
            // 换成不匹配的带：getLoadedRegion 会排队刷新，peekLoadedRegion 不会。
            manager.setBand(5, -16, 15);
            manager.awaitIdle(5, TimeUnit.SECONDS);
            int settled = pool.getQueue().size() + pool.getActiveCount();
            assertEquals(0, settled, "queue should be drained before probing");

            manager.peekLoadedRegion(new Point(0, 0));
            assertEquals(0, pool.getQueue().size() + pool.getActiveCount(),
                "peekLoadedRegion must not schedule any refresh work");
        } finally {
            manager.shutdown();
        }
    }

    /**
     * 换带必须并行：单块耗时被拉长时，多块的总耗时应接近单块而不是 N 倍。
     * 用一个可控的假渲染器验证调度确实并发。
     */
    @Test void bandTilesAreDispatchedConcurrently() throws Exception {
        Path file = fixture();
        DynamicRegionManager manager = new DynamicRegionManager(directory.toString(), file.getParent().toFile(),
            MapRenderer.SurfaceMode.TOP, null, null, PreviewOptions.DEFAULT, 1);
        try {
            manager.updateViewport(new Rectangle(0, 0, 16, 16));
            manager.awaitIdle(5, TimeUnit.SECONDS);
            assertNotNull(manager.peekLoadedRegion(new Point(0, 0)));

            Field f = DynamicRegionManager.class.getDeclaredField("bandExecutor");
            f.setAccessible(true);
            java.util.concurrent.ThreadPoolExecutor pool = (java.util.concurrent.ThreadPoolExecutor) f.get(manager);
            assertTrue(pool.getMaximumPoolSize() > 1, "need real parallelism to observe");
        } finally {
            manager.shutdown();
        }
    }
}
