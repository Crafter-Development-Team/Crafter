package com.minecraft.selector.gui;

import java.awt.Point;
import java.util.Objects;

/** Inclusive world-block bounds. All arithmetic is independent of Swing. */
public final class SelectionMath {
    private SelectionMath() {}
    public enum Snap { NONE(1), CHUNK(16), REGION(512); public final int size; Snap(int size) {this.size=size;} @Override public String toString() {return this==NONE?"None":this==CHUNK?"Chunk":"Region";} }
    public static final class Bounds {
        public final int minX,minZ,maxX,maxZ;
        public Bounds(int minX,int minZ,int maxX,int maxZ) {
            if(minX>maxX || minZ>maxZ) throw new IllegalArgumentException("Minimum must not exceed maximum");
            this.minX=minX;this.minZ=minZ;this.maxX=maxX;this.maxZ=maxZ;
        }
        public Point center() {return new Point((int)(((long)minX+maxX)/2),(int)(((long)minZ+maxZ)/2));}
        @Override public boolean equals(Object o) {if(!(o instanceof Bounds)) return false; Bounds b=(Bounds)o;return minX==b.minX&&minZ==b.minZ&&maxX==b.maxX&&maxZ==b.maxZ;}
        @Override public int hashCode() {return Objects.hash(minX,minZ,maxX,maxZ);}
        @Override public String toString() {return "("+minX+", "+minZ+") → ("+maxX+", "+maxZ+")";}
    }
    public static int clamp(long v) {return (int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,v));}
    public static Bounds normalized(int x1,int z1,int x2,int z2) {return new Bounds(Math.min(x1,x2),Math.min(z1,z2),Math.max(x1,x2),Math.max(z1,z2));}
    public static Bounds parse(String x1,String z1,String x2,String z2) {return new Bounds(Integer.parseInt(x1.trim()),Integer.parseInt(z1.trim()),Integer.parseInt(x2.trim()),Integer.parseInt(z2.trim()));}
    public static Bounds drag(Point a,Point b,boolean square) {
        long dx=(long)b.x-a.x,dz=(long)b.y-a.y;
        if(square) {
            long size=Math.max(Math.abs(dx),Math.abs(dz));
            // At integer limits shorten both axes together to preserve the square.
            size=Math.min(size,dx<0?(long)a.x-Integer.MIN_VALUE:(long)Integer.MAX_VALUE-a.x);
            size=Math.min(size,dz<0?(long)a.y-Integer.MIN_VALUE:(long)Integer.MAX_VALUE-a.y);
            b=new Point(clamp(a.x+(dx<0?-size:size)),clamp(a.y+(dz<0?-size:size)));
        }
        return normalized(a.x,a.y,b.x,b.y);
    }
    public static Bounds snap(Bounds b,Snap mode) {
        int n=mode.size;
        return new Bounds(clamp(Math.floorDiv((long)b.minX,n)*n),clamp(Math.floorDiv((long)b.minZ,n)*n),
            clamp(Math.floorDiv((long)b.maxX,n)*n+n-1),clamp(Math.floorDiv((long)b.maxZ,n)*n+n-1));
    }
    public static Bounds constrain(Bounds bounds,Snap snap,boolean square) {
        Bounds b=snap(bounds,snap);if(!square) return b;
        long side=Math.max((long)b.maxX-b.minX+1,(long)b.maxZ-b.minZ+1);
        long x=b.minX,z=b.minZ;
        if(x+side-1>Integer.MAX_VALUE) x=(long)Integer.MAX_VALUE-side+1;
        if(z+side-1>Integer.MAX_VALUE) z=(long)Integer.MAX_VALUE-side+1;
        return new Bounds(clamp(x),clamp(z),clamp(x+side-1),clamp(z+side-1));
    }
    /** Handle ids match both existing canvases: N,S,W,E,NW,NE,SW,SE. */
    public static Bounds resize(Bounds b,int h,Point p,boolean square) {
        boolean west=h==3||h==5||h==7,east=h==4||h==6||h==8;
        boolean north=h==1||h==5||h==6,south=h==2||h==7||h==8;
        int x1=west?p.x:b.minX,x2=east?p.x:b.maxX,z1=north?p.y:b.minZ,z2=south?p.y:b.maxZ;
        if(!square) return normalized(x1,z1,x2,z2);
        Point anchor=new Point(west?b.maxX:b.minX,north?b.maxZ:b.minZ);
        Point end=new Point(west?x1:x2,north?z1:z2);
        if(!west&&!east) end.x=clamp((long)anchor.x+Math.abs((long)end.y-anchor.y));
        if(!north&&!south) end.y=clamp((long)anchor.y+Math.abs((long)end.x-anchor.x));
        return drag(anchor,end,true);
    }
    /** direction: 0 left, 1 right, 2 up, 3 down. */
    public static Bounds nudge(Bounds b,int direction,boolean shrink,int step) {
        if(step<1) throw new IllegalArgumentException("Positive step required");
        int x1=b.minX,x2=b.maxX,z1=b.minZ,z2=b.maxZ;
        long d=shrink?-step:step;
        switch(direction) {
            case 0:x1=Math.min(x2,clamp((long)x1-d));break;
            case 1:x2=Math.max(x1,clamp((long)x2+d));break;
            case 2:z1=Math.min(z2,clamp((long)z1-d));break;
            case 3:z2=Math.max(z1,clamp((long)z2+d));break;
            default:throw new IllegalArgumentException("Unknown direction");
        }
        return new Bounds(x1,z1,x2,z2);
    }
}
