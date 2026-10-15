package com.minecraft.selector.gui;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

/**
 * 样式化组件工厂类
 * 提供统一样式的UI组件
 */
public class StyledComponents {
    
    /**
     * 创建样式化的文本输入框
     */
    public static JTextField createStyledTextField(String text, int columns) {
        JTextField textField = new JTextField(text, columns);
        
        // 设置基本样式
        textField.setFont(textField.getFont().deriveFont(Font.PLAIN, 12f));
        textField.setBorder(createRoundedBorder(UIThemeManager.ThemeColors.PRIMARY_BLUE, false));
        
        // 添加焦点效果
        textField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                textField.setBorder(createRoundedBorder(UIThemeManager.ThemeColors.PRIMARY_BLUE, true));
            }
            
            @Override
            public void focusLost(FocusEvent e) {
                textField.setBorder(createRoundedBorder(UIThemeManager.ThemeColors.PRIMARY_BLUE, false));
            }
        });
        
        return textField;
    }
    
    /**
     * 创建样式化的下拉框
     */
    public static <T> JComboBox<T> createStyledComboBox(T[] items) {
        JComboBox<T> comboBox = new JComboBox<>(items);
        
        // 设置基本样式
        comboBox.setFont(comboBox.getFont().deriveFont(Font.PLAIN, 12f));
        comboBox.setBorder(createRoundedBorder(UIThemeManager.ThemeColors.MINECRAFT_GREEN, false));
        
        // 设置渲染器
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                
                if (isSelected) {
                    setBackground(UIThemeManager.ThemeColors.PRIMARY_BLUE);
                    setForeground(Color.WHITE);
                } else {
                    setBackground(Color.WHITE);
                    setForeground(Color.BLACK);
                }
                
                setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
                return this;
            }
        });
        
        return comboBox;
    }
    
    /**
     * 创建样式化的复选框
     */
    public static JCheckBox createStyledCheckBox(String text, boolean selected) {
        JCheckBox checkBox = new JCheckBox(text, selected);
        
        // 设置基本样式
        checkBox.setFont(checkBox.getFont().deriveFont(Font.BOLD, 12f));
        checkBox.setFocusPainted(false);
        
        // 设置颜色
        checkBox.setForeground(UIThemeManager.ThemeColors.MINECRAFT_GREEN);
        
        return checkBox;
    }
    
    /**
     * 创建样式化的标签
     */
    public static JLabel createStyledLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        return label;
    }
    
    /**
     * 创建标题标签
     */
    public static JLabel createTitleLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        label.setForeground(UIThemeManager.ThemeColors.PRIMARY_BLUE);
        return label;
    }
    
    /**
     * 创建信息标签
     */
    public static JLabel createInfoLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(Color.GRAY);
        return label;
    }
    
    /**
     * 创建成功状态标签
     */
    public static JLabel createSuccessLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setForeground(UIThemeManager.ThemeColors.SUCCESS_GREEN);
        return label;
    }
    
    /**
     * 创建警告状态标签
     */
    public static JLabel createWarningLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setForeground(UIThemeManager.ThemeColors.WARNING_ORANGE);
        return label;
    }
    
    /**
     * 创建错误状态标签
     */
    public static JLabel createErrorLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setForeground(UIThemeManager.ThemeColors.DANGER_RED);
        return label;
    }
    
    /**
     * 创建样式化的进度条
     */
    public static JProgressBar createStyledProgressBar() {
        JProgressBar progressBar = new JProgressBar();
        
        // 设置基本样式
        progressBar.setStringPainted(true);
        progressBar.setFont(progressBar.getFont().deriveFont(Font.BOLD, 11f));
        progressBar.setForeground(UIThemeManager.ThemeColors.SUCCESS_GREEN);
        progressBar.setBackground(Color.LIGHT_GRAY);
        
        // 设置边框
        progressBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIThemeManager.ThemeColors.PRIMARY_BLUE, 1),
            BorderFactory.createEmptyBorder(2, 2, 2, 2)
        ));
        
        return progressBar;
    }
    
    /**
     * 创建分隔符
     */
    public static JSeparator createStyledSeparator() {
        JSeparator separator = new JSeparator();
        separator.setForeground(UIThemeManager.ThemeColors.PRIMARY_BLUE);
        separator.setBackground(UIThemeManager.ThemeColors.PRIMARY_BLUE);
        return separator;
    }
    
    /**
     * 创建圆角边框
     */
    private static Border createRoundedBorder(Color color, boolean focused) {
        int thickness = focused ? 2 : 1;
        Color borderColor = focused ? color.brighter() : color;
        
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor, thickness, true),
            BorderFactory.createEmptyBorder(5, 8, 5, 8)
        );
    }
    
    /**
     * 创建带阴影效果的面板
     */
    public static JPanel createShadowPanel() {
        return new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 绘制阴影
                g2d.setColor(new Color(0, 0, 0, 30));
                g2d.fillRoundRect(2, 2, getWidth() - 2, getHeight() - 2, 10, 10);
                
                // 绘制主体
                g2d.setColor(getBackground());
                g2d.fillRoundRect(0, 0, getWidth() - 2, getHeight() - 2, 8, 8);
                
                g2d.dispose();
            }
        };
    }
    
    /**
     * 创建卡片样式面板
     */
    public static JPanel createCardPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1, true),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        panel.setBackground(Color.WHITE);
        return panel;
    }
    
    /**
     * 创建工具提示
     */
    public static void setStyledToolTip(JComponent component, String text) {
        component.setToolTipText(text);
        
        // 自定义工具提示样式
        UIManager.put("ToolTip.background", new Color(255, 255, 225));
        UIManager.put("ToolTip.foreground", Color.BLACK);
        UIManager.put("ToolTip.border", BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIThemeManager.ThemeColors.PRIMARY_BLUE, 1),
            BorderFactory.createEmptyBorder(5, 8, 5, 8)
        ));
    }
    
    /**
     * 添加悬停效果到组件
     */
    public static void addHoverEffect(JComponent component, Color normalColor, Color hoverColor) {
        Color originalBackground = component.getBackground();

        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (component.isEnabled()) {
                    component.setBackground(hoverColor);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (component.isEnabled()) {
                    component.setBackground(originalBackground != null ? originalBackground : normalColor);
                }
            }
        });
    }

    /**
     * 创建双滑块组件用于高度范围选择
     */
    public static class DualRangeSlider extends JPanel {
        private int minValue;
        private int maxValue;
        private int lowerValue;
        private int upperValue;
        private boolean draggingLower = false;
        private boolean draggingUpper = false;
        private final int THUMB_SIZE = 12;
        private final int TRACK_HEIGHT = 6;

        private java.util.List<ChangeListener> listeners = new java.util.ArrayList<>();

        public DualRangeSlider(int min, int max, int lower, int upper) {
            this.minValue = min;
            this.maxValue = max;
            this.lowerValue = Math.max(min, Math.min(lower, max));
            this.upperValue = Math.max(min, Math.min(upper, max));

            setPreferredSize(new Dimension(200, 40)); // 增加高度为标签留空间
            setOpaque(false);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleMousePressed(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    draggingLower = false;
                    draggingUpper = false;
                    setCursor(Cursor.getDefaultCursor());
                }
            });

            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    handleMouseDragged(e);
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    updateCursor(e);
                }
            });
        }

        public void addChangeListener(ChangeListener listener) {
            listeners.add(listener);
        }

        private void fireChangeEvent() {
            ChangeEvent event = new ChangeEvent(this);
            for (ChangeListener listener : listeners) {
                listener.stateChanged(event);
            }
        }

        private void handleMousePressed(MouseEvent e) {
            int trackStart = THUMB_SIZE / 2;
            int trackWidth = getWidth() - THUMB_SIZE;
            int trackY = (getHeight() - TRACK_HEIGHT) / 2 - 5;

            int lowerPos = trackStart + (int) ((double) (lowerValue - minValue) / (maxValue - minValue) * trackWidth);
            int upperPos = trackStart + (int) ((double) (upperValue - minValue) / (maxValue - minValue) * trackWidth);

            int mouseX = e.getX();
            int mouseY = e.getY();

            // 检查是否在滑块区域内
            int thumbY = trackY - (THUMB_SIZE - TRACK_HEIGHT) / 2;
            boolean inThumbArea = mouseY >= thumbY && mouseY <= thumbY + THUMB_SIZE;

            if (inThumbArea) {
                // 检查点击的是哪个滑块
                if (Math.abs(mouseX - lowerPos) <= THUMB_SIZE / 2) {
                    draggingLower = true;
                } else if (Math.abs(mouseX - upperPos) <= THUMB_SIZE / 2) {
                    draggingUpper = true;
                } else {
                    // 点击轨道，移动最近的滑块
                    if (Math.abs(mouseX - lowerPos) < Math.abs(mouseX - upperPos)) {
                        draggingLower = true;
                        updateLowerValue(mouseX);
                    } else {
                        draggingUpper = true;
                        updateUpperValue(mouseX);
                    }
                }
            }
        }

        private void handleMouseDragged(MouseEvent e) {
            if (draggingLower) {
                updateLowerValue(e.getX());
            } else if (draggingUpper) {
                updateUpperValue(e.getX());
            }
        }

        private void updateLowerValue(int mouseX) {
            int trackStart = THUMB_SIZE / 2;
            int trackWidth = getWidth() - THUMB_SIZE;

            double ratio = Math.max(0, Math.min(1, (double) (mouseX - trackStart) / trackWidth));
            int newValue = (int) (minValue + ratio * (maxValue - minValue));

            if (newValue != lowerValue && newValue <= upperValue) {
                lowerValue = newValue;
                repaint();
                fireChangeEvent();
            }
        }

        private void updateUpperValue(int mouseX) {
            int trackStart = THUMB_SIZE / 2;
            int trackWidth = getWidth() - THUMB_SIZE;

            double ratio = Math.max(0, Math.min(1, (double) (mouseX - trackStart) / trackWidth));
            int newValue = (int) (minValue + ratio * (maxValue - minValue));

            if (newValue != upperValue && newValue >= lowerValue) {
                upperValue = newValue;
                repaint();
                fireChangeEvent();
            }
        }

        private void updateCursor(MouseEvent e) {
            int trackStart = THUMB_SIZE / 2;
            int trackWidth = getWidth() - THUMB_SIZE;
            int trackY = (getHeight() - TRACK_HEIGHT) / 2 - 5;

            int lowerPos = trackStart + (int) ((double) (lowerValue - minValue) / (maxValue - minValue) * trackWidth);
            int upperPos = trackStart + (int) ((double) (upperValue - minValue) / (maxValue - minValue) * trackWidth);

            int mouseX = e.getX();
            int mouseY = e.getY();

            // 检查是否在滑块区域内
            int thumbY = trackY - (THUMB_SIZE - TRACK_HEIGHT) / 2;
            boolean inThumbArea = mouseY >= thumbY && mouseY <= thumbY + THUMB_SIZE;

            if (inThumbArea && (Math.abs(mouseX - lowerPos) <= THUMB_SIZE / 2 || Math.abs(mouseX - upperPos) <= THUMB_SIZE / 2)) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            } else {
                setCursor(Cursor.getDefaultCursor());
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 明确绘制背景，使用父容器的背景色
            Container parent = getParent();
            Color bgColor = (parent != null) ? parent.getBackground() : Color.WHITE;
            g2d.setColor(bgColor);
            g2d.fillRect(0, 0, getWidth(), getHeight());

            int trackStart = THUMB_SIZE / 2;
            int trackWidth = getWidth() - THUMB_SIZE;
            int trackY = (getHeight() - TRACK_HEIGHT) / 2 - 5; // 向上移动为标签留空间

            // 绘制轨道背景
            g2d.setColor(Color.LIGHT_GRAY);
            g2d.fillRoundRect(trackStart, trackY, trackWidth, TRACK_HEIGHT, TRACK_HEIGHT, TRACK_HEIGHT);

            // 计算滑块位置
            int lowerPos = trackStart + (int) ((double) (lowerValue - minValue) / (maxValue - minValue) * trackWidth);
            int upperPos = trackStart + (int) ((double) (upperValue - minValue) / (maxValue - minValue) * trackWidth);

            // 绘制选中范围
            g2d.setColor(UIThemeManager.ThemeColors.PRIMARY_BLUE);
            g2d.fillRoundRect(lowerPos, trackY, upperPos - lowerPos, TRACK_HEIGHT, TRACK_HEIGHT, TRACK_HEIGHT);

            // 绘制下限滑块
            g2d.setColor(UIThemeManager.ThemeColors.MINECRAFT_GREEN);
            g2d.fillOval(lowerPos - THUMB_SIZE / 2, trackY - (THUMB_SIZE - TRACK_HEIGHT) / 2, THUMB_SIZE, THUMB_SIZE);
            g2d.setColor(Color.WHITE);
            g2d.drawOval(lowerPos - THUMB_SIZE / 2, trackY - (THUMB_SIZE - TRACK_HEIGHT) / 2, THUMB_SIZE, THUMB_SIZE);

            // 绘制上限滑块
            g2d.setColor(UIThemeManager.ThemeColors.WARNING_ORANGE);
            g2d.fillOval(upperPos - THUMB_SIZE / 2, trackY - (THUMB_SIZE - TRACK_HEIGHT) / 2, THUMB_SIZE, THUMB_SIZE);
            g2d.setColor(Color.WHITE);
            g2d.drawOval(upperPos - THUMB_SIZE / 2, trackY - (THUMB_SIZE - TRACK_HEIGHT) / 2, THUMB_SIZE, THUMB_SIZE);

            // 绘制数值标签
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2d.getFontMetrics();

            // 下限标签
            String lowerText = String.valueOf(lowerValue);
            int lowerTextWidth = fm.stringWidth(lowerText);
            int lowerTextX = Math.max(0, Math.min(getWidth() - lowerTextWidth, lowerPos - lowerTextWidth / 2));
            g2d.drawString(lowerText, lowerTextX, trackY - 8);

            // 上限标签
            String upperText = String.valueOf(upperValue);
            int upperTextWidth = fm.stringWidth(upperText);
            int upperTextX = Math.max(0, Math.min(getWidth() - upperTextWidth, upperPos - upperTextWidth / 2));
            g2d.drawString(upperText, upperTextX, trackY - 8);

            g2d.dispose();
        }

        // Getter 和 Setter 方法
        public int getLowerValue() { return lowerValue; }
        public int getUpperValue() { return upperValue; }

        public void setLowerValue(int value) {
            int newValue = Math.max(minValue, Math.min(value, upperValue));
            if (newValue != lowerValue) {
                lowerValue = newValue;
                repaint();
                fireChangeEvent();
            }
        }

        public void setUpperValue(int value) {
            int newValue = Math.max(lowerValue, Math.min(value, maxValue));
            if (newValue != upperValue) {
                upperValue = newValue;
                repaint();
                fireChangeEvent();
            }
        }

        /** Apply a fitted import range once, retaining extended world heights. */
        public void setSelectedRange(int lower,int upper) {
            if(lower>upper) throw new IllegalArgumentException("Minimum Y must not exceed maximum Y");
            minValue=Math.min(minValue,lower);maxValue=Math.max(maxValue,upper);
            boolean changed=lowerValue!=lower||upperValue!=upper;
            lowerValue=lower;upperValue=upper;repaint();
            if(changed) fireChangeEvent();
        }

        public void setRange(int min, int max) {
            this.minValue = min;
            this.maxValue = max;
            this.lowerValue = Math.max(min, Math.min(lowerValue, max));
            this.upperValue = Math.max(min, Math.min(upperValue, max));
            repaint();
        }
    }

    /**
     * 竖向三滑块：世界 Y 向上增长。绿 = 下限（Bottom Y），橙 = 上限（Top Y），
     * 紫 = 兴趣带上沿（Highlight Y）：Top Y 与它之间若存在方块，画布会用 50% 紫棋盘标出来。
     * 拖动过程中只重绘，松手才发出 change 事件，由外部防抖决定何时整幅重渲。
     */
    public static class VerticalRangeSlider extends JPanel {
        private int minValue;
        private int maxValue;
        private int lowerValue;
        private int upperValue;
        private int highlightValue;
        private boolean draggingLower;
        private boolean draggingUpper;
        private boolean draggingHighlight;
        private boolean syncing;
        private boolean notifying;
        private final int THUMB_SIZE = 13;
        private final int TRACK_WIDTH = 6;
        private final java.util.List<ChangeListener> listeners = new java.util.ArrayList<>();
        private final java.util.List<ChangeListener> liveListeners = new java.util.ArrayList<>();

        public VerticalRangeSlider(int min, int max, int lower, int upper) {
            this.minValue = min;
            this.maxValue = Math.max(min + 1, max);
            this.lowerValue = Math.max(this.minValue, Math.min(Math.min(lower, upper), this.maxValue));
            this.upperValue = Math.max(this.lowerValue, Math.min(Math.max(lower, upper), this.maxValue));
            this.highlightValue = Math.min(this.maxValue, this.upperValue + 16);   // 默认贴着上界上方一点：既能看到紫 thumb，带里通常又是空的
            setPreferredSize(new Dimension(104, 420));
            setOpaque(false);
            setToolTipText("Purple = highlight band top, orange = top Y, green = bottom Y");

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleMousePressed(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    boolean moved = draggingLower || draggingUpper || draggingHighlight;
                    draggingLower = false;
                    draggingUpper = false;
                    draggingHighlight = false;
                    setCursor(Cursor.getDefaultCursor());
                    // 拖动中不通知外部：整幅重渲只在松手后触发一次
                    if (moved) fireChangeEvent();
                }
            });

            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    handleMouseDragged(e);
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    updateCursor(e);
                }
            });
        }

        public void addChangeListener(ChangeListener listener) {
            listeners.add(listener);
        }

        /** 拖动过程中的每次取值变化都会回调（只用于廉价的即时反馈，重活留给 change）。 */
        public void addLiveListener(ChangeListener listener) {
            liveListeners.add(listener);
        }

        private void fireChangeEvent() {
            if (syncing) return;
            syncing = true;
            try {
                ChangeEvent event = new ChangeEvent(this);
                for (ChangeListener listener : listeners) listener.stateChanged(event);
            } finally {
                syncing = false;
            }
        }

        private void fireLiveEvent() {
            if (notifying) return;
            notifying = true;
            try {
                ChangeEvent event = new ChangeEvent(this);
                for (ChangeListener listener : liveListeners) listener.stateChanged(event);
            } finally {
                notifying = false;
            }
        }

        // ── 几何 ──
        private double span() { return Math.max(1, (double) maxValue - minValue); }
        private int trackTop() { return THUMB_SIZE / 2; }
        private int trackHeight() { return Math.max(1, getHeight() - THUMB_SIZE); }
        private int trackX() { return Math.max(0, (getWidth() - TRACK_WIDTH) / 2); }

        private int valueToY(int value) {
            double ratio = (maxValue - (double) value) / span();
            return trackTop() + (int) Math.round(ratio * trackHeight());
        }

        private int yToValue(int y) {
            double ratio = Math.max(0, Math.min(1, (y - trackTop()) / (double) trackHeight()));
            return (int) Math.round(maxValue - ratio * span());
        }

        // ── 交互 ──
        private void handleMousePressed(MouseEvent e) {
            int y = e.getY();
            int lowerY = valueToY(lowerValue);
            int upperY = valueToY(upperValue);
            int highlightY = valueToY(highlightValue);
            int hit = THUMB_SIZE / 2 + 3;
            int dl = Math.abs(y - lowerY), du = Math.abs(y - upperY), dh = Math.abs(y - highlightY);
            if (dl <= hit && dl <= du && dl <= dh) {
                draggingLower = true;
            } else if (du <= hit && du <= dh) {
                draggingUpper = true;
            } else if (dh <= hit) {
                draggingHighlight = true;
            } else if (dl <= du && dl <= dh) {
                draggingLower = true;
                updateLower(y);
            } else if (du <= dh) {
                draggingUpper = true;
                updateUpper(y);
            } else {
                draggingHighlight = true;
                updateHighlight(y);
            }
        }

        private void handleMouseDragged(MouseEvent e) {
            if (draggingLower) updateLower(e.getY());
            else if (draggingUpper) updateUpper(e.getY());
            else if (draggingHighlight) updateHighlight(e.getY());
        }

        private void updateLower(int y) {
            int value = Math.max(minValue, Math.min(yToValue(y), upperValue));
            if (value != lowerValue) {
                lowerValue = value;
                repaint();
                fireLiveEvent();
            }
        }

        private void updateUpper(int y) {
            int value = Math.min(highlightValue, Math.max(yToValue(y), lowerValue));
            if (value != upperValue) {
                upperValue = value;
                repaint();
                fireLiveEvent();
            }
        }

        private void updateHighlight(int y) {
            int value = Math.min(maxValue, Math.max(yToValue(y), upperValue));
            if (value != highlightValue) {
                highlightValue = value;
                repaint();
                fireLiveEvent();
            }
        }

        private void updateCursor(MouseEvent e) {
            int y = e.getY();
            int hit = THUMB_SIZE / 2 + 3;
            boolean onThumb = Math.abs(y - valueToY(lowerValue)) <= hit
                || Math.abs(y - valueToY(upperValue)) <= hit
                || Math.abs(y - valueToY(highlightValue)) <= hit;
            setCursor(onThumb ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        }

        // ── 取值 ──
        public int getLowerValue() { return lowerValue; }
        public int getUpperValue() { return upperValue; }
        public int getHighlightValue() { return highlightValue; }

        public void setHighlightValue(int value) {
            int next = Math.max(upperValue, Math.min(value, maxValue));
            if (next != highlightValue) {
                highlightValue = next;
                repaint();
                fireChangeEvent();
            }
        }

        public void setLowerValue(int value) {
            int next = Math.max(minValue, Math.min(value, upperValue));
            if (next != lowerValue) {
                lowerValue = next;
                repaint();
                fireChangeEvent();
            }
        }

        public void setUpperValue(int value) {
            int next = Math.min(highlightValue, Math.max(value, lowerValue));
            if (next != upperValue) {
                upperValue = next;
                repaint();
                fireChangeEvent();
            }
        }

        /** 同时设置两端，只发一次 change（启动灌初值时用）。 */
        public void setValues(int lower, int upper) {
            setValues(lower, upper, upper);
        }

        public void setValues(int lower, int upper, int highlight) {
            int lo = Math.max(minValue, Math.min(Math.min(lower, upper), maxValue));
            int hi = Math.max(lo, Math.min(Math.max(lower, upper), maxValue));
            int hl = Math.max(hi, Math.min(Math.max(highlight, hi), maxValue));
            if (lo == lowerValue && hi == upperValue && hl == highlightValue) return;
            lowerValue = lo;
            upperValue = hi;
            highlightValue = hl;
            repaint();
            fireChangeEvent();
        }

        /** Apply selection/preset bounds atomically using the cloud height-axis control. */
        public void setSelectedRange(int lower,int upper) {
            if(lower>upper) throw new IllegalArgumentException("Minimum Y must not exceed maximum Y");
            minValue=Math.min(minValue,lower);maxValue=Math.max(maxValue,upper);
            boolean changed=lowerValue!=lower||upperValue!=upper;
            lowerValue=lower;upperValue=upper;highlightValue=Math.max(highlightValue,upper);repaint();
            if(changed) fireChangeEvent();
        }

        /** 扩大/移动坐标轴模型范围（不是取值）。 */
        public void setRange(int min, int max) {
            if (max <= min) return;
            minValue = min;
            maxValue = max;
            lowerValue = Math.max(min, Math.min(lowerValue, max));
            upperValue = Math.max(lowerValue, Math.min(upperValue, max));
            highlightValue = Math.max(upperValue, Math.min(highlightValue, max));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Container parent = getParent();
            g2d.setColor(parent != null ? parent.getBackground() : Color.WHITE);
            g2d.fillRect(0, 0, getWidth(), getHeight());

            int x = trackX();
            int top = trackTop();
            int height = trackHeight();
            int lowerY = valueToY(lowerValue);
            int upperY = valueToY(upperValue);
            int highlightY = valueToY(highlightValue);

            g2d.setColor(Color.LIGHT_GRAY);
            g2d.fillRoundRect(x, top, TRACK_WIDTH, height, TRACK_WIDTH, TRACK_WIDTH);

            // 选中区间（下限→上限）
            g2d.setColor(UIThemeManager.ThemeColors.PRIMARY_BLUE);
            g2d.fillRoundRect(x, Math.min(lowerY, upperY), TRACK_WIDTH,
                              Math.max(1, Math.abs(lowerY - upperY)), TRACK_WIDTH, TRACK_WIDTH);

            // 兴趣带（上限→高亮上沿）：地图上会用紫色棋盘标出这段里的方块
            if (highlightEnabled && highlightY < upperY) {
                g2d.setColor(HIGHLIGHT_COLOR);
                g2d.fillRoundRect(x, highlightY, TRACK_WIDTH,
                                  Math.max(1, upperY - highlightY), TRACK_WIDTH, TRACK_WIDTH);
            }

            // 每 64 一格刻度
            g2d.setColor(new Color(255, 255, 255, 140));
            for (int value = minValue; value <= maxValue; value += 64) {
                int tickY = valueToY(value);
                g2d.drawLine(x, tickY, x + TRACK_WIDTH, tickY);
            }

            drawThumb(g2d, x, lowerY, UIThemeManager.ThemeColors.MINECRAFT_GREEN);
            drawThumb(g2d, x, upperY, UIThemeManager.ThemeColors.WARNING_ORANGE);
            drawThumb(g2d, x, highlightY, highlightEnabled ? HIGHLIGHT_COLOR : Color.GRAY);

            g2d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2d.getFontMetrics();
            g2d.setColor(Color.DARK_GRAY);
            drawValue(g2d, fm, x, highlightY, highlightValue);
            drawValue(g2d, fm, x, upperY, upperValue);
            drawValue(g2d, fm, x, lowerY, lowerValue);

            g2d.dispose();
        }

        /** 兴趣带高亮色（与画布上的紫棋盘同一色系） */
        static final Color HIGHLIGHT_COLOR = new Color(0x80, 0x30, 0xC0);
        /** 关掉"兴趣带"开关时紫 thumb 画成灰的 */
        public volatile boolean highlightEnabled = true;

        private void drawThumb(Graphics2D g2d, int trackX, int y, Color color) {
            int px = trackX + TRACK_WIDTH / 2 - THUMB_SIZE / 2;
            int py = y - THUMB_SIZE / 2;
            g2d.setColor(color);
            g2d.fillOval(px, py, THUMB_SIZE, THUMB_SIZE);
            g2d.setColor(Color.WHITE);
            g2d.drawOval(px, py, THUMB_SIZE, THUMB_SIZE);
        }

        private void drawValue(Graphics2D g2d, FontMetrics fm, int trackX, int y, int value) {
            String text = String.valueOf(value);
            int tx = trackX - 6 - fm.stringWidth(text);
            if (tx < 2) tx = trackX + TRACK_WIDTH + 6;
            int ty = Math.max(fm.getAscent(),
                              Math.min(getHeight() - fm.getDescent(), y + fm.getAscent() / 2 - 1));
            g2d.drawString(text, tx, ty);
        }
    }

    /**
     * 创建带动画效果的按钮
     */
    public static JButton createAnimatedButton(String text, Icon icon) {
        JButton button = new JButton(text, icon);
        
        // 设置基本样式
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 12f));
        button.setIconTextGap(8);
        button.setBackground(UIThemeManager.ThemeColors.PRIMARY_BLUE);
        button.setForeground(Color.WHITE);
        
        // 添加动画效果
        Timer hoverTimer = new Timer(50, null);
        button.addMouseListener(new MouseAdapter() {
            private float alpha = 1.0f;
            
            @Override
            public void mouseEntered(MouseEvent e) {
                if (button.isEnabled()) {
                    hoverTimer.stop();
                    hoverTimer.addActionListener(evt -> {
                        alpha = Math.min(1.0f, alpha + 0.1f);
                        Color hoverColor = new Color(
                            (int) (UIThemeManager.ThemeColors.PRIMARY_BLUE.getRed() * alpha),
                            (int) (UIThemeManager.ThemeColors.PRIMARY_BLUE.getGreen() * alpha),
                            (int) (UIThemeManager.ThemeColors.PRIMARY_BLUE.getBlue() * alpha)
                        );
                        button.setBackground(hoverColor.brighter());
                        if (alpha >= 1.0f) {
                            hoverTimer.stop();
                        }
                    });
                    hoverTimer.start();
                }
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(UIThemeManager.ThemeColors.PRIMARY_BLUE);
                    hoverTimer.stop();
                    alpha = 1.0f;
                }
            }
        });
        
        return button;
    }
}
