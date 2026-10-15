package com.minecraft.selector.gui;

import com.minecraft.selector.core.MapRenderer;
import com.minecraft.selector.core.PreviewOptions;
import com.minecraft.selector.core.SelectionPreset;
import com.minecraft.selector.utils.RecentWorlds;
import com.minecraft.selector.core.SelectionYFit;
import com.minecraft.selector.core.WorldAnchors;
import com.minecraft.selector.core.CoordinatesPayload;
import java.awt.datatransfer.StringSelection;
import com.minecraft.selector.core.RenderedMap;
import com.minecraft.selector.core.MinecraftResourceExtractor;
import com.minecraft.selector.core.BlockColors;
import com.minecraft.selector.core.ResourceOptions;
import com.minecraft.selector.nbt.NBTReader;
import com.minecraft.selector.utils.FileUtils;
import com.minecraft.selector.utils.LogManager;
import com.minecraft.selector.utils.WorldPathResolver;
import com.minecraft.selector.core.DynamicRegionManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import javax.swing.SwingWorker;

/**
 * Minecraft地图GUI界面
 * 对应Python的gui.py
 */
public class MinecraftMapGUI extends JFrame {
    
    // GUI组件
    private JLabel saveInfoLabel;
    private JLabel playerPosLabel;
    private JLabel regionLabel;
    private JLabel coordDisplayLabel;
    private JLabel fileInfoLabel;
    private final JLabel selectionStatsLabel=new JLabel("No selection");
    private WorldAnchors worldAnchors=WorldAnchors.empty();
    private SelectionMath.Snap selectionSnap=SelectionMath.Snap.NONE;
    private int gridMode;
    private JMenuItem gridMenuItem;
    private boolean confirmationOpen;
    private volatile PreviewOptions preview=PreviewOptions.DEFAULT;
    private final ViewOverlays overlays=new ViewOverlays();
    private final RecentWorlds recentWorlds=new RecentWorlds();
    private boolean multiBoxExport,includeWaypointsExport;
    private SwingWorker<com.minecraft.selector.core.OverlayScan.Result,Void> p2ScanWorker;
    private volatile long p2ScanRevision;
    private javax.swing.Timer hudTimer;
    public void setSelectionExportOptions(boolean multi,boolean markers){multiBoxExport=multi;includeWaypointsExport=markers;}
    private final TransferHandler fileDropHandler=new TransferHandler(){
        public boolean canImport(TransferSupport support){return support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);}
        public boolean importData(TransferSupport support){if(!canImport(support))return false;try{Object value=support.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);java.util.List<?> files=(java.util.List<?>)value;if(files.size()!=1)throw new IOException("Drop one world, preset or pack at a time");handleDroppedFile((File)files.get(0));return true;}catch(Exception e){showFileError(e);return false;}}
    };
    private JMenu recentMenu;
    private SelectionPreset pendingPreset;
    private final Map<String,JCheckBoxMenuItem> previewItems=new HashMap<>();
    private JMenuItem biomeMenu;
    private javax.swing.Timer selectionCoverageTimer;
    private SwingWorker<SelectionYFit.Result,Void> selectionCoverageWorker;
    public void setInitialSelectionSeed(SelectionMath.Bounds seed) {if(seed!=null) pendingPreset=new SelectionPreset(seed,null,null,null);}



    private JTextField minYEntry;
    private JTextField maxYEntry;
    private StyledComponents.DualRangeSlider heightRangeSlider;
    private JComboBox<String> lodDropdown;
    private JTextField xCoordEntry;
    private JTextField zCoordEntry;
    // 地图范围选择输入框已删除
    // private JTextField rangeXEntry;
    // private JTextField rangeZEntry;
    // private JTextField rangeSizeEntry;
    private JComboBox<String> mcaRangeDropdown;
    private JComboBox<WorldPathResolver.DimensionInfo> dimensionDropdown;
    private List<WorldPathResolver.DimensionInfo> availableDimensions = new ArrayList<>();
    private WorldPathResolver.DimensionInfo currentDimension;
    private boolean updatingDimensionDropdown = false;
    private JCheckBox autoLoadCheckbox;
    private JCheckBoxMenuItem hideNetherRoofCheckbox;
    private volatile MapRenderer.SurfaceMode surfaceMode = MapRenderer.SurfaceMode.AUTO;
    private volatile long viewGeneration;
    private volatile long colorGeneration;

    private void resetSurfaceView() {
        surfaceMode = hideNetherRoofCheckbox.isSelected() ? MapRenderer.SurfaceMode.AUTO : MapRenderer.SurfaceMode.TOP;
        // Height choices made during preparation apply when the first viewport is created.
        // In particular --render-y's debounce must not invalidate the world loading worker.
        if(loadingPanel.getPhase()==LoadingPhasePanel.Phase.PREPARING) return;
        cancelP2Scan();++viewGeneration;
        coordDisplayLabel.setText("X: —  Y: —  Z: —");
        dynamicMapManager.clear();
        mapCanvas.setImage(null);
        mapImage = null;
        if (isDynamicMode && dynamicRegionManager != null) {
            dynamicRegionManager.shutdown();
            dynamicRegionManager = createDynamicRegionManager();
            dynamicMapCanvas.setRegionManager(dynamicRegionManager);
        } else if (lastMcaPath != null) {
            renderMap(lastMcaPath);
        } else if (savePath != null) {
            renderAroundPlayer();
        }
    }

    private DynamicMapManager dynamicMapManager;
    // private JButton renderButton; // 已删除

    // 多区域渲染的坐标信息
    private int lastMultiRenderMinRegionX = 0;
    private int lastMultiRenderMinRegionZ = 0;
    private JButton renderAroundPlayerButton;
    private JButton jumpButton;
    private JButton confirmSelectionButton;
    private JButton reRenderButton;
    private MapCanvas mapCanvas;
    private JProgressBar progressBar;
    private JLabel progressLabel;
    
    // 数据
    private String savePath;
    private double[] playerPos;
    private Rectangle selectedRegion;
    private BufferedImage mapImage;

    private double customScale = 1.0;
    private Point currentRegion;
    private String currentOutputFile;
    private String currentJsonFile;
    private MinecraftResourceExtractor resourceExtractor;
    private final ResourceOptions colorResources=ResourceOptions.launch();
    private String lastMcaPath;
    private String singleResourcePath;
    private RenderHeightPanel renderHeightPanel;
    private LoadingPhasePanel loadingPanel;
    private javax.swing.Timer heightDebounce;
    private String programDir;
    
    // 日志相关
    private LogWindow logWindow;
    private LogManager logManager;
    
    // 全区域渲染的边界信息
    private int lastFullRenderMinWorldX = 0;
    private int lastFullRenderMinWorldZ = 0;
    
    // 动态模式相关
    private DynamicRegionManager dynamicRegionManager;
    private DynamicMapCanvas dynamicMapCanvas;
    private boolean isDynamicMode = false;
    private JButton dynamicModeButton;

    // Blender集成相关
    private String outputFilePath = null;
    private String jarFilePath = null;
    private boolean isBlenderMode = false;
    private int blenderMinY = -64;
    private int blenderMaxY = 365;

    // 多线程渲染优化
    private ExecutorService renderingExecutor;
    private ExecutorService backgroundExecutor;
    private final int RENDERING_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private final int BACKGROUND_THREADS = Math.min(4, Runtime.getRuntime().availableProcessors());

    // UI主题管理器
    private UIThemeManager themeManager;

    // 状态指示器
    private LoadingAnimationPanel.StatusIndicator statusIndicator;
    
    public MinecraftMapGUI() {
        this(null, null, null, -64, 365);
    }

    public MinecraftMapGUI(String worldPath, String outputFile) {
        this(worldPath, outputFile, null, -64, 365);
    }

    public MinecraftMapGUI(String worldPath, String outputFile, int minY, int maxY) {
        this(worldPath, outputFile, null, minY, maxY);
    }

    public MinecraftMapGUI(String worldPath, String outputFile, String jarPath, int minY, int maxY) {
        // 初始化程序目录
        programDir = System.getProperty("user.dir");
        resourceExtractor = new MinecraftResourceExtractor();
        
        // 初始化日志管理器
        logManager = LogManager.getInstance();
        logManager.info("Minecraft Map Selector GUI started", "MinecraftMapGUI");

        // 设置Blender模式
        this.outputFilePath = outputFile;
        this.jarFilePath = jarPath;
        if(jarPath!=null && colorResources.jars.isEmpty()) colorResources.jars.add(jarPath);
        this.isBlenderMode = (outputFile != null);
        this.blenderMinY = minY;
        this.blenderMaxY = maxY;

        // 初始化线程池
        initializeThreadPools();

        initializeGUI();
        if(isBlenderMode) showLoadingState("Parsing world and discovering resources...");

        // 尝试加载已保存的颜色文件
        // World loading initializes its own colors before rendering. Avoid a
        // competing startup extraction (and unscoped colors from another world).
        if (worldPath == null) {
            loadSavedColors();
        }

        // 如果是Blender模式，显示模式信息
        if (worldPath != null || isBlenderMode) {
            SwingUtilities.invokeLater(() -> {
                progressLabel.setText(isBlenderMode?"Blender integration mode started":"Opening world preview…");

                // 设置Blender模式的高度范围
                if (heightRangeSlider != null) {
                    heightRangeSlider.setLowerValue(blenderMinY);
                    heightRangeSlider.setUpperValue(blenderMaxY);
                    minYEntry.setText(String.valueOf(blenderMinY));
                    maxYEntry.setText(String.valueOf(blenderMaxY));
                }

                if (worldPath != null) {
                    if (new File(worldPath).exists()) {
                        loadWorldFromPath(worldPath);
                    } else {
                        showErrorState("World path does not exist - " + worldPath);
                        System.err.println("Blender mode: world path does not exist: " + worldPath);
                    }
                }
            });
        }
    }

    /**
     * 加载已保存的颜色文件
     */
    private void loadSavedColors() {
        // Unscoped historical exports lack model coverage and resource fingerprints.
        // Actual assets are initialized on the worker before world/MCA rendering.
        BlockColors.setResourceExtractor(null);
    }

    /**
     * 初始化线程池
     */
    private void initializeThreadPools() {
        // 主渲染线程池 - 用于重要的渲染任务
        renderingExecutor = Executors.newFixedThreadPool(RENDERING_THREADS, r -> {
            Thread t = new Thread(r, "RenderingThread");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1); // 稍高优先级
            return t;
        });

        // 后台线程池 - 用于自动加载等后台任务
        backgroundExecutor = Executors.newFixedThreadPool(BACKGROUND_THREADS, r -> {
            Thread t = new Thread(r, "BackgroundThread");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1); // 稍低优先级
            return t;
        });

        System.out.printf("Thread pools initialized - render threads: %d, background threads: %d\n",
            RENDERING_THREADS, BACKGROUND_THREADS);
    }

    /**
     * 清理线程池资源
     */
    private void shutdownThreadPools() {
        if(heightDebounce!=null) heightDebounce.stop();
        // Window close runs on Swing EDT; waiting here can freeze it for 10s.
        if (renderingExecutor != null) renderingExecutor.shutdownNow();
        if (backgroundExecutor != null) backgroundExecutor.shutdownNow();
        
        // 清理动态模式资源
        if (isDynamicMode) {
            disableDynamicMode();
        }

        System.out.println("Thread pools shut down");
    }

    private void initializeGUI() {
        setTitle("Minecraft Map Selector");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1600, 1000);  // 增加宽度以更好利用空间
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(1200, 800));  // 优化最小尺寸

        // 添加窗口关闭监听器，确保资源清理
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                cancelP2Scan();if(hudTimer!=null)hudTimer.stop();shutdownThreadPools();
                System.exit(0);
            }
        });

        // 添加组件监听器，确保布局稳定
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                // 窗口大小变化时，确保控制面板可见
                SwingUtilities.invokeLater(() -> {
                    revalidate();
                    repaint();
                });
            }
        });
        
        // 初始化主题管理器
        themeManager = UIThemeManager.getInstance();
        themeManager.initializeTheme();
        
        createComponents();
        layoutComponents();
        setupEventHandlers();
    }
    
    private void createComponents() {
        // 控制面板组件 - 使用样式化标签
        saveInfoLabel = new JLabel("No save selected");
        playerPosLabel = new JLabel("Player position: unknown");
        regionLabel = new JLabel("Selection: none");
        coordDisplayLabel = new JLabel("X: —  Y: —  Z: —");
        fileInfoLabel = new JLabel("");


        // 输入框 - 使用样式化输入框
        minYEntry = new JTextField("-64", 6);
        maxYEntry = new JTextField("320", 6);
        xCoordEntry = new JTextField("", 8);
        zCoordEntry = new JTextField("", 8);

        // 创建高度范围双滑块
        heightRangeSlider = new StyledComponents.DualRangeSlider(-64, 365, -64, 365);
        heightRangeSlider.addChangeListener(e -> {
            // 当滑块值改变时，同步更新文本框
            minYEntry.setText(String.valueOf(heightRangeSlider.getLowerValue()));
            maxYEntry.setText(String.valueOf(heightRangeSlider.getUpperValue()));
            updateSelectionStats();
        });

        // 地图范围选择输入框已删除
        
        // LOD下拉菜单 - 使用样式化下拉框
        String[] lodOptions = {
            "Auto (by file count)",
            "1 (full)",
            "2 (1/2)",
            "4 (1/4)",
            "8 (1/8)"
        };
        lodDropdown = new JComboBox<>(lodOptions);
        lodDropdown.setSelectedIndex(0);

        // MCA文件范围下拉框
        mcaRangeDropdown = new JComboBox<>(new String[]{"1x1 (1 file)", "3x3 (9 files)", "5x5 (25 files)", "7x7 (49 files)"});
        mcaRangeDropdown.setSelectedItem("1x1 (1 file)");

        // Dimension selector is populated after a save is loaded.
        dimensionDropdown = new JComboBox<>();
        dimensionDropdown.setPreferredSize(new Dimension(210, 26));
        dimensionDropdown.setEnabled(false);
        dimensionDropdown.addActionListener(e -> {
            if (!updatingDimensionDropdown) switchDimension((WorldPathResolver.DimensionInfo) dimensionDropdown.getSelectedItem());
        });

        // 自动加载复选框 - 使用样式化复选框
        autoLoadCheckbox = new JCheckBox("Enable auto-load", true);

        hideNetherRoofCheckbox = new JCheckBoxMenuItem("Hide Nether roof", true);
        hideNetherRoofCheckbox.setToolTipText("Show the surface below a bedrock ceiling; uncheck to see the roof and builds above it");
        hideNetherRoofCheckbox.addActionListener(e -> resetSurfaceView());

        // 按钮 - 使用新的图标和样式
        // renderButton 已删除
        renderAroundPlayerButton = createStyledButton("Render around player", IconManager.getPlayerIcon(IconManager.SMALL_ICON_SIZE));
        renderAroundPlayerButton.setEnabled(false);
        jumpButton = createStyledButton("Jump to coords", IconManager.getJumpIcon(IconManager.SMALL_ICON_SIZE));
        confirmSelectionButton = createStyledButton("Confirm selection", IconManager.getConfirmIcon(IconManager.SMALL_ICON_SIZE));
        confirmSelectionButton.setEnabled(false);
        reRenderButton = createStyledButton("Re-render", IconManager.getRefreshIcon(IconManager.SMALL_ICON_SIZE));
        reRenderButton.setEnabled(false);
        
        // 地图画布
        mapCanvas = new MapCanvas();
        mapCanvas.setHoverListener(coordDisplayLabel::setText);

        // 初始化动态地图管理器
        dynamicMapManager = new DynamicMapManager();

        // 设置选择回调
        mapCanvas.setSelectionCallback(new MapCanvas.SelectionCallback() {
            @Override public void onSelectionComplete(int minX,int minZ,int maxX,int maxZ) {updateSelectionStats();}
            @Override public void onSelectionConfirmed(int minX,int minZ,int maxX,int maxZ) {handleSelectionConfirmation(new SelectionMath.Bounds(minX,minZ,maxX,maxZ));}
        });
        configureCanvas(mapCanvas);

        // 设置视野管理回调
        mapCanvas.setViewportCallback(new MapCanvas.ViewportCallback() {
            @Override
            public void onViewportChanged(int minWorldX, int minWorldZ, int maxWorldX, int maxWorldZ) {
                SwingUtilities.invokeLater(() -> {
                    // 可以在这里更新状态栏显示当前视野范围
                    // progressLabel.setText(String.format("Viewport: (%d,%d) to (%d,%d)",
                    //     minWorldX, minWorldZ, maxWorldX, maxWorldZ));
                });
            }

            @Override
            public void loadRegion(int regionX, int regionZ) {
                // 在后台线程中加载区域
                loadRegionInBackground(regionX, regionZ);
            }

            @Override
            public void unloadRegion(int regionX, int regionZ) {
                // 卸载区域（可以清理内存中的数据）
                unloadRegionInBackground(regionX, regionZ);
            }
        });
        
        // 进度条 - 使用样式化进度条
        loadingPanel = new LoadingPhasePanel();
        progressBar = loadingPanel.getMapProgress();
        progressLabel = new JLabel("Ready");
        progressBar.setStringPainted(true);

        // 状态指示器
        statusIndicator = new LoadingAnimationPanel.StatusIndicator();
        statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.IDLE);
    }

    /**
     * 创建样式化按钮
     */
    private JButton createStyledButton(String text, Icon icon) {
        return new JButton(text);
    }

    /**
     * 创建分组面板
     */
    private JPanel createGroupPanel(String title, Color borderColor) {
        JPanel groupPanel = new JPanel();
        groupPanel.setLayout(new BoxLayout(groupPanel, BoxLayout.Y_AXIS));
        groupPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(borderColor, 1, true),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font(Font.SANS_SERIF, Font.BOLD, 11),  // 稍微减小字体
                borderColor
            ),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)  // 减少内边距
        ));
        groupPanel.setBackground(new Color(borderColor.getRed(), borderColor.getGreen(), borderColor.getBlue(), 20));
        return groupPanel;
    }

    /**
     * 创建分隔线
     */
    private JSeparator createSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        separator.setForeground(new Color(220, 220, 220));
        separator.setBackground(new Color(220, 220, 220));
        return separator;
    }

    /**
     * 显示加载状态
     */
    private static void onEdt(Runnable action) {
        if(SwingUtilities.isEventDispatchThread()) action.run();else SwingUtilities.invokeLater(action);
    }
    private void showLoadingState(String message) {showLoadingState(viewGeneration,message);}
    private void showLoadingState(long request,String message) {
        onEdt(() -> {
            if(request!=viewGeneration) return;
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.WORKING);
            loadingPanel.beginPreparation(request,message);loadingPanel.appendLog("Preparation: "+message);progressLabel.setText(message);
            logManager.info(message,"MinecraftMapGUI");
        });
    }
    private MinecraftResourceExtractor.ProgressListener resourceProgress(long request) {
        return (completed,total,message) -> onEdt(() -> {
            if(request==viewGeneration) {
                loadingPanel.preparationProgress(request,completed,total,message);
                loadingPanel.appendLog("Resources: "+message);
            }
        });
    }
    private void showMapLoading(long request,String message) {
        onEdt(() -> {
            if(request!=viewGeneration) return;
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.WORKING);
            loadingPanel.beginMap(request,message);progressLabel.setText(message);
        });
    }
    private void hideLoadingState() {
        final long request=viewGeneration;
        onEdt(() -> {
            if(request!=viewGeneration) return;
            applyPendingPreset();
            loadingPanel.finish(request);
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.IDLE);
        });
    }
    private void showSuccessState(String message) {
        final long request=viewGeneration;
        onEdt(() -> {
            if(request!=viewGeneration) return;
            loadingPanel.finish(request);
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.SUCCESS);
            progressLabel.setText(message);
        });
    }
    private void showErrorState(String message) {
        final long request=viewGeneration;
        onEdt(() -> {
            if(request!=viewGeneration) return;
            loadingPanel.finish(request);
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.ERROR);
            progressLabel.setText(message);logManager.error(message,"MinecraftMapGUI");
        });
    }
    private DynamicRegionManager createDynamicRegionManager() {
        final long request=viewGeneration;
        final DynamicRegionManager manager=new DynamicRegionManager(savePath,getCurrentRegionDir(),surfaceMode,renderHeightPanel.getClipY(),preview);
        showMapLoading(request,"Loading visible map regions...");
        manager.addRegionLoadListener(new DynamicRegionManager.RegionLoadListener() {
            public void onRegionLoaded(Point point,DynamicRegionManager.RegionTile tile) {onEdt(()->{if(request==viewGeneration&&manager==dynamicRegionManager) updateSelectionStats();});}
            public void onRegionUnloaded(Point point) {}
            public void onLoadingStarted(Point point) {}
            public void onLoadingFailed(Point point,Exception error) {}
            public void onViewportProgress(long revision,int settled,int total,int failed) {
                onEdt(() -> {
                    if(request!=viewGeneration || dynamicRegionManager!=manager || revision!=manager.getProgressRevision()) return;
                    if(settled>=total) {
                        loadingPanel.finish(request);
                        applyPendingPreset();
                        if(revision!=manager.getProgressRevision()) return;
                        statusIndicator.setStatus(failed==0?LoadingAnimationPanel.StatusIndicator.StatusType.IDLE:LoadingAnimationPanel.StatusIndicator.StatusType.ERROR);
                        progressLabel.setText(total==0?"No map regions in this viewport":
                            "Map regions loaded: "+settled+"/"+total+(failed==0?"":" (failed: "+failed+")"));
                    } else {
                        if(loadingPanel.getPhase()!=LoadingPhasePanel.Phase.MAP_LOADING) showMapLoading(request,"Loading visible map regions...");
                        loadingPanel.mapProgress(request,settled,total,"Map regions: "+settled+"/"+total+(failed==0?"":" (failed: "+failed+")"));
                    }
                });
            }
        });
        return manager;
    }
    private void openPreparedWorld() {
        renderAroundPlayerButton.setEnabled(true);reRenderButton.setEnabled(true);
        // No preliminary static render; the dynamic viewport is the first and only startup render.
        autoLoadCheckbox.setSelected(true);mapCanvas.enableAutoViewportManagement(false);
        if(isDynamicMode && dynamicMapCanvas!=null) {
            dynamicRegionManager=createDynamicRegionManager();
            dynamicMapCanvas.setRegionManager(dynamicRegionManager,false);
            SwingUtilities.invokeLater(() -> dynamicMapCanvas.showInitialViewport(playerPos));
        } else enableDynamicMode();
    }

    private void layoutComponents() {
        setLayout(new BorderLayout());

        // 菜单栏 (Windows 原生风格)
        setJMenuBar(createMenuBar());
        getRootPane().setTransferHandler(fileDropHandler);((JComponent)getContentPane()).setTransferHandler(fileDropHandler);
        hudTimer=new javax.swing.Timer(1000,e->{if(overlays.hud){overlays.hudText=com.minecraft.selector.core.PerformanceMonitor.heapHud()+" · "+(isDynamicMode&&dynamicRegionManager!=null?dynamicRegionManager.hudMetrics():"static viewport; tile cache n/a");repaintCanvases();}});hudTimer.start();

        // 顶部交互工具栏 (滑块/输入框等)
        add(createToolbar(), BorderLayout.NORTH);

        // 右侧地图显示区域 - 使用现代化边框
        JPanel mapPanel = new JPanel(new BorderLayout());
        mapPanel.setBorder(BorderFactory.createTitledBorder("Map Preview"));
        heightDebounce=new javax.swing.Timer(250,e -> resetSurfaceView());heightDebounce.setRepeats(false);
        renderHeightPanel=new RenderHeightPanel(y -> heightDebounce.restart());
        if(colorResources.renderY!=null) renderHeightPanel.setClipY(colorResources.renderY);
        mapPanel.add(renderHeightPanel,BorderLayout.EAST);

        JScrollPane mapScrollPane = new JScrollPane(mapCanvas);
        mapScrollPane.setMinimumSize(new Dimension(400, 300));
        mapScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        mapScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        mapPanel.add(mapScrollPane, BorderLayout.CENTER);
        mapPanel.setMinimumSize(new Dimension(400, 300));

        add(new LoadingOverlayPane(mapPanel,loadingPanel), BorderLayout.CENTER);

        // 底部状态栏
        JPanel statusPanel = new JPanel(new BorderLayout());
        JPanel leftStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftStatusPanel.add(statusIndicator);
        leftStatusPanel.add(progressLabel);
        statusPanel.add(leftStatusPanel, BorderLayout.WEST);
        statusPanel.add(coordDisplayLabel, BorderLayout.EAST);
        selectionStatsLabel.setToolTipText("Inclusive bounds. Cost is volume capacity, not actual solid blocks. Alt+arrows expand; Ctrl+Alt+arrows shrink; Shift=16.");
        statusPanel.add(selectionStatsLabel,BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(statusPanel, BorderLayout.SOUTH);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // ── 文件菜单 ──
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic('F');

        addMenuItem(fileMenu, "Select Save", e -> selectSave(), null, "Select Minecraft Save Folder");
        addMenuItem(fileMenu, "Select MCA File", e -> selectMcaFile(), null, "Select an MCA region file manually");
        addMenuItem(fileMenu, "Select MC Client", e -> selectMinecraftJar(), null, "Select Minecraft client JAR file");
        addMenuItem(fileMenu, "Select Mods Folder", e -> selectColorResources(true), null, "Load actual mod textures");
        addMenuItem(fileMenu, "Select Resource Packs", e -> selectColorResources(false), null, "Select ZIP packs in override order");
        recentMenu=new JMenu("Recent worlds");fileMenu.add(recentMenu);refreshRecentWorlds();
        recentMenu.addMenuListener(new javax.swing.event.MenuListener() {
            public void menuSelected(javax.swing.event.MenuEvent e) {refreshRecentWorlds();}
            public void menuDeselected(javax.swing.event.MenuEvent e) {}
            public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        addMenuItem(fileMenu,"Save selection preset…",e->savePreset(),null,null);
        addMenuItem(fileMenu,"Load selection preset…",e->loadPreset(),null,null);
        addMenuItem(fileMenu,"Save waypoints…",e->saveWaypoints(),null,null);
        addMenuItem(fileMenu,"Load waypoints…",e->loadWaypoints(),null,null);
        addMenuItem(fileMenu,"Export view PNG…",e->exportView(),null,null);
        fileMenu.addSeparator();
        addMenuItem(fileMenu, "Clear Cache", e -> clearPngCache(), null, "Delete PNG map cache files");
        fileMenu.addSeparator();
        addMenuItem(fileMenu, "Log", e -> openLogWindow(), null, "Open log window");
        fileMenu.addSeparator();
        addMenuItem(fileMenu, "Exit", e -> System.exit(0), null, null);
        menuBar.add(fileMenu);
        JMenu viewMenu = new JMenu("View");
        viewMenu.add(hideNetherRoofCheckbox);
        gridMenuItem=addMenuItem(viewMenu,"Map grid: Off",e->cycleGrid(),null,"G: Off / Chunk / Chunk+Region");
        addPreviewItem(viewMenu,"Transparent water","water",false);
        addPreviewItem(viewMenu,"Cave floor below Render Top Y","cave",false);
        biomeMenu=addMenuItem(viewMenu,"Biomes: Surface",e->togglePreview("biome"),null,"B: Surface / Tint / Biome overlay");
        addPreviewItem(viewMenu,"Night preview","night",false);
        addPreviewItem(viewMenu,"Contours (16 blocks)","contours",false);
        addPreviewItem(viewMenu,"Spawn / player / POI markers","markers",true);
        addPreviewItem(viewMenu,"Empty / missing chunk shading","gaps",true);
        addPreviewItem(viewMenu,"Scale bar","scale",true);
        addMenuItem(viewMenu,"World information…",e->showWorldInfo(),null,"I on map");
        addMenuItem(viewMenu,"Resource coverage…",e->showCoverage(),null,null);
        addPreviewItem(viewMenu,"Minimap overview","minimap",false);
        addPreviewItem(viewMenu,"Memory / cache / viewport HUD","hud",false);
        addPreviewItem(viewMenu,"Ore heatmap","ore",false);
        addMenuItem(viewMenu,"Scan ore heatmap from JSON…",e->scanOres(),null,"Generic ID regex / list; uses Import Y range");
        addMenuItem(viewMenu,"Cancel background scan",e->cancelP2Scan(),null,null);
        addMenuItem(viewMenu,"Trim viewport cache",e->trimViewportCache(),null,"Keep current viewport; release warm tiles");
        menuBar.add(viewMenu);
        JMenu selectionMenu=new JMenu("Selection");
        addMenuItem(selectionMenu,"Undo selection",e->activeCanvas().undoSelection(),null,"Ctrl+Z on map");
        addMenuItem(selectionMenu,"Select visible viewport",e->activeCanvas().selectVisible(),null,"Ctrl+A on map");
        addMenuItem(selectionMenu,"Edit selection bounds…",e->editSelectionBounds(),null,null);
        addMenuItem(selectionMenu,"Auto-fit Import Y",e->autoFitImportY(),null,"Space on map; loaded heights only");
        addPreviewItem(selectionMenu,"Pos1 / Pos2 left-click mode","pos",false);
        addMenuItem(selectionMenu,"Fit Import Y (opaque water/glass)",e->autoFitImportY(true),null,"Shift+Space on map");
        addMenuItem(selectionMenu,"Copy coordinates",e->copyCoordinates(),null,"Ctrl+Shift+C on map");
        addMenuItem(selectionMenu,"Confirm selection…",e->confirmSelection(),null,"Enter on map");
        JMenu tools=new JMenu("Selection tool");ButtonGroup group=new ButtonGroup();
        for(String tool:new String[]{"Box","Chunk paint","Circle"}){JRadioButtonMenuItem item=new JRadioButtonMenuItem(tool,"Box".equals(tool));group.add(item);tools.add(item);item.addActionListener(e->{overlays.selectionMode=tool;overlays.posMode=false;JCheckBoxMenuItem pos=previewItems.get("pos");if(pos!=null)pos.setSelected(false);if(!"Box".equals(tool)){overlays.addBoxMode=false;JCheckBoxMenuItem add=previewItems.get("addbox");if(add!=null)add.setSelected(false);}progressLabel.setText(selectionToolHint(tool));});}selectionMenu.add(tools);
        addPreviewItem(selectionMenu,"Add box (Alt+RMB)","addbox",false);
        previewItems.get("addbox").setToolTipText("Sticky append: plain right-drag adds another AABB. Also: Alt+RMB or Ctrl+RMB (Linux WM often steals Alt).");
        addMenuItem(selectionMenu,"Suggest selection by chunk metadata…",e->filterChunks(),null,null);
        menuBar.add(selectionMenu);
        JMenu navigate=new JMenu("Navigate");
        addMenuItem(navigate,"Jump to Spawn",e->jumpAnchor("F2"),"F2",null);
        addMenuItem(navigate,"Jump to Player (or Spawn)",e->jumpAnchor("F3"),"F3",null);
        addMenuItem(navigate,"Jump to Selection",e->jumpAnchor("F4"),"F4",null);
        addMenuItem(navigate,"Go to X/Z…",e->GoToDialog.show(this,this::jumpTo),"control G",null);
        addMenuItem(navigate,"Maximum zoom",e->activeCanvas().zoomExtents(true),null,"Home on map");
        addMenuItem(navigate,"Fit loaded content",e->activeCanvas().zoomExtents(false),null,"End on map");
        addMenuItem(navigate,"Add waypoint / POI…",e->addWaypoint(),null,null);
        addMenuItem(navigate,"Remove waypoint / POI…",e->removeWaypoint(),null,null);
        menuBar.add(navigate);

        return menuBar;
    }

    private JMenuItem addMenuItem(JMenu menu, String text, java.awt.event.ActionListener listener,
                                   String accelerator, String tooltip) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(listener);
        if (accelerator != null) {
            item.setAccelerator(KeyStroke.getKeyStroke(accelerator));
        }
        if (tooltip != null) item.setToolTipText(tooltip);
        menu.add(item);
        return item;
    }

    private JPanel createToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(200, 200, 200)),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        bar.setBackground(new Color(245, 245, 245));

        // ── Dimension ──
        bar.add(new JLabel("Dimension:"));
        bar.add(dimensionDropdown);
        bar.add(new JSeparator(SwingConstants.VERTICAL));

        // ── 操作按钮 ──
        confirmSelectionButton.addActionListener(e -> confirmSelection());
        bar.add(confirmSelectionButton);
        JComboBox<SelectionMath.Snap> snap=new JComboBox<>(SelectionMath.Snap.values());
        snap.setToolTipText("Snap inclusive selection outward to chunk (16) or region (512) boundaries");
        snap.addActionListener(e->{selectionSnap=(SelectionMath.Snap)snap.getSelectedItem();mapCanvas.setSnapMode(selectionSnap);if(dynamicMapCanvas!=null) dynamicMapCanvas.setSnapMode(selectionSnap);});
        bar.add(new JLabel("Snap:"));bar.add(snap);
        JButton bounds=new JButton("Bounds…");bounds.addActionListener(e->editSelectionBounds());bar.add(bounds);
        JButton trim=new JButton("Trim cache");trim.addActionListener(e->trimViewportCache());bar.add(trim);

        bar.add(new JSeparator(SwingConstants.VERTICAL));

        // ── 高度设置 ──
        bar.add(new JLabel("Import Y:"));
        heightRangeSlider.setPreferredSize(new Dimension(120, 22));
        // Import range retains its model; the visible controls are standard text fields.
        bar.add(new JLabel("Min Y:"));
        bar.add(minYEntry);
        bar.add(new JLabel("Max Y:"));
        bar.add(maxYEntry);
        minYEntry.addActionListener(e -> {
            try { heightRangeSlider.setLowerValue(Integer.parseInt(minYEntry.getText())); }
            catch (NumberFormatException ex) { minYEntry.setText(String.valueOf(heightRangeSlider.getLowerValue())); }
        });
        maxYEntry.addActionListener(e -> {
            try { heightRangeSlider.setUpperValue(Integer.parseInt(maxYEntry.getText())); }
            catch (NumberFormatException ex) { maxYEntry.setText(String.valueOf(heightRangeSlider.getUpperValue())); }
        });

        bar.add(new JSeparator(SwingConstants.VERTICAL));

        // ── 坐标跳转 ──
        bar.add(new JLabel("X:"));
        bar.add(xCoordEntry);
        bar.add(new JLabel("Z:"));
        bar.add(zCoordEntry);
        JButton gotoBtn = new JButton("Jump");
        gotoBtn.addActionListener(e -> gotoCoords());
        bar.add(gotoBtn);

        bar.add(new JSeparator(SwingConstants.VERTICAL));

        // ── 信息 ──
        bar.add(saveInfoLabel);
        bar.add(playerPosLabel);
        bar.add(regionLabel);

        return bar;
    }

    private JPanel createControlPanel() {
        // 保留空实现，防止调用异常
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(0, 0));
        panel.setMinimumSize(new Dimension(0, 0));
        return panel;
    }
    
    private void setupEventHandlers() {
        // 地图画布事件处理将在MapCanvas类中实现
    }

    // renderSelectedRegion() 方法已删除，因为地图范围选择输入框已被移除

    /**
     * 获取LOD级别
     */
    private int getLodLevel() {
        String selected = (String) lodDropdown.getSelectedItem();
        if (selected.contains("1 (")) return 1;
        if (selected.contains("2 (")) return 2;
        if (selected.contains("4 (")) return 4;
        if (selected.contains("8 (")) return 8;
        return 1; // 默认或自动
    }
    
    /**
     * 当前维度对应的 region 目录
     */
    private File getCurrentRegionDir() {
        if (currentDimension != null) {
            return currentDimension.getRegionDir();
        }
        return WorldPathResolver.getOverworldRegionDir(savePath);
    }

    /**
     * 刷新维度下拉框内容（在EDT调用）
     */
    private void refreshDimensionDropdown() {
        if (dimensionDropdown == null) return;
        updatingDimensionDropdown = true;
        try {
            dimensionDropdown.removeAllItems();
            if (availableDimensions.isEmpty()) {
                dimensionDropdown.setEnabled(false);
                return;
            }
            for (WorldPathResolver.DimensionInfo dim : availableDimensions) {
                dimensionDropdown.addItem(dim);
            }
            // 选中当前维度
            if (currentDimension != null) {
                dimensionDropdown.setSelectedItem(currentDimension);
            }
            dimensionDropdown.setEnabled(true);
        } finally {
            updatingDimensionDropdown = false;
        }
    }

    /**
     * 切换维度：重建动态区域管理器与画布，重新适配视图
     */
    private void switchDimension(WorldPathResolver.DimensionInfo dim) {
        if (dim == null) return;
        if (currentDimension != null && dim.getId().equals(currentDimension.getId())) return;
        if (savePath == null) return;

        currentDimension = dim;overlays.dimension=dim.getId();lastMcaPath=null;
        mapCanvas.resetSelection();if(dynamicMapCanvas!=null) dynamicMapCanvas.resetSelection();
        cancelP2Scan();++viewGeneration;
        dynamicMapManager.clear();
        mapCanvas.setImage(null);
        mapImage = null;
        logManager.info("Switch dimension: " + dim.getId() + " -> " + dim.getRegionDir().getAbsolutePath(), "MinecraftMapGUI");

        // 如果当前处于动态模式，重建动态区域管理器并清空旧缓存
        if (isDynamicMode && dynamicMapCanvas != null && dynamicRegionManager != null) {
            try {
                dynamicMapCanvas.cleanup();
                dynamicRegionManager.shutdown(); // stop old dimension jobs; do not retain its pool/cache
                dynamicRegionManager = createDynamicRegionManager();
                dynamicMapCanvas.setRegionManager(dynamicRegionManager);
                SwingUtilities.invokeLater(() -> {
                    dynamicMapCanvas.showInitialViewport(playerPos);
                    dynamicMapCanvas.requestFocus();
                    progressLabel.setText("Dimension switched: " + dim.getDisplayName());
                });
            } catch (Exception ex) {
                logManager.error("Failed to switch dimension", "MinecraftMapGUI", ex);
                progressLabel.setText("Failed to switch dimension: " + ex.getMessage());
            }
        } else {
            // 静态模式：提示切换后重新渲染
            progressLabel.setText("Dimension switched: " + dim.getDisplayName() + " - re-render to update");
        }
    }

    /**
     * 选择Minecraft存档
     */
    private void selectSave() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Select Minecraft Save Folder");
        
        // 设置默认路径
        String defaultPath = getDefaultSavesPath();
        if (defaultPath != null) {
            fileChooser.setCurrentDirectory(new File(defaultPath));
        }
        
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedDir = fileChooser.getSelectedFile();
            cancelP2Scan();++viewGeneration;
            dynamicMapManager.clear();
            mapCanvas.setImage(null);
            mapImage = null;
            if (dynamicRegionManager != null) {
                dynamicRegionManager.shutdown();
                dynamicRegionManager = null;
            }
            if (dynamicMapCanvas != null) dynamicMapCanvas.setRegionManager(null);
            ++colorGeneration;
            lastMcaPath=null;singleResourcePath=null;
            savePath = selectedDir.getAbsolutePath();
            saveInfoLabel.setText("Current save: " + selectedDir.getName());
            
            dimensionDropdown.setEnabled(false);
            showLoadingState("Reading save info...");
            
            // 在后台线程中读取存档信息
            final long request=viewGeneration;
            final String requestedWorld=savePath;final ResourceOptions requestedResources=colorResources.copy();
            SwingWorker<PreparedWorld, Void> worker = new SwingWorker<PreparedWorld, Void>() {
                protected PreparedWorld doInBackground() {return loadSaveData(request,requestedWorld,requestedResources);}
                protected void done() {
                    if(request!=viewGeneration) return;
                    try {applyPreparedWorld(get());}
                    catch(Exception error) {showErrorState("World loading failed: "+error.getMessage());}
                }
            };
            worker.execute();
        }
    }
    
    /**
     * 选择MCA文件
     */
    private void selectMcaFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setDialogTitle("Select MCA region file");
        
        FileNameExtensionFilter filter = new FileNameExtensionFilter("Minecraft region files (*.mca)", "mca");
        fileChooser.setFileFilter(filter);
        
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            renderSingleMcaFile(selectedFile.getAbsolutePath());
        }
    }
    
    /**
     * 获取默认的Minecraft存档路径
     */
    private String getDefaultSavesPath() {
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");
        
        if (os.contains("win")) {
            return System.getenv("APPDATA") + "\\.minecraft\\saves";
        } else if (os.contains("mac")) {
            return userHome + "/Library/Application Support/minecraft/saves";
        } else {
            return userHome + "/.minecraft/saves";
        }
    }
    
    /**
     * 加载存档数据
     */
    private static final class PreparedWorld {
        final List<WorldPathResolver.DimensionInfo> dimensions;
        final BlockColors.Palette palette;
        final double[] player;
        final WorldAnchors anchors;
        PreparedWorld(List<WorldPathResolver.DimensionInfo> dimensions,BlockColors.Palette palette,double[] player,WorldAnchors anchors) {
            this.dimensions=dimensions;this.palette=palette;this.player=player;this.anchors=anchors;
        }
    }
    private PreparedWorld loadSaveData(long request,String world,ResourceOptions resources) {
        showLoadingState(request,"Parsing world dimensions...");
        List<WorldPathResolver.DimensionInfo> dimensions=WorldPathResolver.discoverDimensions(world);
        if(dimensions.isEmpty()) dimensions.add(new WorldPathResolver.DimensionInfo(
            "minecraft:overworld","Overworld",WorldPathResolver.getOverworldRegionDir(world)));
        showLoadingState(request,"Extracting vanilla / mod / resource-pack colors...");
        BlockColors.Palette palette=BlockColors.loadPalette(world,resources,resourceProgress(request));
        resourceProgress(request).onProgress(0,0,"Reading player data and preparing the initial viewport...");
        File levelDat=new File(world,"level.dat");
        double[] player=levelDat.isFile()?readPlayerPos(levelDat.getAbsolutePath()):null;
        if(player==null) logManager.warn("No player position; opening nearest available map region","MinecraftMapGUI");
        WorldAnchors anchors=WorldAnchors.empty();
        try {if(levelDat.isFile()) anchors=WorldAnchors.read(new File(world));}
        catch(Exception e) {logManager.warn("World anchor metadata unavailable: "+e.getMessage(),"MinecraftMapGUI");}
        return new PreparedWorld(dimensions,palette,player,anchors);
    }
    private void applyPreparedWorld(PreparedWorld world) {
        availableDimensions=world.dimensions;currentDimension=availableDimensions.get(0);
        BlockColors.setPalette(world.palette);playerPos=world.player;worldAnchors=world.anchors;
        overlays.anchors=worldAnchors;overlays.dimension=currentDimension.getId();
        try {recentWorlds.add(new File(savePath));refreshRecentWorlds();}catch(IOException e) {logManager.warn("Recent worlds: "+e.getMessage(),"MinecraftMapGUI");}
        mapCanvas.resetSelection();if(dynamicMapCanvas!=null) dynamicMapCanvas.resetSelection();
        refreshDimensionDropdown();
        playerPosLabel.setText(playerPos==null?"Player: —":String.format("Player: X:%.1f, Y:%.1f, Z:%.1f",playerPos[0],playerPos[1],playerPos[2]));
        openPreparedWorld();
    }

    /**
     * 手动选择Minecraft JAR文件
     */
    private void selectMinecraftJar() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".jar");
            }

            @Override
            public String getDescription() {
                return "Minecraft JAR files (*.jar)";
            }
        });

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            extractColorsFromJar(selectedFile.getAbsolutePath());
        }
    }

    /**
     * 从指定JAR文件提取颜色
     */
    private void extractColorsFromJar(String jarPath) {
        jarFilePath=jarPath;colorResources.jars.clear();colorResources.jars.add(jarPath);reloadColorResources();
    }
    private void selectColorResources(boolean mods) {
        JFileChooser chooser=new JFileChooser();
        chooser.setFileSelectionMode(mods?JFileChooser.DIRECTORIES_ONLY:JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(!mods);
        if(!mods) chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Resource packs (*.zip)","zip"));
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION) return;
        if(mods) {
            colorResources.mods.clear();
            File[] files=chooser.getSelectedFile().listFiles((dir,name)->name.endsWith(".jar"));
            if(files!=null) {java.util.Arrays.sort(files);for(File file:files) colorResources.mods.add(file.getAbsolutePath());}
        } else {
            colorResources.packs.clear();for(File file:chooser.getSelectedFiles()) colorResources.packs.add(file.getAbsolutePath());
        }
        reloadColorResources();
    }
    private void reloadColorResources() {
        singleResourcePath=null;
        cancelP2Scan();++viewGeneration;
        final long generation=++colorGeneration;
        final long request=viewGeneration;
        final boolean dynamic=isDynamicMode;
        if(dynamicRegionManager!=null) {dynamicRegionManager.shutdown();dynamicRegionManager=null;}
        if(dynamicMapCanvas!=null) dynamicMapCanvas.setRegionManager(null);
        mapImage=null;mapCanvas.setImage(null);
        final String path=savePath!=null?savePath:lastMcaPath;
        final ResourceOptions requested=colorResources.copy();
        showLoadingState("Loading resource colors...");
        new SwingWorker<BlockColors.Palette,Void>() {
            protected BlockColors.Palette doInBackground() {
                return BlockColors.loadPalette(path,requested,resourceProgress(request));
            }
            protected void done() {
                if(generation!=colorGeneration) return;
                try {
                    BlockColors.setPalette(get());
                    if(dynamic && savePath!=null) {
                        dynamicRegionManager=createDynamicRegionManager();
                        dynamicMapCanvas.setRegionManager(dynamicRegionManager);
                    } else if(lastMcaPath!=null) renderMap(lastMcaPath);
                    else if(savePath!=null && playerPos!=null) {
                        showMapLoading(request,"Loading map regions...");renderAroundPlayer();
                    } else showSuccessState("Resource colors loaded");
                } catch(Exception e) {showErrorState("Resource colors failed: "+e.getMessage());}
            }
        }.execute();
    }

    /**
     * 读取level.dat中的玩家位置
     */
    private double[] readPlayerPos(String levelDatPath) {
        try {
            NBTReader.NBTCompound nbtFile = NBTReader.readFromFile(levelDatPath);

            // 尝试获取玩家位置数据
            try {
                // 单人模式
                NBTReader.NBTCompound data = nbtFile.getCompound("Data");
                NBTReader.NBTCompound player = data.getCompound("Player");
                NBTReader.NBTList pos = player.getList("Pos");

                double x = ((NBTReader.NBTDouble) pos.get(0)).getValue();
                double y = ((NBTReader.NBTDouble) pos.get(1)).getValue();
                double z = ((NBTReader.NBTDouble) pos.get(2)).getValue();

                return new double[]{x, y, z};
            } catch (Exception e) {
                // 多人模式或其他格式
                try {
                    NBTReader.NBTCompound data = nbtFile.getCompound("Data");
                    int spawnX = data.getInt("SpawnX");
                    int spawnY = data.getInt("SpawnY");
                    int spawnZ = data.getInt("SpawnZ");

                    return new double[]{spawnX, spawnY, spawnZ};
                } catch (Exception e2) {
                    return null;
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading level.dat: " + e.getMessage());
            return null;
        }
    }

    /**
     * 渲染玩家周围的区域
     */
    private void renderAroundPlayer() {
        if(loadingPanel.getPhase()==LoadingPhasePanel.Phase.PREPARING) return;
        if (playerPos == null) {return;}
        if(isDynamicMode && dynamicMapCanvas!=null && dynamicRegionManager!=null) {
            dynamicMapCanvas.jumpToWorldCoordinate(new Point((int)Math.floor(playerPos[0]),(int)Math.floor(playerPos[2])));
            return;
        }

        // 计算玩家所在的区域文件
        int centerRegionX = (int) Math.floor(playerPos[0] / 512);
        int centerRegionZ = (int) Math.floor(playerPos[2] / 512);

        // 获取MCA范围设置
        int mcaRange = getMcaRange();
        int halfRange = mcaRange / 2;

        // 收集需要渲染的MCA文件
        java.util.List<String> mcaFiles = new java.util.ArrayList<>();
        File regionDir = getCurrentRegionDir();
        
        logManager.debug("Start rendering around player", "MinecraftMapGUI");
        logManager.info("Save path: " + savePath, "MinecraftMapGUI");
        logManager.info("Region dir: " + regionDir.getAbsolutePath(), "MinecraftMapGUI");
        logManager.info("Region dir exists: " + regionDir.exists(), "MinecraftMapGUI");
        logManager.info("Player: X=" + playerPos[0] + ", Z=" + playerPos[2], "MinecraftMapGUI");
        logManager.info("Center region: r." + centerRegionX + "." + centerRegionZ + ".mca", "MinecraftMapGUI");
        logManager.info("Search range: " + mcaRange + "x" + mcaRange, "MinecraftMapGUI");
        
        if (regionDir.exists()) {
            File[] allFiles = regionDir.listFiles();
            if (allFiles != null) {
                logManager.debug("Files in region dir: " + allFiles.length, "MinecraftMapGUI");
                for (File file : allFiles) {
                    logManager.debug("Found file: " + file.getName(), "MinecraftMapGUI");
                }
            }
        }
        
        for (int x = centerRegionX - halfRange; x <= centerRegionX + halfRange; x++) {
            for (int z = centerRegionZ - halfRange; z <= centerRegionZ + halfRange; z++) {
                String regionFile = String.format("r.%d.%d.mca", x, z);
                File regionPath = new File(regionDir, regionFile);
                logManager.debug("Checking file: " + regionFile + " - exists: " + regionPath.exists(), "MinecraftMapGUI");
                if (regionPath.exists()) {
                    mcaFiles.add(regionPath.getAbsolutePath());
                }
            }
        }

        if (!mcaFiles.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                progressLabel.setText(String.format("Rendering %dx%d regions (%d files), player: X:%.1f, Z:%.1f...",
                    mcaRange, mcaRange, mcaFiles.size(), playerPos[0], playerPos[2]));
            });

            // 保存区域坐标信息
            currentRegion = new Point(centerRegionX, centerRegionZ);

            // 渲染多个MCA文件
            if (mcaFiles.size() == 1) {
                // 单个文件，使用原有方法
                renderMap(mcaFiles.get(0));
            } else {
                // 多个文件，使用新的多文件渲染方法
                renderMultipleMcaFiles(mcaFiles, centerRegionX - halfRange, centerRegionZ - halfRange, mcaRange);
            }
        } else {
            SwingUtilities.invokeLater(() -> {
                progressLabel.setText(String.format("No region files around player (center: r.%d.%d.mca)", centerRegionX, centerRegionZ));
                hideLoadingState();
            });
        }
    }

    /**
     * 获取MCA范围设置
     */
    private int getMcaRange() {
        String selected = (String) mcaRangeDropdown.getSelectedItem();
        if (selected.contains("3x3")) return 3;
        if (selected.contains("5x5")) return 5;
        if (selected.contains("7x7")) return 7;
        return 1; // 默认1x1
    }

    /**
     * 渲染多个MCA文件
     */
    private void renderMultipleMcaFiles(java.util.List<String> mcaFiles, int startRegionX, int startRegionZ, int gridSize) {
        showMapLoading(viewGeneration,"Loading map regions...");
        final long requestGeneration = viewGeneration;
        final MapRenderer.SurfaceMode requestedMode = surfaceMode;
        final PreviewOptions requestedPreview=preview;
        final Integer requestedTop=renderHeightPanel.getClipY();
        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                // 创建进度回调
                final java.util.concurrent.atomic.AtomicInteger completedRegions=new java.util.concurrent.atomic.AtomicInteger();
                MapRenderer.ProgressCallback progressCallback = new MapRenderer.ProgressCallback() {
                    @Override
                    public void onProgress(int processed, int total, double speed, Set<String> foundBlocks) {
                        if (total == 0 || requestGeneration!=viewGeneration) return;

                        final int completed=completedRegions.get();
                        final int units=completed*1000+(int)(1000L*processed/total);
                        SwingUtilities.invokeLater(() -> {
                            if(requestGeneration!=viewGeneration) return;
                            loadingPanel.mapProgress(requestGeneration,units,mcaFiles.size()*1000,
                                String.format("Map regions: %d/%d; chunks: %d/%d (%.1f/s)",completed,mcaFiles.size(),processed,total,speed));
                        });
                    }
                    
                    @Override
                    public void onProgressiveRender(BufferedImage partialImage, int completedChunks, int totalChunks) {
                        // GUI中暂时不需要渐进式渲染回调
                    }
                };

                // 创建地图渲染器
                int maxWorkers = Math.min(Runtime.getRuntime().availableProcessors(), 4);
                MapRenderer renderer = new MapRenderer(maxWorkers, progressCallback, requestedMode,requestedTop,BlockColors.snapshot(),requestedPreview);

                try {
                    // 计算区域范围
                    int minRegionX = Integer.MAX_VALUE;
                    int maxRegionX = Integer.MIN_VALUE;
                    int minRegionZ = Integer.MAX_VALUE;
                    int maxRegionZ = Integer.MIN_VALUE;

                    // 解析所有MCA文件的坐标，找到边界
                    Map<String, int[]> regionCoords = new HashMap<>();
                    for (String mcaFile : mcaFiles) {
                        String fileName = new File(mcaFile).getName();
                        String[] parts = fileName.replace("r.", "").replace(".mca", "").split("\\.");
                        if (parts.length == 2) {
                            int regionX = Integer.parseInt(parts[0]);
                            int regionZ = Integer.parseInt(parts[1]);

                            regionCoords.put(mcaFile, new int[]{regionX, regionZ});

                            minRegionX = Math.min(minRegionX, regionX);
                            maxRegionX = Math.max(maxRegionX, regionX);
                            minRegionZ = Math.min(minRegionZ, regionZ);
                            maxRegionZ = Math.max(maxRegionZ, regionZ);
                        }
                    }

                    // 计算总图像大小
                    int totalWidth = (maxRegionX - minRegionX + 1) * 512;
                    int totalHeight = (maxRegionZ - minRegionZ + 1) * 512;

                    System.out.printf("Region range: (%d,%d) to (%d,%d), total size: %dx%d\n",
                        minRegionX, minRegionZ, maxRegionX, maxRegionZ, totalWidth, totalHeight);

                    // 创建大图像
                    RenderedMap combinedImage = new RenderedMap(totalWidth, totalHeight);
                    Graphics2D g2d = combinedImage.createGraphics();

                    // 设置高质量渲染
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                    // 填充背景色
                    g2d.setColor(new Color(32, 32, 32));
                    g2d.fillRect(0, 0, totalWidth, totalHeight);

                    // 渲染每个MCA文件
                    int processedCount = 0;
                    for (String mcaFile : mcaFiles) {
                        int[] coords = regionCoords.get(mcaFile);
                        if (coords != null) {
                            int regionX = coords[0];
                            int regionZ = coords[1];

                            System.out.printf("Rendering region %d/%d: r.%d.%d.mca\n",
                                ++processedCount, mcaFiles.size(), regionX, regionZ);

                            // 渲染单个区域
                            MapRenderer.BlockInfo[][] topBlocks = renderer.getTopBlocks(mcaFile, 32, 1);
                            if (topBlocks != null) {
                                BufferedImage regionImage = renderer.renderToPng(topBlocks, 1);
                                if (regionImage != null) {
                                    // 计算在大图像中的正确位置
                                    int offsetX = (regionX - minRegionX) * 512;
                                    int offsetY = (regionZ - minRegionZ) * 512;

                                    System.out.printf("  -> placed at: (%d, %d)\n", offsetX, offsetY);

                                    g2d.drawImage(regionImage, offsetX, offsetY, null);
                                    combinedImage.copyHeightsFrom(regionImage, offsetX, offsetY);
                                }
                            }
                        }
                        completedRegions.incrementAndGet();
                    }

                    g2d.dispose();

                    // 保存实际的起始坐标信息到类成员变量中（用于后续坐标映射）
                    lastMultiRenderMinRegionX = minRegionX;
                    lastMultiRenderMinRegionZ = minRegionZ;

                    return combinedImage;

                } finally {
                    renderer.shutdown();
                }
            }

            @Override
            protected void done() {
                if (requestGeneration != viewGeneration) return;
                try {
                    BufferedImage image = get();
                    if (image != null) {
                        mapImage = image;
                        mapCanvas.setImage(image); // 手动渲染时不重置视图

                        // 设置世界坐标映射
                        int worldMinX = lastMultiRenderMinRegionX * 512;
                        int worldMinZ = lastMultiRenderMinRegionZ * 512;
                        double pixelsPerBlock = 1.0;  // 1像素 = 1方块
                        mapCanvas.setWorldCoordinateMapping(worldMinX, worldMinZ, pixelsPerBlock);

                        // renderButton 已删除
                        renderAroundPlayerButton.setEnabled(true);

                        progressLabel.setText(String.format("Multi-region render complete, image size: %dx%d",
                                                          image.getWidth(), image.getHeight()));
                        progressBar.setValue(100);
                        progressBar.setString("Done");
                        hideLoadingState();

                        // 保存图像
                        saveRenderedImage(image, String.format("multi_region_%dx%d", gridSize, gridSize));

                    } else {
                        progressLabel.setText("Multi-region render failed: cannot get block data");
                        progressBar.setValue(0);
                        progressBar.setString("Failed");
                        hideLoadingState();
                    }
                } catch (Exception e) {
                    logManager.error("Map operation failed: "+e.getMessage(),"MinecraftMapGUI",e);
                    JOptionPane.showMessageDialog(MinecraftMapGUI.this,
                                                "Error rendering multi-region map: " + e.getMessage(),
                                                "Error", JOptionPane.ERROR_MESSAGE);
                    progressLabel.setText("Multi-region render failed");
                    progressBar.setValue(0);
                    progressBar.setString("Failed");
                    hideLoadingState();
                }
            }
        };

        worker.execute();
    }

    /**
     * 渲染单个MCA文件
     */
    private void renderSingleMcaFile(String mcaFilePath) {
        cancelP2Scan();++viewGeneration;++colorGeneration;
        if(dynamicRegionManager!=null) {dynamicRegionManager.shutdown();dynamicRegionManager=null;}
        if(isDynamicMode) disableDynamicMode();
        savePath=null;worldAnchors=WorldAnchors.empty();overlays.anchors=worldAnchors;overlays.dimension="minecraft:overworld";currentDimension=null;availableDimensions=new ArrayList<>();refreshDimensionDropdown();mapCanvas.resetSelection();lastMcaPath=mcaFilePath;currentRegion=null;
        String[] regionParts=new File(mcaFilePath).getName().split("\\.");
        if(regionParts.length==4) try {currentRegion=new Point(Integer.parseInt(regionParts[1]),Integer.parseInt(regionParts[2]));}
        catch(NumberFormatException ignored) {currentRegion=null;}
        showLoadingState("Preparing MCA resources...");
        SwingUtilities.invokeLater(() -> {
            progressLabel.setText("Rendering MCA file: " + new File(mcaFilePath).getName());
        });

        renderMap(mcaFilePath);
    }

    /**
     * 渲染地图
     */
    private void renderMap(String regionPath) {
        final ResourceOptions requestedResources=colorResources.copy();
        final boolean needsResources=regionPath.equals(lastMcaPath) && !regionPath.equals(singleResourcePath);
        final BlockColors.Palette requestedPalette=BlockColors.snapshot();
        final long requestGeneration = viewGeneration;
        final MapRenderer.SurfaceMode requestedMode = surfaceMode;
        final PreviewOptions requestedPreview=preview;
        final Integer requestedTop=renderHeightPanel.getClipY();
        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            private BlockColors.Palette renderedPalette=requestedPalette;
            @Override
            protected BufferedImage doInBackground() throws Exception {
                if(needsResources) renderedPalette=BlockColors.loadPalette(regionPath,requestedResources,resourceProgress(requestGeneration));
                if(requestGeneration!=viewGeneration) return null;
                showMapLoading(requestGeneration,"Loading map regions...");
                // 创建进度回调
                MapRenderer.ProgressCallback progressCallback = new MapRenderer.ProgressCallback() {
                    @Override
                    public void onProgress(int processed, int total, double speed, Set<String> foundBlocks) {
                        if (total == 0 || requestGeneration!=viewGeneration) return;

                        int percent = Math.min(100, processed * 100 / total);
                        SwingUtilities.invokeLater(() -> {
                            if(requestGeneration!=viewGeneration || loadingPanel.getPhase()!=LoadingPhasePanel.Phase.MAP_LOADING) return;
                            progressBar.setIndeterminate(false);
                            progressBar.setValue(percent);
                            progressBar.setString(String.format("%d%% (%d/%d) %.1f chunks/s",
                                                               percent, processed, total, speed));
                        });
                    }
                    
                    @Override
                    public void onProgressiveRender(BufferedImage partialImage, int completedChunks, int totalChunks) {
                        // GUI中暂时不需要渐进式渲染回调
                    }
                };

                // 创建地图渲染器
                int maxWorkers = Math.min(Runtime.getRuntime().availableProcessors(), 4); // GUI模式使用较少线程
                MapRenderer renderer = new MapRenderer(maxWorkers, progressCallback, requestedMode,requestedTop,renderedPalette,requestedPreview);

                try {
                    // 渲染区块
                    MapRenderer.BlockInfo[][] topBlocks = renderer.getTopBlocks(regionPath, 32, 1);

                    if (topBlocks != null) {
                        
                        // 渲染图像
                        BufferedImage image = renderer.renderToPng(topBlocks, 1);

                        // 保存图像到当前目录
                        if (image != null) {
                            saveRenderedImage(image, regionPath);
                        }

                        return image;
                    }

                    return null;
                } finally {
                    renderer.shutdown();
                }
            }

            @Override
            protected void done() {
                if (requestGeneration != viewGeneration) return;
                try {
                    BufferedImage image = get();
                    if (image != null) {
                        if(needsResources) {BlockColors.setPalette(renderedPalette);singleResourcePath=regionPath;}
                        if(!java.util.Objects.equals(requestedTop,renderHeightPanel.getClipY()) || requestedMode!=surfaceMode) {
                            resetSurfaceView();return;
                        }
                        mapImage = image;
                        mapCanvas.setImage(image); // 手动渲染时不重置视图

                        // 设置世界坐标映射 (一个区域文件是512x512方块)
                        if (currentRegion != null) {
                            int worldMinX = currentRegion.x * 512;
                            int worldMinZ = currentRegion.y * 512;
                            double pixelsPerBlock = (double) image.getWidth() / 512.0;
                            mapCanvas.setWorldCoordinateMapping(worldMinX, worldMinZ, pixelsPerBlock);
                        }

                        // renderButton 已删除

                        progressLabel.setText(String.format("Map render complete, image size: %dx%d",
                                                          image.getWidth(), image.getHeight()));
                        progressBar.setValue(100);
                        progressBar.setString("Done");
                        hideLoadingState();
                    } else {
                        progressLabel.setText("Map render failed: cannot get block data");
                        progressBar.setValue(0);
                        progressBar.setString("Failed");
                        hideLoadingState();
                    }
                } catch (Exception e) {
                    logManager.error("Map operation failed: "+e.getMessage(),"MinecraftMapGUI",e);
                    JOptionPane.showMessageDialog(MinecraftMapGUI.this,
                                                "Error rendering map: " + e.getMessage(),
                                                "Error", JOptionPane.ERROR_MESSAGE);
                    progressLabel.setText("Map render failed");
                    progressBar.setValue(0);
                    progressBar.setString("Failed");
                    hideLoadingState();
                }
            }
        };

        worker.execute();
    }







    /**
     * 保存渲染的图像到当前目录
     */
    private void saveRenderedImage(BufferedImage image, String regionPath) {
        try {
            // 从区域文件路径提取文件名
            File regionFile = new File(regionPath);
            String regionFileName = regionFile.getName();
            String baseName = regionFileName.replaceAll("\\.mca$", "");

            // 生成时间戳
            long timestamp = System.currentTimeMillis();

            // 在当前工作目录生成输出文件名
            String currentDir = System.getProperty("user.dir");
            String outputFileName = String.format("%s_map_%d.png", baseName, timestamp);
            String outputPath = new File(currentDir, outputFileName).getAbsolutePath();

            // 保存图像
            javax.imageio.ImageIO.write(image, "PNG", new File(outputPath));

            // 更新文件信息标签
            SwingUtilities.invokeLater(() -> {
                fileInfoLabel.setText(String.format("<html>Rendered image saved:<br>%s<br><br>Size: %dx%d</html>",
                                                   outputPath, image.getWidth(), image.getHeight()));
                progressLabel.setText("Image saved to: " + outputFileName);
            });

            System.out.println("Image saved to: " + outputPath);

        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, "Failed to save image: " + e.getMessage(),
                                            "Error", JOptionPane.ERROR_MESSAGE);
            });
            System.err.println("Failed to save image: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 保存渲染数据（图像和JSON）
     */
    private void saveRenderedData(BufferedImage image, int minX, int maxX, int minZ, int maxZ) {
        try {
            long timestamp = System.currentTimeMillis();
            String baseName = String.format("region_%d_%d_%d_%d", minX, minZ, maxX, maxZ);

            // 保存图像
            String imageFileName = String.format("%s_map_%d.png", baseName, timestamp);
            String imagePath = new File(programDir, imageFileName).getAbsolutePath();
            javax.imageio.ImageIO.write(image, "PNG", new File(imagePath));

            // 更新文件信息
            SwingUtilities.invokeLater(() -> {
                fileInfoLabel.setText(String.format("<html>Saved:<br>%s<br>Size: %dx%d</html>",
                                                   imageFileName, image.getWidth(), image.getHeight()));
            });

            System.out.println("Map saved to: " + imagePath);

        } catch (Exception e) {
            System.err.println("Failed to save data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 跳转到指定坐标
     */
    private void gotoCoords() {
        try {
            int x = Integer.parseInt(xCoordEntry.getText().trim());
            int z = Integer.parseInt(zCoordEntry.getText().trim());

            // 动态模式下使用动态地图画布
            if (isDynamicMode && dynamicMapCanvas != null) {
                boolean ok = dynamicMapCanvas.jumpToWorldCoordinate(new Point(x, z));
                if (ok) {
                    progressLabel.setText(String.format("Jumped to coordinates (%d, %d)", x, z));
                } else {
                    progressLabel.setText(String.format("Coordinates (%d, %d) have no map data nearby", x, z));
                }
            } else if (mapCanvas != null && mapCanvas.getImage() != null) {
                // 将世界坐标转换为图像坐标
                boolean ok = mapCanvas.jumpToWorldCoordinate(new Point(x, z));
                if (ok) {
                    progressLabel.setText(String.format("Jumped to coordinates (%d, %d)", x, z));
                } else {
                    progressLabel.setText(String.format("Coordinates (%d, %d) are outside the loaded map", x, z));
                }
            } else {
                progressLabel.setText("Please load a map before jumping");
            }

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Please enter valid coordinate numbers", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }



    /**
     * 确认选择区域
     */
    private SelectionCanvas activeCanvas() {return isDynamicMode&&dynamicMapCanvas!=null?dynamicMapCanvas:mapCanvas;}
    private void configureCanvas(MapCanvas canvas) {
        canvas.setSelectionChangeListener(b->updateSelectionStats());canvas.setCommandListener(this::selectionCommand);
        canvas.setTransferHandler(fileDropHandler);canvas.setOverlays(overlays);canvas.setImportFloorListener(this::setImportFloor);canvas.setSnapMode(selectionSnap);canvas.setGridMode(gridMode);
    }
    private void configureCanvas(DynamicMapCanvas canvas) {
        canvas.setSelectionChangeListener(b->updateSelectionStats());canvas.setCommandListener(this::selectionCommand);
        canvas.setTransferHandler(fileDropHandler);canvas.setOverlays(overlays);canvas.setImportFloorListener(this::setImportFloor);canvas.setSnapMode(selectionSnap);canvas.setGridMode(gridMode);
    }
    private void selectionCommand(String command) {
        switch(command) {
            case "fit":autoFitImportY(false);break;
            case "fitOpaque":autoFitImportY(true);break;
            case "water":case "cave":case "biome":case "night":case "contours":case "pos":togglePreview(command);break;
            case "info":showWorldInfo();break;
            case "grid":cycleGrid();break;
            case "goto":GoToDialog.show(this,this::jumpTo);break;
            case "copy":copyCoordinates();break;
            default:jumpAnchor(command);
        }
    }
    private void updateSelectionStats() {
        if(mapCanvas==null||heightRangeSlider==null) return;
        overlays.minY=heightRangeSlider.getLowerValue();overlays.maxY=heightRangeSlider.getUpperValue();repaintCanvases();
        SelectionMath.Bounds b=activeCanvas().getSelectionBounds();
        if(confirmSelectionButton!=null) confirmSelectionButton.setEnabled(b!=null);
        if(b==null) {selectionStatsLabel.setText("No selection");selectionStatsLabel.setForeground(UIManager.getColor("Label.foreground"));return;}
        SelectionStats stats=new SelectionStats(b,heightRangeSlider.getLowerValue(),heightRangeSlider.getUpperValue());
        selectionStatsLabel.setText(stats.toString()+" · checking cached columns…");queueSelectionCoverage();selectionStatsLabel.setForeground(stats.isLarge()?new Color(190,80,0):UIManager.getColor("Label.foreground"));
    }
    private void setImportFloor(int y) {
        if(y==RenderedMap.NO_HEIGHT) {progressLabel.setText("No cached height at this column; Import Y unchanged");return;}
        heightRangeSlider.setLowerValue(y);
        progressLabel.setText("Import min-Y = "+heightRangeSlider.getLowerValue()+" (cached height "+y+")");
    }
    private void autoFitImportY() {autoFitImportY(false);}
    private void autoFitImportY(boolean opaque) {
        final SelectionCanvas canvas=activeCanvas();final SelectionMath.Bounds b=canvas.getSelectionBounds();
        if(b==null) {progressLabel.setText("Select an area before fitting Import Y");return;}
        final long generation=viewGeneration;
        progressLabel.setText("Fitting Import Y from cached selection heights…");
        new SwingWorker<SelectionYFit.Result,Void>() {
            @Override protected SelectionYFit.Result doInBackground() {return canvas.fitSelectionY(b,opaque);}
            @Override protected void done() {
                if(generation!=viewGeneration||canvas!=activeCanvas()||!b.equals(canvas.getSelectionBounds())) return;
                try {
                    SelectionYFit.Result r=get();
                    if(r.count==0) {progressLabel.setText("No cached heights in selection; Import Y unchanged");return;}
                    // Apply both fitted ends atomically; do not truncate extended-world heights.
                    heightRangeSlider.setSelectedRange(r.min,r.max);
                    progressLabel.setText("Import Y fit: "+heightRangeSlider.getLowerValue()+"…"+heightRangeSlider.getUpperValue()+" ("+r.count+" cached samples; "+r.empty+" empty, "+r.missing+" missing samples; unloaded columns ignored)");
                } catch(Exception e) {progressLabel.setText("Could not fit Import Y: "+e.getMessage());}
            }
        }.execute();
    }
    private void editSelectionBounds() {
        final SelectionCanvas canvas=activeCanvas();final long generation=viewGeneration;
        JDialog dialog=new JDialog(this,"Selection bounds (inclusive)",false);
        dialog.setContentPane(new SelectionBoundsPanel(canvas.getSelectionBounds(),b->{
            if(canvas!=activeCanvas()||generation!=viewGeneration) {dialog.dispose();return;}
            canvas.setSelectionBounds(b);progressLabel.setText("Selection updated: "+canvas.getSelectionBounds());
        }));
        dialog.pack();dialog.setLocationRelativeTo(this);dialog.setVisible(true);
    }
    private void copyCoordinates() {
        SelectionMath.Bounds b=activeCanvas().getSelectionBounds();String text;
        if(b!=null) text=ClipboardCoords.selection(b,heightRangeSlider.getLowerValue(),heightRangeSlider.getUpperValue());
        else {int[] xyz=activeCanvas().getHoverCoordinates();if(xyz==null||xyz[1]==RenderedMap.NO_HEIGHT) {progressLabel.setText("No selection or known hover coordinates to copy");return;}text=ClipboardCoords.hover(xyz[0],xyz[1],xyz[2]);}
        try {Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text),null);progressLabel.setText("Copied: "+text);}
        catch(IllegalStateException|HeadlessException e) {progressLabel.setText("Clipboard unavailable: "+text);}
    }
    private void cycleGrid() {
        gridMode=(gridMode+1)%3;mapCanvas.setGridMode(gridMode);if(dynamicMapCanvas!=null) dynamicMapCanvas.setGridMode(gridMode);
        String name=new String[]{"Off","Chunk","Chunk+Region"}[gridMode];gridMenuItem.setText("Map grid: "+name);progressLabel.setText("Map grid: "+name);
    }
    private void jumpTo(Point point) {
        boolean data=activeCanvas().jumpToWorldCoordinate(point);
        progressLabel.setText("View centered on ("+point.x+", "+point.y+")"+(data?"":"; no cached map data nearby"));
    }
    private void jumpAnchor(String key) {
        if("F4".equals(key)) {SelectionMath.Bounds b=activeCanvas().getSelectionBounds();if(b!=null) jumpTo(b.center());else progressLabel.setText("No selection to center on");return;}
        WorldAnchors.Anchor anchor="F3".equals(key)&&worldAnchors.player!=null?worldAnchors.player:worldAnchors.spawn;
        if(anchor==null) {JOptionPane.showMessageDialog(this,"Spawn/player coordinates are unavailable in level.dat","World anchor unavailable",JOptionPane.INFORMATION_MESSAGE);return;}
        if(currentDimension!=null&&!anchor.dimension.equals(currentDimension.getId())) {
            WorldPathResolver.DimensionInfo target=null;
            for(WorldPathResolver.DimensionInfo dim:availableDimensions) if(anchor.dimension.equals(dim.getId())) {target=dim;break;}
            if(target==null) {JOptionPane.showMessageDialog(this,"Anchor dimension is unavailable: "+anchor.dimension);return;}
            dimensionDropdown.setSelectedItem(target);
            // Dimension switching queues its initial view; jump after that EDT task.
            SwingUtilities.invokeLater(()->jumpTo(anchor.xz));
        } else jumpTo(anchor.xz);
    }
    private void handleSelectionConfirmation(SelectionMath.Bounds b) {
        if(confirmationOpen) return;
        confirmationOpen=true;
        try {
            int minY=heightRangeSlider.getLowerValue(),maxY=heightRangeSlider.getUpperValue();
            long generation=viewGeneration;SelectionCanvas canvas=activeCanvas();Map<String,Object> approvedShape=canvas.getSelectionShape().json();
            SelectionStats stats=new SelectionStats(b,minY,maxY);
            String message="Import bounds: "+ClipboardCoords.selection(b,minY,maxY)+"\n"+stats+"\n\n"+
                (stats.isLarge()?"Large selection: Blender may require substantial memory and time.\n":"")+"Selection shape exports as inclusive union AABB"+(multiBoxExport?" + boxes extras":"")+".\nContinue with this selection?";
            int result=JOptionPane.showConfirmDialog(this,message,"Confirm import selection",JOptionPane.OK_CANCEL_OPTION,stats.isLarge()?JOptionPane.WARNING_MESSAGE:JOptionPane.QUESTION_MESSAGE);
            if(result!=JOptionPane.OK_OPTION) {progressLabel.setText("Confirmation cancelled; selection kept");return;}
            if(generation!=viewGeneration||canvas!=activeCanvas()||!approvedShape.equals(canvas.getSelectionShape().json())||!b.equals(canvas.getSelectionBounds())||minY!=heightRangeSlider.getLowerValue()||maxY!=heightRangeSlider.getUpperValue()) {
                progressLabel.setText("Map or import bounds changed during confirmation; please confirm again");return;
            }
            progressLabel.setText("Confirmed: "+b);
            if(isBlenderMode&&outputFilePath!=null) outputCoordinatesToFile(b.minX,b.minZ,b.maxX,b.maxZ,minY,maxY);
        } finally {confirmationOpen=false;updateSelectionStats();}
    }

    private void confirmSelection() {activeCanvas().confirmCurrentSelection();}

    /**
     * 切换自动加载模式
     */
    private void toggleAutoLoad() {
        boolean enabled = autoLoadCheckbox.isSelected();

        if (mapCanvas != null) {
            mapCanvas.enableAutoViewportManagement(enabled && !isDynamicMode);
        }

        if (enabled) {
            progressLabel.setText("Auto-load enabled - map loads by viewport");
        } else {
            progressLabel.setText("Auto-load disabled");
        }
    }

    /**
     * 在后台加载区域 - 优化版本 (Java 8兼容)
     */
    private void loadRegionInBackground(int regionX, int regionZ) {
        final long requestGeneration = viewGeneration;
        final File requestedRegionDir = getCurrentRegionDir();
        final MapRenderer.SurfaceMode requestedMode = surfaceMode;
        final PreviewOptions requestedPreview=preview;
        final Integer requestedTop=renderHeightPanel.getClipY();
        if (!autoLoadCheckbox.isSelected() || isDynamicMode || loadingPanel.getPhase()==LoadingPhasePanel.Phase.PREPARING) return;

        // 使用后台线程池异步加载
        Future<BufferedImage> future = backgroundExecutor.submit(() -> {
            String regionFile = String.format("r.%d.%d.mca", regionX, regionZ);
            File regionPath = new File(requestedRegionDir, regionFile);

            if (!regionPath.exists()) {
                System.out.printf("Region file not found: %s\n", regionFile);
                return null;
            }

            System.out.printf("Rendering region: %s (world: %d, %d)\n",
                regionFile, regionX * 512, regionZ * 512);

            // 创建简单的进度回调
            MapRenderer.ProgressCallback progressCallback = new MapRenderer.ProgressCallback() {
                @Override
                public void onProgress(int processed, int total, double speed, Set<String> foundBlocks) {
                    // 静默加载，不显示进度
                }
                
                @Override
                public void onProgressiveRender(BufferedImage partialImage, int completedChunks, int totalChunks) {
                    // 静默加载，不需要渐进式渲染回调
                }
            };

            // 创建地图渲染器，使用单线程避免影响主渲染
            MapRenderer renderer = new MapRenderer(1, progressCallback, requestedMode,requestedTop,BlockColors.snapshot(),requestedPreview);

            try {
                // 渲染整个区域 (32x32区块 = 512x512方块)
                MapRenderer.BlockInfo[][] topBlocks = renderer.getTopBlocks(regionPath.getAbsolutePath(), 32, 1);
                if (topBlocks != null) {
                    BufferedImage regionImage = renderer.renderToPng(topBlocks, 1);
                    if (regionImage != null) {
                        System.out.printf("Rendered region: %s, image size: %dx%d\n",
                            regionFile, regionImage.getWidth(), regionImage.getHeight());
                    }
                    return regionImage;
                }
                return null;
            } catch (Exception e) {
                System.err.printf("Error rendering region %s: %s\n", regionFile, e.getMessage());
                return null;
            } finally {
                renderer.shutdown();
            }
        });

        // 处理结果的后续任务
        backgroundExecutor.submit(() -> {
            try {
                BufferedImage regionImage = future.get();
                if (regionImage != null) {
                    SwingUtilities.invokeLater(() -> {
                        if (requestGeneration != viewGeneration) return;
                        dynamicMapManager.addRegion(regionX, regionZ, regionImage);
                        BufferedImage combinedImage = dynamicMapManager.getCombinedImage();
                        if (combinedImage != null) {
                            // 使用新方法保持世界坐标中心点不变，避免视角跳动
                            Point worldOrigin = dynamicMapManager.getWorldOrigin();
                            mapCanvas.setImageKeepWorldCenter(combinedImage, worldOrigin.x, worldOrigin.y);

                            // 确保布局稳定
                            revalidate();
                            repaint();

                            progressLabel.setText(String.format("Dynamic map updated - %d regions loaded",
                                dynamicMapManager.getRegionCount()));
                        }
                    });

                    System.out.printf("Loaded region r.%d.%d.mca\n", regionX, regionZ);
                }
            } catch (Exception throwable) {
                System.err.printf("Failed to load region r.%d.%d.mca: %s\n", regionX, regionZ, throwable.getMessage());
            }
        });
    }

    /**
     * 卸载区域
     */
    private void unloadRegionInBackground(int regionX, int regionZ) {
        // 从动态地图管理器中移除区域
        dynamicMapManager.removeRegion(regionX, regionZ);

        // 更新地图显示，保持用户当前视图
        SwingUtilities.invokeLater(() -> {
            BufferedImage combinedImage = dynamicMapManager.getCombinedImage();
            if (combinedImage != null) {
                // 使用新方法保持世界坐标中心点不变，避免视角跳动
                Point worldOrigin = dynamicMapManager.getWorldOrigin();
                mapCanvas.setImageKeepWorldCenter(combinedImage, worldOrigin.x, worldOrigin.y);

                // 确保布局稳定
                revalidate();
                repaint();

                progressLabel.setText(String.format("Dynamic map updated - %d regions loaded",
                    dynamicMapManager.getRegionCount()));
            } else {
                // 如果没有区域了，清空地图
                mapCanvas.setImage(null);
                progressLabel.setText("All regions unloaded");
            }
        });

        System.out.printf("Unloaded region r.%d.%d.mca\n", regionX, regionZ);
    }

    /**
     * 重新渲染当前视图
     */
    private void reRenderCurrentView() {
        if(isDynamicMode && dynamicRegionManager!=null) {
            resetSurfaceView();return;
        }
        if (dynamicMapManager == null) return;

        // 清除当前所有区域
        dynamicMapManager.clear();
        mapCanvas.setImage(null);

        // 重新启动自动加载
        if (autoLoadCheckbox.isSelected()) {
            progressLabel.setText("Re-rendering current view...");

            // 触发视野更新，重新加载区域
            SwingUtilities.invokeLater(() -> {
                if (mapCanvas != null) {
                    // 强制更新视野
                    mapCanvas.forceViewportUpdate();
                }
                progressLabel.setText("Reloading regions...");
            });
        } else {
            progressLabel.setText("Please enable auto-load first");
        }
    }

    /**
     * 从指定路径加载世界（用于Blender集成）
     */
    private void loadWorldFromPath(String worldPath) {
        File worldDir = new File(worldPath);
        if (!worldDir.exists() || !worldDir.isDirectory()) {
            showErrorState("Invalid world path: " + worldPath);
            return;
        }

        cancelP2Scan();++viewGeneration;
        dimensionDropdown.setEnabled(false);
        showLoadingState("Parsing world: " + worldPath);

        // 设置存档路径
        ++colorGeneration;
        lastMcaPath=null;singleResourcePath=null;
        this.savePath = worldPath;

        // 更新GUI界面显示存档路径
        SwingUtilities.invokeLater(() -> {
            if (saveInfoLabel != null) {
                saveInfoLabel.setText("Current save: " + worldDir.getName());
            }
            progressLabel.setText("Reading save info...");
        });

        // 在后台线程中加载存档数据，模拟用户选择存档的完整流程
        final long request=viewGeneration;
        final ResourceOptions requestedResources=colorResources.copy();
        SwingWorker<PreparedWorld, Void> worker = new SwingWorker<PreparedWorld, Void>() {
            protected PreparedWorld doInBackground() {return loadSaveData(request,worldPath,requestedResources);}
            protected void done() {
                if(request!=viewGeneration) return;
                try {applyPreparedWorld(get());}
                catch(Exception error) {showErrorState("World loading failed: "+error.getMessage());}
            }
        };
        worker.execute();
    }

    /**
     * 输出坐标到文件（用于Blender集成）
     */
    private void outputCoordinatesToFile(int minX,int minZ,int maxX,int maxZ,int actualMinY,int actualMaxY) {
        try {
            // Use exactly the Y bounds shown in the approved confirmation dialog.
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                new File(outputFilePath),CoordinatesPayload.create(new SelectionMath.Bounds(minX,minZ,maxX,maxZ),actualMinY,actualMaxY,System.currentTimeMillis(),
                    currentDimension==null?null:currentDimension.getId(),worldAnchors.worldName,worldAnchors.dataVersion,activeCanvas().getSelectionShape(),multiBoxExport,overlays.waypoints,includeWaypointsExport));

            System.out.printf("Blender mode: coordinates written to %s\n", outputFilePath);
            System.out.printf("XYZ range: (%d,%d,%d) to (%d,%d,%d)\n",
                minX, actualMinY, minZ, maxX, actualMaxY, maxZ);

            // 延迟关闭程序，让用户看到确认信息
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                    String.format("Coordinates selected and sent to Blender:\n" +
                                "Start: (%d, %d, %d)\n" +
                                "End: (%d, %d, %d)",
                        minX, actualMinY, minZ, maxX, actualMaxY, maxZ),
                    "Coordinates confirmed", JOptionPane.INFORMATION_MESSAGE);

                // 关闭程序
                System.exit(0);
            });

        } catch (Exception e) {
            System.err.println("Blender mode: failed to write coordinates " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 清除PNG缓存文件
     */
    private void clearPngCache() {
        // 显示确认对话框
        int result = JOptionPane.showConfirmDialog(
            this,
            "<html>Clear PNG cache?<br><br>" +
            "This will delete all map PNG files and thumbnails in the program directory,<br>" +
            "Including:<br>" +
            "• *_map_*.png (map files)<br>" +
            "• *_thumbnail.png (thumbnail files)<br>" +
            "• region_*.png (region files)<br>" +
            "• r.*.png (MCA files)<br><br>" +
            "This action cannot be undone!</html>",
            "Confirm cache clear",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );

        if (result != JOptionPane.YES_OPTION) {
            return;
        }

        // 在后台线程中执行清理
        SwingWorker<FileUtils.CacheCleanupResult, Void> worker = new SwingWorker<FileUtils.CacheCleanupResult, Void>() {
            @Override
            protected FileUtils.CacheCleanupResult doInBackground() throws Exception {
                SwingUtilities.invokeLater(() -> {
                    progressLabel.setText("Clearing PNG cache files...");
                });

                return FileUtils.cleanupPngCache(programDir);
            }

            @Override
            protected void done() {
                try {
                    FileUtils.CacheCleanupResult cleanupResult = get();

                    // 显示结果
                    String message = cleanupResult.getSummary();
                    progressLabel.setText(message);

                    // 显示详细结果对话框
                    int messageType = cleanupResult.isSuccess() ?
                        JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE;

                    JOptionPane.showMessageDialog(
                        MinecraftMapGUI.this,
                        message,
                        "Cache clear result",
                        messageType
                    );

                } catch (Exception e) {
                    String errorMsg = "Error clearing cache: " + e.getMessage();
                    progressLabel.setText(errorMsg);
                    JOptionPane.showMessageDialog(
                        MinecraftMapGUI.this,
                        errorMsg,
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        };

        worker.execute();
    }
    
    /**
     * 打开日志窗口
     */
    private void openLogWindow() {
        if (logWindow == null) {
            logWindow = new LogWindow();
            logManager.info("Creating new log window", "MinecraftMapGUI");
        }
        
        if (!logWindow.isVisible()) {
            logWindow.setVisible(true);
            logManager.info("Showing log window", "MinecraftMapGUI");
        } else {
            // 如果窗口已经可见，将其置于前台
            logWindow.toFront();
            logWindow.requestFocus();
            logManager.debug("Log window brought to front", "MinecraftMapGUI");
        }
    }
    
    /**
     * 渲染所有可用的区域
     */
    private void renderAllAvailableRegions() {
        final long requestGeneration = viewGeneration;
        final MapRenderer.SurfaceMode requestedMode = surfaceMode;
        final PreviewOptions requestedPreview=preview;
        final Integer requestedTop=renderHeightPanel.getClipY();
        if (savePath == null || savePath.isEmpty()) {
            JOptionPane.showMessageDialog(this, 
                "Please select a Minecraft save first", 
                "Info", 
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        logManager.info("Start scanning all available regions", "MinecraftMapGUI");
        
        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                // 扫描所有可用的区域文件
                File regionDir = WorldPathResolver.getOverworldRegionDir(savePath);
                if (!regionDir.exists()) {
                    logManager.error("Region dir does not exist: " + regionDir.getAbsolutePath(), "MinecraftMapGUI");
                    return null;
                }
                
                // 获取所有.mca文件
                File[] mcaFiles = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
                if (mcaFiles == null || mcaFiles.length == 0) {
                    logManager.warn("No .mca region files found", "MinecraftMapGUI");
                    return null;
                }
                
                logManager.info("Found " + mcaFiles.length + " region files", "MinecraftMapGUI");
                
                // 解析区域坐标并找到边界
                final int[] regionBounds = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
                // regionBounds[0] = minRegionX, [1] = maxRegionX, [2] = minRegionZ, [3] = maxRegionZ
                
                java.util.List<String> validMcaFiles = new java.util.ArrayList<>();
                java.util.Map<String, int[]> regionCoords = new java.util.HashMap<>();
                
                for (File mcaFile : mcaFiles) {
                    String fileName = mcaFile.getName();
                    // 解析文件名格式: r.x.z.mca
                    if (fileName.matches("r\\.-?\\d+\\.-?\\d+\\.mca")) {
                        String[] parts = fileName.replace(".mca", "").split("\\.");
                        try {
                            int regionX = Integer.parseInt(parts[1]);
                            int regionZ = Integer.parseInt(parts[2]);
                            
                            regionBounds[0] = Math.min(regionBounds[0], regionX); // minRegionX
                            regionBounds[1] = Math.max(regionBounds[1], regionX); // maxRegionX
                            regionBounds[2] = Math.min(regionBounds[2], regionZ); // minRegionZ
                            regionBounds[3] = Math.max(regionBounds[3], regionZ); // maxRegionZ
                            
                            validMcaFiles.add(mcaFile.getAbsolutePath());
                            regionCoords.put(mcaFile.getAbsolutePath(), new int[]{regionX, regionZ});
                            
                            logManager.debug("Found region: r." + regionX + "." + regionZ + ".mca", "MinecraftMapGUI");
                        } catch (NumberFormatException e) {
                            logManager.warn("Cannot parse region file name: " + fileName, "MinecraftMapGUI");
                        }
                    }
                }
                
                if (validMcaFiles.isEmpty()) {
                    logManager.error("No valid region files found", "MinecraftMapGUI");
                    return null;
                }
                
                logManager.info(String.format("Region range: X[%d, %d], Z[%d, %d]", 
                    regionBounds[0], regionBounds[1], regionBounds[2], regionBounds[3]), "MinecraftMapGUI");
                
                // 计算总图像大小
                int totalWidth = (regionBounds[1] - regionBounds[0] + 1) * 512;
                int totalHeight = (regionBounds[3] - regionBounds[2] + 1) * 512;
                
                logManager.info(String.format("Creating large image: %dx%d px", totalWidth, totalHeight), "MinecraftMapGUI");
                
                SwingUtilities.invokeLater(() -> {
                    progressLabel.setText(String.format("Rendering all regions (%d files), image size: %dx%d...",
                        validMcaFiles.size(), totalWidth, totalHeight));
                });
                
                // 创建大图像
                RenderedMap combinedImage = new RenderedMap(totalWidth, totalHeight);
                Graphics2D g2d = combinedImage.createGraphics();
                
                // 设置高质量渲染
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                
                // 填充背景色（深灰色表示未探索区域）
                g2d.setColor(new Color(32, 32, 32));
                g2d.fillRect(0, 0, totalWidth, totalHeight);
                
                showMapLoading(requestGeneration,"Loading map regions...");
                // 创建进度回调
                final java.util.concurrent.atomic.AtomicInteger completedRegions=new java.util.concurrent.atomic.AtomicInteger();
                MapRenderer.ProgressCallback progressCallback = new MapRenderer.ProgressCallback() {
                    @Override
                    public void onProgress(int processed, int total, double speed, java.util.Set<String> foundBlocks) {
                        final int completed=completedRegions.get();
                        final int finished=completed*1000+(total<=0?0:(int)(1000L*processed/total));
                        SwingUtilities.invokeLater(() -> {
                            if(requestGeneration!=viewGeneration) return;
                            loadingPanel.mapProgress(requestGeneration,finished,validMcaFiles.size()*1000,
                                String.format("Map regions: %d/%d; chunks: %d/%d (%.1f/s)",completed,validMcaFiles.size(),processed,total,speed));
                        });
                    }
                    
                    @Override
                    public void onProgressiveRender(BufferedImage partialImage, int completedChunks, int totalChunks) {
                        // 全区域渲染中不需要渐进式回调
                    }
                };
                
                // 创建地图渲染器
                int maxWorkers = Math.min(Runtime.getRuntime().availableProcessors(), 4);
                MapRenderer renderer = new MapRenderer(maxWorkers, progressCallback, requestedMode,requestedTop,BlockColors.snapshot(),requestedPreview);
                
                try {
                    // 渲染每个区域
                    int processedCount = 0;
                    for (String mcaFile : validMcaFiles) {
                        int[] coords = regionCoords.get(mcaFile);
                        if (coords != null) {
                            int regionX = coords[0];
                            int regionZ = coords[1];
                            
                            final int currentCount = ++processedCount;
                            logManager.info(String.format("Rendering region %d/%d: r.%d.%d.mca",
                                currentCount, validMcaFiles.size(), regionX, regionZ), "MinecraftMapGUI");
                            
                            SwingUtilities.invokeLater(() -> {
                                progressLabel.setText(String.format("Rendering region %d/%d: r.%d.%d.mca",
                                    currentCount, validMcaFiles.size(), regionX, regionZ));
                            });
                            
                            // 渲染单个区域
                            MapRenderer.BlockInfo[][] topBlocks = renderer.getTopBlocks(mcaFile, 32, 1);
                            if (topBlocks != null) {
                                BufferedImage regionImage = renderer.renderToPng(topBlocks, 1);
                                if (regionImage != null) {
                                    // 计算在大图像中的正确位置
                                    int offsetX = (regionX - regionBounds[0]) * 512;
                                    int offsetY = (regionZ - regionBounds[2]) * 512;
                                    
                                    logManager.debug(String.format("Placing region image at: (%d, %d)", offsetX, offsetY), "MinecraftMapGUI");
                                    g2d.drawImage(regionImage, offsetX, offsetY, null);
                                    combinedImage.copyHeightsFrom(regionImage, offsetX, offsetY);
                                } else {
                                    logManager.warn("Region image render failed: r." + regionX + "." + regionZ + ".mca", "MinecraftMapGUI");
                                }
                            } else {
                                logManager.warn("Region data fetch failed: r." + regionX + "." + regionZ + ".mca", "MinecraftMapGUI");
                            }
                        }
                        completedRegions.incrementAndGet();
                    }
                    
                    g2d.dispose();
                    
                    // 保存区域信息
                    currentRegion = new Point((regionBounds[0] + regionBounds[1]) / 2, (regionBounds[2] + regionBounds[3]) / 2);
                    
                    // 保存边界信息到成员变量
                    lastFullRenderMinWorldX = regionBounds[0] * 512;
                    lastFullRenderMinWorldZ = regionBounds[2] * 512;
                    
                    logManager.info("Full-region render complete", "MinecraftMapGUI");
                    
                    return combinedImage;
                    
                } finally {
                    renderer.shutdown();
                }
            }
            
            @Override
            protected void done() {
                if (requestGeneration != viewGeneration) return;
                try {
                    BufferedImage result = get();
                    if (result != null) {
                        mapImage = result;
                        
                        SwingUtilities.invokeLater(() -> {
                            // 更新显示
                            mapCanvas.setImage(result);
                            progressLabel.setText("Full-region render complete! All available regions rendered.");hideLoadingState();
                            
                            // 设置世界坐标映射
                            mapCanvas.setWorldCoordinateMapping(lastFullRenderMinWorldX, lastFullRenderMinWorldZ, 1.0);
                            
                            // 刷新显示
                            mapCanvas.repaint();
                        });
                        
                        logManager.info("Map display updated", "MinecraftMapGUI");
                    } else {
                        SwingUtilities.invokeLater(() -> {
                            progressLabel.setText("Full-region rendering failed");hideLoadingState();
                        });
                        logManager.error("Full-region rendering failed", "MinecraftMapGUI");
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("Render error: " + e.getMessage());hideLoadingState();
                        JOptionPane.showMessageDialog(MinecraftMapGUI.this,
                            "Error rendering all regions: " + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    });
                    logManager.error("Full-region render error", "MinecraftMapGUI", e);
                }
            }
        };
        
        worker.execute();
    }
    
    /**
     * 切换动态模式
     */
    private void toggleDynamicMode() {
        if(loadingPanel.getPhase()==LoadingPhasePanel.Phase.PREPARING) return;
        if (savePath == null || savePath.isEmpty()) {
            JOptionPane.showMessageDialog(this, 
                "Please select a Minecraft save first", 
                "Info", 
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        if (!isDynamicMode) {
            // 启用动态模式
            enableDynamicMode();
        } else {
            // 禁用动态模式
            disableDynamicMode();
        }
    }
    
    /**
     * 启用动态模式
     */
    private void enableDynamicMode() {
        try {
            logManager.info("Enable dynamic mode", "MinecraftMapGUI");
            
            mapCanvas.enableAutoViewportManagement(false);
            // Create the manager for the currently selected dimension.
            dynamicRegionManager = createDynamicRegionManager();
            
            // 创建动态地图画布
            dynamicMapCanvas = new DynamicMapCanvas();
            dynamicMapCanvas.setHoverListener(coordDisplayLabel::setText);
            dynamicMapCanvas.setRegionManager(dynamicRegionManager,false);
            
            // 设置选区回调
            dynamicMapCanvas.setSelectionCallback(new DynamicMapCanvas.SelectionCallback() {
                @Override public void onSelectionComplete(int minX,int minZ,int maxX,int maxZ) {updateSelectionStats();}
                @Override public void onSelectionConfirmed(int minX,int minZ,int maxX,int maxZ) {handleSelectionConfirmation(new SelectionMath.Bounds(minX,minZ,maxX,maxZ));}
            });
            configureCanvas(dynamicMapCanvas);
            dynamicMapCanvas.restoreSelectionBounds(mapCanvas.getSelectionBounds());if(!mapCanvas.getSelectionShape().isEmpty())dynamicMapCanvas.setSelectionShape(mapCanvas.getSelectionShape(),false);

            // 替换地图显示组件
            Container parent = mapCanvas.getParent();
            if (parent != null) {
                parent.remove(mapCanvas);
                parent.add(dynamicMapCanvas, BorderLayout.CENTER);
                parent.revalidate();
                parent.repaint();
            }
            
            // 适应窗口
            SwingUtilities.invokeLater(() -> {
                dynamicMapCanvas.showInitialViewport(playerPos);
                dynamicMapCanvas.requestFocus(); // 确保可以接收键盘事件
            });
            
            isDynamicMode = true;
            updateSelectionStats();
            
            // 更新按钮文本和颜色（按钮已隐藏，但保留逻辑）
            // dynamicModeButton.setText("Exit dynamic mode");
            // dynamicModeButton.setBackground(UIThemeManager.ThemeColors.WARNING_ORANGE);
            // dynamicModeButton.setToolTipText("Exit dynamic mode, return to static map mode");
            
            // 更新进度标签
            progressLabel.setText("Dynamic mode enabled - drag and scroll to browse");
            
            logManager.info("Dynamic mode enabled", "MinecraftMapGUI");
            
        } catch (Exception e) {
            logManager.error("Failed to enable dynamic mode", "MinecraftMapGUI", e);
            JOptionPane.showMessageDialog(this,
                "Failed to enable dynamic mode: " + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * 禁用动态模式
     */
    private void disableDynamicMode() {
        try {
            logManager.info("Disable dynamic mode", "MinecraftMapGUI");
            
            // 清理动态组件
            if (dynamicMapCanvas != null) {
                mapCanvas.restoreSelectionBounds(dynamicMapCanvas.getSelectionBounds());if(!dynamicMapCanvas.getSelectionShape().isEmpty())mapCanvas.setSelectionShape(dynamicMapCanvas.getSelectionShape(),false);
                dynamicMapCanvas.cleanup();
                
                // 替换回原来的地图画布
                Container parent = dynamicMapCanvas.getParent();
                if (parent != null) {
                    parent.remove(dynamicMapCanvas);
                    parent.add(mapCanvas, BorderLayout.CENTER);
                    parent.revalidate();
                    parent.repaint();
                }
                
                dynamicMapCanvas = null;
            }
            
            // 清理动态区域管理器
            if (dynamicRegionManager != null) {
                dynamicRegionManager.shutdown();
                dynamicRegionManager = null;
            }
            
            isDynamicMode = false;
            updateSelectionStats();
            
            // 更新按钮文本和颜色（按钮已隐藏，但保留逻辑）
            // dynamicModeButton.setText("Dynamic Mode");
            // dynamicModeButton.setBackground(UIThemeManager.ThemeColors.PRIMARY_BLUE);
            // dynamicModeButton.setToolTipText("Enable dynamic loading, only render viewport regions");
            
            // 更新进度标签
            progressLabel.setText("Dynamic mode disabled - back to static map");
            
            logManager.info("Dynamic mode disabled", "MinecraftMapGUI");
            
        } catch (Exception e) {
            logManager.error("Failed to disable dynamic mode", "MinecraftMapGUI", e);
        }
    }
    
    /**
     * 清理动态模式内存
     */
    private void clearDynamicMemory() {
        if (!isDynamicMode || dynamicRegionManager == null) {
            JOptionPane.showMessageDialog(this,
                "Not in dynamic mode, no memory to clear",
                "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        int result = JOptionPane.showConfirmDialog(this,
            "Clear all loaded regions?\nThis frees memory but regions must reload.",
            "Confirm memory clear",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE);
            
        if (result == JOptionPane.YES_OPTION) {
            try {
                logManager.info("Start clearing dynamic mode memory", "MinecraftMapGUI");
                
                // 清理所有已加载的区域
                dynamicRegionManager.clearAllLoadedRegions();
                
                // 更新进度标签
                progressLabel.setText("Memory cleared - regions will reload as needed");
                
                logManager.info("Dynamic mode memory cleared", "MinecraftMapGUI");
                
                JOptionPane.showMessageDialog(this,
                    "Memory cleared! Regions reload by viewport.",
                    "Clear complete", JOptionPane.INFORMATION_MESSAGE);
                    
            } catch (Exception e) {
                logManager.error("Failed to clear dynamic mode memory", "MinecraftMapGUI", e);
                JOptionPane.showMessageDialog(this,
                    "Failed to clear memory: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void repaintCanvases() {if(mapCanvas!=null) mapCanvas.repaint();if(dynamicMapCanvas!=null) dynamicMapCanvas.repaint();}
    private String selectionToolHint(String tool){
        if("Box".equals(tool))return overlays.addBoxMode
            ?"Box + Add box ON: right-drag appends AABB (Alt/Ctrl+RMB also). Export uses union AABB."
            :"Box: right drag replaces; Alt/Ctrl+right drag or Selection→Add box appends. Export uses union AABB.";
        if("Chunk paint".equals(tool))return "Chunk paint: right drag paints 16×16; Shift+right drag erases. Export uses union AABB.";
        return tool+": right drag; Shift+paint erases. Export uses union AABB.";
    }
    private void addPreviewItem(JMenu menu,String title,String command,boolean selected) {
        JCheckBoxMenuItem item=new JCheckBoxMenuItem(title,selected);previewItems.put(command,item);menu.add(item);item.addActionListener(e->togglePreview(command));
    }
    private void togglePreview(String command) {
        boolean render=false;
        switch(command) {
            case "water":preview=new PreviewOptions(!preview.transparentWater,preview.cave,preview.biomeMode);render=true;break;
            case "cave":preview=new PreviewOptions(preview.transparentWater,!preview.cave,preview.biomeMode);render=true;
                if(preview.cave&&renderHeightPanel.getClipY()==null) renderHeightPanel.setClipY(((Number)renderHeightPanel.getInput().getValue()).intValue());break;
            case "biome":preview=new PreviewOptions(preview.transparentWater,preview.cave,preview.biomeMode+1);render=true;break;
            case "night":overlays.night=!overlays.night;break;
            case "contours":overlays.contours=!overlays.contours;break;
            case "markers":overlays.markers=!overlays.markers;break;
            case "gaps":overlays.gaps=!overlays.gaps;break;
            case "scale":overlays.scaleBar=!overlays.scaleBar;break;
            case "minimap":overlays.minimap=!overlays.minimap;break;
            case "hud":overlays.hud=!overlays.hud;break;
            case "ore":overlays.ore=!overlays.ore;break;
            case "pos":overlays.selectionMode="Box";overlays.posMode=!overlays.posMode;overlays.addBoxMode=false;mapCanvas.resetPosCorner();if(dynamicMapCanvas!=null) dynamicMapCanvas.resetPosCorner();break;
            case "addbox":overlays.addBoxMode=!overlays.addBoxMode;overlays.selectionMode="Box";overlays.posMode=false;JCheckBoxMenuItem posItem=previewItems.get("pos");if(posItem!=null)posItem.setSelected(false);progressLabel.setText(selectionToolHint("Box"));break;
        }
        String[] commands={"water","cave","night","contours","markers","gaps","scale","pos","addbox"};
        boolean[] states={preview.transparentWater,preview.cave,overlays.night,overlays.contours,overlays.markers,overlays.gaps,overlays.scaleBar,overlays.posMode,overlays.addBoxMode};
        for(int i=0;i<commands.length;i++) if(previewItems.containsKey(commands[i])) previewItems.get(commands[i]).setSelected(states[i]);
        if(biomeMenu!=null) biomeMenu.setText("Biomes: "+new String[]{"Surface","Tint composite","Biome overlay"}[preview.biomeMode]);
        if(render) {if(heightDebounce!=null) heightDebounce.stop();resetSurfaceView();}else repaintCanvases();
    }
    private void refreshRecentWorlds() {
        if(recentMenu==null) return;recentMenu.removeAll();
        try {for(String path:recentWorlds.list()) addMenuItem(recentMenu,path,e->loadWorldFromPath(path),null,path);}
        catch(IOException e) {JMenuItem error=new JMenuItem("Recent worlds unavailable: "+e.getMessage());error.setEnabled(false);recentMenu.add(error);}
        if(recentMenu.getItemCount()==0) {JMenuItem empty=new JMenuItem("No recent worlds");empty.setEnabled(false);recentMenu.add(empty);}
    }
    private void showWorldInfo() {
        StringBuilder info=new StringBuilder("Name: ").append(worldAnchors.worldName).append("\nPath: ").append(savePath==null?lastMcaPath:savePath)
            .append("\nVersion: ").append(worldAnchors.versionName).append("\nDataVersion: ").append(worldAnchors.dataVersion).append("\nDimensions:\n");
        for(WorldPathResolver.DimensionInfo d:availableDimensions) info.append(d.getId()).append("  ").append(d.getRegionDir()).append('\n');
        info.append("\nResource fingerprint (SHA-256): ").append(BlockColors.snapshot().fingerprint()).append("\nPreview: ").append(preview.cacheKey()).append("; night=").append(overlays.night);
        WorldInfoDialog.show(this,info.toString());
    }
    private void showCoverage() {JOptionPane.showMessageDialog(this,new ResourceCoveragePanel(ResourceCoveragePanel.report(activeCanvas().visibleBlockCounts(),BlockColors.snapshot())),"Resource coverage",JOptionPane.PLAIN_MESSAGE);}
    private File chooseOutput(String title,String extension) {
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle(title);chooser.setFileFilter(new FileNameExtensionFilter(extension.toUpperCase()+" files",extension));
        if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION) return null;
        File f=chooser.getSelectedFile();if(!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith("."+extension)) f=new File(f.getPath()+"."+extension);
        if(f.exists()&&JOptionPane.showConfirmDialog(this,"Replace "+f.getName()+"?",title,JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return null;return f;
    }
    private void savePreset() {
        SelectionMath.Bounds b=activeCanvas().getSelectionBounds();if(b==null) {progressLabel.setText("Select an area before saving a preset");return;}
        File f=chooseOutput("Save selection preset","json");if(f==null) return;
        try {new SelectionPreset(b,heightRangeSlider.getLowerValue(),heightRangeSlider.getUpperValue(),currentDimension==null?null:currentDimension.getId(),activeCanvas().getSelectionShape(),overlays.waypoints).save(f);progressLabel.setText("Preset saved: "+f);}
        catch(Exception e) {showFileError(e);}
    }
    private void loadPreset() {
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Load selection preset");if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION) return;
        loadPresetFile(chooser.getSelectedFile());
    }
    private void loadPresetFile(File file) {
        try {
            SelectionPreset preset=SelectionPreset.load(file);
            WorldPathResolver.DimensionInfo target=null;
            if(preset.dimension!=null) {for(WorldPathResolver.DimensionInfo d:availableDimensions) if(preset.dimension.equals(d.getId())) target=d;
                if(target==null) throw new IOException("Preset dimension is not available in this world: "+preset.dimension);}
            boolean dimensionChanged=target!=null&&(currentDimension==null||!target.getId().equals(currentDimension.getId()));
            if(target!=null) switchDimension(target);
            // Restore the values immediately, including dimensions with no nearby cached tile.
            activeCanvas().restoreSelectionBounds(preset.bounds);
            if(preset.minY!=null) heightRangeSlider.setSelectedRange(preset.minY,preset.maxY);
            pendingPreset=preset;
            if(!isDynamicMode&&mapCanvas.getImage()==null&&target!=null) {
                Point center=preset.bounds.center();currentRegion=new Point(Math.floorDiv(center.x,512),Math.floorDiv(center.y,512));
                File region=new File(target.getRegionDir(),String.format("r.%d.%d.mca",currentRegion.x,currentRegion.y));
                if(region.isFile()) renderMap(region.getAbsolutePath());
            }
            // switchDimension queues its initial view; recenter the preset after that task.
            if(dimensionChanged&&isDynamicMode) SwingUtilities.invokeLater(this::applyPendingPreset);else applyPendingPreset();
        } catch(Exception e) {showFileError(e);}
    }
    private void applyPendingPreset() {
        if(pendingPreset==null||mapCanvas==null) return;
        if(isDynamicMode) {if(dynamicMapCanvas==null||dynamicRegionManager==null) return;}
        else if(mapCanvas.getImage()==null) return;
        SelectionPreset preset=pendingPreset;pendingPreset=null;
        activeCanvas().restoreSelectionBounds(preset.bounds);
        if(!preset.shape.isEmpty())activeCanvas().setSelectionShape(preset.shape,false);
        overlays.waypoints.replace(preset.waypoints);
        if(preset.minY!=null) heightRangeSlider.setSelectedRange(preset.minY,preset.maxY);
        jumpTo(preset.bounds.center());updateSelectionStats();
    }
    private void exportView() {
        File file=chooseOutput("Export current map view","png");if(file==null) return;
        final BufferedImage image;
        try {image=ViewExport.capture((JComponent)activeCanvas());}catch(Exception e) {showFileError(e);return;}
        new SwingWorker<Void,Void>() {
            protected Void doInBackground() throws Exception {ViewExport.write(image,file);return null;}
            protected void done() {try {get();progressLabel.setText("View exported: "+file);}catch(Exception e) {showFileError(e);}}
        }.execute();
    }
    private void showFileError(Exception e) {JOptionPane.showMessageDialog(this,e.getMessage(),"Map selector",JOptionPane.ERROR_MESSAGE);}

    private void queueSelectionCoverage() {
        if(selectionCoverageTimer==null) {selectionCoverageTimer=new javax.swing.Timer(180,e->refreshSelectionCoverage());selectionCoverageTimer.setRepeats(false);}selectionCoverageTimer.restart();
    }
    private void refreshSelectionCoverage() {
        final SelectionCanvas canvas=activeCanvas();final SelectionMath.Bounds b=canvas.getSelectionBounds();final long generation=viewGeneration;if(b==null) return;
        if(selectionCoverageWorker!=null) selectionCoverageWorker.cancel(true);
        selectionCoverageWorker=new SwingWorker<SelectionYFit.Result,Void>() {
            protected SelectionYFit.Result doInBackground() {return canvas.fitSelectionY(b,true);}
            protected void done() {
                if(isCancelled()||generation!=viewGeneration||canvas!=activeCanvas()||!b.equals(canvas.getSelectionBounds())) return;
                try {
                    SelectionYFit.Result r=get();SelectionStats stats=new SelectionStats(b,heightRangeSlider.getLowerValue(),heightRangeSlider.getUpperValue());
                    java.math.BigInteger total=java.math.BigInteger.valueOf((long)b.maxX-b.minX+1).multiply(java.math.BigInteger.valueOf((long)b.maxZ-b.minZ+1));
                    java.math.BigInteger missing=total.subtract(java.math.BigInteger.valueOf(r.count+r.empty)).max(java.math.BigInteger.ZERO);
                    selectionStatsLabel.setText(stats.toString()+" · cached "+r.count+" / empty "+r.empty+" / gaps "+missing);
                    selectionStatsLabel.setToolTipText("Cached surface columns: "+r.count+"; loaded empty: "+r.empty+"; missing/unloaded: "+missing+". Cost is volume capacity, not actual solid blocks. Alt+arrows expand; Ctrl+Alt+arrows shrink; Shift=16.");
                } catch(Exception e) {if(!isCancelled()) progressLabel.setText("Coverage unavailable: "+e.getMessage());}
            }
        };selectionCoverageWorker.execute();
    }

    private void handleDroppedFile(File file)throws IOException {
        switch(DropFiles.classify(file)) {
            case WORLD:loadWorldFromPath(DropFiles.world(file).getCanonicalPath());break;
            case PRESET:loadPresetFile(file);break;
            case PACK:
                if(JOptionPane.showConfirmDialog(this,"Add resource pack "+file.getName()+" and rebuild preview colors?","Resource pack",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                String path=file.getCanonicalPath();if(!colorResources.packs.contains(path))colorResources.packs.add(path);reloadColorResources();break;
        }
    }
    private void addWaypoint(){SelectionMath.Bounds b=activeCanvas().getSelectionBounds();int[] hover=activeCanvas().getHoverCoordinates();Point point=b!=null?b.center():hover!=null?new Point(hover[0],hover[2]):new Point();
        JTextField label=new JTextField("POI"),x=new JTextField(Integer.toString(point.x)),z=new JTextField(Integer.toString(point.y));JPanel panel=new JPanel(new GridLayout(3,2,6,6));panel.add(new JLabel("Label"));panel.add(label);panel.add(new JLabel("X"));panel.add(x);panel.add(new JLabel("Z"));panel.add(z);
        if(JOptionPane.showConfirmDialog(this,panel,"Add waypoint",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        try{overlays.waypoints.add(new com.minecraft.selector.core.WaypointStore.Marker(label.getText(),Integer.parseInt(x.getText().trim()),Integer.parseInt(z.getText().trim()),overlays.dimension));repaintCanvases();}catch(Exception e){showFileError(e);}
    }
    private void removeWaypoint(){List<com.minecraft.selector.core.WaypointStore.Marker> markers=overlays.waypoints.list();if(markers.isEmpty()){progressLabel.setText("No waypoints to remove");return;}JList<com.minecraft.selector.core.WaypointStore.Marker> list=new JList<>(markers.toArray(new com.minecraft.selector.core.WaypointStore.Marker[0]));list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setSelectedIndex(0);if(JOptionPane.showConfirmDialog(this,new JScrollPane(list),"Remove waypoint",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION&&list.getSelectedIndex()>=0){overlays.waypoints.remove(list.getSelectedIndex());repaintCanvases();}}
    private void saveWaypoints(){File file=chooseOutput("Save waypoints","json");if(file!=null)try{overlays.waypoints.save(file);}catch(Exception e){showFileError(e);}}
    private void loadWaypoints(){JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)try{overlays.waypoints.replace(com.minecraft.selector.core.WaypointStore.load(chooser.getSelectedFile()));repaintCanvases();}catch(Exception e){showFileError(e);}}
    private void trimViewportCache(){if(isDynamicMode&&dynamicRegionManager!=null)dynamicRegionManager.trimViewportCache();else {ViewLayers.clearCache();dynamicMapManager.clear();}repaintCanvases();progressLabel.setText("Viewport cache trimmed");}
    private void cancelP2Scan(){p2ScanRevision++;if(p2ScanWorker!=null){p2ScanWorker.cancel(true);p2ScanWorker=null;}overlays.oreCounts=java.util.Collections.emptyMap();}
    private void scanOres(){JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Ore scan JSON (regex / ids)");if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{startP2Scan(com.minecraft.selector.core.OverlayScan.load(chooser.getSelectedFile()),null);}catch(Exception e){showFileError(e);}}
    private void filterChunks(){JTextField time=new JTextField("0"),min=new JTextField(),max=new JTextField();JPanel panel=new JPanel(new GridLayout(3,2,6,6));panel.add(new JLabel("InhabitedTime > ticks (blank = any)"));panel.add(time);panel.add(new JLabel("DataVersion minimum (blank = any)"));panel.add(min);panel.add(new JLabel("DataVersion maximum (blank = any)"));panel.add(max);if(JOptionPane.showConfirmDialog(this,panel,"Suggest selection: AND conditions",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        try{startP2Scan(null,new com.minecraft.selector.core.ChunkFilter(time.getText().trim().isEmpty()?null:Long.valueOf(time.getText().trim()),min.getText().trim().isEmpty()?null:Integer.valueOf(min.getText().trim()),max.getText().trim().isEmpty()?null:Integer.valueOf(max.getText().trim())));}catch(Exception e){showFileError(e);}
    }
    private void startP2Scan(com.minecraft.selector.core.OverlayScan ore,com.minecraft.selector.core.ChunkFilter filter)throws IOException {
        File directory=currentDimension==null?lastMcaPath==null?null:new File(lastMcaPath).getParentFile():currentDimension.getRegionDir();if(directory==null)throw new IOException("Load a world or MCA first");
        final File[] files=lastMcaPath!=null&&currentDimension==null?new File[]{new File(lastMcaPath)}:directory.listFiles((d,n)->n.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
        cancelP2Scan();final long revision=p2ScanRevision,generation=viewGeneration;final SelectionCanvas canvas=activeCanvas();final SelectionMath.Bounds before=canvas.getSelectionBounds();final Map<String,Object> beforeShape=canvas.getSelectionShape().json();final int minY=heightRangeSlider.getLowerValue(),maxY=heightRangeSlider.getUpperValue();
        progressLabel.setText("Scanning chunk NBT (current selection if set, otherwise dimension; up to 65536 chunks); View → Cancel background scan");
        p2ScanWorker=new SwingWorker<com.minecraft.selector.core.OverlayScan.Result,Void>(){
            protected com.minecraft.selector.core.OverlayScan.Result doInBackground()throws Exception{return com.minecraft.selector.core.OverlayScan.scan(files,ore,filter,minY,maxY,()->isCancelled()||generation!=viewGeneration||revision!=p2ScanRevision,before);}
            protected void done(){if(isCancelled()||generation!=viewGeneration||revision!=p2ScanRevision)return;try{com.minecraft.selector.core.OverlayScan.Result result=get();
                if(ore!=null){if(minY!=heightRangeSlider.getLowerValue()||maxY!=heightRangeSlider.getUpperValue()){progressLabel.setText("Import Y changed while scanning; run ore scan again");return;}overlays.oreCounts=java.util.Collections.unmodifiableMap(result.counts);overlays.ore=true;JCheckBoxMenuItem item=previewItems.get("ore");if(item!=null)item.setSelected(true);}
                else if(!result.suggested.isEmpty()){if(canvas!=activeCanvas()||!java.util.Objects.equals(before,canvas.getSelectionBounds())||!beforeShape.equals(canvas.getSelectionShape().json())){progressLabel.setText("Selection changed while filtering; run suggestion again");return;}canvas.setSelectionShape(result.suggested,true);jumpTo(result.suggested.bounds().center());}
                progressLabel.setText("Scan: "+result.scanned+" chunks, "+result.failed+" unreadable; "+(ore!=null?result.counts.size()+" ore chunks":result.suggested.boxes().size()+" matching chunks"));repaintCanvases();
            }catch(Exception e){showFileError(e);}finally{p2ScanWorker=null;}}
        };p2ScanWorker.execute();
    }
}
