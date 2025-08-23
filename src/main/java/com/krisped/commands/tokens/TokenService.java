package com.krisped.commands.tokens;

import com.krisped.KPWebhookPreset;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Centralised token building and expansion service.
 * Extracted from KPWebhookPlugin to reduce file size and improve clarity.
 */
@Singleton
public class TokenService {
    @Inject private Client client;

    @Getter private int lastHitsplatSelf = -1;
    @Getter private int lastHitsplatTarget = -1;
    @Getter private int lastHitsplatAny = -1;
    /** New: last spawn/despawn formatted tokens: "Name (Combat)" */
    @Getter private String lastPlayerSpawn = "";
    @Getter private String lastPlayerDespawn = "";
    // New: last player who started interacting with local player (formatted Name (Combat))
    @Getter private String lastInteraction = "";

    /** Update internal hitsplat state (called from plugin event). */
    public void updateHitsplat(boolean self, boolean target, int amount){
        if(self){ lastHitsplatSelf = amount; lastHitsplatAny = amount; }
        else if(target){ lastHitsplatTarget = amount; lastHitsplatAny = amount; }
    }

    /** New: update spawn/despawn tokens */
    public void updatePlayerSpawnToken(Player p, boolean despawn){
        if(p==null) return;
        String name = sanitizePlayerName(p.getName());
        int cb=0; try { cb = p.getCombatLevel(); } catch(Exception ignored){}
        String formatted = name + " (" + cb + ")";
        if(despawn) lastPlayerDespawn = formatted; else lastPlayerSpawn = formatted;
    }

    /** Update interaction token when another player begins interacting with local player */
    public void updateInteraction(Player p){
        if(p==null) return; String name = sanitizePlayerName(p.getName()); int cb=0; try { cb=p.getCombatLevel(); } catch(Exception ignored){} lastInteraction = name + " ("+cb+")"; }

    private String sanitizePlayerName(String n){
        if(n==null) return "";
        try { String nt = Text.removeTags(n); return nt.replace('\u00A0',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').trim(); }
    }
    private String sanitizeNpcName(String n){
        if(n==null) return "";
        try { String nt = Text.removeTags(n); return nt.replace('\u00A0',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').trim(); }
    }

    /** Build the base replacement context for a rule execution. */
    public Map<String,String> buildContext(Skill skill, int value, KPWebhookPreset.WidgetConfig widgetCfg,
                                           Player otherPlayer, NPC npc, String currentTargetName){
        Map<String,String> ctx = new HashMap<>();
        String local = "Unknown";
        try { if(client.getLocalPlayer()!=null) local = sanitizePlayerName(client.getLocalPlayer().getName()); } catch(Exception ignored){}
        ctx.put("player", local);
        ctx.put("TARGET", currentTargetName!=null? currentTargetName : "");
        ctx.put("$TARGET", currentTargetName!=null? currentTargetName : "");
        ctx.put("stat", skill!=null? skill.name(): "");
        ctx.put("value", value>=0? String.valueOf(value): "");
        try { ctx.put("current", skill!=null? String.valueOf(client.getBoostedSkillLevel(skill)) : ""); } catch(Exception ignored){ ctx.put("current", ""); }
        if(widgetCfg!=null){
            ctx.put("widgetGroup", String.valueOf(widgetCfg.getGroupId()));
            ctx.put("widgetChild", widgetCfg.getChildId()==null? "": String.valueOf(widgetCfg.getChildId()));
        } else {
            ctx.put("widgetGroup", "");
            ctx.put("widgetChild", "");
        }
        ctx.put("time", Instant.now().toString());
        if(otherPlayer!=null){
            ctx.put("otherPlayer", sanitizePlayerName(otherPlayer.getName()));
            try { ctx.put("otherCombat", String.valueOf(otherPlayer.getCombatLevel())); } catch(Exception ignored){ ctx.put("otherCombat", ""); }
        } else { ctx.put("otherPlayer", ""); ctx.put("otherCombat", ""); }
        if(npc!=null){
            ctx.put("npcName", sanitizeNpcName(npc.getName()));
            ctx.put("npcId", String.valueOf(npc.getId()));
        } else { ctx.put("npcName", ""); ctx.put("npcId", ""); }
        // Hitsplat tokens
        ctx.put("HITSPLAT", lastHitsplatAny>=0? String.valueOf(lastHitsplatAny): "");
        ctx.put("HITSPLAT_SELF", lastHitsplatSelf>=0? String.valueOf(lastHitsplatSelf): "");
        ctx.put("HITSPLAT_TARGET", lastHitsplatTarget>=0? String.valueOf(lastHitsplatTarget): "");
        ctx.put("hitsplat", ctx.get("HITSPLAT"));
        ctx.put("hitsplat_self", ctx.get("HITSPLAT_SELF"));
        ctx.put("hitsplat_target", ctx.get("HITSPLAT_TARGET"));
        try { ctx.put("WORLD", String.valueOf(client.getWorld())); } catch(Exception ignored){ ctx.put("WORLD"," "); }
        // New: spawn/despawn tokens
        ctx.put("PLAYER_SPAWN", lastPlayerSpawn);
        ctx.put("player_spawn", lastPlayerSpawn);
        ctx.put("$PLAYER_SPAWN", lastPlayerSpawn);
        ctx.put("PLAYER_DESPAWN", lastPlayerDespawn);
        ctx.put("player_despawn", lastPlayerDespawn);
        ctx.put("$PLAYER_DESPAWN", lastPlayerDespawn);
        // Interaction token
        ctx.put("INTERACTION", lastInteraction);
        ctx.put("interaction", lastInteraction);
        ctx.put("$INTERACTION", lastInteraction);
        for(Skill s: Skill.values()){
            try {
                int real = client.getRealSkillLevel(s);
                int boosted = client.getBoostedSkillLevel(s);
                ctx.put("$"+s.name(), String.valueOf(real));
                ctx.put("$CURRENT_"+s.name(), String.valueOf(boosted));
                ctx.put(s.name(), String.valueOf(real));
                ctx.put("CURRENT_"+s.name(), String.valueOf(boosted));
            } catch(Exception ignored){}
        }
        return ctx;
    }

    /** Expand tokens inside a string. */
    public String expand(String input, Map<String,String> ctx){
        if(input==null || ctx==null || ctx.isEmpty()) return input;
        String out = input;
        List<String> keys = new ArrayList<>(ctx.keySet());
        // Replace longer keys first
        keys.sort((a,b)-> Integer.compare(b.length(), a.length()));
        for(String k: keys){
            String v = ctx.get(k); if(v==null) v="";
            out = out.replace("${"+k+"}", v).replace("{{"+k+"}}", v);
            if(!k.equals(k.toLowerCase(Locale.ROOT))){
                String lk = k.toLowerCase(Locale.ROOT);
                out = out.replace("${"+lk+"}", v).replace("{{"+lk+"}}", v);
            }
            out = safeReplaceDollarToken(out, k, v);
        }
        return out;
    }

    private String safeReplaceDollarToken(String text, String key, String value){
        if(text==null || text.indexOf('$')<0) return text;
        String rawKey = key.startsWith("$") ? key.substring(1) : key; // allow keys stored with leading $
        if(rawKey.isEmpty()) return text;
        String pattern = "(?i)\\$" + Pattern.quote(rawKey) + "(?![A-Za-z0-9_])";
        return text.replaceAll(pattern, java.util.regex.Matcher.quoteReplacement(value));
    }
}
