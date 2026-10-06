package com.minecraft.selector.utils;

import java.io.File;
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
 */
public final class WorldPathResolver {

    private WorldPathResolver() {
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
     * Discover every dimension that has a region directory in this save.
     * Supports vanilla legacy folders, Forge DIM<number>, and modern
     * dimensions/<namespace>/<path>/region layouts.
     */
    public static List<DimensionInfo> discoverDimensions(String savePath) {
        List<DimensionInfo> result = new ArrayList<>();
        File saveDir = new File(savePath);

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
            String relative = root.toURI().relativize(current.getParentFile().toURI()).getPath();
            String[] parts = relative.replace('\\', '/').split("/");
            if (parts.length >= 2) {
                String namespace = parts[0];
                StringBuilder path = new StringBuilder(parts[1]);
                for (int i = 2; i < parts.length; i++) path.append('/').append(parts[i]);
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
        File[] regions = dir.listFiles((d, fileName) -> fileName.endsWith(".mca") && new File(d, fileName).length() > 0);
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
        // 拆分命名空间与维度名
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

        // 候选路径:新布局优先
        File newLayout = new File(savePath,
                "dimensions/" + namespace + "/" + dimName + "/region");
        if (newLayout.exists() && newLayout.isDirectory()) {
            return newLayout;
        }

        // 旧布局:原版维度各自约定
        File legacy;
        if ("minecraft:overworld".equals(dimensionId)) {
            legacy = new File(savePath, "region");
        } else if ("minecraft:the_nether".equals(dimensionId)) {
            legacy = new File(savePath, "DIM-1/region");
        } else if ("minecraft:the_end".equals(dimensionId)) {
            legacy = new File(savePath, "DIM1/region");
        } else {
            // 模组/自定义维度:扫描 DIM<number>/region (老版 Forge 遗留布局)
            File found = findLegacyDimRegion(savePath);
            legacy = (found != null) ? found : new File(savePath,
                    "dimensions/" + namespace + "/" + dimName + "/region");
        }
        return legacy;
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
        File saveDir = new File(savePath);
        File levelDat = new File(saveDir, "level.dat");
        if (!levelDat.exists()) {
            return false;
        }
        File regionDir = getOverworldRegionDir(savePath);
        return regionDir.exists() && regionDir.isDirectory();
    }

    /**
     * 在存档根目录下扫描 DIM<number>/region 形式的目录。
     * 用于支持 pre-1.16 Forge 模组维度的遗留布局。
     *
     * @return 第一个匹配的 region 目录,没有则返回 null
     */
    private static File findLegacyDimRegion(String savePath) {
        File saveDir = new File(savePath);
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
