package com.minecraft.selector.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.io.*;
import java.util.*;

/** Real PNG and model extraction from a merged vanilla/mod/resource-pack asset stack. */
public class MinecraftResourceExtractor {
    private final JarModelColors assets;
    public MinecraftResourceExtractor() {assets=new JarModelColors();}
    private MinecraftResourceExtractor(MinecraftResourceExtractor original) {
        assets=original.assets.copy();imported.putAll(original.imported);completed=original.completed;
    }
    MinecraftResourceExtractor snapshot() {return new MinecraftResourceExtractor(this);}
    private final Map<String,Color> imported = new HashMap<>();
    private boolean completed;
    public void addArchive(String path) throws IOException { assets.addArchive(path); completed=true; }
    public interface ProgressListener {void onProgress(int completed,int total,String message);}
    /** Decode independently; merge in original override order, never completion order. */
    public void addArchives(List<String> paths,ProgressListener progress) throws IOException {
        if(paths.isEmpty()) {progress.onProgress(0,0,"No resource archives found");return;}
        java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(
            Math.min(paths.size(),Math.min(4,Math.max(1,Runtime.getRuntime().availableProcessors()))));
        java.util.concurrent.CompletionService<Map.Entry<Integer,ResourceArchiveCache.Assets>> done=
            new java.util.concurrent.ExecutorCompletionService<>(workers);
        ResourceArchiveCache.Assets[] ordered=new ResourceArchiveCache.Assets[paths.size()];
        try {
            for(int i=0;i<paths.size();i++) {
                final int index=i;
                done.submit(() -> new AbstractMap.SimpleImmutableEntry<>(index,
                    ResourceArchiveCache.assets(ResourceArchiveCache.index(paths.get(index)))));
            }
            for(int i=1;i<=paths.size();i++) {
                Map.Entry<Integer,ResourceArchiveCache.Assets> result=done.take().get();
                ordered[result.getKey()]=result.getValue();
                String message="Extracted resources "+i+"/"+paths.size()+": "+new File(paths.get(result.getKey())).getName();
                com.minecraft.selector.utils.LogManager.getInstance().info(message,"Resources");
                progress.onProgress(i,paths.size(),message);
            }
            for(ResourceArchiveCache.Assets archive:ordered) assets.merge(archive);
            completed=true;
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException("Resource loading interrupted",e);}
        catch(java.util.concurrent.ExecutionException e) {throw new IOException("Resource extraction failed",e.getCause());}
        finally {workers.shutdownNow();}
    }
    public boolean extractColorsFromMinecraftJar(String path) {
        try { addArchive(path); return true; }
        catch(IOException e) { System.err.println("Cannot load resources: " + path + ": " + e.getMessage()); return false; }
    }
    public boolean extractColorsFromModsDirectory(String path) {
        File[] files=new File(path).listFiles((dir,name)->name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if(files==null) return false;
        Arrays.sort(files); boolean success=false;
        for(File file:files) success=extractColorsFromMinecraftJar(file.getAbsolutePath()) || success;
        return success;
    }
    JarModelColors.Surface surface(String id,Map<String,String> props) {
        Color c=imported.get(id);
        return c==null?assets.resolve(id,props):new JarModelColors.Surface(c,"imported");
    }
    public Color getExtractedColor(String id) {
        JarModelColors.Surface result=surface(id,Collections.emptyMap());return result==null?null:result.color;
    }
    public Map<String,Color> getAllExtractedColors() {
        Map<String,Color> result=new TreeMap<>(imported);
        for(String id:assets.blockIds()) {
            Color c=getExtractedColor(id);if(c!=null) result.put(id,c);
        }
        return result;
    }
    public boolean isExtractionCompleted() {return completed;}
    // These exports are for inspection only. GUI never silently loads unscoped old exports.
    public void saveExtractedColors(String path) throws IOException {
        Properties props=new Properties();getAllExtractedColors().forEach((id,c)->props.setProperty(id,c.getRed()+","+c.getGreen()+","+c.getBlue()+","+c.getAlpha()));
        try(OutputStream out=new FileOutputStream(path)) {props.store(out,"Resource colors (RGBA)");}
    }
    public void saveExtractedColorsAsJson(String path) throws IOException {
        Map<String,Object> root=new TreeMap<>(), colors=new TreeMap<>();
        getAllExtractedColors().forEach((id,c)-> {Map<String,Integer> rgb=new TreeMap<>();rgb.put("r",c.getRed());rgb.put("g",c.getGreen());rgb.put("b",c.getBlue());rgb.put("a",c.getAlpha());colors.put(id,rgb);});
        root.put("colors",colors);new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new File(path),root);
    }
    public boolean loadExtractedColors(String path) {
        try(InputStream in=new FileInputStream(path)) {
            Properties props=new Properties();props.load(in);imported.clear();
            for(String id:props.stringPropertyNames()) {String[] c=props.getProperty(id).split(",");imported.put(id,new Color(Integer.parseInt(c[0]),Integer.parseInt(c[1]),Integer.parseInt(c[2]),c.length>3?Integer.parseInt(c[3]):255));}
            completed=true;return true;
        } catch(Exception e) {return false;}
    }
    public boolean loadExtractedColorsFromJson(String path) {
        try {imported.clear();new ObjectMapper().readTree(new File(path)).path("colors").fields().forEachRemaining(e -> {com.fasterxml.jackson.databind.JsonNode c=e.getValue();imported.put(e.getKey(),new Color(c.path("r").asInt(),c.path("g").asInt(),c.path("b").asInt(),c.path("a").asInt(255)));});completed=true;return true;}
        catch(Exception e) {return false;}
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
