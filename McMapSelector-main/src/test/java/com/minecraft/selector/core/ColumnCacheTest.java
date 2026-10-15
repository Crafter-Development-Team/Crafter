package com.minecraft.selector.core;

import com.minecraft.selector.nbt.NBTReader.NBTCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 层缓存换 Y 带后的结果，必须与"直接在该带内重扫"逐列、逐像素一致 ——
 * 包括半透明层被带顶/带底切掉、以及带底放开后重新出现深层方块这两种方向。
 */
class ColumnCacheTest {
    @TempDir Path directory;

    private static final String[] PALETTE = {"minecraft:air", "minecraft:stone", "minecraft:glass"};

    /**
     * 一个区块（x 决定列型，z 行都相同）：
     * x=0 石柱 64..70；x=1 玻璃70 压 石68；x=2 只有 石64；x=3 表面全空、深处 -50 有石；
     * x=5 只有 玻璃70（不饱和）；x>=4 石70。
     */
    private Path fixture() throws Exception {
        HeightmapTest h = new HeightmapTest(); h.directory = directory;
        NBTCompound surface = h.section(4, true, PALETTE, i -> {
            int x = i % 16, y = i / 256;
            switch (x) {
                case 0: return y <= 6 ? 1 : 0;
                case 1: return y == 6 ? 2 : (y == 4 ? 1 : 0);
                case 2: return y == 0 ? 1 : 0;
                case 3: return 0;
                case 5: return y == 6 ? 2 : 0;
                default: return y == 6 ? 1 : 0;
            }
        });
        NBTCompound deep = h.section(-4, true, PALETTE, i -> (i % 16 == 3 && i / 256 == 14) ? 1 : 0);
        return h.mca("region/r.0.0.mca", surface, deep);
    }

    @Test void cachedBandsEqualFreshScansInBothDirections() throws Exception {
        Path path = fixture();
        ColumnCache cache = scan(path, MapRenderer.SurfaceMode.AUTO);
        assertNotNull(cache);

        Integer[][] bands = {
            {null, null}, {100, null}, {71, null}, {70, null}, {69, null}, {68, null}, {65, null}, {63, null},
            {null, 71}, {null, 70}, {null, 69}, {null, 65}, {null, 64}, {null, -49}, {null, -55}, {null, -60},
            {70, 69}, {69, 68}, {71, 65}, {65, -60}, {-49, null}, {-50, -60}, {200, -200}
        };
        for (Integer[] band : bands) {
            assertBandEquals(band, cache.topBlocks(band[0], band[1]), freshScan(path, band[0], band[1]));
        }
    }

    @Test void cachedBandsProduceIdenticalPixels() throws Exception {
        Path path = fixture();
        ColumnCache cache = scan(path, MapRenderer.SurfaceMode.AUTO);
        MapRenderer renderer = new MapRenderer(1, null, MapRenderer.SurfaceMode.AUTO, (Integer) null, (Integer) null);
        try {
            Integer[][] bands = {{null, null}, {null, 69}, {70, null}, {null, -60}, {69, 68}, {65, -60}};
            for (Integer[] band : bands) {
                BufferedImage cached = renderer.renderToPng(cache.topBlocks(band[0], band[1]), 1);
                BufferedImage fresh = renderer.renderToPng(freshScan(path, band[0], band[1]), 1);
                assertEquals(fresh.getWidth(), cached.getWidth());
                assertEquals(fresh.getHeight(), cached.getHeight());
                for (int y = 0; y < fresh.getHeight(); y++) {
                    for (int x = 0; x < fresh.getWidth(); x++) {
                        assertEquals(fresh.getRGB(x, y), cached.getRGB(x, y),
                            "pixel " + x + "," + y + " band " + band[0] + ".." + band[1]);
                    }
                }
            }
        } finally {
            renderer.shutdown();
        }
    }

    @Test void bandEditsChangeExactlyWhatTheyShould() throws Exception {
        Path path = fixture();
        ColumnCache cache = scan(path, MapRenderer.SurfaceMode.AUTO);

        // 全不裁：玻璃压石头 → 合成后不透明；x=3 的深层石头也在（无带底）
        MapRenderer.BlockInfo[][] all = cache.topBlocks(null, null);
        assertEquals("glass", all[0][1].getBlockId());
        assertEquals(70, all[0][1].getHeight());
        assertTrue(all[0][1].getSurfaceColor().getAlpha() > 200, "玻璃叠石头后应接近不透明");
        assertEquals("stone", all[0][3].getBlockId());
        assertEquals(-50, all[0][3].getHeight());

        // 带顶压到 69：只剩下面那块石头
        MapRenderer.BlockInfo[][] under70 = cache.topBlocks(69, null);
        assertEquals("stone", under70[0][1].getBlockId());
        assertEquals(68, under70[0][1].getHeight());

        // 带底抬到 70：玻璃被留下、石头被切掉 → 重新变半透明
        MapRenderer.BlockInfo[][] onlyGlass = cache.topBlocks(70, 70);
        assertEquals("glass", onlyGlass[0][1].getBlockId());
        assertTrue(onlyGlass[0][1].getSurfaceColor().getAlpha() < 120, "只剩玻璃时应是半透明");
        assertEquals(70, onlyGlass[0][1].getHeight());

        // 带底放到 -60：x=3 的深层石头出现；带底收到 -49 又变空
        assertEquals(-50, cache.topBlocks(null, -60)[0][3].getHeight());
        assertEquals(MapRenderer.EMPTY, cache.topBlocks(null, -49)[0][3]);

        // 带顶压到 63：这一整片地面全部空掉
        assertEquals(MapRenderer.EMPTY, cache.topBlocks(63, null)[0][0]);

        // 只有玻璃的列：不饱和，合成 alpha 就是玻璃自身
        assertEquals("glass", cache.topBlocks(null, null)[0][5].getBlockId());
        assertEquals(cache.topBlocks(null, 0)[0][5].getSurfaceColor(),
                     cache.topBlocks(200, 0)[0][5].getSurfaceColor());
    }

    @Test void inRenderScanGivesTheSameBlocksAndAReusableCache() throws Exception {
        Path path = fixture();
        Integer[][] bands = {{null, null}, {null, 69}, {70, null}, {65, -60}};
        for (Integer[] band : bands) {
            MapRenderer renderer = new MapRenderer(1, null, MapRenderer.SurfaceMode.AUTO, band[0], band[1]);
            MapRenderer.RegionScan scan;
            try {
                scan = renderer.scanRegionBlocks(path.toString(), 1, band[0], band[1], true);
            } finally {
                renderer.shutdown();
            }
            assertNotNull(scan.cache, "渲染那次应该顺手建出层缓存");
            assertBandEquals(band, scan.topBlocks(), freshScan(path, band[0], band[1]));
            // 顺手建出来的缓存必须能服务别的 Y 带
            assertBandEquals(new Integer[]{69, null}, scan.cache.topBlocks(69, null), freshScan(path, 69, null));
            assertBandEquals(new Integer[]{null, -60}, scan.cache.topBlocks(null, -60), freshScan(path, null, -60));
        }
    }

    @Test void arrayFastPathMatchesBlockPathPixelsAndHeights() throws Exception {
        Path path = fixture();
        ColumnCache cache = scan(path, MapRenderer.SurfaceMode.AUTO);
        MapRenderer renderer = new MapRenderer(1, null, MapRenderer.SurfaceMode.AUTO, (Integer) null, (Integer) null);
        try {
            Integer[][] bands = {{null, null}, {null, 69}, {70, null}, {65, -60}, {69, 68}};
            for (Integer[] band : bands) {
                BufferedImage fast = cache.toImage(band[0], band[1]);
                BufferedImage slow = renderer.renderToPng(cache.topBlocks(band[0], band[1]), 1);
                assertEquals(slow.getWidth(), fast.getWidth());
                for (int y = 0; y < slow.getHeight(); y++) {
                    for (int x = 0; x < slow.getWidth(); x++) {
                        assertEquals(slow.getRGB(x, y), fast.getRGB(x, y),
                            "pixel " + x + "," + y + " band " + band[0] + ".." + band[1]);
                        assertEquals(RenderedMap.heightAt(slow, x, y), RenderedMap.heightAt(fast, x, y),
                            "height " + x + "," + y + " band " + band[0] + ".." + band[1]);
                    }
                }
            }
        } finally {
            renderer.shutdown();
        }
    }

    @Test void highlightFlagMarksColumnsWithBlocksAboveTheTopBound() throws Exception {
        Path path = fixture();
        ColumnCache cache = scan(path, MapRenderer.SurfaceMode.AUTO);
        // 上界 64、兴趣带上沿 70：x=0（石 64..70）在 (64,70] 里有方块 → 标记；
        // x=2（只有石 64）与 x=3（只有深层石 -50）→ 不标记
        RenderedMap image = cache.toImage(64, null, 70);
        assertTrue(image.isHighlighted(0, 0), "x=0 在兴趣带里有方块");
        assertTrue(image.isHighlighted(1, 0), "x=1 玻璃 70 也在带里");
        assertFalse(image.isHighlighted(2, 0), "x=2 带里没有方块");
        assertFalse(image.isHighlighted(3, 0), "x=3 只有深层方块");

        // 兴趣带上沿 == 上界 → 带为空 → 全不标记
        RenderedMap off = cache.toImage(64, null, 64);
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++)
                assertFalse(off.isHighlighted(x, z), "空兴趣带不该标记 @" + x + "," + z);

        // 标记只是"掩码"：不得改变像素与高度
        RenderedMap plain = cache.toImage(64, null);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(plain.getRGB(x, z), image.getRGB(x, z), "highlight 不该改像素 @" + x + "," + z);
                assertEquals(RenderedMap.heightAt(plain, x, z), RenderedMap.heightAt(image, x, z),
                    "highlight 不该改高度 @" + x + "," + z);
            }
        }
    }

    @Test void netherRoofRegionsAreNotCached() throws Exception {
        HeightmapTest h = new HeightmapTest(); h.directory = directory;
        Path path = h.mca("DIM-1/region/r.0.0.mca", h.section(7, true, new String[]{"minecraft:bedrock"}, i -> 0));
        assertNull(scan(path, MapRenderer.SurfaceMode.AUTO), "下界内部视图的分支判定跟扫描窗口耦合，不缓存");
        assertNotNull(scan(path, MapRenderer.SurfaceMode.TOP), "TOP 模式没有 roof 分支，仍可缓存");
    }

    private ColumnCache scan(Path path, MapRenderer.SurfaceMode mode) throws Exception {
        MapRenderer renderer = new MapRenderer(1, null, mode, (Integer) null, (Integer) null);
        try {
            return renderer.scanRegion(path.toString(), 1);
        } finally {
            renderer.shutdown();
        }
    }

    private MapRenderer.BlockInfo[][] freshScan(Path path, Integer top, Integer bottom) throws Exception {
        MapRenderer renderer = new MapRenderer(1, null, MapRenderer.SurfaceMode.AUTO, top, bottom);
        try {
            return renderer.getTopBlocks(path.toString(), 1, 1);
        } finally {
            renderer.shutdown();
        }
    }

    private void assertBandEquals(Integer[] band, MapRenderer.BlockInfo[][] cached, MapRenderer.BlockInfo[][] fresh) {
        String where = "band " + band[0] + ".." + band[1];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                MapRenderer.BlockInfo expected = fresh[z][x], actual = cached[z][x];
                String at = where + " @ " + x + "," + z;
                assertEquals(expected.getBlockId(), actual.getBlockId(), at + " id");
                assertEquals(expected.getHeight(), actual.getHeight(), at + " height");
                Color expectedColor = expected.getSurfaceColor(), actualColor = actual.getSurfaceColor();
                if (expectedColor == null) assertNull(actualColor, at + " color");
                else {
                    assertNotNull(actualColor, at + " color");
                    assertEquals(expectedColor.getRGB(), actualColor.getRGB(), at + " argb");
                }
            }
        }
    }
}
