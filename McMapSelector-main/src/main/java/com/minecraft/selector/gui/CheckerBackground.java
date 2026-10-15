package com.minecraft.selector.gui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;

/**
 * 空地/透明像素的棋盘底纹。图案锚在屏幕坐标原点上，所以拖动或缩放地图时它不会跟着动。
 * 另有一份"兴趣带高亮"贴图：黑白棋盘压 50% 紫，半透明地叠在地图上层。
 */
final class CheckerBackground {
    /** 单个方格边长（像素） */
    static final int SQUARE = 8;
    /** 底色，也用作滚动面板视口的背景，避免画布外的空白串色 */
    static final Color DARK = new Color(0x2A2A2A);
    private static final Color LIGHT = new Color(0x363636);
    // 注意：这两个颜色必须声明在 HIGHLIGHT 之前 —— 静态字段按书写顺序初始化，
    // 放后面的话 createHighlightPaint() 里取到的还是 null（setColor(null) 会变成白色）。
    //
    // 半透明的做法：同一个紫色，只靠 alpha 区分棋盘格。
    // （早先用"黑格/白格各压 50% 紫"会把下面的地形颜色洗成灰，看不出来是什么东西。）
    /** 深格：约 55% 紫 */
    private static final Color HIGHLIGHT_ON_BLACK = new Color(0x6A, 0x28, 0xB0, 140);
    /** 浅格：约 27% 紫 */
    private static final Color HIGHLIGHT_ON_WHITE = new Color(0x6A, 0x28, 0xB0, 70);
    private static final TexturePaint PAINT = createPaint();
    private static final TexturePaint HIGHLIGHT = createHighlightPaint();

    private CheckerBackground() {}

    /** 用棋盘填满给定区域（未变换的屏幕坐标）。 */
    static void paint(Graphics2D g2d, int width, int height) {
        g2d.setPaint(PAINT);
        g2d.fillRect(0, 0, width, height);
    }

    /**
     * 兴趣带高亮的贴图：黑白棋盘再压一层 50% 紫（带 alpha，底下的地图能透出来）。
     * 因为它同样锚在屏幕原点，所以拖动/缩放地图时这个图案也不动。
     */
    static TexturePaint highlightPaint() {
        return HIGHLIGHT;
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

    private static TexturePaint createHighlightPaint() {
        BufferedImage tile = new BufferedImage(SQUARE * 2, SQUARE * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics();
        g.setColor(HIGHLIGHT_ON_BLACK);
        g.fillRect(0, 0, SQUARE * 2, SQUARE * 2);
        // 必须用 Src 覆盖：否则浅格会叠在深格上，alpha 被合成成更大的值（反而更不透明）
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(HIGHLIGHT_ON_WHITE);
        g.fillRect(0, 0, SQUARE, SQUARE);
        g.fillRect(SQUARE, SQUARE, SQUARE, SQUARE);
        g.dispose();
        return new TexturePaint(tile, new Rectangle(0, 0, SQUARE * 2, SQUARE * 2));
    }
}
