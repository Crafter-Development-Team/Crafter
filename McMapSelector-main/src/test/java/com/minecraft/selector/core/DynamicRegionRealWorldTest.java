package com.minecraft.selector.core;

import org.junit.jupiter.api.Test;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/** Opt-in real MCA correctness check for viewport loading and tile rendering. */
class DynamicRegionRealWorldTest {
    @Test void dynamicTilesMatchDirectRegionRendering() throws Exception {
        String world = System.getProperty("selector.world");
        assumeTrue(world != null, "Provide selector.world for integration");
        File regions = new File(world, "region");
        DynamicRegionManager manager = new DynamicRegionManager(world, regions);
        MapRenderer reference = new MapRenderer(2, null);
        long started = System.nanoTime();
        try {
            manager.updateViewport(new Rectangle(-512,-512,1024,1024));
            manager.awaitIdle(60, TimeUnit.SECONDS);
            for (Point point : new Point[]{new Point(-1,-1),new Point(-1,0),new Point(0,-1),new Point(0,0)}) {
                DynamicRegionManager.RegionTile tile = manager.getLoadedRegion(point);
                assertNotNull(tile, "Missing viewport tile " + point);
                File file = new File(regions, "r."+point.x+"."+point.y+".mca");
                BufferedImage direct = reference.renderToPng(reference.getTopBlocks(file.toString(),32,1),1);
                assertArrayEquals(direct.getRGB(0,0,512,512,null,0,512),
                                  tile.getImage().getRGB(0,0,512,512,null,0,512));
            }
            System.out.println("Real dynamic viewport: four core tiles pixel-identical, total ms=" +
                (System.nanoTime()-started)/1000000);
        } finally { manager.shutdown(); reference.shutdown(); }
    }
}
