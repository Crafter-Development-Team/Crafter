package com.minecraft.selector.gui;
import com.minecraft.selector.core.SelectionYFit;
import java.awt.Point;
public interface SelectionCanvas {
    SelectionShape getSelectionShape();
    void setSelectionShape(SelectionShape shape,boolean remember);
    SelectionMath.Bounds getSelectionBounds();
    void setSelectionBounds(SelectionMath.Bounds b);
    void restoreSelectionBounds(SelectionMath.Bounds b);
    void undoSelection();
    void setSquareGesture(boolean square);
    void nudgeSelection(int direction,boolean shrink,int step);
    void selectVisible();
    void setSnapMode(SelectionMath.Snap snap);
    void setGridMode(int mode);
    SelectionYFit.Result fitSelectionY(SelectionMath.Bounds b);
    SelectionYFit.Result fitSelectionY(SelectionMath.Bounds b,boolean opaque);
    void setOverlays(ViewOverlays overlays);
    java.util.Map<String,Long> visibleBlockCounts();
    void zoomExtents(boolean maximum);
    int[] getHoverCoordinates();
    boolean jumpToWorldCoordinate(Point point);
    void confirmCurrentSelection();
    void clearSelection();
}
