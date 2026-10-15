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

    /** Logical LOD 0..4; mesh sample sizes are 0, 1, 2, 4, 8. Boundaries are inclusive. */
    public int levelAtChunk(int cx, int cz) {
        double dx = (long)cx - Math.floorDiv(centerX,16), dz = (long)cz - Math.floorDiv(centerZ,16);
        double dist2 = dx*dx + dz*dz;
        for (int level = 0; level < maxLevel; level++) {
            double radius = radiusChunks(level);
            if (dist2 <= radius*radius) return level;
        }
        return maxLevel;
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
