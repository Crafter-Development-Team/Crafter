package com.minecraft.selector.gui;

import com.minecraft.selector.render3d.*;
import com.minecraft.selector.core.MapRenderer;
import org.lwjgl.glfw.GLFW;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * 3D 地图查看器窗口
 */
public class Viewer3D extends JFrame {
    private Renderer3D renderer;
    private Map3DManager map3DManager;
    private Thread renderThread;
    private volatile boolean running = false;
    
    private JLabel infoLabel;
    private JLabel coordLabel;
    private JSlider lodSlider;
    
    private int lodLevel = 0;
    
    public Viewer3D(MapRenderer.BlockInfo[][] blockData, int worldMinX, int worldMinZ) {
        setTitle("Minecraft 3D 地图查看器");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        
        map3DManager = new Map3DManager();
        map3DManager.loadBlockData(blockData, worldMinX, worldMinZ);
        
        setupUI();
        setupWindowListener();
        
        startRendering();
    }
    
    private void setupUI() {
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setBackground(new Color(240, 240, 240));
        
        infoLabel = new JLabel("3D 查看器 - WASD 移动, 鼠标看向, Space/Shift 上下");
        infoLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        controlPanel.add(infoLabel);
        
        controlPanel.add(new JSeparator(JSeparator.VERTICAL));
        
        controlPanel.add(new JLabel("LOD 级别:"));
        lodSlider = new JSlider(0, 4, 0);
        lodSlider.setPreferredSize(new Dimension(150, 40));
        lodSlider.addChangeListener(e -> {
            lodLevel = lodSlider.getValue();
            System.out.println("LOD 级别: " + lodLevel);
        });
        controlPanel.add(lodSlider);
        
        coordLabel = new JLabel("坐标: (0, 64, 0)");
        coordLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        controlPanel.add(coordLabel);
        
        JButton resetButton = new JButton("重置视图");
        resetButton.addActionListener(e -> resetCamera());
        controlPanel.add(resetButton);
        
        JButton exportButton = new JButton("导出选择");
        exportButton.addActionListener(e -> exportSelection());
        controlPanel.add(exportButton);
        
        add(controlPanel, BorderLayout.NORTH);
    }
    
    private void setupWindowListener() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopRendering();
            }
        });
    }
    
    private void startRendering() {
        if (running) {
            return;
        }
        
        running = true;
        renderThread = new Thread(() -> {
            try {
                // 调试：检查方块数据
                MapRenderer.BlockInfo[][] blockData = map3DManager.getBlockData();
                if (blockData != null) {
                    System.out.println("方块数据大小: " + blockData.length + "x" + blockData[0].length);
                    int nonAirCount = 0;
                    for (MapRenderer.BlockInfo[] row : blockData) {
                        for (MapRenderer.BlockInfo block : row) {
                            if (block != null && !block.getBlockId().equals("air")) {
                                nonAirCount++;
                            }
                        }
                    }
                    System.out.println("非空气方块数: " + nonAirCount);
                } else {
                    System.err.println("方块数据为 null");
                }
                
                renderer = new Renderer3D();
                
                // 初始化网格
                System.out.println("正在构建网格...");
                map3DManager.buildMesh(renderer.getBlockMesh(), lodLevel);
                System.out.println("网格构建完成");
                
                renderer.run(new Renderer3D.RenderCallback() {
                    @Override
                    public void onUpdate(Renderer3D r) {
                        // 更新坐标显示
                        org.joml.Vector3f pos = r.getCamera().getPosition();
                        SwingUtilities.invokeLater(() -> {
                            coordLabel.setText(String.format("坐标: (%.1f, %.1f, %.1f)", pos.x, pos.y, pos.z));
                        });
                    }
                    
                    @Override
                    public void onRender(Renderer3D r) {
                        // 检查 LOD 级别是否改变
                        // 这里可以添加动态 LOD 调整
                    }
                });
            } catch (Exception e) {
                System.err.println("3D 渲染错误: " + e.getMessage());
                e.printStackTrace();
            } finally {
                running = false;
            }
        });
        
        renderThread.setName("3D-Viewer");
        renderThread.start();
    }
    
    private void stopRendering() {
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
    
    private void resetCamera() {
        if (renderer != null) {
            int[] bounds = map3DManager.getWorldBounds();
            float centerX = (bounds[0] + bounds[2]) / 2.0f;
            float centerZ = (bounds[1] + bounds[3]) / 2.0f;
            renderer.getCamera().setPosition(centerX, 100, centerZ);
        }
    }
    
    private void exportSelection() {
        int[] selection = map3DManager.getSelection();
        if (selection != null) {
            System.out.println("导出选择: " + selection[0] + ", " + selection[1] + ", " + selection[2] + ", " + selection[3]);
            JOptionPane.showMessageDialog(this, 
                "选择区域: (" + selection[0] + ", " + selection[1] + ") 到 (" + selection[2] + ", " + selection[3] + ")",
                "导出选择",
                JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this, "请先选择一个区域", "提示", JOptionPane.WARNING_MESSAGE);
        }
    }
}
