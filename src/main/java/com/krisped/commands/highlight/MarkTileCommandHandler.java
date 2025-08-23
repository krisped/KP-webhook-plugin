package com.krisped.commands.highlight;

import com.krisped.KPWebhookPreset;
import com.krisped.KPWebhookPlugin;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.Locale;

/** Handles MARK_TILE / TILE_MARK* commands. */
@Singleton
public class MarkTileCommandHandler {
    private final Client client;
    private final KPWebhookPlugin plugin; // for target lookup
    private final MarkTileManager markTileManager;

    @Inject
    public MarkTileCommandHandler(Client client, KPWebhookPlugin plugin, MarkTileManager markTileManager){
        this.client = client;
        this.plugin = plugin;
        this.markTileManager = markTileManager;
    }

    public boolean handle(String rawLine, KPWebhookPreset rule){
        if(rawLine==null) return false;
        String line = rawLine.trim();
        if(line.isEmpty()) return false;
        String upper = line.toUpperCase(Locale.ROOT);
        if(!(upper.startsWith("MARK_TILE") || upper.startsWith("TILE_MARKER") || upper.startsWith("TILE_MARK"))) return false;
        try { process(line, rule); } catch(Exception ignored){}
        return true;
    }

    private void process(String line, KPWebhookPreset rule){
        // Syntax: MARK_TILE <color[:width]> <x,y[,plane] | HERE | TARGET> [label]
        String[] parts = line.trim().split("\\s+", 4); // allow label with spaces
        if(parts.length < 3) return; // command + color + coord
        String colorSpec = parts[1];
        String coordSpec = parts[2];
        // FIX: earlier implementation rebuilt label starting after the 2nd space, accidentally including coord spec.
        // With split limit 4, parts[3] already contains the intended label (remaining text). Use it directly.
        String label = (parts.length >= 4) ? parts[3].trim() : null;
        if(label!=null && label.isEmpty()) label = null;
        int width = 2; Color color = parseNamedOrHexColor(colorSpec, Color.RED);
        if(colorSpec.contains(":")){
            String[] cw = colorSpec.split(":",2); if(cw.length==2){ try { width = Math.max(1, Integer.parseInt(cw[1])); } catch(Exception ignored){} }
        }
        WorldPoint wp = determineWorldPoint(coordSpec);
        if(wp==null) return;
        int ruleId = rule!=null? rule.getId(): -1;
        markTileManager.addOrReplace(ruleId, wp, color, label, width);
    }

    private WorldPoint determineWorldPoint(String coordSpec){
        if(coordSpec==null) return null;
        try {
            Player local = client.getLocalPlayer();
            if(coordSpec.equalsIgnoreCase("HERE")){
                if(local!=null) return local.getWorldLocation();
                return null;
            }
            if(coordSpec.equalsIgnoreCase("TARGET")){
                Actor t = plugin.getCurrentTargetActor();
                if(t instanceof Player){ return ((Player)t).getWorldLocation(); }
                if(t instanceof NPC){ return ((NPC)t).getWorldLocation(); }
                return null;
            }
            String[] c = coordSpec.split(",");
            if(c.length>=2){
                int x = Integer.parseInt(c[0]);
                int y = Integer.parseInt(c[1]);
                int plane = (c.length>=3)? Integer.parseInt(c[2]) : (local!=null? local.getWorldLocation().getPlane(): client.getPlane());
                return new WorldPoint(x,y,plane);
            }
        } catch(Exception ignored){}
        return null;
    }

    private int nthIndexOf(String s, char ch, int n){
        int count=0; for(int i=0;i<s.length();i++){ if(s.charAt(i)==ch){ count++; if(count==n) return i; } } return -1; }

    private Color parseNamedOrHexColor(String spec, Color def){
        if(spec==null || spec.isBlank()) return def;
        String raw = spec.split(":",2)[0].trim();
        try {
            String h = raw.startsWith("#")? raw.substring(1): raw;
            if(h.matches("(?i)[0-9A-F]{6}")){ return new Color(Integer.parseInt(h,16)); }
            if(h.matches("(?i)[0-9A-F]{8}")){ return new Color((int)Long.parseLong(h,16), true); }
        } catch(Exception ignored){}
        switch(raw.toUpperCase(Locale.ROOT)){
            case "RED": return Color.RED;
            case "GREEN": return Color.GREEN;
            case "BLUE": return Color.BLUE;
            case "CYAN": return Color.CYAN;
            case "YELLOW": return Color.YELLOW;
            case "MAGENTA": return Color.MAGENTA;
            case "ORANGE": return Color.ORANGE;
            case "PINK": return Color.PINK;
            case "WHITE": return Color.WHITE;
            case "BLACK": return Color.BLACK;
            case "GRAY": case "GREY": return Color.GRAY;
            default: return def;
        }
    }
}
