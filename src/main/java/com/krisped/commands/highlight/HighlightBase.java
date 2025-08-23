package com.krisped.commands.highlight;

import com.krisped.KPWebhookPreset;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Common helper methods for highlight command handlers. */
abstract class HighlightBase {
    protected static class TargetParseResult {
        ActiveHighlight.TargetType targetType = ActiveHighlight.TargetType.LOCAL_PLAYER;
        Set<String> targetNames;
        Set<Integer> targetIds;
    }

    protected TargetParseResult parseTarget(String remainder){
        TargetParseResult res = new TargetParseResult();
        if(remainder==null || remainder.isBlank()) return res;
        String[] toks = remainder.split("\\s+",3);
        if(toks.length>=1){
            String t0 = toks[0].toUpperCase(Locale.ROOT);
            if (t0.equals("LOCAL_PLAYER")) {
                res.targetType = ActiveHighlight.TargetType.LOCAL_PLAYER;
            } else if (t0.equals("PLAYER") && toks.length>=2) {
                res.targetType = ActiveHighlight.TargetType.PLAYER_NAME;
                res.targetNames = new HashSet<>();
                res.targetNames.add(norm(toks[1]));
            } else if (t0.equals("NPC") && toks.length>=2) {
                String spec = toks[1];
                if (spec.matches("\\d+")) {
                    res.targetType = ActiveHighlight.TargetType.NPC_ID;
                    res.targetIds = new HashSet<>();
                    try { res.targetIds.add(Integer.parseInt(spec)); } catch(Exception ignored){}
                } else {
                    res.targetType = ActiveHighlight.TargetType.NPC_NAME;
                    res.targetNames = new HashSet<>();
                    res.targetNames.add(norm(spec));
                }
            } else if (t0.equals("TARGET")) {
                res.targetType = ActiveHighlight.TargetType.TARGET;
            } else if (t0.equals("FRIEND_LIST")) {
                res.targetType = ActiveHighlight.TargetType.FRIEND_LIST;
            } else if (t0.equals("IGNORE_LIST")) {
                res.targetType = ActiveHighlight.TargetType.IGNORE_LIST;
            } else if (t0.equals("PARTY_MEMBERS")) {
                res.targetType = ActiveHighlight.TargetType.PARTY_MEMBERS;
            } else if (t0.equals("FRIENDS_CHAT")) {
                res.targetType = ActiveHighlight.TargetType.FRIENDS_CHAT;
            } else if (t0.equals("TEAM_MEMBERS")) {
                res.targetType = ActiveHighlight.TargetType.TEAM_MEMBERS;
            } else if (t0.equals("CLAN_MEMBERS")) {
                res.targetType = ActiveHighlight.TargetType.CLAN_MEMBERS;
            } else if (t0.equals("OTHERS")) {
                res.targetType = ActiveHighlight.TargetType.OTHERS;
            }
        }
        return res;
    }

    protected int safe(Integer v, int def){ return v==null?def:v; }
    protected boolean bool(Boolean b){ return b!=null && b; }
    private String norm(String s){ return s==null?"":s.replace('_',' ').trim().toLowerCase(Locale.ROOT); }

    protected abstract boolean handle(String line, KPWebhookPreset rule);
}
