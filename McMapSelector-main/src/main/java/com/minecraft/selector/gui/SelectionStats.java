package com.minecraft.selector.gui;
import java.math.BigInteger;
public final class SelectionStats {
    public final long sizeX,sizeZ,sizeY; public final BigInteger columns,cells;
    public SelectionStats(SelectionMath.Bounds b,int minY,int maxY) {
        if(minY>maxY) throw new IllegalArgumentException("Invalid Y range");
        sizeX=(long)b.maxX-b.minX+1;sizeZ=(long)b.maxZ-b.minZ+1;sizeY=(long)maxY-minY+1;
        columns=BigInteger.valueOf(sizeX).multiply(BigInteger.valueOf(sizeZ));cells=columns.multiply(BigInteger.valueOf(sizeY));
    }
    public static long warningThreshold() {return Math.max(1,Long.getLong("selector.warningColumns",65536L));}
    public boolean isLarge() {return columns.compareTo(BigInteger.valueOf(warningThreshold()))>0;}
    @Override public String toString() {return sizeX+"×"+sizeZ+" blocks, H="+sizeY+", "+columns+" columns, ~"+cells+" block-cells"+(isLarge()?" — Large Blender import; may be slow":"");}
}
