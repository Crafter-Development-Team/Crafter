package com.minecraft.selector.gui;

import com.minecraft.selector.render3d.*;
import com.minecraft.selector.core.MapRenderer;
import org.lwjgl.glfw.GLFW;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * 3D 画布组件，用于在 Swing 中显示 3D 地图
 */
public class Canvas3D extends JPanel {
    private Map3DManager map3DManager;
    private Renderer3D renderer;
    private Thread renderThread;
    private volatile boolean running = false;
    private volatile boolean needsRebuild = false;
    
    private int lodLevel = 0;
    private int currentMinX = -1;
    private int currentMinZ = -1;
    private int currentMaxX = -1;
    private int currentMaxZ = -1;
    
    private SelectionCallback selectionCallback;
    
    public interface SelectionCallback {
        void onSelectionComplete(int minX, int minZ, int maxX, int maxZ);
        void onSelectionConfirmed(int minX, int minZ, int maxX, int maxZ);
    }
    
    public Canvas3D() {
        setPreferredSize(new Dimension(1200, 800));
        setBackground(Color.BLACK);
        
        map3DManager = new Map3DManager();
        
        setupMouseListeners();
    }
    
    private void setupMouseListeners() {
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    startSelection(e.getPoint());
                }
            }
            
            @Override
            public void mouseDragged(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    updateSelection(e.getPoint());
                }
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    finishSelection(e.getPoint());
                }
            }
        };
        
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && currentMinX != -1) {
                    if (selectionCallback != null) {
                        selectionCallback.onSelectionConfirmed(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    clearSelection();
                } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                    lodLevel = Math.max(0, lodLevel - 1);
                    needsRebuild = true;
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    lodLevel = Math.min(4, lodLevel + 1);
                    needsRebuild = true;
                }
            }
        });
        
        setFocusable(true);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
            }
        });
    }
    
    /**
     * 加载方块数据并启动 3D 渲染
     */
    public void loadBlockData(MapRenderer.BlockInfo[][] blockData, int worldMinX, int worldMinZ) {
        map3DManager.loadBlockData(blockData, worldMinX, worldMinZ);
        needsRebuild = true;
        
        if (!running) {
            startRendering();
        }
    }
    
    /**
     * 启动渲染线程
     */
    private void startRendering() {
        if (running) {
            return;
        }
        
        running = true;
        renderThread = new Thread(() -> {
            try {
                renderer = new Renderer3D();
                renderer.setWindowSize(getWidth(), getHeight());
                
                renderer.run(new Renderer3D.RenderCallback() {
                    @Override
                    public void onUpdate(Renderer3D r) {
                        // 更新回调
                    }
                    
                    @Override
                    public void onRender(Renderer3D r) {
                        // 渲染回调
                        if (needsRebuild) {
                            map3DManager.buildMesh(r.getBlockMesh(), lodLevel);
                            needsRebuild = false;
                        }
                    }
                });
            } catch (Exception e) {
                System.err.println("3D 渲染错误: " + e.getMessage());
                e.printStackTrace();
            } finally {
                running = false;
            }
        });
        
        renderThread.setName("3D-Renderer");
        renderThread.start();
    }
    
    /**
     * 停止渲染
     */
    public void stopRendering() {
        running = false;
        if (renderer != null) {
            GLFW.glfwSetWindowShouldClose(renderer.getWindow(), true);
        }
        if (renderThread != null) {
            try {
                renderThread.join(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 设置选择回调
     */
    public void setSelectionCallback(SelectionCallback callback) {
        this.selectionCallback = callback;
    }
    
    /**
     * 开始选择
     */
    private void startSelection(Point point) {
        System.out.println("3D 选择开始: " + point);
    }
    
    /**
     * 更新选择
     */
    private void updateSelection(Point point) {
        System.out.println("3D 选择更新: " + point);
    }
    
    /**
     * 完成选择
     */
    private void finishSelection(Point point) {
        System.out.println("3D 选择完成: " + point);
    }
    
    /**
     * 清除选择
     */
    public void clearSelection() {
        map3DManager.clearSelection();
        currentMinX = -1;
        currentMinZ = -1;
        currentMaxX = -1;
        currentMaxZ = -1;
    }
    
    /**
     * 获取相机位置
     */
    public float[] getCameraPosition() {
        if (renderer != null) {
            org.joml.Vector3f pos = renderer.getCamera().getPosition();
            return new float[]{pos.x, pos.y, pos.z};
        }
        return new float[]{0, 64, 0};
    }
    
    /**
     * 设置相机位置
     */
    public void setCameraPosition(float x, float y, float z) {
        if (renderer != null) {
            renderer.getCamera().setPosition(x, y, z);
        }
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        if (!running) {
            Graphics2D g2d = (Graphics2D) g;
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Arial", Font.PLAIN, 16));
            g2d.drawString("3D 渲染器加载中...", 50, 50);
        }
    }
}
