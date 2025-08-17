package com.krisped.commands.overlaytext;

import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.List;

/**
 * Renders OVERLAY_TEXT messages as small adaptive boxes using RuneLite overlay system.
 */
@Singleton
public class OverlayTextOverlay extends Overlay {
    private final OverlayTextManager manager;
    private final Client client;

    @Inject
    public OverlayTextOverlay(OverlayTextManager manager, Client client) {
        this.manager = manager;
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g) {
        List<OverlayTextEntry> list = manager.getEntries();
        if (list.isEmpty()) return null;

        int canvasW = client.getCanvas().getWidth();
        int y = 40; // start a bit below top edge
        int paddingX = 8;
        int paddingY = 4;
        int gap = 6;

        for (OverlayTextEntry e : list) {
            int size = Math.max(8, e.getSize());
            Font font = g.getFont().deriveFont(Font.BOLD, (float) size);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            String text = e.getText();
            int textW = fm.stringWidth(text);
            int textH = fm.getAscent();
            int boxW = textW + paddingX * 2;
            int boxH = textH + paddingY * 2;
            int x = (canvasW - boxW) / 2; // center horizontally

            // Background
            g.setColor(new Color(0,0,0,160));
            g.fillRoundRect(x, y, boxW, boxH, 10, 10);
            g.setColor(new Color(255,255,255,100));
            g.drawRoundRect(x, y, boxW, boxH, 10, 10);

            // Text with optional shadow
            int textX = x + paddingX;
            int textY = y + paddingY + fm.getAscent() - 2;
            if (e.isShadow()) {
                g.setColor(Color.BLACK);
                g.drawString(text, textX+1, textY+1);
            }
            g.setColor(e.getColor());
            g.drawString(text, textX, textY);

            y += boxH + gap;
        }
        return null;
    }
}
