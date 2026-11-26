package com.minecraft.selector.gui;
import javax.swing.*;
import com.minecraft.selector.i18n.I18n;
import java.awt.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
public final class SelectionBoundsPanel extends JPanel {
    private final JTextField[] fields;
    public SelectionBoundsPanel(SelectionMath.Bounds b,Consumer<SelectionMath.Bounds> apply) {
        super(new GridLayout(5,2,6,6));
        this.fields=new JTextField[4];
        String[] labels={I18n.t("Min X"),I18n.t("Min Z"),I18n.t("Max X"),I18n.t("Max Z")};
        int[] v=b==null?new int[]{0,0,0,0}:new int[]{b.minX,b.minZ,b.maxX,b.maxZ};
        Runnable submit=() -> {try {apply.accept(SelectionMath.parse(fields[0].getText(),fields[1].getText(),fields[2].getText(),fields[3].getText()));}catch(IllegalArgumentException e){JOptionPane.showMessageDialog(this,I18n.t("Enter integer bounds with min ≤ max"),I18n.t("Invalid bounds"),JOptionPane.ERROR_MESSAGE);}};
        for(int i=0;i<4;i++) {add(new JLabel(labels[i]));fields[i]=new JTextField(Integer.toString(v[i]),12);fields[i].addActionListener(e->submit.run());add(fields[i]);}
        JButton button=new JButton(I18n.t("Apply"));button.addActionListener(e->submit.run());add(button);
    }
    public SelectionBoundsPanel(SelectionMath.Bounds b,int minY,int maxY,BiConsumer<SelectionMath.Bounds,int[]> apply) {
        super(new GridLayout(7,2,6,6));
        setBorder(BorderFactory.createEmptyBorder(8,10,8,10));
        this.fields=new JTextField[6];
        String[] labels={I18n.t("Min X"),I18n.t("Min Y"),I18n.t("Min Z"),I18n.t("Max X"),I18n.t("Max Y"),I18n.t("Max Z")};
        int[] v=b==null?new int[]{0,minY,0,0,maxY,0}:new int[]{b.minX,minY,b.minZ,b.maxX,maxY,b.maxZ};
        Runnable submit=() -> {
            try {
                SelectionMath.Bounds parsedBounds=SelectionMath.parse(fields[0].getText(),fields[2].getText(),fields[3].getText(),fields[5].getText());
                int loY=Integer.parseInt(fields[1].getText().trim());
                int hiY=Integer.parseInt(fields[4].getText().trim());
                if(loY>hiY) throw new IllegalArgumentException("minY > maxY");
                apply.accept(parsedBounds,new int[]{loY,hiY});
            } catch(IllegalArgumentException e) {
                JOptionPane.showMessageDialog(this,I18n.t("Enter integer bounds with min ≤ max"),I18n.t("Invalid bounds"),JOptionPane.ERROR_MESSAGE);
            }
        };
        for(int i=0;i<6;i++) {add(new JLabel(labels[i]));fields[i]=new JTextField(Integer.toString(v[i]),12);fields[i].addActionListener(e->submit.run());add(fields[i]);}
        JButton button=new JButton(I18n.t("Apply"));button.addActionListener(e->submit.run());add(button);
    }
}
