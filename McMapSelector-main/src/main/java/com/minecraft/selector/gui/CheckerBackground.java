package com.minecraft.selector.gui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;

/**
 * 空地/透明像素的棋盘底纹。图案锚在屏幕坐标原点上，所以拖动或缩放地图时它不会跟着动。
 */
final class CheckerBackground {
    /** 单个方格边长（像素） */
    static final int SQUARE = 8;
    /** 底色，也用作滚动面板视口的背景，避免画布外的空白串色 */
    static final Color DARK = new Color(0x2A2A2A);
    private static final Color LIGHT = new Color(0x363636);
    private static final TexturePaint PAINT = createPaint();

    private CheckerBackground() {}

    /** 用棋盘填满给定区域（未变换的屏幕坐标）。 */
    static void paint(Graphics2D g2d, int width, int height) {
        g2d.setPaint(PAINT);
        g2d.fillRect(0, 0, width, height);
    }

    private static TexturePaint createPaint() {
        BufferedImage tile = new BufferedImage(SQUARE * 2, SQUARE * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = tile.createGraphics();
        g.setColor(DARK);
        g.fillRect(0, 0, SQUARE * 2, SQUARE * 2);
        g.setColor(LIGHT);
        g.fillRect(0, 0, SQUARE, SQUARE);
        g.fillRect(SQUARE, SQUARE, SQUARE, SQUARE);
        g.dispose();
        return new TexturePaint(tile, new Rectangle(0, 0, SQUARE * 2, SQUARE * 2));
    }
}
