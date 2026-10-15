package com.minecraft.selector.gui;
import java.awt.Point;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SelectionMathTest {
    @Test void squareUsesInclusiveOddSizesAndBothDragDirections() {
        SelectionMath.Bounds b=SelectionMath.drag(new Point(-4,2),new Point(-6,6),true);
        assertEquals(new SelectionMath.Bounds(-8,2,-4,6),b);
        assertEquals(new SelectionMath.Bounds(-6,2,-4,6),SelectionMath.drag(new Point(-4,2),new Point(-6,6),false));
        assertEquals(new SelectionMath.Bounds(3,3,3,3),SelectionMath.drag(new Point(3,3),new Point(3,3),true));
    }
    @Test void chunkAndRegionSnapUseFloorAtNegativeBoundaries() {
        assertEquals(new SelectionMath.Bounds(-32,-16,-1,15),SelectionMath.snap(new SelectionMath.Bounds(-17,-1,-16,0),SelectionMath.Snap.CHUNK));
        assertEquals(new SelectionMath.Bounds(-512,-1024,-1,-513),SelectionMath.snap(new SelectionMath.Bounds(-512,-513,-1,-513),SelectionMath.Snap.REGION));
        SelectionMath.Bounds exact=new SelectionMath.Bounds(0,0,15,15);
        assertEquals(exact,SelectionMath.snap(exact,SelectionMath.Snap.CHUNK));
        assertEquals(exact,SelectionMath.snap(exact,SelectionMath.Snap.NONE));
    }
    @Test void squareAndGridConstraintsComposeAtOffsetBoundaries() {
        SelectionMath.Bounds b=SelectionMath.constrain(new SelectionMath.Bounds(0,15,1,16),SelectionMath.Snap.CHUNK,true);
        assertEquals(new SelectionMath.Bounds(0,0,31,31),b);
        b=SelectionMath.constrain(new SelectionMath.Bounds(-511,-1,-510,0),SelectionMath.Snap.REGION,true);
        assertEquals(0,Math.floorMod(b.minX,512));assertEquals(511,Math.floorMod(b.maxX,512));
        assertEquals((long)b.maxX-b.minX,(long)b.maxZ-b.minZ);
    }
    @Test void resizeKeepsOppositeCornerAndReleaseRestoresFreeAspect() {
        SelectionMath.Bounds b=new SelectionMath.Bounds(0,0,10,10);
        assertEquals(new SelectionMath.Bounds(-3,-3,10,10),SelectionMath.resize(b,5,new Point(-3,1),true));
        assertEquals(new SelectionMath.Bounds(-3,1,10,10),SelectionMath.resize(b,5,new Point(-3,1),false));
        assertEquals(new SelectionMath.Bounds(0,0,16,16),SelectionMath.resize(b,4,new Point(16,9),true));
        assertEquals(new SelectionMath.Bounds(10,10,20,30),SelectionMath.resize(b,5,new Point(20,30),false));
    }
    @Test void nudgeExpandsShrinksAndNeverInvertsOrOverflows() {
        SelectionMath.Bounds b=new SelectionMath.Bounds(-4,-8,4,8);
        assertEquals(new SelectionMath.Bounds(-20,-8,4,8),SelectionMath.nudge(b,0,false,16));
        assertEquals(new SelectionMath.Bounds(-4,-8,-4,8),SelectionMath.nudge(b,1,true,16));
        assertEquals(new SelectionMath.Bounds(-4,-9,4,8),SelectionMath.nudge(b,2,false,1));
        assertEquals(new SelectionMath.Bounds(-4,-8,4,7),SelectionMath.nudge(b,3,true,1));
        SelectionMath.Bounds extreme=new SelectionMath.Bounds(Integer.MIN_VALUE,0,Integer.MAX_VALUE,0);
        assertEquals(extreme,SelectionMath.nudge(extreme,0,false,16));
        assertEquals(extreme,SelectionMath.nudge(extreme,1,false,16));
        SelectionMath.Bounds square=SelectionMath.drag(new Point(Integer.MAX_VALUE-2,0),new Point(Integer.MAX_VALUE,100),true);
        assertEquals(2L,(long)square.maxX-square.minX);assertEquals(2L,(long)square.maxZ-square.minZ);
    }
    @Test void typedBoundsRejectReversedAndNonIntegers() {
        assertEquals(new SelectionMath.Bounds(-10,2,3,8),SelectionMath.parse(" -10 ","2","3","8"));
        assertThrows(IllegalArgumentException.class,()->SelectionMath.parse("3","2","-10","8"));
        assertThrows(IllegalArgumentException.class,()->SelectionMath.parse("hi","2","3","8"));
        assertThrows(NumberFormatException.class,()->GoToDialog.parse("1.2","3"));
        assertEquals(new Point(-3,4),GoToDialog.parse(" -3 ","4"));
    }
    @Test void historyIncludesEmptySelectionAndSecondUndoIsNoOp() {
        SelectionHistory h=new SelectionHistory();assertFalse(h.canUndo());
        SelectionMath.Bounds b=new SelectionMath.Bounds(1,2,3,4);h.remember(b);assertEquals(b,h.undo());assertFalse(h.canUndo());
        h.remember(null);assertTrue(h.canUndo());assertNull(h.undo());assertFalse(h.canUndo());
    }
    @Test void statsAndClipboardUseInclusiveRangesAndUnlimitedCounts() {
        SelectionMath.Bounds b=new SelectionMath.Bounds(-1,-2,1,2);SelectionStats s=new SelectionStats(b,-64,63);
        assertEquals(3,s.sizeX);assertEquals(5,s.sizeZ);assertEquals(128,s.sizeY);
        assertEquals(BigInteger.valueOf(15),s.columns);assertEquals(BigInteger.valueOf(1920),s.cells);
        assertEquals("-1,-64,-2 -> 1,63,2",ClipboardCoords.selection(b,-64,63));assertEquals("-1 20 -2",ClipboardCoords.hover(-1,20,-2));
        SelectionStats huge=new SelectionStats(new SelectionMath.Bounds(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE),Integer.MIN_VALUE,Integer.MAX_VALUE);
        assertEquals(BigInteger.ONE.shiftLeft(64),huge.columns);assertEquals(BigInteger.ONE.shiftLeft(96),huge.cells);assertTrue(huge.isLarge());
        String old=System.getProperty("selector.warningColumns");
        try {System.setProperty("selector.warningColumns","10");assertTrue(s.isLarge());System.setProperty("selector.warningColumns","15");assertFalse(s.isLarge());}
        finally {if(old==null)System.clearProperty("selector.warningColumns");else System.setProperty("selector.warningColumns",old);}
    }
}
