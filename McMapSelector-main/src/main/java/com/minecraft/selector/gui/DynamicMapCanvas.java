package com.minecraft.selector.gui;

import com.minecraft.selector.core.DynamicRegionManager;
import com.minecraft.selector.core.DynamicRegionManager.RegionTile;
import com.minecraft.selector.utils.LogManager;

import javax.swing.*;
import com.minecraft.selector.core.RenderedMap;
import com.minecraft.selector.core.SelectionYFit;
import java.util.function.Consumer;
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
public class DynamicMapCanvas extends JPanel implements SelectionCanvas, DynamicRegionManager.RegionLoadListener {

    private SelectionShape selectionShape=new SelectionShape();
    private final SelectionGestures gestures=new SelectionGestures();
    private final MinimapInset minimap=new MinimapInset();
    private SelectionCanvas thisCanvas(){return this;}
    public SelectionShape getSelectionShape(){return selectionShape.copy();}
    public void setSelectionShape(SelectionShape shape,boolean remember){if(remember)selectionHistory.remember(getSelectionBounds(),selectionShape);SelectionShape copy=shape.copy();applyBounds(copy.bounds(),false);selectionShape=copy;repaint();}
    private final SelectionHistory selectionHistory = new SelectionHistory();
    private SelectionMath.Snap snapMode = SelectionMath.Snap.NONE;
    private SelectionMath.Bounds resizeOriginal;
    private Point resizeGrabWorld;
    private boolean squareGesture;
    private int gridMode;
    private Consumer<SelectionMath.Bounds> selectionChangeListener;
    private Consumer<String> commandListener;
    private Consumer<Point> lodCenterListener;
    /** 中键拖动 LOD 中心进行中（按下时置位，松开清除）。 */
    private boolean lodDragging;

    public void setSelectionChangeListener(Consumer<SelectionMath.Bounds> listener) {selectionChangeListener=listener;}
    public void setCommandListener(Consumer<String> listener) {commandListener=listener;}
    public void setLodCenterListener(Consumer<Point> listener) {lodCenterListener=listener;}
    private void command(String name) {if(commandListener!=null) commandListener.accept(name);else if("grid".equals(name)) setGridMode((gridMode+1)%3);}
    public SelectionMath.Bounds getSelectionBounds() {return hasValidSelection?SelectionMath.normalized(currentMinX,currentMinZ,currentMaxX,currentMaxZ):null;}
    public void restoreSelectionBounds(SelectionMath.Bounds b) {if(java.util.Objects.equals(getSelectionBounds(),b)&&selectionShape.isEmpty()) return;selectionHistory.remember(getSelectionBounds(),selectionShape);applyBounds(b,false);}
    public void setSelectionBounds(SelectionMath.Bounds bounds) {
        if(java.util.Objects.equals(getSelectionBounds(),bounds)&&selectionShape.isEmpty()) return;
        selectionHistory.remember(getSelectionBounds(),selectionShape);applyBounds(bounds,true);
    }
    private void applyBounds(SelectionMath.Bounds bounds,boolean applySnap) {applyBounds(bounds,applySnap,false);}
    private void applyBounds(SelectionMath.Bounds bounds,boolean applySnap,boolean square) {
        selectionShape=new SelectionShape();
        corner1=null;corner2=null;
        if(bounds==null) {hasValidSelection=false;selectionWorldStart=null;selectionWorldEnd=null;selectionRect=null;}
        else {
            if(applySnap) bounds=SelectionMath.constrain(bounds,snapMode,square);
            currentMinX=bounds.minX;currentMinZ=bounds.minZ;currentMaxX=bounds.maxX;currentMaxZ=bounds.maxZ;
            hasValidSelection=true;selectionWorldStart=new Point(currentMinX,currentMinZ);selectionWorldEnd=new Point(currentMaxX,currentMaxZ);
        }
        if(selectionChangeListener!=null) selectionChangeListener.accept(getSelectionBounds());
        refreshHover();repaint();
    }
    public void setSquareGesture(boolean square) {
        squareGesture=square;
        if(hoverPoint!=null) {
            if(resizeHandle!=HANDLE_NONE) resizeTo(screenToWorldCoordinates(hoverPoint),square);
            else if(selecting) updateSelection(hoverPoint);
        }
    }
    public void resetSelection() {overlays.resetSelectionTool();gestures.cancel();resetPosCorner();selecting=false;resizeHandle=HANDLE_NONE;applyBounds(null,false);selectionHistory.clear();}
    public void nudgeSelection(int direction,boolean shrink,int step) {
        SelectionMath.Bounds b=getSelectionBounds();if(b==null) return;
        SelectionMath.Bounds next=SelectionMath.nudge(b,direction,shrink,step);
        if(!next.equals(b)) {selectionHistory.remember(b,selectionShape);applyBounds(next,false);}
    }
    public void undoSelection() {gestures.cancel();if(selectionHistory.canUndo()) {selecting=false;resizeHandle=HANDLE_NONE;SelectionShape previous=selectionHistory.shape();applyBounds(selectionHistory.undo(),false);selectionShape=previous;repaint();}}
    public void setSnapMode(SelectionMath.Snap snap) {snapMode=snap;SelectionMath.Bounds b=getSelectionBounds();if(b!=null) setSelectionBounds(SelectionMath.snap(b,snap));}
    public void setGridMode(int mode) {gridMode=Math.floorMod(mode,3);repaint();}
    public void selectVisible() {SelectionMath.Bounds b=visibleBounds();if(b!=null) setSelectionBounds(b);}
    private void resizeTo(Point world,boolean square) {
        if(world==null||resizeOriginal==null||resizeGrabWorld==null) return;
        boolean west=resizeHandle==3||resizeHandle==5||resizeHandle==7,north=resizeHandle==1||resizeHandle==5||resizeHandle==6;
        Point target=new Point(SelectionMath.clamp((long)(west?resizeOriginal.minX:resizeOriginal.maxX)+world.x-resizeGrabWorld.x),
            SelectionMath.clamp((long)(north?resizeOriginal.minZ:resizeOriginal.maxZ)+world.y-resizeGrabWorld.y));
        applyBounds(SelectionMath.resize(resizeOriginal,resizeHandle,target,square),true,square);
    }

    private ViewOverlays overlays=new ViewOverlays();
    private Point pos1,corner1,corner2;
    public void setOverlays(ViewOverlays value) {overlays=value;overlays.resetSelectionTool();repaint();}
    public void resetPosCorner() {pos1=null;corner1=null;corner2=null;}
    private void posClick(Point world) {
        if(world==null) return;
        if(pos1==null) {pos1=world;repaint();}
        else {Point first=pos1;setSelectionBounds(SelectionMath.normalized(first.x,first.y,world.x,world.y));corner1=first;corner2=world;pos1=null;}
    }
    private Point hoverPoint;
    private Consumer<String> hoverListener;

    public void setHoverListener(Consumer<String> listener) { hoverListener = listener; }

    public String getHoverText(Point point) {
        if (point == null || regionManager == null) return "X: —  Y: —  Z: —";
        Point world = screenToWorldCoordinates(point);
        RegionTile tile = regionManager.getLoadedRegion(new Point(Math.floorDiv(world.x, 512), Math.floorDiv(world.y, 512)));
        int y = tile == null ? RenderedMap.NO_HEIGHT :
            tile.getHeightAt(Math.floorMod(world.x, 512), Math.floorMod(world.y, 512));
        // 逐列方块状态 / 生物群系元数据已移除（每块 +4 MB），悬停只显示坐标与表面高度。
        return String.format("X: %d  Y: %s  Z: %d", world.x,
            y == RenderedMap.NO_HEIGHT ? "—" : Integer.toString(y), world.y);
    }

    private void refreshHover() {
        String text = getHoverText(hoverPoint)+(hoverPoint==null?"":overlays.tooltip(hoverPoint,this::worldToScreenCoordinates));
        setToolTipText(hoverPoint == null ? null : text);
        if (hoverListener != null) hoverListener.accept(text);
    }

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

    private boolean showLoadingIndicator = true;
    /** 加载指示的延迟阈值：短于它就别闪（换带时的短暂重载不该糊一片 Loading） */
    private static final long LOADING_INDICATOR_DELAY_MS = 400;
    private final java.util.Map<Point, Long> loadingSince = new java.util.concurrent.ConcurrentHashMap<>();
    private Rectangle lastViewport = new Rectangle();
    
    // 防抖相关
    private Timer viewportUpdateTimer;
    private volatile boolean needsViewportUpdate = false;
    private static final int VIEWPORT_UPDATE_DELAY = 200; // 200ms延迟
    
    public DynamicMapCanvas() {
        this.logManager = LogManager.getInstance();
        
        setBackground(CheckerBackground.DARK);
        setPreferredSize(new Dimension(800, 600));
        
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {requestViewportUpdate();}
        });
        setupMouseListeners();
        setupKeyListeners();
        SelectionBindings.install(this,this,this::command);
        
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
    public void setRegionManager(DynamicRegionManager regionManager) {setRegionManager(regionManager,true);}
    public void setRegionManager(DynamicRegionManager regionManager,boolean loadImmediately) {
        if (this.regionManager != null) {
            this.regionManager.removeRegionLoadListener(this);
        }
        
        viewportUpdateTimer.stop();
        lastViewport = new Rectangle(); // a new dimension needs its first load even at identical bounds
        this.regionManager = regionManager;
        
        if (regionManager != null) {
            regionManager.addRegionLoadListener(this);
            if(loadImmediately) updateViewportIfNeeded();
        }
        
        refreshHover();
        repaint();
    }
    
    /**
     * 设置鼠标监听器
     */
    private void setupMouseListeners() {
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==2&&!overlays.posMode&&!minimap.contains(e.getPoint())) zoomAt(e.getPoint(),1.2);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverPoint = null;
                refreshHover();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                squareGesture=e.isShiftDown();
                hoverPoint=e.getPoint();
                if(overlays.minimap&&SwingUtilities.isLeftMouseButton(e)&&minimap.contains(e.getPoint())) {Point target=minimap.worldAt(e.getPoint());if(target!=null)jumpToWorldCoordinate(target);return;}
                // Special-shape gestures stay implemented, but are not dispatched by the GUI.
                overlays.resetSelectionTool();
                gestures.cancel();
                // 中键：拖动 LOD 中心（LOD 叠加层可见时）。旧的"设为地表高度"已移除。
                if(SwingUtilities.isMiddleMouseButton(e)) {
                    if(overlays.importLodVisible) {
                        lodDragging=true;
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                        dragLodCenterTo(e.getPoint());
                    }
                    return;
                }
                if(overlays.posMode&&SwingUtilities.isLeftMouseButton(e)) {posClick(screenToWorldCoordinates(e.getPoint()));return;}
                if (SwingUtilities.isLeftMouseButton(e)) {
                    // 先检查是否点击到已选区域的线框/角点（可拖拽调整）
                    if (hasValidSelection) {
                        int handle = hitTestResizeHandle(e.getPoint());
                        if (handle != HANDLE_NONE) {
                            selectionHistory.remember(getSelectionBounds(),selectionShape);
                            resizeOriginal=getSelectionBounds();
                            resizeGrabWorld=screenToWorldCoordinates(e.getPoint());
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
                squareGesture=e.isShiftDown();
                if(gestures.release(e,screenToWorldCoordinates(e.getPoint()),thisCanvas(),snapMode)){if(hasValidSelection&&selectionCallback!=null)selectionCallback.onSelectionComplete(currentMinX,currentMinZ,currentMaxX,currentMaxZ);refreshHover();return;}
                if(lodDragging) { dragLodCenterTo(e.getPoint()); lodDragging=false; setCursor(Cursor.getDefaultCursor()); return; }
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (resizeHandle != HANDLE_NONE) {
                        // 结束选区调整：归一化坐标并通知回调
                        resizeTo(screenToWorldCoordinates(e.getPoint()),e.isShiftDown());
                        normalizeSelection();
                        resizeHandle = HANDLE_NONE;
                        setCursor(Cursor.getDefaultCursor());
                        if (selectionCallback != null) {
                            selectionCallback.onSelectionComplete(currentMinX, currentMinZ, currentMaxX, currentMaxZ);
                        }
                        refreshHover();
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
                hoverPoint = e.getPoint();
                squareGesture=e.isShiftDown();
                if(gestures.update(e,screenToWorldCoordinates(e.getPoint()),thisCanvas(),snapMode))return;
                if(lodDragging) { dragLodCenterTo(e.getPoint()); return; }
                if (resizeHandle != HANDLE_NONE && hasValidSelection) {
                    // 拖拽调整选区边界
                    Point world = screenToWorldCoordinates(e.getPoint());
                    resizeTo(world,e.isShiftDown());
                    refreshHover();
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
                    refreshHover();
                    repaint();
                    requestViewportUpdate(); // 使用防抖更新
                } else if (SwingUtilities.isRightMouseButton(e) && selecting) {
                    // 右键拖拽更新选择区域
                    updateSelection(e.getPoint());
                }
            }
            
            @Override
            public void mouseMoved(MouseEvent e) {
                hoverPoint = e.getPoint();
                refreshHover();
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
            
            refreshHover();
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
                if(e.isControlDown() || e.isAltDown() || e.getKeyCode()==KeyEvent.VK_G) return;
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

                    case KeyEvent.VK_L:
                        showLoadingIndicator = !showLoadingIndicator;
                        break;
                    case KeyEvent.VK_R:
                        resetView();
                        break;
                }
                refreshHover();
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
        refreshHover();
        repaint();
    }
    
    /**
     * 适应窗口大小
     * 为避免极远孤立区域(如异常坐标的region)导致地图缩放过小，
     * 使用"main cluster"(与大多数区域相邻的连续区域)来计算边界。
     */
    public void showInitialViewport(double[] player) {
        if(regionManager==null) return;
        Set<Point> regions=regionManager.getAvailableRegions();
        if(regions.isEmpty()) {
            regionManager.updateViewport(new Rectangle(0,0,1,1));return;
        }
        Point anchor=regions.stream().min(java.util.Comparator.comparingDouble(p -> {
            double x=player==null?0:player[0],z=player==null?0:player[2];
            return Math.pow(p.x*512.0+256-x,2)+Math.pow(p.y*512.0+256-z,2);
        })).get();
        double x=player==null?anchor.x*512.0+256:player[0];
        double z=player==null?anchor.y*512.0+256:player[2];
        if(!regions.contains(new Point((int)Math.floor(x/512),(int)Math.floor(z/512)))) {
            x=anchor.x*512.0+256;z=anchor.y*512.0+256;
        }
        scale=1.0;
        viewOffset.x=getWidth()/2-(int)x;viewOffset.y=getHeight()/2-(int)z;
        lastViewport=new Rectangle();viewportUpdateTimer.stop();
        updateViewportIfNeeded();repaint();
    }

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
        
        refreshHover();
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

        refreshHover();
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
        if (regionManager == null || getWidth()<=0 || getHeight()<=0) return;
        
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
        // Crossing a region edge matters even for small moves now that loading is viewport-only.
        if(Math.floorDiv(current.x,512)!=Math.floorDiv(last.x,512) ||
           Math.floorDiv(current.y,512)!=Math.floorDiv(last.y,512) ||
           Math.floorDiv((long)current.x+current.width-1,512)!=Math.floorDiv((long)last.x+last.width-1,512) ||
           Math.floorDiv((long)current.y+current.height-1,512)!=Math.floorDiv((long)last.y+last.height-1,512)) return true;
        
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
        
        // 空地先铺棋盘底：图案固定在屏幕坐标上，拖动/缩放地图时不动
        CheckerBackground.paint(g2d, getWidth(), getHeight());
        
        try {
            if (regionManager != null) {
                drawRegions(g2d);
                if (gridMode!=0) {
                    drawGrid(g2d);
                }
                drawSelection(g2d);
                drawStatusInfo(g2d);
                overlays.drawOre(g2d,this::worldToScreenCoordinates,scale);
                SelectionGestures.draw(g2d,selectionShape,this::worldToScreenCoordinates,scale);
                overlays.drawInView(g2d,getVisibleRect(),scale,this::worldToScreenCoordinates,getSelectionBounds(),pos1,corner1,corner2);
                if(overlays.minimap){java.util.List<MinimapInset.Tile> tiles=new java.util.ArrayList<>();for(Point p:regionManager.getAvailableRegions()){RegionTile t=regionManager.peekLoadedRegion(p);long x=(long)p.x*512,z=(long)p.y*512;tiles.add(new MinimapInset.Tile(new SelectionMath.Bounds(SelectionMath.clamp(x),SelectionMath.clamp(z),SelectionMath.clamp(x+511),SelectionMath.clamp(z+511)),t==null?null:t.peekImage()));}
                    Rectangle view=getVisibleRect();Point a=screenToWorldCoordinates(new Point(view.x,view.y)),b=screenToWorldCoordinates(new Point(view.x+view.width-1,view.y+view.height-1));minimap.draw(g2d,view,tiles,SelectionMath.normalized(a.x,a.y,b.x,b.y));
                }else minimap.clear();
            } else {
                minimap.clear();drawNoDataMessage(g2d);
            }
        } finally {
            g2d.dispose();
        }
    }
    
    /**
     * 一块瓦片上的兴趣带高亮：按屏幕坐标叠紫棋盘（图案锚在屏幕原点，所以拖动/缩放时不动）。
     * 逐行把连续的高亮像素并成一段再 fillRect；边界用 floor 与 drawImage 的最近邻缩放对齐，
     * 否则缩放后瓦片之间会露出 1~3 像素的缝。
     */
    private void drawTileHighlight(Graphics2D g2d, RenderedMap map, int originX, int originY, double pixelScale) {
        int mapWidth = map.getWidth(), mapHeight = map.getHeight();
        g2d.setPaint(CheckerBackground.highlightPaint());
        for (int z = 0; z < mapHeight; z++) {
            int rowTop = originY + (int) Math.floor(z * pixelScale);
            int rowBottom = originY + (int) Math.floor((z + 1) * pixelScale);
            int rowHeight = Math.max(1, rowBottom - rowTop);
            int x = 0;
            while (x < mapWidth) {
                if (!map.isHighlighted(x, z)) { x++; continue; }
                int start = x;
                while (x < mapWidth && map.isHighlighted(x, z)) x++;
                int left = originX + (int) Math.floor(start * pixelScale);
                int right = originX + (int) Math.floor(x * pixelScale);
                g2d.fillRect(left, rowTop, Math.max(1, right - left), rowHeight);
            }
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
                
                // 绘制期间只读，不触发重渲染：paint 里排队 refresh 会造成"重绘引发重绘"，
                // 瓦片一块块回来时看起来就是按顺序闪。过期检测交给非绘制的取用路径。
                RegionTile tile = regionManager.peekLoadedRegion(regionCoord);
                if (tile != null) {
                    // 绘制已加载的区域
                    BufferedImage image = tile.getImage();
                    // 使用+1像素重叠来消除缝隙
                    g2d.drawImage(image, screenX, screenY, screenWidth + 1, screenHeight + 1, null);
                    if(overlays.night||overlays.contours||overlays.gaps)
                        g2d.drawImage(ViewLayers.overlay(image,overlays.night,overlays.contours,overlays.gaps),screenX,screenY,screenWidth+1,screenHeight+1,null);
                    // 兴趣带高亮：同样按屏幕坐标叠紫棋盘（图案不随地图移动）
                    if (image instanceof RenderedMap) {
                        // 用图像"实际绘制尺寸"(screenWidth+1) 算缩放：否则瓦片交界处会空出 1 像素露底
                        drawTileHighlight(g2d, (RenderedMap) image, screenX, screenY,
                            (double) (screenWidth + 1) / REGION_SIZE);
                    }
                    
                } else if (availableRegions.contains(regionCoord)) {
                    if (regionManager.isRegionLoading(regionCoord)) {
                        // 绘制加载中的区域：没超过阈值就什么都不画（旧瓦片/棋盘保持不动）
                        long since = loadingSince.computeIfAbsent(regionCoord, k -> System.currentTimeMillis());
                        if (System.currentTimeMillis() - since < LOADING_INDICATOR_DELAY_MS) continue;
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
                        // 绘制未加载但可用的区域：同样延迟
                        long since = loadingSince.computeIfAbsent(regionCoord, k -> System.currentTimeMillis());
                        if (System.currentTimeMillis() - since < LOADING_INDICATOR_DELAY_MS) continue;
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
        MapGrid.draw(g2d,gridMode,viewportBounds(),scale,this::worldToScreenCoordinates,getWidth(),getHeight());
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
        loadingSince.remove(regionCoord);
        SwingUtilities.invokeLater(() -> { refreshHover(); repaint(); });
    }
    
    @Override
    public void onRegionUnloaded(Point regionCoord) {
        loadingSince.remove(regionCoord);
        SwingUtilities.invokeLater(() -> { refreshHover(); repaint(); });
    }
    
    @Override
    public void onLoadingStarted(Point regionCoord) {
        SwingUtilities.invokeLater(() -> { refreshHover(); repaint(); });
    }
    
    @Override
    public void onLoadingFailed(Point regionCoord, Exception error) {
        loadingSince.remove(regionCoord);
        SwingUtilities.invokeLater(() -> { refreshHover(); repaint(); });
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
        resetPosCorner();
        selectionHistory.remember(getSelectionBounds(),selectionShape);
        selectionStart = new Point(point);
        selectionEnd = new Point(point);
        selecting = true;
        selectionRect = new Rectangle();
        updateSelection(point);
        refreshHover();
        repaint();
    }
    
    /**
     * 更新选择区域
     */
    private void updateSelection(Point point) {
        if (!selecting || selectionStart==null) return;
        Point a=screenToWorldCoordinates(selectionStart),b=screenToWorldCoordinates(point);
        if(a==null||b==null) return;
        applyBounds(SelectionMath.drag(a,b,squareGesture),true,squareGesture);
        // Preview uses the same inclusive world bounds as the committed rectangle.
        selectionEnd=worldToScreenCoordinates(new Point(currentMaxX,currentMaxZ));
        selectionRect=getSelectionScreenRect();
    }

    private void finishSelection(Point point) {
        updateSelection(point);selecting=false;selectionStart=null;selectionEnd=null;
        if(hasValidSelection&&selectionCallback!=null) selectionCallback.onSelectionComplete(currentMinX,currentMinZ,currentMaxX,currentMaxZ);
        refreshHover();repaint();
    }

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
        if(!hasValidSelection) return new Rectangle();
        double factor=scale;
        int x=(int)Math.floor(((long)currentMinX)*factor+viewOffset.x);
        int z=(int)Math.floor(((long)currentMinZ)*factor+viewOffset.y);
        int right=(int)Math.ceil(((long)currentMaxX+1)*factor+viewOffset.x);
        int bottom=(int)Math.ceil(((long)currentMaxZ+1)*factor+viewOffset.y);
        return new Rectangle(x,z,Math.max(1,SelectionMath.clamp((long)right-x)),Math.max(1,SelectionMath.clamp((long)bottom-z)));
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
     * 中键拖动 LOD 中心（区块对齐与回写面板由 GUI 侧监听器负责）。
     */
    private void dragLodCenterTo(Point screenPoint) {
        if (lodCenterListener == null) return;
        Point world = screenToWorldCoordinates(screenPoint);
        if (world != null) lodCenterListener.accept(world);
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
    public void clearSelection() {overlays.resetSelectionTool();gestures.cancel();
        resetPosCorner();
        if(hasValidSelection) selectionHistory.remember(getSelectionBounds(),selectionShape);
        selectionShape=new SelectionShape();
        hasValidSelection = false;
        selecting = false;
        selectionStart = null;
        selectionEnd = null;
        selectionWorldStart = null;
        selectionWorldEnd = null;
        selectionRect = new Rectangle();
        resizeHandle = HANDLE_NONE;
        if(selectionChangeListener!=null) selectionChangeListener.accept(null);
        refreshHover();
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

    private SelectionMath.Bounds viewportBounds() {
        int minX=(int)Math.floor(-viewOffset.x/scale),minZ=(int)Math.floor(-viewOffset.y/scale);
        int maxX=SelectionMath.clamp((long)Math.ceil((getWidth()-(double)viewOffset.x)/scale)-1);
        int maxZ=SelectionMath.clamp((long)Math.ceil((getHeight()-(double)viewOffset.y)/scale)-1);
        return SelectionMath.normalized(minX,minZ,maxX,maxZ);
    }
    private SelectionMath.Bounds visibleBounds() {
        SelectionMath.Bounds b=viewportBounds();if(regionManager==null) return null;
        Set<Point> regions=regionManager.getAvailableRegions();if(regions.isEmpty()) return null;
        long minX=Long.MAX_VALUE,minZ=Long.MAX_VALUE,maxX=Long.MIN_VALUE,maxZ=Long.MIN_VALUE;
        for(Point p:regions) {minX=Math.min(minX,(long)p.x*512);minZ=Math.min(minZ,(long)p.y*512);maxX=Math.max(maxX,(long)p.x*512+511);maxZ=Math.max(maxZ,(long)p.y*512+511);}
        int x1=Math.max(b.minX,SelectionMath.clamp(minX)),z1=Math.max(b.minZ,SelectionMath.clamp(minZ)),x2=Math.min(b.maxX,SelectionMath.clamp(maxX)),z2=Math.min(b.maxZ,SelectionMath.clamp(maxZ));
        return x1>x2||z1>z2?null:new SelectionMath.Bounds(x1,z1,x2,z2);
    }
    public int[] getHoverCoordinates() {
        if(hoverPoint==null||regionManager==null) return null;
        Point w=screenToWorldCoordinates(hoverPoint);
        RegionTile tile=regionManager.getLoadedRegion(new Point(Math.floorDiv(w.x,512),Math.floorDiv(w.y,512)));
        return new int[]{w.x,tile==null?RenderedMap.NO_HEIGHT:tile.getHeightAt(Math.floorMod(w.x,512),Math.floorMod(w.y,512)),w.y};
    }
    public SelectionYFit.Result fitSelectionY(SelectionMath.Bounds b) {return fitSelectionY(b,true);}
    public SelectionYFit.Result fitSelectionY(SelectionMath.Bounds b,boolean opaque) {
        SelectionYFit.Result result=new SelectionYFit.Result();if(regionManager==null||b==null) return result;
        for(Map.Entry<Point,RegionTile> e:regionManager.getAllLoadedRegions().entrySet())
            result.merge(SelectionYFit.scan(e.getValue().getImage(),SelectionMath.clamp((long)e.getKey().x*512),SelectionMath.clamp((long)e.getKey().y*512),1,b,opaque));
        return result;
    }
    public java.util.Map<String,Long> visibleBlockCounts() {
        java.util.Map<String,Long> counts=new java.util.TreeMap<>();if(regionManager==null) return counts;
        SelectionMath.Bounds b=viewportBounds();
        for(java.util.Map.Entry<Point,RegionTile> e:regionManager.getAllLoadedRegions().entrySet()) {
            long x=(long)e.getKey().x*512,z=(long)e.getKey().y*512;
            if(x>b.maxX||z>b.maxZ||x+511<b.minX||z+511<b.minZ) continue;
            ViewLayers.count(counts,e.getValue().getImage(),SelectionMath.clamp((long)b.minX-x),SelectionMath.clamp((long)b.minZ-z),SelectionMath.clamp((long)b.maxX-x),SelectionMath.clamp((long)b.maxZ-z));
        }return counts;
    }
    private void zoomAt(Point p,double factor) {
        double next=Math.max(0.000000000001,Math.min(10.0,scale*factor)),ratio=next/scale;
        viewOffset.x=(int)Math.floor(p.x-(p.x-(double)viewOffset.x)*ratio);viewOffset.y=(int)Math.floor(p.y-(p.y-(double)viewOffset.y)*ratio);scale=next;
        refreshHover();repaint();updateViewportIfNeeded();
    }
    public double getZoomLevel() {return scale;}
    public void zoomExtents(boolean maximum) {
        if(maximum) {zoomAt(new Point(getWidth()/2,getHeight()/2),10.0/scale);return;}
        if(regionManager==null) return;
        java.util.Set<Point> points=regionManager.getAllLoadedRegions().keySet();if(points.isEmpty()) points=regionManager.getAvailableRegions();if(points.isEmpty()) return;
        long x1=Long.MAX_VALUE,z1=Long.MAX_VALUE,x2=Long.MIN_VALUE,z2=Long.MIN_VALUE;
        for(Point p:points) {x1=Math.min(x1,(long)p.x*512);z1=Math.min(z1,(long)p.y*512);x2=Math.max(x2,(long)p.x*512+512);z2=Math.max(z2,(long)p.y*512+512);}
        scale=Math.max(0.000000000001,Math.min(10.0,Math.min(getWidth()/(double)(x2-x1),getHeight()/(double)(z2-z1))));
        viewOffset.x=(int)(getWidth()/2.0-((x1+x2)/2.0)*scale);viewOffset.y=(int)(getHeight()/2.0-((z1+z2)/2.0)*scale);refreshHover();repaint();updateViewportIfNeeded();
    }
}
