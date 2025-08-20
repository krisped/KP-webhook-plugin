package com.krisped.commands.highlight;

import com.krisped.KPWebhookPlugin;
import com.krisped.KPWebhookPlugin.ActiveOverheadImage;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.NPC;
import net.runelite.api.Actor;
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
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
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
        Actor currentTarget = plugin.getCurrentTargetActor();
        if (!active.isEmpty()) {
            Collection<Player> players = enumeratePlayers();
            Collection<NPC> npcs = enumerateNpcs();
            for (ActiveHighlight h : active) {
                if (!h.isVisiblePhase()) continue;
                if (h.getTargetType() == ActiveHighlight.TargetType.LOCAL_PLAYER || h.getTargetType()==null) {
                    renderHighlightFor(graphics, h, local);
                } else if (h.getTargetType() == ActiveHighlight.TargetType.PLAYER_NAME) {
                    for (Player p : players) {
                        if (p==null || p.getName()==null) continue;
                        String n = p.getName().replace('_',' ').trim().toLowerCase();
                        if (h.getTargetNames()!=null && h.getTargetNames().contains(n)) {
                            renderHighlightFor(graphics, h, p);
                        }
                    }
                } else if (h.getTargetType() == ActiveHighlight.TargetType.NPC_NAME || h.getTargetType()== ActiveHighlight.TargetType.NPC_ID) {
                    for (NPC npc : npcs) {
                        if (npc==null) continue;
                        if (h.getTargetType()== ActiveHighlight.TargetType.NPC_ID) {
                            if (h.getTargetIds()!=null && h.getTargetIds().contains(npc.getId())) {
                                renderHighlightFor(graphics, h, npc);
                            }
                        } else {
                            String n = npc.getName(); if (n!=null) { n = n.replace('_',' ').trim().toLowerCase(); }
                            if (n!=null && h.getTargetNames()!=null && h.getTargetNames().contains(n)) {
                                renderHighlightFor(graphics, h, npc);
                            }
                        }
                    }
                } else if (h.getTargetType() == ActiveHighlight.TargetType.TARGET) {
                    if (currentTarget instanceof Player) {
                        renderHighlightFor(graphics, h, (Player)currentTarget);
                    } else if (currentTarget instanceof NPC) {
                        renderHighlightFor(graphics, h, (NPC)currentTarget);
                    }
                }
            }
        }

        // Overhead texts (also below widgets now)
        java.util.List<KPWebhookPlugin.ActiveOverheadText> texts = plugin.getOverheadTexts();
        if (!texts.isEmpty()) {
            Collection<Player> players = enumeratePlayers();
            Collection<NPC> npcs = enumerateNpcs();
            for (KPWebhookPlugin.ActiveOverheadText aot : texts) {
                if (!aot.isVisiblePhase()) continue;
                if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.LOCAL_PLAYER || aot.getTargetType()==null) {
                    drawTextOverEntity(graphics, local, aot);
                } else if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.PLAYER_NAME) {
                    for (Player p : players) {
                        if (p==null||p.getName()==null) continue;
                        String n = p.getName().replace('_',' ').trim().toLowerCase();
                        if (aot.getTargetNames()!=null && aot.getTargetNames().contains(n)) drawTextOverEntity(graphics, p, aot);
                    }
                } else if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.NPC_ID || aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.NPC_NAME) {
                    for (NPC npc : npcs) {
                        if (npc==null) continue;
                        if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.NPC_ID) {
                            if (aot.getTargetIds()!=null && aot.getTargetIds().contains(npc.getId())) drawTextOverEntity(graphics, npc, aot);
                        } else {
                            String n = npc.getName(); if (n!=null){ n=n.replace('_',' ').trim().toLowerCase(); }
                            if (n!=null && aot.getTargetNames()!=null && aot.getTargetNames().contains(n)) drawTextOverEntity(graphics, npc, aot);
                        }
                    }
                } else if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.TARGET) {
                    if (currentTarget instanceof Player) drawTextOverEntity(graphics, (Player)currentTarget, aot);
                    else if (currentTarget instanceof NPC) drawTextOverEntity(graphics, (NPC)currentTarget, aot);
                }
            }
        }
        // Overhead images
        java.util.List<ActiveOverheadImage> images = plugin.getOverheadImages();
        if (!images.isEmpty()) {
            Collection<Player> players = enumeratePlayers();
            Collection<NPC> npcs = enumerateNpcs();
            for (ActiveOverheadImage oi : images) {
                if (!oi.isVisiblePhase()) continue;
                if (oi.getTargetType()== ActiveOverheadImage.TargetType.LOCAL_PLAYER || oi.getTargetType()==null) {
                    drawImageOverEntity(graphics, local, oi);
                } else if (oi.getTargetType()== ActiveOverheadImage.TargetType.PLAYER_NAME) {
                    for (Player p : players) {
                        if (p==null || p.getName()==null) continue;
                        String n = p.getName().replace('_',' ').trim().toLowerCase();
                        if (oi.getTargetNames()!=null && oi.getTargetNames().contains(n)) drawImageOverEntity(graphics, p, oi);
                    }
                } else if (oi.getTargetType()== ActiveOverheadImage.TargetType.NPC_ID || oi.getTargetType()== ActiveOverheadImage.TargetType.NPC_NAME) {
                    for (NPC npc : npcs) {
                        if (npc==null) continue;
                        if (oi.getTargetType()== ActiveOverheadImage.TargetType.NPC_ID) {
                            if (oi.getTargetIds()!=null && oi.getTargetIds().contains(npc.getId())) drawImageOverEntity(graphics, npc, oi);
                        } else {
                            String n = npc.getName(); if (n!=null) { n = n.replace('_',' ').trim().toLowerCase(); }
                            if (n!=null && oi.getTargetNames()!=null && oi.getTargetNames().contains(n)) drawImageOverEntity(graphics, npc, oi);
                        }
                    }
                } else if (oi.getTargetType()== ActiveOverheadImage.TargetType.TARGET) {
                    if (currentTarget instanceof Player) drawImageOverEntity(graphics, (Player)currentTarget, oi);
                    else if (currentTarget instanceof NPC) drawImageOverEntity(graphics, (NPC)currentTarget, oi);
                }
            }
        }

        // Render MARK_TILE tiles
        java.util.List<KPWebhookPlugin.MarkedTile> tiles = plugin.getMarkedTiles();
        if(tiles!=null && !tiles.isEmpty()){
            Font oldFont = graphics.getFont();
            Font tileFont = oldFont.deriveFont(Font.BOLD, 14f);
            graphics.setFont(tileFont);
            for(KPWebhookPlugin.MarkedTile mt : tiles){
                if(mt==null || mt.getWorldPoint()==null) continue;
                net.runelite.api.coords.WorldPoint wp = mt.getWorldPoint();
                if(wp.getPlane() != client.getPlane()) continue;
                LocalPoint lp = LocalPoint.fromWorld(client, wp);
                if(lp==null) continue;
                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if(poly==null) continue;
                // Border
                graphics.setColor(mt.getColor()!=null? mt.getColor(): Color.RED);
                graphics.setStroke(new BasicStroke(Math.max(1f, mt.getWidth())));
                graphics.draw(poly);
                // Optional text centered inside
                String txt = mt.getText();
                if(txt!=null && !txt.isBlank()){
                    Rectangle b = poly.getBounds();
                    FontMetrics fm = graphics.getFontMetrics();
                    int tw = fm.stringWidth(txt);
                    int th = fm.getAscent();
                    int tx = b.x + (b.width - tw)/2;
                    int ty = b.y + (b.height + th)/2 - 2;
                    // Outline text for readability
                    graphics.setColor(Color.BLACK);
                    for(int dx=-1; dx<=1; dx++) for(int dy=-1; dy<=1; dy++) if(dx!=0 || dy!=0) graphics.drawString(txt, tx+dx, ty+dy);
                    graphics.setColor(mt.getColor()!=null? mt.getColor(): Color.WHITE);
                    graphics.drawString(txt, tx, ty);
                }
            }
            graphics.setFont(oldFont);
        }
        return null;
    }

    private void renderHighlightFor(Graphics2D graphics, ActiveHighlight h, Player p) {
        switch (h.getType()) {
            case OUTLINE:
                int thinWidth = Math.max(1, Math.min(2, h.getWidth()));
                modelOutlineRenderer.drawOutline(p, thinWidth, h.getColor(), 1);
                break;
            case TILE: {
                LocalPoint lp = p.getLocalLocation(); if (lp == null) break;
                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if (poly != null) {
                    graphics.setStroke(new BasicStroke(Math.max(1f, h.getWidth())));
                    graphics.setColor(h.getColor());
                    graphics.draw(poly);
                }
                break; }
            case HULL: {
                Shape hull = p.getConvexHull();
                if (hull != null) {
                    graphics.setStroke(new BasicStroke(Math.max(1f, h.getWidth())));
                    graphics.setColor(h.getColor());
                    graphics.draw(hull);
                }
                break; }
            case MINIMAP:
                // ignored here
                break;
        }
    }
    private void renderHighlightFor(Graphics2D graphics, ActiveHighlight h, NPC npc) {
        switch (h.getType()) {
            case OUTLINE:
                int thinWidth = Math.max(1, Math.min(2, h.getWidth()));
                modelOutlineRenderer.drawOutline(npc, thinWidth, h.getColor(), 1);
                break;
            case TILE: {
                LocalPoint lp = npc.getLocalLocation(); if (lp == null) break;
                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if (poly != null) {
                    graphics.setStroke(new BasicStroke(Math.max(1f, h.getWidth())));
                    graphics.setColor(h.getColor());
                    graphics.draw(poly);
                }
                break; }
            case HULL: {
                Shape hull = npc.getConvexHull();
                if (hull != null) {
                    graphics.setStroke(new BasicStroke(Math.max(1f, h.getWidth())));
                    graphics.setColor(h.getColor());
                    graphics.draw(hull);
                }
                break; }
            case MINIMAP:
                break;
        }
    }

    private void drawTextOverEntity(Graphics2D graphics, Actor actor, KPWebhookPlugin.ActiveOverheadText aot) {
        if (actor == null) return;
        // Derive font first (needed for metrics when we use hull-based placement)
        int style = Font.PLAIN;
        if (aot.isBold()) style |= Font.BOLD;
        if (aot.isItalic()) style |= Font.ITALIC;
        Font base = FontManager.getRunescapeBoldFont();
        Font use = base.deriveFont(style, (float) aot.getSize());
        Font old = graphics.getFont();
        graphics.setFont(use);
        FontMetrics fm = graphics.getFontMetrics();
        Shape hull = actor.getConvexHull();
        if (hull != null) {
            Rectangle b = hull.getBounds();
            int xCenter = b.x + b.width / 2;
            int textWidth = fm.stringWidth(aot.getText());
            int ascent = fm.getAscent();
            int drawX = xCenter - textWidth / 2;
            int drawY;
            switch (aot.getPosition()) {
                case "Above":
                    // Slightly above top of model
                    drawY = b.y - 4; // move a few pixels above
                    break;
                case "Center":
                    // Vertically centered in model hull
                    drawY = b.y + (b.height / 2) + (ascent / 2);
                    break;
                case "Under":
                default:
                    // Just below the base of the model
                    drawY = b.y + b.height + ascent;
                    break;
            }
            drawStyledOutlinedString(graphics, aot.getText(), drawX, drawY, aot.getColor(), aot.isUnderline());
            graphics.setFont(old);
            return;
        }
        // Fallback to original projection method if hull unavailable
        LocalPoint lp = actor.getLocalLocation(); if (lp==null) { graphics.setFont(old); return; }
        int logical = actor.getLogicalHeight();
        int zOffset;
        switch (aot.getPosition()) {
            case "Above": zOffset = logical + 20; break;
            case "Center": zOffset = logical / 2; break;
            case "Under": default: zOffset = 0; break;
        }
        net.runelite.api.Point p = Perspective.getCanvasTextLocation(client, graphics, lp, aot.getText(), zOffset);
        if (p != null) {
            drawStyledOutlinedString(graphics, aot.getText(), p.getX(), p.getY(), aot.getColor(), aot.isUnderline());
        }
        graphics.setFont(old);
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

    private void drawImageOverEntity(Graphics2D graphics, Actor actor, ActiveOverheadImage oi) {
        if (actor == null || oi == null || oi.getImage()==null) return;
        Shape hull = actor.getConvexHull();
        BufferedImage img = oi.getImage();
        int drawX, drawY;
        if (hull != null) {
            Rectangle b = hull.getBounds();
            drawX = b.x + b.width/2 - img.getWidth()/2;
            switch (oi.getPosition()) {
                case "Above":
                    drawY = b.y - img.getHeight() - 4; break;
                case "Center":
                    drawY = b.y + (b.height/2) - (img.getHeight()/2); break;
                case "Under":
                default:
                    drawY = b.y + b.height + 4; break;
            }
            graphics.drawImage(img, drawX, drawY, null);
            return;
        }
        LocalPoint lp = actor.getLocalLocation(); if (lp==null) return;
        int logical = actor.getLogicalHeight();
        int zOffset;
        switch (oi.getPosition()) {
            case "Above": zOffset = logical + 40; break; // a bit higher for icon
            case "Center": zOffset = logical / 2; break;
            case "Under": default: zOffset = 0; break;
        }
        // Corrected parameter order: (client, localPoint, image, zOffset)
        net.runelite.api.Point p = Perspective.getCanvasImageLocation(client, lp, img, zOffset);
        if (p != null) {
            graphics.drawImage(img, p.getX() - img.getWidth()/2, p.getY() - img.getHeight()/2, null);
        }
    }

    // Reflection-based world view enumeration (fallback to deprecated methods if necessary)
    private Collection<Player> enumeratePlayers() {
        try {
            Method m = client.getClass().getMethod("getTopLevelWorldView");
            Object wv = m.invoke(client);
            if (wv != null) {
                Method mp = wv.getClass().getMethod("getPlayers");
                Object result = mp.invoke(wv);
                if (result instanceof Collection) {
                    @SuppressWarnings("unchecked") Collection<Player> players = (Collection<Player>) result; return players;
                }
            }
        } catch (Exception ignored) {}
        try { return client.getPlayers(); } catch (Exception e) { return Collections.emptyList(); }
    }
    private Collection<NPC> enumerateNpcs() {
        try {
            Method m = client.getClass().getMethod("getTopLevelWorldView");
            Object wv = m.invoke(client);
            if (wv != null) {
                Method mp = wv.getClass().getMethod("getNpcs");
                Object result = mp.invoke(wv);
                if (result instanceof Collection) {
                    @SuppressWarnings("unchecked") Collection<NPC> npcs = (Collection<NPC>) result; return npcs;
                }
            }
        } catch (Exception ignored) {}
        try { return client.getNpcs(); } catch (Exception e) { return Collections.emptyList(); }
    }
}
