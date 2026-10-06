package com.minecraft.selector.gui;

import com.minecraft.selector.core.DynamicRegionManager;
import com.minecraft.selector.core.DynamicRegionManager.RegionTile;
import com.minecraft.selector.utils.LogManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.List;

/**
 * 动态地图画布
 * 支持动态加载和显示地图区域
 */
public class DynamicMapCanvas extends JPanel implements DynamicRegionManager.RegionLoadListener {
    private final LogManager logManager;
    private DynamicRegionManager regionManager;
    
    // 视图参数
    private double scale = 1.0;
    private Point viewOffset = new Point(0, 0);
    private Point lastMousePos = new Point();
    private boolean dragging = false;
    
    // 选区相关
    private boolean selecting = false;
    private Point selectionStart = null;
    private Point selectionEnd = null;
    private Rectangle selectionRect = new Rectangle();
    private Point selectionWorldStart = null;
    private Point selectionWorldEnd = null;
    private boolean hasValidSelection = false;
    private int currentMinX, currentMaxX, currentMinZ, currentMaxZ;
    
    // 选区边框拖拽调整相关
    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_N = 1;
    private static final int HANDLE_S = 2;
    private static final int HANDLE_W = 3;
    private static final int HANDLE_E = 4;
    private static final int HANDLE_NW = 5;
    private static final int HANDLE_NE = 6;
    private static final int HANDLE_SW = 7;
    private static final int HANDLE_SE = 8;
    private static final int RESIZE_HIT_TOLERANCE = 6; // 像素容差
    private int resizeHandle = HANDLE_NONE; // 当前正在拖拽调整的边/角
    
    // 选区回调接口
    public interface SelectionCallback {
        void onSelectionComplete(int minX, int minZ, int maxX, int maxZ);
        void onSelectionConfirmed(int minX, int minZ, int maxX, int maxZ);
    }
    
    private SelectionCallback selectionCallback;
    
    // 渲染参数
    private static final int REGION_SIZE = 512;
    private static final Color LOADING_COLOR = new Color(64, 64, 64);
    private static final Color UNAVAILABLE_COLOR = new Color(32, 32, 32);
    private static final Color GRID_COLOR = new Color(128, 128, 128, 100);
    
    // 状态
    private boolean showGrid = false;
    private boolean showLoadingIndicator = true;
    private Rectangle lastViewport = new Rectangle();
    
    // 防抖相关
    private Timer viewportUpdateTimer;
    private volatile boolean needsViewportUpdate = false;
    private static final int VIEWPORT_UPDATE_DELAY = 200; // 200ms延迟
    
    public DynamicMapCanvas() {
        this.logManager = LogManager.getInstance();
        
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(800, 600));
        
        setupMouseListeners();
        setupKeyListeners();
        
        // 防抖定时器 - 只有在停止移动后才更新视口
        viewportUpdateTimer = new Timer(VIEWPORT_UPDATE_DELAY, e -> {
            if (needsViewportUpdate) {
                needsViewportUpdate = false;
                updateViewportIfNeeded();
            }
        });
        viewportUpdateTimer.setRepeats(false); // 只执行一次
        
        logManager.info("Dynamic map canvas initialized", "DynamicMapCanvas");
    }
    
    /**
     * 设置区域管理器
     */
    public void setRegionManager(DynamicRegionManager regionManager) {
        if (this.regionManager != null) {
            this.regionManager.removeRegionLoadListener(this);
        }
        
        viewportUpdateTimer.stop();
        lastViewport = new Rectangle(); // a new dimension needs its first load even at identical bounds
        this.regionManager = regionManager;
        
        if (regionManager != null) {
            regionManager.addRegionLoadListener(this);
            updateViewportIfNeeded();
        }
        
        repaint();
    }
    
    /**
     * 设置鼠标监听器
     */
    private void setupMouseListeners() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    // 先检查是否点击到已选区域的线框/角点（可拖拽调整）
                    if (hasValidSelection) {
                        int handle = hitTestResizeHandle(e.getPoint());
                        if (handle != HANDLE_NONE) {
                            resizeHandle = handle;
                            return; // 进入调整模式，不启动地图拖拽
                        }
                    }
                    lastMousePos = e.getPoint();
                    dragging = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    // 右键开始选择区域
                    startSelection(e.getPoint());
                }
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (resizeHandle != HANDLE_NONE) {
                        // 结束选区调整：归一化坐标并通知回调
                        normalizeSelection();
                        resizeHandle = HANDLE_NONE;
                        setCursor(Cursor.getDefaultCursor());
                        if (selectionCallback != null) {
                            selectionCallback.onSelectionComplete(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
                        }
                        repaint();
                        return;
                    }
                    dragging = false;
                    setCursor(Cursor.getDefaultCursor());
                } else if (SwingUtilities.isRightMouseButton(e) && selecting) {
                    // 完成右键选择
                    finishSelection(e.getPoint());
                }
            }
        });
        
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (resizeHandle != HANDLE_NONE && hasValidSelection) {
                    // 拖拽调整选区边界
                    Point world = screenToWorldCoordinates(e.getPoint());
                    switch (resizeHandle) {
                        case HANDLE_N: currentMinZ = world.y; break;
                        case HANDLE_S: currentMaxZ = world.y; break;
                        case HANDLE_W: currentMinX = world.x; break;
                        case HANDLE_E: currentMaxX = world.x; break;
                        case HANDLE_NW: currentMinX = world.x; currentMinZ = world.y; break;
                        case HANDLE_NE: currentMaxX = world.x; currentMinZ = world.y; break;
                        case HANDLE_SW: currentMinX = world.x; currentMaxZ = world.y; break;
                        case HANDLE_SE: currentMaxX = world.x; currentMaxZ = world.y; break;
                        default: break;
                    }
                    repaint();
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && dragging) {
                    // 左键拖拽移动地图
                    Point currentPos = e.getPoint();
                    int deltaX = currentPos.x - lastMousePos.x;
                    int deltaY = currentPos.y - lastMousePos.y;
                    
                    viewOffset.x += deltaX;
                    viewOffset.y += deltaY;
                    
                    lastMousePos = currentPos;
                    repaint();
                    requestViewportUpdate(); // 使用防抖更新
                } else if (SwingUtilities.isRightMouseButton(e) && selecting) {
                    // 右键拖拽更新选择区域
                    updateSelection(e.getPoint());
                }
            }
            
            @Override
            public void mouseMoved(MouseEvent e) {
                // 鼠标悬停在线框/角点上时显示调整光标
                if (hasValidSelection && resizeHandle == HANDLE_NONE) {
                    int handle = hitTestResizeHandle(e.getPoint());
                    if (handle != HANDLE_NONE) {
                        setCursor(resizeCursorForHandle(handle));
                    } else {
                        setCursor(Cursor.getDefaultCursor());
                    }
                } else {
                    setCursor(Cursor.getDefaultCursor());
                }
            }
        });
        
        addMouseWheelListener(e -> {
            double oldScale = scale;
            Point mousePos = e.getPoint();
            
            // 计算鼠标在世界坐标中的位置
            double worldX = (mousePos.x - viewOffset.x) / scale;
            double worldY = (mousePos.y - viewOffset.y) / scale;
            
            // 缩放
            if (e.getWheelRotation() < 0) {
                scale *= 1.1;
            } else {
                scale /= 1.1;
            }
            
            // 限制缩放范围
            scale = Math.max(0.1, Math.min(10.0, scale));
            
            // 调整视图偏移以保持鼠标位置不变
            viewOffset.x = (int) (mousePos.x - worldX * scale);
            viewOffset.y = (int) (mousePos.y - worldY * scale);
            
            repaint();
            requestViewportUpdate(); // 使用防抖更新
        });
    }
    
    /**
     * 设置键盘监听器
     */
    private void setupKeyListeners() {
        setFocusable(true);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int moveSpeed = (int) (20 / scale);
                
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_W:
                    case KeyEvent.VK_UP:
                        viewOffset.y += moveSpeed;
                        break;
                    case KeyEvent.VK_S:
                    case KeyEvent.VK_DOWN:
                        viewOffset.y -= moveSpeed;
                        break;
                    case KeyEvent.VK_A:
                    case KeyEvent.VK_LEFT:
                        viewOffset.x += moveSpeed;
                        break;
                    case KeyEvent.VK_D:
                    case KeyEvent.VK_RIGHT:
                        viewOffset.x -= moveSpeed;
                        break;
                    case KeyEvent.VK_G:
                        showGrid = !showGrid;
                        break;
                    case KeyEvent.VK_L:
                        showLoadingIndicator = !showLoadingIndicator;
                        break;
                    case KeyEvent.VK_R:
                        resetView();
                        break;
                }
                repaint();
                requestViewportUpdate(); // 使用防抖更新
            }
        });
    }
    
    /**
     * 重置视图
     */
    public void resetView() {
        scale = 1.0;
        viewOffset = new Point(getWidth() / 2, getHeight() / 2);
        repaint();
    }
    
    /**
     * 适应窗口大小
     * 为避免极远孤立区域(如异常坐标的region)导致地图缩放过小，
     * 使用"main cluster"(与大多数区域相邻的连续区域)来计算边界。
     */
    public void fitToWindow() {
        if (regionManager == null) return;
        
        Set<Point> availableRegions = regionManager.getAvailableRegions();
        if (availableRegions.isEmpty()) return;
        
        // 找出主体区域：找到含区域最多的坐标簇
        // 简单方式：以中位数坐标为中心，选择距离中位数最近的连续区域
        List<Point> regions = new java.util.ArrayList<>(availableRegions);
        
        // 计算中位数坐标
        List<Integer> xs = new java.util.ArrayList<>();
        List<Integer> zs = new java.util.ArrayList<>();
        for (Point p : regions) {
            xs.add(p.x);
            zs.add(p.y);
        }
        java.util.Collections.sort(xs);
        java.util.Collections.sort(zs);
        int medianX = xs.get(xs.size() / 2);
        int medianZ = zs.get(zs.size() / 2);
        
        // 找离中位数最近的点作为主体锚点
        Point anchor = null;
        long minDist = Long.MAX_VALUE;
        for (Point p : regions) {
            long dist = (long)(p.x - medianX) * (p.x - medianX) + (long)(p.y - medianZ) * (p.y - medianZ);
            if (dist < minDist) {
                minDist = dist;
                anchor = p;
            }
        }
        if (anchor == null) return;
        
        // 从锚点出发做连通域（相邻≤1视为连通），保留最大的连通簇
        List<Point> mainCluster = largestConnectedCluster(regions, anchor);
        
        // 计算主体区域边界
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Point region : mainCluster) {
            minX = Math.min(minX, region.x);
            maxX = Math.max(maxX, region.x);
            minZ = Math.min(minZ, region.y);
            maxZ = Math.max(maxZ, region.y);
        }
        
        // 计算总尺寸
        int totalWidth = (maxX - minX + 1) * REGION_SIZE;
        int totalHeight = (maxZ - minZ + 1) * REGION_SIZE;
        
        // 计算适合的缩放比例
        double scaleX = (double) getWidth() / totalWidth;
        double scaleY = (double) getHeight() / totalHeight;
        scale = Math.min(scaleX, scaleY) * 0.9; // 留一些边距
        
        // 居中显示
        int centerWorldX = (minX + maxX) * REGION_SIZE / 2;
        int centerWorldZ = (minZ + maxZ) * REGION_SIZE / 2;
        
        viewOffset.x = getWidth() / 2 - (int) (centerWorldX * scale);
        viewOffset.y = getHeight() / 2 - (int) (centerWorldZ * scale);
        
        repaint();
        requestViewportUpdate();
    }
    
    /**
     * 返回与锚点连通的区域簇（曼哈顿距离≤1视为相邻）
     */
    private List<Point> largestConnectedCluster(List<Point> regions, Point anchor) {
        Set<Point> regionSet = new java.util.HashSet<>(regions);
        Set<Point> visited = new java.util.HashSet<>();
        java.util.Queue<Point> queue = new java.util.ArrayDeque<>();
        queue.add(anchor);
        visited.add(anchor);
        
        while (!queue.isEmpty()) {
            Point current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Point neighbor = new Point(current.x + dx, current.y + dz);
                    if (regionSet.contains(neighbor) && !visited.contains(neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }
        return new java.util.ArrayList<>(visited);
    }
    
    /**
     * 跳转到世界坐标
     * @return true 表示坐标在可用区域内（可能正在加载），false 表示坐标处没有地图数据
     */
    public boolean jumpToWorldCoordinate(Point worldCoord) {
        if (getWidth() <= 0 || getHeight() <= 0) return false;

        // 检查该坐标附近是否存在已加载或可用的区域
        boolean hasDataNearby = false;
        if (regionManager != null) {
            Set<Point> available = regionManager.getAvailableRegions();
            int regionX = (int) Math.floor(worldCoord.x / (double) REGION_SIZE);
            int regionZ = (int) Math.floor(worldCoord.y / (double) REGION_SIZE);
            // 检查目标区域及其 8 邻域，只要有数据就允许跳转
            for (int dx = -1; dx <= 1 && !hasDataNearby; dx++) {
                for (int dz = -1; dz <= 1 && !hasDataNearby; dz++) {
                    if (available.contains(new Point(regionX + dx, regionZ + dz))) {
                        hasDataNearby = true;
                    }
                }
            }
        }

        // 调整视图偏移以将目标坐标置于中心
        viewOffset.x = getWidth() / 2 - (int) (worldCoord.x * scale);
        viewOffset.y = getHeight() / 2 - (int) (worldCoord.y * scale);

        repaint();

        // 立即更新视口（不等防抖），让目标区域的加载尽快开始
        if (regionManager != null) {
            needsViewportUpdate = true;
            viewportUpdateTimer.stop();
            updateViewportIfNeeded();
        }

        return hasDataNearby;
    }

    /**
     * 请求视口更新（防抖）
     */
    private void requestViewportUpdate() {
        needsViewportUpdate = true;
        viewportUpdateTimer.restart();
    }
    
    /**
     * 检查是否需要更新视口
     */
    private void updateViewportIfNeeded() {
        if (regionManager == null) return;
        
        Rectangle currentViewport = calculateWorldViewport();
        
        // 检查视口是否有显著变化（避免微小移动导致重新加载）
        if (hasSignificantViewportChange(currentViewport, lastViewport)) {
            lastViewport = new Rectangle(currentViewport);
            regionManager.updateViewport(currentViewport);
            logManager.debug("Viewport update: " + currentViewport, "DynamicMapCanvas");
        }
    }
    
    /**
     * 检查视口是否有显著变化
     */
    private boolean hasSignificantViewportChange(Rectangle current, Rectangle last) {
        if (last.isEmpty()) return true;
        
        // 计算变化阈值（区域大小的1/4）
        int threshold = REGION_SIZE / 4;
        
        return Math.abs(current.x - last.x) > threshold ||
               Math.abs(current.y - last.y) > threshold ||
               Math.abs(current.width - last.width) > threshold ||
               Math.abs(current.height - last.height) > threshold;
    }
    
    /**
     * 计算当前世界视口
     */
    private Rectangle calculateWorldViewport() {
        // 将屏幕坐标转换为世界坐标
        int worldX = (int) Math.floor(-viewOffset.x / scale);
        int worldY = (int) Math.floor(-viewOffset.y / scale);
        int worldWidth = Math.max(1, (int) Math.ceil(getWidth() / scale));
        int worldHeight = Math.max(1, (int) Math.ceil(getHeight() / scale));
        
        return new Rectangle(worldX, worldY, worldWidth, worldHeight);
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        
        try {
            if (regionManager != null) {
                drawRegions(g2d);
                if (showGrid) {
                    drawGrid(g2d);
                }
                drawSelection(g2d);
                drawStatusInfo(g2d);
            } else {
                drawNoDataMessage(g2d);
            }
        } finally {
            g2d.dispose();
        }
    }
    
    /**
     * 绘制区域
     */
    private void drawRegions(Graphics2D g2d) {
        Rectangle viewport = calculateWorldViewport();
        
        Set<Point> availableRegions = regionManager.getAvailableRegions();
        // Sparse worlds must cost O(existing regions), not O(world bounding box).
        // Avoid copying the complete tile map on each mouse-drag repaint.
        for (Point regionCoord : availableRegions) {
                int regionX = regionCoord.x, regionZ = regionCoord.y;
                long worldX = (long) regionX * REGION_SIZE, worldZ = (long) regionZ * REGION_SIZE;
                if (worldX + REGION_SIZE <= viewport.x || worldZ + REGION_SIZE <= viewport.y ||
                    worldX >= (long)viewport.x + viewport.width ||
                    worldZ >= (long)viewport.y + viewport.height) continue;
                // 计算区域在屏幕上的位置
                int screenX = (int) (worldX * scale + viewOffset.x);
                int screenY = (int) (worldZ * scale + viewOffset.y);
                int screenWidth = (int) (REGION_SIZE * scale);
                int screenHeight = (int) (REGION_SIZE * scale);
                
                RegionTile tile = regionManager.getLoadedRegion(regionCoord);
                if (tile != null) {
                    // 绘制已加载的区域
                    BufferedImage image = tile.getImage();
                    // 使用+1像素重叠来消除缝隙
                    g2d.drawImage(image, screenX, screenY, screenWidth + 1, screenHeight + 1, null);
                    
                } else if (availableRegions.contains(regionCoord)) {
                    if (regionManager.isRegionLoading(regionCoord)) {
                        // 绘制加载中的区域
                        g2d.setColor(LOADING_COLOR);
                        g2d.fillRect(screenX, screenY, screenWidth, screenHeight);
                        
                        if (showLoadingIndicator) {
                            g2d.setColor(Color.WHITE);
                            g2d.setFont(new Font("Arial", Font.BOLD, 12));
                            String text = "Loading...";
                            FontMetrics fm = g2d.getFontMetrics();
                            int textX = screenX + (screenWidth - fm.stringWidth(text)) / 2;
                            int textY = screenY + (screenHeight + fm.getAscent()) / 2;
                            g2d.drawString(text, textX, textY);
                        }
                    } else {
                        // 绘制未加载但可用的区域
                        g2d.setColor(LOADING_COLOR);
                        g2d.fillRect(screenX, screenY, screenWidth, screenHeight);
                        
                        g2d.setColor(Color.GRAY);
                        g2d.drawRect(screenX, screenY, screenWidth - 1, screenHeight - 1);
                    }
                } else {
                    // 绘制不可用的区域（可选）
                    // g2d.setColor(UNAVAILABLE_COLOR);
                    // g2d.fillRect(screenX, screenY, screenWidth, screenHeight);
                }
        }
    }
    
    /**
     * 绘制网格
     */
    private void drawGrid(Graphics2D g2d) {
        g2d.setColor(GRID_COLOR);
        g2d.setStroke(new BasicStroke(1.0f));
        
        Rectangle viewport = calculateWorldViewport();
        
        // 绘制垂直线
        int startRegionX = (int) Math.floor(viewport.x / (double) REGION_SIZE);
        int endRegionX = (int) Math.ceil((viewport.x + viewport.width) / (double) REGION_SIZE);
        
        for (int regionX = startRegionX; regionX <= endRegionX; regionX++) {
            int screenX = (int) (regionX * REGION_SIZE * scale + viewOffset.x);
            g2d.drawLine(screenX, 0, screenX, getHeight());
        }
        
        // 绘制水平线
        int startRegionZ = (int) Math.floor(viewport.y / (double) REGION_SIZE);
        int endRegionZ = (int) Math.ceil((viewport.y + viewport.height) / (double) REGION_SIZE);
        
        for (int regionZ = startRegionZ; regionZ <= endRegionZ; regionZ++) {
            int screenY = (int) (regionZ * REGION_SIZE * scale + viewOffset.y);
            g2d.drawLine(0, screenY, getWidth(), screenY);
        }
    }
    
    /**
     * 绘制选区
     */
    private void drawSelection(Graphics2D g2d) {
        // 绘制当前拖拽的选择矩形
        if (selecting && selectionRect != null && !selectionRect.isEmpty()) {
            g2d.setColor(new Color(0, 120, 215, 100)); // 半透明蓝色
            g2d.fillRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
            
            g2d.setColor(new Color(0, 120, 215)); // 蓝色边框
            g2d.setStroke(new BasicStroke(2.0f));
            g2d.drawRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
        }
        
        // 绘制已确认的选择区域
        if (hasValidSelection && selectionWorldStart != null && selectionWorldEnd != null) {
            Rectangle r = getSelectionScreenRect();
            int x = r.x, y = r.y, width = r.width, height = r.height;
            
            // 绘制选中区域的边框
            g2d.setColor(new Color(255, 0, 0, 150)); // 半透明红色
            g2d.fillRect(x, y, width, height);
            
            g2d.setColor(Color.RED); // 红色边框
            g2d.setStroke(new BasicStroke(3.0f));
            g2d.drawRect(x, y, width, height);
            
            // 绘制4个角的拖拽方块（提示可拖动调整）
            int handleSize = 8;
            g2d.setColor(Color.WHITE);
            g2d.fillRect(x - handleSize / 2, y - handleSize / 2, handleSize, handleSize);
            g2d.fillRect(x + width - handleSize / 2, y - handleSize / 2, handleSize, handleSize);
            g2d.fillRect(x - handleSize / 2, y + height - handleSize / 2, handleSize, handleSize);
            g2d.fillRect(x + width - handleSize / 2, y + height - handleSize / 2, handleSize, handleSize);
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(1.0f));
            g2d.drawRect(x - handleSize / 2, y - handleSize / 2, handleSize, handleSize);
            g2d.drawRect(x + width - handleSize / 2, y - handleSize / 2, handleSize, handleSize);
            g2d.drawRect(x - handleSize / 2, y + height - handleSize / 2, handleSize, handleSize);
            g2d.drawRect(x + width - handleSize / 2, y + height - handleSize / 2, handleSize, handleSize);
            
            // 绘制选区信息
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Arial", Font.BOLD, 12));
            String selectionInfo = String.format("Selection: (%d,%d) to (%d,%d)", 
                currentMinX, currentMinZ, currentMaxX, currentMaxZ);
            g2d.drawString(selectionInfo, x + 5, y + 20);
        }
    }
    
    /**
     * 绘制状态信息
     */
    private void drawStatusInfo(Graphics2D g2d) {
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.PLAIN, 12));
        
        String statusText = String.format("Zoom: %.2f | %s", scale, regionManager.getStatusInfo());
        g2d.drawString(statusText, 10, 20);
        
        String controlsText = "Controls: drag move | scroll zoom | WASD move | G grid | R reset view";
        g2d.drawString(controlsText, 10, getHeight() - 10);
    }
    
    /**
     * 绘制无数据消息
     */
    private void drawNoDataMessage(Graphics2D g2d) {
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.BOLD, 16));
        
        String message = "Please select a Minecraft save first";
        FontMetrics fm = g2d.getFontMetrics();
        int x = (getWidth() - fm.stringWidth(message)) / 2;
        int y = getHeight() / 2;
        
        g2d.drawString(message, x, y);
    }
    
    // RegionLoadListener 实现
    @Override
    public void onRegionLoaded(Point regionCoord, RegionTile tile) {
        SwingUtilities.invokeLater(this::repaint);
    }
    
    @Override
    public void onRegionUnloaded(Point regionCoord) {
        SwingUtilities.invokeLater(this::repaint);
    }
    
    @Override
    public void onLoadingStarted(Point regionCoord) {
        SwingUtilities.invokeLater(this::repaint);
    }
    
    @Override
    public void onLoadingFailed(Point regionCoord, Exception error) {
        SwingUtilities.invokeLater(this::repaint);
        logManager.error("Region load failed: r." + regionCoord.x + "." + regionCoord.y + ".mca", "DynamicMapCanvas", error);
    }
    
    /**
     * 设置选区回调
     */
    public void setSelectionCallback(SelectionCallback callback) {
        this.selectionCallback = callback;
    }
    
    /**
     * 开始选择区域
     */
    private void startSelection(Point point) {
        selectionStart = new Point(point);
        selectionEnd = new Point(point);
        selecting = true;
        selectionRect = new Rectangle();
        repaint();
    }
    
    /**
     * 更新选择区域
     */
    private void updateSelection(Point point) {
        if (selecting && selectionStart != null) {
            selectionEnd = new Point(point);
            
            // 计算选择矩形
            int x = Math.min(selectionStart.x, selectionEnd.x);
            int y = Math.min(selectionStart.y, selectionEnd.y);
            int width = Math.abs(selectionEnd.x - selectionStart.x);
            int height = Math.abs(selectionEnd.y - selectionStart.y);
            
            selectionRect = new Rectangle(x, y, width, height);
            repaint();
        }
    }
    
    /**
     * 完成选择区域
     */
    private void finishSelection(Point point) {
        if (selecting && selectionStart != null) {
            selectionEnd = new Point(point);
            
            // 将屏幕坐标转换为世界坐标
            Point worldStart = screenToWorldCoordinates(selectionStart);
            Point worldEnd = screenToWorldCoordinates(selectionEnd);
            
            if (worldStart != null && worldEnd != null) {
                // 保存世界坐标用于后续绘制
                selectionWorldStart = new Point(worldStart);
                selectionWorldEnd = new Point(worldEnd);
                
                // 计算世界坐标范围
                currentMinX = Math.min(worldStart.x, worldEnd.x);
                currentMaxX = Math.max(worldStart.x, worldEnd.x);
                currentMinZ = Math.min(worldStart.y, worldEnd.y);
                currentMaxZ = Math.max(worldStart.y, worldEnd.y);
                hasValidSelection = true;
                
                // 调用回调
                if (selectionCallback != null) {
                    selectionCallback.onSelectionComplete(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
                }
                
                System.out.printf("Selected area: world (%d, %d) to (%d, %d) - press Enter to confirm\n",
                    currentMinX, currentMinZ, currentMaxX, currentMaxZ);
            }
        }
        
        selecting = false;
        repaint();
    }
    
    /**
     * 归一化选区坐标（保证 min <= max）
     */
    private void normalizeSelection() {
        if (currentMinX > currentMaxX) { int t = currentMinX; currentMinX = currentMaxX; currentMaxX = t; }
        if (currentMinZ > currentMaxZ) { int t = currentMinZ; currentMinZ = currentMaxZ; currentMaxZ = t; }
        selectionWorldStart = new Point(currentMinX, currentMinZ);
        selectionWorldEnd = new Point(currentMaxX, currentMaxZ);
    }
    
    /**
     * 计算选区在屏幕上的矩形
     */
    private Rectangle getSelectionScreenRect() {
        Point screenStart = worldToScreenCoordinates(new Point(currentMinX, currentMinZ));
        Point screenEnd = worldToScreenCoordinates(new Point(currentMaxX, currentMaxZ));
        int x = Math.min(screenStart.x, screenEnd.x);
        int y = Math.min(screenStart.y, screenEnd.y);
        int width = Math.abs(screenEnd.x - screenStart.x);
        int height = Math.abs(screenEnd.y - screenStart.y);
        return new Rectangle(x, y, width, height);
    }
    
    /**
     * 检测鼠标是否命中选区的边或角
     * @return 对应的 handle，未命中返回 HANDLE_NONE
     */
    private int hitTestResizeHandle(Point screenPoint) {
        if (!hasValidSelection) return HANDLE_NONE;
        Rectangle r = getSelectionScreenRect();
        if (r.width < 2 * RESIZE_HIT_TOLERANCE || r.height < 2 * RESIZE_HIT_TOLERANCE) {
            // 选区太小，只能整体判断
            Rectangle grown = new Rectangle(r);
            grown.grow(RESIZE_HIT_TOLERANCE, RESIZE_HIT_TOLERANCE);
            if (r.contains(screenPoint) || grown.contains(screenPoint)) {
                return HANDLE_SE;
            }
            return HANDLE_NONE;
        }
        
        int left = r.x, right = r.x + r.width;
        int top = r.y, bottom = r.y + r.height;
        int px = screenPoint.x, py = screenPoint.y;
        int tol = RESIZE_HIT_TOLERANCE;
        
        boolean nearLeft = Math.abs(px - left) <= tol;
        boolean nearRight = Math.abs(px - right) <= tol;
        boolean nearTop = Math.abs(py - top) <= tol;
        boolean nearBottom = Math.abs(py - bottom) <= tol;
        
        // 角优先
        if (nearLeft && nearTop) return HANDLE_NW;
        if (nearRight && nearTop) return HANDLE_NE;
        if (nearLeft && nearBottom) return HANDLE_SW;
        if (nearRight && nearBottom) return HANDLE_SE;
        
        // 边（仅当在边的长度范围内）
        if (nearTop && px > left + tol && px < right - tol) return HANDLE_N;
        if (nearBottom && px > left + tol && px < right - tol) return HANDLE_S;
        if (nearLeft && py > top + tol && py < bottom - tol) return HANDLE_W;
        if (nearRight && py > top + tol && py < bottom - tol) return HANDLE_E;
        
        return HANDLE_NONE;
    }
    
    /**
     * 根据 handle 返回对应的调整光标
     */
    private Cursor resizeCursorForHandle(int handle) {
        switch (handle) {
            case HANDLE_N: case HANDLE_S: return Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
            case HANDLE_W: case HANDLE_E: return Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
            case HANDLE_NW: case HANDLE_SE: return Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
            case HANDLE_NE: case HANDLE_SW: return Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
            default: return Cursor.getDefaultCursor();
        }
    }
    
    /**
     * 屏幕坐标转世界坐标
     */
    private Point screenToWorldCoordinates(Point screenPoint) {
        int worldX = (int) Math.floor(((double) screenPoint.x - viewOffset.x) / scale);
        int worldY = (int) Math.floor(((double) screenPoint.y - viewOffset.y) / scale);
        return new Point(worldX, worldY);
    }
    
    /**
     * 世界坐标转屏幕坐标
     */
    private Point worldToScreenCoordinates(Point worldPoint) {
        int screenX = (int) (worldPoint.x * scale + viewOffset.x);
        int screenY = (int) (worldPoint.y * scale + viewOffset.y);
        return new Point(screenX, screenY);
    }
    
    /**
     * 获取当前选区
     */
    public Rectangle getSelection() {
        if (hasValidSelection) {
            return new Rectangle(currentMinX, currentMinZ, 
                currentMaxX - currentMinX, currentMaxZ - currentMinZ);
        }
        return null;
    }
    
    /**
     * 确认当前选区
     */
    public void confirmCurrentSelection() {
        if (hasValidSelection && selectionCallback != null) {
            selectionCallback.onSelectionConfirmed(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
        }
    }
    
    /**
     * 清除选区
     */
    public void clearSelection() {
        hasValidSelection = false;
        selecting = false;
        selectionStart = null;
        selectionEnd = null;
        selectionWorldStart = null;
        selectionWorldEnd = null;
        selectionRect = new Rectangle();
        resizeHandle = HANDLE_NONE;
        repaint();
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (regionManager != null) {
            regionManager.removeRegionLoadListener(this);
        }
        if (viewportUpdateTimer != null) {
            viewportUpdateTimer.stop();
        }
    }
}