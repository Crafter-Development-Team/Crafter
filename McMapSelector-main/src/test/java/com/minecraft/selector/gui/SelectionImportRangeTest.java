package com.minecraft.selector.gui;
import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SelectionImportRangeTest {
    @Test void cloudVerticalAxisFitsExtendedYAtomicallyAndKeepsPurpleHandleOrdered() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            StyledComponents.VerticalRangeSlider slider=new StyledComponents.VerticalRangeSlider(-128,384,-64,320);
            AtomicInteger changes=new AtomicInteger();slider.addChangeListener(e->changes.incrementAndGet());
            slider.setSelectedRange(-200,655);
            assertEquals(-200,slider.getLowerValue());assertEquals(655,slider.getUpperValue());
            assertTrue(slider.getHighlightValue()>=slider.getUpperValue());assertEquals(1,changes.get());
            slider.setSelectedRange(-200,655);assertEquals(1,changes.get());
            assertThrows(IllegalArgumentException.class,()->slider.setSelectedRange(10,9));
            slider.setSelectedRange(100,200);assertEquals(2,changes.get());
            slider.setLowerValue(900);assertEquals(200,slider.getLowerValue());
            assertTrue(slider.getHighlightValue()>=slider.getUpperValue());
        });
    }
    @Test void autoFitAppliesExtendedYAtomicallyAndPreservesOrdinaryClamping() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            StyledComponents.DualRangeSlider slider=new StyledComponents.DualRangeSlider(-64,365,-64,365);
            AtomicInteger changes=new AtomicInteger();slider.addChangeListener(e->changes.incrementAndGet());
            slider.setSelectedRange(-128,655);assertEquals(-128,slider.getLowerValue());assertEquals(655,slider.getUpperValue());assertEquals(1,changes.get());
            slider.setSelectedRange(500,600);assertEquals(500,slider.getLowerValue());assertEquals(600,slider.getUpperValue());assertEquals(2,changes.get());
            slider.setSelectedRange(-120,-110);assertEquals(-120,slider.getLowerValue());assertEquals(-110,slider.getUpperValue());assertEquals(3,changes.get());
            slider.setLowerValue(900);assertEquals(-110,slider.getLowerValue()); // middle-click remains clamped
            assertThrows(IllegalArgumentException.class,()->slider.setSelectedRange(10,9));
            assertEquals(-110,slider.getLowerValue());assertEquals(-110,slider.getUpperValue());
        });
    }
}
