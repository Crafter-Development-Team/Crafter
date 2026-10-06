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
        
        // 创建头部面板
        headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(borderColor.getRed(), borderColor.getGreen(), borderColor.getBlue(), 30));
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor, 1, true),
            new EmptyBorder(6, 10, 6, 10)
        ));
        headerPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        
        // 标题标签
        titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));
        titleLabel.setForeground(borderColor.darker());
        
        // 箭头标签
        arrowLabel = new JLabel(collapsed ? "▶" : "▼");
        arrowLabel.setFont(arrowLabel.getFont().deriveFont(Font.BOLD, 10f));
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
                headerPanel.setBackground(new Color(borderColor.getRed(), borderColor.getGreen(), borderColor.getBlue(), 50));
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                headerPanel.setBackground(new Color(borderColor.getRed(), borderColor.getGreen(), borderColor.getBlue(), 30));
            }
        };
        
        headerPanel.addMouseListener(clickListener);
        titleLabel.addMouseListener(clickListener);
        arrowLabel.addMouseListener(clickListener);
        
        // 创建内容面板
        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 1, 1, 1, borderColor),
            new EmptyBorder(8, 10, 8, 10)
        ));
        contentPanel.setBackground(new Color(borderColor.getRed(), borderColor.getGreen(), borderColor.getBlue(), 10));
        
        // 添加组件
        add(headerPanel, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);
        
        // 设置初始状态
        contentPanel.setVisible(!collapsed);
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
}
