package com.minecraft.selector.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.swing.Timer;

/**
 * 地图画布组件
 * 用于显示和交互Minecraft地图
 */
public class MapCanvas extends JPanel {

    // 缩放限制常量
    private static final double MIN_SCALE = 0.1;
    private static final double MAX_SCALE = 10.0;

    private BufferedImage image;
    private double scale = 1.0;
    private Point imageOffset = new Point(0, 0);
    private Rectangle selectionRect;
    private Point selectionStart;
    private Point selectionEnd;
    private boolean dragging = false;
    private boolean selecting = false;
    private Point lastMousePos;

    // 选择回调接口
    public interface SelectionCallback {
        void onSelectionComplete(int minX, int minZ, int maxX, int maxZ);
        void onSelectionConfirmed(int minX, int minZ, int maxX, int maxZ);
    }

    // 当前选择的坐标
    private int currentMinX, currentMinZ, currentMaxX, currentMaxZ;
    private boolean hasValidSelection = false;

    // 选择的世界坐标（用于绘制时转换回屏幕坐标）
    private Point selectionWorldStart, selectionWorldEnd;

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

    // 世界坐标映射信息
    private int worldMinX = 0, worldMinZ = 0;  // 地图对应的世界坐标起点
    private double pixelsPerBlock = 1.0;       // 每个方块对应的像素数

    // 用于检测坐标系统变化的缓存
    private int lastWorldMinX = 0, lastWorldMinZ = 0;

    // 视野管理
    private ViewportManager viewportManager;
    private Timer viewportUpdateTimer;
    private Rectangle lastViewport = new Rectangle();
    private Point lastViewCenter = new Point(); // 记录上次的视图中心点
    private double lastScale = 1.0; // 记录上次的缩放级别
    private boolean isZooming = false; // 标记是否正在缩放
    private Timer zoomEndTimer; // 缩放结束检测定时器

    // 视野管理回调接口
    public interface ViewportCallback {
        void onViewportChanged(int minWorldX, int minWorldZ, int maxWorldX, int maxWorldZ);
        void loadRegion(int regionX, int regionZ);
        void unloadRegion(int regionX, int regionZ);
    }

    private ViewportCallback viewportCallback;

    private SelectionCallback selectionCallback;
    
    public MapCanvas() {
        setPreferredSize(new Dimension(800, 600));
        setBackground(Color.WHITE);
        
        setupMouseListeners();
    }
    
    private void setupMouseListeners() {
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    // 右键开始选择区域
                    startSelection(e.getPoint());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    // 先检查是否点击到已选区域的线框/角点（可拖拽调整）
                    if (hasValidSelection) {
                        int handle = hitTestResizeHandle(e.getPoint());
                        if (handle != HANDLE_NONE) {
                            resizeHandle = handle;
                            return; // 进入调整模式，不启动地图拖拽
                        }
                    }
                    // 左键开始拖拽地图
                    startDrag(e.getPoint());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (resizeHandle != HANDLE_NONE && hasValidSelection) {
                    // 拖拽调整选区边界
                    Point world = screenToWorldCoordinates(e.getPoint());
                    if (world != null) {
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
                    }
                    return;
                }
                if (SwingUtilities.isRightMouseButton(e) && selecting) {
                    // 右键拖拽更新选择区域
                    updateSelection(e.getPoint());
                } else if (SwingUtilities.isLeftMouseButton(e) && dragging) {
                    // 左键拖拽移动地图
                    updateDrag(e.getPoint());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) && selecting) {
                    // 完成右键选择
                    finishSelection(e.getPoint());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    if (resizeHandle != HANDLE_NONE) {
                        // 结束选区调整：归一化坐标并通知回调
                        normalizeSelection();
                        resizeHandle = HANDLE_NONE;
                        if (selectionCallback != null) {
                            selectionCallback.onSelectionComplete(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
                        }
                        repaint();
                        return;
                    }
                    if (dragging) {
                        // 完成左键拖拽
                        finishDrag();
                    }
                }
            }
            
            @Override
            public void mouseMoved(MouseEvent e) {
                // 更新鼠标坐标显示
                updateMouseCoordinates(e.getPoint());
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
            
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                // 鼠标滚轮缩放
                handleMouseWheel(e);
            }
        };
        
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        addMouseWheelListener(mouseHandler);

        // 添加键盘监听器
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && hasValidSelection) {
                    // 按下Enter键确认选择
                    if (selectionCallback != null) {
                        selectionCallback.onSelectionConfirmed(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    // 按下Escape键清除选择
                    clearSelection();
                    hasValidSelection = false;
                }
            }
        });

        // 设置焦点以接收键盘事件
        setFocusable(true);

        // 鼠标点击时获得焦点
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
            }
        });

        // 初始化视野更新定时器（每500ms检查一次视野变化）
        viewportUpdateTimer = new Timer(500, e -> updateViewportIfNeeded());
        viewportUpdateTimer.start();
    }
    
    /**
     * 设置要显示的图像 - 完全不调整视图
     */
    public void setImage(BufferedImage image) {
        BufferedImage oldImage = this.image;
        this.image = image;

        // 调试信息
        if (oldImage == null && image != null) {
            System.out.printf("MapCanvas: first image set %dx%d, offset: (%d,%d), scale: %.2f\n",
                image.getWidth(), image.getHeight(), imageOffset.x, imageOffset.y, scale);
        } else if (oldImage != null && image != null) {
            System.out.printf("MapCanvas: update image %dx%d -> %dx%d, offset kept: (%d,%d), scale: %.2f\n",
                oldImage.getWidth(), oldImage.getHeight(),
                image.getWidth(), image.getHeight(),
                imageOffset.x, imageOffset.y, scale);
        }

        repaint();
    }

    /**
     * 设置图像并保持世界坐标中心点不变
     * 用于动态地图更新，避免视角跳动
     */
    public void setImageKeepWorldCenter(BufferedImage newImage, int newWorldMinX, int newWorldMinZ) {
        if (newImage == null) {
            setImage(null);
            return;
        }

        BufferedImage oldImage = this.image;

        // 如果是第一次设置图像，直接设置并居中
        if (oldImage == null) {
            this.image = newImage;
            setWorldCoordinateMapping(newWorldMinX, newWorldMinZ, 1.0);
            centerImage();
            repaint();
            return;
        }

        // 保存当前的视图状态
        Point currentViewCenter = getViewCenter();
        double currentScale = this.scale;
        
        // 保存当前的世界坐标映射
        int oldWorldMinX = this.worldMinX;
        int oldWorldMinZ = this.worldMinZ;
        double oldPixelsPerBlock = this.pixelsPerBlock;

        // 检测坐标系统是否发生了变化
        boolean coordinateSystemChanged = (newWorldMinX != oldWorldMinX || newWorldMinZ != oldWorldMinZ);

        // 更新图像和世界坐标映射
        this.image = newImage;
        setWorldCoordinateMapping(newWorldMinX, newWorldMinZ, 1.0);

        // 如果坐标系统没有变化，保持视图中心不变
        if (!coordinateSystemChanged && currentViewCenter != null) {
            // 恢复缩放级别
            this.scale = currentScale;
            
            // 设置视图中心到相同的世界坐标位置
            setViewCenter(currentViewCenter);
            
            System.out.printf("MapCanvas: update image keeping view center %dx%d -> %dx%d\n",
                oldImage.getWidth(), oldImage.getHeight(),
                newImage.getWidth(), newImage.getHeight());
        } else if (coordinateSystemChanged && currentViewCenter != null) {
            // 如果坐标系统发生了变化，需要重新计算视图中心
            
            // 恢复缩放级别
            this.scale = currentScale;
            
            // 当前视图中心的世界坐标（使用旧的坐标映射）
            double worldCenterX = oldWorldMinX + currentViewCenter.x / oldPixelsPerBlock;
            double worldCenterZ = oldWorldMinZ + currentViewCenter.y / oldPixelsPerBlock;

            // 计算新图像中对应的像素坐标（使用新的坐标映射）
            double newImageCenterX = (worldCenterX - newWorldMinX) * pixelsPerBlock;
            double newImageCenterY = (worldCenterZ - newWorldMinZ) * pixelsPerBlock;

            // 检查新的中心点是否在图像范围内
            if (newImageCenterX >= 0 && newImageCenterX < newImage.getWidth() &&
                newImageCenterY >= 0 && newImageCenterY < newImage.getHeight()) {
                // 设置视图中心到相同的世界坐标位置
                setViewCenter(new Point((int)newImageCenterX, (int)newImageCenterY));

                System.out.printf("MapCanvas: update image keeping world center (%.1f, %.1f) %dx%d -> %dx%d, new image coords (%.1f, %.1f) [coordinate system changed]\n",
                    worldCenterX, worldCenterZ,
                    oldImage.getWidth(), oldImage.getHeight(),
                    newImage.getWidth(), newImage.getHeight(),
                    newImageCenterX, newImageCenterY);
            } else {
                // 如果计算出的中心点超出了新图像范围，则居中显示
                centerImage();
                System.out.printf("MapCanvas: world center (%.1f, %.1f) exceeds new image bounds, centering instead. New image coords (%.1f, %.1f) out of range [0,0,%d,%d] [coordinate system changed]\n",
                    worldCenterX, worldCenterZ,
                    newImageCenterX, newImageCenterY,
                    newImage.getWidth(), newImage.getHeight());
            }
        } else {
            // 其他情况，居中显示
            centerImage();
            System.out.printf("MapCanvas: center new image %dx%d\n",
                newImage.getWidth(), newImage.getHeight());
        }

        repaint();
    }

    /**
     * 获取当前显示的图像
     */
    public BufferedImage getImage() {
        return this.image;
    }

    /**
     * 设置要显示的图像并重置视图
     */
    public void setImageAndResetView(BufferedImage image) {
        this.image = image;
        if (image != null) {
            // 重置视图
            resetView();
        }
        repaint();
    }
    
    /**
     * 设置缩放比例
     */
    public void setScale(double scale) {
        this.scale = Math.max(0.1, Math.min(10.0, scale));
        repaint();
    }
    
    /**
     * 获取当前缩放比例
     */
    public double getScale() {
        return scale;
    }
    
    /**
     * 重置视图
     */
    public void resetView() {
        scale = 1.0;
        centerImage();
        selectionRect = null;
        repaint();
    }

    /**
     * 将图像居中显示
     */
    public void centerImage() {
        if (image != null) {
            int panelWidth = getWidth();
            int panelHeight = getHeight();
            int imageWidth = (int) (image.getWidth() * scale);
            int imageHeight = (int) (image.getHeight() * scale);

            imageOffset.x = (panelWidth - imageWidth) / 2;
            imageOffset.y = (panelHeight - imageHeight) / 2;
        } else {
            imageOffset.setLocation(0, 0);
        }
    }
    

    
    /**
     * 拖拽地图
     */
    private void dragMap(Point point) {
        if (dragging && lastMousePos != null) {
            int dx = point.x - lastMousePos.x;
            int dy = point.y - lastMousePos.y;
            
            imageOffset.translate(dx, dy);
            lastMousePos = new Point(point);
            repaint();
        }
    }
    

    
    /**
     * 更新鼠标坐标显示
     */
    private void updateMouseCoordinates(Point point) {
        if (image != null) {
            // 将屏幕坐标转换为图像坐标
            Point imagePoint = screenToImageCoordinates(point);
            
            // 这里可以触发坐标更新事件
            // 暂时打印到控制台
            if (imagePoint.x >= 0 && imagePoint.x < image.getWidth() && 
                imagePoint.y >= 0 && imagePoint.y < image.getHeight()) {
                // System.out.println("Image coords: " + imagePoint);
            }
        }
    }
    
    /**
     * 处理鼠标滚轮事件
     */
    private void handleMouseWheel(MouseWheelEvent e) {
        if (image != null) {
            double oldScale = scale;

            if (e.getWheelRotation() < 0) {
                // 向上滚动 - 放大
                scale = Math.min(10.0, scale * 1.2);
            } else {
                // 向下滚动 - 缩小
                scale = Math.max(0.1, scale / 1.2);
            }

            if (scale != oldScale) {
                // 标记正在缩放，暂时禁用视野更新
                isZooming = true;

                // 以鼠标位置为中心缩放
                Point mousePos = e.getPoint();
                double scaleRatio = scale / oldScale;

                // 计算鼠标在图像中的位置（缩放前）
                double imageMouseX = (mousePos.x - imageOffset.x) / oldScale;
                double imageMouseY = (mousePos.y - imageOffset.y) / oldScale;

                // 计算新的偏移量，使鼠标位置保持不变
                imageOffset.x = (int) (mousePos.x - imageMouseX * scale);
                imageOffset.y = (int) (mousePos.y - imageMouseY * scale);

                // 重置缩放结束定时器
                if (zoomEndTimer != null) {
                    zoomEndTimer.stop();
                }
                zoomEndTimer = new Timer(1000, evt -> {
                    isZooming = false;
                    System.out.println("Zoom ended, resuming viewport updates");
                });
                zoomEndTimer.setRepeats(false);
                zoomEndTimer.start();

                repaint();
            }
        }
    }
    
    /**
     * 将屏幕坐标转换为图像坐标
     */
    private Point screenToImageCoordinates(Point screenPoint) {
        if (image == null) {
            return new Point(0, 0);
        }
        
        int imageX = (int) ((screenPoint.x - imageOffset.x) / scale);
        int imageY = (int) ((screenPoint.y - imageOffset.y) / scale);
        
        return new Point(imageX, imageY);
    }
    
    /**
     * 将图像坐标转换为屏幕坐标
     */
    private Point imageToScreenCoordinates(Point imagePoint) {
        if (image == null) {
            return new Point(0, 0);
        }
        
        int screenX = (int) (imagePoint.x * scale + imageOffset.x);
        int screenY = (int) (imagePoint.y * scale + imageOffset.y);
        
        return new Point(screenX, screenY);
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        Graphics2D g2d = (Graphics2D) g.create();
        // 设置像素完美渲染，禁用插值以保持像素艺术风格
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        
        if (image != null) {
            // 绘制图像
            int scaledWidth = (int) (image.getWidth() * scale);
            int scaledHeight = (int) (image.getHeight() * scale);
            
            g2d.drawImage(image, imageOffset.x, imageOffset.y, scaledWidth, scaledHeight, null);
            
            // 绘制选择框 - 根据当前缩放和偏移调整
            if (selecting && selectionStart != null && selectionEnd != null) {
                // 正在选择时，直接使用屏幕坐标
                int x = Math.min(selectionStart.x, selectionEnd.x);
                int y = Math.min(selectionStart.y, selectionEnd.y);
                int width = Math.abs(selectionEnd.x - selectionStart.x);
                int height = Math.abs(selectionEnd.y - selectionStart.y);

                g2d.setColor(Color.RED);
                g2d.setStroke(new BasicStroke(2));
                g2d.drawRect(x, y, width, height);
            } else if (selectionWorldStart != null && selectionWorldEnd != null) {
                // 选择完成后，使用世界坐标转换为当前屏幕坐标
                Point scaledStart = worldToScreenCoordinates(selectionWorldStart);
                Point scaledEnd = worldToScreenCoordinates(selectionWorldEnd);

                if (scaledStart != null && scaledEnd != null) {
                    int x = Math.min(scaledStart.x, scaledEnd.x);
                    int y = Math.min(scaledStart.y, scaledEnd.y);
                    int width = Math.abs(scaledEnd.x - scaledStart.x);
                    int height = Math.abs(scaledEnd.y - scaledStart.y);

                    g2d.setColor(Color.RED);
                    g2d.setStroke(new BasicStroke(2));
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
                }
            }
        } else {
            // 没有图像时显示提示
            g2d.setColor(Color.GRAY);
            String message = "No map image loaded";
            FontMetrics fm = g2d.getFontMetrics();
            int x = (getWidth() - fm.stringWidth(message)) / 2;
            int y = getHeight() / 2;
            g2d.drawString(message, x, y);
        }
        
        g2d.dispose();
    }
    
    /**
     * 设置选择回调
     */
    public void setSelectionCallback(SelectionCallback callback) {
        this.selectionCallback = callback;
    }

    /**
     * 设置世界坐标映射信息
     * @param worldMinX 地图左上角对应的世界X坐标
     * @param worldMinZ 地图左上角对应的世界Z坐标
     * @param pixelsPerBlock 每个方块对应的像素数
     */
    public void setWorldCoordinateMapping(int worldMinX, int worldMinZ, double pixelsPerBlock) {
        this.worldMinX = worldMinX;
        this.worldMinZ = worldMinZ;
        this.pixelsPerBlock = pixelsPerBlock;
        System.out.printf("Set world coordinate mapping: origin(%d, %d), px/block=%.2f\n",
            worldMinX, worldMinZ, pixelsPerBlock);
    }

    /**
     * 获取当前视图中心点（图像坐标）
     */
    public Point getViewCenter() {
        if (image == null) return null;

        int centerX = (int) ((getWidth() / 2.0 - imageOffset.x) / scale);
        int centerY = (int) ((getHeight() / 2.0 - imageOffset.y) / scale);
        return new Point(centerX, centerY);
    }

    /**
     * 设置视图中心点（图像坐标）
     */
    public void setViewCenter(Point center) {
        if (image == null || center == null) return;

        imageOffset.x = (int) (getWidth() / 2.0 - center.x * scale);
        imageOffset.y = (int) (getHeight() / 2.0 - center.y * scale);

        // 确保偏移量在合理范围内
        clampImageOffset();
        repaint();
    }

    /**
     * 限制图像偏移量在合理范围内
     */
    private void clampImageOffset() {
        if (image == null) return;

        int scaledWidth = (int) (image.getWidth() * scale);
        int scaledHeight = (int) (image.getHeight() * scale);
        int panelWidth = getWidth();
        int panelHeight = getHeight();

        // 限制偏移量，确保图像不会完全移出视野
        if (scaledWidth > panelWidth) {
            imageOffset.x = Math.max(panelWidth - scaledWidth, Math.min(0, imageOffset.x));
        } else {
            imageOffset.x = (panelWidth - scaledWidth) / 2;
        }

        if (scaledHeight > panelHeight) {
            imageOffset.y = Math.max(panelHeight - scaledHeight, Math.min(0, imageOffset.y));
        } else {
            imageOffset.y = (panelHeight - scaledHeight) / 2;
        }
    }

    /**
     * 获取当前缩放级别
     */
    public double getZoomLevel() {
        return scale;
    }

    /**
     * 设置缩放级别
     */
    public void setZoomLevel(double zoom) {
        if (zoom <= 0) return;

        // 保存当前中心点
        Point center = getViewCenter();

        // 设置新的缩放级别
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, zoom));

        // 恢复中心点位置
        if (center != null) {
            setViewCenter(center);
        } else {
            clampImageOffset();
        }

        repaint();
    }

    /**
     * 设置视野回调
     */
    public void setViewportCallback(ViewportCallback callback) {
        this.viewportCallback = callback;
        // 初始化视野管理器（缓冲区大小为1，即周围1个区域的范围）
        this.viewportManager = new ViewportManager(callback, 1);
    }

    /**
     * 启用自动视野管理
     */
    public void enableAutoViewportManagement(boolean enable) {
        if (enable) {
            if (!viewportUpdateTimer.isRunning()) {
                viewportUpdateTimer.start();
            }
            // 立即触发一次视野更新
            updateViewportIfNeeded();
        } else {
            viewportUpdateTimer.stop();
        }
    }

    /**
     * 强制更新视野
     */
    public void forceViewportUpdate() {
        lastViewport = new Rectangle(); // 清空上次视野，强制更新
        updateViewportIfNeeded();
    }

    /**
     * 检查视野是否需要更新
     */
    private void updateViewportIfNeeded() {
        if (viewportManager == null || image == null) return;

        // 如果正在缩放，跳过视野更新以避免重新加载
        if (isZooming) {
            return;
        }

        // 获取当前视图中心点和缩放级别
        Point currentCenter = getViewCenter();
        double currentScale = this.scale;

        // 检查是否只是缩放变化（中心点没有显著移动）
        boolean isOnlyZoomChange = false;
        if (currentCenter != null && lastViewCenter != null) {
            double centerDistance = Math.sqrt(
                Math.pow(currentCenter.x - lastViewCenter.x, 2) +
                Math.pow(currentCenter.y - lastViewCenter.y, 2)
            );

            // 如果中心点移动距离小于50像素，且缩放发生了变化，认为是纯缩放操作
            isOnlyZoomChange = (centerDistance < 50) && (Math.abs(currentScale - lastScale) > 0.01);
        }

        // 如果只是缩放变化，不触发视野更新（避免重新加载）
        if (isOnlyZoomChange) {
            System.out.println("Pure zoom detected, skipping viewport update");
            lastScale = currentScale;
            return;
        }

        // 计算当前可见的世界坐标范围
        Rectangle currentViewport = getCurrentViewportInWorldCoords();

        // 如果视野发生了显著变化，更新视野管理
        if (!currentViewport.equals(lastViewport)) {
            lastViewport = new Rectangle(currentViewport);
            if (currentCenter != null) {
                lastViewCenter = new Point(currentCenter);
            }
            lastScale = currentScale;

            System.out.printf("Viewport update: world range (%d,%d) to (%d,%d)\n",
                currentViewport.x, currentViewport.y,
                currentViewport.x + currentViewport.width,
                currentViewport.y + currentViewport.height);

            viewportManager.updateViewport(
                currentViewport.x,
                currentViewport.y,
                currentViewport.x + currentViewport.width,
                currentViewport.y + currentViewport.height
            );
        }
    }

    /**
     * 获取当前可见区域的世界坐标范围
     */
    private Rectangle getCurrentViewportInWorldCoords() {
        if (image == null) return new Rectangle();

        // 获取屏幕可见区域
        Rectangle visibleRect = getVisibleRect();

        // 转换为世界坐标
        Point topLeft = screenToWorldCoordinates(new Point(visibleRect.x, visibleRect.y));
        Point bottomRight = screenToWorldCoordinates(new Point(
            visibleRect.x + visibleRect.width,
            visibleRect.y + visibleRect.height
        ));

        if (topLeft != null && bottomRight != null) {
            return new Rectangle(
                topLeft.x,
                topLeft.y,
                bottomRight.x - topLeft.x,
                bottomRight.y - topLeft.y
            );
        }

        return new Rectangle();
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
        if (selecting && selectionStart != null && image != null) {
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

                // 调用回调（仅用于UI更新，不触发渲染）
                if (selectionCallback != null) {
                    selectionCallback.onSelectionComplete(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
                }

                System.out.printf("Selected area: world (%d, %d) to (%d, %d) - press Enter to confirm\n",
                    currentMinX, currentMinZ, currentMaxX, currentMaxZ);
            }
        }

        // 清除选择状态，但保持世界坐标用于绘制
        selecting = false;
        selectionStart = null;
        selectionEnd = null;
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
        if (selectionWorldStart == null || selectionWorldEnd == null || image == null) {
            return new Rectangle();
        }
        Point screenStart = worldToScreenCoordinates(selectionWorldStart);
        Point screenEnd = worldToScreenCoordinates(selectionWorldEnd);
        if (screenStart == null || screenEnd == null) return new Rectangle();
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
        if (r.width <= 0 || r.height <= 0) return HANDLE_NONE;
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
     * 确认当前选择
     */
    public void confirmCurrentSelection() {
        if (hasValidSelection && selectionCallback != null) {
            selectionCallback.onSelectionConfirmed(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
        }
    }

    /**
     * 清除选择矩形
     */
    public void clearSelection() {
        selectionStart = null;
        selectionEnd = null;
        selectionRect = null;
        selectionWorldStart = null;
        selectionWorldEnd = null;
        selecting = false;
        hasValidSelection = false;
        resizeHandle = HANDLE_NONE;
        repaint();
    }

    /**
     * 将屏幕坐标转换为世界坐标
     */
    private Point screenToWorldCoordinates(Point screenPoint) {
        if (image == null) return null;

        // 1. 屏幕坐标 -> 图像像素坐标
        double imageX = (screenPoint.x - imageOffset.x) / scale;
        double imageY = (screenPoint.y - imageOffset.y) / scale;

        // 确保坐标在图像范围内
        imageX = Math.max(0, Math.min(image.getWidth() - 1, imageX));
        imageY = Math.max(0, Math.min(image.getHeight() - 1, imageY));

        // 2. 图像像素坐标 -> 世界坐标
        int worldX = worldMinX + (int) (imageX / pixelsPerBlock);
        int worldZ = worldMinZ + (int) (imageY / pixelsPerBlock);

        return new Point(worldX, worldZ);
    }

    /**
     * 将世界坐标转换为当前屏幕坐标（考虑缩放和偏移）
     */
    private Point worldToScreenCoordinates(Point worldPoint) {
        if (image == null) return null;

        // 1. 世界坐标 -> 图像像素坐标
        double imageX = (worldPoint.x - worldMinX) * pixelsPerBlock;
        double imageY = (worldPoint.y - worldMinZ) * pixelsPerBlock;

        // 2. 图像像素坐标 -> 屏幕坐标（考虑缩放和偏移）
        int screenX = (int) (imageX * scale + imageOffset.x);
        int screenY = (int) (imageY * scale + imageOffset.y);

        return new Point(screenX, screenY);
    }

    /**
     * 开始拖拽地图
     */
    private void startDrag(Point point) {
        lastMousePos = new Point(point);
        dragging = true;
    }

    /**
     * 更新拖拽地图
     */
    private void updateDrag(Point point) {
        if (dragging && lastMousePos != null) {
            int dx = point.x - lastMousePos.x;
            int dy = point.y - lastMousePos.y;

            imageOffset.x += dx;
            imageOffset.y += dy;

            lastMousePos = new Point(point);
            repaint();
        }
    }

    /**
     * 完成拖拽地图
     */
    private void finishDrag() {
        dragging = false;
        lastMousePos = null;
    }

    @Override
    public Dimension getPreferredSize() {
        if (image != null) {
            int width = (int) (image.getWidth() * scale) + Math.abs(imageOffset.x);
            int height = (int) (image.getHeight() * scale) + Math.abs(imageOffset.y);
            return new Dimension(Math.max(800, width), Math.max(600, height));
        }
        return new Dimension(800, 600);
    }

    /**
     * 跳转到指定的世界坐标
     */
    public boolean jumpToWorldCoordinate(Point worldCoord) {
        if (image == null) return false;

        // 将世界坐标转换为图像坐标
        Point imageCoord = worldToImageCoordinates(worldCoord);

        if (imageCoord != null) {
            // 设置视图中心为目标坐标
            setViewCenter(imageCoord);
            System.out.printf("Jump to world (%d,%d) -> image (%d,%d)\n",
                worldCoord.x, worldCoord.y, imageCoord.x, imageCoord.y);
            return true;
        } else {
            System.out.printf("Cannot jump to world (%d,%d) - conversion failed\n",
                worldCoord.x, worldCoord.y);
            return false;
        }
    }

    /**
     * 将世界坐标转换为图像坐标
     */
    private Point worldToImageCoordinates(Point worldCoord) {
        if (worldMinX == 0 && worldMinZ == 0 && pixelsPerBlock == 0) {
            // 坐标映射未初始化
            return null;
        }

        // 计算相对于世界原点的偏移
        int relativeX = worldCoord.x - worldMinX;
        int relativeZ = worldCoord.y - worldMinZ;

        // 转换为图像坐标
        int imageX = (int) (relativeX * pixelsPerBlock);
        int imageY = (int) (relativeZ * pixelsPerBlock);

        // 检查坐标是否在图像范围内
        if (imageX >= 0 && imageX < image.getWidth() &&
            imageY >= 0 && imageY < image.getHeight()) {
            return new Point(imageX, imageY);
        }

        return null;
    }
}
