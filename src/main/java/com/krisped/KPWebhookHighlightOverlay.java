package com.krisped;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.api.Perspective;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;

public class KPWebhookHighlightOverlay extends Overlay
{
    private final KPWebhookPlugin plugin;
    private final Client client;
    private final ModelOutlineRenderer modelOutlineRenderer;

    @Inject
    public KPWebhookHighlightOverlay(KPWebhookPlugin plugin,
                                     Client client,
                                     ModelOutlineRenderer modelOutlineRenderer)
    {
        this.plugin = plugin;
        this.client = client;
        this.modelOutlineRenderer = modelOutlineRenderer;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        Player local = client.getLocalPlayer();
        if (local == null) return null;

        List<KPWebhookPlugin.ActiveHighlight> active = plugin.getActiveHighlights();
        if (!active.isEmpty())
        {
            for (KPWebhookPlugin.ActiveHighlight h : active)
            {
                if (!h.isVisiblePhase()) continue;
                switch (h.getType())
                {
                    case OUTLINE:
                        modelOutlineRenderer.drawOutline(local, h.getWidth(), h.getColor(), 2);
                        break;
                    case TILE:
                    {
                        LocalPoint lp = local.getLocalLocation();
                        if (lp == null) break;
                        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                        if (poly != null)
                        {
                            graphics.setStroke(new BasicStroke(Math.max(1f, h.getWidth())));
                            graphics.setColor(h.getColor());
                            graphics.draw(poly);
                        }
                        break;
                    }
                    case HULL:
                    {
                        Shape hull = local.getConvexHull();
                        if (hull != null)
                        {
                            graphics.setStroke(new BasicStroke(Math.max(1f, h.getWidth())));
                            graphics.setColor(h.getColor());
                            graphics.draw(hull);
                        }
                        break;
                    }
                    case MINIMAP:
                        // (Optional future: minimap dot)
                        break;
                }
            }
        }

        // Draw overhead/center/under texts simultaneously
        java.util.List<KPWebhookPlugin.ActiveOverheadText> texts = plugin.getOverheadTexts();
        if (!texts.isEmpty())
        {
            LocalPoint lp = local.getLocalLocation();
            if (lp != null)
            {
                int logical = local.getLogicalHeight();
                for (KPWebhookPlugin.ActiveOverheadText aot : texts)
                {
                    if (!aot.isVisiblePhase()) continue;
                    int zOffset;
                    switch (aot.getPosition())
                    {
                        case "Above": zOffset = logical + 40; break;
                        case "Center": zOffset = logical / 2; break;
                        case "Under": default: zOffset = 20; break;
                    }
                    net.runelite.api.Point p = Perspective.getCanvasTextLocation(client, graphics, lp, aot.getText(), zOffset);
                    if (p != null)
                    {
                        Font base = FontManager.getRunescapeBoldFont();
                        Font use = base.deriveFont((float) aot.getSize());
                        Font old = graphics.getFont();
                        graphics.setFont(use);
                        drawOutlinedString(graphics, aot.getText(), p.getX(), p.getY(), aot.getColor());
                        graphics.setFont(old);
                    }
                }
            }
        }
        return null;
    }

    private void drawOutlinedString(Graphics2D g, String s, int x, int y, Color color)
    {
        g.setColor(Color.BLACK);
        for (int dx=-1; dx<=1; dx++)
            for (int dy=-1; dy<=1; dy++)
                if (dx!=0 || dy!=0)
                    g.drawString(s, x+dx, y+dy);
        g.setColor(color);
        g.drawString(s, x, y);
    }
}