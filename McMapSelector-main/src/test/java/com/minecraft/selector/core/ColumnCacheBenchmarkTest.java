package com.minecraft.selector.core;

import com.minecraft.selector.nbt.NBTReader;
import com.minecraft.selector.nbt.NBTReader.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打印层缓存的建立/重合成开销（不断言绝对时间，避免机器差异导致误报）。
 * 场景模拟普通地表：深层深板岩/石头、一层水、泥土+草方块，部分 section 干脆不写（等同全空气）。
 */
class ColumnCacheBenchmarkTest {
    @TempDir Path directory;

    private static final int CHUNKS = 8;
    private static final int FULL = 32;

    @Test void reportFullRegionCostBreakdown() throws Exception {
        Path path = region(directory, FULL);
        MapRenderer renderer = new MapRenderer(4, null, MapRenderer.SurfaceMode.AUTO, (Integer) null, (Integer) null);
        try {
            ColumnCache cache = renderer.scanRegion(path.toString(), FULL);
            long t0 = System.nanoTime();
            MapRenderer.BlockInfo[][] blocks = cache.topBlocks(null, null);
            long topBlocks = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            renderer.renderToPng(blocks, 1);
            long png = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            renderer.renderToPng(cache.topBlocks(200, -40), 1);
            long whole = (System.nanoTime() - t0) / 1_000_000;
            // 只改下界（其余列不变）
            t0 = System.nanoTime();
            renderer.renderToPng(cache.topBlocks(null, 60), 1);
            long lower = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("FULLREGION columns=%d runs=%d | topBlocks=%d ms renderToPng=%d ms 合计=%d ms | 仅改下界=%d ms%n",
                cache.getSize() * cache.getSize(), cache.getRunCount(), topBlocks, png, whole, lower);
        } finally {
            renderer.shutdown();
        }
    }

    @Test void reportScanAndResurfaceCost() throws Exception {
        Path path = region(directory, CHUNKS);

        MapRenderer warm = new MapRenderer(4, null, MapRenderer.SurfaceMode.AUTO, (Integer) null, (Integer) null);
        ColumnCache cache;
        long buildStart = System.nanoTime();
        try {
            cache = warm.scanRegion(path.toString(), CHUNKS);
        } finally {
            warm.shutdown();
        }
        long buildMillis = (System.nanoTime() - buildStart) / 1_000_000;

        MapRenderer renderer = new MapRenderer(4, null, MapRenderer.SurfaceMode.AUTO, (Integer) null, (Integer) null);
        try {
            long fullStart = System.nanoTime();
            MapRenderer.BlockInfo[][] fresh = renderer.getTopBlocks(path.toString(), CHUNKS, 1);
            long fullMillis = (System.nanoTime() - fullStart) / 1_000_000;

            long resurfaceStart = System.nanoTime();
            java.awt.image.BufferedImage image = renderer.renderToPng(cache.topBlocks(null, null), 1);
            long resurfaceMillis = (System.nanoTime() - resurfaceStart) / 1_000_000;

            long againStart = System.nanoTime();
            renderer.renderToPng(cache.topBlocks(80, 40), 1);
            long againMillis = (System.nanoTime() - againStart) / 1_000_000;

            long t0 = System.nanoTime();
            cache.toImage(null, null);
            long fastImage = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            cache.toImage(80, 40);
            long fastAgain = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            renderer.renderToPng(cache.topBlocks(null, null), 1);
            long blockImage = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("FULLREGION 数组快路径 toImage=%d ms / 换带再算=%d ms  |  旧 BlockInfo 路径=%d ms%n",
                fastImage, fastAgain, blockImage);

            assertNotNull(fresh);
            assertNotNull(image);
            int chunks = CHUNKS * CHUNKS;
            // 确认基准真的扫到了东西（否则数字没意义）
            assertTrue(cache.getRunCount() > chunks * 100, "缓存段数太少，基准数据可疑");
            assertEquals(95, cache.topBlocks(null, null)[0][0].getHeight());
            System.out.printf("=== 层缓存开销（%dx%d = %d 区块，%d 列）===%n",
                CHUNKS, CHUNKS, chunks, cache.getSize() * cache.getSize());
            System.out.printf("全量带状渲染(现有路径) : %d ms%n", fullMillis);
            System.out.printf("层缓存建立(整列扫描)   : %d ms  (段数 %,d)%n", buildMillis, cache.getRunCount());
            System.out.printf("缓存重合成+出图        : %d ms%n", resurfaceMillis);
            System.out.printf("换带再来一次           : %d ms%n", againMillis);
            System.out.printf("换算到 1024 区块(32x32) : 建立约 %d ms, 重合成约 %d ms%n",
                buildMillis * 1024 / chunks, resurfaceMillis * 1024 / chunks);
        } finally {
            renderer.shutdown();
        }
    }

    /** 写一个 CHUNKS x CHUNKS 的 region：深层深板岩、石头、(水)、泥土、草方块。 */
    private Path region(Path dir, int chunks) throws Exception {
        ByteArrayOutputStream region = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(region);
        header.write(new byte[8192]);
        int sector = 2;
        int[] offsets = new int[1024], counts = new int[1024];
        HeightmapTest h = new HeightmapTest();
        h.directory = dir;
        for (int chunkZ = 0; chunkZ < chunks; chunkZ++) {
            for (int chunkX = 0; chunkX < chunks; chunkX++) {
                NBTCompound chunk = new NBTCompound("");
                chunk.put("xPos", new NBTInt("xPos", chunkX));
                chunk.put("zPos", new NBTInt("zPos", chunkZ));
                NBTList list = new NBTList("sections", TagType.TAG_Compound);
                list.add(h.section(-4, true, new String[]{"minecraft:deepslate"}, i -> 0));
                for (int y = -3; y <= 2; y++) list.add(h.section(y, true, new String[]{"minecraft:stone"}, i -> 0));
                list.add(h.section(3, true, new String[]{"minecraft:water"}, i -> 0));
                list.add(h.section(4, true, new String[]{"minecraft:dirt"}, i -> 0));
                list.add(h.section(5, true, new String[]{"minecraft:grass_block"}, i -> 0));
                chunk.put("sections", list);

                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                DataOutputStream nbt = new DataOutputStream(raw);
                nbt.writeByte(10); nbt.writeUTF(""); writePayload(nbt, chunk);
                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                try (DeflaterOutputStream zip = new DeflaterOutputStream(compressed)) { zip.write(raw.toByteArray()); }
                byte[] payload = compressed.toByteArray();
                int sectors = (payload.length + 5 + 4095) / 4096;
                DataOutputStream out = new DataOutputStream(region);
                out.writeInt(payload.length + 1); out.writeByte(2); out.write(payload);
                out.write(new byte[sectors * 4096 - payload.length - 5]);
                int index = chunkX + chunkZ * 32;
                offsets[index] = sector;
                counts[index] = sectors;
                sector += sectors;
            }
        }
        byte[] bytes = region.toByteArray();
        for (int i = 0; i < 1024; i++) {
            if (offsets[i] == 0) continue;
            int at = i * 4;
            // 头部：3 字节扇区偏移 + 1 字节扇区数（时间戳那 3 字节留 0）
            bytes[at] = (byte) (offsets[i] >> 16); bytes[at + 1] = (byte) (offsets[i] >> 8); bytes[at + 2] = (byte) offsets[i];
            bytes[at + 3] = (byte) counts[i];
        }
        Path path = dir.resolve("region/r.0.0.mca");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return path;
    }

    private static void writePayload(DataOutputStream out, NBTTag tag) throws Exception {
        Object value = tag.getValue();
        switch (tag.getType()) {
            case TAG_Compound:
                for (String key : ((NBTCompound) tag).keySet()) {
                    NBTTag child = ((NBTCompound) tag).get(key);
                    out.writeByte(child.getType().getId()); out.writeUTF(key); writePayload(out, child);
                }
                out.writeByte(0); break;
            case TAG_List:
                NBTList list = (NBTList) tag;
                out.writeByte(10); out.writeInt(list.size());
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
}
