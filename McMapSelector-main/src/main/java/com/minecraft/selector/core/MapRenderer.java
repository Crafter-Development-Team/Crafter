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
        private final Map<String,String> properties;
        private final Color surfaceColor;

        public BlockInfo(String blockId, int height) {
            this(blockId,height,Collections.emptyMap(),null);
        }
        public BlockInfo(String blockId,int height,Map<String,String> properties,Color surfaceColor) {
            this.blockId=blockId;this.height=height;
            this.properties=Collections.unmodifiableMap(new HashMap<>(properties));this.surfaceColor=surfaceColor;
        }

        public String getBlockId() {
            return blockId;
        }

        public int getHeight() {
            return height;
        }

        public Map<String,String> getProperties() {return properties;}
        public Color getSurfaceColor() {return surfaceColor;}

        @Override
        public String toString() {
            return blockId + "@" + height;
        }
    }
    
    public enum SurfaceMode { AUTO, TOP, NETHER_INTERIOR }
    public static final BlockInfo EMPTY = new BlockInfo("air", RenderedMap.NO_HEIGHT);
    private final SurfaceMode surfaceMode;
    private final BlockColors.Palette scanPalette;
    private final Integer clipY;

    public static boolean isNetherRegion(String path) {
        for (File part = new File(path).getAbsoluteFile(); part != null; part = part.getParentFile()) {
            if ("DIM-1".equals(part.getName()) || "the_nether".equals(part.getName())) return true;
        }
        return false;
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
        this(maxWorkers, progressCallback, SurfaceMode.AUTO);
    }

    public MapRenderer(int maxWorkers, ProgressCallback progressCallback, SurfaceMode surfaceMode) {
        this(maxWorkers,progressCallback,surfaceMode,null);
    }
    public MapRenderer(int maxWorkers,ProgressCallback progressCallback,SurfaceMode surfaceMode,Integer clipY) {
        this(maxWorkers,progressCallback,surfaceMode,clipY,BlockColors.snapshot());
    }
    public MapRenderer(int maxWorkers,ProgressCallback progressCallback,SurfaceMode surfaceMode,Integer clipY,BlockColors.Palette palette) {
        this.clipY=clipY;this.scanPalette=Objects.requireNonNull(palette);
        if (maxWorkers < 1) throw new IllegalArgumentException("maxWorkers must be positive");
        this.surfaceMode = Objects.requireNonNull(surfaceMode);
        this.maxWorkers = maxWorkers;
        this.progressCallback = progressCallback;
        this.executorService = Executors.newFixedThreadPool(maxWorkers);
    }

    /**
     * 渲染区域文件为顶部方块数据
     */
    public BlockInfo[][] getTopBlocks(String mcaFilePath, int regionSize, int sampleInterval) throws IOException {
        if (regionSize < 1 || regionSize > 32 || sampleInterval < 1 || sampleInterval > 16)
            throw new IllegalArgumentException("regionSize must be 1..32 and sampleInterval 1..16");
        final boolean hideRoof = clipY==null && (surfaceMode == SurfaceMode.NETHER_INTERIOR ||
            (surfaceMode == SurfaceMode.AUTO && isNetherRegion(mcaFilePath)));
        com.minecraft.selector.utils.LogManager.getInstance().info("Scanning region: "+new File(mcaFilePath).getName(),"MapRenderer");
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
        BlockInfo empty = EMPTY;
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
                processChunkBatch(region, batch, batchIndex, sampleInterval, hideRoof)
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
                for (Future<?> pending : futures) pending.cancel(true);
                throw new IOException("Cannot render region " + mcaFilePath, e);
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
                                                     int threadId, int sampleInterval, boolean hideRoof) {
        Map<String, BlockInfo[][]> results = new HashMap<>();
        Set<String> localFoundBlocks = new HashSet<>();

        for (int[] coord : chunkCoords) {
            if (Thread.currentThread().isInterrupted()) break;
            int chunkX = coord[0];
            int chunkZ = coord[1];

            try {
                Chunk chunk = region.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    BlockInfo[][] chunkBlocks = processChunk(chunk, localFoundBlocks, sampleInterval, hideRoof);
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
                throw new IllegalStateException("Cannot read chunk " + chunkX + "," + chunkZ, e);
            }
        }

        // 将本地发现的方块添加到全局集合
        foundBlocks.addAll(localFoundBlocks);

        return results;
    }
    
    /**
     * 处理单个区块，提取顶部方块 - 优化版
     */
    private BlockInfo[][] processChunk(Chunk chunk, Set<String> localFoundBlocks,
                                       int sampleInterval, boolean hideRoof) {
        BlockInfo[][] columns = new BlockInfo[16][16];
        for (BlockInfo[] row : columns) Arrays.fill(row, EMPTY);
        List<Chunk.Section> sections = chunk.getSections();
        sections.sort(Comparator.comparingInt(Chunk.Section::getY).reversed());
        Set<Chunk.Section> airSections = new HashSet<>();
        boolean hasRoofCandidate = false;
        for (Chunk.Section section : sections) {
            List<Block> palette = section.getPalette();
            if (palette.isEmpty() || palette.stream().allMatch(block -> BlockColors.isAirBlock(block.getId())))
                airSections.add(section);
            if (section.getY() >= 2 && palette.stream().anyMatch(block -> "bedrock".equals(block.getId())))
                hasRoofCandidate = true;
        }
        for (int z = 0; z < 16; z += sampleInterval) {
            for (int x = 0; x < 16; x += sampleInterval) {
                BlockInfo top = findSurface(chunk, sections, airSections, x, z, hideRoof && hasRoofCandidate);
                if (top != EMPTY) localFoundBlocks.add(top.getBlockId());
                // Color and Y always refer to the same sample (GUI uses interval 1).
                for (int dz = z; dz < Math.min(16, z + sampleInterval); dz++)
                    for (int dx = x; dx < Math.min(16, x + sampleInterval); dx++) columns[dz][dx] = top;
            }
        }
        return columns;
    }

    private BlockInfo findSurface(Chunk chunk,List<Chunk.Section> sections,Set<Chunk.Section> airSections,
                                  int x,int z,boolean hideRoof) {
        // Discover the Nether ceiling/cavity first in the same descending pass. Keep the
        // ordinary surface as fallback for columns without ceiling bedrock.
        List<Layer> ordinary=new ArrayList<>(), interior=new ArrayList<>();
        boolean roofFound=false,cavityFound=false;
        double ordinaryAlpha=0,interiorAlpha=0;
        long previousBottom=Long.MAX_VALUE;
        for(Chunk.Section section:sections) {
            long bottom=(long)section.getY()*16,top=bottom+15;
            if(bottom<=Integer.MIN_VALUE || top>Integer.MAX_VALUE) throw new IllegalArgumentException("Section Y outside supported world coordinates");
            if(roofFound && top<previousBottom-1) cavityFound=true;
            previousBottom=bottom;
            if(airSections.contains(section)) {if(roofFound) cavityFound=true;continue;}
            for(int y=15;y>=0;y--) {
                Block block=section.getBlock(x,y,z);String id=block.getId();int worldY=(int)bottom+y;
                if(clipY!=null && worldY>clipY) continue;
                if(BlockColors.isAirBlock(id)) {if(roofFound) cavityFound=true;continue;}
                if(hideRoof && !roofFound && worldY>=32 && "bedrock".equals(id)) roofFound=true;
                if(!roofFound && ordinaryAlpha<0.995) {
                    Layer layer=layer(chunk,block,x,worldY,z);if(layer.color.getAlpha()>0) {
                        ordinary.add(layer);ordinaryAlpha+=(1-ordinaryAlpha)*layer.color.getAlpha()/255.0;
                        if(!hideRoof && ordinaryAlpha>=0.995) return composite(ordinary);
                    }
                } else if(roofFound && cavityFound) {
                    Layer layer=layer(chunk,block,x,worldY,z);if(layer.color.getAlpha()>0) {
                        interior.add(layer);interiorAlpha+=(1-interiorAlpha)*layer.color.getAlpha()/255.0;
                        if(interiorAlpha>=0.995) return composite(interior);
                    }
                }
            }
        }
        return composite(roofFound?interior:ordinary);
    }
    private static final class Layer {
        final Block block;final int y;final Color color;
        Layer(Block block,int y,Color color) {this.block=block;this.y=y;this.color=color;}
    }
    private Layer layer(Chunk chunk,Block block,int x,int y,int z) {
        Color associated=BlockEntityColors.resolve(block.getId(),chunk.getBlockEntity(x,y,z),scanPalette);
        Color color=associated==null?surfaceColor(block.getId(),block.getProperties(),scanPalette):associated;
        if(associated!=null) scanPalette.recordAssociated(block.getId());
        return new Layer(block,y,color);
    }
    /** Projection opacity describes horizontal coverage, not occupied volume. */
    public static Color surfaceColor(String id,Map<String,String> props,BlockColors.Palette palette) {
        BlockColors.Appearance appearance=palette.appearance(id,props);
        Color color=appearance.color;
        boolean projected=appearance.source.equals("model");
        String name=id.replace("minecraft:","");
        double alpha=color.getAlpha()/255.0;
        if(name.equals("snow")) {
            int layers=1;try {layers=Integer.parseInt(props.getOrDefault("layers","1"));} catch(NumberFormatException ignored) {}
            alpha=0.70+0.30*(Math.max(1,Math.min(8,layers))-1)/7.0;
        } else if(!projected && name.endsWith("_trapdoor") && "true".equals(props.get("open"))) alpha=Math.min(alpha,3.0/16);
        else if(!projected && name.endsWith("_pressure_plate")) alpha=Math.min(alpha,196.0/256);
        else if(name.endsWith("glass") || name.endsWith("glass_pane")) alpha=Math.min(alpha,0.38);
        return new Color(color.getRed(),color.getGreen(),color.getBlue(),(int)Math.round(alpha*255));
    }
    public static Color over(Color top,Color below) {
        double a=top.getAlpha()/255.0,b=below.getAlpha()/255.0,out=a+(1-a)*b;
        if(out==0) return new Color(0,0,0,0);
        return new Color((int)Math.round((top.getRed()*a+below.getRed()*b*(1-a))/out),
            (int)Math.round((top.getGreen()*a+below.getGreen()*b*(1-a))/out),
            (int)Math.round((top.getBlue()*a+below.getBlue()*b*(1-a))/out),(int)Math.round(out*255));
    }
    private static BlockInfo composite(List<Layer> layers) {
        if(layers.isEmpty()) return EMPTY;
        Color color=new Color(0,0,0,0);
        for(int i=layers.size()-1;i>=0;i--) color=over(layers.get(i).color,color);
        Layer top=layers.get(0);return new BlockInfo(top.block.getId(),top.y,top.block.getProperties(),color);
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

        com.minecraft.selector.utils.LogManager.getInstance().info("Rendering surface colors and cached heights...","MapRenderer");
        System.out.println("正在渲染PNG图像...");
        long startTime = System.currentTimeMillis();

        int height = topBlocks.length;
        int width = height == 0 ? 0 : topBlocks[0].length;

        if (height <= 0 || width <= 0) {
            System.err.println("无效的数组大小: " + width + "x" + height);
            return null;
        }

        // 预处理所有方块ID及其颜色，避免在循环中重复查找
        final BlockColors.Palette renderPalette = scanPalette;
        
        // 创建RGBA图像
        RenderedMap image = new RenderedMap(width, height);
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
                    int currentHeight = currentBlock != null && !BlockColors.isAirBlock(blockId)
                        ? currentBlock.getHeight() : RenderedMap.NO_HEIGHT;
                    image.setHeightAt(j, i, currentHeight);

                    Color baseColor;
                    if (currentHeight == RenderedMap.NO_HEIGHT) {
                        baseColor = new Color(0, 0, 0, 0);
                    } else if (blockId == null) {
                        baseColor = ERROR_COLOR;
                        errorCount.incrementAndGet();
                    } else {
                        baseColor=currentBlock.getSurfaceColor()!=null?currentBlock.getSurfaceColor():
                            surfaceColor(blockId,currentBlock.getProperties(),renderPalette);
                    }

                    // 检查相邻像素的高度差
                    boolean shouldDrawEdge = false;
                    int maxHeightDiff = 0;

                    for (int[] dir : directions) {
                        int ni = i + dir[0];
                        int nj = j + dir[1];

                        if (ni >= 0 && ni < height && nj >= 0 && nj < width) {
                            BlockInfo neighborBlock = topBlocks[ni][nj];
                            if (currentHeight == RenderedMap.NO_HEIGHT || neighborBlock == null ||
                                BlockColors.isAirBlock(neighborBlock.getBlockId()) ||
                                neighborBlock.getHeight() == RenderedMap.NO_HEIGHT) continue;
                            int neighborHeight = neighborBlock.getHeight();

                            int heightDiff = (int) Math.min(Integer.MAX_VALUE, Math.abs((long) currentHeight - neighborHeight));
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
        com.minecraft.selector.utils.LogManager.getInstance().info("Rendered "+width+"x"+height+" surface map","MapRenderer");
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
