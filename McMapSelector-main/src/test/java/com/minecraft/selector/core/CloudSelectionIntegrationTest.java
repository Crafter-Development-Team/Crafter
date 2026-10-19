package com.minecraft.selector.core;

import com.minecraft.selector.gui.SelectionMath;
import com.minecraft.selector.nbt.NBTReader.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Heights, highlight masks and the loaded-column mask must survive the column-cache and live-band paths. */
class CloudSelectionIntegrationTest {
    @TempDir Path directory;

    private Path fixture() throws Exception {
        HeightmapTest h=new HeightmapTest();h.directory=directory;
        NBTCompound section=h.section(0,true,new String[]{"air","stone","water"},
            i->i/256==0?1:i/256==6?2:0);
        NBTCompound biome=new NBTCompound("biomes");
        NBTList palette=new NBTList("palette",TagType.TAG_String);
        palette.add(new NBTString("","custom:plateau"));
        biome.put("palette",palette);section.put("biomes",biome);
        return h.mca("r.0.0.mca",section);
    }

    @Test void cachedBandKeepsHeightsAndLoadedMaskAndCloudPixels() throws Exception {
        Path file=fixture();
        MapRenderer renderer=new MapRenderer(1,null,MapRenderer.SurfaceMode.TOP);
        try {
            ColumnCache cache=renderer.scanRegion(file.toString(),2);
            RenderedMap full=cache.toImage(null,null);
            assertEquals(6,full.getHeightAt(0,0));
            assertTrue(full.isLoadedAt(0,0));assertFalse(full.isLoadedAt(16,0));

            RenderedMap band=cache.toImage(4,-64,8);
            assertTrue(band.isHighlighted(0,0));
            assertEquals(0,band.getHeightAt(0,0));
            MapRenderer direct=new MapRenderer(1,null,MapRenderer.SurfaceMode.TOP,4,-64);
            try {
                RenderedMap fresh=(RenderedMap)direct.renderToPng(direct.getTopBlocks(file.toString(),2,1),1);
                assertArrayEquals(fresh.getRGB(0,0,32,32,null,0,32),band.getRGB(0,0,32,32,null,0,32));
            } finally {direct.shutdown();}

            RenderedMap composed=new RenderedMap(40,40);
            composed.copyHeightsFrom(band,4,4);composed.copyHighlightsFrom(band,4,4);
            assertEquals(0,composed.getHeightAt(4,4));
            assertTrue(composed.isHighlighted(4,4));
            assertTrue(composed.isLoadedAt(4,4));
            assertEquals(0,SelectionYFit.scan(composed,0,0,1,new SelectionMath.Bounds(4,4,19,19),false).max);

            // 全空气带：列仍然"已加载"，但高度是 NO_HEIGHT，且不得计入 Fit-Y。
            RenderedMap empty=cache.toImage(10,8);
            assertTrue(empty.isLoadedAt(0,0));assertFalse(empty.isLoadedAt(16,0));
            assertEquals(RenderedMap.NO_HEIGHT,empty.getHeightAt(0,0));
            SelectionYFit.Result fit=SelectionYFit.scan(empty,0,0,1,new SelectionMath.Bounds(0,0,15,15),true);
            assertEquals(0,fit.count);assertEquals(256,fit.empty);
        } finally {renderer.shutdown();}
    }

    @Test void optionalWaterPreviewKeepsItsPolicyAndHeightsInCache() throws Exception {
        Path file=fixture();
        MapRenderer renderer=new MapRenderer(1,null,MapRenderer.SurfaceMode.TOP,null,BlockColors.snapshot(),new PreviewOptions(true,false,0));
        try {
            ColumnCache cache=renderer.scanRegion(file.toString(),2);
            RenderedMap image=cache.toImage(10,-64,12);
            assertEquals(0,image.getHeightAt(0,0));
            RenderedMap fresh=(RenderedMap)renderer.renderToPng(renderer.getTopBlocks(file.toString(),2,1),1);
            assertArrayEquals(fresh.getRGB(0,0,32,32,null,0,32),image.getRGB(0,0,32,32,null,0,32));
        } finally {renderer.shutdown();}
    }

    @Test void liveDynamicBandRetainsHeightsAndPurpleMask() throws Exception {
        fixture();
        DynamicRegionManager manager=new DynamicRegionManager(directory.toString(),directory.toFile(),MapRenderer.SurfaceMode.TOP,10,-64);
        try {
            manager.updateViewport(new Rectangle(0,0,16,16));
            manager.awaitIdle(5,TimeUnit.SECONDS);
            manager.setBand(4,-64,8);
            // The cloud manager builds its cache asynchronously after the first tile.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            RenderedMap image=null;
            while(System.nanoTime()<deadline) {
                DynamicRegionManager.RegionTile tile=manager.getLoadedRegion(new Point(0,0));
                if(tile!=null) {
                    image=(RenderedMap)tile.getImage();
                    if(image.isHighlighted(0,0)&&image.getHeightAt(0,0)==0) break;
                }
                Thread.sleep(10);
            }
            assertNotNull(image);assertTrue(image.isHighlighted(0,0));
            assertEquals(0,image.getHeightAt(0,0));
            assertFalse(image.isLoadedAt(16,0));
        } finally {manager.shutdown();}
    }

    @Test void temporaryBandCutDropsCutHeightsAndRetainsHighlightAndLoadedMask() {
        RenderedMap source=new RenderedMap(1,1);
        source.setHeightAt(0,0,6);source.markLoaded(0,0);source.setHighlighted(0,0,true);
        RenderedMap cut=new RenderedMap(1,1);
        cut.copyHeightsFrom(source,0,0);cut.copyHighlightsFrom(source,0,0);
        cut.putHeightAt(0,0,RenderedMap.NO_HEIGHT);
        assertEquals(RenderedMap.NO_HEIGHT,cut.getHeightAt(0,0));
        // 列仍来自存在的区块（gaps 底纹要区分"空列"与"缺失区块"），高亮标记不变。
        assertTrue(cut.isLoadedAt(0,0));assertTrue(cut.isHighlighted(0,0));
        assertEquals(6,source.getHeightAt(0,0));
    }
}
