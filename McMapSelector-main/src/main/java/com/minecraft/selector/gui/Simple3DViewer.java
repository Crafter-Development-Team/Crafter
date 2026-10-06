package com.minecraft.selector.gui;

import com.minecraft.selector.render3d.Simple3DRenderer;
import com.minecraft.selector.core.MapRenderer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;

/**
 * 简化的 3D 地图查看器，使用 Java 2D 投影
 */
public class Simple3DViewer extends JFrame {
    private Simple3DRenderer renderer;
    private JPanel renderPanel;
    private JLabel infoLabel;
    private JLabel coordLabel;
    
    private BufferedImage currentImage;
    private boolean[] keys = new boolean[256];
    private int lastMouseX = 0;
    private int lastMouseY = 0;
    private boolean firstMouse = true;
    
    private Thread renderThread;
    private volatile boolean running = false;
    
    public Simple3DViewer(MapRenderer.BlockInfo[][] blockData, int worldMinX, int worldMinZ) {
        setTitle("Minecraft 3D 地图查看器 (Java 2D)");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        
        renderer = new Simple3DRenderer(blockData, worldMinX, worldMinZ);
        
        setupUI();
        setupInputHandlers();
        
        startRendering();
    }
    
    private void setupUI() {
        // 控制面板
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setBackground(new Color(240, 240, 240));
        
        infoLabel = new JLabel("3D 查看器 - WASD 移动, 鼠标看向");
        infoLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        controlPanel.add(infoLabel);
        
        coordLabel = new JLabel("坐标: (0, 100, 0)");
        coordLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        controlPanel.add(coordLabel);
        
        JButton resetButton = new JButton("重置视图");
        resetButton.addActionListener(e -> resetCamera());
        controlPanel.add(resetButton);
        
        add(controlPanel, BorderLayout.NORTH);
        
        // 渲染面板
        renderPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (currentImage != null) {
                    g.drawImage(currentImage, 0, 0, null);
                }
            }
        };
        renderPanel.setBackground(Color.BLACK);
        add(renderPanel, BorderLayout.CENTER);
    }
    
    private void setupInputHandlers() {
        // 键盘
        KeyListener keyListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() < 256) {
                    keys[e.getKeyCode()] = true;
                }
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    dispose();
                }
            }
            
            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() < 256) {
                    keys[e.getKeyCode()] = false;
                }
            }
        };
        
        renderPanel.addKeyListener(keyListener);
        renderPanel.setFocusable(true);
        renderPanel.requestFocusInWindow();
        
        // 鼠标
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                renderPanel.requestFocusInWindow();
                
                if (firstMouse) {
                    lastMouseX = e.getX();
                    lastMouseY = e.getY();
                    firstMouse = false;
                    return;
                }
                
                int dx = e.getX() - lastMouseX;
                int dy = e.getY() - lastMouseY;
                
                renderer.rotateCamera(dx * 0.5f, dy * 0.5f);
                
                lastMouseX = e.getX();
                lastMouseY = e.getY();
            }
            
            @Override
            public void mouseEntered(MouseEvent e) {
                firstMouse = true;
                renderPanel.requestFocusInWindow();
            }
        };
        
        renderPanel.addMouseMotionListener(mouseAdapter);
        renderPanel.addMouseListener(mouseAdapter);
    }
    
    private void startRendering() {
        running = true;
        renderThread = new Thread(() -> {
            while (running) {
                // 处理输入
                handleInput();
                
                // 渲染
                currentImage = renderer.render();
                
                // 更新 UI
                SwingUtilities.invokeLater(() -> {
                    renderPanel.repaint();
                    float[] pos = renderer.getCameraPosition();
                    coordLabel.setText(String.format("坐标: (%.1f, %.1f, %.1f)", pos[0], pos[1], pos[2]));
                });
                
                // 控制帧率
                try {
                    Thread.sleep(16); // ~60 FPS
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        
        renderThread.setName("Simple3D-Renderer");
        renderThread.start();
        
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                running = false;
                try {
                    renderThread.join(2000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        });
    }
    
    private void handleInput() {
        float speed = 1.0f;
        
        if (keys[KeyEvent.VK_W]) {
            renderer.moveCamera(0, 0, -speed);
        }
        if (keys[KeyEvent.VK_S]) {
            renderer.moveCamera(0, 0, speed);
        }
        if (keys[KeyEvent.VK_A]) {
            renderer.moveCamera(-speed, 0, 0);
        }
        if (keys[KeyEvent.VK_D]) {
            renderer.moveCamera(speed, 0, 0);
        }
        if (keys[KeyEvent.VK_SPACE]) {
            renderer.moveCamera(0, speed, 0);
        }
        if (keys[KeyEvent.VK_SHIFT]) {
            renderer.moveCamera(0, -speed, 0);
        }
    }
    
    private void resetCamera() {
        // 设置相机在地图中心上方，面向地图
        renderer.setCameraPosition(256, 150, -200);
    }
}
