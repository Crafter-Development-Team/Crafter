package com.minecraft.selector.gui;
import java.awt.*;
import java.awt.event.*;
import javax.swing.SwingUtilities;
/** Shared right-button tools. One undo snapshot covers the entire gesture. */
public final class SelectionGestures {
    private Point start,last;private SelectionShape base,working;private String tool;private boolean erase;
    public boolean active(){return start!=null;}
    public void cancel(){start=null;last=null;}
    public boolean press(MouseEvent e,Point world,SelectionCanvas canvas,String mode,SelectionMath.Snap snap){return press(e,world,canvas,mode,snap,false);}
    /** Box append when Alt, Ctrl (WM-safe), or sticky Add-box menu mode is on. */
    public boolean press(MouseEvent e,Point world,SelectionCanvas canvas,String mode,SelectionMath.Snap snap,boolean forceAdd){boolean addBoxes=e.isAltDown()||e.isControlDown()||forceAdd;if(world==null||!SwingUtilities.isRightMouseButton(e)||("Box".equals(mode)&&!addBoxes))return false;
        start=new Point(world);last=new Point(world);tool=mode;erase=e.isShiftDown()&&"Chunk paint".equals(tool);
        base=new SelectionShape();if("Chunk paint".equals(tool)){SelectionShape current=canvas.getSelectionShape();if("chunks".equals(current.json().get("kind")))base=current;}else if(addBoxes&&"Box".equals(tool)){SelectionShape current=canvas.getSelectionShape();for(SelectionMath.Bounds b:current.boxes())base.addBox(b);if(base.isEmpty())base.addBox(canvas.getSelectionBounds());}
        working=base.copy();canvas.setSelectionShape(working,true);update(e,world,canvas,snap);return true;}
    public boolean update(MouseEvent e,Point world,SelectionCanvas canvas,SelectionMath.Snap snap){if(!active())return false;if(world==null)return true;
        String error=null;try {if("Circle".equals(tool)){long cap=Math.min(Math.min((long)start.x-Integer.MIN_VALUE,(long)Integer.MAX_VALUE-start.x),Math.min((long)start.y-Integer.MIN_VALUE,(long)Integer.MAX_VALUE-start.y));int radius=(int)Math.min(cap,Math.min(Integer.MAX_VALUE,Math.ceil(start.distance(world))));working=new SelectionShape();working.circle(start,radius);}
        else if("Chunk paint".equals(tool)){int ax=Math.floorDiv(last.x,16),az=Math.floorDiv(last.y,16),bx=Math.floorDiv(world.x,16),bz=Math.floorDiv(world.y,16);long steps=Math.max(Math.abs((long)bx-ax),Math.abs((long)bz-az));if(steps>SelectionShape.MAX_PARTS)throw new IllegalArgumentException("Paint gesture exceeds 65536 chunks; use a smaller stroke");for(int i=0;i<=steps;i++){double t=steps==0?0:i/(double)steps;working.paint((int)Math.round(ax+((long)bx-ax)*t),(int)Math.round(az+((long)bz-az)*t),erase);}last=new Point(world);}
        else{working=base.copy();working.addBox(SelectionMath.constrain(SelectionMath.drag(start,world,e.isShiftDown()),snap,e.isShiftDown()));}
        }catch(IllegalArgumentException ex){cancel();error=ex.getMessage();}
        canvas.setSelectionShape(working,false);if(error!=null&&canvas instanceof javax.swing.JComponent)((javax.swing.JComponent)canvas).setToolTipText(error);return true;}
    public boolean release(MouseEvent e,Point world,SelectionCanvas canvas,SelectionMath.Snap snap){if(!active())return false;update(e,world,canvas,snap);cancel();return true;}
    public static void draw(Graphics2D graphics,SelectionShape shape,java.util.function.Function<Point,Point> screen,double scale){if(shape.isEmpty())return;Graphics2D g=(Graphics2D)graphics.create();try{g.setColor(new Color(255,190,60,65));if(shape.isCircle()){SelectionMath.Bounds b=shape.bounds();Point a=screen.apply(new Point(b.minX,b.minZ)),c=screen.apply(new Point(b.maxX,b.maxZ));int w=Math.max(1,c.x-a.x+(int)Math.ceil(scale)),h=Math.max(1,c.y-a.y+(int)Math.ceil(scale));g.fillOval(a.x,a.y,w,h);g.setColor(new Color(255,210,80));g.drawOval(a.x,a.y,w,h);}else for(SelectionMath.Bounds b:shape.boxes()){Point a=screen.apply(new Point(b.minX,b.minZ)),c=screen.apply(new Point(b.maxX,b.maxZ));int w=Math.max(1,c.x-a.x+(int)Math.ceil(scale)),h=Math.max(1,c.y-a.y+(int)Math.ceil(scale));g.setColor(new Color(255,190,60,65));g.fillRect(a.x,a.y,w,h);g.setColor(new Color(255,210,80));g.drawRect(a.x,a.y,w,h);}}finally{g.dispose();}}
}
