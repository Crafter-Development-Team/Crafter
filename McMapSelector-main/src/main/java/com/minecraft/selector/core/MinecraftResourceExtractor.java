package com.minecraft.selector.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minecraft资源提取器
 * 从Minecraft客户端JAR文件中提取方块纹理和颜色信息
 */
public class MinecraftResourceExtractor {
    
    private static final String ASSETS_PATH = "assets/minecraft/";
    private static final String TEXTURES_PATH = ASSETS_PATH + "textures/block/";
    private static final String BLOCKSTATES_PATH = ASSETS_PATH + "blockstates/";
    private static final String MODELS_PATH = ASSETS_PATH + "models/block/";
    
    private Map<String, Color> extractedColors = new HashMap<>();
    private Map<String, Color> modColors = new HashMap<>(); // 存储mod方块颜色
    private boolean extractionCompleted = false;
    
    /**
     * 从Minecraft JAR文件提取方块颜色
     */
    public boolean extractColorsFromMinecraftJar(String jarPath) {
        System.out.println("正在从Minecraft JAR文件提取方块颜色: " + jarPath);

        try (JarFile jarFile = new JarFile(jarPath)) {
            // 提取方块纹理
            Map<String, BufferedImage> textures = extractBlockTextures(jarFile);
            System.out.println("提取了 " + textures.size() + " 个方块纹理");

            if (textures.isEmpty()) return false;
            Map<String, Color> nextColors = new HashMap<>();
            // 计算平均颜色
            for (Map.Entry<String, BufferedImage> entry : textures.entrySet()) {
                String blockName = entry.getKey();
                BufferedImage texture = entry.getValue();
                Color avgColor = calculateAverageColor(blockName, texture);
                nextColors.put(blockName, avgColor);
            }
            nextColors.putAll(JarModelColors.resolve(jarFile, this::calculateAverageColor));
            extractedColors = nextColors;
            extractionCompleted = true;
            System.out.println("成功提取了 " + extractedColors.size() + " 个方块的颜色信息");
            return true;

        } catch (Exception e) {
            System.err.println("提取方块颜色失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 从mods目录提取方块颜色
     */
    public boolean extractColorsFromModsDirectory(String modsPath) {
        System.out.println("正在从mods目录提取方块颜色: " + modsPath);

        File modsDir = new File(modsPath);
        if (!modsDir.exists() || !modsDir.isDirectory()) {
            System.err.println("mods目录不存在: " + modsPath);
            return false;
        }

        File[] modFiles = modsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (modFiles == null || modFiles.length == 0) {
            System.out.println("在mods目录中没有找到jar文件");
            return false;
        }

        int totalExtracted = 0;
        for (File modFile : modFiles) {
            try {
                int extracted = extractColorsFromModJar(modFile.getAbsolutePath());
                totalExtracted += extracted;
                System.out.println("从 " + modFile.getName() + " 提取了 " + extracted + " 个方块颜色");
            } catch (Exception e) {
                System.err.println("处理mod文件失败: " + modFile.getName() + " - " + e.getMessage());
            }
        }

        System.out.println("从mods目录总共提取了 " + totalExtracted + " 个方块颜色");
        return totalExtracted > 0;
    }

    /**
     * 从单个mod JAR文件提取方块颜色
     */
    private int extractColorsFromModJar(String jarPath) throws Exception {
        try (JarFile jarFile = new JarFile(jarPath)) {
            Map<String, BufferedImage> textures = extractModBlockTextures(jarFile);

            for (Map.Entry<String, BufferedImage> entry : textures.entrySet()) {
                String blockName = entry.getKey();
                BufferedImage texture = entry.getValue();
                Color avgColor = calculateAverageColor(blockName, texture);
                modColors.put(blockName, avgColor);
            }

            Map<String, Color> resolved = JarModelColors.resolve(jarFile, this::calculateAverageColor);
            modColors.putAll(resolved);
            return textures.size() + resolved.size();
        }
    }
    
    /**
     * 从JAR文件中提取方块纹理
     */
    private Map<String, BufferedImage> extractBlockTextures(JarFile jarFile) throws IOException {
        Map<String, BufferedImage> textures = new HashMap<>();
        Map<String, Integer> priorities = new HashMap<>();
        
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();
            
            // 只处理方块纹理文件
            if (entryName.startsWith(TEXTURES_PATH) && entryName.endsWith(".png")) {
                String fileName = entryName.substring(TEXTURES_PATH.length());
                String blockName = fileName.replaceAll("\\.png$", "");
                
                try (InputStream is = jarFile.getInputStream(entry)) {
                    BufferedImage image = ImageIO.read(is);
                    if (image != null) {
                        putTexture(textures, priorities, "", blockName, image);
                    }
                } catch (Exception e) {
                    System.err.println("读取纹理失败: " + entryName + " - " + e.getMessage());
                }
            }
        }
        
        return textures;
    }

    /**
     * 多面纹理（_top/_side/_front 等）归并到基础方块名，
     * 提高JAR颜色对阶梯/台阶/草方块等方块类型的覆盖率
     */
    private static final String[] TEXTURE_VARIANTS = {
        "_top", "_front", "_side", "_back", "_left", "_right", "_bottom",
        "_inner", "_outer", "_still", "_flow", "_overlay"
    };

    private static int variantPriority(String blockName) {
        for (int i = 0; i < TEXTURE_VARIANTS.length; i++) {
            String suffix = TEXTURE_VARIANTS[i];
            if (blockName.endsWith(suffix) && blockName.length() > suffix.length()) {
                return i + 1;
            }
        }
        return 0;
    }

    private static void putTexture(Map<String, BufferedImage> textures, Map<String, Integer> priorities,
                                   String prefix, String blockName, BufferedImage image) {
        int priority = variantPriority(blockName);
        String key = blockName;
        if (priority > 0) {
            key = blockName.substring(0, blockName.length() - TEXTURE_VARIANTS[priority - 1].length());
        }
        key = prefix + key;
        Integer current = priorities.get(key);
        if (current == null || priority < current) {
            priorities.put(key, priority);
            textures.put(key, image);
        }
    }

    /**
     * 从mod JAR文件中提取方块纹理
     */
    private Map<String, BufferedImage> extractModBlockTextures(JarFile jarFile) throws IOException {
        Map<String, BufferedImage> textures = new HashMap<>();
        Map<String, Integer> priorities = new HashMap<>();

        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();

            // mod的纹理路径通常是 assets/modid/textures/block/
            if (entryName.startsWith("assets/") && entryName.contains("/textures/block/") && entryName.endsWith(".png")) {
                // 解析路径: assets/modid/textures/block/blockname.png
                String[] pathParts = entryName.split("/");
                if (pathParts.length >= 5) {
                    String modId = pathParts[1]; // mod的命名空间
                    String fileName = pathParts[pathParts.length - 1]; // 文件名
                    String blockName = fileName.replaceAll("\\.png$", "");

                    // 跳过一些特殊纹理
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        BufferedImage image = ImageIO.read(is);
                        if (image != null) {
                            // 使用命名空间:方块名的格式存储
                            putTexture(textures, priorities, modId + ":", blockName, image);
                        }
                    } catch (Exception e) {
                        System.err.println("读取mod纹理失败: " + entryName + " - " + e.getMessage());
                    }
                }
            }
        }

        return textures;
    }
    
    
    /**
     * 计算图像的平均颜色
     */
    private Color calculateAverageColor(String blockName, BufferedImage image) {
        long totalRed = 0, totalGreen = 0, totalBlue = 0;
        int pixelCount = 0;
        
        int width = image.getWidth();
        int height = image.getHeight();
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int alpha = (rgb >> 24) & 0xFF;
                
                // 跳过透明像素
                if (alpha < 128) {
                    continue;
                }
                
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                
                totalRed += red;
                totalGreen += green;
                totalBlue += blue;
                pixelCount++;
            }
        }
        
        if (pixelCount == 0) {
            return new Color(0, 0, 0, 0); // Entirely transparent textures remain invisible.
        }
        
        int avgRed = (int) (totalRed / pixelCount);
        int avgGreen = (int) (totalGreen / pixelCount);
        int avgBlue = (int) (totalBlue / pixelCount);

        Color originalColor = new Color(avgRed, avgGreen, avgBlue);

        // 应用颜色修正（主要是生物群系着色）
        return applyColorCorrections(blockName, originalColor);
    }
    
    /**
     * 应用生物群系着色和特殊颜色修正
     */
    private Color applyColorCorrections(String blockName, Color originalColor) {
        // 树叶方块需要应用生物群系着色（绿色）
        if (blockName.endsWith("_leaves") && isGrayish(originalColor)) {
            return applyFoliageColor(blockName, originalColor);
        }

        // 草方块/草类植物：纹理是灰底色的需要染绿
        if ((blockName.equals("grass_block") || blockName.equals("grass")
             || blockName.endsWith("_grass") || blockName.equals("fern")
             || blockName.equals("large_fern") || blockName.equals("tall_grass")
             || blockName.equals("bush")) && isGrayish(originalColor)) {
            return applyGrassColor(originalColor);
        }

        // 藤蔓/睡莲：灰底色的需要染绿
        if ((blockName.equals("vine") || blockName.equals("lily_pad")) && isGrayish(originalColor)) {
            return new Color(70, 140, 60);
        }

        // 水：灰度底图（生物群系水色）需要染蓝
        if (blockName.equals("water") && isGrayish(originalColor)) {
            return new Color(63, 118, 228);
        }

        return originalColor;
    }

    /**
     * 判断背景色是否为灰度（需生物群系着色）
     */
    private boolean isGrayish(Color color) {
        int r = color.getRed();
        int g = color.getGreen();
        int b = color.getBlue();

        // 如果RGB值相近且都在灰色范围内，认为是灰色
        int maxDiff = Math.max(Math.max(Math.abs(r - g), Math.abs(g - b)), Math.abs(r - b));
        return maxDiff < 40 && r > 60 && r < 235; // 灰色范围（含高亮灰度底图）
    }

    /**
     * 为树叶应用叶子颜色
     */
    private Color applyFoliageColor(String blockName, Color grayColor) {
        // 根据不同树叶类型应用不同的绿色
        switch (blockName) {
            case "oak_leaves":
                return new Color(42, 132, 39);
            case "spruce_leaves":
                return new Color(23, 89, 44);
            case "birch_leaves":
                return new Color(115, 197, 72);
            case "jungle_leaves":
                return new Color(34, 130, 31);
            case "acacia_leaves":
                return new Color(95, 175, 53);
            case "dark_oak_leaves":
                return new Color(32, 92, 22);
            case "pale_oak_leaves":
                return new Color(85, 140, 75);
            case "azalea_leaves":
                return new Color(60, 150, 55);
            case "flowering_azalea_leaves":
                return new Color(70, 160, 65);
            case "mangrove_leaves":
                return new Color(45, 120, 40);
            case "cherry_leaves":
                return new Color(90, 170, 80);
            default:
                // 默认绿色（温带森林的叶子颜色）
                return new Color(60, 150, 50);
        }
    }

    /**
     * 为草方块应用草地颜色
     */
    private Color applyGrassColor(Color grayColor) {
        return new Color(85, 174, 58); // 温带草地颜色
    }

    /**
     * 获取提取的颜色
     */
    public Color getExtractedColor(String blockName) {
        // 首先尝试从mod颜色中查找（支持命名空间）
        Color modColor = modColors.get(blockName);
        if (modColor != null) {
            return modColor;
        }

        // 然后尝试从vanilla颜色中查找
        Color vanillaColor = extractedColors.get(blockName);
        if (vanillaColor != null) {
            return vanillaColor;
        }

        // 如果blockName包含命名空间，尝试只用方块名查找
        if (blockName.contains(":")) {
            String[] parts = blockName.split(":", 2);
            if (parts.length == 2) {
                String simpleBlockName = parts[1];
                // 先尝试mod颜色
                modColor = modColors.get(simpleBlockName);
                if (modColor != null) {
                    return modColor;
                }
                // 再尝试vanilla颜色
                vanillaColor = extractedColors.get(simpleBlockName);
                if (vanillaColor != null) {
                    return vanillaColor;
                }
            }
        }

        return null;
    }
    
    /**
     * 获取所有提取的颜色
     */
    public Map<String, Color> getAllExtractedColors() {
        Map<String, Color> colors = new HashMap<>(extractedColors);
        colors.putAll(modColors);
        return colors;
    }
    
    /**
     * 检查是否已完成提取
     */
    public boolean isExtractionCompleted() {
        return extractionCompleted;
    }
    
    /**
     * 保存提取的颜色到Properties文件
     */
    public void saveExtractedColors(String filePath) throws IOException {
        Properties props = new Properties();

        for (Map.Entry<String, Color> entry : extractedColors.entrySet()) {
            String blockName = entry.getKey();
            Color color = entry.getValue();
            String colorString = String.format("%d,%d,%d", color.getRed(), color.getGreen(), color.getBlue());
            props.setProperty(blockName, colorString);
        }

        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            props.store(fos, "Extracted block colors from Minecraft JAR");
        }

        System.out.println("颜色信息已保存到: " + filePath);
    }

    /**
     * 保存提取的颜色到JSON文件
     */
    public void saveExtractedColorsAsJson(String filePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        // 添加元数据
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("extractedAt", System.currentTimeMillis());
        metadata.put("totalColors", extractedColors.size());
        metadata.put("version", "1.0");
        root.set("metadata", metadata);

        // 添加颜色数据
        ObjectNode colors = mapper.createObjectNode();
        for (Map.Entry<String, Color> entry : extractedColors.entrySet()) {
            String blockName = entry.getKey();
            Color color = entry.getValue();

            ObjectNode colorNode = mapper.createObjectNode();
            colorNode.put("r", color.getRed());
            colorNode.put("g", color.getGreen());
            colorNode.put("b", color.getBlue());
            colorNode.put("hex", String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue()));

            colors.set(blockName, colorNode);
        }
        root.set("colors", colors);

        // 保存到文件
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(filePath), root);
        System.out.println("颜色信息已保存为JSON: " + filePath);
    }
    
    /**
     * 从Properties文件加载提取的颜色
     */
    public boolean loadExtractedColors(String filePath) {
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(filePath)) {
                props.load(fis);
            }

            extractedColors.clear();
            for (String blockName : props.stringPropertyNames()) {
                String colorString = props.getProperty(blockName);
                String[] rgb = colorString.split(",");
                if (rgb.length == 3) {
                    int r = Integer.parseInt(rgb[0]);
                    int g = Integer.parseInt(rgb[1]);
                    int b = Integer.parseInt(rgb[2]);
                    extractedColors.put(blockName, new Color(r, g, b));
                }
            }

            extractionCompleted = true;
            System.out.println("从Properties文件加载了 " + extractedColors.size() + " 个方块颜色");
            return true;

        } catch (Exception e) {
            System.err.println("加载Properties颜色文件失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 从JSON文件加载提取的颜色
     */
    public boolean loadExtractedColorsFromJson(String filePath) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new File(filePath));

            extractedColors.clear();
            JsonNode colorsNode = root.get("colors");
            if (colorsNode != null) {
                colorsNode.fields().forEachRemaining(entry -> {
                    String blockName = entry.getKey();
                    JsonNode colorNode = entry.getValue();

                    int r = colorNode.get("r").asInt();
                    int g = colorNode.get("g").asInt();
                    int b = colorNode.get("b").asInt();

                    extractedColors.put(blockName, new Color(r, g, b));
                });
            }

            extractionCompleted = true;
            System.out.println("从JSON文件加载了 " + extractedColors.size() + " 个方块颜色");
            return true;

        } catch (Exception e) {
            System.err.println("加载JSON颜色文件失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 从存档路径查找mods目录
     */
    public static String findModsDirectoryFromSavePath(String savePath) {
        try {
            File saveDir = new File(savePath);
            File minecraftDir = saveDir.getParentFile().getParentFile(); // 向上两级目录

            if (minecraftDir != null && minecraftDir.exists()) {
                File modsDir = new File(minecraftDir, "mods");
                if (modsDir.exists() && modsDir.isDirectory()) {
                    System.out.println("找到mods目录: " + modsDir.getAbsolutePath());
                    return modsDir.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            System.err.println("查找mods目录时出错: " + e.getMessage());
        }

        return null;
    }

    /**
     * 从存档路径向上查找Minecraft JAR文件
     */
    public static List<String> findMinecraftJarsFromSavePath(String savePath) {
        List<String> jarPaths = new ArrayList<>();

        try {
            File saveDir = new File(savePath);
            File minecraftDir = saveDir.getParentFile().getParentFile(); // 向上两级目录

            if (minecraftDir != null && minecraftDir.exists()) {
                System.out.println("从存档路径查找JAR文件，Minecraft目录: " + minecraftDir.getAbsolutePath());

                // 1. 首先在Minecraft根目录下查找JAR文件
                File[] rootJarFiles = minecraftDir.listFiles((dir, name) ->
                    name.toLowerCase().endsWith(".jar") &&
                    (name.toLowerCase().contains("minecraft") ||
                     name.toLowerCase().contains("fabric") ||
                     name.toLowerCase().contains("forge") ||
                     name.toLowerCase().contains("optimized") ||
                     name.toLowerCase().contains("client")));

                if (rootJarFiles != null) {
                    for (File jarFile : rootJarFiles) {
                        jarPaths.add(jarFile.getAbsolutePath());
                        System.out.println("找到JAR文件: " + jarFile.getAbsolutePath());
                    }
                }

                // 2. 然后在versions目录下查找
                File versionsDir = new File(minecraftDir, "versions");
                if (versionsDir.exists() && versionsDir.isDirectory()) {
                    System.out.println("找到versions目录: " + versionsDir.getAbsolutePath());

                    File[] versionDirs = versionsDir.listFiles(File::isDirectory);
                    if (versionDirs != null) {
                        for (File versionDir : versionDirs) {
                            File jarFile = new File(versionDir, versionDir.getName() + ".jar");
                            if (jarFile.exists()) {
                                jarPaths.add(jarFile.getAbsolutePath());
                                System.out.println("找到JAR文件: " + jarFile.getAbsolutePath());
                            }
                        }
                    }
                }

                // 3. 如果还没找到，递归搜索子目录中的JAR文件
                if (jarPaths.isEmpty()) {
                    System.out.println("在根目录和versions目录未找到JAR文件，开始递归搜索...");
                    searchJarFilesRecursively(minecraftDir, jarPaths, 2); // 最多搜索2层深度
                }
            }
        } catch (Exception e) {
            System.err.println("从存档路径查找JAR文件时出错: " + e.getMessage());
        }

        return jarPaths;
    }

    /**
     * 递归搜索JAR文件
     */
    private static void searchJarFilesRecursively(File dir, List<String> jarPaths, int maxDepth) {
        if (maxDepth <= 0 || !dir.isDirectory()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
                String fileName = file.getName().toLowerCase();
                // 检查是否可能是Minecraft相关的JAR文件
                if (fileName.contains("minecraft") ||
                    fileName.contains("fabric") ||
                    fileName.contains("forge") ||
                    fileName.contains("optimized") ||
                    fileName.contains("client") ||
                    fileName.matches(".*\\d+\\.\\d+.*\\.jar")) { // 包含版本号的JAR

                    jarPaths.add(file.getAbsolutePath());
                    System.out.println("递归找到JAR文件: " + file.getAbsolutePath());
                }
            } else if (file.isDirectory() && !file.getName().startsWith(".")) {
                // 递归搜索子目录
                searchJarFilesRecursively(file, jarPaths, maxDepth - 1);
            }
        }
    }

    /**
     * 查找Minecraft客户端JAR文件（通用方法）
     */
    public static List<String> findMinecraftJars() {
        List<String> jarPaths = new ArrayList<>();

        // 常见的Minecraft安装路径
        String[] possiblePaths = {
            System.getProperty("user.home") + "/.minecraft/versions",
            System.getenv("APPDATA") + "\\.minecraft\\versions", // Windows
            System.getProperty("user.home") + "/Library/Application Support/minecraft/versions", // macOS
            "/Applications/Minecraft.app/Contents/Resources/versions" // macOS App Store版本
        };

        for (String basePath : possiblePaths) {
            if (basePath == null) continue;

            File baseDir = new File(basePath);
            if (baseDir.exists() && baseDir.isDirectory()) {
                File[] versionDirs = baseDir.listFiles(File::isDirectory);
                if (versionDirs != null) {
                    for (File versionDir : versionDirs) {
                        File jarFile = new File(versionDir, versionDir.getName() + ".jar");
                        if (jarFile.exists()) {
                            jarPaths.add(jarFile.getAbsolutePath());
                        }
                    }
                }
            }
        }

        return jarPaths;
    }
}
