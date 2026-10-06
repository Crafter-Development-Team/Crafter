package com.minecraft.selector.gui;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViewportRegressionTest {
    @Test void negativeCoordinatesUseFloorDivisionAndRepeatedViewDoesNotReload() {
        Set<Point> loaded = new HashSet<>();
        final int[] calls = {0};
        ViewportManager manager = new ViewportManager(new MapCanvas.ViewportCallback() {
            public void loadRegion(int x,int z) { loaded.add(new Point(x,z)); calls[0]++; }
            public void unloadRegion(int x,int z) { loaded.remove(new Point(x,z)); }
            public void onViewportChanged(int a,int b,int c,int d) {}
        },0);
        manager.updateViewport(-1,-1,-1,-1);
        assertEquals(1,loaded.size());
        assertTrue(loaded.contains(new Point(-1,-1)));
        manager.updateViewport(-1,-1,-1,-1);
        assertEquals(1,calls[0]);
        manager.updateViewport(-513,-513,-513,-513);
        assertTrue(loaded.contains(new Point(-2,-2)));
        assertEquals(1,loaded.size());
    }
    @Test void clearingImageManagerResetsWorldOriginForNextWorld() {
        DynamicMapManager manager = new DynamicMapManager();
        BufferedImage tile = new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);
        manager.addRegion(0,0,tile);
        manager.clear();
        manager.addRegion(100,-100,tile);
        assertEquals(new Point(98*512,-102*512),manager.getWorldOrigin());
    }
}
