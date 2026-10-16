package com.minecraft.selector.core;

import com.minecraft.selector.nbt.NBTReader;
import com.minecraft.selector.nbt.NBTReader.*;
import com.minecraft.selector.gui.DynamicMapManager;
import com.minecraft.selector.gui.MapCanvas;
import com.minecraft.selector.gui.DynamicMapCanvas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntUnaryOperator;
import java.util.zip.DeflaterOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class HeightmapTest {
    @TempDir Path directory;

    NBTCompound section(int sy, boolean integerY, String[] names, IntUnaryOperator blocks) {
        NBTCompound section = new NBTCompound("");
        section.put("Y", integerY ? new NBTInt("Y", sy) : new NBTByte("Y", (byte) sy));
        NBTCompound states = new NBTCompound("block_states");
        NBTList palette = new NBTList("palette", TagType.TAG_Compound);
        for (String name : names) {
            NBTCompound entry = new NBTCompound("");
            entry.put("Name", new NBTString("Name", name));
            palette.add(entry);
        }
        states.put("palette", palette);
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(names.length - 1));
        int perLong = 64 / bits;
        long[] packed = new long[(4096 + perLong - 1) / perLong];
        for (int i = 0; i < 4096; i++) packed[i / perLong] |= (long) blocks.applyAsInt(i) << ((i % perLong) * bits);
        if (names.length > 1) states.put("data", new NBTLongArray("data", packed));
        section.put("block_states", states);
        return section;
    }

    private NBTCompound solid(int sy, String name) {
        return section(sy, false, new String[]{name}, i -> 0);
    }

    Path mca(String relative, NBTCompound... sections) throws Exception {
        NBTCompound chunk = new NBTCompound("");
        chunk.put("xPos", new NBTInt("xPos", 0));
        chunk.put("zPos", new NBTInt("zPos", 0));
        NBTList list = new NBTList("sections", TagType.TAG_Compound);
        for (NBTCompound section : sections) list.add(section);
        chunk.put("sections", list);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream nbt = new DataOutputStream(raw);
        nbt.writeByte(10); nbt.writeUTF(""); writePayload(nbt, chunk);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(compressed)) { zip.write(raw.toByteArray()); }
        byte[] payload = compressed.toByteArray();
        int sectors = (payload.length + 5 + 4095) / 4096;
        ByteArrayOutputStream region = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(region);
        out.writeInt((2 << 8) | sectors); out.write(new byte[8192 - 4]);
        out.writeInt(payload.length + 1); out.writeByte(2); out.write(payload);
        out.write(new byte[sectors * 4096 - payload.length - 5]);
        Path path = directory.resolve(relative);
        Files.createDirectories(path.getParent()); Files.write(path, region.toByteArray());
        return path;
    }

    private static void writePayload(DataOutputStream out, NBTTag tag) throws Exception {
        Object value = tag.getValue();
        switch (tag.getType()) {
            case TAG_Compound:
                NBTCompound compound = (NBTCompound) tag;
                for (String key : compound.keySet()) {
                    NBTTag child = compound.get(key);
                    out.writeByte(child.getType().getId()); out.writeUTF(key); writePayload(out, child);
                }
                out.writeByte(0); break;
            case TAG_List:
                NBTList list = (NBTList) tag;
                out.writeByte(list.getListType().getId()); out.writeInt(list.size());
                for (int i = 0; i < list.size(); i++) writePayload(out, list.get(i));
                break;
            case TAG_Byte: out.writeByte(((Number) value).intValue()); break;
            case TAG_Int: out.writeInt(((Number) value).intValue()); break;
            case TAG_String: out.writeUTF((String) value); break;
            case TAG_Long_Array:
                long[] longs = (long[]) value; out.writeInt(longs.length);
                for (long item : longs) out.writeLong(item);
                break;
            default: throw new AssertionError(tag.getType());
        }
    }

    private RenderedMap render(Path path, MapRenderer.SurfaceMode mode, int sampling) throws Exception {
        MapRenderer renderer = new MapRenderer(2, null, mode);
        try {
            MapRenderer.BlockInfo[][] tops = renderer.getTopBlocks(path.toString(), 32, sampling);
            RenderedMap image = (RenderedMap) renderer.renderToPng(tops, sampling);
            for (int z = 0; z < 512; z++) for (int x = 0; x < 512; x++) {
                MapRenderer.BlockInfo block = tops[z][x];
                int expected = block == null || BlockColors.isAirBlock(block.getBlockId()) ? RenderedMap.NO_HEIGHT : block.getHeight();
                assertEquals(expected, image.getHeightAt(x, z));
                assertEquals(expected == RenderedMap.NO_HEIGHT, (image.getRGB(x, z) >>> 24) == 0);
            }
            return image;
        } finally { renderer.shutdown(); }
    }

    @Test void extendedSignedAndIntegerSectionsAndSparseColumns() throws Exception {
        Path path = mca("region/r.-1.-1.mca",
            section(-8, false, new String[]{"minecraft:air", "minecraft:stone"}, i -> i / 256 == 0 && i % 16 == 0 ? 1 : 0),
            section(40, true, new String[]{"minecraft:cave_air", "unknown:chair"}, i -> i / 256 == 15 && i % 16 == 1 ? 1 : 0),
            section(0, false, new String[]{"minecraft:void_air", "minecraft:water"}, i -> i / 256 == 0 && i % 16 == 2 ? 1 : 0));
        RenderedMap map = render(path, MapRenderer.SurfaceMode.TOP, 1);
        assertEquals(-128, map.getHeightAt(0, 0));
        assertEquals(655, map.getHeightAt(1, 0));
        assertEquals(0, map.getHeightAt(2, 0));
        assertEquals(RenderedMap.NO_HEIGHT, map.getHeightAt(3, 0));
        assertEquals(RenderedMap.NO_HEIGHT, map.getHeightAt(32, 32));
    }

    @Test void netherRoofAndBuildingsUseTheSameVisibleSurface() throws Exception {
        Path path = mca("DIM-1/region/r.0.0.mca", solid(3, "minecraft:netherrack"),
            section(7, false, new String[]{"minecraft:netherrack", "minecraft:bedrock"}, i -> i / 256 == 15 ? 1 : 0),
            solid(10, "example:roof_build"));
        assertEquals(175, render(path, MapRenderer.SurfaceMode.AUTO, 1).getHeightAt(0, 0));
        assertEquals(175, render(path, MapRenderer.SurfaceMode.TOP, 1).getHeightAt(0, 0));
    }

    @Test void raisedNetherRoofAndAllSolidColumns() throws Exception {
        Path path = mca("dimensions/minecraft/the_nether/region/r.0.0.mca", solid(30, "minecraft:bedrock"), solid(-8, "minecraft:stone"));
        assertEquals(495, render(path, MapRenderer.SurfaceMode.AUTO, 1).getHeightAt(0, 0));
        Path solid = mca("solid.mca", solid(2, "minecraft:bedrock"), solid(1, "minecraft:netherrack"), solid(0, "minecraft:netherrack"));
        assertEquals(47, render(solid, MapRenderer.SurfaceMode.AUTO, 1).getHeightAt(0, 0));
    }

    @Test void netherWithoutRoofAndEmptySections() throws Exception {
        Path path = mca("DIM-1/region/r.0.0.mca", solid(7, "minecraft:air"), solid(0, "minecraft:bedrock"), solid(2, "mod:surface"));
        assertEquals(47, render(path, MapRenderer.SurfaceMode.AUTO, 1).getHeightAt(0, 0));
        Path empty = mca("empty.mca", solid(-8, "minecraft:air"));
        assertEquals(RenderedMap.NO_HEIGHT, render(empty, MapRenderer.SurfaceMode.TOP, 1).getHeightAt(0, 0));
    }

    @Test void samplingRetainsPixelHeightAgreement() throws Exception {
        Path path = mca("region/r.0.0.mca", section(0, false, new String[]{"air", "stone"}, i -> i / 256 == i % 16 ? 1 : 0));
        RenderedMap map = render(path, MapRenderer.SurfaceMode.TOP, 4);
        assertEquals(512, map.getWidth());
        assertEquals(512, map.getHeight());
        assertEquals(4, map.getHeightAt(7, 0));
        assertEquals(0, map.getHeightAt(3, 3));
    }

    @Test void coarsePreviewScanBypassesFullResolutionCache() throws Exception {
        Path path = mca("DIM-1/region/r.0.0.mca", section(0, false,
            new String[]{"air", "stone"}, i -> i / 256 == i % 16 ? 1 : 0));
        MapRenderer renderer = new MapRenderer(1, null);
        try {
            for(int interval:new int[]{2,4,8}) {
                MapRenderer.RegionScan scan=renderer.scanRegionBlocks(path.toString(),1,null,null,true,null,interval);
                assertNull(scan.cache);
                RenderedMap image=scan.image();
                assertEquals(16,image.getWidth());assertEquals(16,image.getHeight());
                assertEquals(0,image.getHeightAt(interval-1,0));
                assertEquals(interval,image.getHeightAt(interval,0));
            }
        } finally {renderer.shutdown();}
    }

    @Test void dynamicLodSurvivesBandReloads() throws Exception {
        Path path=mca("region/r.0.0.mca",section(0,false,new String[]{"air","stone"},i -> i/256==i%16?1:0),solid(-1,"dirt"));
        DynamicRegionManager manager=new DynamicRegionManager(directory.toString(),path.getParent().toFile(),
            MapRenderer.SurfaceMode.TOP,null,null,PreviewOptions.DEFAULT,4);
        try {
            assertEquals(4,manager.getSampleInterval());
            manager.updateViewport(new java.awt.Rectangle(0,0,16,16));
            manager.awaitIdle(5,TimeUnit.SECONDS);
            DynamicRegionManager.RegionTile tile=manager.getLoadedRegion(new Point(0,0));
            assertNotNull(tile);assertEquals(512,tile.getImage().getWidth());assertEquals(512,tile.getImage().getHeight());
            assertEquals(4,tile.getHeightAt(7,0));
            manager.setBand(3,-16,15);
            manager.awaitIdle(5,TimeUnit.SECONDS);
            tile=manager.getLoadedRegion(new Point(0,0));
            assertEquals(-1,tile.getHeightAt(7,0));
            assertEquals(0,tile.getHeightAt(3,0));
            RenderedMap image=(RenderedMap)tile.getImage();
            assertTrue(image.isHighlighted(7,0));assertFalse(image.isHighlighted(3,0));
        } finally {manager.shutdown();}
    }

    @Test void composedMapsRetainHeightsAndHoles() throws Exception {
        RenderedMap map = render(mca("r.0.0.mca", solid(-8, "stone")), MapRenderer.SurfaceMode.TOP, 1);
        DynamicMapManager manager = new DynamicMapManager();
        manager.addRegion(-1, -1, map);
        Point origin = manager.getWorldOrigin();
        int x = -512 - origin.x, z = -512 - origin.y;
        assertEquals(-113, RenderedMap.heightAt(manager.getCombinedImage(), x, z));
        assertEquals(RenderedMap.NO_HEIGHT, RenderedMap.heightAt(manager.getCombinedImage(), 0, 0));
        manager.removeRegion(-1, -1);
        assertNull(manager.getCombinedImage());
    }

    @Test void asyncCacheOwnsHeightAndDoesNotPublishCancelledGeneration() throws Exception {
        RenderedMap first = render(mca("r.0.0.mca", solid(3, "stone")), MapRenderer.SurfaceMode.TOP, 1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        DynamicRegionManager manager = new DynamicRegionManager(directory.toFile(), point -> {
            started.countDown();
            release.await();
            return first;
        });
        try {
            manager.updateViewport(new Rectangle(0, 0, 16, 16));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            manager.clearAllLoadedRegions(); release.countDown();
            manager.awaitIdle(5, TimeUnit.SECONDS);
            assertTrue(manager.getAllLoadedRegions().isEmpty());
            manager.updateViewport(new Rectangle(0, 0, 16, 16));
            manager.awaitIdle(5, TimeUnit.SECONDS);
            assertEquals(63, manager.getLoadedRegion(new Point(0, 0)).getHeightAt(0, 0));
            manager.clearAllLoadedRegions();
            assertNull(manager.getLoadedRegion(new Point(0, 0)));
        } finally { release.countDown(); manager.shutdown(); }
    }

    @Test void hoverUsesCachedNegativeCoordinatesAndClearsOutsideImage() throws Exception {
        RenderedMap image = render(mca("r.0.0.mca", solid(-8, "stone")), MapRenderer.SurfaceMode.TOP, 1);
        SwingUtilities.invokeAndWait(() -> {
            MapCanvas canvas = new MapCanvas();
            canvas.setSize(512, 512);
            canvas.setImage(image); canvas.setWorldCoordinateMapping(-512, -512, 1);
            // 逐列方块 ID 元数据已移除（每块 +4 MB），悬停只保留坐标与表面高度。
            assertEquals("X: -512  Y: -113  Z: -512", canvas.getHoverText(new Point(0, 0)));
            canvas.setZoomLevel(0.5);
            assertTrue(canvas.getHoverText(new Point(-1, -1)).contains("Y: —"));
            canvas.setImage(null);
            assertTrue(canvas.getHoverText(new Point(0, 0)).contains("Y: —"));
        });
    }

    @Test void dynamicHoverUsesNegativeCoordinatesAndTracksDimensionReplacement() throws Exception {
        Path roofPath = mca("DIM-1/region/r.-1.-1.mca", solid(3, "stone"), solid(7, "bedrock"));
        DynamicRegionManager automatic = new DynamicRegionManager(directory.toString(), roofPath.getParent().toFile());
        DynamicRegionManager roof = new DynamicRegionManager(directory.toString(), roofPath.getParent().toFile(), MapRenderer.SurfaceMode.TOP);
        DynamicMapCanvas[] reference = new DynamicMapCanvas[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                DynamicMapCanvas canvas = new DynamicMapCanvas(); reference[0] = canvas;
                canvas.setSize(800, 600); canvas.setRegionManager(automatic);
                assertTrue(canvas.jumpToWorldCoordinate(new Point(-512, -512)));
            });
            automatic.awaitIdle(5, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                DynamicMapCanvas canvas = reference[0];
                assertEquals("X: -512  Y: 127  Z: -512", canvas.getHoverText(new Point(400, 300)));
                // Half a block left must floor into the absent west region, not round to -512.
                assertTrue(canvas.getHoverText(new Point(399, 300)).contains("X: -513"));
                assertTrue(canvas.getHoverText(new Point(399, 300)).contains("Y: —"));
                canvas.setRegionManager(roof);
                assertTrue(canvas.getHoverText(new Point(400, 300)).contains("Y: —"));
            });
            roof.awaitIdle(5, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                DynamicMapCanvas canvas = reference[0];
                assertEquals("X: -512  Y: 127  Z: -512", canvas.getHoverText(new Point(400, 300)));
                canvas.cleanup(); canvas.setRegionManager(null);
                assertTrue(canvas.getHoverText(new Point(400, 300)).contains("Y: —"));
            });
        } finally {
            automatic.shutdown(); roof.shutdown();
            if (reference[0] != null) SwingUtilities.invokeAndWait(reference[0]::cleanup);
        }
    }

    @Test void unknownModNamesRemainVisible() {
        assertEquals(255, BlockColors.getBlockColor("mod:chair").getAlpha());
        assertEquals(255, BlockColors.getBlockColor("mod:none_widget").getAlpha());
        assertTrue(BlockColors.isAirBlock("minecraft:cave_air"));
        assertFalse(BlockColors.isAirBlock("mod:air"));
    }

    @Test void malformedRegionFailsInsteadOfPublishingBlankHeightmap() throws Exception {
        Path path = directory.resolve("broken.mca"); Files.write(path, new byte[12]);
        MapRenderer renderer = new MapRenderer(1, null);
        try { assertThrows(IOException.class, () -> renderer.getTopBlocks(path.toString(), 32, 1)); }
        finally { renderer.shutdown(); }
    }
}
