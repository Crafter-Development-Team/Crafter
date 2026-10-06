package com.minecraft.selector.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.Map;

/**
 * 图标管理器
 * 负责创建和管理应用程序中使用的各种图标
 */
public class IconManager {
    
    // 图标缓存
    private static final Map<String, Icon> iconCache = new HashMap<>();
    
    // 标准图标尺寸
    public static final int SMALL_ICON_SIZE = 16;
    public static final int MEDIUM_ICON_SIZE = 24;
    public static final int LARGE_ICON_SIZE = 32;
    
    // 颜色常量
    private static final Color PRIMARY_COLOR = UIThemeManager.ThemeColors.PRIMARY_BLUE;
    private static final Color SUCCESS_COLOR = UIThemeManager.ThemeColors.SUCCESS_GREEN;
    private static final Color WARNING_COLOR = UIThemeManager.ThemeColors.WARNING_ORANGE;
    private static final Color DANGER_COLOR = UIThemeManager.ThemeColors.DANGER_RED;
    
    /**
     * 获取文件夹图标
     */
    public static Icon getFolderIcon(int size) {
        String key = "folder_" + size;
        return iconCache.computeIfAbsent(key, k -> createFolderIcon(size));
    }
    
    /**
     * 获取文件图标
     */
    public static Icon getFileIcon(int size) {
        String key = "file_" + size;
        return iconCache.computeIfAbsent(key, k -> createFileIcon(size));
    }
    
    /**
     * 获取渲染图标
     */
    public static Icon getRenderIcon(int size) {
        String key = "render_" + size;
        return iconCache.computeIfAbsent(key, k -> createRenderIcon(size));
    }
    
    /**
     * 获取玩家图标
     */
    public static Icon getPlayerIcon(int size) {
        String key = "player_" + size;
        return iconCache.computeIfAbsent(key, k -> createPlayerIcon(size));
    }
    
    /**
     * 获取设置图标
     */
    public static Icon getSettingsIcon(int size) {
        String key = "settings_" + size;
        return iconCache.computeIfAbsent(key, k -> createSettingsIcon(size));
    }
    
    /**
     * 获取清除图标
     */
    public static Icon getClearIcon(int size) {
        String key = "clear_" + size;
        return iconCache.computeIfAbsent(key, k -> createClearIcon(size));
    }
    
    /**
     * 获取跳转图标
     */
    public static Icon getJumpIcon(int size) {
        String key = "jump_" + size;
        return iconCache.computeIfAbsent(key, k -> createJumpIcon(size));
    }
    
    /**
     * 获取确认图标
     */
    public static Icon getConfirmIcon(int size) {
        String key = "confirm_" + size;
        return iconCache.computeIfAbsent(key, k -> createConfirmIcon(size));
    }
    
    /**
     * 获取重新渲染图标
     */
    public static Icon getRefreshIcon(int size) {
        String key = "refresh_" + size;
        return iconCache.computeIfAbsent(key, k -> createRefreshIcon(size));
    }
    
    /**
     * 创建文件夹图标
     */
    private static Icon createFolderIcon(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 设置颜色
                g2d.setColor(PRIMARY_COLOR);
                
                // 绘制文件夹主体
                int folderWidth = (int) (size * 0.8);
                int folderHeight = (int) (size * 0.6);
                int tabWidth = (int) (size * 0.4);
                int tabHeight = (int) (size * 0.2);
                
                // 绘制文件夹标签
                g2d.fillRoundRect(x, y, tabWidth, tabHeight, 2, 2);
                
                // 绘制文件夹主体
                g2d.fillRoundRect(x, y + tabHeight/2, folderWidth, folderHeight, 2, 2);
                
                g2d.dispose();
            }

            @Override
            public int getIconWidth() {
                return SMALL_ICON_SIZE;
            }

            @Override
            public int getIconHeight() {
                return SMALL_ICON_SIZE;
            }
        };
    }
    
    /**
     * 创建文件图标
     */
    private static Icon createFileIcon(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 设置颜色
                g2d.setColor(PRIMARY_COLOR);
                
                // 绘制文件轮廓
                int fileWidth = (int) (size * 0.7);
                int fileHeight = (int) (size * 0.9);
                int foldWidth = (int) (size * 0.3);
                
                // 绘制文件主体
                g2d.fillRoundRect(x, y, fileWidth, fileHeight, 2, 2);
                
                // 绘制折叠角
                Path2D fold = new Path2D.Double();
                fold.moveTo(x + fileWidth - foldWidth, y);
                fold.lineTo(x + fileWidth, y);
                fold.lineTo(x + fileWidth, y + foldWidth);
                fold.closePath();
                g2d.setColor(Color.WHITE);
                g2d.fill(fold);
                g2d.setColor(PRIMARY_COLOR);
                g2d.draw(fold);
                
                g2d.dispose();
            }

            @Override
            public int getIconWidth() {
                return SMALL_ICON_SIZE;
            }

            @Override
            public int getIconHeight() {
                return SMALL_ICON_SIZE;
            }
        };
    }
    
    /**
     * 创建渲染图标
     */
    private static Icon createRenderIcon(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 设置颜色
                g2d.setColor(SUCCESS_COLOR);
                
                // 绘制播放按钮形状（表示渲染开始）
                int triangleSize = (int) (size * 0.7);
                int centerX = x + size / 2;
                int centerY = y + size / 2;
                
                Path2D triangle = new Path2D.Double();
                triangle.moveTo(centerX - triangleSize/2, centerY - triangleSize/2);
                triangle.lineTo(centerX + triangleSize/2, centerY);
                triangle.lineTo(centerX - triangleSize/2, centerY + triangleSize/2);
                triangle.closePath();
                
                g2d.fill(triangle);
                
                g2d.dispose();
            }

            @Override
            public int getIconWidth() {
                return SMALL_ICON_SIZE;
            }

            @Override
            public int getIconHeight() {
                return SMALL_ICON_SIZE;
            }
        };
    }
    
    /**
     * 创建玩家图标
     */
    private static Icon createPlayerIcon(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 设置颜色
                g2d.setColor(PRIMARY_COLOR);
                
                // 绘制简单的玩家图标（圆形表示玩家）
                int headSize = (int) (size * 0.7);
                int centerX = x + size / 2;
                int centerY = y + size / 2;
                
                // 绘制头部
                g2d.fillOval(centerX - headSize/2, centerY - headSize/2, headSize, headSize);
                
                // 绘制身体（简化）
                int bodyWidth = (int) (size * 0.3);
                int bodyHeight = (int) (size * 0.5);
                g2d.fillRect(centerX - bodyWidth/2, centerY + headSize/2 - 2, bodyWidth, bodyHeight);
                
                g2d.dispose();
            }

            @Override
            public int getIconWidth() {
                return SMALL_ICON_SIZE;
            }

            @Override
            public int getIconHeight() {
                return SMALL_ICON_SIZE;
            }
        };
    }
    
    /**
     * 创建跳转图标
     */
    private static Icon createJumpIcon(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 设置颜色
                g2d.setColor(PRIMARY_COLOR);
                
                // 绘制箭头形状
                int arrowSize = (int) (size * 0.7);
                int centerX = x + size / 2;
                int centerY = y + size / 2;
                
                // 绘制箭头主体
                g2d.setStroke(new BasicStroke(2.0f));
                g2d.drawLine(centerX - arrowSize/2, centerY, centerX + arrowSize/2, centerY);
                g2d.drawLine(centerX + arrowSize/3, centerY - arrowSize/3, centerX + arrowSize/2, centerY);
                g2d.drawLine(centerX + arrowSize/3, centerY + arrowSize/3, centerX + arrowSize/2, centerY);
                
                g2d.dispose();
            }

            @Override
            public int getIconWidth() {
                return SMALL_ICON_SIZE;
            }

            @Override
            public int getIconHeight() {
                return SMALL_ICON_SIZE;
            }
        };
    }
    
    /**
     * 创建确认图标
     */
    private static Icon createConfirmIcon(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 设置颜色
                g2d.setColor(SUCCESS_COLOR);
                
                // 绘制勾号
                int checkSize = (int) (size * 0.8);
                int centerX = x + size / 2;
                int centerY = y + size / 2;
                
                g2d.setStroke(new BasicStroke(2.0f));
                g2d.drawLine(centerX - checkSize/3, centerY, centerX, centerY + checkSize/4);
                g2d.drawLine(centerX, centerY + checkSize/4, centerX + checkSize/2, centerY - checkSize/4);
                
                g2d.dispose();
            }

            @Override
            public int getIconWidth() {
                return SMALL_ICON_SIZE;
            }

            @Override
            public int getIconHeight() {
                return SMALL_ICON_SIZE;
            }
        };
    }
    
    /**
     * 创建刷新图标
     */
    private static Icon createRefreshIcon(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 设置颜色
                g2d.setColor(PRIMARY_COLOR);
                
                // 绘制循环箭头
                int refreshSize = (int) (size * 0.7);
                int centerX = x + size / 2;
                int centerY = y + size / 2;
                
                // 绘制圆形轨迹
                g2d.setStroke(new BasicStroke(1.5f));
                g2d.drawOval(centerX - refreshSize/2, centerY - refreshSize/2, refreshSize, refreshSize);
                
                // 绘制箭头
                Path2D arrow = new Path2D.Double();
                arrow.moveTo(centerX + refreshSize/2 - 2, centerY - 2);
                arrow.lineTo(centerX + refreshSize/2 + 3, centerY);
                arrow.lineTo(centerX + refreshSize/2 - 2, centerY + 2);
                arrow.closePath();
                g2d.fill(arrow);
                
                g2d.dispose();
            }

            @Override
            public int getIconWidth() {
                return SMALL_ICON_SIZE;
            }

            @Override
            public int getIconHeight() {
                return SMALL_ICON_SIZE;
            }
        };
    }
    
    /**
     * 创建清除图标
     */
    private static Icon createClearIcon(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 设置颜色
                g2d.setColor(DANGER_COLOR);
                
                // 绘制X号
                int xSize = (int) (size * 0.7);
                int centerX = x + size / 2;
                int centerY = y + size / 2;
                
                g2d.setStroke(new BasicStroke(2.0f));
                g2d.drawLine(centerX - xSize/2, centerY - xSize/2, centerX + xSize/2, centerY + xSize/2);
                g2d.drawLine(centerX + xSize/2, centerY - xSize/2, centerX - xSize/2, centerY + xSize/2);
                
                g2d.dispose();
            }

            @Override
            public int getIconWidth() {
                return SMALL_ICON_SIZE;
            }

            @Override
            public int getIconHeight() {
                return SMALL_ICON_SIZE;
            }
        };
    }
    
    /**
     * 创建设置图标
     */
    private static Icon createSettingsIcon(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 设置颜色
                g2d.setColor(PRIMARY_COLOR);
                
                // 绘制齿轮形状
                int gearSize = (int) (size * 0.7);
                int centerX = x + size / 2;
                int centerY = y + size / 2;
                
                // 绘制中心圆
                g2d.fillOval(centerX - gearSize/6, centerY - gearSize/6, gearSize/3, gearSize/3);
                
                // 绘制齿轮齿
                for (int i = 0; i < 6; i++) {
                    double angle = Math.toRadians(i * 60);
                    int x1 = (int) (centerX + (gearSize/2) * Math.cos(angle));
                    int y1 = (int) (centerY + (gearSize/2) * Math.sin(angle));
                    int x2 = (int) (centerX + (gearSize/3) * Math.cos(angle));
                    int y2 = (int) (centerY + (gearSize/3) * Math.sin(angle));
                    
                    g2d.setStroke(new BasicStroke(2.0f));
                    g2d.drawLine(x2, y2, x1, y1);
                }
                
                g2d.dispose();
            }

            @Override
            public int getIconWidth() {
                return SMALL_ICON_SIZE;
            }

            @Override
            public int getIconHeight() {
                return SMALL_ICON_SIZE;
            }
        };
    }
}