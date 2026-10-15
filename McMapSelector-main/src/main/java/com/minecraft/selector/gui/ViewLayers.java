package com.minecraft.selector.gui;
import com.minecraft.selector.core.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
/** Display-only cached overlays. Source pixels and height metadata stay immutable. */
public final class ViewLayers {
    public static synchronized void clearCache(){CACHE.clear();}
    private static final java.util.LinkedList<Entry> CACHE=new java.util.LinkedList<>();
    private static final class Entry {
        final java.lang.ref.WeakReference<BufferedImage> source;final int key;final BufferedImage image;
        Entry(BufferedImage source,int key,BufferedImage image) {this.source=new java.lang.ref.WeakReference<>(source);this.key=key;this.image=image;}
    }
    public static void background(Graphics2D g,int width,int height) {
        Rectangle clip=g.getClipBounds();if(clip==null) clip=new Rectangle(0,0,width,height);
        for(int y=Math.max(0,clip.y/16*16);y<Math.min(height,(long)clip.y+clip.height);y+=16) for(int x=Math.max(0,clip.x/16*16);x<Math.min(width,(long)clip.x+clip.width);x+=16) {g.setColor(((x/16+y/16)&1)==0?new Color(68,69,76):new Color(76,77,84));g.fillRect(x,y,16,16);}
    }
    public static int band(int y,int interval) {if(interval<1) throw new IllegalArgumentException("Contour interval must be positive");return Math.floorDiv(y,interval);}
    public static boolean contour(int a,int b,int interval) {return a!=RenderedMap.NO_HEIGHT&&b!=RenderedMap.NO_HEIGHT&&band(a,interval)!=band(b,interval);}
    public static synchronized BufferedImage overlay(BufferedImage source,boolean night,boolean contours,boolean gaps) {
        int key=(night?1:0)+(contours?2:0)+(gaps?4:0);
        java.util.Iterator<Entry> entries=CACHE.iterator();
        while(entries.hasNext()) {Entry entry=entries.next();BufferedImage cached=entry.source.get();
            if(cached==null||cached==source) {entries.remove();if(cached==source&&entry.key==key) {CACHE.addFirst(entry);return entry.image;}}
        }
        BufferedImage image=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int z=0;z<image.getHeight();z++) for(int x=0;x<image.getWidth();x++) {
            int y=RenderedMap.heightAt(source,x,z),color=0;
            if(night&&y!=RenderedMap.NO_HEIGHT) color=0x99040818;
            if(gaps&&y==RenderedMap.NO_HEIGHT) {
                boolean loaded=source instanceof RenderedMap&&((RenderedMap)source).isLoadedAt(x,z);
                color=loaded?0x403d5660:(((x>>3)+(z>>3))%2==0?0xb06a6974:0x907e7d87);
            }
            if(contours&&(x>0&&contour(y,RenderedMap.heightAt(source,x-1,z),16)||z>0&&contour(y,RenderedMap.heightAt(source,x,z-1),16))) color=0xc026211b;
            image.setRGB(x,z,color);
        }
        CACHE.addFirst(new Entry(source,key,image));long pixels=0;
        entries=CACHE.iterator();int count=0;
        while(entries.hasNext()) {Entry entry=entries.next();pixels+=(long)entry.image.getWidth()*entry.image.getHeight();count++;if(count>12||count>1&&pixels>16L*1024*1024) entries.remove();}
        return image;
    }
    public static void count(Map<String,Long> counts,BufferedImage image,int x1,int z1,int x2,int z2) {
        for(int z=Math.max(0,z1);z<=Math.min(image.getHeight()-1,z2);z++) for(int x=Math.max(0,x1);x<=Math.min(image.getWidth()-1,x2);x++) {
            String state=RenderedMap.blockAt(image,x,z);if(state!=null) {int bracket=state.indexOf('[');String id=bracket<0?state:state.substring(0,bracket);counts.merge(id,1L,Long::sum);}
        }
    }
}
