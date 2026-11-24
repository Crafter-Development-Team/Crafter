package com.minecraft.selector.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 可折叠面板组件
 * 提供一个可以展开/折叠的面板，用于组织UI元素
 */
public class CollapsiblePanel extends JPanel {
    
    private JPanel headerPanel;
    private JPanel contentPanel;
    private JLabel titleLabel;
    private JLabel arrowLabel;
    private boolean collapsed = false;
    
    /**
     * 创建可折叠面板
     * @param title 面板标题
     * @param borderColor 边框颜色
     */
    public CollapsiblePanel(String title, Color borderColor) {
        this(title, borderColor, false);
    }
    
    /**
     * 创建可折叠面板
     * @param title 面板标题
     * @param borderColor 边框颜色
     * @param startCollapsed 是否初始折叠
     */
    public CollapsiblePanel(String title, Color borderColor, boolean startCollapsed) {
        this.collapsed = startCollapsed;
        
        setLayout(new BorderLayout());
        setOpaque(false);
        
        // 创建头部面板（使用不透明混色，避免半透明 opaque JPanel 在局部重绘时叠字残影）
        headerPanel = new JPanel(new BorderLayout(6, 0));
        headerPanel.setOpaque(true);
        headerPanel.setBackground(solidTint(borderColor, 30));
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor, 1, true),
            new EmptyBorder(6, 10, 6, 10)
        ));
        headerPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        
        // 标题标签（右侧留 4px 余量，避免 Unifont 合成粗体裁掉末尾字母右缘）
        titleLabel = new JLabel(title);
        titleLabel.setFont(Fonts.ui(12f, Font.BOLD));
        titleLabel.setForeground(borderColor.darker());
        titleLabel.setBorder(new EmptyBorder(0, 0, 0, 4));
        
        // 箭头标签
        arrowLabel = new JLabel(collapsed ? "▶" : "▼");
        arrowLabel.setFont(Fonts.ui(10f, Font.BOLD));
        arrowLabel.setForeground(borderColor);
        
        headerPanel.add(arrowLabel, BorderLayout.WEST);
        headerPanel.add(titleLabel, BorderLayout.CENTER);
        
        // 添加点击事件
        MouseAdapter clickListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggleCollapse();
            }
            
            @Override
            public void mouseEntered(MouseEvent e) {
                headerPanel.setBackground(solidTint(borderColor, 50));
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                headerPanel.setBackground(solidTint(borderColor, 30));
            }
        };
        
        headerPanel.addMouseListener(clickListener);
        titleLabel.addMouseListener(clickListener);
        arrowLabel.addMouseListener(clickListener);
        
        // 创建内容面板（同样使用实色混合背景，防止内部标签动态改字时旧像素透出重叠）
        contentPanel = new JPanel();
        contentPanel.setOpaque(true);
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 1, 1, 1, borderColor),
            new EmptyBorder(8, 10, 8, 10)
        ));
        contentPanel.setBackground(solidTint(borderColor, 12));
        
        // 添加组件
        add(headerPanel, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);
        
        // 设置初始状态
        contentPanel.setVisible(!collapsed);
    }
    
    @Override
    public Dimension getPreferredSize() {
        if (!collapsed && contentPanel != null) {
            // BoxLayout caches child heights until invalidated; clear it so width-dependent
            // children (such as WrapLayout) always report height for the parent's current width.
            for (Component child : contentPanel.getComponents()) {
                child.invalidate();
            }
            contentPanel.invalidate();
        }
        return super.getPreferredSize();
    }

    /**
     * 切换折叠状态
     */
    public void toggleCollapse() {
        collapsed = !collapsed;
        contentPanel.setVisible(!collapsed);
        arrowLabel.setText(collapsed ? "▶" : "▼");
        
        // 触发父容器重新布局
        revalidate();
        repaint();
        
        // 通知父容器更新
        Container parent = getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
    }
    
    /**
     * 设置折叠状态
     */
    public void setCollapsed(boolean collapsed) {
        if (this.collapsed != collapsed) {
            toggleCollapse();
        }
    }
    
    /**
     * 获取折叠状态
     */
    public boolean isCollapsed() {
        return collapsed;
    }
    
    /**
     * 获取内容面板，用于添加组件
     */
    public JPanel getContentPanel() {
        return contentPanel;
    }
    
    /**
     * 添加组件到内容面板
     */
    public void addContent(Component component) {
        contentPanel.add(component);
    }
    
    /**
     * 添加组件和垂直间距
     */
    public void addContentWithSpacing(Component component, int spacing) {
        contentPanel.add(component);
        if (spacing > 0) {
            contentPanel.add(Box.createVerticalStrut(spacing));
        }
    }

    /**
     * 将色调按给定 alpha 与当前主题面板底色做实色混合（返回 alpha=255 的不透明颜色）。
     * Swing 中 isOpaque()=true 的面板若直接设置半透明背景色，局部重绘时不会擦除旧像素，导致文字重叠残影。
     */
    private static Color solidTint(Color tint, int alpha) {
        Color base = UIManager.getColor("Panel.background");
        if (base == null) {
            base = Color.WHITE;
        }
        int a = Math.max(0, Math.min(255, alpha));
        int r = (tint.getRed() * a + base.getRed() * (255 - a)) / 255;
        int g = (tint.getGreen() * a + base.getGreen() * (255 - a)) / 255;
        int b = (tint.getBlue() * a + base.getBlue() * (255 - a)) / 255;
        return new Color(r, g, b);
    }
}
