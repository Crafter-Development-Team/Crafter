package com.minecraft.selector.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DynamicRegionManagerTest {
    @TempDir Path world;
    private void region(int x, int z) throws Exception {
        Files.write(world.resolve("r." + x + "." + z + ".mca"), new byte[]{1});
    }
    private BufferedImage image() { return new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB); }
    @Test void repeatedViewportsOnlyScheduleOnce() throws Exception {
        region(0,0);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        DynamicRegionManager manager = new DynamicRegionManager(world.toFile(), p -> {
            calls.incrementAndGet(); entered.countDown(); finish.await(); return image();
        });
        try {
            manager.updateViewport(new Rectangle(0,0,1,1));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            for (int i=0;i<100;i++) manager.updateViewport(new Rectangle(0,0,1,1));
            finish.countDown();
            manager.awaitIdle(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());
            assertNotNull(manager.getLoadedRegion(new Point(0,0)));
        } finally { finish.countDown(); manager.shutdown(); }
    }
    @Test void clearedGenerationCannotPublishLateResults() throws Exception {
        region(0,0);
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        DynamicRegionManager manager = new DynamicRegionManager(world.toFile(), p -> {
            entered.countDown();
            // Simulate region parsing that cannot immediately honor cancellation.
            boolean done=false;
            while (!done) try { finish.await(); done=true; } catch (InterruptedException ignored) {}
            return image();
        });
        try {
            manager.updateViewport(new Rectangle(0,0,1,1));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            manager.clearAllLoadedRegions();
            finish.countDown();
            manager.awaitIdle(5,TimeUnit.SECONDS);
            assertNull(manager.getLoadedRegion(new Point(0,0)));
        } finally { finish.countDown(); manager.shutdown(); }
    }
    @Test void viewportUsesHalfOpenBoundsAndNegativeFloorDivision() throws Exception {
        region(-2,0); region(-1,0); region(0,0); region(1,0);
        DynamicRegionManager manager = new DynamicRegionManager(world.toFile(), p -> image());
        try {
            manager.updateViewport(new Rectangle(-512,0,512,1));
            manager.awaitIdle(5,TimeUnit.SECONDS);
            assertNotNull(manager.getLoadedRegion(new Point(-2,0))); // preload
            assertNotNull(manager.getLoadedRegion(new Point(-1,0)));
            assertNotNull(manager.getLoadedRegion(new Point(0,0))); // preload
            assertNull(manager.getLoadedRegion(new Point(1,0))); // no spurious extra column
        } finally { manager.shutdown(); }
    }
    @Test void extremeZoomOnlySchedulesBoundedExistingFiles() throws Exception {
        for (int i=0;i<100;i++) region(i*1000,0);
        AtomicInteger calls=new AtomicInteger();
        DynamicRegionManager manager = new DynamicRegionManager(world.toFile(), p -> {
            calls.incrementAndGet(); return new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
        });
        try {
            // Millions of possible region coordinates, only 100 existing files.
            manager.updateViewport(new Rectangle(0,0,1000000000,512));
            manager.awaitIdle(5,TimeUnit.SECONDS);
            assertEquals(64,calls.get());
            assertEquals(64,manager.getAllLoadedRegions().size());
        } finally { manager.shutdown(); }
    }
    @Test void shutdownRejectsNewRequestsWithoutThrowing() throws Exception {
        region(0,0);
        DynamicRegionManager manager = new DynamicRegionManager(world.toFile(), p -> image());
        manager.shutdown();
        manager.updateViewport(new Rectangle(0,0,512,512));
        assertFalse(manager.isRegionLoading(new Point(0,0)));
        assertTrue(manager.getAllLoadedRegions().isEmpty());
    }
}
