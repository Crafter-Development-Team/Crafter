package com.minecraft.selector.gui;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
public final class SelectionBindings {
    private SelectionBindings() {}
    public static void install(JComponent component,SelectionCanvas canvas,Consumer<String> command) {
        component.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {if(e.getKeyCode()==KeyEvent.VK_SHIFT) canvas.setSquareGesture(true);}
            @Override public void keyReleased(KeyEvent e) {if(e.getKeyCode()==KeyEvent.VK_SHIFT) canvas.setSquareGesture(false);}
        });
        bind(component,"control Z","undo",canvas::undoSelection);
        bind(component,"control A","visible",canvas::selectVisible);
        bind(component,"ENTER","confirm",canvas::confirmCurrentSelection);
        bind(component,"ESCAPE","clear",canvas::clearSelection);
        bind(component,"HOME","maxZoom",()->canvas.zoomExtents(true));
        bind(component,"END","fitZoom",()->canvas.zoomExtents(false));
        bind(component,"shift SPACE","fitOpaque",()->command.accept("fitOpaque"));
        String[] keys={"T","B","C","N","O","I","P"};String[] commands={"water","biome","cave","night","contours","info","pos"};
        for(int i=0;i<keys.length;i++) {final String cmd=commands[i];bind(component,keys[i],cmd,()->command.accept(cmd));}
        bind(component,"SPACE","fit",()->command.accept("fit"));
        bind(component,"G","grid",()->command.accept("grid"));
        bind(component,"control G","goto",()->command.accept("goto"));
        bind(component,"control shift C","copy",()->command.accept("copy"));
        for(int i=0;i<3;i++) {final int n=i+2;bind(component,"F"+n,"anchor"+n,()->command.accept("F"+n));}
        String[] arrows={"LEFT","RIGHT","UP","DOWN"};
        for(int d=0;d<4;d++) for(int shrink=0;shrink<2;shrink++) for(int big=0;big<2;big++) {
            final int direction=d,step=big==0?1:16;final boolean inward=shrink==1;
            String key="alt "+(shrink==1?"control ":"")+(big==1?"shift ":"")+arrows[d];
            bind(component,key,key,()-> {canvas.nudgeSelection(direction,inward,step);});
        }
    }
    private static void bind(JComponent c,String key,String name,Runnable run) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key),name);
        c.getActionMap().put(name,new AbstractAction() {@Override public void actionPerformed(ActionEvent e) {run.run();}});
    }
}
