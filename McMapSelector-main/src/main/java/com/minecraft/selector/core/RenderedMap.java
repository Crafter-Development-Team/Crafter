package com.minecraft.selector.core;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * A 2D image and the world Y of each rendered column, published as one cache entry.
 * Build pixels/heights before publication; consumers must treat both as read-only.
 * Keeping the pair in one image preserves Swing and PNG APIs without a second cache.
 */
public final class RenderedMap extends BufferedImage {
    public static final int NO_HEIGHT = Integer.MIN_VALUE;
    private final int[] heights;
    /** 每像素 1 字节：1 = 该列在"兴趣带"里有方块（画布按屏幕坐标叠紫棋盘）；0 = 无 */
    private final byte[] highlight;

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

    /** Copy metadata while composing images, before publishing the destination. */
    public void copyHeightsFrom(BufferedImage source, int offsetX, int offsetZ) {
        if (!(source instanceof RenderedMap)) return;
        RenderedMap map = (RenderedMap) source;
        int startX = Math.max(0, -offsetX);
        int endX = Math.min(source.getWidth(), getWidth() - offsetX);
        if (startX >= endX) return;
        for (int z = Math.max(0, -offsetZ); z < Math.min(source.getHeight(), getHeight() - offsetZ); z++) {
            System.arraycopy(map.heights, z * map.getWidth() + startX,
                heights, (z + offsetZ) * getWidth() + offsetX + startX, endX - startX);
        }
    }
}
