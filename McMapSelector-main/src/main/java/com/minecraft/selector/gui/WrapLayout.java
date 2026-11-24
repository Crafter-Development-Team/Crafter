package com.minecraft.selector.gui;

import javax.swing.*;
import java.awt.*;

/**
 * Width-aware {@link FlowLayout} for top bars and collapsible strips.
 *
 * <p>Standard {@code FlowLayout.preferredLayoutSize} always returns single-row height regardless of
 * container width, so wrapped controls get clipped or overlap neighbouring panels when placed in
 * {@code BorderLayout.NORTH} or {@code BoxLayout.Y_AXIS}. This layout computes wrapped row height
 * against the actual available width of the container (or its nearest sized ancestor).
 */
public class WrapLayout extends FlowLayout {

    public WrapLayout() {
        super();
    }

    public WrapLayout(int align) {
        super(align);
    }

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return computeLayoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = computeLayoutSize(target, false);
        minimum.width -= (getHgap() + 1);
        return minimum;
    }

    private Dimension computeLayoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = resolveAvailableWidth(target);
            if (targetWidth <= 0) {
                targetWidth = Integer.MAX_VALUE;
            }

            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
            int maxWidth = Math.max(1, targetWidth - horizontalInsetsAndGap);

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            int memberCount = target.getComponentCount();
            for (int i = 0; i < memberCount; i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) continue;

                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                if (rowWidth > 0 && rowWidth + hgap + d.width > maxWidth) {
                    addRow(dim, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) {
                    rowWidth += hgap;
                }
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(dim, rowWidth, rowHeight);

            dim.width += horizontalInsetsAndGap;
            dim.height += insets.top + insets.bottom + vgap * 2;

            Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (scrollPane != null && target.isValid()) {
                dim.width -= (hgap + 1);
            }
            return dim;
        }
    }

    private static int resolveAvailableWidth(Container target) {
        int subtractedInsets = 0;
        int candidate = target.getWidth();
        Container current = target.getParent();
        while (current != null && !(current instanceof JViewport) && !(current instanceof Window)) {
            Insets in = current.getInsets();
            subtractedInsets += in.left + in.right;
            if (current.getWidth() > 0) {
                // During resize, outer BorderLayout containers (contentPane / mapPanel) already have
                // their new width when querying NORTH's preferredSize, while inner wrappers
                // (CollapsiblePanel / contentPanel) still hold stale pre-resize widths.
                candidate = Math.max(1, current.getWidth() - subtractedInsets);
                if (current.getLayout() instanceof BorderLayout && !(current instanceof CollapsiblePanel)) {
                    break;
                }
            }
            current = current.getParent();
        }
        return candidate;
    }

    private void addRow(Dimension dim, int rowWidth, int rowHeight) {
        dim.width = Math.max(dim.width, rowWidth);
        if (dim.height > 0) {
            dim.height += getVgap();
        }
        dim.height += rowHeight;
    }
}
