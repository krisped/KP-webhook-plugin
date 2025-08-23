package com.krisped.commands.highlight;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import com.krisped.commands.tokens.TokenService; // added

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.List;

/** Overlay that ONLY draws minimap highlights, placed above widgets to appear over minimap. */
@Singleton
public class MinimapHighlightOverlay extends Overlay {
    private final Client client;
    private final HighlightManager highlightManager;
    private final TokenService tokenService; // added

    @Inject
    public MinimapHighlightOverlay(Client client, HighlightManager highlightManager, TokenService tokenService) { // updated constructor
        this.client = client;
        this.highlightManager = highlightManager;
        this.tokenService = tokenService; // assign
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS); // ensure dot shows on minimap
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        Player local = client.getLocalPlayer();
        if (local == null) return null;
        List<ActiveHighlight> active = highlightManager.getActiveHighlights();
        if (active.isEmpty()) return null;
        List<Player> players;
        try { players = client.getPlayers(); } catch(Exception e){ players = java.util.Collections.emptyList(); }
        for (ActiveHighlight h : active) {
            if (!h.isVisiblePhase() || h.getType() != HighlightType.MINIMAP) continue;
            Player targetPlayer = local; // default
            ActiveHighlight.TargetType tt = h.getTargetType();
            if(tt == ActiveHighlight.TargetType.INTERACTION && tokenService!=null){
                String last = tokenService.getLastInteractionNameLower();
                if(last!=null && !last.isBlank()){
                    for(Player p: players){ if(p==null||p.getName()==null) continue; String n=p.getName().replace('_',' ').trim().toLowerCase(); if(n.equals(last)){ targetPlayer=p; break; } }
                }
            } else if(tt == ActiveHighlight.TargetType.PLAYER_NAME && h.getTargetNames()!=null && !h.getTargetNames().isEmpty()){
                for(Player p: players){ if(p==null||p.getName()==null) continue; String n=p.getName().replace('_',' ').trim().toLowerCase(); if(h.getTargetNames().contains(n)){ targetPlayer=p; break; } }
            } else if(tt == ActiveHighlight.TargetType.OTHERS){ // pick first other player for minimap (simple heuristic)
                for(Player p: players){ if(p!=null && p!=local){ targetPlayer=p; break; } }
            }
            if(targetPlayer==null) continue;
            net.runelite.api.Point mp = targetPlayer.getMinimapLocation();
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
