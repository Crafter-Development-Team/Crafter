package com.minecraft.selector.gui;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.swing.*;
import javax.imageio.ImageIO;
public final class ViewExport {
    public static BufferedImage capture(JComponent canvas) {
        if(canvas.getWidth()<1||canvas.getHeight()<1) throw new IllegalArgumentException("Map view has no size");
        Rectangle visible=canvas.getVisibleRect();if(visible.isEmpty()) throw new IllegalArgumentException("Map view is not visible");
        BufferedImage image=new BufferedImage(visible.width,visible.height,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();try {g.translate(-visible.x,-visible.y);g.clip(visible);canvas.printAll(g);}finally {g.dispose();}return image;
    }
    public static void write(BufferedImage image,File file) throws IOException {if(!ImageIO.write(image,"PNG",file)) throw new IOException("PNG writer unavailable");}
}
