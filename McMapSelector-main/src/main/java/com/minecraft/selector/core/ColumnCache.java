package com.minecraft.selector.core;

import java.awt.Color;
import java.util.Collections;

/**
 * 一个 region 的逐列层栈缓存：扫一次之后，任何 Y 带都只做内存重合成，不再读盘、不再解析 NBT。
 *
 * <p>整列从世界顶部到底部按"可见层"收集，并把相邻的同方块同颜色层压成一段（run-length）。
 * 压制后典型的泥土/石头柱子只剩几段，所以换带时既能精确复现带内的合成结果，
 * 又不会因为逐层保存而爆内存。
 *
 */
public final class ColumnCache {
    private final int size;
    private final int[] offsets;    // 每列段数的前缀和
    private final int[] runTopY;    // 段内最高 Y（段是连续的，自上而下）
    private final int[] runBottomY;
    private final int[] runArgb;    // 该段每层自身的颜色
    private final String[] runId;
    /** 每列 1 bit：该列来自存在的区块（含全空气列）。512x512 仅 32 KB。 */
    private final java.util.BitSet loaded;
    private final PreviewOptions preview;
    private final String renderKey;

    ColumnCache(int size, int[] offsets, int[] runTopY, int[] runBottomY, int[] runArgb, String[] runId, java.util.BitSet loaded, PreviewOptions preview, String renderKey) {
        this.size = size;
        this.offsets = offsets;
        this.runTopY = runTopY;
        this.runBottomY = runBottomY;
        this.runArgb = runArgb;
        this.runId = runId;
        this.loaded=loaded;
        this.preview=preview;this.renderKey=renderKey;
    }

    public PreviewOptions getPreviewOptions() {return preview;}
    public int getSize() { return size; }

    /**
     * 单个 region 层缓存的估计占用。512x512 列 × 约 5 段/列 ≈ 130 万段，
     * 每段 runTopY/runBottomY/runArgb 各 4 字节 + runId 引用 ≈ 20 字节，加上 offsets 表。
     */
    public static final long ESTIMATED_BYTES_PER_REGION = 25L * 1024 * 1024;

    /**
     * 层缓存能保留的 region 数上限。
     *
     * <p>关键是必须留足"一个视口同时需要的量"：最大档位是 7x7 = 49 个 region。
     * 旧公式 {@code min(9, heap/64MB)} 在 4GB 堆上只给 9，而 4x4 视口就要 16 个，
     * 于是 ensureColumnCaches 一边建、LRU 一边把刚建好的淘汰掉 —— 缓存永远凑不齐，
     * 每次换带都退回整区重扫（日志里 5895 次"缺缓存重载"就是这么来的）。
     *
     * <p>现在按堆内存给预算：最多用 1/3 堆放层缓存。4GB 堆 ≈ 54 个，够覆盖 7x7 视口；
     * 堆小的时候会退化成只够小视口 —— 那时优先保证不 OOM。
     */
    public static int cacheLimit(long maxHeapBytes) {
        long budget = Math.max(1, maxHeapBytes / 3);
        long byHeap = budget / ESTIMATED_BYTES_PER_REGION;
        return (int) Math.max(8, Math.min(128, byHeap));
    }

    /** 缓存里的段总数（大致反映内存占用）。 */
    public int getRunCount() { return runTopY.length; }

    /** 用给定 Y 带（两端可为 null 表示该侧不裁）在内存里重新合成每列的顶部方块。 */
    public MapRenderer.BlockInfo[][] topBlocks(Integer clipY, Integer clipMinY) {
        MapRenderer.BlockInfo[][] out = new MapRenderer.BlockInfo[size][size];
        int[] included = new int[8];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int column = z * size + x;
                int from = offsets[column], to = offsets[column + 1];
                int count = 0, topRun = -1, topY = 0;
                double alpha = 0;
                for (int run = from; run < to; run++) {
                    int yTop = runTopY[run], yBottom = runBottomY[run];
                    if (clipMinY != null && yTop < clipMinY) break;  // 段自上而下，后面更低
                    int high = clipY != null ? Math.min(yTop, clipY) : yTop;
                    int low = clipMinY != null ? Math.max(yBottom, clipMinY) : yBottom;
                    if (low > high) continue;                        // 整段都在带顶之上
                    if(preview.transparentWater && PreviewOptions.water(runId[run])) continue;
                    int argb = runArgb[run];
                    double layerAlpha = ((argb >>> 24) & 0xFF) / 255.0;
                    if (topRun < 0) { topRun = run; topY = high; }
                    // 与扫描一致：逐层累加，到不透明饱和就停
                    for (int y = high; y >= low; y--) {
                        if (count == included.length) included = java.util.Arrays.copyOf(included, count * 2);
                        included[count++] = argb;
                        alpha += (1 - alpha) * layerAlpha;
                        if (alpha >= 0.995) break;
                    }
                    if (alpha >= 0.995) break;
                }
                if (topRun < 0) {
                    out[z][x] = MapRenderer.EMPTY;
                    continue;
                }
                // 合成方向必须与原实现一致：从最深处往上叠
                Color color = EMPTY_COLOR;
                for (int i = count - 1; i >= 0; i--) color = MapRenderer.over(new Color(included[i], true), color);
                out[z][x] = new MapRenderer.BlockInfo(runId[topRun], topY, Collections.emptyMap(), color);
            }
        }
        return out;
    }

    /**
     * 直接把结果写进调用方给的「高度 / 基色」两个数组（行优先，长度 size*size）。
     * 实时重合成走这条：不构造任何 BlockInfo，一帧的垃圾量从几百 MB 降到几 MB。
     */
    public void toArrays(Integer clipY, Integer clipMinY, int[] heights, int[] baseColors) {
        toArrays(clipY, clipMinY, null, heights, baseColors, null);
    }

    /**
     * 直接把结果写进调用方给的「高度 / 基色」两个数组。
     * highlightTop 非空时，额外标出"在上界之上、highlightTop 之下还有方块"的列（highlight[i]=1），
     * 由画布按屏幕坐标叠紫色棋盘 —— 所以图案不会随地图拖动。
     */
    public void toArrays(Integer clipY, Integer clipMinY, Integer highlightTop,
                         int[] heights, int[] baseColors, byte[] highlight) {
        int[] included = new int[8];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int index = z * size + x;
                int column = index;
                int from = offsets[column], to = offsets[column + 1];
                int count = 0, topRun = -1, topY = 0;
                double alpha = 0;
                boolean above = false;
                int topBound = clipY != null ? clipY : Integer.MIN_VALUE;
                boolean bandActive = highlightTop != null && highlightTop > topBound;   // 空带不标
                for (int run = from; run < to; run++) {
                    int yTop = runTopY[run], yBottom = runBottomY[run];
                    if (bandActive && yTop > topBound && yBottom <= highlightTop) above = true;
                    if (clipMinY != null && yTop < clipMinY) break;
                    int high = clipY != null ? Math.min(yTop, clipY) : yTop;
                    int low = clipMinY != null ? Math.max(yBottom, clipMinY) : yBottom;
                    if (low > high) continue;
                    if(preview.transparentWater && PreviewOptions.water(runId[run])) continue;
                    int argb = runArgb[run];
                    double layerAlpha = ((argb >>> 24) & 0xFF) / 255.0;
                    if (topRun < 0) { topRun = run; topY = high; }
                    for (int y = high; y >= low; y--) {
                        if (count == included.length) included = java.util.Arrays.copyOf(included, count * 2);
                        included[count++] = argb;
                        alpha += (1 - alpha) * layerAlpha;
                        if (alpha >= 0.995) break;
                    }
                    if (alpha >= 0.995) break;
                }
                if (highlight != null) highlight[index] = (byte) (above ? 1 : 0);
                if (topRun < 0) {
                    heights[index] = RenderedMap.NO_HEIGHT;
                    baseColors[index] = 0;
                    continue;
                }
                Color color = EMPTY_COLOR;
                for (int i = count - 1; i >= 0; i--) color = MapRenderer.over(new Color(included[i], true), color);
                heights[index] = topY;
                baseColors[index] = color.getRGB();
            }
        }
    }

    /** 换带出图：数组快路径 + 与全量渲染共用的描边规则。 */
    public RenderedMap toImage(Integer clipY, Integer clipMinY) {
        return toImage(clipY, clipMinY, null);
    }

    /** 带"兴趣带高亮"标记的出图；highlightTop == null 表示不要高亮。 */
    public RenderedMap toImage(Integer clipY, Integer clipMinY, Integer highlightTop) {
        int[] heights = new int[size * size];
        int[] baseColors = new int[size * size];
        byte[] highlight = highlightTop != null ? new byte[size * size] : null;
        toArrays(clipY, clipMinY, highlightTop, heights, baseColors, highlight);
        RenderedMap image=MapRenderer.renderArrays(size, size, heights, baseColors, highlight);
        image.setRenderKey(renderKey+"|band="+clipMinY+":"+clipY);
        // 逐列元数据已移除，但"该列来自存在的区块"仍要精确保留（含全空气列），
        // 否则 gaps 底纹会把已加载的空列画成缺失区块，Fit-Y 的 missing/empty 也会串。
        for(int col=loaded.nextSetBit(0);col>=0;col=loaded.nextSetBit(col+1)) image.markLoaded(col%size,col/size);
        return image;
    }

    private static final Color EMPTY_COLOR = new Color(0, 0, 0, 0);

    /** 一个区块（16x16 列）的段表，扫描时并行产出，最后统一拼成扁平缓存。 */
    static final class ChunkColumns {
        static final int COLUMNS = 256;
        final int[] runs = new int[COLUMNS];        // 每列段数
        final int[] lastArgb = new int[COLUMNS];
        final String[] lastId = new String[COLUMNS];
        final int[] lastY = new int[COLUMNS];       // 该列上一层的 Y（用于判断连续）
        int[] topY = new int[COLUMNS * 2];
        int[] bottomY = new int[COLUMNS * 2];
        int[] argb = new int[COLUMNS * 2];
        String[] id = new String[COLUMNS * 2];
        int total;

        /** 按自上而下的顺序喂入一层的可见方块；相邻同方块同色会自动并成一段。 */
        void addLayer(int index, int y, int layerArgb, String layerId) {
            if (runs[index] > 0 && lastY[index] == y + 1 && lastArgb[index] == layerArgb
                && lastId[index] != null && lastId[index].equals(layerId)) {
                bottomY[total - 1] = y;
            } else {
                if (total == topY.length) grow(total + 1);
                topY[total] = y;
                bottomY[total] = y;
                total++;
                runs[index]++;
            }
            argb[total - 1] = layerArgb;
            id[total - 1] = layerId;
            lastArgb[index] = layerArgb;
            lastId[index] = layerId;
            lastY[index] = y;
        }

        private void grow(int needed) {
            int capacity = Math.max(needed, topY.length * 2);
            topY = java.util.Arrays.copyOf(topY, capacity);
            bottomY = java.util.Arrays.copyOf(bottomY, capacity);
            argb = java.util.Arrays.copyOf(argb, capacity);
            id = java.util.Arrays.copyOf(id, capacity);
        }
    }

    /** 把各区块的段表拼成 region 级扁平数组。 */
    static final class Builder {
        private final int size;
        private final int regionSize;
        private final ChunkColumns[][] chunks;
        private final PreviewOptions preview;
        private final String renderKey;

        Builder(int regionSize) {this(regionSize,PreviewOptions.DEFAULT,"synthetic");}
        Builder(int regionSize,PreviewOptions preview,String renderKey) {
            this.preview=preview;this.renderKey=renderKey;
            this.regionSize = regionSize;
            this.size = regionSize * 16;
            this.chunks = new ChunkColumns[regionSize][regionSize];
        }

        void put(int chunkX, int chunkZ, ChunkColumns columns) {
            chunks[chunkZ][chunkX] = columns;
        }

        ColumnCache build() {
            int columns = size * size;
            int[] offsets = new int[columns + 1];
            int total = 0;
            for (int chunkZ = 0; chunkZ < regionSize; chunkZ++) {
                for (int chunkX = 0; chunkX < regionSize; chunkX++) {
                    ChunkColumns chunk = chunks[chunkZ][chunkX];
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            int column = (chunkZ * 16 + localZ) * size + (chunkX * 16 + localX);
                            int count = chunk == null ? 0 : chunk.runs[localZ * 16 + localX];
                            offsets[column + 1] = count;
                            total += count;
                        }
                    }
                }
            }
            for (int column = 0; column < columns; column++) offsets[column + 1] += offsets[column];

            int[] runTopY = new int[total];
            int[] runBottomY = new int[total];
            int[] runArgb = new int[total];
            String[] runId = new String[total];
            java.util.BitSet loaded=new java.util.BitSet(columns);
            for (int chunkZ = 0; chunkZ < regionSize; chunkZ++) {
                for (int chunkX = 0; chunkX < regionSize; chunkX++) {
                    ChunkColumns chunk = chunks[chunkZ][chunkX];
                    if (chunk == null) continue;
                    int source = 0;
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            int local = localZ * 16 + localX;
                            int column = (chunkZ * 16 + localZ) * size + (chunkX * 16 + localX);
                            int target = offsets[column];
                            loaded.set(column);
                            for (int i = 0; i < chunk.runs[local]; i++) {
                                runTopY[target + i] = chunk.topY[source + i];
                                runBottomY[target + i] = chunk.bottomY[source + i];
                                runArgb[target + i] = chunk.argb[source + i];
                                runId[target + i] = chunk.id[source + i];
                            }
                            source += chunk.runs[local];
                        }
                    }
                }
            }
            return new ColumnCache(size, offsets, runTopY, runBottomY, runArgb, runId, loaded, preview, renderKey);
        }
    }
}
