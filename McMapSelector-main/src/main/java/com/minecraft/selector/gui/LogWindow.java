package com.minecraft.selector.gui;

import com.minecraft.selector.utils.LogManager;
import com.minecraft.selector.utils.LogManager.LogEntry;
import com.minecraft.selector.utils.LogManager.LogLevel;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志窗口
 * 显示实时日志和历史日志，支持导出功能
 */
public class LogWindow extends JFrame implements LogManager.LogListener {
    private final LogManager logManager;
    private final LogTableModel tableModel;
    private final JTable logTable;
    private JLabel statusLabel;
    private JComboBox<LogLevel> levelFilter;
    private JTextField searchField;
    private final SimpleDateFormat dateFormat;
    
    public LogWindow() {
        this.logManager = LogManager.getInstance();
        this.tableModel = new LogTableModel();
        this.logTable = new JTable(tableModel);
        this.dateFormat = new SimpleDateFormat("HH:mm:ss.SSS");
        
        initializeComponents();
        setupLayout();
        setupEventHandlers();
        
        // 注册为日志监听器
        logManager.addLogListener(this);
        
        // 加载现有日志
        refreshLogs();
        
        logManager.info("Log window opened", "LogWindow");
    }
    
    private void initializeComponents() {
        setTitle("Minecraft Map Selector - Log Viewer");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(1000, 600);
        setLocationRelativeTo(null);
        
        // 设置表格
        setupTable();
        
        // 状态标签
        statusLabel = new JLabel(logManager.getLogStats());
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        // 级别过滤器
        levelFilter = new JComboBox<>();
        levelFilter.addItem(null); // 显示所有级别
        for (LogLevel level : LogLevel.values()) {
            levelFilter.addItem(level);
        }
        levelFilter.setSelectedItem(null);
        
        // 搜索框
        searchField = new JTextField(20);
        searchField.setToolTipText("Search log content...");
    }
    
    private void setupTable() {
        // 设置表格属性
        logTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        logTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        logTable.getTableHeader().setReorderingAllowed(false);
        
        // 设置列宽
        TableColumn timeColumn = logTable.getColumnModel().getColumn(0);
        timeColumn.setPreferredWidth(100);
        timeColumn.setMaxWidth(120);
        
        TableColumn levelColumn = logTable.getColumnModel().getColumn(1);
        levelColumn.setPreferredWidth(60);
        levelColumn.setMaxWidth(80);
        
        TableColumn sourceColumn = logTable.getColumnModel().getColumn(2);
        sourceColumn.setPreferredWidth(120);
        sourceColumn.setMaxWidth(150);
        
        // 设置行高
        logTable.setRowHeight(20);
        
        // 设置渲染器
        logTable.setDefaultRenderer(Object.class, new LogCellRenderer());
        
        // 自动滚动到最新日志
        logTable.setAutoCreateRowSorter(false);
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // 顶部工具栏
        JPanel toolbarPanel = createToolbarPanel();
        add(toolbarPanel, BorderLayout.NORTH);
        
        // 中央表格
        JScrollPane scrollPane = new JScrollPane(logTable);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        add(scrollPane, BorderLayout.CENTER);
        
        // 底部状态栏
        add(statusLabel, BorderLayout.SOUTH);
    }
    
    private JPanel createToolbarPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createEtchedBorder());
        
        // 刷新按钮
        JButton refreshButton = new JButton("Refresh");
        refreshButton.setToolTipText("Refresh log list");
        panel.add(refreshButton);
        
        // 清空按钮
        JButton clearButton = new JButton("Clear");
        clearButton.setToolTipText("Clear all logs");
        panel.add(clearButton);
        
        // 导出按钮
        JButton exportButton = new JButton("Export");
        exportButton.setToolTipText("Export logs to file");
        panel.add(exportButton);
        
        panel.add(new JSeparator(SwingConstants.VERTICAL));
        
        // 级别过滤
        panel.add(new JLabel("Level:"));
        panel.add(levelFilter);
        
        panel.add(new JSeparator(SwingConstants.VERTICAL));
        
        // 搜索
        panel.add(new JLabel("Search:"));
        panel.add(searchField);
        JButton searchButton = new JButton("Search");
        panel.add(searchButton);
        
        // 事件处理
        refreshButton.addActionListener(e -> refreshLogs());
        clearButton.addActionListener(e -> clearLogs());
        exportButton.addActionListener(e -> exportLogs());
        levelFilter.addActionListener(e -> filterLogs());
        searchButton.addActionListener(e -> searchLogs());
        searchField.addActionListener(e -> searchLogs());
        
        return panel;
    }
    
    private void setupEventHandlers() {
        // 窗口关闭时移除监听器
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                logManager.removeLogListener(LogWindow.this);
                logManager.info("Log window closed", "LogWindow");
            }
        });
    }
    
    @Override
    public void onLogAdded(LogEntry entry) {
        // 在EDT中更新表格
        SwingUtilities.invokeLater(() -> {
            tableModel.addLogEntry(entry);
            updateStatus();
            
            // 自动滚动到最新日志
            int lastRow = logTable.getRowCount() - 1;
            if (lastRow >= 0) {
                logTable.scrollRectToVisible(logTable.getCellRect(lastRow, 0, true));
            }
        });
    }
    
    private void refreshLogs() {
        tableModel.setLogEntries(logManager.getAllLogs());
        updateStatus();
        logManager.debug("Log list refreshed", "LogWindow");
    }
    
    private void clearLogs() {
        int result = JOptionPane.showConfirmDialog(
            this,
            "Clear all logs? This cannot be undone.",
            "Confirm clear",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        
        if (result == JOptionPane.YES_OPTION) {
            logManager.clearLogs();
            refreshLogs();
        }
    }
    
    private void exportLogs() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export log file");
        fileChooser.setSelectedFile(new File("log.log"));
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "Log files (*.log)", "log"));
        
        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try {
                logManager.exportToFile(file.getAbsolutePath());
                JOptionPane.showMessageDialog(
                    this,
                    "Logs exported to: " + file.getAbsolutePath(),
                    "Export successful",
                    JOptionPane.INFORMATION_MESSAGE
                );
            } catch (Exception e) {
                logManager.error("Failed to export logs", "LogWindow", e);
                JOptionPane.showMessageDialog(
                    this,
                    "Failed to export logs: " + e.getMessage(),
                    "Export failed",
                    JOptionPane.ERROR_MESSAGE
                );
            }
        }
    }
    
    private void filterLogs() {
        LogLevel selectedLevel = (LogLevel) levelFilter.getSelectedItem();
        if (selectedLevel == null) {
            tableModel.setLogEntries(logManager.getAllLogs());
        } else {
            tableModel.setLogEntries(logManager.getLogsByLevel(selectedLevel));
        }
        updateStatus();
    }
    
    private void searchLogs() {
        String searchText = searchField.getText().trim().toLowerCase();
        if (searchText.isEmpty()) {
            refreshLogs();
            return;
        }
        
        List<LogEntry> allLogs = logManager.getAllLogs();
        List<LogEntry> filteredLogs = new ArrayList<>();
        
        for (LogEntry entry : allLogs) {
            if (entry.getMessage().toLowerCase().contains(searchText) ||
                entry.getSource().toLowerCase().contains(searchText)) {
                filteredLogs.add(entry);
            }
        }
        
        tableModel.setLogEntries(filteredLogs);
        updateStatus();
        logManager.debug("Search complete, found " + filteredLogs.size() + " matching logs", "LogWindow");
    }
    
    private void updateStatus() {
        statusLabel.setText(logManager.getLogStats() + " | showing: " + tableModel.getRowCount() + " entries");
    }
    
    /**
     * 日志表格模型
     */
    private class LogTableModel extends AbstractTableModel {
        private final String[] columnNames = {"Time", "Level", "Source", "Message"};
        private List<LogEntry> logEntries = new ArrayList<>();
        
        public void setLogEntries(List<LogEntry> entries) {
            this.logEntries = new ArrayList<>(entries);
            fireTableDataChanged();
        }
        
        public void addLogEntry(LogEntry entry) {
            logEntries.add(entry);
            int row = logEntries.size() - 1;
            fireTableRowsInserted(row, row);
        }
        
        @Override
        public int getRowCount() {
            return logEntries.size();
        }
        
        @Override
        public int getColumnCount() {
            return columnNames.length;
        }
        
        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }
        
        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex >= logEntries.size()) return null;
            
            LogEntry entry = logEntries.get(rowIndex);
            switch (columnIndex) {
                case 0: return dateFormat.format(entry.getTimestamp());
                case 1: return entry.getLevel();
                case 2: return entry.getSource();
                case 3: return entry.getMessage();
                default: return null;
            }
        }
        
        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (columnIndex) {
                case 1: return LogLevel.class;
                default: return String.class;
            }
        }
    }
    
    /**
     * 日志单元格渲染器
     */
    private class LogCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            
            if (!isSelected) {
                LogEntry entry = tableModel.logEntries.get(row);
                switch (entry.getLevel()) {
                    case ERROR:
                        c.setBackground(new Color(255, 230, 230));
                        break;
                    case WARN:
                        c.setBackground(new Color(255, 245, 230));
                        break;
                    case DEBUG:
                        c.setBackground(new Color(240, 240, 240));
                        break;
                    default:
                        c.setBackground(Color.WHITE);
                        break;
                }
            }
            
            return c;
        }
    }
}