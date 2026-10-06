package com.minecraft.selector.core;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块颜色映射类
 * 对应Python代码中的BLOCK_COLORS和get_block_color函数
 */
public class BlockColors {
    
    // 方块类型到颜色的映射 (RGB格式)
    private static final Map<String, Color> BLOCK_COLORS = new HashMap<>();
    
    // Atomically publish an immutable color source with its own cache.
    // In-flight renders retain their palette and cannot repopulate a newer cache.
    public static final class Palette {
        private final Map<String, Color> extractedColors;
        private final Map<String, Color> cache = new ConcurrentHashMap<>();

        private Palette(Map<String, Color> colors) {
            extractedColors = java.util.Collections.unmodifiableMap(new HashMap<>(colors));
        }

        public Color getBlockColor(String blockId) {
            if (blockId == null || blockId.isEmpty()) {
                return new Color(255, 0, 255, 128);
            }
            return cache.computeIfAbsent(blockId, id -> calculateBlockColor(id, extractedColors));
        }
    }

    private static volatile Palette palette = new Palette(java.util.Collections.emptyMap());

    public static Palette snapshot() {
        return palette;
    }
    
    static {
        // 石头类
        BLOCK_COLORS.put("stone", new Color(127, 127, 127));
        BLOCK_COLORS.put("cobblestone", new Color(110, 110, 110));
        BLOCK_COLORS.put("granite", new Color(154, 123, 100));
        BLOCK_COLORS.put("diorite", new Color(207, 207, 207));
        BLOCK_COLORS.put("andesite", new Color(138, 138, 138));
        
        // 矿石类
        BLOCK_COLORS.put("coal_ore", new Color(46, 46, 46));
        BLOCK_COLORS.put("iron_ore", new Color(197, 145, 106));
        BLOCK_COLORS.put("gold_ore", new Color(252, 222, 112));
        BLOCK_COLORS.put("diamond_ore", new Color(93, 236, 245));
        BLOCK_COLORS.put("emerald_ore", new Color(23, 221, 98));
        BLOCK_COLORS.put("lapis_ore", new Color(22, 64, 201));
        BLOCK_COLORS.put("redstone_ore", new Color(255, 0, 0));
        
        // 泥土和草方块
        BLOCK_COLORS.put("dirt", new Color(139, 111, 63));
        BLOCK_COLORS.put("grass_block", new Color(85, 174, 58));
        BLOCK_COLORS.put("podzol", new Color(106, 67, 27));
        BLOCK_COLORS.put("mycelium", new Color(126, 108, 140));
        
        // 沙子和沙砾
        BLOCK_COLORS.put("sand", new Color(219, 207, 142));
        BLOCK_COLORS.put("red_sand", new Color(189, 106, 55));
        BLOCK_COLORS.put("gravel", new Color(150, 141, 125));
        
        // 木头类
        BLOCK_COLORS.put("oak_log", new Color(188, 152, 98));
        BLOCK_COLORS.put("spruce_log", new Color(109, 84, 59));
        BLOCK_COLORS.put("birch_log", new Color(215, 203, 143));
        BLOCK_COLORS.put("jungle_log", new Color(151, 114, 80));
        BLOCK_COLORS.put("acacia_log", new Color(169, 88, 33));
        BLOCK_COLORS.put("dark_oak_log", new Color(76, 51, 25));
        
        // 树叶类
        BLOCK_COLORS.put("oak_leaves", new Color(42, 132, 39));
        BLOCK_COLORS.put("spruce_leaves", new Color(23, 89, 44));
        BLOCK_COLORS.put("birch_leaves", new Color(115, 197, 72));
        BLOCK_COLORS.put("jungle_leaves", new Color(34, 130, 31));
        BLOCK_COLORS.put("acacia_leaves", new Color(95, 175, 53));
        BLOCK_COLORS.put("dark_oak_leaves", new Color(32, 92, 22));
        BLOCK_COLORS.put("pale_oak_leaves", new Color(85, 140, 75)); // 苍白橡木叶 - 浅绿色
        
        // 水和岩浆
        BLOCK_COLORS.put("water", new Color(60, 68, 170));
        BLOCK_COLORS.put("lava", new Color(234, 92, 15));
        
        // 冰和雪
        BLOCK_COLORS.put("ice", new Color(160, 233, 255));
        BLOCK_COLORS.put("snow", new Color(255, 255, 255));
        BLOCK_COLORS.put("snow_block", new Color(243, 244, 251));
        
        // 植物
        BLOCK_COLORS.put("grass", new Color(88, 169, 47));
        BLOCK_COLORS.put("tall_grass", new Color(94, 174, 49));
        BLOCK_COLORS.put("fern", new Color(82, 139, 62));
        BLOCK_COLORS.put("large_fern", new Color(82, 139, 62));
        BLOCK_COLORS.put("dandelion", new Color(255, 236, 79));
        BLOCK_COLORS.put("poppy", new Color(237, 48, 44));
        
        // 其他常见方块
        BLOCK_COLORS.put("bedrock", new Color(10, 10, 10));
        BLOCK_COLORS.put("obsidian", new Color(21, 18, 30));
        BLOCK_COLORS.put("netherrack", new Color(114, 58, 57));
        BLOCK_COLORS.put("soul_sand", new Color(85, 67, 54));
        BLOCK_COLORS.put("glowstone", new Color(254, 217, 63));
        BLOCK_COLORS.put("end_stone", new Color(219, 222, 158));
        
        // 楼梯类 - 使用对应完整方块的颜色
        BLOCK_COLORS.put("oak_stairs", new Color(162, 130, 78));
        BLOCK_COLORS.put("stone_stairs", new Color(127, 127, 127));
        BLOCK_COLORS.put("cobblestone_stairs", new Color(110, 110, 110));
        BLOCK_COLORS.put("brick_stairs", new Color(150, 97, 83));
        BLOCK_COLORS.put("stone_brick_stairs", new Color(122, 122, 122));
        BLOCK_COLORS.put("nether_brick_stairs", new Color(44, 21, 26));
        BLOCK_COLORS.put("sandstone_stairs", new Color(219, 207, 142));
        BLOCK_COLORS.put("spruce_stairs", new Color(114, 84, 48));
        BLOCK_COLORS.put("birch_stairs", new Color(196, 177, 123));
        BLOCK_COLORS.put("jungle_stairs", new Color(160, 115, 80));
        BLOCK_COLORS.put("acacia_stairs", new Color(168, 90, 50));
        BLOCK_COLORS.put("dark_oak_stairs", new Color(66, 43, 20));
        BLOCK_COLORS.put("quartz_stairs", new Color(235, 229, 222));
        BLOCK_COLORS.put("red_sandstone_stairs", new Color(189, 106, 55));
        BLOCK_COLORS.put("purpur_stairs", new Color(169, 125, 169));

        // 台阶类 - 使用对应完整方块的颜色
        BLOCK_COLORS.put("oak_slab", new Color(162, 130, 78));
        BLOCK_COLORS.put("stone_slab", new Color(127, 127, 127));
        BLOCK_COLORS.put("cobblestone_slab", new Color(110, 110, 110));
        BLOCK_COLORS.put("brick_slab", new Color(150, 97, 83));
        BLOCK_COLORS.put("stone_brick_slab", new Color(122, 122, 122));
        BLOCK_COLORS.put("nether_brick_slab", new Color(44, 21, 26));
        BLOCK_COLORS.put("sandstone_slab", new Color(219, 207, 142));
        BLOCK_COLORS.put("spruce_slab", new Color(114, 84, 48));
        BLOCK_COLORS.put("birch_slab", new Color(196, 177, 123));
        BLOCK_COLORS.put("jungle_slab", new Color(160, 115, 80));
        BLOCK_COLORS.put("acacia_slab", new Color(168, 90, 50));
        BLOCK_COLORS.put("dark_oak_slab", new Color(66, 43, 20));
        BLOCK_COLORS.put("quartz_slab", new Color(235, 229, 222));
        BLOCK_COLORS.put("red_sandstone_slab", new Color(189, 106, 55));
        BLOCK_COLORS.put("purpur_slab", new Color(169, 125, 169));

        // 栅栏类 - 使用对应木材颜色但稍微暗一些
        BLOCK_COLORS.put("oak_fence", new Color(142, 110, 68));
        BLOCK_COLORS.put("spruce_fence", new Color(94, 64, 38));
        BLOCK_COLORS.put("birch_fence", new Color(176, 157, 103));
        BLOCK_COLORS.put("jungle_fence", new Color(140, 95, 60));
        BLOCK_COLORS.put("acacia_fence", new Color(148, 70, 30));
        BLOCK_COLORS.put("dark_oak_fence", new Color(46, 23, 10));
        BLOCK_COLORS.put("nether_brick_fence", new Color(44, 21, 26));

        // 栅栏门类
        BLOCK_COLORS.put("oak_fence_gate", new Color(142, 110, 68));
        BLOCK_COLORS.put("spruce_fence_gate", new Color(94, 64, 38));
        BLOCK_COLORS.put("birch_fence_gate", new Color(176, 157, 103));
        BLOCK_COLORS.put("jungle_fence_gate", new Color(140, 95, 60));
        BLOCK_COLORS.put("acacia_fence_gate", new Color(148, 70, 30));
        BLOCK_COLORS.put("dark_oak_fence_gate", new Color(46, 23, 10));

        // 门类 - 使用对应木材颜色
        BLOCK_COLORS.put("oak_door", new Color(162, 130, 78));
        BLOCK_COLORS.put("spruce_door", new Color(114, 84, 48));
        BLOCK_COLORS.put("birch_door", new Color(196, 177, 123));
        BLOCK_COLORS.put("jungle_door", new Color(160, 115, 80));
        BLOCK_COLORS.put("acacia_door", new Color(168, 90, 50));
        BLOCK_COLORS.put("dark_oak_door", new Color(66, 43, 20));
        BLOCK_COLORS.put("iron_door", new Color(191, 191, 191));

        // 活板门类
        BLOCK_COLORS.put("oak_trapdoor", new Color(162, 130, 78));
        BLOCK_COLORS.put("spruce_trapdoor", new Color(114, 84, 48));
        BLOCK_COLORS.put("birch_trapdoor", new Color(196, 177, 123));
        BLOCK_COLORS.put("jungle_trapdoor", new Color(160, 115, 80));
        BLOCK_COLORS.put("acacia_trapdoor", new Color(168, 90, 50));
        BLOCK_COLORS.put("dark_oak_trapdoor", new Color(66, 43, 20));
        BLOCK_COLORS.put("iron_trapdoor", new Color(191, 191, 191));

        // 容器类
        BLOCK_COLORS.put("chest", new Color(162, 130, 78));
        BLOCK_COLORS.put("trapped_chest", new Color(162, 130, 78));
        BLOCK_COLORS.put("ender_chest", new Color(21, 18, 30));
        BLOCK_COLORS.put("shulker_box", new Color(139, 90, 181));
        BLOCK_COLORS.put("barrel", new Color(114, 84, 48));
        BLOCK_COLORS.put("furnace", new Color(127, 127, 127));
        BLOCK_COLORS.put("blast_furnace", new Color(107, 107, 107));
        BLOCK_COLORS.put("smoker", new Color(97, 97, 97));

        // 工作台类
        BLOCK_COLORS.put("crafting_table", new Color(162, 130, 78));
        BLOCK_COLORS.put("cartography_table", new Color(162, 130, 78));
        BLOCK_COLORS.put("fletching_table", new Color(162, 130, 78));
        BLOCK_COLORS.put("smithing_table", new Color(162, 130, 78));
        BLOCK_COLORS.put("loom", new Color(162, 130, 78));
        BLOCK_COLORS.put("composter", new Color(162, 130, 78));

        // 压力板类
        BLOCK_COLORS.put("oak_pressure_plate", new Color(162, 130, 78));
        BLOCK_COLORS.put("spruce_pressure_plate", new Color(114, 84, 48));
        BLOCK_COLORS.put("birch_pressure_plate", new Color(196, 177, 123));
        BLOCK_COLORS.put("jungle_pressure_plate", new Color(160, 115, 80));
        BLOCK_COLORS.put("acacia_pressure_plate", new Color(168, 90, 50));
        BLOCK_COLORS.put("dark_oak_pressure_plate", new Color(66, 43, 20));
        BLOCK_COLORS.put("stone_pressure_plate", new Color(127, 127, 127));
        BLOCK_COLORS.put("light_weighted_pressure_plate", new Color(255, 215, 0));
        BLOCK_COLORS.put("heavy_weighted_pressure_plate", new Color(191, 191, 191));

        // 按钮类
        BLOCK_COLORS.put("oak_button", new Color(162, 130, 78));
        BLOCK_COLORS.put("spruce_button", new Color(114, 84, 48));
        BLOCK_COLORS.put("birch_button", new Color(196, 177, 123));
        BLOCK_COLORS.put("jungle_button", new Color(160, 115, 80));
        BLOCK_COLORS.put("acacia_button", new Color(168, 90, 50));
        BLOCK_COLORS.put("dark_oak_button", new Color(66, 43, 20));
        BLOCK_COLORS.put("stone_button", new Color(127, 127, 127));

        // 红石相关
        BLOCK_COLORS.put("redstone_wire", new Color(255, 0, 0));
        BLOCK_COLORS.put("redstone_torch", new Color(255, 0, 0));
        BLOCK_COLORS.put("redstone_block", new Color(255, 0, 0));
        BLOCK_COLORS.put("repeater", new Color(127, 127, 127));
        BLOCK_COLORS.put("comparator", new Color(127, 127, 127));
        BLOCK_COLORS.put("lever", new Color(127, 127, 127));

        // 花盆和植物
        BLOCK_COLORS.put("flower_pot", new Color(139, 111, 63));
        BLOCK_COLORS.put("potted_oak_sapling", new Color(139, 111, 63));
        BLOCK_COLORS.put("potted_spruce_sapling", new Color(139, 111, 63));
        BLOCK_COLORS.put("potted_birch_sapling", new Color(139, 111, 63));
        BLOCK_COLORS.put("potted_jungle_sapling", new Color(139, 111, 63));
        BLOCK_COLORS.put("potted_acacia_sapling", new Color(139, 111, 63));
        BLOCK_COLORS.put("potted_dark_oak_sapling", new Color(139, 111, 63));

        // 地毯类
        BLOCK_COLORS.put("white_carpet", new Color(249, 255, 254));
        BLOCK_COLORS.put("orange_carpet", new Color(249, 128, 29));
        BLOCK_COLORS.put("magenta_carpet", new Color(199, 78, 189));
        BLOCK_COLORS.put("light_blue_carpet", new Color(58, 179, 218));
        BLOCK_COLORS.put("yellow_carpet", new Color(254, 216, 61));
        BLOCK_COLORS.put("lime_carpet", new Color(128, 199, 31));
        BLOCK_COLORS.put("pink_carpet", new Color(243, 139, 170));
        BLOCK_COLORS.put("gray_carpet", new Color(71, 79, 82));
        BLOCK_COLORS.put("light_gray_carpet", new Color(157, 157, 151));
        BLOCK_COLORS.put("cyan_carpet", new Color(22, 156, 156));
        BLOCK_COLORS.put("purple_carpet", new Color(137, 50, 184));
        BLOCK_COLORS.put("blue_carpet", new Color(60, 68, 170));
        BLOCK_COLORS.put("brown_carpet", new Color(131, 84, 50));
        BLOCK_COLORS.put("green_carpet", new Color(94, 124, 22));
        BLOCK_COLORS.put("red_carpet", new Color(176, 46, 38));
        BLOCK_COLORS.put("black_carpet", new Color(29, 29, 33));

        // 特殊方块 - 使用透明度
        BLOCK_COLORS.put("air", new Color(255, 255, 255, 0));
        BLOCK_COLORS.put("cave_air", new Color(255, 255, 255, 0));
        BLOCK_COLORS.put("void_air", new Color(255, 255, 255, 0));
        BLOCK_COLORS.put("none", new Color(255, 255, 255, 0)); // 完全透明
    }
    
    /**
     * 根据方块ID获取对应的颜色
     */
    public static Color getBlockColor(String blockId) {
        return palette.getBlockColor(blockId);
    }
    
    /**
     * 设置资源提取器
     */
    public static void setResourceExtractor(MinecraftResourceExtractor extractor) {
        palette = new Palette(extractor == null
            ? java.util.Collections.emptyMap() : extractor.getAllExtractedColors());
    }

    /**
     * 从存档路径初始化颜色提取器（包括minecraft和mods）
     */
    public static boolean initializeColorsFromSavePath(String savePath) {
        return initializeColorsFromSavePath(savePath, null);
    }

    public static boolean initializeColorsFromSavePath(String savePath, String preferredJarPath) {
        MinecraftResourceExtractor extractor = new MinecraftResourceExtractor();
        boolean success = false;

        // 1. 尝试加载minecraft颜色
        List<String> jarPaths = MinecraftResourceExtractor.findMinecraftJarsFromSavePath(savePath);
        if (preferredJarPath != null) {
            jarPaths.remove(preferredJarPath);
            jarPaths.add(0, preferredJarPath);
        }
        if (!jarPaths.isEmpty()) {
            for (String jarPath : jarPaths) {
                MinecraftResourceExtractor candidate = new MinecraftResourceExtractor();
                if (candidate.extractColorsFromMinecraftJar(jarPath)) {
                    extractor = candidate;
                    System.out.println("成功从Minecraft JAR加载颜色: " + jarPath);
                    success = true;
                    break; // 只需要一个成功的jar文件
                }
            }
        }

        // 2. 尝试加载mods颜色
        String modsPath = MinecraftResourceExtractor.findModsDirectoryFromSavePath(savePath);
        if (modsPath != null) {
            if (extractor.extractColorsFromModsDirectory(modsPath)) {
                System.out.println("成功从mods目录加载颜色: " + modsPath);
                success = true;
            }
        } else {
            System.out.println("未找到mods目录，跳过mod颜色加载");
        }

        // Also reset to defaults on failure; never retain a previous world's colors.
        setResourceExtractor(success ? extractor : null);
        if (success) {
            System.out.println("颜色提取器初始化完成");
        } else {
            System.out.println("颜色提取器初始化失败，将使用默认颜色");
        }

        return success;
    }

    /**
     * 计算方块颜色
     */
    private static Color calculateBlockColor(String blockId, Map<String, Color> extractedColors) {
        // 如果blockId为null或空字符串，返回紫色标记错误
        if (blockId == null || blockId.isEmpty()) {
            return new Color(255, 0, 255, 128); // 半透明紫色
        }

        String normalized = blockId.startsWith("minecraft:") ? blockId.substring(10) : blockId;
        if (isAirBlock(normalized)) return new Color(0, 0, 0, 0);

        // 只要成功从JAR提取到了颜色，就完全使用JAR颜色（不再混用内置默认色板）
        if (!extractedColors.isEmpty()) {
            Color jarColor = lookupJarColor(blockId, extractedColors);
            if (jarColor != null) {
                return jarColor;
            }
            return randomColor(blockId);
        }

        // ===== 以下为JAR颜色不可用时的默认兜底 =====

        // 从完整方块ID中提取基本名称
        String processedId = blockId;
        if (blockId.contains(":")) {
            String[] parts = blockId.split(":", 2);
            // 如果是minecraft命名空间，只使用名称部分
            if ("minecraft".equals(parts[0])) {
                processedId = parts[1];
            } else {
                // 对于mod方块，保留完整的命名空间
                processedId = blockId;
            }
        }

        // 尝试匹配完整ID
        if (BLOCK_COLORS.containsKey(processedId)) {
            return BLOCK_COLORS.get(processedId);
        }

        // 智能匹配变体方块
        Color variantColor = matchVariantBlock(processedId);
        if (variantColor != null) {
            return variantColor;
        }

        // 尝试匹配部分ID（例如，如果blockId是"spruce_planks"，可以匹配"spruce"）
        for (String key : BLOCK_COLORS.keySet()) {
            if (processedId.contains(key)) {
                return BLOCK_COLORS.get(key);
            }
        }

        return randomColor(blockId);
    }

    /**
     * 从JAR提取的颜色中查找方块颜色（支持命名空间、变体后缀、木质衍生方块）
     */
    static Color lookupJarColor(String blockId, Map<String, Color> colors) {
        Color color = colors.get(blockId);
        String simple = blockId.startsWith("minecraft:")
            ? blockId.substring("minecraft:".length()) : blockId;
        if (color == null) {
            color = colors.get(simple);
        }

        // Cross-version renamed vanilla blocks (old worlds with a newer client JAR).
        if (color == null && !simple.contains(":")) {
            if ("grass".equals(simple)) color = colors.get("short_grass");
            if ("short_grass".equals(simple)) color = colors.get("grass");
            if ("chain".equals(simple)) color = colors.get("iron_chain");
            if ("iron_chain".equals(simple)) color = colors.get("chain");
        }

        // 木质方块：xx_wood 与 xx_log 纹理相同
        if (color == null && simple.endsWith("_wood")) {
            color = colors.get(simple.substring(0, simple.length() - "_wood".length()) + "_log");
        }

        // 阶梯/台阶/栅栏等变体：退回到基础方块的颜色
        if (color == null) {
            for (String suffix : VARIANT_SUFFIXES) {
                if (simple.endsWith(suffix)) {
                    String base = simple.substring(0, simple.length() - suffix.length());
                    color = colors.get(base);
                    if (color == null) color = colors.get(base + "s");
                    if (color == null && VANILLA_WOODS.contains(base)) {
                        color = colors.get(base + "_planks");
                    }
                    break;
                }
            }
        }
        return color;
    }

    private static final Set<String> VANILLA_WOODS = new HashSet<>(Arrays.asList(
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
        "cherry", "pale_oak", "bamboo", "crimson", "warped"));

    private static final String[] VARIANT_SUFFIXES = {
        "_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_trapdoor",
        "_door", "_pressure_plate", "_button", "_hanging_sign", "_sign"};

    /**
     * 未知方块的固定哈希色（保证同一方块颜色稳定）
     */
    private static Color randomColor(String blockId) {
        java.util.Random random = new java.util.Random(blockId.hashCode());
        int r = random.nextInt(200) + 55; // 避免太暗的颜色
        int g = random.nextInt(200) + 55;
        int b = random.nextInt(200) + 55;
        return new Color(r, g, b, 255);
    }

    /**
     * 智能匹配变体方块
     */
    private static Color matchVariantBlock(String blockId) {
        // 处理楼梯变体 - 如果没有直接匹配，尝试匹配基础方块
        if (blockId.endsWith("_stairs")) {
            String baseBlock = blockId.replace("_stairs", "");
            if (BLOCK_COLORS.containsKey(baseBlock)) {
                return BLOCK_COLORS.get(baseBlock);
            }
            // 特殊处理木材楼梯
            if (baseBlock.equals("oak")) return BLOCK_COLORS.get("oak_planks");
            if (baseBlock.equals("spruce")) return BLOCK_COLORS.get("spruce_planks");
            if (baseBlock.equals("birch")) return BLOCK_COLORS.get("birch_planks");
            if (baseBlock.equals("jungle")) return BLOCK_COLORS.get("jungle_planks");
            if (baseBlock.equals("acacia")) return BLOCK_COLORS.get("acacia_planks");
            if (baseBlock.equals("dark_oak")) return BLOCK_COLORS.get("dark_oak_planks");
        }

        // 处理台阶变体
        if (blockId.endsWith("_slab")) {
            String baseBlock = blockId.replace("_slab", "");
            if (BLOCK_COLORS.containsKey(baseBlock)) {
                return BLOCK_COLORS.get(baseBlock);
            }
            // 特殊处理木材台阶
            if (baseBlock.equals("oak")) return BLOCK_COLORS.get("oak_planks");
            if (baseBlock.equals("spruce")) return BLOCK_COLORS.get("spruce_planks");
            if (baseBlock.equals("birch")) return BLOCK_COLORS.get("birch_planks");
            if (baseBlock.equals("jungle")) return BLOCK_COLORS.get("jungle_planks");
            if (baseBlock.equals("acacia")) return BLOCK_COLORS.get("acacia_planks");
            if (baseBlock.equals("dark_oak")) return BLOCK_COLORS.get("dark_oak_planks");
        }

        // 处理栅栏变体
        if (blockId.endsWith("_fence") || blockId.endsWith("_fence_gate")) {
            String baseBlock = blockId.replace("_fence_gate", "").replace("_fence", "");
            if (baseBlock.equals("oak")) return BLOCK_COLORS.get("oak_planks");
            if (baseBlock.equals("spruce")) return BLOCK_COLORS.get("spruce_planks");
            if (baseBlock.equals("birch")) return BLOCK_COLORS.get("birch_planks");
            if (baseBlock.equals("jungle")) return BLOCK_COLORS.get("jungle_planks");
            if (baseBlock.equals("acacia")) return BLOCK_COLORS.get("acacia_planks");
            if (baseBlock.equals("dark_oak")) return BLOCK_COLORS.get("dark_oak_planks");
            if (baseBlock.equals("nether_brick")) return BLOCK_COLORS.get("nether_bricks");
        }

        // 处理门和活板门变体
        if (blockId.endsWith("_door") || blockId.endsWith("_trapdoor")) {
            String baseBlock = blockId.replace("_trapdoor", "").replace("_door", "");
            if (baseBlock.equals("oak")) return BLOCK_COLORS.get("oak_planks");
            if (baseBlock.equals("spruce")) return BLOCK_COLORS.get("spruce_planks");
            if (baseBlock.equals("birch")) return BLOCK_COLORS.get("birch_planks");
            if (baseBlock.equals("jungle")) return BLOCK_COLORS.get("jungle_planks");
            if (baseBlock.equals("acacia")) return BLOCK_COLORS.get("acacia_planks");
            if (baseBlock.equals("dark_oak")) return BLOCK_COLORS.get("dark_oak_planks");
            if (baseBlock.equals("iron")) return new Color(191, 191, 191);
        }

        // 处理压力板和按钮变体
        if (blockId.endsWith("_pressure_plate") || blockId.endsWith("_button")) {
            String baseBlock = blockId.replace("_pressure_plate", "").replace("_button", "");
            if (baseBlock.equals("oak")) return BLOCK_COLORS.get("oak_planks");
            if (baseBlock.equals("spruce")) return BLOCK_COLORS.get("spruce_planks");
            if (baseBlock.equals("birch")) return BLOCK_COLORS.get("birch_planks");
            if (baseBlock.equals("jungle")) return BLOCK_COLORS.get("jungle_planks");
            if (baseBlock.equals("acacia")) return BLOCK_COLORS.get("acacia_planks");
            if (baseBlock.equals("dark_oak")) return BLOCK_COLORS.get("dark_oak_planks");
            if (baseBlock.equals("stone")) return BLOCK_COLORS.get("stone");
        }

        // 处理地毯变体 - 使用对应羊毛颜色
        if (blockId.endsWith("_carpet")) {
            String colorName = blockId.replace("_carpet", "");
            if (BLOCK_COLORS.containsKey(colorName + "_wool")) {
                return BLOCK_COLORS.get(colorName + "_wool");
            }
        }

        return null;
    }

    /**
     * 清除颜色缓存
     */
    public static void clearCache() {
        palette.cache.clear();
    }
    
    /**
     * 获取缓存大小
     */
    public static int getCacheSize() {
        return palette.cache.size();
    }
    
    /**
     * 检查是否为空气方块或透明方块
     */
    public static boolean isAirBlock(String blockId) {
        return "air".equals(blockId) || "cave_air".equals(blockId) || "void_air".equals(blockId) || "none".equals(blockId);
    }
}
