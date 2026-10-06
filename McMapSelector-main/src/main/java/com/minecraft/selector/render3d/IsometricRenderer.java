package com.minecraft.selector.render3d;

import com.minecraft.selector.core.BlockColors;
import com.minecraft.selector.core.MapRenderer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * 等距投影 3D 渲染器 - 区块优化版
 * 使用区块系统和视锥裁剪，大幅提升性能
 */
public class IsometricRenderer {
    private final MapRenderer.BlockInfo[][] blockData;
    private final int worldMinX;
    private final int worldMinZ;
    
    // 相机参数
    private float offsetX = 0;
    private float offsetY = 0;
    private float zoom = 1.0f;
    
    // 渲染参数
    private int width = 1200;
    private int height = 800;
    
    // 等距投影参数
    private static final int TILE_WIDTH = 32;
    private static final int TILE_HEIGHT = 16;
    private static final int TILE_DEPTH = 16;
    
    // 区块系统
    private static final int CHUNK_SIZE = 16; // 每个区块16x16方块
    private Map<ChunkPos, Chunk> chunks;
    private boolean enableCulling = true; // 是否启用视锥裁剪
    
    // 缓存
    private BufferedImage cachedImage;
    private boolean needsRedraw = true;
    private float lastZoom = -1;
    private float lastOffsetX = -1;
    private float lastOffsetY = -1;
    
    // 统计信息
    private int totalBlocks = 0;
    private int renderedChunks = 0;
    private int renderedBlocks = 0;
    
    public IsometricRenderer(MapRenderer.BlockInfo[][] blockData, int worldMinX, int worldMinZ) {
        this.blockData = blockData;
        this.worldMinX = worldMinX;
        this.worldMinZ = worldMinZ;
        this.chunks = buildChunks();
    }
    
    /**
     * 构建区块系统
     */
    private Map<ChunkPos, Chunk> buildChunks() {
        Map<ChunkPos, Chunk> chunkMap = new ConcurrentHashMap<>();
        int dataWidth = blockData[0].length;
        int dataHeight = blockData.length;
        
        // 计算需要多少个区块
        int chunksX = (dataWidth + CHUNK_SIZE - 1) / CHUNK_SIZE;
        int chunksZ = (dataHeight + CHUNK_SIZE - 1) / CHUNK_SIZE;
        
        System.out.println("构建区块系统: " + chunksX + "x" + chunksZ + " = " + (chunksX * chunksZ) + " 个区块");
        
        // 为每个区块构建方块列表
        for (int cz = 0; cz < chunksZ; cz++) {
            for (int cx = 0; cx < chunksX; cx++) {
                ChunkPos pos = new ChunkPos(cx, cz);
                Chunk chunk = new Chunk(pos);
                
                // 填充区块内的方块
                int startX = cx * CHUNK_SIZE;
                int startZ = cz * CHUNK_SIZE;
                int endX = Math.min(startX + CHUNK_SIZE, dataWidth);
                int endZ = Math.min(startZ + CHUNK_SIZE, dataHeight);
                
                for (int z = startZ; z < endZ; z++) {
                    for (int x = startX; x < endX; x++) {
                        MapRenderer.BlockInfo info = blockData[z][x];
                        if (info != null && !info.getBlockId().equals("air")) {
                            String blockId = info.getBlockId();
                            
                            // 跳过装饰性方块
                            if (shouldSkipBlock(blockId)) {
                                continue;
                            }
                            
                            Color color = BlockColors.getBlockColor(blockId);
                            int topY = info.getHeight();
                            
                            // 只添加顶层方块，不填充
                            // 这样显示的是真实的顶层表面
                            chunk.addBlock(new Block(x, z, topY, color));
                            totalBlocks++;
                        }
                    }
                }
                
                // 对区块内的方块排序
                chunk.sortBlocks();
                
                if (!chunk.isEmpty()) {
                    chunkMap.put(pos, chunk);
                }
            }
        }
        
        System.out.println("区块构建完成: " + chunkMap.size() + " 个非空区块, 总方块数: " + totalBlocks);
        System.out.println("注意：只显示顶层方块，如需完整3D结构请使用完整方块数据读取");
        return chunkMap;
    }
    
    /**
     * 渲染场景
     */
    public BufferedImage render() {
        // 检查是否需要重新渲染
        boolean viewChanged = lastZoom != zoom || 
                             Math.abs(lastOffsetX - offsetX) > 1 || 
                             Math.abs(lastOffsetY - offsetY) > 1;
        
        if (!needsRedraw && !viewChanged && cachedImage != null) {
            return cachedImage;
        }
        
        long startTime = System.currentTimeMillis();
        
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        
        // 性能优化
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_SPEED);
        
        // 背景
        g.setColor(new Color(135, 206, 235));
        g.fillRect(0, 0, width, height);
        
        // 计算中心偏移
        int centerX = width / 2;
        int centerY = height / 2;
        
        // 计算可见区块范围
        Set<ChunkPos> visibleChunks;
        if (enableCulling) {
            visibleChunks = getVisibleChunks(centerX, centerY);
        } else {
            // 禁用裁剪时渲染所有区块
            visibleChunks = new HashSet<>(chunks.keySet());
        }
        
        // 渲染可见区块
        renderedChunks = 0;
        renderedBlocks = 0;
        
        for (ChunkPos chunkPos : visibleChunks) {
            Chunk chunk = chunks.get(chunkPos);
            if (chunk != null) {
                renderedBlocks += renderChunk(g, chunk, centerX, centerY);
                renderedChunks++;
            }
        }
        
        long renderTime = System.currentTimeMillis() - startTime;
        
        // 绘制信息
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(5, 5, 280, 65);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 11));
        g.drawString("总方块: " + totalBlocks + " | 总区块: " + chunks.size(), 10, 18);
        g.drawString("可见区块: " + renderedChunks + " | 渲染方块: " + renderedBlocks, 10, 33);
        g.drawString("缩放: " + String.format("%.2f", zoom) + " | 渲染时间: " + renderTime + "ms", 10, 48);
        g.drawString("FPS: " + (renderTime > 0 ? (1000 / renderTime) : "∞"), 10, 63);
        
        g.dispose();
        
        cachedImage = image;
        needsRedraw = false;
        lastZoom = zoom;
        lastOffsetX = offsetX;
        lastOffsetY = offsetY;
        
        return image;
    }
    
    /**
     * 计算可见区块
     */
    private Set<ChunkPos> getVisibleChunks(int centerX, int centerY) {
        Set<ChunkPos> visible = new HashSet<>();
        
        // 遍历所有区块，检查是否可见
        for (Map.Entry<ChunkPos, Chunk> entry : chunks.entrySet()) {
            ChunkPos pos = entry.getKey();
            Chunk chunk = entry.getValue();
            
            // 检查区块的四个角是否有任何一个在屏幕范围内
            boolean isVisible = false;
            
            // 区块的四个角
            int[][] corners = {
                {pos.x * CHUNK_SIZE, pos.z * CHUNK_SIZE},
                {(pos.x + 1) * CHUNK_SIZE, pos.z * CHUNK_SIZE},
                {pos.x * CHUNK_SIZE, (pos.z + 1) * CHUNK_SIZE},
                {(pos.x + 1) * CHUNK_SIZE, (pos.z + 1) * CHUNK_SIZE}
            };
            
            // 检查每个角
            for (int[] corner : corners) {
                int worldX = corner[0];
                int worldZ = corner[1];
                
                // 转换为屏幕坐标
                int screenX = (int)((worldX - worldZ) * TILE_WIDTH / 2 * zoom + centerX + offsetX);
                int screenY = (int)((worldX + worldZ) * TILE_HEIGHT / 2 * zoom + centerY + offsetY);
                
                // 使用更大的边距，确保不会过早裁剪
                int margin = (int)(CHUNK_SIZE * TILE_WIDTH * zoom * 2);
                if (screenX > -margin && screenX < width + margin &&
                    screenY > -margin && screenY < height + margin) {
                    isVisible = true;
                    break;
                }
            }
            
            if (isVisible) {
                visible.add(pos);
            }
        }
        
        return visible;
    }
    
    /**
     * 渲染单个区块
     */
    private int renderChunk(Graphics2D g, Chunk chunk, int centerX, int centerY) {
        int count = 0;
        
        for (Block block : chunk.blocks) {
            // 转换为屏幕坐标
            int screenX = (int)((block.x - block.z) * TILE_WIDTH / 2 * zoom + centerX + offsetX);
            int screenY = (int)((block.x + block.z) * TILE_HEIGHT / 2 * zoom - block.y * TILE_DEPTH * zoom + centerY + offsetY);
            
            // 放宽视口裁剪边距
            int margin = (int)(TILE_WIDTH * zoom * 4);
            if (screenX < -margin || screenX > width + margin || 
                screenY < -margin || screenY > height + margin) {
                continue;
            }
            
            drawBlock(g, screenX, screenY, block.color, zoom);
            count++;
        }
        
        return count;
    }
    
    /**
     * 绘制单个方块
     */
    private void drawBlock(Graphics2D g, int x, int y, Color color, float zoom) {
        int w = (int)(TILE_WIDTH * zoom);
        int h = (int)(TILE_HEIGHT * zoom);
        int d = (int)(TILE_DEPTH * zoom);
        
        // 如果太小，只画一个点
        if (w < 2) {
            g.setColor(color);
            g.fillRect(x, y, 2, 2);
            return;
        }
        
        // 顶面（菱形）
        int[] topX = {x, x + w/2, x, x - w/2};
        int[] topY = {y, y + h/2, y + h, y + h/2};
        g.setColor(color);
        g.fillPolygon(topX, topY, 4);
        
        // 只在方块足够大时绘制侧面
        if (d > 2 && w > 4) {
            // 左侧面
            int[] leftX = {x - w/2, x, x, x - w/2};
            int[] leftY = {y + h/2, y + h, y + h + d, y + h/2 + d};
            g.setColor(color.darker().darker());
            g.fillPolygon(leftX, leftY, 4);
            
            // 右侧面
            int[] rightX = {x, x + w/2, x + w/2, x};
            int[] rightY = {y + h, y + h/2, y + h/2 + d, y + h + d};
            g.setColor(color.darker());
            g.fillPolygon(rightX, rightY, 4);
        }
    }
    
    /**
     * 判断是否应该跳过某个方块
     */
    private boolean shouldSkipBlock(String blockId) {
        if (blockId == null) return false;
        String id = blockId.toLowerCase();
        
        // 花类
        if (id.contains("flower") || id.contains("rose") || id.contains("dandelion") ||
            id.contains("poppy") || id.contains("orchid") || id.contains("allium") ||
            id.contains("tulip") || id.contains("daisy") || id.contains("cornflower") ||
            id.contains("lily") || id.contains("peony") || id.contains("bluet") ||
            id.contains("wildflowers")) {
            return true;
        }
        
        // 草类和灌木
        if (id.contains("grass") && !id.contains("block") || id.contains("fern") ||
            id.equals("bush") || id.contains("seagrass") || id.contains("firefly_bush")) {
            return true;
        }
        
        // 地面装饰
        if (id.contains("leaf_litter") || id.contains("fallen_leaves")) {
            return true;
        }
        
        // 其他装饰
        if (id.contains("sapling") || id.contains("torch") || id.contains("fire") ||
            id.contains("wheat") || id.contains("carrots") || id.contains("potatoes") ||
            id.contains("beetroots") || id.contains("sweet_berry") || id.contains("sugar_cane") ||
            id.contains("cactus") || id.contains("bamboo") || id.contains("vine") ||
            id.contains("kelp") || id.contains("carpet") || 
            (id.contains("snow") && !id.contains("block"))) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 移动视图
     */
    public void pan(float dx, float dy) {
        offsetX += dx;
        offsetY += dy;
    }
    
    /**
     * 缩放
     */
    public void zoom(float factor) {
        zoom = Math.max(0.1f, Math.min(10.0f, zoom * factor));
    }
    
    /**
     * 重置视图
     */
    public void reset() {
        offsetX = 0;
        offsetY = 0;
        zoom = 1.0f;
        needsRedraw = true;
    }
    
    /**
     * 设置渲染大小
     */
    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
        needsRedraw = true;
    }
    
    /**
     * 区块位置
     */
    private static class ChunkPos {
        final int x, z;
        
        ChunkPos(int x, int z) {
            this.x = x;
            this.z = z;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkPos)) return false;
            ChunkPos that = (ChunkPos) o;
            return x == that.x && z == that.z;
        }
        
        @Override
        public int hashCode() {
            return 31 * x + z;
        }
    }
    
    /**
     * 区块
     */
    private static class Chunk {
        final ChunkPos pos;
        final List<Block> blocks;
        
        Chunk(ChunkPos pos) {
            this.pos = pos;
            this.blocks = new ArrayList<>();
        }
        
        void addBlock(Block block) {
            blocks.add(block);
        }
        
        void sortBlocks() {
            blocks.sort((a, b) -> {
                int depthA = a.z + a.x;
                int depthB = b.z + b.x;
                if (depthA != depthB) return depthA - depthB;
                if (a.x != b.x) return a.x - b.x;
                return a.y - b.y;
            });
        }
        
        boolean isEmpty() {
            return blocks.isEmpty();
        }
    }
    
    /**
     * 方块
     */
    private static class Block {
        final int x, z, y;
        final Color color;
        
        Block(int x, int z, int y, Color color) {
            this.x = x;
            this.z = z;
            this.y = y;
            this.color = color;
        }
    }
}
