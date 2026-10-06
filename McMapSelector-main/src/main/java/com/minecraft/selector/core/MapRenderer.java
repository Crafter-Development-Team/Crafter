package com.minecraft.selector.core;

import com.minecraft.selector.region.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * 地图渲染器
 * 对应Python代码中的地图渲染功能
 */
public class MapRenderer {

    /**
     * 存储方块信息的内部类，包含方块ID和高度
     */
    public static class BlockInfo {
        private final String blockId;
        private final int height;

        public BlockInfo(String blockId, int height) {
            this.blockId = blockId;
            this.height = height;
        }

        public String getBlockId() {
            return blockId;
        }

        public int getHeight() {
            return height;
        }

        @Override
        public String toString() {
            return blockId + "@" + height;
        }
    }
    
    private final ExecutorService executorService;
    private final int maxWorkers;
    private final ProgressCallback progressCallback;
    
    // 进度跟踪
    private final AtomicInteger processedChunks = new AtomicInteger(0);
    private final AtomicInteger totalChunks = new AtomicInteger(0);
    private final AtomicLong startTime = new AtomicLong(0);
    private final Set<String> foundBlocks = ConcurrentHashMap.newKeySet();
    
    // 性能统计
    private final AtomicLong totalRenderTime = new AtomicLong(0);
    private final AtomicLong totalImageTime = new AtomicLong(0);
    private final AtomicInteger totalRenderedChunks = new AtomicInteger(0);
    
    /**
     * 进度回调接口
     */
    public interface ProgressCallback {
        void onProgress(int processed, int total, double speed, Set<String> foundBlocks);
        void onProgressiveRender(BufferedImage partialImage, int completedChunks, int totalChunks);
    }
    
    public MapRenderer(int maxWorkers, ProgressCallback progressCallback) {
        this.maxWorkers = maxWorkers;
        this.progressCallback = progressCallback;
        this.executorService = Executors.newFixedThreadPool(maxWorkers);
    }

    /**
     * 静态方法：渲染指定区域
     */
    public static BufferedImage renderRegion(String regionPath, int minX, int maxX, int minZ, int maxZ, int lodLevel) {
        try {
            // 创建简单的进度回调
            ProgressCallback callback = new ProgressCallback() {
                @Override
                public void onProgress(int processed, int total, double speed, Set<String> foundBlocks) {
                    System.out.printf("渲染进度: %d/%d (%.1f%%) - 速度: %.1f区块/秒\n",
                        processed, total, (processed * 100.0 / total), speed);
                }
                
                @Override
                public void onProgressiveRender(BufferedImage partialImage, int completedChunks, int totalChunks) {
                    // 静态方法中不需要渐进式渲染回调
                }
            };

            // 创建渲染器
            MapRenderer renderer = new MapRenderer(4, callback);

            // 计算区域大小
            int width = maxX - minX;
            int height = maxZ - minZ;

            // 渲染区域 - 这里需要处理多个MCA文件
            // 简化版本：假设只有一个MCA文件
            File regionDir = new File(regionPath);
            File[] mcaFiles = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));

            if (mcaFiles != null && mcaFiles.length > 0) {
                // 使用第一个MCA文件进行渲染
                String mcaPath = mcaFiles[0].getAbsolutePath();
                BlockInfo[][] topBlocks = renderer.getTopBlocks(mcaPath, Math.max(width/16, height/16), lodLevel);
                if (topBlocks != null) {
                    return renderer.renderToPng(topBlocks, lodLevel);
                }
            }

        } catch (Exception e) {
            System.err.println("渲染区域失败: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }
    
    /**
     * 渲染区域文件为顶部方块数据
     */
    public BlockInfo[][] getTopBlocks(String mcaFilePath, int regionSize, int sampleInterval) throws IOException {
        System.out.println("正在处理区域文件: " + mcaFilePath);
        System.out.println("区域大小: " + regionSize + "x" + regionSize + " 区块");
        System.out.println("采样间隔: " + sampleInterval);
        
        // 重置进度
        processedChunks.set(0);
        foundBlocks.clear();
        startTime.set(System.currentTimeMillis());
        
        // 加载区域文件
        Region region = Region.fromFile(mcaFilePath);
        
        // 获取存在的区块坐标
        List<int[]> populatedChunks = new ArrayList<>();
        for (int x = 0; x < regionSize; x++) {
            for (int z = 0; z < regionSize; z++) {
                if (region.chunkExists(x, z)) {
                    populatedChunks.add(new int[]{x, z});
                }
            }
        }
        
        totalChunks.set(populatedChunks.size());
        System.out.println("发现 " + populatedChunks.size() + " 个有效区块");
        
        if (populatedChunks.isEmpty()) {
            System.out.println("没有找到有效区块");
            return new BlockInfo[regionSize * 16][regionSize * 16];
        }

        // 创建结果数组
        int arraySize = regionSize * 16;
        BlockInfo[][] topBlocks = new BlockInfo[arraySize][arraySize];

        // BlockInfo is immutable: share the empty sentinel instead of allocating
        // 262,144 identical objects for every rendered region.
        BlockInfo empty = new BlockInfo("none", -64);
        for (BlockInfo[] row : topBlocks) Arrays.fill(row, empty);
        
        // 分批处理区块
        int batchSize = Math.max(1, populatedChunks.size() / maxWorkers);
        List<List<int[]>> batches = new ArrayList<>();
        
        for (int i = 0; i < populatedChunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, populatedChunks.size());
            batches.add(populatedChunks.subList(i, end));
        }
        
        System.out.println("使用 " + batches.size() + " 个批次处理");
        
        // 并行处理批次
        List<Future<Map<String, BlockInfo[][]>>> futures = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            final int batchIndex = i;
            final List<int[]> batch = batches.get(i);

            Future<Map<String, BlockInfo[][]>> future = executorService.submit(() ->
                processChunkBatch(region, batch, batchIndex, sampleInterval)
            );
            futures.add(future);
        }

        // 收集结果
        for (Future<Map<String, BlockInfo[][]>> future : futures) {
            try {
                Map<String, BlockInfo[][]> batchResults = future.get();

                // 将批次结果合并到主数组
                for (Map.Entry<String, BlockInfo[][]> entry : batchResults.entrySet()) {
                    String[] coords = entry.getKey().split(",");
                    int chunkX = Integer.parseInt(coords[0]);
                    int chunkZ = Integer.parseInt(coords[1]);
                    BlockInfo[][] chunkBlocks = entry.getValue();

                    if (chunkBlocks != null) {
                        // 将区块数据复制到结果数组
                        int startX = chunkX * 16;
                        int startZ = chunkZ * 16;

                        for (int localZ = 0; localZ < 16; localZ++) {
                            for (int localX = 0; localX < 16; localX++) {
                                int globalX = startX + localX;
                                int globalZ = startZ + localZ;

                                if (globalX < arraySize && globalZ < arraySize) {
                                    topBlocks[globalZ][globalX] = chunkBlocks[localZ][localX];
                                }
                            }
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                for (Future<?> pending : futures) pending.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("Region rendering cancelled", interrupted);
            } catch (Exception e) {
                System.err.println("处理批次结果时出错: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // 输出统计信息
        long totalTime = System.currentTimeMillis() - startTime.get();
        System.out.println("\n处理完成!");
        System.out.println("总耗时: " + (totalTime / 1000.0) + "秒");
        System.out.println("处理区块: " + populatedChunks.size());
        System.out.println("平均速度: " + (populatedChunks.size() / (totalTime / 1000.0)) + " 区块/秒");
        System.out.println("发现的方块类型数量: " + foundBlocks.size());
        
        return topBlocks;
    }
    
    /**
     * 处理一批区块
     */
    private Map<String, BlockInfo[][]> processChunkBatch(Region region, List<int[]> chunkCoords,
                                                     int threadId, int sampleInterval) {
        Map<String, BlockInfo[][]> results = new HashMap<>();
        Set<String> localFoundBlocks = new HashSet<>();

        for (int[] coord : chunkCoords) {
            if (Thread.currentThread().isInterrupted()) break;
            int chunkX = coord[0];
            int chunkZ = coord[1];

            try {
                Chunk chunk = region.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    BlockInfo[][] chunkBlocks = processChunk(chunk, localFoundBlocks, sampleInterval);
                    results.put(chunkX + "," + chunkZ, chunkBlocks);
                } else {
                    results.put(chunkX + "," + chunkZ, null);
                }

                // 更新进度
                int processed = processedChunks.incrementAndGet();
                if (processed % 8 == 0) { // 每处理8个区块更新一次进度
                    updateProgress();
                }

            } catch (Exception e) {
                System.err.println("处理区块 (" + chunkX + ", " + chunkZ + ") 时出错: " + e.getMessage());
                results.put(chunkX + "," + chunkZ, null);
            }
        }

        // 将本地发现的方块添加到全局集合
        foundBlocks.addAll(localFoundBlocks);

        return results;
    }
    
    /**
     * 处理单个区块，提取顶部方块 - 优化版
     */
    private BlockInfo[][] processChunk(Chunk chunk, Set<String> localFoundBlocks, int sampleInterval) {
        BlockInfo[][] chunkBlocks = new BlockInfo[16][16];

        // 初始化为空气
        BlockInfo airInfo = new BlockInfo("air", -64);
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                chunkBlocks[i][j] = airInfo;
            }
        }
        
        if (!chunk.hasSections()) return chunkBlocks;
        
        boolean[][] foundBlocks = new boolean[16][16];

        // 优化：建立区段索引，避免重复查找
        // Minecraft Y范围通常是 -64 到 319 (Section -4 到 19)
        // 使用数组作为快速查找表，偏移量为5 (处理 -5 到 20+ 的范围)
        Chunk.Section[] sectionMap = new Chunk.Section[32]; 
        int sectionOffset = 5;
        int maxSectionY = -100;
        int minSectionY = 100;

        for (Chunk.Section section : chunk.getSections()) {
            int y = section.getY();
            if (y + sectionOffset >= 0 && y + sectionOffset < sectionMap.length) {
                sectionMap[y + sectionOffset] = section;
                if (y > maxSectionY) maxSectionY = y;
                if (y < minSectionY) minSectionY = y;
            }
        }
        
        if (maxSectionY == -100) return chunkBlocks; // 没有有效区段

        // 预计算哪些区段是全空气的，避免无效查找
        boolean[] isAirSection = new boolean[32];
        for (int sy = minSectionY; sy <= maxSectionY; sy++) {
            int idx = sy + sectionOffset;
            Chunk.Section s = sectionMap[idx];
            // 如果区段存在且调色板只有空气，标记为全空气
            if (s != null) {
                List<Block> palette = s.getPalette();
                if (palette.size() == 1 && BlockColors.isAirBlock(palette.get(0).getId())) {
                    isAirSection[idx] = true;
                }
            }
        }

        // 遍历每个采样点
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                // 应用采样间隔
                if (sampleInterval > 1 && (localX % sampleInterval != 0 || localZ % sampleInterval != 0)) {
                    continue;
                }

                // 从顶部区段向下查找
                for (int sy = maxSectionY; sy >= minSectionY; sy--) {
                    int arrayIndex = sy + sectionOffset;
                    Chunk.Section section = sectionMap[arrayIndex];
                    
                    // 跳过空区段或全空气区段
                    if (section == null || isAirSection[arrayIndex]) continue;

                    // 在区段内从上向下查找
                    for (int y = 15; y >= 0; y--) {
                        Block block = section.getBlock(localX, y, localZ);
                        String blockId = block.getId();
                        
                        if (!BlockColors.isAirBlock(blockId)) {
                            // 找到非空气方块
                            if (blockId.startsWith("minecraft:")) {
                                blockId = blockId.substring(10);
                            }
                            
                            int worldY = (sy << 4) + y;
                            chunkBlocks[localZ][localX] = new BlockInfo(blockId, worldY);
                            localFoundBlocks.add(blockId);
                            foundBlocks[localZ][localX] = true;
                            
                            // 找到最高方块后，跳出Y循环和Section循环
                            sy = minSectionY - 1; // 强制结束外层循环
                            break;
                        }
                    }
                }
            }
        }
            
        // 如果使用了采样间隔 > 1，填充未采样的方块
        if (sampleInterval > 1) {
            fillUnsampledBlocks(chunkBlocks, foundBlocks, sampleInterval);
        }
        
        return chunkBlocks;
    }
    
    /**
     * 填充未采样的方块
     */
    private void fillUnsampledBlocks(BlockInfo[][] chunkBlocks, boolean[][] foundBlocks, int sampleInterval) {
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                if (!foundBlocks[localZ][localX]) {
                    // 找到最近的采样点
                    int sampleX = (localX / sampleInterval) * sampleInterval;
                    int sampleZ = (localZ / sampleInterval) * sampleInterval;

                    // 确保采样点在范围内
                    if (sampleX >= 16) {
                        sampleX = 16 - sampleInterval;
                    }
                    if (sampleZ >= 16) {
                        sampleZ = 16 - sampleInterval;
                    }

                    // 如果该采样点有方块数据，使用它
                    if (foundBlocks[sampleZ][sampleX]) {
                        chunkBlocks[localZ][localX] = chunkBlocks[sampleZ][sampleX];
                    } else {
                        // 如果没有找到最近的采样点，使用默认值
                        chunkBlocks[localZ][localX] = new BlockInfo("air", -64);
                    }
                }
            }
        }
    }
    
    /**
     * 更新进度
     */
    private void updateProgress() {
        if (progressCallback != null) {
            int processed = processedChunks.get();
            int total = totalChunks.get();
            long elapsed = System.currentTimeMillis() - startTime.get();
            double speed = processed / Math.max(0.1, elapsed / 1000.0);
            
            progressCallback.onProgress(processed, total, speed, new HashSet<>(foundBlocks));
        }
    }
    
    /**
     * 渲染顶部方块数据为PNG图像 - 优化版
     */
    public BufferedImage renderToPng(BlockInfo[][] topBlocks, int sampleInterval) {
        if (topBlocks == null) {
            System.err.println("无法渲染：顶部方块数据为空");
            return null;
        }

        System.out.println("正在渲染PNG图像...");
        long startTime = System.currentTimeMillis();

        int height = topBlocks.length;
        int width = topBlocks[0].length;

        if (height <= 0 || width <= 0) {
            System.err.println("无效的数组大小: " + width + "x" + height);
            return null;
        }

        // 预处理所有方块ID及其颜色，避免在循环中重复查找
        Map<String, Color> blockColorCache = new ConcurrentHashMap<>();
        final BlockColors.Palette renderPalette = BlockColors.snapshot();
        
        // 创建RGBA图像
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];

        // 高度差阈值和最大值
        final int HEIGHT_DIFF_THRESHOLD = 3;
        final int MAX_HEIGHT_DIFF = 50;
        final int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        final Color ERROR_COLOR = new Color(255, 0, 255, 255);
        final AtomicInteger errorCount = new AtomicInteger(0);

        // 并行处理每一行
        IntStream.range(0, height).parallel().forEach(i -> {
            for (int j = 0; j < width; j++) {
                try {
                    BlockInfo currentBlock = topBlocks[i][j];
                    String blockId = currentBlock != null ? currentBlock.getBlockId() : null;
                    int currentHeight = currentBlock != null ? currentBlock.getHeight() : -64;

                    Color baseColor;
                    if (currentBlock == null) {
                        baseColor = new Color(0, 0, 0, 0);
                    } else if (blockId == null) {
                        baseColor = ERROR_COLOR;
                        errorCount.incrementAndGet();
                    } else {
                        // 使用 computeIfAbsent 线程安全地获取或计算颜色
                        baseColor = blockColorCache.computeIfAbsent(blockId, 
                            id -> {
                                Color c = renderPalette.getBlockColor(id);
                                return c != null ? c : ERROR_COLOR;
                            });
                        
                        if (baseColor == ERROR_COLOR && !blockColorCache.containsKey(blockId)) {
                            // 只有在真正找不到颜色时才计数错误
                            errorCount.incrementAndGet();
                        }
                    }

                    // 检查相邻像素的高度差
                    boolean shouldDrawEdge = false;
                    int maxHeightDiff = 0;

                    for (int[] dir : directions) {
                        int ni = i + dir[0];
                        int nj = j + dir[1];

                        if (ni >= 0 && ni < height && nj >= 0 && nj < width) {
                            BlockInfo neighborBlock = topBlocks[ni][nj];
                            int neighborHeight = neighborBlock != null ? neighborBlock.getHeight() : -64;

                            int heightDiff = Math.abs(currentHeight - neighborHeight);
                            if (heightDiff > HEIGHT_DIFF_THRESHOLD) {
                                shouldDrawEdge = true;
                                maxHeightDiff = Math.max(maxHeightDiff, heightDiff);
                            }
                        }
                    }

                    int pixelValue;
                    if (shouldDrawEdge) {
                        float intensity = Math.min(1.0f, (float) maxHeightDiff / MAX_HEIGHT_DIFF);
                        int r = (int) (baseColor.getRed() * (1 - intensity * 0.7f));
                        int g = (int) (baseColor.getGreen() * (1 - intensity * 0.7f));
                        int b = (int) (baseColor.getBlue() * (1 - intensity * 0.7f));
                        pixelValue = (baseColor.getAlpha() << 24) | (r << 16) | (g << 8) | b;
                    } else {
                        pixelValue = baseColor.getRGB();
                    }

                    pixels[i * width + j] = pixelValue;

                } catch (Exception e) {
                    pixels[i * width + j] = ERROR_COLOR.getRGB();
                    errorCount.incrementAndGet();
                }
            }
        });

        // 一次性设置所有像素
        image.setRGB(0, 0, width, height, pixels, 0, width);

        if (errorCount.get() > 0) {
            System.out.println("\n注意：处理过程中有 " + errorCount.get() + " 个像素出现错误");
        }

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("渲染完成，耗时: " + (totalTime / 1000.0) + "秒");
        
        return image;
    }

    /**
     * 获取性能报告
     */
    public String getPerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== 性能报告 ===\n");
        report.append("总渲染时间: ").append(totalRenderTime.get()).append("ms\n");
        report.append("总图像生成时间: ").append(totalImageTime.get()).append("ms\n");
        report.append("处理的区块数: ").append(totalRenderedChunks.get()).append("\n");
        report.append("发现的方块类型: ").append(foundBlocks.size()).append("\n");
        
        if (totalRenderedChunks.get() > 0) {
            double avgRenderTime = (double) totalRenderTime.get() / totalRenderedChunks.get();
            report.append("平均每区块渲染时间: ").append(String.format("%.2f", avgRenderTime)).append("ms\n");
        }
        
        return report.toString();
    }

    /**
     * 渐进式渲染方法
     */
    public BufferedImage renderProgressively(String mcaFilePath, int regionSize, int sampleInterval) {
        try {
            System.out.println("开始渐进式渲染: " + mcaFilePath);
            
            // 记录开始时间
            long renderStartTime = System.currentTimeMillis();
            
            // 获取顶部方块数据
            BlockInfo[][] topBlocks = getTopBlocks(mcaFilePath, regionSize, sampleInterval);
            
            long renderTime = System.currentTimeMillis() - renderStartTime;
            totalRenderTime.addAndGet(renderTime);
            
            if (topBlocks != null) {
                // 记录图像生成开始时间
                long imageStartTime = System.currentTimeMillis();
                
                // 渲染图像
                BufferedImage result = renderToPng(topBlocks, sampleInterval);
                
                long imageTime = System.currentTimeMillis() - imageStartTime;
                totalImageTime.addAndGet(imageTime);
                totalRenderedChunks.addAndGet(processedChunks.get());
                
                // 调用渐进式渲染回调
                if (progressCallback != null && result != null) {
                    progressCallback.onProgressiveRender(result, processedChunks.get(), totalChunks.get());
                }
                
                return result;
            }
            
        } catch (Exception e) {
            System.err.println("渐进式渲染失败: " + e.getMessage());
            e.printStackTrace();
        }
        
        return null;
    }

    /**
     * 关闭线程池
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
