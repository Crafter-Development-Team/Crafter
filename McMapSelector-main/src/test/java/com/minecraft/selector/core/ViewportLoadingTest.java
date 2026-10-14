package com.minecraft.selector.core;

import com.minecraft.selector.gui.DynamicMapCanvas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ViewportLoadingTest {
    @TempDir Path directory;
    private void region(int x,int z) throws Exception {Files.write(directory.resolve("r."+x+"."+z+".mca"),new byte[]{1});}
    private DynamicRegionManager.RegionLoadListener progress(List<int[]> events) {
        return new DynamicRegionManager.RegionLoadListener() {
            public void onRegionLoaded(Point p,DynamicRegionManager.RegionTile t) {}
            public void onRegionUnloaded(Point p) {}
            public void onLoadingStarted(Point p) {}
            public void onLoadingFailed(Point p,Exception e) {}
            public void onViewportProgress(int settled,int total,int failed) {events.add(new int[]{settled,total,failed});}
        };
    }
    @Test void onlyVisibleRegionsAreScheduledAndNegativeHalfOpenBoundsAreCorrect() throws Exception {
        for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) region(x,z);
        Set<Point> rendered=ConcurrentHashMap.newKeySet();List<int[]> events=new CopyOnWriteArrayList<>();
        DynamicRegionManager manager=new DynamicRegionManager(directory.toFile(),p->{rendered.add(p);return new BufferedImage(1,1,2);});
        manager.addRegionLoadListener(progress(events));
        try {
            manager.updateViewport(new Rectangle(-512,-512,512,512));manager.awaitIdle(5,TimeUnit.SECONDS);
            assertEquals(Collections.singleton(new Point(-1,-1)),rendered);
            assertArrayEquals(new int[]{1,1,0},events.get(events.size()-1));
            manager.updateViewport(new Rectangle(0,0,512,512));manager.awaitIdle(5,TimeUnit.SECONDS);
            assertEquals(new HashSet<>(Arrays.asList(new Point(-1,-1),new Point(0,0))),rendered);
        } finally {manager.shutdown();}
    }
    @Test void failuresAndEmptyViewportsSettleProgressInsteadOfHanging() throws Exception {
        region(0,0);region(1,0);List<int[]> events=new CopyOnWriteArrayList<>();
        DynamicRegionManager manager=new DynamicRegionManager(directory.toFile(),p->{if(p.x==0) return null;throw new Exception("bad region");});
        manager.addRegionLoadListener(progress(events));
        try {
            manager.updateViewport(new Rectangle(0,0,1024,512));manager.awaitIdle(5,TimeUnit.SECONDS);
            assertArrayEquals(new int[]{2,2,2},events.get(events.size()-1));
            manager.updateViewport(new Rectangle(4096,4096,512,512));manager.awaitIdle(5,TimeUnit.SECONDS);
            assertArrayEquals(new int[]{0,0,0},events.get(events.size()-1));
        } finally {manager.shutdown();}
    }
    @Test void cancelledOldViewportDoesNotCountTowardNewViewportCompletion() throws Exception {
        region(0,0);region(3,0);CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        List<int[]> events=new CopyOnWriteArrayList<>();
        DynamicRegionManager manager=new DynamicRegionManager(directory.toFile(),p->{
            if(p.x==0) {started.countDown();release.await();}return new BufferedImage(1,1,2);
        });manager.addRegionLoadListener(progress(events));
        try {
            manager.updateViewport(new Rectangle(0,0,512,512));assertTrue(started.await(5,TimeUnit.SECONDS));
            manager.updateViewport(new Rectangle(1536,0,512,512));release.countDown();manager.awaitIdle(5,TimeUnit.SECONDS);
            assertNull(manager.getLoadedRegion(new Point(0,0)));assertNotNull(manager.getLoadedRegion(new Point(3,0)));
            assertArrayEquals(new int[]{1,1,0},events.get(events.size()-1));
        } finally {release.countDown();manager.shutdown();}
    }
    @Test void initialCanvasUsesLocalViewportAndDefersRemainingWorldRegions() throws Exception {
        for(int x=0;x<20;x++) region(x,0);
        Set<Point> rendered=ConcurrentHashMap.newKeySet();
        DynamicRegionManager manager=new DynamicRegionManager(directory.toFile(),p->{rendered.add(p);return new BufferedImage(1,1,2);});
        DynamicMapCanvas[] canvas=new DynamicMapCanvas[1];
        try {
            SwingUtilities.invokeAndWait(()->{
                canvas[0]=new DynamicMapCanvas();canvas[0].setSize(800,600);
                canvas[0].setRegionManager(manager,false);canvas[0].showInitialViewport(new double[]{256,64,256});
            });manager.awaitIdle(5,TimeUnit.SECONDS);
            assertFalse(rendered.isEmpty());assertTrue(rendered.size()<=2,rendered.toString());
            assertFalse(rendered.contains(new Point(19,0)));
        } finally {SwingUtilities.invokeAndWait(()->canvas[0].cleanup());manager.shutdown();}
    }
}
