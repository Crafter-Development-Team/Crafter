package com.minecraft.selector.gui;
import com.minecraft.selector.core.*;
import java.awt.*;
import java.util.*;
import javax.swing.*;
/** Counts cached visible columns; uses palette usage sources, never reads MCA on hover. */
public final class ResourceCoveragePanel extends JPanel {
    public static String report(Map<String,Long> counts,BlockColors.Palette palette) {
        Map<String,Object> usage=palette.usageReport();Map<String,Long> namespaces=new TreeMap<>();java.util.List<String> unknown=new ArrayList<>();long total=0,unresolved=0;
        java.util.List<Map.Entry<String,Long>> rows=new ArrayList<>(counts.entrySet());rows.sort((a,b)->Long.compare(b.getValue(),a.getValue()));
        for(Map.Entry<String,Long> e:rows) {
            String id=e.getKey(),key=id.startsWith("minecraft:")?id.substring(10):id;total+=e.getValue();
            Object row=usage.get(id);if(row==null) row=usage.get(key);
            String source=row instanceof Map?String.valueOf(((Map<?,?>)row).get("source")):"unclassified";
            if(source.equals("unknown")||source.equals("unclassified")) {unresolved+=e.getValue();String ns=id.contains(":")?id.split(":",2)[0]:"minecraft";namespaces.merge(ns,e.getValue(),Long::sum);unknown.add(id+"  "+e.getValue()+" columns ("+source+")");}
        }
        StringBuilder out=new StringBuilder("Current viewport cached surface: "+total+" columns; unknown/unclassified: "+unresolved+"\nUnloaded columns excluded.\nNamespaces: "+namespaces+"\n\n");
        for(String line:unknown) out.append(line).append('\n');return out.toString();
    }
    public ResourceCoveragePanel(String report) {
        super(new BorderLayout(4,4));JTextArea text=new JTextArea(report,22,75);text.setEditable(false);add(new JScrollPane(text));JButton copy=new JButton("Copy report");copy.addActionListener(e->{try {java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text.getText()),null);}catch(Exception ex) {copy.setText("Clipboard unavailable");}});add(copy,BorderLayout.SOUTH);
    }
}
