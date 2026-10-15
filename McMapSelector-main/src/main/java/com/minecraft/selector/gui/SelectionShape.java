package com.minecraft.selector.gui;

import java.awt.Point;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
/** Sparse selection geometry; exported root bounds remain inclusive and compatible. */
public final class SelectionShape {
    public static final int MAX_PARTS=65536;
    private final List<SelectionMath.Bounds> boxes=new ArrayList<>();
    private final Set<Point> chunks=new LinkedHashSet<>();
    private Point center;private int radius;
    public SelectionShape copy() {SelectionShape s=new SelectionShape();s.boxes.addAll(boxes);for(Point p:chunks)s.chunks.add(new Point(p));s.center=center==null?null:new Point(center);s.radius=radius;return s;}
    public boolean isEmpty() {return boxes.isEmpty()&&chunks.isEmpty()&&center==null;}
    public boolean isCircle() {return center!=null;}
    public Point center() {return center==null?null:new Point(center);}
    public int radius() {return radius;}
    public void addBox(SelectionMath.Bounds b) {if(b!=null&&!boxes.contains(b)) {if(boxes.size()>=MAX_PARTS)throw new IllegalArgumentException("Too many boxes");boxes.add(b);}}
    public void paint(int chunkX,int chunkZ,boolean erase) {Point p=new Point(chunkX,chunkZ);chunkBounds(p);if(erase)chunks.remove(p);else {if(chunks.size()>=MAX_PARTS&&!chunks.contains(p))throw new IllegalArgumentException("Too many painted chunks");chunks.add(p);}}
    public void circle(Point c,int r) {if(c==null||r<0||(long)c.x-r<Integer.MIN_VALUE||(long)c.x+r>Integer.MAX_VALUE||(long)c.y-r<Integer.MIN_VALUE||(long)c.y+r>Integer.MAX_VALUE)throw new IllegalArgumentException("Circle exceeds integer coordinates");center=new Point(c);radius=r;boxes.clear();chunks.clear();}
    public boolean contains(int x,int z) {if(center!=null)return Math.hypot((double)x-center.x,(double)z-center.y)<=radius;for(SelectionMath.Bounds b:boxes)if(x>=b.minX&&x<=b.maxX&&z>=b.minZ&&z<=b.maxZ)return true;return chunks.contains(new Point(Math.floorDiv(x,16),Math.floorDiv(z,16)));}
    public List<SelectionMath.Bounds> boxes() {List<SelectionMath.Bounds> out=new ArrayList<>(boxes);for(Point p:chunks)out.add(chunkBounds(p));if(center!=null)out.add(new SelectionMath.Bounds(center.x-radius,center.y-radius,center.x+radius,center.y+radius));return Collections.unmodifiableList(out);}
    public static SelectionMath.Bounds chunkBounds(Point p) {long x=(long)p.x*16,z=(long)p.y*16;if(x<Integer.MIN_VALUE||z<Integer.MIN_VALUE||x+15>Integer.MAX_VALUE||z+15>Integer.MAX_VALUE)throw new IllegalArgumentException("Chunk exceeds integer coordinates");return new SelectionMath.Bounds((int)x,(int)z,(int)x+15,(int)z+15);}
    public SelectionMath.Bounds bounds() {return union(boxes());}
    public static SelectionMath.Bounds union(Collection<SelectionMath.Bounds> boxes) {SelectionMath.Bounds out=null;for(SelectionMath.Bounds b:boxes)out=out==null?b:new SelectionMath.Bounds(Math.min(out.minX,b.minX),Math.min(out.minZ,b.minZ),Math.max(out.maxX,b.maxX),Math.max(out.maxZ,b.maxZ));return out;}
    public Map<String,Object> json() {Map<String,Object> n=new LinkedHashMap<>();n.put("kind",center!=null?"circle":!chunks.isEmpty()?"chunks":"boxes");List<Map<String,Integer>> a=new ArrayList<>();for(SelectionMath.Bounds b:boxes())a.add(boxJson(b));n.put("boxes",a);if(center!=null){n.put("centerX",center.x);n.put("centerZ",center.y);n.put("radius",radius);}return n;}
    public static Map<String,Integer> boxJson(SelectionMath.Bounds b) {Map<String,Integer> n=new LinkedHashMap<>();n.put("minX",b.minX);n.put("minZ",b.minZ);n.put("maxX",b.maxX);n.put("maxZ",b.maxZ);return n;}
    public static int integer(JsonNode n,String key) {JsonNode v=n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToInt())throw new IllegalArgumentException("Expected integer "+key);return v.intValue();}
    public static SelectionShape parse(JsonNode n) {if(n==null||!n.isObject())throw new IllegalArgumentException("Expected selection shape object");SelectionShape s=new SelectionShape();String kind=n.path("kind").asText();if("circle".equals(kind)){s.circle(new Point(integer(n,"centerX"),integer(n,"centerZ")),integer(n,"radius"));return s;}if(!"chunks".equals(kind)&&!"boxes".equals(kind))throw new IllegalArgumentException("Unknown selection kind");JsonNode a=n.get("boxes");if(a==null||!a.isArray()||a.size()>MAX_PARTS)throw new IllegalArgumentException("Invalid boxes");for(JsonNode b:a){SelectionMath.Bounds v=new SelectionMath.Bounds(integer(b,"minX"),integer(b,"minZ"),integer(b,"maxX"),integer(b,"maxZ"));if("chunks".equals(kind)){if(!v.equals(chunkBounds(new Point(Math.floorDiv(v.minX,16),Math.floorDiv(v.minZ,16)))))throw new IllegalArgumentException("Invalid chunk box");s.paint(Math.floorDiv(v.minX,16),Math.floorDiv(v.minZ,16),false);}else s.addBox(v);}return s;}
}
