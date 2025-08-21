package com.krisped;

import java.util.*;

/**
 * Central editable reference for the Info tab in the Preset dialog.
 * Easy to maintain: just adjust the maps/lists below. Everything renders alphabetically.
 */
public final class KPWebhookInfoContent {
    private KPWebhookInfoContent() {}

    private static class Info {
        final String desc;
        final String example; // may be null
        Info(String desc, String example){ this.desc = desc; this.example = example; }
    }

    public static String buildHtml() {
        String css = "<style>" +
                // enforce white text everywhere using stronger selectors
                ".kpi, .kpi * { color:#FFFFFF !important; background:transparent !important; }" +
                "html, body, h1,h2,h3,h4,h5,h6, div, span, p, code, pre, ul, li, b, strong, i, em { color:#FFFFFF !important; }" +
                "a{color:#FFFFFF !important;text-decoration:none;}" +
                "body{font-family:Arial,'Segoe UI',sans-serif;font-size:12px;line-height:1.42;margin:6px;background:transparent;}" +
                "h2{margin:12px 0 6px 0;font-size:15px;}" +
                "code{font-family:monospace;font-size:11px;padding:0 2px;border:none;}" +
                // Updated sizes here
                ".entry{margin:6px 0 12px 0;}" +
                ".entry .name{font-size:15px;font-weight:bold;margin:0 0 2px 0;}" +
                ".entry .desc{font-size:10px;margin-left:6px;}" +
                ".entry .ex{font-size:9px;margin:2px 0 0 10px;}" +
                ".entry .ex code{font-size:9px;}" +
                ".dim{font-size:9px;margin-top:6px;}" +
                "ul{margin:4px 0 8px 20px;padding:0;} li{margin:2px 0;font-size:10px;}" +
                "pre{margin:6px 0 12px 0;padding:0;font-size:10px;overflow:auto;border:none;}" +
                ".sect{margin:2px 0 10px 0;}" +
                "</style>";

        // COMMANDS (name -> Info)
        Map<String, Info> commands = new HashMap<>();
        commands.put("CUSTOM_MESSAGE <ChatType|id> <text>", new Info("Send a chat line with explicit ChatMessageType enum name, alias or numeric id.", "CUSTOM_MESSAGE GAMEMESSAGE Fight starting!"));
        commands.put("HIGHLIGHT_HULL [target]", new Info("Highlights convex hull around the target entity.", "HIGHLIGHT_HULL TARGET"));
        commands.put("HIGHLIGHT_MINIMAP [target]", new Info("Adds a marker on the minimap (if supported).", "HIGHLIGHT_MINIMAP TARGET"));
        commands.put("HIGHLIGHT_OUTLINE [target]", new Info("Entity outline. Duration 0 = persists until STOP/STOP_RULE.", "HIGHLIGHT_OUTLINE TARGET"));
        commands.put("HIGHLIGHT_SCREEN [target]", new Info("Coloured border around the game frame.", "HIGHLIGHT_SCREEN"));
        commands.put("HIGHLIGHT_TILE [target]", new Info("Coloured tile under the entity.", "HIGHLIGHT_TILE TARGET"));
        commands.put("IMG_CENTER [target] <id>", new Info("Centered overhead image (item id or -sprite id).", "IMG_CENTER TARGET 4151"));
        commands.put("IMG_OVER [target] <id>", new Info("Image above head (item id or -sprite id).", "IMG_OVER TARGET 11802"));
        commands.put("IMG_UNDER [target] <id>", new Info("Image at feet (item id or -sprite id).", "IMG_UNDER 4151"));
        commands.put("INFOBOX <id> [tooltip]", new Info("Shows an infobox with optional tooltip. Duration configured in settings.", "INFOBOX 4151 Whip drop!"));
        commands.put("MARK_TILE <color[:width]> <x,y[,plane]> [label]", new Info("Mark a specific world tile (auto-cleans after configured duration).", "MARK_TILE RED 3212,2884,0 Safespot"));
        commands.put("NOTIFY <text>", new Info("RuneLite + chat notification.", "NOTIFY Boss spawn!"));
        commands.put("OVERLAY_TEXT <text>", new Info("Top centre overlay textbox.", "OVERLAY_TEXT Phase 1"));
        commands.put("SCREENSHOT [caption]", new Info("Takes a screenshot and sends it via webhook (if configured).", "SCREENSHOT TripStart"));
        commands.put("SLEEP <ms>", new Info("Real time delay (milliseconds) before next command.", "SLEEP 1500"));
        commands.put("STOP", new Info("Stops ALL active presets and visuals.", "STOP"));
        commands.put("STOP_RULE [id]", new Info("Stops visuals/sequence for a single preset (default: current).", "STOP_RULE"));
        commands.put("TEXT_CENTER [target] <text>", new Info("Text centered over middle of entity.", "TEXT_CENTER TARGET FOCUS!"));
        commands.put("TEXT_OVER [target] <text>", new Info("Text above entity.", "TEXT_OVER TARGET $TARGET"));
        commands.put("TEXT_UNDER [target] <text>", new Info("Text at feet.", "TEXT_UNDER TARGET Poison!"));
        commands.put("TICK [n]", new Info("Delay of n game ticks (600ms each) – default 1.", "TICK 2"));
        commands.put("TOGGLEGROUP <category> <1|0>", new Info("Enable/disable all presets in category (1=on,0=off).", "TOGGLEGROUP BOSS 0"));
        commands.put("TOGGLEPRESET <title> <1|0>", new Info("Enable/disable preset by exact title (1=on,0=off).", "TOGGLEPRESET WhipOutline 1"));
        commands.put("WEBHOOK <text>", new Info("Send raw text to webhook.", "WEBHOOK Starting fight..."));

        // TRIGGERS (name -> Info)
        Map<String, Info> triggers = new HashMap<>();
        triggers.put("ANIMATION_ANY", new Info("Animation id of any player or NPC matches list.", "ANIMATION_ANY 804"));
        triggers.put("ANIMATION_SELF", new Info("Local player animation id match.", "ANIMATION_SELF 804"));
        triggers.put("ANIMATION_TARGET", new Info("Current target animation id match.", "ANIMATION_TARGET 804"));
        triggers.put("GRAPHIC_ANY", new Info("Spot anim / graphic id on anyone.", "GRAPHIC_ANY 1234"));
        triggers.put("GRAPHIC_SELF", new Info("Local player graphic.", "GRAPHIC_SELF 1234"));
        triggers.put("GRAPHIC_TARGET", new Info("Target graphic.", "GRAPHIC_TARGET 1234"));
        triggers.put("HITSPLAT_SELF", new Info("Hitsplat on you meets comparison.", "HITSPLAT_SELF >=10"));
        triggers.put("HITSPLAT_TARGET", new Info("Hitsplat on target meets comparison.", "HITSPLAT_TARGET >20"));
        triggers.put("IDLE", new Info("Local player idle for X ms.", "IDLE 5000"));
        triggers.put("GEAR_CHANGED", new Info("Utstyr (gear) endres på deg. Valgfri filter-liste: itemId, eksakt navn, delnavn (substring) eller *wildcard*. 'bow' matcher f.eks. Magic shortbow uten *.", "GEAR_CHANGED bow"));
        triggers.put("TARGET_GEAR_CHANGED", new Info("Utstyr endres på ditt target (spillere). Samme syntaks som GEAR_CHANGED (itemId, navn, delnavn eller *wildcard*).", "TARGET_GEAR_CHANGED bow"));
        triggers.put("MANUAL", new Info("Runs only manually (no automatic trigger).", "MANUAL"));
        triggers.put("MESSAGE", new Info("Chat message id / text match.", "MESSAGE You have been poisoned"));
        triggers.put("NPC_DESPAWN", new Info("NPC despawns (id / name list).", "NPC_DESPAWN 5867"));
        triggers.put("NPC_SPAWN", new Info("NPC spawns (id / name list).", "NPC_SPAWN 5867"));
        triggers.put("PLAYER_DESPAWN", new Info("Player leaves scene (name / combat filter).", "PLAYER_DESPAWN PlayerName"));
        triggers.put("PLAYER_SPAWN", new Info("Player appears (name / combat filter).", "PLAYER_SPAWN PlayerName"));
        triggers.put("PROJECTILE_ANY", new Info("Projectile id in list (any direction).", "PROJECTILE_ANY 335"));
        triggers.put("PROJECTILE_SELF", new Info("Projectile towards you.", "PROJECTILE_SELF 335"));
        triggers.put("PROJECTILE_TARGET", new Info("Projectile from you to target OR from target to you (direction logic).", "PROJECTILE_TARGET 335"));
        triggers.put("STAT", new Info("Skill ABOVE / BELOW / LEVEL_UP threshold (basert på ekte nivå).", "STAT ATTACK ABOVE 90"));
        triggers.put("XP_DROP", new Info("XP total for valgt skill er OVER / UNDER terskel (trigges ved XP endring).", "XP_DROP FISHING ABOVE 1000000"));
        triggers.put("TARGET", new Info("Target acquired or lost.", "TARGET"));
        triggers.put("TICK", new Info("Every game tick.", "TICK"));
        triggers.put("VARBIT", new Info("Varbit id == value.", "VARBIT 1234 7"));
        triggers.put("VARPLAYER", new Info("Varplayer id == value.", "VARPLAYER 281 1"));
        triggers.put("WIDGET_SPAWN", new Info("Widget group[:child] loaded.", "WIDGET_SPAWN 593 5"));
        triggers.put("REGION", new Info("Local player enters region id (64x64 world area). Fires once per entry unless force-cancel toggled.", "REGION 12850"));
        triggers.put("INVENTORY_FULL", new Info("Inventory blir fullt. Valgfri filter-liste begrenser til om minst ett av oppførte items er i inv når den blir full (id, navn, substring eller *wildcard*).", "INVENTORY_FULL ore"));
        triggers.put("INVENTORY_ITEM_ADDED", new Info("Item lagt til i inventory matcher filter (id, navn, substring eller *wildcard*).", "INVENTORY_ITEM_ADDED 2351"));
        triggers.put("INVENTORY_CONTAINS_NONE", new Info("Inventory inneholder ingen av oppførte items (id/navn/substr/wildcard). Force-cancel = trigges så lenge det er sant.", "INVENTORY_CONTAINS_NONE food*"));

        // TOKENS (token -> Info)
        Map<String, Info> tokens = new HashMap<>();
        tokens.put("$HITSPLAT", new Info("Last hitsplat value (self or target depending on trigger).", "TEXT_OVER TARGET $HITSPLAT"));
        tokens.put("$HITSPLAT_SELF", new Info("Last hitsplat on you.", "NOTIFY Self $HITSPLAT_SELF"));
        tokens.put("$HITSPLAT_TARGET", new Info("Last hitsplat on target.", "NOTIFY Target $HITSPLAT_TARGET"));
        tokens.put("$PLAYER / ${player}", new Info("Local player name.", "NOTIFY Hi $PLAYER"));
        tokens.put("$TARGET / ${TARGET}", new Info("Current target name (may be empty).", "TEXT_OVER TARGET $TARGET"));
        tokens.put("$WORLD / ${WORLD}", new Info("Current world.", "CUSTOM_MESSAGE GAMEMESSAGE World $WORLD"));
        tokens.put("${current} / ${CURRENT_STAT}", new Info("Boosted level (STAT trigger).", "TEXT_OVER TARGET ${current}"));
        tokens.put("${stat} / ${STAT}", new Info("Real level (STAT trigger).", "WEBHOOK Base stat ${stat}"));
        tokens.put("${value}", new Info("Threshold value (stat/var/hitsplat comparison).", "NOTIFY Threshold ${value}"));
        tokens.put("${widgetGroup}", new Info("Widget group id (WIDGET_SPAWN).", "NOTIFY Widget ${widgetGroup}"));
        tokens.put("${widgetChild}", new Info("Widget child id (WIDGET_SPAWN, if set).", "NOTIFY Widget child ${widgetChild}"));
        tokens.put("${time}", new Info("Current ISO timestamp.", "WEBHOOK Timestamp ${time}"));
        tokens.put("${otherPlayer}", new Info("Other player name (PLAYER_* triggers).", "NOTIFY Player ${otherPlayer}"));
        tokens.put("${otherCombat}", new Info("Other player combat level (PLAYER_* triggers).", "NOTIFY Combat ${otherCombat}"));
        tokens.put("${npcName}", new Info("Matched NPC name (NPC_* triggers).", "NOTIFY NPC ${npcName}"));
        tokens.put("${npcId}", new Info("Matched NPC id (NPC_* triggers).", "NOTIFY NPC id ${npcId}"));
        tokens.put("Skill tokens", new Info("Each skill: $SKILL (real), $CURRENT_SKILL (boosted).", "NOTIFY HP $HITPOINTS/$CURRENT_HITPOINTS"));

        // Target selectors (entity tokens inside commands)
        List<String> targetSelectors = Arrays.asList(
                "LOCAL_PLAYER", "TARGET", "PLAYER <name>", "NPC <name|id>",
                "FRIEND_LIST", "IGNORE_LIST", "FRIENDS_CHAT", "CLAN_MEMBERS", "TEAM_MEMBERS", "PARTY_MEMBERS", "OTHERS"
        );

        StringBuilder cmdHtml = new StringBuilder("<div class='sect'>");
        List<String> ckeys = new ArrayList<>(commands.keySet());
        ckeys.sort(String.CASE_INSENSITIVE_ORDER);
        for (String k : ckeys) cmdHtml.append(entry(k, commands.get(k)));
        cmdHtml.append("</div>");

        StringBuilder trigHtml = new StringBuilder("<div class='sect'>");
        List<String> tkeys = new ArrayList<>(triggers.keySet());
        tkeys.sort(String.CASE_INSENSITIVE_ORDER);
        for (String k : tkeys) trigHtml.append(entry(k, triggers.get(k)));
        trigHtml.append("</div>");

        StringBuilder tokHtml = new StringBuilder("<div class='sect'>");
        List<String> pkeys = new ArrayList<>(tokens.keySet());
        pkeys.sort(String.CASE_INSENSITIVE_ORDER);
        for (String k : pkeys) tokHtml.append(entry(k, tokens.get(k)));
        tokHtml.append("<div class='dim'>All tokens also support {{NAME}} or $NAME variants.</div></div>");

        StringBuilder selectorHtml = new StringBuilder("<div class='sect'><ul>");
        List<String> selSorted = new ArrayList<>(targetSelectors); selSorted.sort(String.CASE_INSENSITIVE_ORDER);
        for (String s : selSorted) selectorHtml.append("<li><code>").append(esc(s)).append("</code></li>");
        selectorHtml.append("</ul><div class='dim'>Optional first parameter for HIGHLIGHT_*, TEXT_* and IMG_* commands. Omitted = LOCAL_PLAYER.</div></div>");

        String examples = "<pre># Example script\n" +
                "NOTIFY Starting outline on target\n" +
                "HIGHLIGHT_OUTLINE TARGET\n" +
                "TEXT_OVER TARGET $TARGET\n" +
                "TICK 3\n" +
                "CUSTOM_MESSAGE GAMEMESSAGE Focusing $TARGET...\n" +
                "SLEEP 1200\n" +
                "SCREENSHOT Phase1\n" +
                "STOP_RULE\n" +
                "</pre>";

        String sequencing = "<div class='sect'><b>Sequencing:</b> Commands run line-by-line. SLEEP (ms) and TICK (game ticks) add delays. STOP / STOP_RULE clears active visuals.</div>";
        String persistence = "<div class='sect'><b>Duration:</b> Duration 0 for HIGHLIGHT_*, TEXT_* and IMG_* = persists until STOP/STOP_RULE. INFOBOX counts down. Others are instantaneous.</div>";

        return "<html><head>" + css + "</head><body><div class='kpi'>" +
                "<h2>Commands</h2>" + cmdHtml +
                "<h2>Triggers</h2>" + trigHtml +
                "<h2>Tokens</h2>" + tokHtml +
                "<h2>Target Selectors</h2>" + selectorHtml +
                sequencing + persistence +
                "<h2>Example</h2>" + examples +
                "</div></body></html>";
    }

    private static String entry(String name, Info info){ if(info==null) return ""; StringBuilder sb=new StringBuilder(); sb.append("<div class='entry'>"); sb.append("<div class='name'><code>").append(esc(name)).append("</code></div>"); if(info.desc!=null) sb.append("<div class='desc'>").append(esc(info.desc)).append("</div>"); if(info.example!=null) sb.append("<div class='ex'><code>").append(esc(info.example)).append("</code></div>"); sb.append("</div>"); return sb.toString(); }
    private static String esc(String s){ if(s==null) return ""; return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }
}
