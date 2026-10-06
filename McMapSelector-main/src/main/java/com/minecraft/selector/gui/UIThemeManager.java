package com.minecraft.selector.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.Properties;
import java.util.prefs.Preferences;

/**
 * UI主题管理器
 * 负责管理应用程序的外观主题，包括深色和浅色主题的切换
 */
public class UIThemeManager {
    
    // 主题类型枚举
    public enum ThemeType {
        LIGHT("Light theme", "light"),
        DARK("Dark theme", "dark");
        
        private final String displayName;
        private final String key;
        
        ThemeType(String displayName, String key) {
            this.displayName = displayName;
            this.key = key;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public String getKey() {
            return key;
        }
    }
    
    // 单例实例
    private static UIThemeManager instance;
    
    // 当前主题
    private ThemeType currentTheme;
    
    // 用户偏好设置
    private final Preferences prefs;
    
    // 主题颜色定义
    public static class ThemeColors {
        // 主要颜色
        public static final Color PRIMARY_BLUE = new Color(70, 130, 180); // Steel blue
        public static final Color SUCCESS_GREEN = new Color(46, 139, 87); // Sea green
        public static final Color WARNING_ORANGE = new Color(255, 140, 0); // Dark orange
        public static final Color DANGER_RED = new Color(220, 20, 60); // Crimson
        
        // Minecraft主题色
        public static final Color MINECRAFT_GREEN = new Color(85, 170, 85);
        public static final Color MINECRAFT_BROWN = new Color(139, 69, 19);
        public static final Color MINECRAFT_STONE = new Color(120, 120, 120);
        
        // 渐变色
        public static final Color GRADIENT_START = new Color(74, 144, 226);
        public static final Color GRADIENT_END = new Color(80, 170, 221);
        
        // 深色主题颜色
        public static final Color DARK_BG = new Color(45, 45, 45);
        public static final Color DARK_PANEL = new Color(50, 50, 50);
        public static final Color DARK_BORDER = new Color(70, 70, 70);
    }
    
    private UIThemeManager() {
        prefs = Preferences.userNodeForPackage(UIThemeManager.class);
        loadSavedTheme();
    }
    
    /**
     * 获取单例实例
     */
    public static UIThemeManager getInstance() {
        if (instance == null) {
            instance = new UIThemeManager();
        }
        return instance;
    }
    
    /**
     * 初始化主题系统
     */
    public void initializeTheme() {
        // 设置系统属性以获得更好的渲染效果
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        
        // 应用当前主题
        applyTheme(currentTheme);
        
        // 自定义UI组件样式
        customizeUIComponents();
    }
    
    /**
     * 应用指定主题
     */
    public void applyTheme(ThemeType theme) {
        try {
            // 启用动画过渡效果
            FlatAnimatedLafChange.showSnapshot();
            
            switch (theme) {
                case LIGHT:
                    UIManager.setLookAndFeel(new FlatLightLaf());
                    break;
                case DARK:
                    UIManager.setLookAndFeel(new FlatDarkLaf());
                    break;
            }
            
            // 更新所有窗口
            FlatLaf.updateUI();
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
            
            currentTheme = theme;
            saveThemePreference();
            
        } catch (Exception e) {
            System.err.println("Failed to apply theme: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 切换主题
     */
    public void toggleTheme() {
        ThemeType newTheme = (currentTheme == ThemeType.LIGHT) ? ThemeType.DARK : ThemeType.LIGHT;
        applyTheme(newTheme);
    }
    
    /**
     * 自定义UI组件样式
     */
    private void customizeUIComponents() {
        // 按钮样式
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        
        // 进度条样式
        UIManager.put("ProgressBar.arc", 8);
        UIManager.put("ProgressBar.selectionBackground", Color.WHITE);
        UIManager.put("ProgressBar.selectionForeground", Color.BLACK);
        
        // 滚动条样式
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.thumbArc", 6);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        
        // 边框样式
        UIManager.put("TitledBorder.titleColor", ThemeColors.PRIMARY_BLUE);
        
        // 工具提示样式
        UIManager.put("ToolTip.background", new Color(255, 255, 225));
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(Color.GRAY, 1));
        
        // 菜单样式
        UIManager.put("MenuBar.background", Color.WHITE);
        UIManager.put("PopupMenu.background", Color.WHITE);
        
        // 表格样式
        UIManager.put("Table.gridColor", new Color(220, 220, 220));
        UIManager.put("Table.disabled", false);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", true);
    }
    
    /**
     * 获取当前主题
     */
    public ThemeType getCurrentTheme() {
        return currentTheme;
    }
    
    /**
     * 加载保存的主题偏好
     */
    private void loadSavedTheme() {
        String savedTheme = prefs.get("theme", ThemeType.LIGHT.getKey());
        for (ThemeType theme : ThemeType.values()) {
            if (theme.getKey().equals(savedTheme)) {
                currentTheme = theme;
                return;
            }
        }
        currentTheme = ThemeType.LIGHT; // 默认主题
    }
    
    /**
     * 保存主题偏好
     */
    private void saveThemePreference() {
        prefs.put("theme", currentTheme.getKey());
    }
    
    /**
     * 创建带图标的按钮
     */
    public static JButton createStyledButton(String text, String iconName) {
        JButton button = new JButton(text);
        
        // 设置按钮样式
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 12f));
        
        // 设置按钮颜色
        button.setBackground(ThemeColors.PRIMARY_BLUE);
        button.setForeground(Color.WHITE);
        
        // 添加悬停效果
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                button.setBackground(ThemeColors.PRIMARY_BLUE.brighter());
            }
            
            public void mouseExited(java.awt.event.MouseEvent evt) {
                button.setBackground(ThemeColors.PRIMARY_BLUE);
            }
        });
        
        return button;
    }
}