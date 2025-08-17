package com.krisped.commands.highlight;

import com.krisped.KPWebhookPlugin;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.Perspective;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.List;

/** Overlay responsible for rendering active highlights and overhead texts. */
@Singleton
public class HighlightOverlay extends Overlay {
    private final KPWebhookPlugin plugin;
    private final Client client;
    private final ModelOutlineRenderer modelOutlineRenderer;
    private final HighlightManager highlightManager;

    @Inject
    public HighlightOverlay(KPWebhookPlugin plugin,
                            Client client,
                            ModelOutlineRenderer modelOutlineRenderer,
                            HighlightManager highlightManager) {
        this.plugin = plugin;
        this.client = client;
        this.modelOutlineRenderer = modelOutlineRenderer;
        this.highlightManager = highlightManager;
        setPosition(OverlayPosition.DYNAMIC);
        // Draw BELOW widgets so bank / UI panels are above highlights
        setLayer(OverlayLayer.UNDER_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        Player local = client.getLocalPlayer();
        if (local == null) return null;

        List<ActiveHighlight> active = highlightManager.getActiveHighlights();
        if (!active.isEmpty()) {
            for (ActiveHighlight h : active) {
                if (!h.isVisiblePhase()) continue;
                switch (h.getType()) {
                    case OUTLINE:
                        int thinWidth = Math.max(1, Math.min(2, h.getWidth())); // cap to 1-2px
                        modelOutlineRenderer.drawOutline(local, thinWidth, h.getColor(), 1); // smaller feather
                        break;
                    case TILE: {
                        LocalPoint lp = local.getLocalLocation();
                        if (lp == null) break;
                        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                        if (poly != null) {
                            graphics.setStroke(new BasicStroke(Math.max(1f, h.getWidth())));
                            graphics.setColor(h.getColor());
                            graphics.draw(poly);
                        }
                        break;
                    }
                    case HULL: {
                        Shape hull = local.getConvexHull();
                        if (hull != null) {
                            graphics.setStroke(new BasicStroke(Math.max(1f, h.getWidth())));
                            graphics.setColor(h.getColor());
                            graphics.draw(hull);
                        }
                        break;
                    }
                    case MINIMAP: // handled in separate overlay
                        break;
                }
            }
        }

        // Overhead texts (also below widgets now)
        java.util.List<KPWebhookPlugin.ActiveOverheadText> texts = plugin.getOverheadTexts();
        if (!texts.isEmpty()) {
            LocalPoint lp = local.getLocalLocation();
            if (lp != null) {
                int logical = local.getLogicalHeight();
                for (KPWebhookPlugin.ActiveOverheadText aot : texts) {
                    if (!aot.isVisiblePhase()) continue;
                    int zOffset;
                    switch (aot.getPosition()) {
                        case "Above": zOffset = logical + 50; break;
                        case "Center": zOffset = logical / 2; break;
                        case "Under": default: zOffset = 10; break;
                    }
                    net.runelite.api.Point p = Perspective.getCanvasTextLocation(client, graphics, lp, aot.getText(), zOffset);
                    if (p != null) {
                        int style = Font.PLAIN;
                        if (aot.isBold()) style |= Font.BOLD;
                        if (aot.isItalic()) style |= Font.ITALIC;
                        Font base = FontManager.getRunescapeBoldFont();
                        Font use = base.deriveFont(style, (float) aot.getSize());
                        Font old = graphics.getFont();
                        graphics.setFont(use);
                        drawStyledOutlinedString(graphics, aot.getText(), p.getX(), p.getY(), aot.getColor(), aot.isUnderline());
                        graphics.setFont(old);
                    }
                }
            }
        }
        return null;
    }

    private void drawOutlinedString(Graphics2D g, String s, int x, int y, Color color) {
        g.setColor(Color.BLACK);
        for (int dx=-1; dx<=1; dx++)
            for (int dy=-1; dy<=1; dy++)
                if (dx!=0 || dy!=0)
                    g.drawString(s, x+dx, y+dy);
        g.setColor(color);
        g.drawString(s, x, y);
    }

    private void drawStyledOutlinedString(Graphics2D g, String s, int x, int y, Color color, boolean underline) {
        drawOutlinedString(g, s, x, y, color);
        if (underline) {
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(s);
            int underlineY = y + 2;
            g.setColor(Color.BLACK);
            g.drawLine(x-1, underlineY+1, x+w+1, underlineY+1);
            g.setColor(color);
            g.drawLine(x, underlineY, x+w, underlineY);
        }
    }
}
