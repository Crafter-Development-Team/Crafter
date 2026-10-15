package com.minecraft.selector.gui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 空地棋盘底纹：8px 双色方格，锚在屏幕坐标上，不随地图平移移动。 */
class MapCanvasTest {

    @Test void emptyAreaPaintsCheckerboardAnchoredAtScreenOrigin() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MapCanvas canvas = new MapCanvas();
            canvas.setSize(64, 48);
            BufferedImage out = painted(canvas);
            assertEquals(0x363636, out.getRGB(1, 1) & 0xFFFFFF);
            assertEquals(0x2A2A2A, out.getRGB(9, 1) & 0xFFFFFF);
            assertEquals(0x2A2A2A, out.getRGB(1, 9) & 0xFFFFFF);
            assertEquals(0x363636, out.getRGB(9, 9) & 0xFFFFFF);
            // 画布铺满视口，空地不会露出滚动面板的底色
            assertTrue(canvas.getScrollableTracksViewportWidth());
            assertTrue(canvas.getScrollableTracksViewportHeight());
        });
    }

    @Test void checkerStaysPutWhenTheMapIsPanned() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MapCanvas canvas = new MapCanvas();
            canvas.setSize(64, 48);
            // 宽而扁的图：下半部分是空地，且足够宽以便真的能横向平移
            BufferedImage map = new BufferedImage(200, 20, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = map.createGraphics();
            g.setColor(Color.RED);
            g.fillRect(0, 0, 200, 20);
            g.dispose();

            canvas.setImageAndResetView(map);
            int before = painted(canvas).getRGB(5, 45) & 0xFFFFFF;
            assertEquals(0x2A2A2A, before);          // 这个点确实落在空地上
            canvas.setViewCenter(new Point(0, 0));   // 平移地图
            assertEquals(before, painted(canvas).getRGB(5, 45) & 0xFFFFFF);
        });
    }

    private static BufferedImage painted(MapCanvas canvas) {
        BufferedImage out = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        canvas.paint(g);
        g.dispose();
        return out;
    }
}
