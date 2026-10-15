package com.minecraft.selector.gui;
import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;
public final class SelectionBoundsPanel extends JPanel {
    private final JTextField[] fields=new JTextField[4];
    public SelectionBoundsPanel(SelectionMath.Bounds b,Consumer<SelectionMath.Bounds> apply) {
        super(new GridLayout(5,2,6,6));String[] labels={"Min X","Min Z","Max X","Max Z"};int[] v=b==null?new int[]{0,0,0,0}:new int[]{b.minX,b.minZ,b.maxX,b.maxZ};
        Runnable submit=() -> {try {apply.accept(SelectionMath.parse(fields[0].getText(),fields[1].getText(),fields[2].getText(),fields[3].getText()));}catch(IllegalArgumentException e){JOptionPane.showMessageDialog(this,"Enter integer bounds with min ≤ max","Invalid bounds",JOptionPane.ERROR_MESSAGE);}};
        for(int i=0;i<4;i++) {add(new JLabel(labels[i]));fields[i]=new JTextField(Integer.toString(v[i]),12);fields[i].addActionListener(e->submit.run());add(fields[i]);}
        JButton button=new JButton("Apply");button.addActionListener(e->submit.run());add(button);
    }
}
