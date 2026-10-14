package com.minecraft.selector.gui;

import javax.swing.*;
import java.awt.*;

/** Loading feedback overlays the map so showing/hiding it never changes the world viewport. */
public final class LoadingOverlayPane extends JLayeredPane {
    private final JComponent map;
    private final LoadingPhasePanel loading;
    public LoadingOverlayPane(JComponent map,LoadingPhasePanel loading) {
        this.map=map;this.loading=loading;
        add(map,JLayeredPane.DEFAULT_LAYER);add(loading,JLayeredPane.PALETTE_LAYER);
    }
    @Override public void doLayout() {
        map.setBounds(0,0,getWidth(),getHeight());
        int height=loading.isVisible()?Math.min(loading.getPreferredSize().height,Math.max(0,getHeight()-16)):0;
        loading.setBounds(8,Math.max(0,getHeight()-height-8),Math.max(0,getWidth()-16),height);
    }
    @Override public Dimension getPreferredSize() {return map.getPreferredSize();}
    @Override public Dimension getMinimumSize() {return map.getMinimumSize();}
}
