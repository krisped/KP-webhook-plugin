package com.krisped.triggers.tick;

import com.krisped.KPWebhookPreset;
import com.krisped.KPWebhookPlugin;
import com.krisped.commands.highlight.HighlightManager;
import com.krisped.commands.highlight.HighlightType;
import com.krisped.commands.tokens.TokenService;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Handles continuous (every game tick) visual refresh for presets whose triggerType == TICK.
 * Only HIGHLIGHT_* and TEXT_* commands are honored to avoid spam of webhook/chat/screenshot.
 */
@Singleton
public class TickTriggerService {
    private final HighlightManager highlightManager;
    private final TokenService tokenService; // new

    private static final Pattern P_TEXT_UNDER = Pattern.compile("(?i)^TEXT_UNDER\\s+(.*)");
    private static final Pattern P_TEXT_OVER = Pattern.compile("(?i)^TEXT_OVER\\s+(.*)");
    private static final Pattern P_TEXT_CENTER = Pattern.compile("(?i)^TEXT_CENTER\\s+(.*)");

    @Inject
    public TickTriggerService(HighlightManager highlightManager, TokenService tokenService) {
        this.highlightManager = highlightManager;
        this.tokenService = tokenService;
    }

    public void process(List<KPWebhookPreset> rules, List<KPWebhookPlugin.ActiveOverheadText> overheadTexts) {
        if (rules == null || rules.isEmpty()) return;
        for (KPWebhookPreset r : rules) {
            if (r == null || !r.isActive() || r.getTriggerType() != KPWebhookPreset.TriggerType.TICK) continue;
            String cmds = r.getCommands();
            if (cmds == null || cmds.isBlank()) continue;
            // Build a fresh context per rule for tokens (target dynamic, skills irrelevant here)
            Map<String,String> ctx = tokenService.buildContext(null,-1,null,null,null, null);
            for (String raw : cmds.split("\r?\n")) {
                String lineOrig = raw.trim();
                if (lineOrig.isEmpty() || lineOrig.startsWith("#")) continue;
                String expanded = tokenService.expand(lineOrig, ctx);
                String upper = expanded.toUpperCase(Locale.ROOT);
                if (upper.startsWith("HIGHLIGHT_")) {
                    parseAndUpsertTargetedHighlight(r, expanded); // use expanded line
                } else if (P_TEXT_UNDER.matcher(expanded).find() || P_TEXT_OVER.matcher(expanded).find() || P_TEXT_CENTER.matcher(expanded).find()) {
                    handlePersistentText(r, expanded, overheadTexts, ctx);
                }
            }
        }
    }

    private void parseAndUpsertTargetedHighlight(KPWebhookPreset rule, String line) {
        // Accept: HIGHLIGHT_<TYPE> ... + new LINE type and PLAYER_SPAWN target
        String upper = line.toUpperCase(Locale.ROOT);
        HighlightType type = null;
        if (upper.startsWith("HIGHLIGHT_OUTLINE")) type = HighlightType.OUTLINE;
        else if (upper.startsWith("HIGHLIGHT_TILE")) type = HighlightType.TILE;
        else if (upper.startsWith("HIGHLIGHT_HULL")) type = HighlightType.HULL;
        else if (upper.startsWith("HIGHLIGHT_MINIMAP")) type = HighlightType.MINIMAP;
        else if (upper.startsWith("HIGHLIGHT_LINE")) type = HighlightType.LINE;
        if (type == null) return;
        String remainder = line.contains(" ")? line.substring(line.indexOf(' ')+1).trim():"";
        com.krisped.commands.highlight.ActiveHighlight.TargetType targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.LOCAL_PLAYER;
        java.util.Set<String> targetNames = null; java.util.Set<Integer> targetIds = null;
        if (!remainder.isEmpty()) {
            String[] toks = remainder.split("\\s+",3);
            if (toks.length >=1) {
                String t0 = toks[0].toUpperCase(Locale.ROOT);
                if (t0.equals("LOCAL_PLAYER")) {
                    targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.LOCAL_PLAYER;
                } else if (t0.equals("PLAYER") && toks.length>=2) {
                    targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.PLAYER_NAME;
                    targetNames = new java.util.HashSet<>(); targetNames.add(normalizeName(toks[1]));
                } else if (t0.equals("NPC") && toks.length>=2) {
                    String spec=toks[1];
                    if (spec.matches("\\d+")) { targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.NPC_ID; targetIds=new java.util.HashSet<>(); try { targetIds.add(Integer.parseInt(spec)); } catch(Exception ignored){} }
                    else { targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.NPC_NAME; targetNames=new java.util.HashSet<>(); targetNames.add(normalizeName(spec)); }
                } else if (t0.equals("TARGET")) {
                    targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.TARGET;
                } else if (t0.equals("FRIEND_LIST")) {
                    targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.FRIEND_LIST;
                } else if (t0.equals("IGNORE_LIST")) {
                    targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.IGNORE_LIST;
                } else if (t0.equals("PARTY_MEMBERS")) {
                    targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.PARTY_MEMBERS;
                } else if (t0.equals("FRIENDS_CHAT")) {
                    targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.FRIENDS_CHAT;
                } else if (t0.equals("TEAM_MEMBERS")) {
                    targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.TEAM_MEMBERS;
                } else if (t0.equals("CLAN_MEMBERS")) {
                    targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.CLAN_MEMBERS;
                } else if (t0.equals("OTHERS")) {
                    targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.OTHERS;
                } else if (t0.equals("PLAYER_SPAWN")) {
                    targetType = com.krisped.commands.highlight.ActiveHighlight.TargetType.PLAYER_SPAWN;
                }
            }
        }
        switch (type) {
            case OUTLINE:
                highlightManager.upsertHighlightTargeted(rule.getId(), HighlightType.OUTLINE, safe(rule.getHlOutlineWidth(),2), rule.getHlOutlineColor(), bool(rule.getHlOutlineBlink()), targetType, targetNames, targetIds); break;
            case TILE:
                highlightManager.upsertHighlightTargeted(rule.getId(), HighlightType.TILE, safe(rule.getHlTileWidth(),2), rule.getHlTileColor(), bool(rule.getHlTileBlink()), targetType, targetNames, targetIds); break;
            case HULL:
                highlightManager.upsertHighlightTargeted(rule.getId(), HighlightType.HULL, safe(rule.getHlHullWidth(),2), rule.getHlHullColor(), bool(rule.getHlHullBlink()), targetType, targetNames, targetIds); break;
            case MINIMAP:
                highlightManager.upsertHighlightTargeted(rule.getId(), HighlightType.MINIMAP, safe(rule.getHlMinimapWidth(),2), rule.getHlMinimapColor(), bool(rule.getHlMinimapBlink()), targetType, targetNames, targetIds); break;
            case LINE:
                highlightManager.upsertHighlightTargeted(rule.getId(), HighlightType.LINE, safe(rule.getHlOutlineWidth(),2), rule.getHlOutlineColor(), bool(rule.getHlOutlineBlink()), targetType, targetNames, targetIds); break;
        }
    }

    private String normalizeName(String n){ if (n==null) return ""; return n.replace('_',' ').trim().toLowerCase(Locale.ROOT); }

    private void handlePersistentText(KPWebhookPreset rule, String line, List<KPWebhookPlugin.ActiveOverheadText> overheadTexts, Map<String,String> ctx) {
        java.util.regex.Matcher mUnder = P_TEXT_UNDER.matcher(line);
        java.util.regex.Matcher mOver = P_TEXT_OVER.matcher(line);
        java.util.regex.Matcher mCenter = P_TEXT_CENTER.matcher(line);
        if (mUnder.find()) {
            upsertPersistentOverheadText(rule, tokenService.expand(mUnder.group(1).trim(), ctx), "Under", overheadTexts, ctx);
        } else if (mOver.find()) {
            upsertPersistentOverheadText(rule, tokenService.expand(mOver.group(1).trim(), ctx), "Above", overheadTexts, ctx);
        } else if (mCenter.find()) {
            upsertPersistentOverheadText(rule, tokenService.expand(mCenter.group(1).trim(), ctx), "Center", overheadTexts, ctx);
        }
    }

    private void upsertPersistentOverheadText(KPWebhookPreset rule, String text, String position, List<KPWebhookPlugin.ActiveOverheadText> overheadTexts, Map<String,String> ctx) {
        if (text == null || text.isBlank()) return;
        String working = text;
        KPWebhookPlugin.ActiveOverheadText.TargetType targetType = KPWebhookPlugin.ActiveOverheadText.TargetType.LOCAL_PLAYER;
        java.util.Set<String> targetNames = null; java.util.Set<Integer> targetIds = null;
        String[] toks = working.split("\\s+",3);
        if (toks.length >=1) {
            String t0 = toks[0].toUpperCase(Locale.ROOT);
            if (t0.equals("TARGET")) { targetType = KPWebhookPlugin.ActiveOverheadText.TargetType.TARGET; working = working.substring(toks[0].length()).trim(); }
            else if (t0.equals("LOCAL_PLAYER")) { working = working.substring(toks[0].length()).trim(); }
            else if (t0.equals("PLAYER") && toks.length>=2) { targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.PLAYER_NAME; targetNames=new java.util.HashSet<>(); targetNames.add(normalizeName(toks[1])); working = working.substring(toks[0].length()+1+toks[1].length()).trim(); }
            else if (t0.equals("NPC") && toks.length>=2) {
                String spec=toks[1];
                if (spec.matches("\\d+")) { targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.NPC_ID; targetIds=new java.util.HashSet<>(); try { targetIds.add(Integer.parseInt(spec)); } catch(Exception ignored){} }
                else { targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.NPC_NAME; targetNames=new java.util.HashSet<>(); targetNames.add(normalizeName(spec)); }
                working = working.substring(toks[0].length()+1+toks[1].length()).trim();
            } else if (t0.equals("FRIEND_LIST")) { targetType = KPWebhookPlugin.ActiveOverheadText.TargetType.FRIEND_LIST; working = working.substring(toks[0].length()).trim(); }
            else if (t0.equals("IGNORE_LIST")) { targetType = KPWebhookPlugin.ActiveOverheadText.TargetType.IGNORE_LIST; working = working.substring(toks[0].length()).trim(); }
            else if (t0.equals("PARTY_MEMBERS")) { targetType = KPWebhookPlugin.ActiveOverheadText.TargetType.PARTY_MEMBERS; working = working.substring(toks[0].length()).trim(); }
            else if (t0.equals("FRIENDS_CHAT")) { targetType = KPWebhookPlugin.ActiveOverheadText.TargetType.FRIENDS_CHAT; working = working.substring(toks[0].length()).trim(); }
            else if (t0.equals("TEAM_MEMBERS")) { targetType = KPWebhookPlugin.ActiveOverheadText.TargetType.TEAM_MEMBERS; working = working.substring(toks[0].length()).trim(); }
            else if (t0.equals("CLAN_MEMBERS")) { targetType = KPWebhookPlugin.ActiveOverheadText.TargetType.CLAN_MEMBERS; working = working.substring(toks[0].length()).trim(); }
            else if (t0.equals("OTHERS")) { targetType = KPWebhookPlugin.ActiveOverheadText.TargetType.OTHERS; working = working.substring(toks[0].length()).trim(); }
            else if (t0.equals("PLAYER_SPAWN")) { targetType = KPWebhookPlugin.ActiveOverheadText.TargetType.PLAYER_SPAWN; working = working.substring(toks[0].length()).trim(); }
        }
        if (working.isEmpty()) return;
        working = tokenService.expand(working, ctx); // final expansion post target removal
        // Build deterministic identity components for de-dup
        String normNames = null;
        if (targetNames != null && !targetNames.isEmpty()) {
            java.util.List<String> list = new java.util.ArrayList<>(targetNames);
            java.util.Collections.sort(list);
            normNames = String.join(",", list);
        }
        String normIds = null;
        if (targetIds != null && !targetIds.isEmpty()) {
            java.util.List<Integer> listI = new java.util.ArrayList<>(targetIds);
            java.util.Collections.sort(listI);
            StringBuilder sbIds = new StringBuilder();
            for (int i=0;i<listI.size();i++){ if(i>0) sbIds.append(','); sbIds.append(listI.get(i)); }
            normIds = sbIds.toString();
        }
        String key = "RULE_" + rule.getId() + "_" + position + "_" + targetType + "_" + (normNames!=null?normNames:"") + "_" + (normIds!=null?normIds:"");
        KPWebhookPlugin.ActiveOverheadText existing = null;
        for (KPWebhookPlugin.ActiveOverheadText t : overheadTexts) {
            if (t==null) continue;
            if (!t.isPersistent()) continue;
            if (t.getRuleId() != rule.getId()) continue;
            if (!position.equals(t.getPosition())) continue;
            if (t.getTargetType() != targetType) continue;
            boolean namesEqual = (t.getTargetNames()==null||t.getTargetNames().isEmpty()) == (targetNames==null||targetNames.isEmpty());
            if (namesEqual && t.getTargetNames()!=null && targetNames!=null) namesEqual = t.getTargetNames().equals(targetNames);
            if (!namesEqual) continue;
            boolean idsEqual = (t.getTargetIds()==null||t.getTargetIds().isEmpty()) == (targetIds==null||targetIds.isEmpty());
            if (idsEqual && t.getTargetIds()!=null && targetIds!=null) idsEqual = t.getTargetIds().equals(targetIds);
            if (!idsEqual) continue;
            existing = t; break;
        }
        Color color = Color.YELLOW; int size=16; boolean blink=false; boolean bold=false; boolean italic=false; boolean underline=false;
        if ("Under".equals(position)) {
            color = parseColor(rule.getTextUnderColor(), color);
            size = safe(rule.getTextUnderSize(),16); blink = bool(rule.getTextUnderBlink()); bold = bool(rule.getTextUnderBold()); italic=bool(rule.getTextUnderItalic()); underline=bool(rule.getTextUnderUnderline());
        } else if ("Above".equals(position)) {
            color = parseColor(rule.getTextOverColor(), color);
            size = safe(rule.getTextOverSize(),16); blink = bool(rule.getTextOverBlink()); bold = bool(rule.getTextOverBold()); italic=bool(rule.getTextOverItalic()); underline=bool(rule.getTextOverUnderline());
        } else if ("Center".equals(position)) {
            color = parseColor(rule.getTextCenterColor(), color);
            size = safe(rule.getTextCenterSize(),16); blink = bool(rule.getTextCenterBlink()); bold = bool(rule.getTextCenterBold()); italic=bool(rule.getTextCenterItalic()); underline=bool(rule.getTextCenterUnderline());
        }
        if (existing == null) {
            KPWebhookPlugin.ActiveOverheadText t = new KPWebhookPlugin.ActiveOverheadText();
            t.setText(working); t.setColor(color); t.setSize(size); t.setPosition(position); t.setBlink(blink);
            t.setVisiblePhase(true); t.setBlinkInterval(blink?2:0); t.setBlinkCounter(0); t.setKey(key);
            t.setBold(bold); t.setItalic(italic); t.setUnderline(underline);
            t.setPersistent(true); t.setRemainingTicks(0); // mark truly persistent so not recreated
            t.setRuleId(rule.getId());
            t.setTargetType(targetType); t.setTargetNames(targetNames); t.setTargetIds(targetIds);
            overheadTexts.add(t);
        } else {
            existing.setText(working);
            existing.setColor(color); existing.setSize(size); existing.setBlink(blink); existing.setBold(bold); existing.setItalic(italic); existing.setUnderline(underline);
            existing.setPersistent(true); existing.setRemainingTicks(0);
            existing.setRuleId(rule.getId());
            existing.setBlinkInterval(blink? (existing.getBlinkInterval()>0? existing.getBlinkInterval():2):0);
        }
    }

    private int safe(Integer v, int def){ return v==null?def:v; }
    private boolean bool(Boolean b){ return b!=null && b; }
    private Color parseColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        String val = hex.trim();
        if (val.startsWith("#")) val = val.substring(1);
        if (val.matches("(?i)^[0-9A-F]{6}$")) {
            try { return new Color(Integer.parseInt(val,16)); } catch (Exception ignored) {}
        }
        return fallback;
    }
}
