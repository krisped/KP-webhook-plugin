package com.krisped.commands.highlight;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.List;

/** Overlay that ONLY draws minimap highlights, placed above widgets to appear over minimap. */
@Singleton
public class MinimapHighlightOverlay extends Overlay {
    private final Client client;
    private final HighlightManager highlightManager;

    @Inject
    public MinimapHighlightOverlay(Client client, HighlightManager highlightManager) {
        this.client = client;
        this.highlightManager = highlightManager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS); // ensure dot shows on minimap
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        Player local = client.getLocalPlayer();
        if (local == null) return null;
        List<ActiveHighlight> active = highlightManager.getActiveHighlights();
        if (active.isEmpty()) return null;
        for (ActiveHighlight h : active) {
            if (!h.isVisiblePhase() || h.getType() != HighlightType.MINIMAP) continue;
            net.runelite.api.Point mp = local.getMinimapLocation();
            if (mp == null) continue;
            int size = Math.max(4, h.getWidth() * 2);
            int x = mp.getX() - size / 2;
            int y = mp.getY() - size / 2;
            graphics.setColor(Color.BLACK);
            graphics.fillOval(x - 1, y - 1, size + 2, size + 2);
            graphics.setColor(h.getColor());
            graphics.fillOval(x, y, size, size);
        }
        return null;
    }
}

