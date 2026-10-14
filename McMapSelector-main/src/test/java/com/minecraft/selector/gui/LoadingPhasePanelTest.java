package com.minecraft.selector.gui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import static org.junit.jupiter.api.Assertions.*;

class LoadingPhasePanelTest {
    @Test void preparationMapAndIdleHaveSeparateComponentsAndNoReservedLogHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LoadingPhasePanel panel=new LoadingPhasePanel();
            assertEquals(LoadingPhasePanel.Phase.IDLE,panel.getPhase());assertFalse(panel.isVisible());
            assertEquals(0,panel.getPreferredSize().height);
            panel.beginPreparation(1,"Discovering mods");panel.appendLog("Extracting models");
            assertTrue(panel.isVisible());assertTrue(panel.getResourceProgress().getParent().isVisible());
            assertFalse(panel.getMapProgress().isVisible());assertTrue(panel.getResourceProgress().isIndeterminate());
            int preparationHeight=panel.getPreferredSize().height;
            panel.preparationProgress(1,2,4,"2/4");
            assertEquals(50,panel.getResourceProgress().getValue());assertFalse(panel.getResourceProgress().isIndeterminate());
            panel.beginMap(1,"Loading visible regions");
            assertNotSame(panel.getResourceProgress(),panel.getMapProgress());
            assertFalse(panel.getResourceProgress().getParent().isVisible());assertTrue(panel.getMapProgress().isVisible());
            assertTrue(panel.getPreferredSize().height<preparationHeight);
            panel.appendLog("Map render logs must not append here");
            assertEquals("Extracting models\n",panel.getLog().getText());
            panel.preparationProgress(1,4,4,"Late extraction progress");
            assertEquals(50,panel.getResourceProgress().getValue());
            panel.mapProgress(1,1,2,"1/2 regions");assertEquals(50,panel.getMapProgress().getValue());
            panel.finish(1);assertFalse(panel.isVisible());assertEquals(0,panel.getPreferredSize().height);
            assertFalse(panel.getMapProgress().isIndeterminate());assertFalse(panel.getResourceProgress().isIndeterminate());
        });
    }
    @Test void overlayVisibilityNeverResizesTheMapViewport() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel map=new JPanel();LoadingPhasePanel loading=new LoadingPhasePanel();
            LoadingOverlayPane overlay=new LoadingOverlayPane(map,loading);overlay.setSize(800,600);
            overlay.doLayout();assertEquals(new java.awt.Dimension(800,600),map.getSize());
            loading.beginPreparation(1,"Preparing");overlay.doLayout();
            int preparationHeight=loading.getHeight();assertTrue(preparationHeight>0);
            assertEquals(new java.awt.Dimension(800,600),map.getSize());
            loading.beginMap(1,"Loading");overlay.doLayout();assertTrue(loading.getHeight()<preparationHeight);
            assertEquals(new java.awt.Dimension(800,600),map.getSize());
            loading.finish(1);overlay.doLayout();assertEquals(0,loading.getHeight());
            assertEquals(new java.awt.Dimension(800,600),map.getSize());
        });
    }
    @Test void staleCallbacksCannotReopenOrFinishNewWorldAndFailuresCanClosePreparation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LoadingPhasePanel panel=new LoadingPhasePanel();
            panel.beginPreparation(10,"World A");panel.beginMap(10,"Map A");
            panel.beginPreparation(11,"World B");panel.appendLog("B");
            panel.beginMap(10,"Late A");panel.finish(10);panel.preparationProgress(10,1,1,"A");
            assertEquals(LoadingPhasePanel.Phase.PREPARING,panel.getPhase());assertTrue(panel.isVisible());
            assertEquals("B\n",panel.getLog().getText());
            panel.finish(11);assertFalse(panel.isVisible());
            panel.beginPreparation(12,"World C");assertEquals("",panel.getLog().getText());
            panel.beginMap(12,"No regions");panel.finish(12);assertFalse(panel.isVisible());
        });
    }
}
