package com.krisped.commands.highlight;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.awt.*;
import java.util.Set;

@Data
@AllArgsConstructor
public class ActiveHighlight {
    private HighlightType type;
    private int remainingTicks;
    private Color color;
    private int width;
    private boolean blink; // toggles visibility each tick
    private boolean visiblePhase;
    private Integer ruleId; // source rule for persistence (TICK trigger)
    private boolean persistent; // if true, manager refreshes instead of normal decay
    // Targeting
    public enum TargetType { LOCAL_PLAYER, PLAYER_NAME, NPC_NAME, NPC_ID, TARGET, FRIEND_LIST, IGNORE_LIST, PARTY_MEMBERS, FRIENDS_CHAT, TEAM_MEMBERS, CLAN_MEMBERS, OTHERS }
    private TargetType targetType; // null => LOCAL_PLAYER fallback
    private Set<String> targetNames; // lowercase names (players or npcs)
    private Set<Integer> targetIds; // npc ids
}
