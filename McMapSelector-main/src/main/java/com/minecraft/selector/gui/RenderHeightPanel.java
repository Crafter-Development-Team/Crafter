package com.minecraft.selector.gui;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** View clipping is separate from Blender's import Y range. Null preserves automatic surface. */
public final class RenderHeightPanel extends JPanel {
    private final JSlider slider=new JSlider(JSlider.VERTICAL,-128,1024,320);
    private final JSpinner input=new JSpinner(new SpinnerNumberModel(320,Integer.MIN_VALUE+1,Integer.MAX_VALUE,1));
    private final JLabel mode=new JLabel("Auto surface",SwingConstants.CENTER);
    private final Consumer<Integer> changed;
    private volatile Integer clip;
    private boolean syncing;
    public RenderHeightPanel(Consumer<Integer> changed) {
        super(new BorderLayout(4,4));this.changed=changed;
        setBorder(BorderFactory.createTitledBorder("Render height"));setPreferredSize(new Dimension(155,300));
        JPanel top=new JPanel(new GridLayout(0,1,3,3));top.add(mode);top.add(new JLabel("Top Y (Enter)",SwingConstants.CENTER));top.add(input);
        JSpinner.NumberEditor editor=new JSpinner.NumberEditor(input,"#");input.setEditor(editor);
        JButton auto=new JButton("Auto surface");auto.addActionListener(e->setClipY(null));top.add(auto);
        add(top,BorderLayout.NORTH);slider.setPaintTicks(true);slider.setMajorTickSpacing(128);add(slider,BorderLayout.CENTER);
        input.addChangeListener(e->{if(!syncing) setClipY(((Number)input.getValue()).intValue());});
        slider.addChangeListener(e->{if(!syncing) {syncing=true;input.setValue(slider.getValue());syncing=false;
            clip=slider.getValue();mode.setText("Top Y: "+clip);if(!slider.getValueIsAdjusting()) changed.accept(clip);}});
        editor.getTextField().addActionListener(e->{try {input.commitEdit();setClipY(((Number)input.getValue()).intValue());}
            catch(java.text.ParseException ex) {editor.getTextField().setValue(input.getValue());}});
    }
    public Integer getClipY() {return clip;}
    public JSlider getSlider() {return slider;}
    public JSpinner getInput() {return input;}
    public void setClipY(Integer y) {
        if(y!=null && y==Integer.MIN_VALUE) throw new IllegalArgumentException("Y reserved for empty height");
        syncing=true;clip=y;
        if(y!=null) {
            if(y<slider.getMinimum() || y>slider.getMaximum()) {
                int min=(int)Math.max((long)Integer.MIN_VALUE+1,(long)y-512),max=(int)Math.min(Integer.MAX_VALUE,(long)y+512);
                slider.setModel(new DefaultBoundedRangeModel(y,0,min,max));
            }
            slider.setValue(y);input.setValue(y);
        }
        mode.setText(y==null?"Auto surface":"Top Y: "+y);syncing=false;changed.accept(y);
    }
}
