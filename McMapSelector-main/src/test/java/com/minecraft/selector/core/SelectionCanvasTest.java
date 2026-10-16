package com.minecraft.selector.core;

import com.minecraft.selector.gui.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SelectionCanvasTest {
    @TempDir Path directory;
    private void mouse(JComponent c,int id,int x,int z,int modifiers,int button) {
        c.dispatchEvent(new MouseEvent(c,id,System.currentTimeMillis(),modifiers,x,z,1,false,button));
    }
    private void key(JComponent c,String stroke) {
        Object name=c.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke));assertNotNull(name,stroke);
        Action action=c.getActionMap().get(name);assertNotNull(action,stroke);action.actionPerformed(new ActionEvent(c,0,name.toString()));
    }
    @Test void bothCanvasesSupportSquareResizeUndoNudgeAndClearWithoutChangingY() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            MapCanvas staticMap=new MapCanvas();staticMap.setSize(256,256);staticMap.setImage(new RenderedMap(256,256));
            DynamicMapCanvas dynamic=new DynamicMapCanvas();dynamic.setSize(256,256);
            try {
                for(JComponent c:new JComponent[]{staticMap,dynamic}) {
                    SelectionCanvas canvas=(SelectionCanvas)c;
                    AtomicInteger updates=new AtomicInteger();
                    if(c instanceof MapCanvas) ((MapCanvas)c).setSelectionChangeListener(b->updates.incrementAndGet());
                    else ((DynamicMapCanvas)c).setSelectionChangeListener(b->updates.incrementAndGet());
                    mouse(c,MouseEvent.MOUSE_PRESSED,50,50,InputEvent.BUTTON3_DOWN_MASK,MouseEvent.BUTTON3);
                    mouse(c,MouseEvent.MOUSE_DRAGGED,70,80,InputEvent.BUTTON3_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK,MouseEvent.NOBUTTON);
                    assertEquals(new SelectionMath.Bounds(50,50,80,80),canvas.getSelectionBounds());
                    // Shift release/repress without mouse movement must update the live preview.
                    for(KeyListener listener:c.getKeyListeners()) listener.keyReleased(new KeyEvent(c,KeyEvent.KEY_RELEASED,0,0,KeyEvent.VK_SHIFT,KeyEvent.CHAR_UNDEFINED));
                    assertEquals(new SelectionMath.Bounds(50,50,70,80),canvas.getSelectionBounds());
                    for(KeyListener listener:c.getKeyListeners()) listener.keyPressed(new KeyEvent(c,KeyEvent.KEY_PRESSED,0,InputEvent.SHIFT_DOWN_MASK,KeyEvent.VK_SHIFT,KeyEvent.CHAR_UNDEFINED));
                    assertEquals(new SelectionMath.Bounds(50,50,80,80),canvas.getSelectionBounds());
                    mouse(c,MouseEvent.MOUSE_RELEASED,70,80,InputEvent.SHIFT_DOWN_MASK,MouseEvent.BUTTON3);
                    assertEquals(new SelectionMath.Bounds(50,50,80,80),canvas.getSelectionBounds());
                    mouse(c,MouseEvent.MOUSE_PRESSED,80,80,InputEvent.BUTTON1_DOWN_MASK,MouseEvent.BUTTON1);
                    mouse(c,MouseEvent.MOUSE_DRAGGED,100,90,InputEvent.BUTTON1_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK,MouseEvent.NOBUTTON);
                    assertEquals(new SelectionMath.Bounds(50,50,100,100),canvas.getSelectionBounds());
                    // Releasing Shift must recover the raw mouse aspect from the resize baseline.
                    mouse(c,MouseEvent.MOUSE_RELEASED,100,90,0,MouseEvent.BUTTON1);
                    assertEquals(new SelectionMath.Bounds(50,50,100,90),canvas.getSelectionBounds());
                    key(c,"control Z");assertEquals(new SelectionMath.Bounds(50,50,80,80),canvas.getSelectionBounds());
                    key(c,"control Z");assertEquals(new SelectionMath.Bounds(50,50,80,80),canvas.getSelectionBounds());
                    key(c,"alt shift LEFT");assertEquals(new SelectionMath.Bounds(34,50,80,80),canvas.getSelectionBounds());
                    key(c,"control alt RIGHT");assertEquals(new SelectionMath.Bounds(34,50,79,80),canvas.getSelectionBounds());
                    key(c,"ESCAPE");assertNull(canvas.getSelectionBounds());
                    key(c,"control Z");assertEquals(new SelectionMath.Bounds(34,50,79,80),canvas.getSelectionBounds());
                    canvas.setSnapMode(SelectionMath.Snap.CHUNK);assertEquals(new SelectionMath.Bounds(32,48,79,95),canvas.getSelectionBounds());
                    key(c,"control Z");assertEquals(new SelectionMath.Bounds(34,50,79,80),canvas.getSelectionBounds());
                    key(c,"alt LEFT");assertEquals(new SelectionMath.Bounds(33,50,79,80),canvas.getSelectionBounds());
                    if(c instanceof MapCanvas) ((MapCanvas)c).resetSelection();else ((DynamicMapCanvas)c).resetSelection();
                    key(c,"control Z");assertNull(canvas.getSelectionBounds());
                    assertTrue(updates.get()>=8);
                }
            } finally {staticMap.enableAutoViewportManagement(false);dynamic.cleanup();}
        });
    }
    @Test void visibleViewportMiddleClickYFitConfirmAndCommandParity() throws Exception {
        Files.write(directory.resolve("r.-1.-1.mca"),new byte[]{1});
        RenderedMap tile=new RenderedMap(512,512);tile.setHeightAt(256,256,-20);
        DynamicRegionManager manager=new DynamicRegionManager(directory.toFile(),p->tile);
        manager.updateViewport(new Rectangle(-512,-512,512,512));manager.awaitIdle(5,TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(()-> {
            MapCanvas staticMap=new MapCanvas();staticMap.setSize(256,256);staticMap.setImage(tile);staticMap.setWorldCoordinateMapping(-512,-512,1);staticMap.jumpToWorldCoordinate(new Point(-256,-256));
            DynamicMapCanvas dynamic=new DynamicMapCanvas();dynamic.setSize(256,256);dynamic.setRegionManager(manager,false);dynamic.jumpToWorldCoordinate(new Point(-256,-256));
            AtomicInteger confirmed=new AtomicInteger();
            staticMap.setSelectionCallback(new MapCanvas.SelectionCallback() {public void onSelectionComplete(int a,int b,int c,int d){}public void onSelectionConfirmed(int a,int b,int c,int d){confirmed.incrementAndGet();}});
            dynamic.setSelectionCallback(new DynamicMapCanvas.SelectionCallback() {public void onSelectionComplete(int a,int b,int c,int d){}public void onSelectionConfirmed(int a,int b,int c,int d){confirmed.incrementAndGet();}});
            try {
                for(JComponent c:new JComponent[]{staticMap,dynamic}) {
                    SelectionCanvas canvas=(SelectionCanvas)c;java.util.List<String> commands=new java.util.ArrayList<>();
                    java.util.List<java.awt.Point> lodCenters=new java.util.ArrayList<>();
                    if(c instanceof MapCanvas) {staticMap.setLodCenterListener(lodCenters::add);staticMap.setCommandListener(commands::add);}else{dynamic.setLodCenterListener(lodCenters::add);dynamic.setCommandListener(commands::add);}
                    // 中键现在是拖动 LOD 中心（旧的"设为地表高度"已移除）；LOD 叠加层默认可见。
                    mouse(c,MouseEvent.MOUSE_PRESSED,128,128,InputEvent.BUTTON2_DOWN_MASK,MouseEvent.BUTTON2);
                    assertFalse(lodCenters.isEmpty(),"middle press must report a LOD center");
                    assertArrayEquals(new int[]{-256,-20,-256},canvas.getHoverCoordinates());
                    key(c,"control A");assertEquals(new SelectionMath.Bounds(-384,-384,-129,-129),canvas.getSelectionBounds());                    assertEquals(-20,canvas.fitSelectionY(canvas.getSelectionBounds()).min);
                    key(c,"ENTER");assertNotNull(canvas.getSelectionBounds());
                    for(String stroke:new String[]{"SPACE","G","control G","control shift C","F2","F3","F4"}) key(c,stroke);
                    assertEquals(java.util.Arrays.asList("fit","grid","goto","copy","F2","F3","F4"),commands);
                    // Grid is a pure overlay: repainting does not load new tile data.
                    canvas.setGridMode(2);c.paint(new java.awt.image.BufferedImage(256,256,2).getGraphics());
                }
                assertEquals(2,confirmed.get());
            } finally {staticMap.enableAutoViewportManagement(false);dynamic.cleanup();}
        });manager.shutdown();
    }

    /** 中键拖动 LOD 中心：press/drag 都要回调，release 之后必须停止。 */
    @Test void middleButtonDragsLodCenterOnBothCanvases() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            MapCanvas staticMap=new MapCanvas();staticMap.setSize(256,256);
            staticMap.setImage(new RenderedMap(256,256));staticMap.jumpToWorldCoordinate(new Point(0,0));
            DynamicMapCanvas dynamic=new DynamicMapCanvas();dynamic.setSize(256,256);
            try {
                for(JComponent c:new JComponent[]{staticMap,dynamic}) {
                    java.util.List<java.awt.Point> centers=new java.util.ArrayList<>();
                    if(c instanceof MapCanvas) ((MapCanvas)c).setLodCenterListener(centers::add);
                    else ((DynamicMapCanvas)c).setLodCenterListener(centers::add);

                    mouse(c,MouseEvent.MOUSE_PRESSED,128,128,InputEvent.BUTTON2_DOWN_MASK,MouseEvent.BUTTON2);
                    assertEquals(1,centers.size(),"middle press must move the LOD center");
                    mouse(c,MouseEvent.MOUSE_DRAGGED,140,128,InputEvent.BUTTON2_DOWN_MASK,MouseEvent.NOBUTTON);
                    assertEquals(2,centers.size(),"middle drag must keep moving the LOD center");
                    assertNotEquals(centers.get(0),centers.get(1),"dragging must change the center");

                    mouse(c,MouseEvent.MOUSE_RELEASED,140,128,InputEvent.BUTTON2_DOWN_MASK,MouseEvent.BUTTON2);
                    int settled=centers.size();
                    mouse(c,MouseEvent.MOUSE_DRAGGED,180,128,InputEvent.BUTTON2_DOWN_MASK,MouseEvent.NOBUTTON);
                    assertEquals(settled,centers.size(),"after release the drag must not keep moving the center");
                }
            } finally {staticMap.enableAutoViewportManagement(false);dynamic.cleanup();}
        });
    }
}
