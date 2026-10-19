package com.minecraft.selector.gui;
import java.awt.*;
import java.util.function.Function;
public final class MapGrid {
    private MapGrid() {}
    public static void draw(Graphics2D g,int mode,SelectionMath.Bounds visible,double scale,Function<Point,Point> toScreen,int width,int height) {
        if(mode==0||visible==null) return;
        if(scale*16>=4) lines(g,visible,16,toScreen,width,height,new Color(128,128,128,90));
        if(mode==2) lines(g,visible,512,toScreen,width,height,new Color(255,200,60,180));
    }
    private static void lines(Graphics2D g,SelectionMath.Bounds b,int size,Function<Point,Point> convert,int w,int h,Color color) {
        g.setColor(color);g.setStroke(new BasicStroke(1));
        for(long x=Math.floorDiv((long)b.minX,size)*size;x<=b.maxX;x+=size) {Point p=convert.apply(new Point(SelectionMath.clamp(x),b.minZ));if(p!=null)g.drawLine(p.x,0,p.x,h);}
        for(long z=Math.floorDiv((long)b.minZ,size)*size;z<=b.maxZ;z+=size) {Point p=convert.apply(new Point(b.minX,SelectionMath.clamp(z)));if(p!=null)g.drawLine(0,p.y,w,p.y);}
    }
}
