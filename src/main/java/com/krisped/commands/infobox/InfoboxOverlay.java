package com.krisped.commands.infobox;

import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.List;

/** Overlay that renders active INFOBOX entries stacked near top-center of the game viewport. */
@Singleton
public class InfoboxOverlay extends Overlay {
    private final InfoboxManager manager;

    @Inject
    public InfoboxOverlay(InfoboxManager manager) {
        this.manager = manager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS); // ensure above most UI
    }

    @Override
    public Dimension render(Graphics2D g) {
        List<ActiveInfobox> list = manager.getActive();
        if (list.isEmpty()) return null;
        // Use RuneLite font for consistency
        Font font = FontManager.getRunescapeBoldFont().deriveFont(Font.PLAIN, 16f);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int canvasW = g.getClipBounds()!=null? g.getClipBounds().width : 765; // fallback width
        int y = 40; // start some px below top
        int gap = 8; // gap between boxes
        for (ActiveInfobox box : list) {
            int w = box.getWidth();
            int h = box.getHeight();
            int x = (canvasW - w)/2; // center horizontally
            // Background
            g.setColor(new Color(0,0,0,170));
            g.fillRoundRect(x, y, w, h, (int)box.getArc(), (int)box.getArc());
            // Border
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(255,255,255,180));
            g.drawRoundRect(x, y, w, h, (int)box.getArc(), (int)box.getArc());
            // Text
            int padding = box.getPadding();
            int lineY = y + padding + fm.getAscent();
            for (String line : box.getLines()) {
                g.setColor(Color.BLACK);
                int textW = fm.stringWidth(line);
                // simple outline (1px) for readability
                for (int dx=-1; dx<=1; dx++)
                    for (int dy=-1; dy<=1; dy++)
                        if (dx!=0 || dy!=0)
                            g.drawString(line, x + padding + (w-2*padding - textW)/2 + dx, lineY + dy);
                g.setColor(Color.WHITE);
                g.drawString(line, x + padding + (w-2*padding - textW)/2, lineY);
                lineY += fm.getHeight() + 2; // include extra spacing used in measurement
            }
            y += h + gap;
        }
        return null;
    }
}

