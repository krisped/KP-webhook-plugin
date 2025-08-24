package com.krisped.commands.tokens;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Central token documentation (names, description, example usage) to keep token related concerns
 * under the commands.tokens package.
 */
public final class TokenDocumentation {
    private TokenDocumentation(){}

    /**
     * Returns an immutable map: token -> [description, example]
     */
    public static Map<String,String[]> getTokens(){
        Map<String,String[]> m = new HashMap<>();
        m.put("$HITSPLAT", new String[]{"Last hitsplat value (self or target depending on trigger).", "TEXT_OVER TARGET $HITSPLAT"});
        m.put("$HITSPLAT_SELF", new String[]{"Last hitsplat on you.", "NOTIFY Self $HITSPLAT_SELF"});
        m.put("$HITSPLAT_TARGET", new String[]{"Last hitsplat on target.", "NOTIFY Target $HITSPLAT_TARGET"});
        m.put("$PLAYER / ${player}", new String[]{"Local player name.", "NOTIFY Hi $PLAYER"});
        m.put("$TARGET / ${TARGET}", new String[]{"Current target name (may be empty).", "TEXT_OVER TARGET $TARGET"});
        m.put("$INTERACTION / ${INTERACTION}", new String[]{"Last player who started interacting with you (Name (Combat)).", "NOTIFY Aggro from $INTERACTION"});
        m.put("$PLAYER_SPAWN / ${PLAYER_SPAWN}", new String[]{"Last player that spawned (Name (Combat)).", "NOTIFY Spawn $PLAYER_SPAWN"});
        m.put("$PLAYER_DESPAWN / ${PLAYER_DESPAWN}", new String[]{"Last player that despawned (Name (Combat)).", "NOTIFY Despawn $PLAYER_DESPAWN"});
        m.put("$WORLD / ${WORLD}", new String[]{"Current world.", "CUSTOM_MESSAGE GAMEMESSAGE World $WORLD"});
        m.put("${current} / ${CURRENT_STAT}", new String[]{"Boosted level (STAT trigger).", "TEXT_OVER TARGET ${current}"});
        m.put("${stat} / ${STAT}", new String[]{"Real level (STAT trigger).", "WEBHOOK Base stat ${stat}"});
        m.put("${value}", new String[]{"Threshold value (stat/var/hitsplat comparison).", "NOTIFY Threshold ${value}"});
        m.put("${widgetGroup}", new String[]{"Widget group id (WIDGET_SPAWN).", "NOTIFY Widget ${widgetGroup}"});
        m.put("${widgetChild}", new String[]{"Widget child id (WIDGET_SPAWN, if set).", "NOTIFY Widget child ${widgetChild}"});
        m.put("${time}", new String[]{"Current ISO timestamp.", "WEBHOOK Timestamp ${time}"});
        m.put("${otherPlayer}", new String[]{"Other player name (PLAYER_* triggers).", "NOTIFY Player ${otherPlayer}"});
        m.put("${otherCombat}", new String[]{"Other player combat level (PLAYER_* triggers).", "NOTIFY Combat ${otherCombat}"});
        m.put("${npcName}", new String[]{"Matched NPC name (NPC_* triggers).", "NOTIFY NPC ${npcName}"});
        m.put("${npcId}", new String[]{"Matched NPC id (NPC_* triggers).", "NOTIFY NPC id ${npcId}"});
        m.put("Skill tokens", new String[]{"Each skill: $SKILL (real), $CURRENT_SKILL (boosted).", "NOTIFY HP $HITPOINTS/$CURRENT_HITPOINTS"});
        m.put("ITEM_SPAWN", new String[]{"World location (x,y,plane) of last matching ground item spawn.", "HIGHLIGHT_LINE ITEM_SPAWN"});
        m.put("$ITEM_SPAWN", new String[]{"Name of last ground item that spawned.", "TEXT_OVER ITEM_SPAWN $ITEM_SPAWN"});
        m.put("LOOT_DROP", new String[]{"World location (x,y,plane) of last loot drop (matches min value filter).", "TEXT_OVER LOCAL_PLAYER Loot at ${LOOT_DROP}"});
        m.put("$LOOT_DROP", new String[]{"Name of last loot drop item (after min value filter).", "NOTIFY Loot $LOOT_DROP"});
        return Collections.unmodifiableMap(m);
    }
}
