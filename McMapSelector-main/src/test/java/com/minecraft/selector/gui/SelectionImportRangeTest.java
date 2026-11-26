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
    @Test void expandRangeUnlocksTallDatapackHeightsWithoutMovingSelectedValues() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            StyledComponents.VerticalRangeSlider slider=new StyledComponents.VerticalRangeSlider(-128,384,-64,319);
            AtomicInteger changes=new AtomicInteger();slider.addChangeListener(e->changes.incrementAndGet());
            slider.expandRange(-2032,1024);
            assertEquals(-2032,slider.getMinValue());assertEquals(1024,slider.getMaxValue());
            assertEquals(-64,slider.getLowerValue());assertEquals(319,slider.getUpperValue());
            assertEquals(0,changes.get());
            // Shrinking requests are ignored by expandRange.
            slider.expandRange(-64,320);
            assertEquals(-2032,slider.getMinValue());assertEquals(1024,slider.getMaxValue());
            // Painting a huge height span uses adaptive tick spacing and does not stall.
            slider.setSize(104,420);
            slider.expandRange(-100000,100000);
            java.awt.image.BufferedImage img=new java.awt.image.BufferedImage(104,420,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g=img.createGraphics();
            try { slider.paint(g); } finally { g.dispose(); }
        });
    }
    @Test void selectionBoundsPanelSupportsEditingYAlongsideXZ() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            java.util.concurrent.atomic.AtomicReference<SelectionMath.Bounds> outBounds=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<int[]> outY=new java.util.concurrent.atomic.AtomicReference<>();
            SelectionBoundsPanel panel=new SelectionBoundsPanel(new SelectionMath.Bounds(10,20,30,40),-64,950,(b,y)->{outBounds.set(b);outY.set(y);});
            for(java.awt.Component c:panel.getComponents()) {
                if(c instanceof javax.swing.JButton) ((javax.swing.JButton)c).doClick();
            }
            assertEquals(new SelectionMath.Bounds(10,20,30,40),outBounds.get());
            assertArrayEquals(new int[]{-64,950},outY.get());
        });
    }
}
