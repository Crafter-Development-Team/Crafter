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
}
