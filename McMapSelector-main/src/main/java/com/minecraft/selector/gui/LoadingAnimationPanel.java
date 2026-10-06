package com.minecraft.selector.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.Arc2D;

/**
 * 加载动画面板
 * 提供各种加载动画效果
 */
public class LoadingAnimationPanel extends JPanel {
    
    private Timer animationTimer;
    private int animationFrame = 0;
    private boolean isAnimating = false;
    private String loadingText = "Loading...";
    private AnimationType animationType = AnimationType.SPINNING_CIRCLE;
    
    public enum AnimationType {
        SPINNING_CIRCLE,
        BOUNCING_DOTS,
        PROGRESS_BAR,
        MINECRAFT_BLOCKS
    }
    
    public LoadingAnimationPanel() {
        setPreferredSize(new Dimension(200, 100));
        setOpaque(false);
        
        // 创建动画定时器
        animationTimer = new Timer(100, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                animationFrame++;
                repaint();
            }
        });
    }
    
    /**
     * 开始动画
     */
    public void startAnimation() {
        if (!isAnimating) {
            isAnimating = true;
            animationFrame = 0;
            animationTimer.start();
            setVisible(true);
        }
    }
    
    /**
     * 停止动画
     */
    public void stopAnimation() {
        if (isAnimating) {
            isAnimating = false;
            animationTimer.stop();
            setVisible(false);
        }
    }
    
    /**
     * 设置加载文本
     */
    public void setLoadingText(String text) {
        this.loadingText = text;
        repaint();
    }
    
    /**
     * 设置动画类型
     */
    public void setAnimationType(AnimationType type) {
        this.animationType = type;
        repaint();
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        if (!isAnimating) return;
        
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;
        
        switch (animationType) {
            case SPINNING_CIRCLE:
                drawSpinningCircle(g2d, centerX, centerY - 10);
                break;
            case BOUNCING_DOTS:
                drawBouncingDots(g2d, centerX, centerY - 10);
                break;
            case PROGRESS_BAR:
                drawProgressBar(g2d, centerX, centerY - 10);
                break;
            case MINECRAFT_BLOCKS:
                drawMinecraftBlocks(g2d, centerX, centerY - 10);
                break;
        }
        
        // 绘制加载文本
        g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 12f));
        g2d.setColor(UIThemeManager.ThemeColors.PRIMARY_BLUE);
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(loadingText);
        g2d.drawString(loadingText, centerX - textWidth / 2, centerY + 25);
        
        g2d.dispose();
    }
    
    /**
     * 绘制旋转圆圈动画
     */
    private void drawSpinningCircle(Graphics2D g2d, int centerX, int centerY) {
        int radius = 15;
        int arcLength = 60;
        int startAngle = (animationFrame * 10) % 360;
        
        // 绘制背景圆圈
        g2d.setStroke(new BasicStroke(3));
        g2d.setColor(Color.LIGHT_GRAY);
        g2d.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        
        // 绘制旋转的弧
        g2d.setColor(UIThemeManager.ThemeColors.PRIMARY_BLUE);
        Arc2D.Double arc = new Arc2D.Double(
            centerX - radius, centerY - radius, radius * 2, radius * 2,
            startAngle, arcLength, Arc2D.OPEN
        );
        g2d.draw(arc);
    }
    
    /**
     * 绘制跳动圆点动画
     */
    private void drawBouncingDots(Graphics2D g2d, int centerX, int centerY) {
        int dotRadius = 4;
        int spacing = 15;
        Color[] colors = {
            UIThemeManager.ThemeColors.PRIMARY_BLUE,
            UIThemeManager.ThemeColors.SUCCESS_GREEN,
            UIThemeManager.ThemeColors.WARNING_ORANGE
        };
        
        for (int i = 0; i < 3; i++) {
            int x = centerX - spacing + i * spacing;
            int bounceOffset = (int) (Math.sin((animationFrame + i * 5) * 0.3) * 8);
            int y = centerY + bounceOffset;
            
            g2d.setColor(colors[i]);
            g2d.fillOval(x - dotRadius, y - dotRadius, dotRadius * 2, dotRadius * 2);
        }
    }
    
    /**
     * 绘制进度条动画
     */
    private void drawProgressBar(Graphics2D g2d, int centerX, int centerY) {
        int barWidth = 100;
        int barHeight = 6;
        int x = centerX - barWidth / 2;
        int y = centerY - barHeight / 2;
        
        // 绘制背景
        g2d.setColor(Color.LIGHT_GRAY);
        g2d.fillRoundRect(x, y, barWidth, barHeight, barHeight, barHeight);
        
        // 绘制进度
        int progress = (animationFrame * 3) % (barWidth + 20);
        int progressWidth = Math.min(progress, barWidth);
        
        if (progressWidth > 0) {
            g2d.setColor(UIThemeManager.ThemeColors.SUCCESS_GREEN);
            g2d.fillRoundRect(x, y, progressWidth, barHeight, barHeight, barHeight);
        }
    }
    
    /**
     * 绘制Minecraft方块动画
     */
    private void drawMinecraftBlocks(Graphics2D g2d, int centerX, int centerY) {
        int blockSize = 8;
        int spacing = 12;
        Color[] blockColors = {
            UIThemeManager.ThemeColors.MINECRAFT_GREEN,
            UIThemeManager.ThemeColors.MINECRAFT_BROWN,
            UIThemeManager.ThemeColors.MINECRAFT_STONE
        };
        
        for (int i = 0; i < 3; i++) {
            int x = centerX - spacing + i * spacing;
            int floatOffset = (int) (Math.sin((animationFrame + i * 10) * 0.2) * 5);
            int y = centerY + floatOffset;
            
            // 绘制方块主体
            g2d.setColor(blockColors[i]);
            g2d.fillRect(x - blockSize/2, y - blockSize/2, blockSize, blockSize);
            
            // 绘制方块边框
            g2d.setColor(blockColors[i].darker());
            g2d.drawRect(x - blockSize/2, y - blockSize/2, blockSize, blockSize);
            
            // 绘制高光
            g2d.setColor(blockColors[i].brighter());
            g2d.drawLine(x - blockSize/2, y - blockSize/2, x + blockSize/2 - 1, y - blockSize/2);
            g2d.drawLine(x - blockSize/2, y - blockSize/2, x - blockSize/2, y + blockSize/2 - 1);
        }
    }
    
    /**
     * 创建带加载动画的对话框
     */
    public static JDialog createLoadingDialog(Window parent, String message) {
        JDialog dialog = new JDialog(parent, "Loading", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setResizable(false);
        
        LoadingAnimationPanel animationPanel = new LoadingAnimationPanel();
        animationPanel.setLoadingText(message);
        animationPanel.setAnimationType(AnimationType.MINECRAFT_BLOCKS);
        
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        contentPanel.add(animationPanel, BorderLayout.CENTER);
        
        dialog.setContentPane(contentPanel);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        
        // 显示对话框时开始动画
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                animationPanel.startAnimation();
            }
            
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                animationPanel.stopAnimation();
            }
        });
        
        return dialog;
    }
    
    /**
     * 创建状态指示器
     */
    public static class StatusIndicator extends JPanel {
        private Color indicatorColor = Color.GRAY;
        private boolean isActive = false;
        private Timer blinkTimer;
        
        public StatusIndicator() {
            setPreferredSize(new Dimension(12, 12));
            setOpaque(false);
            
            blinkTimer = new Timer(500, e -> {
                isActive = !isActive;
                repaint();
            });
        }
        
        public void setStatus(StatusType status) {
            blinkTimer.stop();
            isActive = true;
            
            switch (status) {
                case IDLE:
                    indicatorColor = Color.GRAY;
                    break;
                case WORKING:
                    indicatorColor = UIThemeManager.ThemeColors.WARNING_ORANGE;
                    blinkTimer.start();
                    break;
                case SUCCESS:
                    indicatorColor = UIThemeManager.ThemeColors.SUCCESS_GREEN;
                    break;
                case ERROR:
                    indicatorColor = UIThemeManager.ThemeColors.DANGER_RED;
                    break;
            }
            repaint();
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            if (isActive) {
                g2d.setColor(indicatorColor);
                g2d.fillOval(2, 2, 8, 8);
                
                // 添加高光效果
                g2d.setColor(indicatorColor.brighter());
                g2d.fillOval(3, 3, 3, 3);
            }
            
            g2d.dispose();
        }
        
        public enum StatusType {
            IDLE, WORKING, SUCCESS, ERROR
        }
    }
}
