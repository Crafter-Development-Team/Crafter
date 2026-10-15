package com.minecraft.selector.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minecraft.selector.gui.SelectionMath;
import com.minecraft.selector.gui.SelectionShape;
import com.minecraft.selector.nbt.NBTReader.NBTCompound;
import com.minecraft.selector.region.Block;
import com.minecraft.selector.region.Chunk;
import com.minecraft.selector.region.Region;
import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User-defined ID predicates and an interruptible palette-first volume scan. */
public final class OverlayScan {
    private final List<Pattern> patterns = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();

    public static OverlayScan load(File file) throws IOException {
        if (!file.isFile() || file.length() > 65536)
            throw new IOException("Scan config missing or exceeds 64 KiB");
        try {
            JsonNode n = new ObjectMapper().readTree(file);
            if (n == null || !n.isObject()) throw new IllegalArgumentException("Expected config object");
            OverlayScan scan = new OverlayScan();
            for (String key : new String[]{"regex", "ids"}) {
                if (!n.has(key)) continue;
                JsonNode a = n.get(key);
                if (!a.isArray() || a.size() > 128) throw new IllegalArgumentException("Invalid " + key + " list");
                for (JsonNode v : a) {
                    if (!v.isTextual() || v.asText().length() > 256)
                        throw new IllegalArgumentException("Invalid matcher");
                    if (key.equals("regex")) scan.patterns.add(Pattern.compile(v.asText()));
                    else scan.ids.add(v.asText());
                }
            }
            if (scan.patterns.isEmpty() && scan.ids.isEmpty())
                throw new IllegalArgumentException("Specify regex and/or ids");
            return scan;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid scan config: " + e.getMessage(), e);
        }
    }

    public boolean matches(String id) {
        String full = id.contains(":") ? id : "minecraft:" + id;
        if (ids.contains(full)) return true;
        for (Pattern p : patterns) if (p.matcher(full).find()) return true;
        return false;
    }

    public long count(Chunk chunk, int minY, int maxY, BooleanSupplier cancelled) {
        long count = 0;
        for (Chunk.Section section : chunk.getSections()) {
            check(cancelled);
            long base = (long) section.getY() * 16;
            if (base > maxY || base + 15 < minY) continue;
            Map<String, Boolean> matching = new HashMap<>();
            boolean candidate = false;
            for (Block block : section.getPalette()) {
                check(cancelled);
                boolean match = matches(block.getId());
                matching.put(block.getId(), match);
                candidate |= match;
            }
            if (!candidate) continue;
            int lo = (int) Math.max(0, (long) minY - base);
            int hi = (int) Math.min(15, (long) maxY - base);
            for (int y = lo; y <= hi; y++) {
                check(cancelled);
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                    if (Boolean.TRUE.equals(matching.get(section.getBlock(x, y, z).getId()))) count++;
            }
        }
        return count;
    }

    public static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw new CancellationException("Scan cancelled");
    }

    public static final class Result {
        public final Map<Point, Long> counts = new LinkedHashMap<>();
        public final SelectionShape suggested = new SelectionShape();
        public int scanned, failed;
    }

    public static Result scan(File[] files, OverlayScan ore, ChunkFilter filter, int minY, int maxY,
                              BooleanSupplier cancelled) throws IOException {
        return scan(files, ore, filter, minY, maxY, cancelled, null);
    }

    /** Scope intersects whole chunks; the result retains exact selected chunk coordinates. */
    public static Result scan(File[] files, OverlayScan ore, ChunkFilter filter, int minY, int maxY,
                              BooleanSupplier cancelled, SelectionMath.Bounds scope) throws IOException {
        if (minY > maxY) throw new IOException("Invalid scan Y range");
        if (files == null) throw new IOException("No region files");
        File[] sorted = files.clone();
        Arrays.sort(sorted, Comparator.comparing(File::getName));
        Result result = new Result();
        Pattern name = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
        for (File file : sorted) {
            check(cancelled);
            Matcher m = name.matcher(file.getName());
            if (!m.matches()) continue;
            int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
            if (scope != null && ((long) rx * 512 > scope.maxX || (long) rz * 512 > scope.maxZ ||
                (long) rx * 512 + 511 < scope.minX || (long) rz * 512 + 511 < scope.minZ)) continue;
            Region region = Region.fromFile(file.toString());
            for (int[] p : region.getChunkCoordinates()) {
                check(cancelled);
                long cx = (long) rx * 32 + p[0], cz = (long) rz * 32 + p[1];
                if (cx < Integer.MIN_VALUE / 16 || cx > Integer.MAX_VALUE / 16 ||
                    cz < Integer.MIN_VALUE / 16 || cz > Integer.MAX_VALUE / 16) {
                    result.failed++;
                    continue;
                }
                Point chunkPoint = new Point((int) cx, (int) cz);
                SelectionMath.Bounds cb = SelectionShape.chunkBounds(chunkPoint);
                if (scope != null && (cb.minX > scope.maxX || cb.minZ > scope.maxZ ||
                    cb.maxX < scope.minX || cb.maxZ < scope.minZ)) continue;
                if (result.scanned >= SelectionShape.MAX_PARTS)
                    throw new IOException("Scan exceeds 65536 chunks; select a smaller scan scope");
                result.scanned++;
                try {
                    NBTCompound n = region.getChunkData(p[0], p[1]);
                    if (n == null) continue;
                    if (filter != null && filter.matches(n)) result.suggested.paint(chunkPoint.x, chunkPoint.y, false);
                    if (ore != null) {
                        long count = ore.count(new Chunk(n), minY, maxY, cancelled);
                        if (count > 0) result.counts.put(chunkPoint, count);
                    }
                } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
                    result.failed++;
                }
            }
        }
        return result;
    }
}
