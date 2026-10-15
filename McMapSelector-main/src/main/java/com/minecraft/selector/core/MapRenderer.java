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
            // 空属性是常态（缓存路径每个像素一个 BlockInfo）：别为它复制 HashMap
            this.properties=properties.isEmpty()?Collections.emptyMap()
                :Collections.unmodifiableMap(new HashMap<>(properties));
            this.surfaceColor=surfaceColor;
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
    private final Integer clipMinY;

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
        this(maxWorkers,progressCallback,surfaceMode,clipY,null,palette);
    }
    public MapRenderer(int maxWorkers,ProgressCallback progressCallback,SurfaceMode surfaceMode,Integer clipY,Integer clipMinY) {
        this(maxWorkers,progressCallback,surfaceMode,clipY,clipMinY,BlockColors.snapshot());
    }
    /** clipY=扫描上界, clipMinY=扫描下界(含)；两者为 null 表示该侧不裁剪。 */
    public MapRenderer(int maxWorkers,ProgressCallback progressCallback,SurfaceMode surfaceMode,Integer clipY,Integer clipMinY,BlockColors.Palette palette) {
        this.clipY=clipY;this.clipMinY=clipMinY;this.scanPalette=Objects.requireNonNull(palette);
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
        // 上界裁剪不再关闭下界去顶：否则一旦框了 Y 带就永远看不到下界内部。
        final boolean hideRoof = surfaceMode == SurfaceMode.NETHER_INTERIOR ||
            (surfaceMode == SurfaceMode.AUTO && isNetherRegion(mcaFilePath));
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
     * 扫一个 region，留下每列的可见层（按"无 Y 带"收集），之后换 Y 带只做内存重合成。
     * 下界去顶的 region（NETHER_INTERIOR，或 AUTO 下的下界路径）返回 null：那条分支的
     * roof/cavity 判定依赖扫描窗口本身，不能靠过滤层栈复现，调用方回落到全量渲染。
     */
    public ColumnCache scanRegion(String mcaFilePath,int regionSize) throws IOException {
        if(regionSize<1||regionSize>32) throw new IllegalArgumentException("regionSize must be 1..32");
        if(surfaceMode==SurfaceMode.NETHER_INTERIOR || (surfaceMode==SurfaceMode.AUTO && isNetherRegion(mcaFilePath))) return null;

        long start=System.currentTimeMillis();
        Region region = Region.fromFile(mcaFilePath);
        List<int[]> populatedChunks=new ArrayList<>();
        for(int x=0;x<regionSize;x++) for(int z=0;z<regionSize;z++) if(region.chunkExists(x,z)) populatedChunks.add(new int[]{x,z});
        ColumnCache.Builder builder=new ColumnCache.Builder(regionSize);
        if(populatedChunks.isEmpty()) return builder.build();

        int batchSize=Math.max(1,populatedChunks.size()/maxWorkers);
        List<Future<Map<String,ColumnCache.ChunkColumns>>> futures=new ArrayList<>();
        for(int i=0;i<populatedChunks.size();i+=batchSize) {
            final List<int[]> batch=populatedChunks.subList(i,Math.min(populatedChunks.size(),i+batchSize));
            futures.add(executorService.submit(() -> {
                Map<String,ColumnCache.ChunkColumns> results=new HashMap<>();
                for(int[] coord:batch) {
                    if(Thread.currentThread().isInterrupted()) break;
                    try {
                        Chunk chunk=region.getChunk(coord[0],coord[1]);
                        results.put(coord[0]+","+coord[1],chunk==null?null:scanChunkColumns(chunk));
                    } catch(Exception e) {
                        throw new IllegalStateException("Cannot read chunk "+coord[0]+","+coord[1],e);
                    }
                }
                return results;
            }));
        }
        for(Future<Map<String,ColumnCache.ChunkColumns>> future:futures) {
            try {
                for(Map.Entry<String,ColumnCache.ChunkColumns> entry:future.get().entrySet()) {
                    String[] coords=entry.getKey().split(",");
                    builder.put(Integer.parseInt(coords[0]),Integer.parseInt(coords[1]),entry.getValue());
                }
            } catch(InterruptedException interrupted) {
                for(Future<?> pending:futures) pending.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("Region scan cancelled",interrupted);
            } catch(Exception e) {
                for(Future<?> pending:futures) pending.cancel(true);
                throw new IOException("Cannot scan region "+mcaFilePath,e);
            }
        }
        ColumnCache cache=builder.build();
        System.out.println("层缓存建立完成: "+new File(mcaFilePath).getName()+" 段数="+cache.getRunCount()
            +" 耗时="+((System.currentTimeMillis()-start)/1000.0)+"秒");
        return cache;
    }

    /**
     * 一个区块的 16x16 列，整列收集（不设 Y 带、也不在饱和处提前退出），
     * 供任意 Y 带事后重合成。同一 Block 实例的颜色只解析一次。
     */
    private ColumnCache.ChunkColumns scanChunkColumns(Chunk chunk) {
        ColumnCache.ChunkColumns columns=new ColumnCache.ChunkColumns();
        List<Chunk.Section> sections=chunk.getSections();
        sections.sort(Comparator.comparingInt(Chunk.Section::getY).reversed());
        Set<Chunk.Section> airSections=new HashSet<>();
        for(Chunk.Section section:sections) {
            List<Block> palette=section.getPalette();
            if(palette.isEmpty()||palette.stream().allMatch(block->BlockColors.isAirBlock(block.getId()))) airSections.add(section);
        }
        // 有方块实体的区块，颜色跟坐标有关，不能按方块实例缓存
        final boolean entities=chunk.hasBlockEntities();
        final Map<Block,Integer> memo=entities?null:new IdentityHashMap<>();
        for(int z=0;z<16;z++) {
            for(int x=0;x<16;x++) {
                int column=z*16+x;
                for(Chunk.Section section:sections) {
                    if(airSections.contains(section)) continue;
                    long bottom=(long)section.getY()*16;
                    if(bottom<=Integer.MIN_VALUE || bottom+15>Integer.MAX_VALUE) throw new IllegalArgumentException("Section Y outside supported world coordinates");
                    for(int y=15;y>=0;y--) {
                        Block block=section.getBlock(x,y,z);
                        String blockId=block.getId();
                        if(BlockColors.isAirBlock(blockId)) continue;
                        int argb;
                        Integer cached=memo==null?null:memo.get(block);
                        if(cached!=null) argb=cached;
                        else {
                            int worldY=(int)bottom+y;
                            Color associated=entities?BlockEntityColors.resolve(blockId,chunk.getBlockEntity(x,worldY,z),scanPalette):null;
                            Color color=associated!=null?associated:surfaceColor(blockId,block.getProperties(),scanPalette);
                            if(associated!=null) scanPalette.recordAssociated(blockId);
                            argb=color.getRGB();
                            if(memo!=null) memo.put(block,argb);
                        }
                        if(((argb>>>24)&0xFF)==0) continue; // 全透明的层本来就不进层栈
                        columns.addLayer(column,(int)bottom+y,argb,blockId);
                    }
                }
            }
        }
        return columns;
    }

    /** 一次整列扫描的产物：当前 Y 带的结果 + 换带用的层缓存 + 本次用的调色板。 */
    public static final class RegionScan {
        public final ColumnCache cache;            // null = 该 region 不可缓存（下界去顶）
        public final BlockColors.Palette palette;
        private final MapRenderer renderer;
        private final BlockInfo[][] blocksFallback;
        private final RenderedMap imageFallback;
        private final Integer bandTop, bandBottom;
        private BlockInfo[][] blocks;
        private RenderedMap image;

        RegionScan(ColumnCache cache, BlockColors.Palette palette, MapRenderer renderer,
                   BlockInfo[][] blocksFallback, RenderedMap imageFallback, Integer bandTop, Integer bandBottom) {
            this.cache = cache;
            this.palette = palette;
            this.renderer = renderer;
            this.blocksFallback = blocksFallback;
            this.imageFallback = imageFallback;
            this.bandTop = bandTop;
            this.bandBottom = bandBottom;
        }

        /** 逐列的 BlockInfo（测试/兼容用，按需构造）。 */
        public BlockInfo[][] topBlocks() {
            if (blocks == null) blocks = cache != null ? cache.topBlocks(bandTop, bandBottom) : blocksFallback;
            return blocks;
        }

        /** 出图：有缓存走数组快路径（不建 BlockInfo），否则用刚才那次带状渲染的结果。 */
        public RenderedMap image() {
            if (image == null) {
                image = cache != null ? cache.toImage(bandTop, bandBottom)
                    : (imageFallback != null ? imageFallback : (RenderedMap) renderer.renderToPng(blocksFallback, 1));
            }
            return image;
        }
    }

    /**
     * 扫一次，同时给出当前 Y 带的出图数据和层缓存 —— 让 GUI 渲染完就具备实时换带的能力，
     * 不必再单独扫一遍。allowCache=false 或该 region 不可缓存时，退回原来的带状扫描。
     */
    public RegionScan scanRegionBlocks(String mcaFilePath,int regionSize,Integer bandTop,Integer bandBottom,
                                       boolean allowCache) throws IOException {
        ColumnCache cache=allowCache?scanRegion(mcaFilePath,regionSize):null;
        if(cache==null) {
            BlockInfo[][] blocks=getTopBlocks(mcaFilePath,regionSize,1);
            return new RegionScan(null,scanPalette,this,blocks,(RenderedMap)renderToPng(blocks,1),bandTop,bandBottom);
        }
        return new RegionScan(cache,scanPalette,this,null,null,bandTop,bandBottom);
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

    /** 一列的扫描结果：带内收集到的可见层（top→down）+ 下界去顶需要的两个标记。 */
    private static final class ColumnScan {
        final List<Layer> ordinary = new ArrayList<>(), interior = new ArrayList<>();
        boolean roofFound, cavityFound;
        BlockInfo composite() { return MapRenderer.composite(roofFound ? interior : ordinary); }
    }

    /**
     * 收集一列的可见层。bandTop/bandBottom 为 null 表示该侧不裁。
     * 收集在 alpha 饱和处停止：更深的层改变不了合成结果，这与原来的提前 return 等价。
     */
    private ColumnScan scanColumn(Chunk chunk,List<Chunk.Section> sections,Set<Chunk.Section> airSections,
                                  int x,int z,boolean hideRoof,Integer bandTop,Integer bandBottom) {
        // Discover the Nether ceiling/cavity first in the same descending pass. Keep the
        // ordinary surface as fallback for columns without ceiling bedrock.
        ColumnScan out=new ColumnScan();
        boolean roofFound=false,cavityFound=false;
        double ordinaryAlpha=0,interiorAlpha=0;
        long previousBottom=Long.MAX_VALUE;
        scan:
        for(Chunk.Section section:sections) {
            long bottom=(long)section.getY()*16,top=bottom+15;
            if(bottom<=Integer.MIN_VALUE || top>Integer.MAX_VALUE) throw new IllegalArgumentException("Section Y outside supported world coordinates");
            if(bandBottom!=null && top<bandBottom) break; // sections 已按 Y 降序，更低的整段都在带外
            if(roofFound && top<previousBottom-1) cavityFound=true;
            previousBottom=bottom;
            if(airSections.contains(section)) {if(roofFound) cavityFound=true;continue;}
            for(int y=15;y>=0;y--) {
                Block block=section.getBlock(x,y,z);String id=block.getId();int worldY=(int)bottom+y;
                if(bandTop!=null && worldY>bandTop) continue;
                if(bandBottom!=null && worldY<bandBottom) break; // 到 Y 带底部，本列更低的层不再参与合成
                if(BlockColors.isAirBlock(id)) {if(roofFound) cavityFound=true;continue;}
                if(hideRoof && !roofFound && worldY>=32 && "bedrock".equals(id)) roofFound=true;
                if(!roofFound && ordinaryAlpha<0.995) {
                    Layer layer=layer(chunk,block,x,worldY,z);if(layer.color.getAlpha()>0) {
                        out.ordinary.add(layer);ordinaryAlpha+=(1-ordinaryAlpha)*layer.color.getAlpha()/255.0;
                        if(!hideRoof && ordinaryAlpha>=0.995) break scan;
                    }
                } else if(roofFound && cavityFound) {
                    Layer layer=layer(chunk,block,x,worldY,z);if(layer.color.getAlpha()>0) {
                        out.interior.add(layer);interiorAlpha+=(1-interiorAlpha)*layer.color.getAlpha()/255.0;
                        if(interiorAlpha>=0.995) break scan;
                    }
                }
            }
        }
        out.roofFound=roofFound;out.cavityFound=cavityFound;
        return out;
    }

    private BlockInfo findSurface(Chunk chunk,List<Chunk.Section> sections,Set<Chunk.Section> airSections,
                                  int x,int z,boolean hideRoof) {
        return scanColumn(chunk,sections,airSections,x,z,hideRoof,clipY,clipMinY).composite();
    }
    static final class Layer {
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
        final Color ERROR_COLOR = new Color(255, 0, 255, 255);
        final AtomicInteger errorCount = new AtomicInteger(0);

        // 并行处理每一行
        // 第一趟：从 BlockInfo 抽出「高度 + 基色」两个数组（之后不再需要 BlockInfo）
        final int[] heights = new int[width * height];
        final int[] baseColors = new int[width * height];
        IntStream.range(0, height).parallel().forEach(i -> {
            for (int j = 0; j < width; j++) {
                int index = i * width + j;
                try {
                    BlockInfo currentBlock = topBlocks[i][j];
                    String blockId = currentBlock != null ? currentBlock.getBlockId() : null;
                    int currentHeight = currentBlock != null && !BlockColors.isAirBlock(blockId)
                        ? currentBlock.getHeight() : RenderedMap.NO_HEIGHT;
                    heights[index] = currentHeight;

                    Color baseColor;
                    if (currentHeight == RenderedMap.NO_HEIGHT) {
                        baseColor = TRANSPARENT;
                    } else if (blockId == null) {
                        baseColor = ERROR_COLOR;
                        errorCount.incrementAndGet();
                    } else {
                        baseColor=currentBlock.getSurfaceColor()!=null?currentBlock.getSurfaceColor():
                            surfaceColor(blockId,currentBlock.getProperties(),renderPalette);
                    }
                    baseColors[index] = baseColor.getRGB();
                } catch (Exception e) {
                    heights[index] = RenderedMap.NO_HEIGHT;
                    baseColors[index] = ERROR_COLOR.getRGB();
                    errorCount.incrementAndGet();
                }
            }
        });

        // 第二趟：描边 + 写像素（与缓存路径共用同一套规则）
        RenderedMap image = renderArrays(width, height, heights, baseColors);

        if (errorCount.get() > 0) {
            System.out.println("\n注意：处理过程中有 " + errorCount.get() + " 个像素出现错误");
        }

        long totalTime = System.currentTimeMillis() - startTime;
        com.minecraft.selector.utils.LogManager.getInstance().info("Rendered "+width+"x"+height+" surface map","MapRenderer");
        System.out.println("渲染完成，耗时: " + (totalTime / 1000.0) + "秒");
        
        return image;
    }

    /**
     * 由「每像素高度 + 每像素基色」两个数组直接出图 —— 描边规则与 {@link #renderToPng} 完全同一套，
     * 但不需要逐个像素构造 BlockInfo（Y 带实时重合成走这条路，避免每帧上百万对象）。
     */
    public static RenderedMap renderArrays(int width, int height, int[] heights, int[] baseColors) {
        RenderedMap image = new RenderedMap(width, height);
        int[] pixels = new int[width * height];
        IntStream.range(0, height).parallel().forEach(i -> {
            for (int j = 0; j < width; j++) {
                int index = i * width + j;
                int current = heights[index];
                int left = j > 0 ? heights[index - 1] : RenderedMap.NO_HEIGHT;
                int right = j + 1 < width ? heights[index + 1] : RenderedMap.NO_HEIGHT;
                int up = i > 0 ? heights[index - width] : RenderedMap.NO_HEIGHT;
                int down = i + 1 < height ? heights[index + width] : RenderedMap.NO_HEIGHT;
                pixels[index] = shadedPixel(baseColors[index], current, left, right, up, down);
            }
        });
        image.setRGB(0, 0, width, height, pixels, 0, width);
        image.putHeights(heights);
        return image;
    }

    /**
     * 单像素规则：空列直接透明；否则四邻高度差 &gt; 3 时按最大差值压暗（强度上限 1，最多压 70%）。
     * 邻格为空气/越界都用 NO_HEIGHT 表示。
     */
    public static int shadedPixel(int baseArgb, int height, int left, int right, int up, int down) {
        if (height == RenderedMap.NO_HEIGHT) return baseArgb;
        int maxDiff = 0;
        maxDiff = edgeDiff(height, left, maxDiff);
        maxDiff = edgeDiff(height, right, maxDiff);
        maxDiff = edgeDiff(height, up, maxDiff);
        maxDiff = edgeDiff(height, down, maxDiff);
        if (maxDiff == 0) return baseArgb;
        float intensity = Math.min(1.0f, (float) maxDiff / MAX_HEIGHT_DIFF);
        int a = (baseArgb >>> 24) & 0xFF;
        int r = (int) (((baseArgb >> 16) & 0xFF) * (1 - intensity * 0.7f));
        int g = (int) (((baseArgb >> 8) & 0xFF) * (1 - intensity * 0.7f));
        int b = (int) ((baseArgb & 0xFF) * (1 - intensity * 0.7f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static final int MAX_HEIGHT_DIFF = 50;
    private static final int HEIGHT_DIFF_THRESHOLD = 3;
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    private static int edgeDiff(int height, int neighbour, int maxDiff) {
        if (neighbour == RenderedMap.NO_HEIGHT) return maxDiff;
        int diff = Math.abs(height - neighbour);
        return diff > HEIGHT_DIFF_THRESHOLD ? Math.max(maxDiff, diff) : maxDiff;
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
