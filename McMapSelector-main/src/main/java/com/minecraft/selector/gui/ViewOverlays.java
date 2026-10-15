package com.minecraft.selector.gui;
import com.minecraft.selector.core.WorldAnchors;
import com.minecraft.selector.core.ImportLod;
import java.awt.*;
import java.util.function.Function;
/** Shared map decoration settings and painter, independent of tile loading. */
public final class ViewOverlays {
    public ImportLod importLod=ImportLod.DEFAULT;
    public boolean importLodVisible=true;
    public boolean contours,night,markers=true,scaleBar,gaps,posMode;
    public boolean minimap,hud,ore,addBoxMode;
    public String selectionMode="Box";
    /** Importers currently consume one inclusive AABB; keep special tools dormant. */
    public void resetSelectionTool() {selectionMode="Box";addBoxMode=false;}
    public final com.minecraft.selector.core.WaypointStore waypoints=new com.minecraft.selector.core.WaypointStore();
    public java.util.Map<Point,Long> oreCounts=java.util.Collections.emptyMap();
    public String hudText="";
    public void drawOre(Graphics2D graphics,Function<Point,Point> screen,double scale) {
        if(!ore||oreCounts.isEmpty())return;long max=java.util.Collections.max(oreCounts.values());Graphics2D g=(Graphics2D)graphics.create();try{for(java.util.Map.Entry<Point,Long> e:oreCounts.entrySet()){SelectionMath.Bounds b=SelectionShape.chunkBounds(e.getKey());Point a=screen.apply(new Point(b.minX,b.minZ)),c=screen.apply(new Point(b.maxX,b.maxZ));int alpha=(int)(40+180*Math.log1p(e.getValue())/Math.log1p(max));g.setColor(new Color(255,85,20,alpha));g.fillRect(a.x,a.y,Math.max(1,c.x-a.x+(int)Math.ceil(scale)),Math.max(1,c.y-a.y+(int)Math.ceil(scale)));}}finally{g.dispose();}
    }
    public int minY=-64,maxY=320;
    public String dimension="minecraft:overworld";
    public WorldAnchors anchors=WorldAnchors.empty();
    public static double scaleDistance(double pixelsPerBlock) {
        if(!Double.isFinite(pixelsPerBlock)||pixelsPerBlock<=0) throw new IllegalArgumentException("Invalid scale");
        double target=120/pixelsPerBlock,power=Math.pow(10,Math.floor(Math.log10(target))),n=target/power;
        return (n>=5?5:n>=2?2:1)*power;
    }
    public void drawInView(Graphics2D graphics,Rectangle view,double pixelsPerBlock,Function<Point,Point> screen,SelectionMath.Bounds selection,Point pending,Point corner1,Point corner2) {
        Graphics2D g=(Graphics2D)graphics.create();
        try {g.translate(view.x,view.y);draw(g,view.width,view.height,pixelsPerBlock,p->{Point s=screen.apply(p);return new Point(s.x-view.x,s.y-view.y);},selection,pending,corner1,corner2);}finally {g.dispose();}
    }
    public void draw(Graphics2D g,int width,int height,double pixelsPerBlock,Function<Point,Point> screen,SelectionMath.Bounds selection,Point pos1,Point corner1,Point corner2) {
        if(importLodVisible) drawImportLod(g,width,height,pixelsPerBlock,screen,selection);
        if(markers) {marker(g,anchors.spawn,"Spawn",new Color(255,211,60),screen);marker(g,anchors.player,"Player",new Color(76,209,255),screen);}
        if(markers)for(com.minecraft.selector.core.WaypointStore.Marker m:waypoints.list())if(dimension.equals(m.dimension)){Point p=screen.apply(new Point(m.x,m.z));corner(g,p,new Color(220,120,255),m.label);}
        if(hud&&width>250&&height>140)badge(g,hudText,8,height-12);
        if(selection!=null) {
            Point a=screen.apply(new Point(selection.minX,selection.minZ)),b=screen.apply(new Point(selection.maxX,selection.maxZ));
            int x=Math.max(4,Math.min(width-150,a.x+5)),y=Math.max(20,Math.min(height-65,a.y-6));
            String label="Import Y: "+minY+" … "+maxY;badge(g,label,x,y);
            g.setColor(new Color(255,220,85));for(int i=0;i<4;i++) {int tx=a.x+(int)(((long)b.x-a.x)*i/3);g.drawLine(tx,a.y,tx,a.y-7);}
            if(posMode) {corner(g,corner1==null?a:screen.apply(corner1),new Color(255,100,120),"1");corner(g,corner2==null?b:screen.apply(corner2),new Color(100,170,255),"2");}
        }
        if(posMode&&pos1!=null) corner(g,screen.apply(pos1),new Color(255,100,120),"Pos1");
        if(scaleBar&&width>150&&height>90) {
            double distance=scaleDistance(pixelsPerBlock);int length=(int)Math.round(distance*pixelsPerBlock),x=18,y=height-45;
            g.setColor(new Color(0,0,0,180));g.fillRoundRect(x-8,y-24,length+30,39,6,6);g.setColor(Color.WHITE);g.setStroke(new BasicStroke(2));
            g.drawLine(x,y,x+length,y);g.drawLine(x,y-5,x,y+5);g.drawLine(x+length,y-5,x+length,y+5);
            g.drawString(String.format(java.util.Locale.ROOT,distance<1?"%.2f blocks":"%.0f blocks",distance),x,y-8);
        }
    }
    private static final Color[] LOD_COLORS={new Color(92,230,130),new Color(85,190,255),
        new Color(255,210,70),new Color(255,145,70),new Color(215,135,255)};
    private void drawImportLod(Graphics2D graphics,int width,int height,double scale,Function<Point,Point> screen,SelectionMath.Bounds selection) {
        if(importLod.maxLevel==0) return;
        ImportLod lod=importLod.resolved(selection,anchors,dimension);
        // Manual block coordinates are floored to chunks by Blender before WI receives them.
        Point center=screen.apply(new Point(Math.floorDiv(lod.centerX,16)*16,Math.floorDiv(lod.centerZ,16)*16));
        Graphics2D g=(Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(2f));
            for(int level=0;level<lod.maxLevel;level++) {
                double radius=lod.radiusChunks(level)*16.0*scale;
                g.setColor(LOD_COLORS[level]);
                g.draw(new java.awt.geom.Ellipse2D.Double(center.x-radius,center.y-radius,2*radius,2*radius));
            }
            g.setColor(Color.WHITE);g.drawLine(center.x-6,center.y,center.x+6,center.y);g.drawLine(center.x,center.y-6,center.x,center.y+6);
            if(width>240&&height>180) {
                String title="Import LOD · center chunk "+Math.floorDiv(lod.centerX,16)+", "+Math.floorDiv(lod.centerZ,16);
                int legendWidth=g.getFontMetrics().stringWidth(title)+12;
                for(int level=0;level<=lod.maxLevel;level++) {
                    String range=level==lod.maxLevel?"> "+lod.radiusChunks(level-1):"≤ "+lod.radiusChunks(level);
                    legendWidth=Math.max(legendWidth,g.getFontMetrics().stringWidth("LOD"+level+" "+range+" chunks")+28);
                }
                // DynamicMapCanvas already paints tile status in the upper left.
                int legendX=Math.max(12,width-legendWidth-12);
                badge(g,title,legendX,22);
                for(int level=0;level<=lod.maxLevel;level++) {
                    String range=level==lod.maxLevel?"> "+lod.radiusChunks(level-1):"≤ "+lod.radiusChunks(level);
                    int y=44+level*22;
                    badge(g,"LOD"+level+" "+range+" chunks",legendX+14,y);
                    g.setColor(LOD_COLORS[level]);g.fillRect(legendX,y-10,9,9);
                }
            }
        } finally {g.dispose();}
    }
    private static void badge(Graphics2D g,String text,int x,int y) {g.setColor(new Color(0,0,0,190));g.fillRoundRect(x-3,y-15,g.getFontMetrics().stringWidth(text)+9,21,5,5);g.setColor(Color.WHITE);g.drawString(text,x,y);}
    private static void corner(Graphics2D g,Point p,Color c,String name) {g.setColor(c);g.fillRect(p.x-4,p.y-4,9,9);g.drawString(name,p.x+7,p.y-7);}
    private void marker(Graphics2D g,WorldAnchors.Anchor anchor,String name,Color color,Function<Point,Point> screen) {
        if(anchor==null||!dimension.equals(anchor.dimension)) return;
        Point p=screen.apply(anchor.xz);g.setColor(Color.BLACK);g.fillOval(p.x-7,p.y-7,15,15);g.setColor(color);g.fillOval(p.x-5,p.y-5,11,11);g.drawString(name,p.x+9,p.y-7);
    }
    public String tooltip(Point cursor,Function<Point,Point> screen) {
        if(!markers) return "";StringBuilder out=new StringBuilder();
        WorldAnchors.Anchor[] a={anchors.spawn,anchors.player};String[] names={"Spawn","Player"};
        for(int i=0;i<a.length;i++) if(a[i]!=null&&dimension.equals(a[i].dimension)&&screen.apply(a[i].xz).distance(cursor)<=12) out.append("  ").append(names[i]).append(" (").append(a[i].xz.x).append(", ").append(a[i].xz.y).append(')');
        for(com.minecraft.selector.core.WaypointStore.Marker m:waypoints.list())if(dimension.equals(m.dimension)&&screen.apply(new Point(m.x,m.z)).distance(cursor)<=12)out.append("  POI: ").append(m.toString());
        return out.toString();
    }
}
