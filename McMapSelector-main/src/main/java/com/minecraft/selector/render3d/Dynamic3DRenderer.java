package com.minecraft.selector.render3d;

import com.minecraft.selector.core.BlockColors;
import com.minecraft.selector.region.*;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.utils.WorldPathResolver;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.util.*;

/**
 * 动态3D渲染器 - 极致性能优化版
 */
public class Dynamic3DRenderer {
    private final String savePath;
    
    // 相机参数
    private float offsetX = 0;
    private float offsetY = 0;
    private float zoom = 1.0f;
    
    // 渲染参数
    private int width = 1200;
    private int height = 800;
    private static final int TILE_WIDTH = 32;
    private static final int TILE_HEIGHT = 16;
    private static final int TILE_DEPTH = 16;
    
    // 所有方块数据（预排序）
    private int[] blockX;
    private int[] blockZ;
    private int[] blockY;
    private int[] blockColor;
    private int[] blockColorDark;
    private int[] blockColorDarker;
    private int[] blockFaces; // 位标记: bit0=top, bit1=left, bit2=right
    private int blockCount = 0;
    
    // 缓存
    private BufferedImage cachedImage;
    private int[] pixels;
    private boolean needsRedraw = true;
    
    private static final int SKY_COLOR = 0xFF87CEEB;
    private int renderedBlocks = 0;
    
    public Dynamic3DRenderer(String savePath, int worldMinX, int worldMinZ) {
        this.savePath = savePath;
        System.out.println("3D渲染器初始化");
        initBuffers();
        loadAllBlocks();
    }
    
    private void initBuffers() {
        cachedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        pixels = ((DataBufferInt) cachedImage.getRaster().getDataBuffer()).getData();
    }

    private void loadAllBlocks() {
        System.out.println("加载方块...");
        long startTime = System.currentTimeMillis();
        
        String mcaFile = WorldPathResolver.getMcaFile(savePath, 0, 0).getAbsolutePath();
        File file = new File(mcaFile);
        if (!file.exists()) {
            System.err.println("找不到: " + mcaFile);
            return;
        }
        
        // 临时存储
        java.util.List<int[]> tempBlocks = new ArrayList<>(500000);
        
        try {
            Region region = Region.fromFile(mcaFile);
            
            for (int cx = 0; cx < 32; cx++) {
                for (int cz = 0; cz < 32; cz++) {
                    if (region.chunkExists(cx, cz)) {
                        Chunk chunk = region.getChunk(cx, cz);
                        if (chunk != null) {
                            extractChunk(chunk, cx, cz, tempBlocks);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("加载失败: " + e.getMessage());
            return;
        }
        
        // 按深度排序（只排序一次）
        tempBlocks.sort((a, b) -> {
            int depthA = a[0] + a[1];
            int depthB = b[0] + b[1];
            if (depthA != depthB) return depthA - depthB;
            return a[2] - b[2];
        });
        
        // 转换为原始数组
        blockCount = tempBlocks.size();
        blockX = new int[blockCount];
        blockZ = new int[blockCount];
        blockY = new int[blockCount];
        blockColor = new int[blockCount];
        blockColorDark = new int[blockCount];
        blockColorDarker = new int[blockCount];
        blockFaces = new int[blockCount];
        
        for (int i = 0; i < blockCount; i++) {
            int[] b = tempBlocks.get(i);
            blockX[i] = b[0];
            blockZ[i] = b[1];
            blockY[i] = b[2];
            blockColor[i] = b[3];
            blockColorDark[i] = b[4];
            blockColorDarker[i] = b[5];
            blockFaces[i] = b[6];
        }
        
        long loadTime = System.currentTimeMillis() - startTime;
        System.out.println("加载完成: " + blockCount + " 方块, " + loadTime + "ms");
    }
    
    private void extractChunk(Chunk chunk, int chunkX, int chunkZ, java.util.List<int[]> blocks) {
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        
        // 构建实心方块网格
        boolean[][][] solid = new boolean[16][384][16];
        String[][][] ids = new String[16][384][16];
        
        // 第一遍：标记所有实心方块
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 319; y >= -64; y--) {
                    com.minecraft.selector.region.Block block = chunk.getBlock(x, y, z);
                    if (block != null && !block.isAir()) {
                        String id = block.getId();
                        if (id.startsWith("minecraft:")) id = id.substring(10);
                        if (!shouldSkip(id)) {
                            solid[x][y + 64][z] = true;
                            ids[x][y + 64][z] = id;
                        }
                    }
                }
            }
        }
        
        // 第二遍：只添加从等距视角可见的方块，并记录哪些面暴露
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 319; y >= -64; y--) {
                    int yi = y + 64;
                    if (!solid[x][yi][z]) continue;
                    
                    // 检查每个面是否暴露
                    boolean topExposed = (y >= 319 || !solid[x][yi + 1][z]);
                    boolean leftExposed = (x >= 15 || !solid[x + 1][yi][z]);
                    boolean rightExposed = (z >= 15 || !solid[x][yi][z + 1]);
                    
                    // 只有至少一个面暴露才渲染
                    if (topExposed || leftExposed || rightExposed) {
                        String id = ids[x][yi][z];
                        Color c = BlockColors.getBlockColor(id);
                        int rgb = c.getRGB();
                        
                        // 用位标记哪些面需要绘制: bit0=top, bit1=left, bit2=right
                        int faces = (topExposed ? 1 : 0) | (leftExposed ? 2 : 0) | (rightExposed ? 4 : 0);
                        
                        blocks.add(new int[]{
                            baseX + x, baseZ + z, y,
                            rgb, darker(rgb), darker(darker(rgb)),
                            faces
                        });
                    }
                }
            }
        }
    }
    
    private int darker(int rgb) {
        int r = ((rgb >> 16) & 0xFF) * 7 / 10;
        int g = ((rgb >> 8) & 0xFF) * 7 / 10;
        int b = (rgb & 0xFF) * 7 / 10;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    
    private boolean shouldSkip(String id) {
        return id.contains("flower") || (id.contains("grass") && !id.contains("block")) ||
               id.contains("fern") || id.equals("bush") || id.contains("torch") ||
               id.contains("wheat") || id.contains("vine") || id.contains("kelp");
    }

    public BufferedImage render() {
        if (!needsRedraw && cachedImage != null) {
            return cachedImage;
        }
        
        long startTime = System.currentTimeMillis();
        
        // 清空
        Arrays.fill(pixels, SKY_COLOR);
        
        int centerX = width / 2;
        int centerY = height / 2;
        
        float zoomHalfW = zoom * TILE_WIDTH / 2;
        float zoomHalfH = zoom * TILE_HEIGHT / 2;
        float zoomD = zoom * TILE_DEPTH;
        
        int w = (int)(TILE_WIDTH * zoom);
        int h = (int)(TILE_HEIGHT * zoom);
        int d = (int)(TILE_DEPTH * zoom);
        
        int hw = w / 2 + 1;
        int hh = h / 2 + 1;
        
        renderedBlocks = 0;
        
        // 直接遍历预排序的方块数组
        for (int i = 0; i < blockCount; i++) {
            int screenX = (int)((blockX[i] - blockZ[i]) * zoomHalfW + centerX + offsetX);
            int screenY = (int)((blockX[i] + blockZ[i]) * zoomHalfH - blockY[i] * zoomD + centerY + offsetY);
            
            // 视锥剔除
            if (screenX < -w || screenX > width + w || screenY < -h - d || screenY > height + h) {
                continue;
            }
            
            // 绘制方块
            if (w < 3) {
                setPixel(screenX, screenY, blockColor[i]);
                setPixel(screenX + 1, screenY, blockColor[i]);
            } else {
                int faces = blockFaces[i];
                
                // 先画侧面（只画暴露的面）
                if (d > 2 && w > 4) {
                    // 左侧面（较暗）- 只在左面暴露时画
                    if ((faces & 2) != 0) {
                        fillLeftFace(screenX, screenY, hw, hh, d + 1, blockColorDarker[i]);
                    }
                    // 右侧面（稍暗）- 只在右面暴露时画
                    if ((faces & 4) != 0) {
                        fillRightFace(screenX, screenY, hw, hh, d + 1, blockColorDark[i]);
                    }
                }
                // 顶面（最亮）- 只在顶面暴露时画
                if ((faces & 1) != 0) {
                    fillDiamond(screenX, screenY, hw, hh, blockColor[i]);
                }
            }
            renderedBlocks++;
        }
        
        long renderTime = System.currentTimeMillis() - startTime;
        
        // 信息
        Graphics2D g = cachedImage.createGraphics();
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(5, 5, 220, 50);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 11));
        g.drawString("方块: " + blockCount + " | 渲染: " + renderedBlocks, 10, 20);
        g.drawString("缩放: " + String.format("%.2f", zoom) + " | " + renderTime + "ms", 10, 35);
        g.drawString("拖拽移动 | 滚轮缩放 | R重置", 10, 50);
        g.dispose();
        
        needsRedraw = false;
        return cachedImage;
    }
    
    private void fillDiamond(int cx, int cy, int hw, int hh, int color) {
        int h2 = hh * 2;
        for (int dy = 0; dy <= h2; dy++) {
            int rowWidth = (dy <= hh) ? (dy * hw / hh) : ((h2 - dy) * hw / hh);
            int py = cy + dy;
            if (py < 0 || py >= height) continue;
            
            int startX = Math.max(0, cx - rowWidth);
            int endX = Math.min(width - 1, cx + rowWidth);
            if (startX <= endX) {
                int offset = py * width;
                Arrays.fill(pixels, offset + startX, offset + endX + 1, color);
            }
        }
    }
    
    private void fillLeftFace(int cx, int cy, int hw, int hh, int d, int color) {
        // 左侧面：从顶面左下角向下延伸的平行四边形
        for (int dy = 0; dy < d; dy++) {
            int py = cy + hh + dy;
            if (py < 0 || py >= height) continue;
            
            // 左侧面的x范围随y变化（斜边）
            int leftX = cx - hw;
            int rightX = cx;
            
            int startX = Math.max(0, leftX);
            int endX = Math.min(width - 1, rightX);
            if (startX <= endX) {
                int offset = py * width;
                Arrays.fill(pixels, offset + startX, offset + endX + 1, color);
            }
        }
    }
    
    private void fillRightFace(int cx, int cy, int hw, int hh, int d, int color) {
        // 右侧面：从顶面右下角向下延伸的平行四边形
        for (int dy = 0; dy < d; dy++) {
            int py = cy + hh + dy;
            if (py < 0 || py >= height) continue;
            
            int leftX = cx;
            int rightX = cx + hw;
            
            int startX = Math.max(0, leftX);
            int endX = Math.min(width - 1, rightX);
            if (startX <= endX) {
                int offset = py * width;
                Arrays.fill(pixels, offset + startX, offset + endX + 1, color);
            }
        }
    }
    
    private void setPixel(int x, int y, int color) {
        if (x >= 0 && x < width && y >= 0 && y < height) {
            pixels[y * width + x] = color;
        }
    }
    
    public void pan(float dx, float dy) {
        offsetX += dx;
        offsetY += dy;
        needsRedraw = true;
    }
    
    public void zoom(float factor) {
        zoom = Math.max(0.1f, Math.min(10.0f, zoom * factor));
        needsRedraw = true;
    }
    
    public void reset() {
        offsetX = 0;
        offsetY = 0;
        zoom = 1.0f;
        needsRedraw = true;
    }
    
    public void setSize(int width, int height) {
        if (this.width != width || this.height != height) {
            this.width = width;
            this.height = height;
            initBuffers();
            needsRedraw = true;
        }
    }
    
    public void shutdown() {}
}
