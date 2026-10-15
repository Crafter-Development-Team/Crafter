package com.minecraft.selector.gui;
/** One-level undo, including an empty selection. */
public final class SelectionHistory {
    private SelectionShape shape=new SelectionShape();
    public void remember(SelectionMath.Bounds bounds,SelectionShape value) {remember(bounds);shape=value.copy();}
    public SelectionShape shape() {return shape.copy();}
    private SelectionMath.Bounds previous; private boolean available;
    public void remember(SelectionMath.Bounds bounds) {previous=bounds;shape=new SelectionShape();available=true;}
    public void clear() {previous=null;available=false;}
    public boolean canUndo() {return available;}
    public SelectionMath.Bounds undo() {available=false;return previous;}
}
