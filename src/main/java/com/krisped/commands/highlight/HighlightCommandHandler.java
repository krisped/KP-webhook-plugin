package com.krisped.commands.highlight;

import com.krisped.KPWebhookPreset;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.regex.Pattern;

@Singleton
public class HighlightCommandHandler {
    private static final Pattern P_OUTLINE = Pattern.compile("(?i)^HIGHLIGHT_OUTLINE\\b");
    private static final Pattern P_TILE    = Pattern.compile("(?i)^HIGHLIGHT_TILE\\b");
    private static final Pattern P_HULL    = Pattern.compile("(?i)^HIGHLIGHT_HULL\\b");
    private static final Pattern P_MINIMAP = Pattern.compile("(?i)^HIGHLIGHT_MINIMAP\\b");
    private static final Pattern P_LINE    = Pattern.compile("(?i)^HIGHLIGHT_LINE\\b");

    private final HighlightManager highlightManager;

    @Inject
    public HighlightCommandHandler(HighlightManager highlightManager) {
        this.highlightManager = highlightManager;
    }

    public boolean handle(String rawLine, KPWebhookPreset rule) {
        if (rawLine == null) return false;
        String line = rawLine.trim();
        String upper = line.toUpperCase(Locale.ROOT);
        HighlightType type = null;
        if (P_OUTLINE.matcher(upper).find()) type = HighlightType.OUTLINE;
        else if (P_TILE.matcher(upper).find()) type = HighlightType.TILE;
        else if (P_HULL.matcher(upper).find()) type = HighlightType.HULL;
        else if (P_MINIMAP.matcher(upper).find()) type = HighlightType.MINIMAP;
        else if (P_LINE.matcher(upper).find()) type = HighlightType.LINE;
        if (type == null) return false;

        // Parse optional target syntax: HIGHLIGHT_X [LOCAL_PLAYER|PLAYER <name>|NPC <name-or-id>|TARGET|FRIEND_LIST|IGNORE_LIST|PARTY_MEMBERS|FRIENDS_CHAT|TEAM_MEMBERS|CLAN_MEMBERS|OTHERS]
        String remainder = line.contains(" ") ? line.substring(line.indexOf(' ')+1).trim() : "";
        ActiveHighlight.TargetType targetType = ActiveHighlight.TargetType.LOCAL_PLAYER;
        Set<String> targetNames = null;
        Set<Integer> targetIds = null;
        if (!remainder.isEmpty()) {
            String[] toks = remainder.split("\\s+", 3); // at most 3 parts
            if (toks.length >= 1) {
                String t0 = toks[0].toUpperCase(Locale.ROOT);
                if (t0.equals("LOCAL_PLAYER")) {
                    targetType = ActiveHighlight.TargetType.LOCAL_PLAYER;
                } else if (t0.equals("PLAYER") && toks.length >= 2) {
                    targetType = ActiveHighlight.TargetType.PLAYER_NAME;
                    targetNames = new HashSet<>();
                    targetNames.add(normalizeName(toks[1]));
                } else if (t0.equals("NPC") && toks.length >= 2) {
                    String spec = toks[1];
                    if (spec.matches("\\d+")) {
                        targetType = ActiveHighlight.TargetType.NPC_ID;
                        targetIds = new HashSet<>();
                        try { targetIds.add(Integer.parseInt(spec)); } catch (NumberFormatException ignored) {}
                    } else {
                        targetType = ActiveHighlight.TargetType.NPC_NAME;
                        targetNames = new HashSet<>();
                        targetNames.add(normalizeName(spec));
                    }
                } else if (t0.equals("TARGET")) {
                    targetType = ActiveHighlight.TargetType.TARGET;
                } else if (t0.equals("FRIEND_LIST")) {
                    targetType = ActiveHighlight.TargetType.FRIEND_LIST;
                } else if (t0.equals("IGNORE_LIST")) {
                    targetType = ActiveHighlight.TargetType.IGNORE_LIST;
                } else if (t0.equals("PARTY_MEMBERS")) {
                    targetType = ActiveHighlight.TargetType.PARTY_MEMBERS;
                } else if (t0.equals("FRIENDS_CHAT")) {
                    targetType = ActiveHighlight.TargetType.FRIENDS_CHAT;
                } else if (t0.equals("TEAM_MEMBERS")) {
                    targetType = ActiveHighlight.TargetType.TEAM_MEMBERS;
                } else if (t0.equals("CLAN_MEMBERS")) {
                    targetType = ActiveHighlight.TargetType.CLAN_MEMBERS;
                } else if (t0.equals("OTHERS")) {
                    targetType = ActiveHighlight.TargetType.OTHERS;
                } else if (t0.equals("PLAYER_SPAWN")) {
                    targetType = ActiveHighlight.TargetType.PLAYER_SPAWN;
                } else if (t0.equals("INTERACTION")) {
                    targetType = ActiveHighlight.TargetType.INTERACTION;
                } else if (t0.equals("ITEM_SPAWN")) {
                    targetType = ActiveHighlight.TargetType.ITEM_SPAWN;
                } else if (t0.equals("LOOT_DROP")) {
                    targetType = ActiveHighlight.TargetType.LOOT_DROP;
                }
            }
        }

        boolean persistent = durationFor(type, rule) <= 0;
        if (persistent) {
            highlightManager.upsertHighlightTargeted(rule.getId(), type,
                    widthFor(type, rule), colorFor(type, rule), blinkFor(type, rule),
                    targetType, targetNames, targetIds);
        } else {
            // Use rule-associated variant so we can remove immediately on preset deactivation
            highlightManager.addHighlightTargetedForRule(rule.getId(), type, durationFor(type, rule), widthFor(type, rule),
                    colorFor(type, rule), blinkFor(type, rule), targetType, targetNames, targetIds);
        }
        return true;
    }

    private int durationFor(HighlightType t, KPWebhookPreset r) {
        switch (t) {
            case OUTLINE: return safe(r.getHlOutlineDuration(),5);
            case TILE: return safe(r.getHlTileDuration(),5);
            case HULL: return safe(r.getHlHullDuration(),5);
            case MINIMAP: return safe(r.getHlMinimapDuration(),5);
            case LINE: return safe(r.getHlOutlineDuration(),5);
        }
        return 5;
    }
    private int widthFor(HighlightType t, KPWebhookPreset r) {
        switch (t) {
            case OUTLINE: return safe(r.getHlOutlineWidth(),2);
            case TILE: return safe(r.getHlTileWidth(),2);
            case HULL: return safe(r.getHlHullWidth(),2);
            case MINIMAP: return safe(r.getHlMinimapWidth(),2);
            case LINE: return safe(r.getHlOutlineWidth(),2);
        }
        return 2;
    }
    private String colorFor(HighlightType t, KPWebhookPreset r) {
        switch (t) {
            case OUTLINE: return r.getHlOutlineColor();
            case TILE: return r.getHlTileColor();
            case HULL: return r.getHlHullColor();
            case MINIMAP: return r.getHlMinimapColor();
            case LINE: return r.getHlOutlineColor();
        }
        return "#FFFF00";
    }
    private boolean blinkFor(HighlightType t, KPWebhookPreset r) {
        switch (t) {
            case OUTLINE: return bool(r.getHlOutlineBlink());
            case TILE: return bool(r.getHlTileBlink());
            case HULL: return bool(r.getHlHullBlink());
            case MINIMAP: return bool(r.getHlMinimapBlink());
            case LINE: return bool(r.getHlOutlineBlink());
        }
        return false;
    }

    private int safe(Integer v, int def){ return v==null?def:v; }
    private boolean bool(Boolean b){ return b!=null && b; }
    private String normalizeName(String s) { return s==null?"":s.replace('_',' ').trim().toLowerCase(Locale.ROOT); }
}
