package com.minecraft.selector.gui;

import com.minecraft.selector.render3d.IsometricRenderer;
import com.minecraft.selector.core.MapRenderer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;

/**
 * 等距投影 3D 地图查看器 - 重写版
 */
public class IsometricViewer extends JFrame {
    private final IsometricRenderer renderer;
    private final RenderPanel renderPanel;
    
    private Point lastMousePos;
    private boolean isDragging = false;
    
    public IsometricViewer(MapRenderer.BlockInfo[][] blockData, int worldMinX, int worldMinZ) {
        setTitle("Minecraft 3D 地图查看器");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        
        renderer = new IsometricRenderer(blockData, worldMinX, worldMinZ);
        renderPanel = new RenderPanel();
        
        setupUI();
        setupControls();
    }
    
    private void setupUI() {
        setLayout(new BorderLayout());
        
        // 控制面板
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setBackground(new Color(240, 240, 240));
        
        JLabel infoLabel = new JLabel("鼠标拖拽移动 | 滚轮缩放 | R键重置");
        infoLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        controlPanel.add(infoLabel);
        
        JButton resetBtn = new JButton("重置视图");
        resetBtn.addActionListener(e -> {
            renderer.reset();
            renderPanel.repaint();
        });
        controlPanel.add(resetBtn);
        
        add(controlPanel, BorderLayout.NORTH);
        add(renderPanel, BorderLayout.CENTER);
    }
    
    private void setupControls() {
        // 鼠标拖拽
        renderPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastMousePos = e.getPoint();
                isDragging = true;
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                isDragging = false;
            }
        });
        
        renderPanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (isDragging && lastMousePos != null) {
                    int dx = e.getX() - lastMousePos.x;
                    int dy = e.getY() - lastMousePos.y;
                    
                    renderer.pan(dx, dy);
                    renderPanel.repaint();
                    
                    lastMousePos = e.getPoint();
                }
            }
        });
        
        // 鼠标滚轮缩放
        renderPanel.addMouseWheelListener(e -> {
            float factor = e.getWheelRotation() < 0 ? 1.1f : 0.9f;
            renderer.zoom(factor);
            renderPanel.repaint();
        });
        
        // 键盘控制
        renderPanel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_R:
                        renderer.reset();
                        renderPanel.repaint();
                        break;
                    case KeyEvent.VK_ESCAPE:
                        dispose();
                        break;
                }
            }
        });
        
        renderPanel.setFocusable(true);
        renderPanel.requestFocusInWindow();
    }
    
    /**
     * 渲染面板
     */
    private class RenderPanel extends JPanel {
        private BufferedImage currentImage;
        
        public RenderPanel() {
            setBackground(Color.BLACK);
            setDoubleBuffered(true);
            
            // 初始渲染
            Timer timer = new Timer(100, e -> {
                currentImage = renderer.render();
                repaint();
            });
            timer.setRepeats(false);
            timer.start();
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            
            if (currentImage == null) {
                // 首次渲染
                currentImage = renderer.render();
            }
            
            if (currentImage != null) {
                g.drawImage(currentImage, 0, 0, null);
            }
        }
        
        @Override
        public void repaint() {
            // 重新渲染
            currentImage = renderer.render();
            super.repaint();
        }
    }
}
