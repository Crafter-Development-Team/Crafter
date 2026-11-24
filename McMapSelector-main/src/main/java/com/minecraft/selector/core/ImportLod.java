package com.minecraft.selector.core;

import com.minecraft.selector.gui.SelectionMath.Bounds;
import java.awt.Point;
import java.util.*;

/** WorldImporter import policy. Centers are blocks; ring widths are chunks. */
public final class ImportLod {
    public static final ImportLod DEFAULT = new ImportLod(0, true, 0, 0, 8, 8, 8, 8);
    public final int maxLevel, centerX, centerZ;
    public final boolean autoCenter;
    private final int[] distances;
    private static final List<String> FLAGS = Arrays.asList("--import-lod-max", "--import-lod-d0",
        "--import-lod-d1", "--import-lod-d2", "--import-lod-d3", "--import-lod-auto-center",
        "--import-lod-cx", "--import-lod-cz");

    public ImportLod(int maxLevel, boolean autoCenter, int centerX, int centerZ,
                     int d0, int d1, int d2, int d3) {
        if (maxLevel < 0 || maxLevel > 4) throw new IllegalArgumentException("Import LOD max must be 0..4");
        distances = new int[]{d0, d1, d2, d3};
        for (int d : distances) if (d < 0) throw new IllegalArgumentException("Import LOD distances must be nonnegative");
        this.maxLevel = maxLevel; this.autoCenter = autoCenter;
        this.centerX = centerX; this.centerZ = centerZ;
    }

    public int distance(int level) { return distances[level]; }
    public long radiusChunks(int level) {
        long radius = 0;
        for (int i = 0; i <= level; i++) radius += distances[i];
        return radius;
    }

    /** Match config.cpp: floor blocks to chunks, then midpoint with C++ truncation toward zero. */
    public ImportLod resolved(Bounds selection, WorldAnchors anchors, String dimension) {
        if (!autoCenter) return this;
        Point center;
        if (selection != null) {
            center = new Point((int)(((long)Math.floorDiv(selection.minX,16) + Math.floorDiv(selection.maxX,16))/2)*16,
                               (int)(((long)Math.floorDiv(selection.minZ,16) + Math.floorDiv(selection.maxZ,16))/2)*16);
        } else {
            WorldAnchors.Anchor anchor = anchors.player != null && dimension.equals(anchors.player.dimension)
                ? anchors.player : anchors.spawn != null && dimension.equals(anchors.spawn.dimension) ? anchors.spawn : null;
            center = anchor == null ? new Point(0,0) : anchor.xz;
        }
        return new ImportLod(maxLevel, true, center.x, center.y, distances[0], distances[1], distances[2], distances[3]);
    }

    /**
     * Logical LOD 0..4; mesh sample sizes are 0, 1, 2, 4, 8. Boundaries are inclusive.
     * Matches ChunkLoader.cpp + RegionModelExporter.cpp (when LOD0 distance is 0, LOD0 is
     * disabled and the center chunk is exported as LOD1).
     */
    public int levelAtChunk(int cx, int cz) {
        if (maxLevel == 0) return 0;
        double dx = (long)cx - Math.floorDiv(centerX,16), dz = (long)cz - Math.floorDiv(centerZ,16);
        double dist2 = dx*dx + dz*dz;
        for (int level = 0; level < maxLevel; level++) {
            double radius = radiusChunks(level);
            if (dist2 <= radius*radius) {
                return (level == 0 && distances[0] == 0) ? 1 : level;
            }
        }
        return maxLevel;
    }

    /**
     * Whether {@code level} (0..maxLevel) actually contains any chunk under WorldImporter's rules:
     * <ul>
     *   <li>{@code level == 0}: disabled when {@code distances[0] == 0} (center chunk becomes LOD1).</li>
     *   <li>{@code 1 <= level < maxLevel}: active when {@code distances[level] > 0}, or when
     *       {@code level == 1 && distances[0] == 0} (center chunk promoted from LOD0 to LOD1).</li>
     *   <li>{@code level == maxLevel}: outer unbounded region, always active.</li>
     * </ul>
     */
    public boolean hasChunksAtLevel(int level) {
        if (maxLevel == 0) return level == 0;
        if (level < 0 || level > maxLevel) return false;
        if (level == maxLevel) return true;
        if (level == 0) return distances[0] > 0;
        if (level == 1 && distances[0] == 0) return true;
        return distances[level] > 0;
    }

    /**
     * Whether {@code level} (0..maxLevel-1) has an outer ring boundary separating it from a higher LOD.
     * Prevents zero-width disabled rings from drawing over the inner ring's colour or drawing a
     * phantom 1×1 LOD0 square when LOD0 distance is 0.
     */
    public boolean hasRingBoundary(int level) {
        if (level < 0 || level >= maxLevel) return false;
        if (!hasChunksAtLevel(level)) return false;
        // If a later inner level has zero width, its radius equals ours, so our boundary still
        // separates us from the next active outer level (at least maxLevel > level).
        return true;
    }

    /**
     * 区块对齐：把方块坐标向下取整到所属区块的最小方块坐标。
     * 与 {@link #resolved} 和 ViewOverlays 画圆心的口径一致（都是 floorDiv(...,16)*16）。
     */
    public static int alignCenterToChunk(int block) { return Math.floorDiv(block,16)*16; }

    /**
     * 半径为 {@code radiusChunks} 个区块的阶梯圆，在第 {@code dz} 行（相对圆心的区块行偏移）上的半宽（区块数）。
     *
     * <p>这是 C++ {@code ChunkLoader::CalculateChunkLODs} 的真实判定：
     * {@code dx*dx + dz*dz <= r*r}，dx/dz 都是<b>区块索引</b>差，所以一个区块整个属于某一级，
     * 边界是 16 方块一级的阶梯，而不是光滑圆。
     *
     * @return 该行包含的区块满足 |dx| &lt;= 返回值；该行完全在圆外时返回 -1
     */
    public static int chunkRingHalfWidth(int radiusChunks,int dz) {
        long rem = (long)radiusChunks*radiusChunks - (long)dz*dz;
        if (rem < 0) return -1;
        return (int)Math.floor(Math.sqrt((double)rem));
    }

    /**
     * 拖动画布设定手动中心：其余字段原样保留，autoCenter 强制为 false
     * （否则 {@link #resolved} 会用选区/出生点把拖出来的中心覆盖掉）。
     */
    public ImportLod withManualCenter(int newCenterX, int newCenterZ) {
        return new ImportLod(maxLevel, false,
            alignCenterToChunk(newCenterX), alignCenterToChunk(newCenterZ),
            distances[0], distances[1], distances[2], distances[3]);
    }

    public Map<String,Object> json() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("maxLevel",maxLevel); out.put("autoCenter",autoCenter);
        out.put("centerX",centerX); out.put("centerZ",centerZ);
        for (int i=0;i<4;i++) out.put("distance"+i,distances[i]);
        return out;
    }

    public static boolean isFlag(String flag) { return FLAGS.contains(flag); }
    public static ImportLod parse(String[] args) {
        Map<String,String> values = new HashMap<>();
        for (int i=0;i<args.length;i++) if (isFlag(args[i])) {
            String flag=args[i];
            if (values.containsKey(flag) || i+1>=args.length || args[i+1].startsWith("--"))
                throw new IllegalArgumentException("Missing or duplicate "+flag);
            values.put(flag,args[++i]);
        }
        String auto=values.getOrDefault("--import-lod-auto-center","true");
        if (!auto.equals("true") && !auto.equals("false")) throw new IllegalArgumentException("Import LOD auto center must be true|false");
        try {
            return new ImportLod(Integer.parseInt(values.getOrDefault("--import-lod-max","0")),Boolean.parseBoolean(auto),
                Integer.parseInt(values.getOrDefault("--import-lod-cx","0")),Integer.parseInt(values.getOrDefault("--import-lod-cz","0")),
                Integer.parseInt(values.getOrDefault("--import-lod-d0","8")),Integer.parseInt(values.getOrDefault("--import-lod-d1","8")),
                Integer.parseInt(values.getOrDefault("--import-lod-d2","8")),Integer.parseInt(values.getOrDefault("--import-lod-d3","8")));
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid import LOD integer",e); }
    }

    public static String[] withoutFlags(String[] args) {
        List<String> out = new ArrayList<>();
        for (int i=0;i<args.length;i++) {
            if (isFlag(args[i])) { if (i+1<args.length && !args[i+1].startsWith("--")) i++; }
            else out.add(args[i]);
        }
        return out.toArray(new String[0]);
    }
}
