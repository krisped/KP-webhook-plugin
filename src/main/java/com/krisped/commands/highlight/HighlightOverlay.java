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
import net.runelite.client.util.Text;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.PartyMember;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** Overlay responsible for rendering active highlights and overhead texts. */
@Singleton
public class HighlightOverlay extends Overlay {
    private final KPWebhookPlugin plugin;
    private final Client client;
    private final ModelOutlineRenderer modelOutlineRenderer;
    private final HighlightManager highlightManager;
    private final PartyService partyService; // optional, may be null in some contexts
    private final MarkTileManager markTileManager; // new source for marked tiles

    @Inject
    public HighlightOverlay(KPWebhookPlugin plugin,
                            Client client,
                            ModelOutlineRenderer modelOutlineRenderer,
                            HighlightManager highlightManager,
                            PartyService partyService,
                            MarkTileManager markTileManager) { // added markTileManager
        this.plugin = plugin;
        this.client = client;
        this.modelOutlineRenderer = modelOutlineRenderer;
        this.highlightManager = highlightManager;
        this.partyService = partyService;
        this.markTileManager = markTileManager;
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
                } else if (h.getTargetType() == ActiveHighlight.TargetType.FRIEND_LIST) {
                    for (Player p : players) {
                        if (p==null || p==local || p.getName()==null) continue; // skip local player explicitly
                        try {
                            String raw = p.getName();
                            if (client.isFriended(raw, false) || client.isFriended(normalizePlayer(raw), false)) {
                                renderHighlightFor(graphics, h, p);
                            }
                        } catch (Throwable ignored) { }
                    }
                } else if (h.getTargetType() == ActiveHighlight.TargetType.IGNORE_LIST) {
                    for (Player p : players) {
                        if (p==null || p==local || p.getName()==null) continue;
                        try {
                            String raw = p.getName();
                            if (isIgnoredPlayer(raw) || isIgnoredPlayer(normalizePlayer(raw))) {
                                renderHighlightFor(graphics, h, p);
                            }
                        } catch (Throwable ignored) { }
                    }
                } else if (h.getTargetType() == ActiveHighlight.TargetType.PARTY_MEMBERS) {
                    for (Player p : players) {
                        if (p==null || p==local) continue;
                        if (isPartyMember(p)) {
                            renderHighlightFor(graphics, h, p);
                        }
                    }
                } else if (h.getTargetType() == ActiveHighlight.TargetType.FRIENDS_CHAT) {
                    for (Player p : players) {
                        if (p==null || p==local) continue;
                        if (isFriendsChatMember(p)) {
                            renderHighlightFor(graphics, h, p);
                        }
                    }
                } else if (h.getTargetType() == ActiveHighlight.TargetType.TEAM_MEMBERS) {
                    int myTeam = safeTeam(local);
                    if (myTeam > 0) {
                        for (Player p : players) {
                            if (p==null || p==local) continue;
                            if (safeTeam(p) == myTeam) {
                                renderHighlightFor(graphics, h, p);
                            }
                        }
                    }
                } else if (h.getTargetType() == ActiveHighlight.TargetType.CLAN_MEMBERS) {
                    for (Player p : players) {
                        if (p==null || p==local) continue;
                        if (isClanMember(p)) {
                            renderHighlightFor(graphics, h, p);
                        }
                    }
                } else if (h.getTargetType() == ActiveHighlight.TargetType.OTHERS) {
                    for (Player p : players) {
                        if (p==null || p==local) continue;
                        renderHighlightFor(graphics, h, p);
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
                } else if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.FRIEND_LIST) {
                    for(Player p: players){ if(p==null||p==local||p.getName()==null) continue; try { String raw=p.getName(); if(client.isFriended(raw,false) || client.isFriended(normalizePlayer(raw),false)) drawTextOverEntity(graphics,p,aot);}catch(Exception ignored){} }
                } else if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.IGNORE_LIST) {
                    for(Player p: players){ if(p==null||p==local||p.getName()==null) continue; try { String raw=p.getName(); if(isIgnoredPlayer(raw) || isIgnoredPlayer(normalizePlayer(raw))) drawTextOverEntity(graphics,p,aot);}catch(Exception ignored){} }
                } else if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.PARTY_MEMBERS) {
                    for(Player p: players){ if(p==null||p==local) continue; if(isPartyMember(p)) drawTextOverEntity(graphics,p,aot); }
                } else if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.FRIENDS_CHAT) {
                    for(Player p: players){ if(p==null||p==local) continue; if(isFriendsChatMember(p)) drawTextOverEntity(graphics,p,aot); }
                } else if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.TEAM_MEMBERS) {
                    int myTeam=safeTeam(local); if(myTeam>0){ for(Player p: players){ if(p==null||p==local) continue; if(safeTeam(p)==myTeam) drawTextOverEntity(graphics,p,aot);} }
                } else if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.CLAN_MEMBERS) {
                    for(Player p: players){ if(p==null||p==local) continue; if(isClanMember(p)) drawTextOverEntity(graphics,p,aot); }
                } else if (aot.getTargetType()== KPWebhookPlugin.ActiveOverheadText.TargetType.OTHERS) {
                    for(Player p: players){ if(p==null||p==local) continue; drawTextOverEntity(graphics,p,aot); }
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
                } else if (oi.getTargetType()== ActiveOverheadImage.TargetType.FRIEND_LIST) {
                    for(Player p: players){ if(p==null||p==local||p.getName()==null) continue; try { String raw=p.getName(); if(client.isFriended(raw,false) || client.isFriended(normalizePlayer(raw),false)) drawImageOverEntity(graphics,p,oi);}catch(Exception ignored){} }
                } else if (oi.getTargetType()== ActiveOverheadImage.TargetType.IGNORE_LIST) {
                    for(Player p: players){ if(p==null||p==local||p.getName()==null) continue; try { String raw=p.getName(); if(isIgnoredPlayer(raw) || isIgnoredPlayer(normalizePlayer(raw))) drawImageOverEntity(graphics,p,oi);}catch(Exception ignored){} }
                } else if (oi.getTargetType()== ActiveOverheadImage.TargetType.PARTY_MEMBERS) {
                    for(Player p: players){ if(p==null||p==local) continue; if(isPartyMember(p)) drawImageOverEntity(graphics,p,oi); }
                } else if (oi.getTargetType()== ActiveOverheadImage.TargetType.FRIENDS_CHAT) {
                    for(Player p: players){ if(p==null||p==local) continue; if(isFriendsChatMember(p)) drawImageOverEntity(graphics,p,oi); }
                } else if (oi.getTargetType()== ActiveOverheadImage.TargetType.TEAM_MEMBERS) {
                    int myTeam=safeTeam(local); if(myTeam>0){ for(Player p: players){ if(p==null||p==local) continue; if(safeTeam(p)==myTeam) drawImageOverEntity(graphics,p,oi);} }
                } else if (oi.getTargetType()== ActiveOverheadImage.TargetType.CLAN_MEMBERS) {
                    for(Player p: players){ if(p==null||p==local) continue; if(isClanMember(p)) drawImageOverEntity(graphics,p,oi); }
                } else if (oi.getTargetType()== ActiveOverheadImage.TargetType.OTHERS) {
                    for(Player p: players){ if(p==null||p==local) continue; drawImageOverEntity(graphics,p,oi); }
                }
            }
        }

        // Render MARK_TILE tiles via manager
        java.util.List<MarkTileManager.MarkedTile> tiles = markTileManager.getMarkedTiles();
        if(tiles!=null && !tiles.isEmpty()){
            Font oldFont = graphics.getFont();
            Font tileFont = oldFont.deriveFont(Font.BOLD, 14f);
            graphics.setFont(tileFont);
            for(MarkTileManager.MarkedTile mt : tiles){
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

    private boolean isIgnoredPlayer(String name){
        if(name==null || name.isBlank()) return false;
        String key = sanitizeName(name).toLowerCase();
        ensureIgnoreCache();
        return ignoreNamesLower.contains(key);
    }

    private String normalizePlayer(String n){
        if(n==null) return "";
        try { return Text.removeTags(n).replace('\u00A0',' ').replace('_',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').replace('_',' ').trim(); }
    }

    private String sanitizeName(String n){
        if(n==null) return "";
        try { return Text.removeTags(n).replace('\u00A0',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').trim(); }
    }

    // Ignore name cache (lowercase sanitized)
    private Set<String> ignoreNamesLower = new HashSet<>();
    private long ignoreNamesBuiltAt = 0L;
    private static final long IGNORE_CACHE_MS = 4000L;
    private static final boolean IGNORE_DEBUG = false; // set true for logging

    private void ensureIgnoreCache(){
        long now = System.currentTimeMillis();
        if(now - ignoreNamesBuiltAt < IGNORE_CACHE_MS && !ignoreNamesLower.isEmpty()) return;
        ignoreNamesBuiltAt = now;
        Set<String> out = new HashSet<>();
        // Primary: client.getIgnoreContainer().getIgnores()
        try {
            Object cont = callNoArg(client, "getIgnoreContainer");
            if(cont != null){
                Object ignoresArr = callNoArg(cont, "getIgnores");
                if(ignoresArr != null){
                    collectAny(ignoresArr, out);
                }
                // Some versions might have getNameables
                collectFromContainer(cont, out);
            }
        } catch(Exception ignored){}
        // Direct client.getIgnores()/getIgnored()/getIgnoreList()
        try { Object list = callNoArg(client, "getIgnores"); collectAny(list, out);} catch(Exception ignored){}
        try { Object list2 = callNoArg(client, "getIgnored"); collectAny(list2, out);} catch(Exception ignored){}
        try { Object list3 = callNoArg(client, "getIgnoreList"); collectAny(list3, out);} catch(Exception ignored){}
        // Heuristic fallback: scan methods containing "ignore"
        if(out.isEmpty()){
            Method[] ms = client.getClass().getMethods();
            for(Method m: ms){
                try {
                    String n = m.getName().toLowerCase();
                    if(!n.contains("ignore")) continue;
                    if(m.getParameterCount()!=0) continue;
                    if(m.getReturnType()==Void.TYPE) continue;
                    Object val = m.invoke(client);
                    collectAny(val, out);
                } catch(Exception ignored){}
            }
        }
        if(IGNORE_DEBUG){
            try { System.out.println("[KPWebhook IGNORE] collected=" + out); } catch(Exception ignored){}
        }
        ignoreNamesLower = out; // atomic swap
    }

    private Object callNoArg(Object target, String method){
        if(target==null) return null;
        try { Method m = target.getClass().getMethod(method); return m.invoke(target); } catch(Exception e){ return null; }
    }
    private void collectFromContainer(Object container, Set<String> out){
        if(container==null) return;
        try { Object col = callNoArg(container, "getNameables"); if(col!=null){ collectAny(col, out);} } catch(Exception ignored){}
        try { Object col2 = callNoArg(container, "getMembers"); if(col2!=null){ collectAny(col2, out);} } catch(Exception ignored){}
        collectAny(container, out);
    }
    private void collectAny(Object obj, Set<String> out){
        if(obj==null) return;
        if(obj instanceof Collection){
            Collection coll = (Collection)obj;
            for(Object o: coll){ extractNameable(o, out);}
            return;
        }
        if(obj.getClass().isArray()){
            int len = java.lang.reflect.Array.getLength(obj);
            for(int i=0;i<len;i++){
                Object o = java.lang.reflect.Array.get(obj,i);
                extractNameable(o,out);
            }
            return;
        }
        // Single entry maybe
        extractNameable(obj, out);
    }
    private void extractNameable(Object entry, Set<String> out){
        if(entry==null) return;
        try { Method m = entry.getClass().getMethod("getName"); Object n = m.invoke(entry); if(n instanceof String){ addIgnoreName((String)n,out);} } catch(Exception ignored){}
        try { Method m2 = entry.getClass().getMethod("getPrevName"); Object n2 = m2.invoke(entry); if(n2 instanceof String){ addIgnoreName((String)n2,out);} } catch(Exception ignored){}
        try { Method m3 = entry.getClass().getMethod("getDisplayName"); Object n3 = m3.invoke(entry); if(n3 instanceof String){ addIgnoreName((String)n3,out);} } catch(Exception ignored){}
    }
    private void addIgnoreName(String s, Set<String> out){
        if(s==null) return; String norm = sanitizeName(s).toLowerCase(); if(!norm.isEmpty()) out.add(norm);
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

    private void collectIgnoreNames(Object source, java.util.Set<String> out){
        if(source == null) return;
        try {
            if(source instanceof java.util.Collection){
                for(Object o: (java.util.Collection<?>)source){ extractIgnoreName(o, out); }
                return;
            }
            // Try getNameables()
            try {
                java.lang.reflect.Method gn = source.getClass().getMethod("getNameables");
                Object col = gn.invoke(source);
                if(col instanceof java.util.Collection){
                    for(Object o: (java.util.Collection<?>)col){ extractIgnoreName(o, out); }
                    return;
                }
            } catch (NoSuchMethodException ignored) { }
            // Try to treat it as array
            if(source.getClass().isArray()){
                int len = java.lang.reflect.Array.getLength(source);
                for(int i=0;i<len;i++){ Object o = java.lang.reflect.Array.get(source,i); extractIgnoreName(o,out);} return;
            }
        } catch(Exception ignored) { }
    }

    private void extractIgnoreName(Object entry, java.util.Set<String> out){
        if(entry==null) return;
        try {
            // Primary: getName()
            try {
                java.lang.reflect.Method gn = entry.getClass().getMethod("getName");
                Object nObj = gn.invoke(entry);
                if(nObj instanceof String){ String norm = sanitizeName((String)nObj).toLowerCase(); if(!norm.isEmpty()) out.add(norm); }
            } catch (NoSuchMethodException ignored) { }
            // Previous name maybe
            try {
                java.lang.reflect.Method gp = entry.getClass().getMethod("getPrevName");
                Object nObj = gp.invoke(entry);
                if(nObj instanceof String){ String norm = sanitizeName((String)nObj).toLowerCase(); if(!norm.isEmpty()) out.add(norm); }
            } catch (NoSuchMethodException ignored) { }
        } catch (Exception ignored) {}
    }

    private boolean isPartyMember(Player p){
        if(p==null) return false;
        try {
            if(partyService == null) return false;
            String pn = sanitizeName(p.getName()).toLowerCase();
            for(PartyMember m : partyService.getMembers()){
                if(m==null) continue;
                String mn = sanitizeName(m.getDisplayName()).toLowerCase();
                if(!mn.isEmpty() && mn.equals(pn)) return true;
            }
        } catch(Exception ignored){}
        return false;
    }
    private boolean isFriendsChatMember(Player p){
        if(p==null) return false;
        try { return p.isFriendsChatMember(); } catch(Throwable ignored){}
        // Reflection fallback
        try { java.lang.reflect.Method m = p.getClass().getMethod("isFriendsChatMember"); Object r = m.invoke(p); if(r instanceof Boolean) return (Boolean)r; } catch(Exception ignored){}
        return false;
    }
    private boolean isClanMember(Player p){
        if(p==null) return false;
        try { return p.isClanMember(); } catch(Throwable ignored){}
        try { java.lang.reflect.Method m = p.getClass().getMethod("isClanMember"); Object r = m.invoke(p); if(r instanceof Boolean) return (Boolean)r; } catch(Exception ignored){}
        return false;
    }
    private int safeTeam(Player p){
        if(p==null) return -1;
        try { return p.getTeam(); } catch(Throwable ignored){}
        try { java.lang.reflect.Method m = p.getClass().getMethod("getTeam"); Object r = m.invoke(p); if(r instanceof Integer) return (Integer)r; } catch(Exception ignored){}
        return -1;
    }
}
