package com.minecraft.selector.gui;

import com.minecraft.selector.core.MapRenderer;
import com.minecraft.selector.core.MinecraftResourceExtractor;
import com.minecraft.selector.core.BlockColors;
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
    private DynamicMapManager dynamicMapManager;
    // private JButton renderButton; // 已删除

    // 多区域渲染的坐标信息
    private int lastMultiRenderMinRegionX = 0;
    private int lastMultiRenderMinRegionZ = 0;
    private JButton renderAroundPlayerButton;
    private JButton jumpButton;
    private JButton confirmSelectionButton;
    private JButton reRenderButton;
    private JButton view3DButton;
    private MapCanvas mapCanvas;
    private JProgressBar progressBar;
    private JLabel progressLabel;
    
    // 数据
    private String savePath;
    private double[] playerPos;
    private Rectangle selectedRegion;
    private BufferedImage mapImage;
    private MapRenderer.BlockInfo[][] lastRenderedBlockData; // 保存最后渲染的方块数据

    private double customScale = 1.0;
    private Point currentRegion;
    private String currentOutputFile;
    private String currentJsonFile;
    private MinecraftResourceExtractor resourceExtractor;
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
        this.isBlenderMode = (outputFile != null);
        this.blenderMinY = minY;
        this.blenderMaxY = maxY;

        // 初始化线程池
        initializeThreadPools();

        initializeGUI();

        // 尝试加载已保存的颜色文件
        // World loading initializes its own colors before rendering. Avoid a
        // competing startup extraction (and unscoped colors from another world).
        if (!isBlenderMode || worldPath == null) {
            loadSavedColors();
        }

        // 如果是Blender模式，显示模式信息
        if (isBlenderMode) {
            SwingUtilities.invokeLater(() -> {
                progressLabel.setText("Blender integration mode started");

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
                        progressLabel.setText("Blender mode: world path does not exist - " + worldPath);
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
        // 如果提供了JAR路径，优先从JAR文件提取颜色
        if (jarFilePath != null && new File(jarFilePath).exists()) {
            System.out.println("Extracting colors from provided JAR path: " + jarFilePath);
            extractColorsFromJar(jarFilePath);
            return;
        }

        // 优先尝试加载JSON格式
        String jsonColorFile = new File(programDir, "block_colors.json").getAbsolutePath();
        if (new File(jsonColorFile).exists()) {
            if (resourceExtractor.loadExtractedColorsFromJson(jsonColorFile)) {
                BlockColors.setResourceExtractor(resourceExtractor);
                System.out.println("Loaded saved block colors (JSON)");
                return;
            }
        }

        // 如果JSON不存在，尝试加载Properties格式
        String propsColorFile = new File(programDir, "extracted_colors.properties").getAbsolutePath();
        if (new File(propsColorFile).exists()) {
            if (resourceExtractor.loadExtractedColors(propsColorFile)) {
                BlockColors.setResourceExtractor(resourceExtractor);
                System.out.println("Loaded saved block colors (Properties)");
            }
        }
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
                shutdownThreadPools();
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
        saveInfoLabel = StyledComponents.createInfoLabel("No save selected");
        playerPosLabel = StyledComponents.createInfoLabel("Player position: unknown");
        regionLabel = StyledComponents.createInfoLabel("Selection: none");
        coordDisplayLabel = StyledComponents.createInfoLabel("X: 0, Z: 0");
        fileInfoLabel = StyledComponents.createInfoLabel("");


        // 输入框 - 使用样式化输入框
        minYEntry = StyledComponents.createStyledTextField("-64", 6);
        maxYEntry = StyledComponents.createStyledTextField("320", 6);
        xCoordEntry = StyledComponents.createStyledTextField("", 8);
        zCoordEntry = StyledComponents.createStyledTextField("", 8);

        // 创建高度范围双滑块
        heightRangeSlider = new StyledComponents.DualRangeSlider(-64, 365, -64, 365);
        heightRangeSlider.addChangeListener(e -> {
            // 当滑块值改变时，同步更新文本框
            minYEntry.setText(String.valueOf(heightRangeSlider.getLowerValue()));
            maxYEntry.setText(String.valueOf(heightRangeSlider.getUpperValue()));
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
        lodDropdown = StyledComponents.createStyledComboBox(lodOptions);
        lodDropdown.setSelectedIndex(0);

        // MCA文件范围下拉框
        mcaRangeDropdown = StyledComponents.createStyledComboBox(new String[]{"1x1 (1 file)", "3x3 (9 files)", "5x5 (25 files)", "7x7 (49 files)"});
        mcaRangeDropdown.setSelectedItem("1x1 (1 file)");

        // Dimension selector is populated after a save is loaded.
        dimensionDropdown = new JComboBox<>();
        dimensionDropdown.setPreferredSize(new Dimension(210, 26));
        dimensionDropdown.setEnabled(false);
        dimensionDropdown.addActionListener(e -> {
            if (!updatingDimensionDropdown) switchDimension((WorldPathResolver.DimensionInfo) dimensionDropdown.getSelectedItem());
        });

        // 自动加载复选框 - 使用样式化复选框
        autoLoadCheckbox = StyledComponents.createStyledCheckBox("Enable auto-load", true);

        // 按钮 - 使用新的图标和样式
        // renderButton 已删除
        renderAroundPlayerButton = createStyledButton("Render around player", IconManager.getPlayerIcon(IconManager.SMALL_ICON_SIZE));
        renderAroundPlayerButton.setEnabled(false);
        jumpButton = createStyledButton("Jump to coords", IconManager.getJumpIcon(IconManager.SMALL_ICON_SIZE));
        confirmSelectionButton = createStyledButton("Confirm selection", IconManager.getConfirmIcon(IconManager.SMALL_ICON_SIZE));
        confirmSelectionButton.setEnabled(false);
        reRenderButton = createStyledButton("Re-render", IconManager.getRefreshIcon(IconManager.SMALL_ICON_SIZE));
        reRenderButton.setEnabled(false);
        view3DButton = createStyledButton("3D View", null);
        view3DButton.setEnabled(false);
        
        // 地图画布
        mapCanvas = new MapCanvas();

        // 初始化动态地图管理器
        dynamicMapManager = new DynamicMapManager();

        // 设置选择回调
        mapCanvas.setSelectionCallback(new MapCanvas.SelectionCallback() {
            @Override
            public void onSelectionComplete(int minX, int minZ, int maxX, int maxZ) {
                // 选择完成时只更新UI显示，启用确认按钮
                SwingUtilities.invokeLater(() -> {
                    confirmSelectionButton.setEnabled(true);
                    progressLabel.setText(String.format("Selected area: (%d,%d) to (%d,%d) - click Confirm",
                        minX, minZ, maxX, maxZ));
                });
            }

            @Override
            public void onSelectionConfirmed(int minX, int minZ, int maxX, int maxZ) {
                // 按Enter确认时返回坐标并更新输入框
                SwingUtilities.invokeLater(() -> {
                    int centerX = (minX + maxX) / 2;
                    int centerZ = (minZ + maxZ) / 2;
                    int sizeX = maxX - minX;
                    int sizeZ = maxZ - minZ;
                    int size = Math.max(sizeX, sizeZ);

                    // 地图范围选择输入框已删除，不再更新输入框

                    progressLabel.setText(String.format("Confirmed area: (%d,%d) to (%d,%d)",
                        minX, minZ, maxX, maxZ));

                    // 在控制台输出坐标
                    System.out.printf("=== Confirmed Selection ===\n");
                    System.out.printf("Top-left corner: (%d, %d)\n", minX, minZ);
                    System.out.printf("Bottom-right corner: (%d, %d)\n", maxX, maxZ);
                    System.out.printf("Center: (%d, %d)\n", centerX, centerZ);
                    System.out.printf("Area size: %dx%d\n", sizeX, sizeZ);

                    // 如果是Blender模式，输出坐标到文件并关闭程序
                    if (isBlenderMode && outputFilePath != null) {
                        outputCoordinatesToFile(minX, minZ, maxX, maxZ);
                    }
                });
            }
        });

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
        progressBar = StyledComponents.createStyledProgressBar();
        progressLabel = StyledComponents.createInfoLabel("Ready");

        // 状态指示器
        statusIndicator = new LoadingAnimationPanel.StatusIndicator();
        statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.IDLE);
    }

    /**
     * 创建样式化按钮
     */
    private JButton createStyledButton(String text, Icon icon) {
        JButton button = new JButton(text, icon);

        // 设置按钮样式 - 更紧凑的设计
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));  // 稍微减小字体
        button.setIconTextGap(6);  // 减少图标和文字间距
        button.setMargin(new Insets(4, 8, 4, 8));  // 减少按钮内边距

        // 设置按钮颜色
        button.setBackground(UIThemeManager.ThemeColors.PRIMARY_BLUE);
        button.setForeground(Color.WHITE);

        // 设置按钮的最大尺寸，防止超出面板边界
        button.setMaximumSize(new Dimension(280, 32));  // 限制宽度和高度
        button.setPreferredSize(new Dimension(280, 32));

        // 添加悬停效果
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(UIThemeManager.ThemeColors.PRIMARY_BLUE.brighter());
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(UIThemeManager.ThemeColors.PRIMARY_BLUE);
                }
            }
        });

        return button;
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
    private void showLoadingState(String message) {
        SwingUtilities.invokeLater(() -> {
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.WORKING);
            progressLabel.setText(message);
        });
    }

    /**
     * 隐藏加载状态
     */
    private void hideLoadingState() {
        SwingUtilities.invokeLater(() -> {
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.IDLE);
        });
    }

    /**
     * 显示成功状态
     */
    private void showSuccessState(String message) {
        SwingUtilities.invokeLater(() -> {
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.SUCCESS);
            progressLabel.setText(message);
        });
    }

    /**
     * 显示错误状态
     */
    private void showErrorState(String message) {
        SwingUtilities.invokeLater(() -> {
            statusIndicator.setStatus(LoadingAnimationPanel.StatusIndicator.StatusType.ERROR);
            progressLabel.setText(message);
        });
    }
    
    private void layoutComponents() {
        setLayout(new BorderLayout());

        // 菜单栏 (Windows 原生风格)
        setJMenuBar(createMenuBar());

        // 顶部交互工具栏 (滑块/输入框等)
        add(createToolbar(), BorderLayout.NORTH);

        // 右侧地图显示区域 - 使用现代化边框
        JPanel mapPanel = new JPanel(new BorderLayout());
        mapPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(UIThemeManager.ThemeColors.MINECRAFT_STONE, 2, true),
                "Map Preview",
                TitledBorder.CENTER,
                TitledBorder.TOP,
                new Font(Font.SANS_SERIF, Font.BOLD, 14),
                UIThemeManager.ThemeColors.MINECRAFT_STONE
            ),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));

        JScrollPane mapScrollPane = new JScrollPane(mapCanvas);
        mapScrollPane.setMinimumSize(new Dimension(400, 300));
        mapScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        mapScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        mapPanel.add(mapScrollPane, BorderLayout.CENTER);
        mapPanel.setMinimumSize(new Dimension(400, 300));

        add(mapPanel, BorderLayout.CENTER);

        // 底部状态栏
        JPanel statusPanel = new JPanel(new BorderLayout());
        JPanel leftStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftStatusPanel.add(statusIndicator);
        leftStatusPanel.add(progressLabel);
        statusPanel.add(leftStatusPanel, BorderLayout.WEST);
        statusPanel.add(coordDisplayLabel, BorderLayout.EAST);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(progressBar, BorderLayout.NORTH);
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
        fileMenu.addSeparator();
        addMenuItem(fileMenu, "Clear Cache", e -> clearPngCache(), null, "Delete PNG map cache files");
        fileMenu.addSeparator();
        addMenuItem(fileMenu, "Log", e -> openLogWindow(), null, "Open log window");
        fileMenu.addSeparator();
        addMenuItem(fileMenu, "Exit", e -> System.exit(0), null, null);
        menuBar.add(fileMenu);

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
        confirmSelectionButton.setBackground(new Color(40, 167, 69));
        bar.add(confirmSelectionButton);

        bar.add(new JSeparator(SwingConstants.VERTICAL));

        // ── 高度设置 ──
        bar.add(new JLabel("Height:"));
        heightRangeSlider.setPreferredSize(new Dimension(120, 22));
        bar.add(heightRangeSlider);
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
     * 渲染指定区域
     */
    private void renderRegion(int minX, int maxX, int minZ, int maxZ) {
        showLoadingState("Rendering map area...");
        // renderButton 已删除
        renderAroundPlayerButton.setEnabled(false);

        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    // 获取LOD设置
                    int lodLevel = getLodLevel();

                    // 渲染地图
                    String regionPath = WorldPathResolver.getOverworldRegionDir(savePath).getAbsolutePath();
                    BufferedImage image = MapRenderer.renderRegion(regionPath, minX, maxX, minZ, maxZ, lodLevel);

                    if (image != null) {
                        SwingUtilities.invokeLater(() -> {
                            mapImage = image;
                            mapCanvas.setImage(image); // 手动渲染时不重置视图

                            // 设置世界坐标映射
                            double pixelsPerBlock = (double) image.getWidth() / (maxX - minX);
                            mapCanvas.setWorldCoordinateMapping(minX, minZ, pixelsPerBlock);

                            mapCanvas.repaint();

                            // 保存图像和数据
                            saveRenderedData(image, minX, maxX, minZ, maxZ);

                            showSuccessState(String.format("Render complete - area: (%d,%d) to (%d,%d)", minX, minZ, maxX, maxZ));
                        });
                    } else {
                        SwingUtilities.invokeLater(() -> {
                            showErrorState("Render failed");
                        });
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        showErrorState("Render error: " + e.getMessage());
                        JOptionPane.showMessageDialog(MinecraftMapGUI.this, "Render failed: " + e.getMessage(),
                                                    "Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
                return null;
            }

            @Override
            protected void done() {
                // renderButton 已删除
                renderAroundPlayerButton.setEnabled(true);
                hideLoadingState();
            }
        };

        worker.execute();
    }

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

        currentDimension = dim;
        logManager.info("Switch dimension: " + dim.getId() + " -> " + dim.getRegionDir().getAbsolutePath(), "MinecraftMapGUI");

        // 如果当前处于动态模式，重建动态区域管理器并清空旧缓存
        if (isDynamicMode && dynamicMapCanvas != null && dynamicRegionManager != null) {
            try {
                dynamicMapCanvas.cleanup();
                dynamicRegionManager.shutdown(); // stop old dimension jobs; do not retain its pool/cache
                dynamicRegionManager = new DynamicRegionManager(savePath, getCurrentRegionDir());
                dynamicMapCanvas.setRegionManager(dynamicRegionManager);
                SwingUtilities.invokeLater(() -> {
                    dynamicMapCanvas.fitToWindow();
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
            savePath = selectedDir.getAbsolutePath();
            saveInfoLabel.setText("Current save: " + selectedDir.getName());
            
            showLoadingState("Reading save info...");
            
            // 在后台线程中读取存档信息
            SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    loadSaveData();
                    return null;
                }
                
                @Override
                protected void done() {
                    showSuccessState("Save loaded");
                    // 启用渲染按钮
                    // renderButton 已删除
                    renderAroundPlayerButton.setEnabled(true);
                    reRenderButton.setEnabled(true);
                    view3DButton.setEnabled(true);

                    // 默认启用自动加载
                    toggleAutoLoad();
                    
                    // 自动启用动态模式
                    SwingUtilities.invokeLater(() -> {
                        if (!isDynamicMode) {
                            enableDynamicMode();
                        }
                    });
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
    private void loadSaveData() {
        try {
            // Discover vanilla and mod dimensions before rendering.
            availableDimensions = WorldPathResolver.discoverDimensions(savePath);
            if (!availableDimensions.isEmpty()) {
                currentDimension = availableDimensions.get(0); // resolver keeps Overworld first
            } else {
                currentDimension = new WorldPathResolver.DimensionInfo(
                    "minecraft:overworld", "Overworld", WorldPathResolver.getOverworldRegionDir(savePath));
                availableDimensions.add(currentDimension);
            }
            SwingUtilities.invokeLater(this::refreshDimensionDropdown);

            // 先完成颜色提取，再开始渲染（提取是同步的，避免渲染与换色竞态）
            autoExtractColorsFromSavePath();

            // 读取level.dat获取玩家位置
            File levelDat = new File(savePath, "level.dat");
            logManager.info("Checking level.dat: " + levelDat.getAbsolutePath(), "MinecraftMapGUI");
            logManager.info("level.dat exists: " + levelDat.exists(), "MinecraftMapGUI");
            
            if (levelDat.exists()) {
                playerPos = readPlayerPos(levelDat.getAbsolutePath());
                logManager.info("Player position read: " + (playerPos != null ? 
                    String.format("X=%.1f, Y=%.1f, Z=%.1f", playerPos[0], playerPos[1], playerPos[2]) : "null"), "MinecraftMapGUI");
                
                if (playerPos != null) {
                    SwingUtilities.invokeLater(() -> {
                        playerPosLabel.setText(String.format("Player: X:%.1f, Y:%.1f, Z:%.1f",
                                                           playerPos[0], playerPos[1], playerPos[2]));
                    });

                    // 渲染玩家周围的区域
                    renderAroundPlayer();
                } else {
                    logManager.warn("Cannot read player position, skipping render", "MinecraftMapGUI");
                }
            } else {
                logManager.warn("level.dat not found, cannot get player position", "MinecraftMapGUI");
            }

        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, "Error loading save: " + e.getMessage(),
                                            "Error", JOptionPane.ERROR_MESSAGE);
                progressLabel.setText("Failed to load save");
            });
        }
    }

    /**
     * 从存档路径自动提取颜色（包括minecraft和mods）
     */
    private void autoExtractColorsFromSavePath() {
        // loadSaveData already runs on a worker. Finish extraction before any
        // player-area rendering or dynamic loading starts; do not launch a second worker.
        boolean success = BlockColors.initializeColorsFromSavePath(savePath, jarFilePath);
        SwingUtilities.invokeLater(() -> progressLabel.setText(success
            ? "Loaded Minecraft and mod block colors"
            : "No JAR or mods found, using default colors"));
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
        progressLabel.setText("Extracting block colors from JAR...");

        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                MinecraftResourceExtractor extractor = new MinecraftResourceExtractor();
                if (extractor.extractColorsFromMinecraftJar(jarPath)) {
                    resourceExtractor = extractor;
                    BlockColors.setResourceExtractor(extractor);

                    // 保存为JSON和Properties两种格式
                    String jsonFile = new File(programDir, "block_colors.json").getAbsolutePath();
                    String propsFile = new File(programDir, "extracted_colors.properties").getAbsolutePath();

                    resourceExtractor.saveExtractedColorsAsJson(jsonFile);
                    resourceExtractor.saveExtractedColors(propsFile);

                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("Extracted " + resourceExtractor.getAllExtractedColors().size() + " block colors");
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("JAR color extraction failed");
                    });
                }
                return null;
            }
        };

        worker.execute();
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
        if (playerPos == null) {
            return;
        }

        // 计算玩家所在的区域文件
        int centerRegionX = (int) (playerPos[0] / 512);
        int centerRegionZ = (int) (playerPos[2] / 512);

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
        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                // 创建进度回调
                MapRenderer.ProgressCallback progressCallback = new MapRenderer.ProgressCallback() {
                    @Override
                    public void onProgress(int processed, int total, double speed, Set<String> foundBlocks) {
                        if (total == 0) return;

                        int percent = Math.min(100, processed * 100 / total);
                        SwingUtilities.invokeLater(() -> {
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
                int maxWorkers = Math.min(Runtime.getRuntime().availableProcessors(), 4);
                MapRenderer renderer = new MapRenderer(maxWorkers, progressCallback);

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
                    BufferedImage combinedImage = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
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
                                }
                            }
                        }
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

                        // 保存图像
                        saveRenderedImage(image, String.format("multi_region_%dx%d", gridSize, gridSize));

                    } else {
                        progressLabel.setText("Multi-region render failed: cannot get block data");
                        progressBar.setValue(0);
                        progressBar.setString("Failed");
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(MinecraftMapGUI.this,
                                                "Error rendering multi-region map: " + e.getMessage(),
                                                "Error", JOptionPane.ERROR_MESSAGE);
                    progressLabel.setText("Multi-region render failed");
                    progressBar.setValue(0);
                    progressBar.setString("Failed");
                }
            }
        };

        worker.execute();
    }

    /**
     * 渲染单个MCA文件
     */
    private void renderSingleMcaFile(String mcaFilePath) {
        SwingUtilities.invokeLater(() -> {
            progressLabel.setText("Rendering MCA file: " + new File(mcaFilePath).getName());
        });

        renderMap(mcaFilePath);
    }

    /**
     * 渲染地图
     */
    private void renderMap(String regionPath) {
        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                // 创建进度回调
                MapRenderer.ProgressCallback progressCallback = new MapRenderer.ProgressCallback() {
                    @Override
                    public void onProgress(int processed, int total, double speed, Set<String> foundBlocks) {
                        if (total == 0) return;

                        int percent = Math.min(100, processed * 100 / total);
                        SwingUtilities.invokeLater(() -> {
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
                MapRenderer renderer = new MapRenderer(maxWorkers, progressCallback);

                try {
                    // 渲染区块
                    MapRenderer.BlockInfo[][] topBlocks = renderer.getTopBlocks(regionPath, 32, 1);

                    if (topBlocks != null) {
                        // 保存方块数据供3D查看器使用
                        lastRenderedBlockData = topBlocks;
                        
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
                try {
                    BufferedImage image = get();
                    if (image != null) {
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
                    } else {
                        progressLabel.setText("Map render failed: cannot get block data");
                        progressBar.setValue(0);
                        progressBar.setString("Failed");
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(MinecraftMapGUI.this,
                                                "Error rendering map: " + e.getMessage(),
                                                "Error", JOptionPane.ERROR_MESSAGE);
                    progressLabel.setText("Map render failed");
                    progressBar.setValue(0);
                    progressBar.setString("Failed");
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
    private void confirmSelection() {
        // 根据当前模式触发相应画布的确认回调
        if (isDynamicMode && dynamicMapCanvas != null) {
            dynamicMapCanvas.confirmCurrentSelection();
        } else if (mapCanvas != null) {
            mapCanvas.confirmCurrentSelection();
        }

        // 禁用确认按钮
        confirmSelectionButton.setEnabled(false);
    }

    /**
     * 切换自动加载模式
     */
    private void toggleAutoLoad() {
        boolean enabled = autoLoadCheckbox.isSelected();

        if (mapCanvas != null) {
            mapCanvas.enableAutoViewportManagement(enabled);
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
        if (!autoLoadCheckbox.isSelected()) return;

        // 使用后台线程池异步加载
        Future<BufferedImage> future = backgroundExecutor.submit(() -> {
            String regionFile = String.format("r.%d.%d.mca", regionX, regionZ);
            File regionPath = WorldPathResolver.getMcaFile(savePath, regionX, regionZ);

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
            MapRenderer renderer = new MapRenderer(1, progressCallback);

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
                    // 添加到动态地图管理器
                    dynamicMapManager.addRegion(regionX, regionZ, regionImage);

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
     * 打开 3D 查看器 - 使用动态区块加载
     */
    private void openViewer3D() {
        if (savePath == null) {
            JOptionPane.showMessageDialog(this, 
                "Please select an MC save first", 
                "Info", 
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        try {
            System.out.println("Open dynamic 3D viewer");
            System.out.println("Save path: " + savePath);
            
            // 创建动态3D查看器
            Dynamic3DViewer viewer = new Dynamic3DViewer(savePath, 0, 0);
            viewer.setVisible(true);
            
        } catch (Exception ex) {
            System.err.println("Failed to open 3D viewer: " + ex.getMessage());
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, 
                "Failed to open 3D viewer: " + ex.getMessage(), 
                "Error", 
                JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 从指定路径加载世界（用于Blender集成）
     */
    private void loadWorldFromPath(String worldPath) {
        File worldDir = new File(worldPath);
        if (!worldDir.exists() || !worldDir.isDirectory()) {
            System.err.println("Invalid world path: " + worldPath);
            return;
        }

        System.out.println("Blender mode: loading world " + worldPath);

        // 设置存档路径
        this.savePath = worldPath;

        // 更新GUI界面显示存档路径
        SwingUtilities.invokeLater(() -> {
            if (saveInfoLabel != null) {
                saveInfoLabel.setText("Current save: " + worldDir.getName());
            }
            progressLabel.setText("Blender mode: reading save info...");
        });

        // 在后台线程中加载存档数据，模拟用户选择存档的完整流程
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                // 调用loadSaveData方法，这会完整地加载存档数据
                loadSaveData();
                return null;
            }

            @Override
            protected void done() {
                SwingUtilities.invokeLater(() -> {
                    progressLabel.setText("Blender mode: save loaded, auto-load enabled");

                    // 启用自动加载
                    autoLoadCheckbox.setSelected(true);
                    toggleAutoLoad();
                    
                    // 自动启用动态模式（与正常模式保持一致）
                    if (!isDynamicMode) {
                        enableDynamicMode();
                    }

                    System.out.println("Blender mode: world loaded with auto-load and dynamic mode " + worldPath);
                });
            }
        };
        worker.execute();
    }

    /**
     * 输出坐标到文件（用于Blender集成）
     */
    private void outputCoordinatesToFile(int minX, int minZ, int maxX, int maxZ) {
        try {
            // 使用高度滑块/输入框的当前值（用户在GUI中可调整），而非构造时的固定值
            int actualMinY;
            int actualMaxY;
            if (heightRangeSlider != null) {
                actualMinY = heightRangeSlider.getLowerValue();
                actualMaxY = heightRangeSlider.getUpperValue();
            } else {
                actualMinY = blenderMinY;
                actualMaxY = blenderMaxY;
            }

            // 写入文件
            try (java.io.FileWriter writer = new java.io.FileWriter(outputFilePath)) {
                // 简单的JSON输出
                writer.write("{\n");
                writer.write("  \"minX\": " + minX + ",\n");
                writer.write("  \"minY\": " + actualMinY + ",\n");
                writer.write("  \"minZ\": " + minZ + ",\n");
                writer.write("  \"maxX\": " + maxX + ",\n");
                writer.write("  \"maxY\": " + actualMaxY + ",\n");
                writer.write("  \"maxZ\": " + maxZ + ",\n");
                writer.write("  \"timestamp\": " + System.currentTimeMillis() + "\n");
                writer.write("}\n");
            }

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
                BufferedImage combinedImage = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = combinedImage.createGraphics();
                
                // 设置高质量渲染
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                
                // 填充背景色（深灰色表示未探索区域）
                g2d.setColor(new Color(32, 32, 32));
                g2d.fillRect(0, 0, totalWidth, totalHeight);
                
                // 创建进度回调
                MapRenderer.ProgressCallback progressCallback = new MapRenderer.ProgressCallback() {
                    @Override
                    public void onProgress(int processed, int total, double speed, java.util.Set<String> foundBlocks) {
                        SwingUtilities.invokeLater(() -> {
                            int percent = total > 0 ? (processed * 100 / total) : 0;
                            progressLabel.setText(String.format("Progress: %d%% (%d/%d) - %.1f chunks/s",
                                percent, processed, total, speed));
                        });
                    }
                    
                    @Override
                    public void onProgressiveRender(BufferedImage partialImage, int completedChunks, int totalChunks) {
                        // 全区域渲染中不需要渐进式回调
                    }
                };
                
                // 创建地图渲染器
                int maxWorkers = Math.min(Runtime.getRuntime().availableProcessors(), 4);
                MapRenderer renderer = new MapRenderer(maxWorkers, progressCallback);
                
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
                                } else {
                                    logManager.warn("Region image render failed: r." + regionX + "." + regionZ + ".mca", "MinecraftMapGUI");
                                }
                            } else {
                                logManager.warn("Region data fetch failed: r." + regionX + "." + regionZ + ".mca", "MinecraftMapGUI");
                            }
                        }
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
                try {
                    BufferedImage result = get();
                    if (result != null) {
                        mapImage = result;
                        
                        SwingUtilities.invokeLater(() -> {
                            // 更新显示
                            mapCanvas.setImage(result);
                            progressLabel.setText("Full-region render complete! All available regions rendered.");
                            
                            // 设置世界坐标映射
                            mapCanvas.setWorldCoordinateMapping(lastFullRenderMinWorldX, lastFullRenderMinWorldZ, 1.0);
                            
                            // 刷新显示
                            mapCanvas.repaint();
                        });
                        
                        logManager.info("Map display updated", "MinecraftMapGUI");
                    } else {
                        SwingUtilities.invokeLater(() -> {
                            progressLabel.setText("Full-region rendering failed");
                        });
                        logManager.error("Full-region rendering failed", "MinecraftMapGUI");
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("Render error: " + e.getMessage());
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
            
            // Create the manager for the currently selected dimension.
            dynamicRegionManager = new DynamicRegionManager(savePath, getCurrentRegionDir());
            
            // 创建动态地图画布
            dynamicMapCanvas = new DynamicMapCanvas();
            dynamicMapCanvas.setRegionManager(dynamicRegionManager);
            
            // 设置选区回调
            dynamicMapCanvas.setSelectionCallback(new DynamicMapCanvas.SelectionCallback() {
                @Override
                public void onSelectionComplete(int minX, int minZ, int maxX, int maxZ) {
                    // 选择完成时只更新UI显示，启用确认按钮
                    SwingUtilities.invokeLater(() -> {
                        confirmSelectionButton.setEnabled(true);
                        progressLabel.setText(String.format("Selected area: (%d,%d) to (%d,%d) - click Confirm",
                            minX, minZ, maxX, maxZ));
                    });
                }
                
                @Override
                public void onSelectionConfirmed(int minX, int minZ, int maxX, int maxZ) {
                    // 按确认按钮时返回坐标并更新输入框
                    SwingUtilities.invokeLater(() -> {
                        int centerX = (minX + maxX) / 2;
                        int centerZ = (minZ + maxZ) / 2;
                        int sizeX = maxX - minX;
                        int sizeZ = maxZ - minZ;

                        progressLabel.setText(String.format("Confirmed area: (%d,%d) to (%d,%d)",
                            minX, minZ, maxX, maxZ));

                        // 在控制台输出坐标
                        System.out.printf("=== Confirmed Selection ===\n");
                        System.out.printf("Top-left corner: (%d, %d)\n", minX, minZ);
                        System.out.printf("Bottom-right corner: (%d, %d)\n", maxX, maxZ);
                        System.out.printf("Center: (%d, %d)\n", centerX, centerZ);
                        System.out.printf("Area size: %dx%d\n", sizeX, sizeZ);

                        // 如果是Blender模式，输出坐标到文件并关闭程序
                        if (isBlenderMode && outputFilePath != null) {
                            outputCoordinatesToFile(minX, minZ, maxX, maxZ);
                        }
                    });
                }
            });
            
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
                dynamicMapCanvas.fitToWindow();
                dynamicMapCanvas.requestFocus(); // 确保可以接收键盘事件
            });
            
            isDynamicMode = true;
            
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
}
