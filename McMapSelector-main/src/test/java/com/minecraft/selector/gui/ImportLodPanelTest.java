package com.minecraft.selector.gui;

import com.minecraft.selector.core.ImportLod;
import javax.swing.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImportLodPanelTest {
    @Test void seededControlsRespectActiveLevelsAndManualCenterAndUpdateLive() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            AtomicReference<ImportLod> changed=new AtomicReference<>();
            ImportLodPanel panel=new ImportLodPanel(changed::set);
            assertEquals(ImportLod.DEFAULT.json(),changed.get().json());
            for(JSpinner spinner:panel.distances) assertFalse(spinner.isEnabled());
            panel.setSettings(new ImportLod(2,false,-17,31,1,2,3,4));
            assertTrue(panel.distances[0].isEnabled());assertTrue(panel.distances[1].isEnabled());
            assertFalse(panel.distances[2].isEnabled());assertFalse(panel.distances[3].isEnabled());
            assertTrue(panel.centerX.isEnabled());assertTrue(panel.centerZ.isEnabled());
            panel.distances[1].setValue(9);panel.centerX.setValue(-33);
            assertEquals(9,changed.get().distance(1));assertEquals(-33,changed.get().centerX);
            panel.autoCenter.doClick();
            assertTrue(changed.get().autoCenter);assertFalse(panel.centerX.isEnabled());
            assertFalse(panel.centerZ.isEnabled());
            panel.maxLevel.setSelectedIndex(4);
            assertTrue(panel.distances[3].isEnabled());assertEquals(3,changed.get().distance(2));
            panel.maxLevel.setSelectedIndex(0);
            assertEquals(0,changed.get().maxLevel);assertFalse(panel.autoCenter.isEnabled());
        });
    }

    @Test void typedIntegersCommitWithoutSpinnerArrows() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            AtomicReference<ImportLod> changed=new AtomicReference<>();
            ImportLodPanel panel=new ImportLodPanel(changed::set);
            panel.setSettings(new ImportLod(4,false,0,0,8,8,8,8));
            JFormattedTextField field=((JSpinner.DefaultEditor)panel.distances[0].getEditor()).getTextField();
            field.setText("12");
            assertEquals(12,changed.get().distance(0));
            field=((JSpinner.DefaultEditor)panel.centerZ.getEditor()).getTextField();field.setText("-17");
            assertEquals(-17,changed.get().centerZ);
        });
    }

    /** 画布拖动回写：控件同步、退出自动居中，且**不得回调 listener**（否则拖动会把事件打回 GUI 成环）。 */
    @Test void draggedCenterSyncsControlsWithoutCallingBack() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
            ImportLodPanel panel=new ImportLodPanel(v->calls.incrementAndGet());
            panel.setSettings(new ImportLod(4,true,0,0,8,8,8,8));
            assertTrue(panel.autoCenter.isSelected());
            calls.set(0);
            panel.applyDraggedCenter(-32,48);
            assertEquals(0,calls.get(),"dragging must not re-enter the listener");
            assertEquals(-32,panel.centerX.getValue());
            assertEquals(48,panel.centerZ.getValue());
            assertFalse(panel.autoCenter.isSelected(),"dragging must leave auto center");
            assertTrue(panel.centerX.isEnabled());assertTrue(panel.centerZ.isEnabled());
            assertEquals("-32, 48",panel.effectiveCenter.getText(),"preview center must follow");
            // 拖动后再手动改 spinner 仍要能正常回调
            panel.centerX.setValue(16);
            assertEquals(1,calls.get());
        });
    }
}
