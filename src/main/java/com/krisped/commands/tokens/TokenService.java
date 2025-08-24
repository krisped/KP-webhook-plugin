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
    private String lastInteractionNameLower = ""; // new raw lower name

    // New: last item spawn (name + world point)
    @Getter private String lastItemSpawnName = ""; // raw item name
    private net.runelite.api.coords.WorldPoint lastItemSpawnPoint = null;
    // New: last loot drop (after min value filter)
    @Getter private String lastLootDropName = ""; // raw item name
    private net.runelite.api.coords.WorldPoint lastLootDropPoint = null;

    public net.runelite.api.coords.WorldPoint getLastItemSpawnPoint(){ return lastItemSpawnPoint; }
    public void updateLastItemSpawn(String itemName, net.runelite.api.coords.WorldPoint wp){
        this.lastItemSpawnName = itemName==null?"":itemName.trim();
        this.lastItemSpawnPoint = wp;
    }
    public void updateLastLootDrop(String itemName, net.runelite.api.coords.WorldPoint wp){
        this.lastLootDropName = itemName==null?"":itemName.trim();
        this.lastLootDropPoint = wp;
    }

    /** Aggregated recent spawns (name -> epoch ms) */
    private final LinkedHashMap<String, Long> recentPlayerSpawns = new LinkedHashMap<>();
    private final Map<String,String> recentPlayerSpawnOriginal = new HashMap<>();
    private static final long RECENT_SPAWN_WINDOW_MS = 7000L; // 7s window

    /** Update internal hitsplat state (called from plugin event). */
    public void updateHitsplat(boolean self, boolean target, int amount){
        if(self){ lastHitsplatSelf = amount; lastHitsplatAny = amount; }
        else if(target){ lastHitsplatTarget = amount; lastHitsplatAny = amount; }
    }

    /** New: update spawn/despawn tokens */
    public void updatePlayerSpawnToken(Player p, boolean despawn){
        if(p==null) return; String name = sanitizePlayerName(p.getName()); String low = name.toLowerCase(Locale.ROOT);
        int cb=0; try { cb = p.getCombatLevel(); } catch(Exception ignored){}
        String formatted = name + " (" + cb + ")";
        if(despawn) {
            lastPlayerDespawn = formatted;
            recentPlayerSpawns.remove(low); recentPlayerSpawnOriginal.remove(low);
        } else {
            lastPlayerSpawn = formatted; long now = System.currentTimeMillis(); recentPlayerSpawns.put(low, now); recentPlayerSpawnOriginal.put(low, name); pruneRecentSpawns(now);
        }
    }
    private void pruneRecentSpawns(long now){
        Iterator<Map.Entry<String,Long>> it = recentPlayerSpawns.entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String,Long> e = it.next();
            if(now - e.getValue() > RECENT_SPAWN_WINDOW_MS) it.remove();
        }
    }

    /** Update interaction token when another player begins interacting with local player */
    public void updateInteraction(Player p){
        if(p==null) return; String name = sanitizePlayerName(p.getName()); int cb=0; try { cb=p.getCombatLevel(); } catch(Exception ignored){} lastInteraction = name + " ("+cb+")"; lastInteractionNameLower = name.toLowerCase(java.util.Locale.ROOT); }

    public String getLastInteractionNameLower(){ return lastInteractionNameLower; }

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
        ctx.put("RSN", local); ctx.put("$RSN", local); ctx.put("rsn", local); // new alias for local player name
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
        ctx.put("$WORLD", ctx.get("WORLD")); ctx.put("world", ctx.get("WORLD")); ctx.put("$world", ctx.get("WORLD"));
        // New aggregated spawn list (comma separated names that spawned recently)
        String aggregatedSpawns = buildAggregatedRecentSpawns();
        ctx.put("PLAYER_SPAWN", aggregatedSpawns);
        ctx.put("player_spawn", aggregatedSpawns);
        ctx.put("$PLAYER_SPAWN", aggregatedSpawns);
        ctx.put("$player_spawn", aggregatedSpawns);
        // keep individual last spawn/despawn for backwards compatibility
        ctx.put("LAST_PLAYER_SPAWN", lastPlayerSpawn);
        ctx.put("LAST_PLAYER_DESPAWN", lastPlayerDespawn);
        // Interaction token
        ctx.put("INTERACTION", lastInteraction);
        ctx.put("interaction", lastInteraction);
        ctx.put("$INTERACTION", lastInteraction);
        // New item spawn tokens
        String itemLoc=""; if(lastItemSpawnPoint!=null){ itemLoc= lastItemSpawnPoint.getX()+","+ lastItemSpawnPoint.getY()+","+ lastItemSpawnPoint.getPlane(); }
        ctx.put("ITEM_SPAWN", itemLoc); // location
        ctx.put("item_spawn", itemLoc);
        ctx.put("$ITEM_SPAWN", lastItemSpawnName); // name
        ctx.put("ITEM_SPAWN_NAME", lastItemSpawnName);
        ctx.put("$ITEM_SPAWN_NAME", lastItemSpawnName);
        // New loot drop tokens (location + name)
        String lootLoc=""; if(lastLootDropPoint!=null){ lootLoc = lastLootDropPoint.getX()+","+ lastLootDropPoint.getY()+","+ lastLootDropPoint.getPlane(); }
        ctx.put("LOOT_DROP", lootLoc);
        ctx.put("loot_drop", lootLoc);
        ctx.put("$LOOT_DROP", lastLootDropName);
        ctx.put("LOOT_DROP_NAME", lastLootDropName);
        ctx.put("$LOOT_DROP_NAME", lastLootDropName);
        for(Skill s: Skill.values()){
            try {
                int real = client.getRealSkillLevel(s);
                int boosted = client.getBoostedSkillLevel(s);
                ctx.put("$"+s.name(), String.valueOf(real));
                ctx.put("$CURRENT_"+s.name(), String.valueOf(boosted));
                ctx.put(s.name(), String.valueOf(real));
                ctx.put("CURRENT_"+s.name(), String.valueOf(boosted));
                // lowercase variants
                String low = s.name().toLowerCase(Locale.ROOT);
                ctx.put("$"+low, String.valueOf(real));
                ctx.put("$current_"+low, String.valueOf(boosted));
                ctx.put(low, String.valueOf(real));
                ctx.put("current_"+low, String.valueOf(boosted));
            } catch(Exception ignored){}
        }
        if(skill!=null){
            String sn = skill.name().toLowerCase(Locale.ROOT);
            try { ctx.put("$skill", String.valueOf(client.getRealSkillLevel(skill))); } catch(Exception ignored){ ctx.put("$skill", ""); }
            try { ctx.put("$current_skill", String.valueOf(client.getBoostedSkillLevel(skill))); } catch(Exception ignored){ ctx.put("$current_skill", ""); }
            ctx.put("skill_name", sn);
        }
        return ctx;
    }

    private String buildAggregatedRecentSpawns(){
        long now = System.currentTimeMillis(); pruneRecentSpawns(now); if(recentPlayerSpawns.isEmpty()) return ""; StringBuilder sb=new StringBuilder(); for(String low: recentPlayerSpawns.keySet()){ if(sb.length()>0) sb.append(", "); String orig = recentPlayerSpawnOriginal.getOrDefault(low, low); sb.append(orig); } return sb.toString(); }
    public Set<String> getRecentSpawnLowerNames(){ pruneRecentSpawns(System.currentTimeMillis()); return new HashSet<>(recentPlayerSpawns.keySet()); }

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
