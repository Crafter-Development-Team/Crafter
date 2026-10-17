package com.minecraft.selector.utils;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Minecraft 存档维度/区域路径解析工具
 *
 * 同时支持 26.1+ 新布局与旧布局:
 * <ul>
 *   <li>新布局 (26.1+): 所有维度统一位于 dimensions/&lt;ns&gt;/&lt;name&gt;/region</li>
 *   <li>旧布局: 主世界在 region/, 下界在 DIM-1/region/, 末地在 DIM1/region/,
 *       老版 Forge 模组维度在 DIM&lt;number&gt;/region/</li>
 * </ul>
 * 解析时新布局优先,旧布局回退,二者均不存在时返回主世界 region 路径(保持旧行为)。
 * 回退与失败可通过 {@link RegionResolve} 观测。
 */
public final class WorldPathResolver {

    private WorldPathResolver() {
    }

    /**
     * Normalize a save/world path for File lookups.
     * Collapses {@code .}/{@code ..}, and lets Java apply the host separator rules
     * so Blender-passed Windows paths with {@code \} still resolve on Windows.
     * Always prefer this (or {@code new File(path)}) over string concatenation.
     */
    public static File normalizeSaveDir(String savePath) {
        if (savePath == null || savePath.isEmpty()) {
            return new File(".");
        }
        return new File(savePath).getAbsoluteFile();
    }

    /** A dimension discovered in a save. */
    public static final class DimensionInfo {
        private final String id;
        private final String displayName;
        private final File regionDir;

        public DimensionInfo(String id, String displayName, File regionDir) {
            this.id = id;
            this.displayName = displayName;
            this.regionDir = regionDir;
        }

        public String getId() { return id; }
        public String getDisplayName() { return displayName; }
        public File getRegionDir() { return regionDir; }

        @Override
        public String toString() { return displayName; }
    }

    /**
     * Result of resolving a dimension's region directory, including tried paths
     * and whether a modern→legacy fallback was used.
     */
    public static final class RegionResolve {
        private final File regionDir;
        private final boolean found;
        private final boolean usedLegacyFallback;
        private final List<String> triedPaths;
        private final String note;

        RegionResolve(File regionDir, boolean found, boolean usedLegacyFallback,
                      List<String> triedPaths, String note) {
            this.regionDir = regionDir;
            this.found = found;
            this.usedLegacyFallback = usedLegacyFallback;
            this.triedPaths = Collections.unmodifiableList(new ArrayList<String>(triedPaths));
            this.note = note;
        }

        public File getRegionDir() { return regionDir; }
        /** True when the returned directory exists on disk. */
        public boolean isFound() { return found; }
        /** True when 26.1+ path was missing and an existing legacy path was used. */
        public boolean usedLegacyFallback() { return usedLegacyFallback; }
        public List<String> getTriedPaths() { return triedPaths; }
        /** Human-readable note (fallback or missing); may be null when modern path hit. */
        public String getNote() { return note; }

        /** User-facing message when region could not be found. */
        public String missingMessage(String dimensionId) {
            StringBuilder sb = new StringBuilder();
            sb.append("找不到 region（含 26.1+ dimensions/.../region）");
            if (dimensionId != null) sb.append(" [").append(dimensionId).append(']');
            sb.append(". Tried: ");
            for (int i = 0; i < triedPaths.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(triedPaths.get(i));
            }
            return sb.toString();
        }
    }

    /**
     * Discover every dimension that has a region directory in this save.
     * Supports vanilla legacy folders, Forge DIM&lt;number&gt;, and modern
     * dimensions/&lt;namespace&gt;/&lt;path&gt;/region layouts.
     */
    public static List<DimensionInfo> discoverDimensions(String savePath) {
        List<DimensionInfo> result = new ArrayList<>();
        File saveDir = normalizeSaveDir(savePath);

        addIfRegionDir(result, "minecraft:overworld", "Overworld", new File(saveDir, "region"));
        addIfRegionDir(result, "minecraft:the_nether", "Nether", new File(saveDir, "DIM-1/region"));
        addIfRegionDir(result, "minecraft:the_end", "The End", new File(saveDir, "DIM1/region"));

        // Legacy Forge dimensions (excluding vanilla DIM-1 and DIM1 already added).
        File[] roots = saveDir.listFiles();
        if (roots != null) {
            for (File root : roots) {
                String name = root.getName();
                if (!root.isDirectory() || !name.matches("DIM-?\\d+") || "DIM-1".equals(name) || "DIM1".equals(name)) continue;
                addIfRegionDir(result, "legacy:" + name.toLowerCase(), name, new File(root, "region"));
            }
        }

        // Modern vanilla/mod dimensions. The dimension path may contain subfolders,
        // so recursively locate directories named region.
        File dimensionsRoot = new File(saveDir, "dimensions");
        scanModernDimensions(dimensionsRoot, dimensionsRoot, result);

        Collections.sort(result, Comparator.comparing(DimensionInfo::getDisplayName));
        // Keep vanilla dimensions first in a predictable order.
        moveToFront(result, "minecraft:the_end");
        moveToFront(result, "minecraft:the_nether");
        moveToFront(result, "minecraft:overworld");
        return result;
    }

    private static void scanModernDimensions(File root, File current, List<DimensionInfo> result) {
        if (!current.isDirectory()) return;
        if ("region".equals(current.getName()) && current.getParentFile() != null) {
            // Path.relativize is reliable on Windows; URI.relativize can break when
            // drive-letter case differs (C: vs c:) and then yields absolute /C:/... parts.
            Path rootPath = root.toPath().toAbsolutePath().normalize();
            Path dimPath = current.getParentFile().toPath().toAbsolutePath().normalize();
            if (!dimPath.startsWith(rootPath)) return;
            Path relative = rootPath.relativize(dimPath);
            int n = relative.getNameCount();
            if (n >= 2) {
                String namespace = relative.getName(0).toString();
                if (namespace.isEmpty()) return;
                StringBuilder path = new StringBuilder(relative.getName(1).toString());
                for (int i = 2; i < n; i++) {
                    String part = relative.getName(i).toString();
                    if (part.isEmpty()) continue;
                    path.append('/').append(part);
                }
                String id = namespace + ":" + path;
                String name = "minecraft:overworld".equals(id) ? "Overworld" :
                              "minecraft:the_nether".equals(id) ? "Nether" :
                              "minecraft:the_end".equals(id) ? "The End" : id;
                addIfRegionDir(result, id, name, current);
            }
            return;
        }
        File[] children = current.listFiles(File::isDirectory);
        if (children != null) for (File child : children) scanModernDimensions(root, child, result);
    }

    private static void addIfRegionDir(List<DimensionInfo> result, String id, String name, File dir) {
        if (!dir.isDirectory()) return;
        // 0-byte .mca is normal (chunks not generated yet); presence of the filename counts.
        File[] regions = dir.listFiles((d, fileName) -> fileName.endsWith(".mca"));
        if (regions == null || regions.length == 0) return;
        for (DimensionInfo existing : result) {
            if (existing.getId().equals(id) || existing.getRegionDir().equals(dir)) return;
        }
        result.add(new DimensionInfo(id, name, dir));
    }

    private static void moveToFront(List<DimensionInfo> dimensions, String id) {
        for (int i = 0; i < dimensions.size(); i++) {
            if (dimensions.get(i).getId().equals(id)) {
                dimensions.add(0, dimensions.remove(i));
                return;
            }
        }
    }

    /**
     * 解析指定存档的主世界 region 目录绝对路径。
     * 该项目当前仅读取主世界,因此这是最常用的入口。
     *
     * @param savePath 存档根目录(world 文件夹)
     * @return region 目录的 File;若新旧布局都不存在,返回主世界旧布局路径(可能不存在)
     */
    public static File getOverworldRegionDir(String savePath) {
        return getRegionDir(savePath, "minecraft:overworld");
    }

    /**
     * 解析指定存档与维度ID的 region 目录绝对路径。
     *
     * @param savePath       存档根目录
     * @param dimensionId    维度ID,如 minecraft:overworld / minecraft:the_nether / minecraft:the_end / modid:custom
     * @return region 目录的 File;若新旧布局都不存在,返回主世界旧布局路径(可能不存在)
     */
    public static File getRegionDir(String savePath, String dimensionId) {
        return resolveRegionDir(savePath, dimensionId).getRegionDir();
    }

    /**
     * Resolve region dir with observability: tried paths, modern→legacy fallback note.
     */
    public static RegionResolve resolveRegionDir(String savePath, String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            dimensionId = "minecraft:overworld";
        }
        File saveDir = normalizeSaveDir(savePath);
        String namespace;
        String dimName;
        int colon = dimensionId.indexOf(':');
        if (colon < 0) {
            namespace = "minecraft";
            dimName = dimensionId;
        } else {
            namespace = dimensionId.substring(0, colon);
            dimName = dimensionId.substring(colon + 1);
        }

        List<String> tried = new ArrayList<>();
        // Child uses '/', which File accepts on Windows and Unix (do not use '\\').
        File newLayout = new File(saveDir,
                "dimensions/" + namespace + "/" + dimName + "/region");
        tried.add(newLayout.getAbsolutePath());
        if (newLayout.exists() && newLayout.isDirectory()) {
            return new RegionResolve(newLayout, true, false, tried, null);
        }

        File legacy;
        if ("minecraft:overworld".equals(dimensionId) || "overworld".equals(dimensionId)) {
            legacy = new File(saveDir, "region");
        } else if ("minecraft:the_nether".equals(dimensionId) || "the_nether".equals(dimensionId)) {
            legacy = new File(saveDir, "DIM-1/region");
        } else if ("minecraft:the_end".equals(dimensionId) || "the_end".equals(dimensionId)) {
            legacy = new File(saveDir, "DIM1/region");
        } else {
            File found = findLegacyDimRegion(savePath);
            if (found != null) {
                tried.add(found.getAbsolutePath());
                legacy = found;
            } else {
                legacy = new File(saveDir,
                        "dimensions/" + namespace + "/" + dimName + "/region");
            }
        }
        String legacyPath = legacy.getAbsolutePath();
        if (!tried.contains(legacyPath)) tried.add(legacyPath);

        if (legacy.exists() && legacy.isDirectory()) {
            String note = "26.1+ dimensions 路径不存在，已回退到 legacy "
                    + relativeHint(savePath, legacy) + "/";
            return new RegionResolve(legacy, true, true, tried, note);
        }
        String note = "找不到 region（含 26.1+ dimensions/.../region）[" + dimensionId + "]. Tried: "
                + joinTried(tried);
        return new RegionResolve(legacy, false, false, tried, note);
    }

    private static String relativeHint(String savePath, File regionDir) {
        String abs = regionDir.getAbsolutePath().replace('\\', '/');
        String root = normalizeSaveDir(savePath).getAbsolutePath().replace('\\', '/');
        if (abs.startsWith(root)) {
            String rel = abs.substring(root.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            return rel.isEmpty() ? regionDir.getName() : rel;
        }
        return regionDir.getName();
    }

    private static String joinTried(List<String> tried) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tried.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(tried.get(i));
        }
        return sb.toString();
    }

    /**
     * 构造指定区域坐标的 .mca 文件路径(主世界)。
     * 等价于旧代码 new File(savePath, "region/r.x.z.mca"),但兼容新布局。
     */
    public static File getMcaFile(String savePath, int regionX, int regionZ) {
        File regionDir = getOverworldRegionDir(savePath);
        return new File(regionDir, String.format("r.%d.%d.mca", regionX, regionZ));
    }

    /**
     * 检查给定存档是否为有效的 Minecraft 存档(任意布局)。
     * level.dat 仍需位于存档根目录;region 目录在新旧布局之一存在即可。
     */
    public static boolean isValidMinecraftSave(String savePath) {
        return explainInvalidSave(savePath) == null;
    }

    /**
     * @return null if valid; otherwise a short bilingual explanation for the user/CLI.
     */
    public static String explainInvalidSave(String savePath) {
        if (savePath == null || savePath.isEmpty()) {
            return "存档路径为空 / Save path is empty";
        }
        File saveDir = normalizeSaveDir(savePath);
        if (!saveDir.exists()) {
            return "存档路径不存在 / Save path does not exist: " + saveDir.getAbsolutePath();
        }
        if (!saveDir.isDirectory()) {
            return "存档路径不是目录 / Not a directory: " + saveDir.getAbsolutePath();
        }
        File levelDat = new File(saveDir, "level.dat");
        if (!levelDat.exists()) {
            return "缺少 level.dat / Missing level.dat: " + levelDat.getAbsolutePath();
        }
        if (!levelDat.canRead()) {
            return "无法读取 level.dat（权限）/ Cannot read level.dat (permission): " + levelDat.getAbsolutePath();
        }
        // 26.1+ may have no top-level region/; any dimension with .mca counts.
        if (!discoverDimensions(savePath).isEmpty()) {
            return null;
        }
        RegionResolve resolve = resolveRegionDir(savePath, "minecraft:overworld");
        if (resolve.isFound()) {
            // 有任意 .mca 文件名即有效（含 0 字节未生成占位）；仅当目录里完全没有 .mca 才报错。
            if (hasMcaFiles(resolve.getRegionDir())) return null;
            return "region 目录中没有 .mca 文件 / No .mca files in region: "
                    + resolve.getRegionDir().getAbsolutePath();
        }
        return resolve.missingMessage("minecraft:overworld");
    }

    /**
     * True if the directory contains at least one file whose name ends with .mca
     * (including 0-byte placeholders for ungenerated chunks).
     */
    public static boolean hasMcaFiles(File regionDir) {
        if (regionDir == null || !regionDir.isDirectory()) return false;
        File[] files = regionDir.listFiles((d, name) -> name.endsWith(".mca"));
        return files != null && files.length > 0;
    }

    /**
     * Pick first non-empty .mca under a resolved region dir (for resource probing).
     * 0-byte placeholders are skipped here because they have no chunk data to probe.
     */
    public static File findFirstMca(File regionDir) {
        if (regionDir == null || !regionDir.isDirectory()) return null;
        File[] files = regionDir.listFiles((d, name) -> name.endsWith(".mca") && new File(d, name).length() > 0);
        if (files == null || files.length == 0) return null;
        java.util.Arrays.sort(files);
        return files[0];
    }

    /**
     * 在存档根目录下扫描 DIM&lt;number&gt;/region 形式的目录。
     * 用于支持 pre-1.16 Forge 模组维度的遗留布局。
     *
     * @return 第一个匹配的 region 目录,没有则返回 null
     */
    private static File findLegacyDimRegion(String savePath) {
        File saveDir = normalizeSaveDir(savePath);
        File[] children = saveDir.listFiles();
        if (children == null) {
            return null;
        }
        for (File entry : children) {
            if (!entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            // 匹配 DIM 后跟数字(可带负号),如 DIM2、DIM-1、DIM7
            if (!name.startsWith("DIM") || name.length() <= 3) {
                continue;
            }
            boolean numeric = true;
            for (int i = 3; i < name.length(); i++) {
                char c = name.charAt(i);
                if (!(c == '-' || (c >= '0' && c <= '9'))) {
                    numeric = false;
                    break;
                }
            }
            if (!numeric) {
                continue;
            }
            File regionDir = new File(entry, "region");
            if (regionDir.exists() && regionDir.isDirectory()) {
                return regionDir;
            }
        }
        return null;
    }
}
