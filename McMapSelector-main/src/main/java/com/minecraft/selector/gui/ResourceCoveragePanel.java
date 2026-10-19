package com.minecraft.selector.gui;
import com.minecraft.selector.core.*;
import java.awt.*;
import java.util.*;
import javax.swing.*;
/**
 * Reports the cached surface column count for the viewport plus the palette IDs that no
 * resource source could classify. Per-column block IDs were removed with the +4 MB/tile
 * metadata arrays, so unknown-ID attribution now comes from the palette, not from pixels.
 */
public final class ResourceCoveragePanel extends JPanel {
    public static String report(Map<String,Long> counts,BlockColors.Palette palette) {
        long total=0;for(long value:counts.values()) total+=value;
        Map<String,Object> usage=palette.usageReport();Map<String,Long> namespaces=new TreeMap<>();java.util.List<String> unknown=new ArrayList<>();
        for(Map.Entry<String,Object> e:usage.entrySet()) {
            Object row=e.getValue();String source=row instanceof Map?String.valueOf(((Map<?,?>)row).get("source")):"unclassified";
            if(source.equals("unknown")||source.equals("unclassified")) {
                String ns=e.getKey().contains(":")?e.getKey().split(":",2)[0]:"minecraft";
                namespaces.merge(ns,1L,Long::sum);unknown.add(e.getKey()+"  ("+source+")");
            }
        }
        StringBuilder out=new StringBuilder("Current viewport cached surface: "+total+" columns\nUnloaded columns excluded.\nUnclassified palette IDs: "+unknown.size()+"\nNamespaces: "+namespaces+"\n\n");
        for(String line:unknown) out.append(line).append('\n');return out.toString();
    }
    public ResourceCoveragePanel(String report) {
        super(new BorderLayout(4,4));JTextArea text=new JTextArea(report,22,75);text.setEditable(false);add(new JScrollPane(text));JButton copy=new JButton("Copy report");copy.addActionListener(e->{try {java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text.getText()),null);}catch(Exception ex) {copy.setText("Clipboard unavailable");}});add(copy,BorderLayout.SOUTH);
    }
}
