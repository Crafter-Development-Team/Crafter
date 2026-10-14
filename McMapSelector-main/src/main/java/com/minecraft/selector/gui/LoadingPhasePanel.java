package com.minecraft.selector.gui;

import javax.swing.*;
import java.awt.*;

/** Two distinct loading presentations; idle occupies no space. All calls run on the EDT. */
public final class LoadingPhasePanel extends JPanel {
    public enum Phase {IDLE, PREPARING, MAP_LOADING}
    private Phase phase=Phase.IDLE;
    private long ticket=Long.MIN_VALUE;
    private final CardLayout cards=new CardLayout();
    private final JPanel preparation=new JPanel(new BorderLayout(0,4));
    private final JTextArea log=new JTextArea(5,80);
    private final JProgressBar resourceProgress=new JProgressBar(0,100);
    private final JProgressBar mapProgress=new JProgressBar(0,100);

    public LoadingPhasePanel() {
        setLayout(cards);
        resourceProgress.setStringPainted(true);mapProgress.setStringPainted(true);
        log.setEditable(false);log.setLineWrap(true);
        JScrollPane scroll=new JScrollPane(log);
        scroll.setBorder(BorderFactory.createTitledBorder("Resource preparation log"));
        preparation.add(resourceProgress,BorderLayout.NORTH);preparation.add(scroll,BorderLayout.CENTER);
        add(preparation,Phase.PREPARING.name());add(mapProgress,Phase.MAP_LOADING.name());
        setVisible(false);
    }
    public void beginPreparation(long request,String message) {
        if(request<ticket) return;
        if(ticket!=request || phase!=Phase.PREPARING) log.setText("");
        ticket=request;phase=Phase.PREPARING;
        resourceProgress.setValue(0);resourceProgress.setIndeterminate(true);resourceProgress.setString(message);
        display();
    }
    public void preparationProgress(long request,int completed,int total,String message) {
        if(request!=ticket || phase!=Phase.PREPARING) return;
        resourceProgress.setIndeterminate(total<=0);
        resourceProgress.setValue(total<=0?0:(int)Math.min(100,100L*completed/total));
        resourceProgress.setString(message);
    }
    public void appendLog(String message) {
        if(phase!=Phase.PREPARING) return;
        log.append(message+"\n");
        if(log.getLineCount()>500) try {log.replaceRange("",0,log.getLineEndOffset(100));}
        catch(javax.swing.text.BadLocationException ignored) {}
        log.setCaretPosition(log.getDocument().getLength());
    }
    public void beginMap(long request,String message) {
        if(request<ticket) return;
        ticket=request;phase=Phase.MAP_LOADING;
        resourceProgress.setIndeterminate(false);
        mapProgress.setValue(0);mapProgress.setIndeterminate(true);mapProgress.setString(message);
        display();
    }
    public void mapProgress(long request,int completed,int total,String message) {
        if(request!=ticket || phase!=Phase.MAP_LOADING) return;
        mapProgress.setIndeterminate(total<=0);
        mapProgress.setValue(total<=0?0:(int)Math.min(100,100L*completed/total));
        mapProgress.setString(message);
    }
    public void finish(long request) {
        if(request!=ticket) return;
        phase=Phase.IDLE;resourceProgress.setIndeterminate(false);mapProgress.setIndeterminate(false);
        setVisible(false);revalidateParent();
    }
    private void display() {cards.show(this,phase.name());setVisible(true);revalidateParent();}
    private void revalidateParent() {if(getParent()!=null) {getParent().revalidate();getParent().repaint();}}
    @Override public Dimension getPreferredSize() {
        if(phase==Phase.IDLE) return new Dimension(0,0);
        return phase==Phase.PREPARING?preparation.getPreferredSize():mapProgress.getPreferredSize();
    }
    @Override public Dimension getMinimumSize() {return getPreferredSize();}
    public Phase getPhase() {return phase;}
    public JProgressBar getResourceProgress() {return resourceProgress;}
    public JProgressBar getMapProgress() {return mapProgress;}
    public JTextArea getLog() {return log;}
}
