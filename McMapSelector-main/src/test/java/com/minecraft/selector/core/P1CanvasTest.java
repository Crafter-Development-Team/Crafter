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
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class P1CanvasTest {
    @TempDir Path directory;
    private void mouse(JComponent c,int id,int x,int z,int count,int button) {c.dispatchEvent(new MouseEvent(c,id,System.currentTimeMillis(),button==MouseEvent.BUTTON1?InputEvent.BUTTON1_DOWN_MASK:0,x,z,count,false,button));}
    private void click(JComponent c,int x,int z) {mouse(c,MouseEvent.MOUSE_PRESSED,x,z,1,MouseEvent.BUTTON1);mouse(c,MouseEvent.MOUSE_RELEASED,x,z,1,MouseEvent.BUTTON1);}
    private void key(JComponent c,String stroke) {Object k=c.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke));assertNotNull(k,stroke);c.getActionMap().get(k).actionPerformed(new ActionEvent(c,0,k.toString()));}
    @Test void bothCanvasesSupportCornersFitPoliciesNewKeysZoomAndPngComposite() throws Exception {
        Files.write(directory.resolve("r.-1.-1.mca"),new byte[]{1});RenderedMap tile=new RenderedMap(512,512);
        for(int z=0;z<512;z++) for(int x=0;x<512;x++) {tile.setRGB(x,z,0xffc8c8c8);tile.setHeightAt(x,z,64);}
        DynamicRegionManager manager=new DynamicRegionManager(directory.toFile(),p->tile);manager.updateViewport(new Rectangle(-512,-512,512,512));manager.awaitIdle(5,TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(()-> {
            MapCanvas fixed=new MapCanvas();fixed.setSize(256,256);fixed.setImage(tile);fixed.setWorldCoordinateMapping(-512,-512,1);fixed.jumpToWorldCoordinate(new Point(-256,-256));
            DynamicMapCanvas dynamic=new DynamicMapCanvas();dynamic.setSize(256,256);dynamic.setRegionManager(manager,false);dynamic.jumpToWorldCoordinate(new Point(-256,-256));
            try {
                for(JComponent component:new JComponent[]{fixed,dynamic}) {
                    SelectionCanvas canvas=(SelectionCanvas)component;ViewOverlays overlays=new ViewOverlays();overlays.posMode=true;overlays.markers=false;overlays.scaleBar=false;canvas.setOverlays(overlays);
                    click(component,50,50);assertNull(canvas.getSelectionBounds());click(component,80,90);assertEquals(new SelectionMath.Bounds(-334,-334,-304,-294),canvas.getSelectionBounds());
                    // 逐列 opaque/dry 元数据已移除：两种 Fit 策略都返回可见顶高。
                    assertEquals(64,canvas.fitSelectionY(canvas.getSelectionBounds(),false).min);assertEquals(64,canvas.fitSelectionY(canvas.getSelectionBounds(),true).min);
                    key(component,"control Z");assertNull(canvas.getSelectionBounds());
                    click(component,60,60);key(component,"ESCAPE");click(component,90,90);assertNull(canvas.getSelectionBounds());click(component,100,110);assertNotNull(canvas.getSelectionBounds());
                    canvas.setSnapMode(SelectionMath.Snap.CHUNK);SelectionMath.Bounds exact=new SelectionMath.Bounds(-333,-332,-301,-300);canvas.restoreSelectionBounds(exact);assertEquals(exact,canvas.getSelectionBounds());
                    canvas.clearSelection();overlays.posMode=false;
                    // 悬停不再显示方块 ID / 生物群系（元数据已移除），但坐标与高度仍在。
                    mouse(component,MouseEvent.MOUSE_MOVED,128,128,0,MouseEvent.NOBUTTON);assertTrue((component==fixed?fixed.getHoverText(new Point(128,128)):dynamic.getHoverText(new Point(128,128))).contains("Y: 64"));
                    assertTrue(canvas.visibleBlockCounts().getOrDefault("cached surface",0L)>0);
                    int[] before=canvas.getHoverCoordinates();double oldZoom=component==fixed?fixed.getZoomLevel():dynamic.getZoomLevel();mouse(component,MouseEvent.MOUSE_CLICKED,128,128,2,MouseEvent.BUTTON1);assertArrayEquals(before,canvas.getHoverCoordinates());assertTrue((component==fixed?fixed.getZoomLevel():dynamic.getZoomLevel())>oldZoom);
                    key(component,"HOME");assertEquals(10,component==fixed?fixed.getZoomLevel():dynamic.getZoomLevel());key(component,"END");assertEquals(.5,component==fixed?fixed.getZoomLevel():dynamic.getZoomLevel());
                    java.util.List<String> commands=new ArrayList<>();if(component==fixed) fixed.setCommandListener(commands::add);else dynamic.setCommandListener(commands::add);
                    for(String stroke:new String[]{"T","B","C","N","O","I","P","shift SPACE"}) key(component,stroke);assertEquals(Arrays.asList("water","biome","cave","night","contours","info","pos","fitOpaque"),commands);
                    BufferedImage day=ViewExport.capture(component);overlays.night=true;BufferedImage night=ViewExport.capture(component);assertTrue((day.getRGB(128,100)&255)>(night.getRGB(128,100)&255));
                    try {Path output=directory.resolve(component==fixed?"static.png":"dynamic.png");ViewExport.write(night,output.toFile());BufferedImage read=ImageIO.read(output.toFile());assertEquals(256,read.getWidth());assertEquals(night.getRGB(128,100),read.getRGB(128,100));}catch(Exception e) {throw new RuntimeException(e);}
                    assertEquals(64,tile.getHeightAt(256,256));
                }
            } finally {fixed.enableAutoViewportManagement(false);dynamic.cleanup();}
        });manager.shutdown();
    }
    @Test void exportCropsScrolledViewportAndYBadgeUpdatesWithoutChangingSelection() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            MapCanvas canvas=new MapCanvas();ViewOverlays overlays=new ViewOverlays();overlays.scaleBar=true;canvas.setOverlays(overlays);canvas.setSize(256,256);RenderedMap map=new RenderedMap(256,256);canvas.setImage(map);
            JViewport viewport=new JViewport();viewport.setSize(128,128);viewport.setView(canvas);viewport.setViewPosition(new Point(64,64));
            try {
                canvas.restoreSelectionBounds(new SelectionMath.Bounds(90,90,120,120));BufferedImage before=ViewExport.capture(canvas);assertEquals(128,before.getWidth());assertEquals(128,before.getHeight());
                overlays.minY=-128;overlays.maxY=655;BufferedImage after=ViewExport.capture(canvas);assertFalse(Arrays.equals(before.getRGB(0,0,128,128,null,0,128),after.getRGB(0,0,128,128,null,0,128)));assertEquals(new SelectionMath.Bounds(90,90,120,120),canvas.getSelectionBounds());
            } finally {canvas.enableAutoViewportManagement(false);}
        });
    }
    @Test void markerTooltipFiltersDimensionAndIncludesNameAndCoordinates() {
        com.minecraft.selector.nbt.NBTReader.NBTCompound root=new com.minecraft.selector.nbt.NBTReader.NBTCompound("");root.put("SpawnX",new com.minecraft.selector.nbt.NBTReader.NBTInt("SpawnX",-20));root.put("SpawnZ",new com.minecraft.selector.nbt.NBTReader.NBTInt("SpawnZ",30));ViewOverlays overlays=new ViewOverlays();overlays.anchors=WorldAnchors.parse(root);
        assertTrue(overlays.tooltip(new Point(-20,30),p->p).contains("Spawn (-20, 30)"));overlays.dimension="minecraft:the_nether";assertEquals("",overlays.tooltip(new Point(-20,30),p->p));
    }
}
