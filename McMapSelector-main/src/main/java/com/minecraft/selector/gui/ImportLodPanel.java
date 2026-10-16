package com.minecraft.selector.gui;

import com.minecraft.selector.core.ImportLod;
import java.awt.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Swing controls kept independent of JFrame so the preferences link is headless-testable. */
public final class ImportLodPanel extends JPanel {
    final JComboBox<String> maxLevel = new JComboBox<>(new String[]{"Off","1","2","3","4"});
    final JSpinner[] distances = new JSpinner[4];
    final JCheckBox autoCenter = new JCheckBox("Auto center",true);
    final JSpinner centerX = integer(0,Integer.MIN_VALUE), centerZ = integer(0,Integer.MIN_VALUE);
    final JLabel effectiveCenter = new JLabel();
    private final Consumer<ImportLod> listener;
    private boolean updating;

    public ImportLodPanel(Consumer<ImportLod> listener) {
        this.listener=listener;
        setLayout(new GridLayout(0,2,5,5));
        add(new JLabel("Max LOD level")); add(maxLevel);
        for (int i=0;i<4;i++) {
            distances[i]=integer(8,0);
            add(new JLabel("LOD"+i+" distance (chunks)")); add(distances[i]);
            distances[i].addChangeListener(e->changed());
        }
        add(autoCenter); add(new JLabel(""));
        add(new JLabel("Center X (blocks)")); add(centerX);
        add(new JLabel("Center Z (blocks)")); add(centerZ);
        add(new JLabel("Preview center (blocks)")); add(effectiveCenter);
        maxLevel.addActionListener(e->changed()); autoCenter.addActionListener(e->changed());
        centerX.addChangeListener(e->changed()); centerZ.addChangeListener(e->changed());
        setToolTipText("Auto: selection chunk midpoint (toward zero); otherwise player, spawn in this dimension, or 0,0. Circles use cumulative chunk distances × 16.");
        setSettings(ImportLod.DEFAULT);
    }

    private static JSpinner integer(int value,int min) {
        JSpinner spinner=new JSpinner(new SpinnerNumberModel(value,min,Integer.MAX_VALUE,1));
        spinner.setEditor(new JSpinner.NumberEditor(spinner,"0"));
        ((JSpinner.DefaultEditor)spinner.getEditor()).getTextField().setColumns(7);
        // Commit valid typed values immediately; invalid edits revert when focus leaves.
        ((JSpinner.DefaultEditor)spinner.getEditor()).getTextField().setFocusLostBehavior(JFormattedTextField.COMMIT_OR_REVERT);
        ((javax.swing.text.DefaultFormatter)((JSpinner.DefaultEditor)spinner.getEditor()).getTextField().getFormatter()).setCommitsOnValidEdit(true);
        return spinner;
    }

    public ImportLod settings() {
        return new ImportLod(maxLevel.getSelectedIndex(),autoCenter.isSelected(),(Integer)centerX.getValue(),(Integer)centerZ.getValue(),
            (Integer)distances[0].getValue(),(Integer)distances[1].getValue(),(Integer)distances[2].getValue(),(Integer)distances[3].getValue());
    }

    public void setSettings(ImportLod value) {
        updating=true;
        try {
            maxLevel.setSelectedIndex(value.maxLevel); autoCenter.setSelected(value.autoCenter);
            centerX.setValue(value.centerX); centerZ.setValue(value.centerZ);
            for (int i=0;i<4;i++) distances[i].setValue(value.distance(i));
        } finally {updating=false;}
        changed();
    }

    public void showResolvedCenter(ImportLod resolved) { effectiveCenter.setText((Math.floorDiv(resolved.centerX,16)*16)+", "+(Math.floorDiv(resolved.centerZ,16)*16)); }

    /**
     * 画布中键拖动驱动：同步 spinner/checkbox 但**不回调 listener**（避免把事件打回 GUI 形成回环）。
     * 拖动必须退出自动居中，否则 resolved() 会用选区/出生点覆盖手动中心。
     */
    public void applyDraggedCenter(int newCenterX,int newCenterZ) {
        updating=true;
        try {
            autoCenter.setSelected(false);
            centerX.setValue(newCenterX); centerZ.setValue(newCenterZ);
        } finally {updating=false;}
        ImportLod value=settings();
        for (int i=0;i<4;i++) distances[i].setEnabled(i<value.maxLevel);
        centerX.setEnabled(value.maxLevel>0); centerZ.setEnabled(value.maxLevel>0);
        showResolvedCenter(value);
    }

    private void changed() {
        if (updating) return;
        ImportLod value=settings();
        for (int i=0;i<4;i++) distances[i].setEnabled(i<value.maxLevel);
        autoCenter.setEnabled(value.maxLevel>0);
        centerX.setEnabled(value.maxLevel>0&&!value.autoCenter); centerZ.setEnabled(value.maxLevel>0&&!value.autoCenter);
        listener.accept(value);
    }
}
