package com.minecraft.selector.core;
import java.awt.image.BufferedImage;
import com.minecraft.selector.gui.SelectionMath.Bounds;
/** Scans intersections with cached maps only; no world I/O or UI dependencies. */
public final class SelectionYFit {
    public static final class Result {
        public int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE; public long count,missing,empty;
        public void add(int y) {if(y!=RenderedMap.NO_HEIGHT) {min=Math.min(min,y);max=Math.max(max,y);count++;}}
        public void merge(Result r) {missing+=r.missing;empty+=r.empty;if(r.count>0) {min=Math.min(min,r.min);max=Math.max(max,r.max);count+=r.count;}}
    }
    public static Result scan(BufferedImage image,int originX,int originZ,double pixelsPerBlock,Bounds b) {
        return scan(image,originX,originZ,pixelsPerBlock,b,true);
    }
    public static Result scan(BufferedImage image,int originX,int originZ,double pixelsPerBlock,Bounds b,boolean opaque) {
        Result r=new Result();if(image==null) return r;
        int x1=Math.max(0,pixelFloor(((long)b.minX-originX)*pixelsPerBlock));
        int z1=Math.max(0,pixelFloor(((long)b.minZ-originZ)*pixelsPerBlock));
        int x2=Math.min(image.getWidth()-1,pixelFloor(((long)b.maxX-originX)*pixelsPerBlock));
        int z2=Math.min(image.getHeight()-1,pixelFloor(((long)b.maxZ-originZ)*pixelsPerBlock));
        for(int z=z1;z<=z2;z++) {if(Thread.currentThread().isInterrupted()) return r;for(int x=x1;x<=x2;x++) {
            int y=image instanceof RenderedMap?((RenderedMap)image).getFitHeightAt(x,z,opaque):RenderedMap.heightAt(image,x,z);
            r.add(y);if(image instanceof RenderedMap&&!((RenderedMap)image).isLoadedAt(x,z)) r.missing++;else if(y==RenderedMap.NO_HEIGHT) r.empty++;
        }
        }
        return r;
    }
    private static int pixelFloor(double value) {return (int)Math.floor(value);}
}
