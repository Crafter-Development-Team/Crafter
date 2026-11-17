package com.minecraft.selector.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * 统一 UI 字体：从 classpath 加载内置的 GNU Unifont，供中文等 CJK 字符显示。
 *
 * <p>加载失败（资源缺失、字体损坏、JDK 不支持该格式）一律回退到系统无衬线字体，
 * 绝不因为字体问题拦住程序启动。Unifont 没有真正的 Bold 字重，BOLD 由 JDK 合成，
 * 这是已知且可接受的。
 *
 * <p>这里打包的是 TrueType 轮廓版本（unifont.ttf），不是上游的 CFF OTF：
 * Java 8 的 CFF 缩放器会把 OTF 字形缩到 1 像素以下，导致界面看起来全白；
 * TrueType 版本在 Java 8/17/21 上都能正常显示。
 */
public final class Fonts {

    /** 内置字体资源路径（构建时由 src/main/resources/fonts/unifont.ttf 打进 jar）。 */
    private static final String RESOURCE = "/fonts/unifont.ttf";

    /** 默认字号：Unifont 是 16px 点阵字体，13pt 下中文仍然清晰。 */
    public static final float DEFAULT_SIZE = 13f;

    /** 已注册的基准字体（PLAIN，DEFAULT_SIZE）；回退时就是 SansSerif。 */
    private static final Font BASE = loadBaseFont();

    private Fonts() {
    }

    /** 13pt 常规字重。 */
    public static Font ui() {
        return ui(DEFAULT_SIZE);
    }

    public static Font ui(float size) {
        return ui(size, Font.PLAIN);
    }

    /**
     * 取得指定字号/字重的 UI 字体。
     *
     * @param size  字号（&lt;=0 时回退到 {@link #DEFAULT_SIZE}）
     * @param style {@link Font#PLAIN}、{@link Font#BOLD} 或 {@link Font#ITALIC} 的组合
     */
    public static Font ui(float size, int style) {
        float safeSize = size > 0f ? size : DEFAULT_SIZE;
        return BASE.deriveFont(style, safeSize);
    }

    private static Font loadBaseFont() {
        InputStream in = null;
        try {
            in = Fonts.class.getResourceAsStream(RESOURCE);
            if (in == null) {
                warn("resource " + RESOURCE + " not found; using fallback font");
                return fallbackFont();
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            try {
                // 注册后系统字体列表里也能按 family 找到它；失败不影响直接用这个 Font 对象。
                if (!GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)) {
                    warn("registerFont returned false; using the font object directly");
                }
            } catch (Throwable registrationError) {
                warn("registerFont failed: " + registrationError);
            }
            Font uiFont = font.deriveFont(Font.PLAIN, DEFAULT_SIZE);
            // Java 8 的 CFF 光栅化器对 Unifont 的 OTF 会画出全空白；能加载不等于能显示。
            if (!canRender(uiFont)) {
                warn("Unifont loaded but renders blank; using fallback font");
                return fallbackFont();
            }
            return uiFont;
        } catch (Throwable error) {
            // 包括 FontFormatException / IOException / 无头环境下的 AWTError：都退回系统字体
            warn("cannot load " + RESOURCE + ": " + error + "; using fallback font");
            return fallbackFont();
        } finally {
            closeQuietly(in);
        }
    }

    private static Font fallbackFont() {
        // 优先选一个系统里真的能显示中文的字体；Unifont 加载失败时也不至于满屏方块。
        String[] candidates = {
            "Microsoft YaHei", "Microsoft JhengHei", "SimSun", "Noto Sans CJK SC",
            "Noto Sans CJK", "Source Han Sans SC", "WenQuanYi Micro Hei",
            "PingFang SC", "Hiragino Sans GB"
        };
        for (String name : candidates) {
            Font candidate = new Font(name, Font.PLAIN, 1);
            if (candidate.canDisplayUpTo("中文测试") < 0) {
                Font sized = candidate.deriveFont(Font.PLAIN, DEFAULT_SIZE);
                if (canRender(sized)) return sized;
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(Font.PLAIN, DEFAULT_SIZE);
    }

    /** 画几个汉字到内存图，确认字体不是“能加载但光栅化全空白”。 */
    private static boolean canRender(Font font) {
        try {
            BufferedImage image = new BufferedImage(128, 48, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setFont(font);
                graphics.setColor(Color.BLACK);
                graphics.drawString("中文测试", 0, 32);
            } finally {
                graphics.dispose();
            }
            int ink = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) != 0) ink++;
                }
            }
            // Java 8 的 CFF 光栅化器只画出 1~4 个杂点；正常中文字形远多于这个数。
            return ink >= 24;
        } catch (Throwable ignored) {
            // 无图形环境/字体异常：按“不能渲染”处理，交给系统字体回退
        }
        return false;
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Exception ignored) {
            // 关闭失败无关紧要
        }
    }

    private static void warn(String message) {
        System.err.println("[Fonts] " + message);
    }
}
