package com.minecraft.selector.gui;
import javax.swing.*;
import java.awt.*;
public final class WorldInfoDialog {
    public static void show(Component owner,String info) {JTextArea text=new JTextArea(info,18,65);text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setCaretPosition(0);JOptionPane.showMessageDialog(owner,new JScrollPane(text),"World information",JOptionPane.INFORMATION_MESSAGE);}
}
