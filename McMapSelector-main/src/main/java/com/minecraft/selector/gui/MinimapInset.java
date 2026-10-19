package com.minecraft.selector.gui;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
/** Bounded corner overview. Unknown regions are footprints, never synthetic map colors. */
public final class MinimapInset {
    public static final class Tile {public final SelectionMath.Bounds bounds;public final BufferedImage image;public Tile(SelectionMath.Bounds b,BufferedImage image){bounds=b;this.image=image;}}
    private Rectangle rect=new Rectangle();private SelectionMath.Bounds extent;
    private double factor,ox,oz;
    public boolean contains(Point p){return p!=null&&rect.contains(p)&&extent!=null;}
    public Point worldAt(Point p){if(!contains(p))return null;return new Point(SelectionMath.clamp(Math.max(extent.minX,Math.min(extent.maxX,(long)Math.floor(extent.minX+(p.x-ox)/factor)))),SelectionMath.clamp(Math.max(extent.minZ,Math.min(extent.maxZ,(long)Math.floor(extent.minZ+(p.y-oz)/factor)))));}
    public void clear(){extent=null;rect=new Rectangle();}
    public void draw(Graphics2D graphics,Rectangle view,Collection<Tile> tiles,SelectionMath.Bounds viewport){clear();if(view.width<250||view.height<180||tiles.isEmpty())return;java.util.List<SelectionMath.Bounds> b=new ArrayList<>();for(Tile t:tiles)b.add(t.bounds);extent=SelectionShape.union(b);rect=new Rectangle(view.x+view.width-190,view.y+12,178,130);
        factor=Math.min((rect.width-12)/((double)extent.maxX-extent.minX+1),(rect.height-12)/((double)extent.maxZ-extent.minZ+1));ox=rect.x+(rect.width-((double)extent.maxX-extent.minX+1)*factor)/2;oz=rect.y+(rect.height-((double)extent.maxZ-extent.minZ+1)*factor)/2;
        Graphics2D g=(Graphics2D)graphics.create();try{g.setClip(rect);g.setColor(new Color(12,16,22,235));g.fillRect(rect.x,rect.y,rect.width,rect.height);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);for(Tile t:tiles){Rectangle r=screen(t.bounds);if(t.image!=null)g.drawImage(t.image,r.x,r.y,r.width,r.height,null);else{g.setColor(new Color(75,80,90));g.fillRect(r.x,r.y,r.width,r.height);}}if(viewport!=null){Rectangle r=screen(viewport);g.setColor(new Color(255,235,110));g.drawRect(r.x,r.y,r.width,r.height);}g.setColor(Color.WHITE);g.drawRect(rect.x,rect.y,rect.width-1,rect.height-1);g.drawString("Overview · click to center",rect.x+6,rect.y+14);}finally{g.dispose();}}
    private Rectangle screen(SelectionMath.Bounds b){int x=(int)Math.floor(ox+((double)b.minX-extent.minX)*factor),z=(int)Math.floor(oz+((double)b.minZ-extent.minZ)*factor);return new Rectangle(x,z,Math.max(1,(int)Math.ceil(((double)b.maxX-b.minX+1)*factor)),Math.max(1,(int)Math.ceil(((double)b.maxZ-b.minZ+1)*factor)));}
}
