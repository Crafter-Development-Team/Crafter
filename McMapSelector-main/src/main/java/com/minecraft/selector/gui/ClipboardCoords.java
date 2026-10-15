package com.minecraft.selector.gui;
public final class ClipboardCoords {
    private ClipboardCoords() {}
    public static String selection(SelectionMath.Bounds b,int minY,int maxY) {return b.minX+","+minY+","+b.minZ+" -> "+b.maxX+","+maxY+","+b.maxZ;}
    public static String hover(int x,int y,int z) {return x+" "+y+" "+z;}
}
