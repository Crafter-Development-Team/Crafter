package com.minecraft.selector.gui;
import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;
public final class GoToDialog {
    private GoToDialog() {}
    public static Point parse(String x,String z) {return new Point(Integer.parseInt(x.trim()),Integer.parseInt(z.trim()));}
    public static void show(Component parent,Consumer<Point> jump) {
        JTextField x=new JTextField("0",12),z=new JTextField("0",12);JPanel p=new JPanel(new GridLayout(2,2,6,6));p.add(new JLabel("X:"));p.add(x);p.add(new JLabel("Z:"));p.add(z);
        while(JOptionPane.showConfirmDialog(parent,p,"Go to X/Z",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION) {
            try {jump.accept(parse(x.getText(),z.getText()));return;} catch(NumberFormatException e) {JOptionPane.showMessageDialog(parent,"Enter integer X/Z coordinates");}
        }
    }
}
