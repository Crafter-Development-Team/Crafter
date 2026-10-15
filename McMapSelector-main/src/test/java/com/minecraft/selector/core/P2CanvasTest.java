package com.minecraft.selector.core;
import com.minecraft.selector.gui.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
class P2CanvasTest {
    @TempDir Path directory;
    private void event(JComponent c,int type,int x,int z,int button,int flags){c.dispatchEvent(new MouseEvent(c,type,System.currentTimeMillis(),flags,x,z,1,false,button));}
    private void drag(JComponent c,int x,int z,int bx,int bz,int flags){event(c,MouseEvent.MOUSE_PRESSED,x,z,MouseEvent.BUTTON3,flags|InputEvent.BUTTON3_DOWN_MASK);event(c,MouseEvent.MOUSE_DRAGGED,bx,bz,MouseEvent.NOBUTTON,flags|InputEvent.BUTTON3_DOWN_MASK);event(c,MouseEvent.MOUSE_RELEASED,bx,bz,MouseEvent.BUTTON3,flags);}
    private void key(JComponent c,String stroke){Object action=c.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke));assertNotNull(action);c.getActionMap().get(action).actionPerformed(new ActionEvent(c,0,action.toString()));}
    @Test void bothCanvasesForceBoxAndKeepP0SquareUndoAndClear()throws Exception{
        Files.write(directory.resolve("r.-1.-1.mca"),new byte[]{1});RenderedMap tile=new RenderedMap(512,512);for(int z=0;z<512;z++)for(int x=0;x<512;x++)tile.setRGB(x,z,0xff547890);
        DynamicRegionManager manager=new DynamicRegionManager(directory.toFile(),p->tile);manager.updateViewport(new Rectangle(-512,-512,512,512));manager.awaitIdle(5,TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(()->{MapCanvas fixed=new MapCanvas();fixed.setSize(512,512);fixed.setImage(tile);fixed.setWorldCoordinateMapping(-512,-512,1);DynamicMapCanvas dynamic=new DynamicMapCanvas();dynamic.setSize(512,512);dynamic.setRegionManager(manager,false);dynamic.jumpToWorldCoordinate(new Point(-256,-256));try{for(JComponent component:new JComponent[]{fixed,dynamic}){
            SelectionCanvas canvas=(SelectionCanvas)component;ViewOverlays overlays=new ViewOverlays();overlays.markers=false;overlays.scaleBar=false;overlays.gaps=false;canvas.setOverlays(overlays);
            SelectionMath.Bounds initial=new SelectionMath.Bounds(-500,-500,-480,-480);canvas.restoreSelectionBounds(initial);
            for(String staleMode:new String[]{"Box","Chunk paint","Circle"}) {
                overlays.selectionMode=staleMode;overlays.addBoxMode=true;
                drag(component,100,100,150,150,InputEvent.ALT_DOWN_MASK);
                assertEquals(new SelectionMath.Bounds(-412,-412,-362,-362),canvas.getSelectionBounds());
                assertTrue(canvas.getSelectionShape().isEmpty());assertEquals("Box",overlays.selectionMode);assertFalse(overlays.addBoxMode);
                key(component,"control Z");assertEquals(initial,canvas.getSelectionBounds());assertTrue(canvas.getSelectionShape().isEmpty());
            }
            drag(component,50,50,80,100,InputEvent.SHIFT_DOWN_MASK);
            assertTrue(canvas.getSelectionShape().isEmpty());assertEquals(new SelectionMath.Bounds(-462,-462,-412,-412),canvas.getSelectionBounds());
            BufferedImage capture=ViewExport.capture(component);assertNotEquals(tile.getRGB(75,50),capture.getRGB(75,50));assertEquals(0xff547890,tile.getRGB(75,75));
            overlays.selectionMode="Circle";overlays.addBoxMode=true;key(component,"ESCAPE");
            assertNull(canvas.getSelectionBounds());assertTrue(canvas.getSelectionShape().isEmpty());assertEquals("Box",overlays.selectionMode);assertFalse(overlays.addBoxMode);
            key(component,"control Z");assertEquals(new SelectionMath.Bounds(-462,-462,-412,-412),canvas.getSelectionBounds());assertTrue(canvas.getSelectionShape().isEmpty());
            overlays.selectionMode="Chunk paint";overlays.addBoxMode=true;
            if(canvas instanceof MapCanvas)((MapCanvas)canvas).resetSelection();else ((DynamicMapCanvas)canvas).resetSelection();
            assertEquals("Box",overlays.selectionMode);assertFalse(overlays.addBoxMode);assertNull(canvas.getSelectionBounds());
            key(component,"control Z");assertNull(canvas.getSelectionBounds());
            canvas.restoreSelectionBounds(initial);
            overlays.minimap=true;overlays.posMode=true;ViewExport.capture(component);SelectionMath.Bounds before=canvas.getSelectionBounds();event(component,MouseEvent.MOUSE_PRESSED,420,77,MouseEvent.BUTTON1,InputEvent.BUTTON1_DOWN_MASK);event(component,MouseEvent.MOUSE_RELEASED,420,77,MouseEvent.BUTTON1,0);assertEquals(before,canvas.getSelectionBounds());
        }}finally{dynamic.cleanup();}});manager.shutdown();
    }
    @Test void poiAndOreOverlaysAreDimensionAwareAndNeverMutateSource(){ViewOverlays overlays=new ViewOverlays();overlays.scaleBar=false;overlays.waypoints.add(new WaypointStore.Marker("Camera",20,20,"minecraft:overworld"));overlays.waypoints.add(new WaypointStore.Marker("Other dimension",40,40,"mod:moon"));assertTrue(overlays.tooltip(new Point(20,20),p->p).contains("Camera"));assertFalse(overlays.tooltip(new Point(40,40),p->p).contains("Other"));BufferedImage image=new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();overlays.ore=true;Map<Point,Long> counts=new LinkedHashMap<>();counts.put(new Point(0,0),1L);counts.put(new Point(1,0),100L);overlays.oreCounts=counts;overlays.drawOre(g,p->p,1);assertTrue((image.getRGB(20,5)>>>24)>(image.getRGB(5,5)>>>24));overlays.draw(g,100,100,1,p->p,null,null,null,null);assertNotEquals(0,image.getRGB(20,20));g.dispose();}

    @Test void altCtrlAndStaleStickyModeAlwaysReplaceSingleBox()throws Exception{
        Files.write(directory.resolve("r.-1.-1.mca"),new byte[]{1});RenderedMap tile=new RenderedMap(512,512);for(int z=0;z<512;z++)for(int x=0;x<512;x++)tile.setRGB(x,z,0xff547890);
        DynamicRegionManager manager=new DynamicRegionManager(directory.toFile(),p->tile);manager.updateViewport(new Rectangle(-512,-512,512,512));manager.awaitIdle(5,TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(()->{MapCanvas fixed=new MapCanvas();fixed.setSize(512,512);fixed.setImage(tile);fixed.setWorldCoordinateMapping(-512,-512,1);DynamicMapCanvas dynamic=new DynamicMapCanvas();dynamic.setSize(512,512);dynamic.setRegionManager(manager,false);dynamic.jumpToWorldCoordinate(new Point(-256,-256));try{for(JComponent component:new JComponent[]{fixed,dynamic}){
            SelectionCanvas canvas=(SelectionCanvas)component;ViewOverlays overlays=new ViewOverlays();overlays.markers=false;overlays.scaleBar=false;overlays.gaps=false;overlays.selectionMode="Box";canvas.setOverlays(overlays);
            SelectionMath.Bounds initial=new SelectionMath.Bounds(-500,-500,-480,-480);canvas.restoreSelectionBounds(initial);
            for(int flags:new int[]{0,InputEvent.ALT_DOWN_MASK,InputEvent.CTRL_DOWN_MASK,InputEvent.ALT_DOWN_MASK|InputEvent.CTRL_DOWN_MASK}) {
                overlays.addBoxMode=true;
                drag(component,100,100,150,150,flags);
                assertTrue(canvas.getSelectionShape().isEmpty(),"GUI drag must not create shape extras");
                assertEquals(new SelectionMath.Bounds(-412,-412,-362,-362),canvas.getSelectionBounds(),"right-drag replaces even with Alt/Ctrl/stale sticky mode");
                assertFalse(overlays.addBoxMode);
                drag(component,200,200,220,220,flags);
                assertTrue(canvas.getSelectionShape().isEmpty());assertEquals(new SelectionMath.Bounds(-312,-312,-292,-292),canvas.getSelectionBounds());
                key(component,"control Z");assertEquals(new SelectionMath.Bounds(-412,-412,-362,-362),canvas.getSelectionBounds());
                canvas.restoreSelectionBounds(initial);
            }
        }}finally{dynamic.cleanup();}});manager.shutdown();
    }
    @Test void hugePaintStrokeStopsSafelyAndRetainsUndoSnapshot()throws Exception {
        SwingUtilities.invokeAndWait(()->{DynamicMapCanvas canvas=new DynamicMapCanvas();SelectionGestures gestures=new SelectionGestures();SelectionMath.Bounds initial=new SelectionMath.Bounds(0,0,15,15);canvas.restoreSelectionBounds(initial);
            MouseEvent press=new MouseEvent(canvas,MouseEvent.MOUSE_PRESSED,0,InputEvent.BUTTON3_DOWN_MASK,0,0,1,false,MouseEvent.BUTTON3);
            assertTrue(gestures.press(press,new Point(0,0),canvas,"Chunk paint",SelectionMath.Snap.NONE));
            assertTrue(gestures.update(press,new Point(2000000,0),canvas,SelectionMath.Snap.NONE));assertFalse(gestures.active());assertEquals(1,canvas.getSelectionShape().boxes().size());assertTrue(canvas.getToolTipText().contains("65536"));canvas.undoSelection();assertEquals(initial,canvas.getSelectionBounds());assertTrue(canvas.getSelectionShape().isEmpty());canvas.cleanup();});
    }
}
