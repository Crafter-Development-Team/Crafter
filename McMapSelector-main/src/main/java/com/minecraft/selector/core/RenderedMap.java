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

    public RenderedMap(int width, int height) {
        super(width, height, BufferedImage.TYPE_INT_ARGB);
        heights = new int[Math.multiplyExact(width, height)];
        Arrays.fill(heights, NO_HEIGHT);
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
