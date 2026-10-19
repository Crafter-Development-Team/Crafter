package com.minecraft.selector.core;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * A 2D image and the world Y of each rendered column, published as one cache entry.
 * Build pixels/heights before publication; consumers must treat both as read-only.
 * Keeping the pair in one image preserves Swing and PNG APIs without a second cache.
 *
 * <p>逐列的方块状态 / 生物群系 / opaque+dry 高度元数据已移除：那是每块地图 +4 MB
 * （四个 int[width*height]）的来源。这里只保留 1 bit/列的「该列来自存在的区块」标记
 * （512x512 仅 32 KB），用于 gaps 底纹、Fit-Y 的 missing 统计和 zoom-extents。
 */
public final class RenderedMap extends BufferedImage {
    public static final int NO_HEIGHT = Integer.MIN_VALUE;
    private String renderKey="synthetic";

    /** 视口内"已加载列"的像素范围；无 loaded 标记时回落到高度扫描。 */
    public java.awt.Rectangle loadedPixelBounds() {
        int minX=getWidth(),minZ=getHeight(),maxX=-1,maxZ=-1;
        if(loaded!=null) {
            for(int i=loaded.nextSetBit(0);i>=0;i=loaded.nextSetBit(i+1)) {int x=i%getWidth(),z=i/getWidth();minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxZ=Math.max(maxZ,z);}
        } else for(int z=0;z<getHeight();z++) for(int x=0;x<getWidth();x++) if(getHeightAt(x,z)!=NO_HEIGHT) {minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxZ=Math.max(maxZ,z);}
        return maxX<minX?new java.awt.Rectangle(0,0,getWidth(),getHeight()):new java.awt.Rectangle(minX,minZ,maxX-minX+1,maxZ-minZ+1);
    }
    public String getRenderKey() {return renderKey;}
    void setRenderKey(String key) {renderKey=key;}
    private final int[] heights;
    /** 每像素 1 字节：1 = 该列在"兴趣带"里有方块（画布按屏幕坐标叠紫棋盘）；0 = 无 */
    private final byte[] highlight;
    /** 每列 1 bit：该列来自一个存在的区块（区分"已加载的空列"与"缺失区块"）。 */
    private java.util.BitSet loaded;

    public boolean isLoadedAt(int x,int z) {return x>=0&&z>=0&&x<getWidth()&&z<getHeight()&&(loaded==null?getHeightAt(x,z)!=NO_HEIGHT:loaded.get(z*getWidth()+x));}

    /**
     * 兼容旧调用点。逐列 opaque/dry 高度元数据已移除（每块 +4 MB），
     * 两种 Fit 策略现在都退回可见顶高，因此结果相同。
     */
    public int getFitHeightAt(int x,int z,boolean opaque) {return getHeightAt(x,z);}

    /** 标记该列来自一个存在的区块；只碰 BitSet，不分配逐列数组。 */
    void markLoaded(int x,int z) {
        if(x<0||z<0||x>=getWidth()||z>=getHeight()) return;
        if(loaded==null) loaded=new java.util.BitSet(heights.length);
        loaded.set(z*getWidth()+x);
    }
    void clearLoaded(int x,int z) {
        if(loaded!=null&&x>=0&&z>=0&&x<getWidth()&&z<getHeight()) loaded.clear(z*getWidth()+x);
    }

    public RenderedMap(int width, int height) {
        super(width, height, BufferedImage.TYPE_INT_ARGB);
        heights = new int[Math.multiplyExact(width, height)];
        Arrays.fill(heights, NO_HEIGHT);
        highlight = new byte[heights.length];
    }

    public int getHeightAt(int x, int z) {
        if (x < 0 || z < 0 || x >= getWidth() || z >= getHeight()) return NO_HEIGHT;
        return heights[z * getWidth() + x];
    }

    void setHeightAt(int x, int z, int height) {
        heights[z * getWidth() + x] = height;
    }

    /** 供增量裁剪就地改写高度（GUI 的 Y 带实时路径用）。 */
    public void putHeightAt(int x, int z, int height) {
        setHeightAt(x, z, height);
    }

    /** 一次性写入整幅高度（长度必须等于 width*height，行优先）。 */
    public void putHeights(int[] source) {
        if (source.length != heights.length) throw new IllegalArgumentException("heights length mismatch");
        System.arraycopy(source, 0, heights, 0, heights.length);
    }

    public boolean isHighlighted(int x, int z) {
        if (x < 0 || z < 0 || x >= getWidth() || z >= getHeight()) return false;
        return highlight[z * getWidth() + x] != 0;
    }

    public void setHighlighted(int x, int z, boolean value) {
        if (x < 0 || z < 0 || x >= getWidth() || z >= getHeight()) return;
        highlight[z * getWidth() + x] = (byte) (value ? 1 : 0);
    }

    /** 一次性写入整幅兴趣带标记（可传 null 表示清空）。 */
    public void putHighlights(byte[] source) {
        if (source == null) {
            Arrays.fill(highlight, (byte) 0);
            return;
        }
        if (source.length != highlight.length) throw new IllegalArgumentException("highlight length mismatch");
        System.arraycopy(source, 0, highlight, 0, highlight.length);
    }

    /** 合成大图时把兴趣带标记一起搬过去。 */
    public void copyHighlightsFrom(BufferedImage source, int offsetX, int offsetZ) {
        if (!(source instanceof RenderedMap)) return;
        RenderedMap map = (RenderedMap) source;
        int startX = Math.max(0, -offsetX);
        int endX = Math.min(source.getWidth(), getWidth() - offsetX);
        if (startX >= endX) return;
        for (int z = Math.max(0, -offsetZ); z < Math.min(source.getHeight(), getHeight() - offsetZ); z++) {
            System.arraycopy(map.highlight, z * map.getWidth() + startX,
                highlight, (z + offsetZ) * getWidth() + offsetX + startX, endX - startX);
        }
    }

    public static int heightAt(BufferedImage image, int x, int z) {
        return image instanceof RenderedMap ? ((RenderedMap) image).getHeightAt(x, z) : NO_HEIGHT;
    }

    /** Copy heights and the loaded mask while composing images, before publishing the destination. */
    public void copyHeightsFrom(BufferedImage source, int offsetX, int offsetZ) {
        if (!(source instanceof RenderedMap)) return;
        RenderedMap map = (RenderedMap) source;
        renderKey=map.renderKey;
        int startX = Math.max(0, -offsetX);
        int endX = Math.min(source.getWidth(), getWidth() - offsetX);
        if (startX >= endX) return;
        for (int z = Math.max(0, -offsetZ); z < Math.min(source.getHeight(), getHeight() - offsetZ); z++) {
            System.arraycopy(map.heights, z * map.getWidth() + startX,
                heights, (z + offsetZ) * getWidth() + offsetX + startX, endX - startX);
            for(int x=startX;x<endX;x++) {
                int dx=x+offsetX,dz=z+offsetZ;
                if(map.isLoadedAt(x,z)) markLoaded(dx,dz); else clearLoaded(dx,dz);
            }
        }
    }
}
