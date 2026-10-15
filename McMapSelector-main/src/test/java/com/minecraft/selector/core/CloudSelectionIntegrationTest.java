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

/** Selection metadata must survive the cloud column-cache and live-band paths. */
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

    @Test void cachedBandKeepsSelectionMetadataAndCloudPixels() throws Exception {
        Path file=fixture();
        MapRenderer renderer=new MapRenderer(1,null,MapRenderer.SurfaceMode.TOP);
        try {
            ColumnCache cache=renderer.scanRegion(file.toString(),2);
            RenderedMap full=cache.toImage(null,null);
            assertEquals("minecraft:water",full.getBlockAt(0,0));
            assertEquals("custom:plateau",full.getBiomeAt(0,0));
            assertEquals(6,full.getFitHeightAt(0,0,true));
            assertEquals(0,full.getFitHeightAt(0,0,false));
            assertTrue(full.isLoadedAt(0,0));assertFalse(full.isLoadedAt(16,0));

            RenderedMap band=cache.toImage(4,-64,8);
            assertTrue(band.isHighlighted(0,0));
            assertEquals("minecraft:stone",band.getBlockAt(0,0));
            assertEquals(0,band.getFitHeightAt(0,0,true));
            assertEquals("custom:plateau",band.getBiomeAt(0,0));
            MapRenderer direct=new MapRenderer(1,null,MapRenderer.SurfaceMode.TOP,4,-64);
            try {
                RenderedMap fresh=(RenderedMap)direct.renderToPng(direct.getTopBlocks(file.toString(),2,1),1);
                assertArrayEquals(fresh.getRGB(0,0,32,32,null,0,32),band.getRGB(0,0,32,32,null,0,32));
            } finally {direct.shutdown();}

            RenderedMap composed=new RenderedMap(40,40);
            composed.copyHeightsFrom(band,4,4);composed.copyHighlightsFrom(band,4,4);
            assertEquals("minecraft:stone",composed.getBlockAt(4,4));
            assertEquals("custom:plateau",composed.getBiomeAt(4,4));
            assertTrue(composed.isHighlighted(4,4));
            assertEquals(0,SelectionYFit.scan(composed,0,0,1,new SelectionMath.Bounds(4,4,19,19),false).max);

            RenderedMap empty=cache.toImage(10,8);
            assertTrue(empty.isLoadedAt(0,0));assertFalse(empty.isLoadedAt(16,0));
            assertNull(empty.getBlockAt(0,0));
            assertEquals(RenderedMap.NO_HEIGHT,empty.getFitHeightAt(0,0,true));
        } finally {renderer.shutdown();}
    }

    @Test void optionalWaterPreviewKeepsItsPolicyAndBothFitHeightsInCache() throws Exception {
        Path file=fixture();
        MapRenderer renderer=new MapRenderer(1,null,MapRenderer.SurfaceMode.TOP,null,BlockColors.snapshot(),new PreviewOptions(true,false,0));
        try {
            ColumnCache cache=renderer.scanRegion(file.toString(),2);
            RenderedMap image=cache.toImage(10,-64,12);
            assertEquals(0,image.getHeightAt(0,0));
            assertEquals("minecraft:stone",image.getBlockAt(0,0));
            assertEquals(6,image.getFitHeightAt(0,0,true));
            assertEquals(0,image.getFitHeightAt(0,0,false));
            RenderedMap fresh=(RenderedMap)renderer.renderToPng(renderer.getTopBlocks(file.toString(),2,1),1);
            assertArrayEquals(fresh.getRGB(0,0,32,32,null,0,32),image.getRGB(0,0,32,32,null,0,32));
        } finally {renderer.shutdown();}
    }

    @Test void liveDynamicBandRetainsMetadataAndPurpleMask() throws Exception {
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
            assertEquals("minecraft:stone",image.getBlockAt(0,0));
            assertEquals("custom:plateau",image.getBiomeAt(0,0));
            assertEquals(0,image.getFitHeightAt(0,0,true));
            assertFalse(image.isLoadedAt(16,0));
        } finally {manager.shutdown();}
    }

    @Test void temporaryBandCutDropsStaleSelectionDataAndRetainsHighlight() {
        RenderedMap source=new RenderedMap(1,1);
        source.setHeightAt(0,0,6);source.setBlockAt(0,0,"minecraft:water");
        source.setColumnMetadata(0,0,"custom:plateau",6,0);source.setHighlighted(0,0,true);
        RenderedMap cut=new RenderedMap(1,1);
        cut.copyHeightsFrom(source,0,0);cut.copyHighlightsFrom(source,0,0);
        cut.putHeightAt(0,0,RenderedMap.NO_HEIGHT);cut.clipSelectionMetadataBelow(8);
        assertNull(cut.getBlockAt(0,0));assertTrue(cut.isLoadedAt(0,0));assertTrue(cut.isHighlighted(0,0));
        assertEquals(RenderedMap.NO_HEIGHT,cut.getFitHeightAt(0,0,true));
        assertEquals(RenderedMap.NO_HEIGHT,cut.getFitHeightAt(0,0,false));
        assertEquals("minecraft:water",source.getBlockAt(0,0));assertEquals(6,source.getHeightAt(0,0));
    }
}
