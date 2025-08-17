package com.krisped.triggers.tick;

import com.krisped.KPWebhookPlugin;
import com.krisped.KPWebhookPreset;
import com.krisped.commands.highlight.HighlightManager;
import com.krisped.commands.highlight.HighlightType;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Handles continuous (every game tick) visual refresh for presets whose triggerType == TICK.
 * Only HIGHLIGHT_* and TEXT_* commands are honored to avoid spam of webhook/chat/screenshot.
 */
@Singleton
public class TickTriggerService {
    private final HighlightManager highlightManager;

    private static final Pattern P_TEXT_UNDER = Pattern.compile("(?i)^TEXT_UNDER\\s+(.*)");
    private static final Pattern P_TEXT_OVER = Pattern.compile("(?i)^TEXT_OVER\\s+(.*)");
    private static final Pattern P_TEXT_CENTER = Pattern.compile("(?i)^TEXT_CENTER\\s+(.*)");

    @Inject
    public TickTriggerService(HighlightManager highlightManager) {
        this.highlightManager = highlightManager;
    }

    public void process(List<KPWebhookPreset> rules, List<KPWebhookPlugin.ActiveOverheadText> overheadTexts) {
        if (rules == null || rules.isEmpty()) return;
        for (KPWebhookPreset r : rules) {
            if (r == null || !r.isActive() || r.getTriggerType() != KPWebhookPreset.TriggerType.TICK) continue;
            String cmds = r.getCommands();
            if (cmds == null || cmds.isBlank()) continue;
            for (String raw : cmds.split("\r?\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.startsWith("HIGHLIGHT_")) {
                    ensurePersistentHighlight(r, upper);
                } else if (P_TEXT_UNDER.matcher(line).find() || P_TEXT_OVER.matcher(line).find() || P_TEXT_CENTER.matcher(line).find()) {
                    handlePersistentText(r, line, overheadTexts);
                }
            }
        }
    }

    private void ensurePersistentHighlight(KPWebhookPreset rule, String lineUpper) {
        if (lineUpper.startsWith("HIGHLIGHT_OUTLINE")) {
            highlightManager.upsertHighlight(rule.getId(), HighlightType.OUTLINE,
                    safe(rule.getHlOutlineWidth(),2), rule.getHlOutlineColor(), bool(rule.getHlOutlineBlink()));
        } else if (lineUpper.startsWith("HIGHLIGHT_TILE")) {
            highlightManager.upsertHighlight(rule.getId(), HighlightType.TILE,
                    safe(rule.getHlTileWidth(),2), rule.getHlTileColor(), bool(rule.getHlTileBlink()));
        } else if (lineUpper.startsWith("HIGHLIGHT_HULL")) {
            highlightManager.upsertHighlight(rule.getId(), HighlightType.HULL,
                    safe(rule.getHlHullWidth(),2), rule.getHlHullColor(), bool(rule.getHlHullBlink()));
        } else if (lineUpper.startsWith("HIGHLIGHT_MINIMAP")) {
            highlightManager.upsertHighlight(rule.getId(), HighlightType.MINIMAP,
                    safe(rule.getHlMinimapWidth(),2), rule.getHlMinimapColor(), bool(rule.getHlMinimapBlink()));
        }
    }

    private void handlePersistentText(KPWebhookPreset rule, String line, List<KPWebhookPlugin.ActiveOverheadText> overheadTexts) {
        java.util.regex.Matcher mUnder = P_TEXT_UNDER.matcher(line);
        java.util.regex.Matcher mOver = P_TEXT_OVER.matcher(line);
        java.util.regex.Matcher mCenter = P_TEXT_CENTER.matcher(line);
        if (mUnder.find()) {
            upsertPersistentOverheadText(rule, mUnder.group(1).trim(), "Under", overheadTexts);
        } else if (mOver.find()) {
            upsertPersistentOverheadText(rule, mOver.group(1).trim(), "Above", overheadTexts);
        } else if (mCenter.find()) {
            upsertPersistentOverheadText(rule, mCenter.group(1).trim(), "Center", overheadTexts);
        }
    }

    private void upsertPersistentOverheadText(KPWebhookPreset rule, String text, String position, List<KPWebhookPlugin.ActiveOverheadText> overheadTexts) {
        if (text == null || text.isBlank()) return;
        String key = "RULE_" + rule.getId() + "_" + position;
        KPWebhookPlugin.ActiveOverheadText existing = null;
        for (KPWebhookPlugin.ActiveOverheadText t : overheadTexts) {
            if (key.equals(t.getKey())) { existing = t; break; }
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
            t.setText(text); t.setColor(color); t.setSize(size); t.setPosition(position); t.setBlink(blink); t.setVisiblePhase(true); t.setBlinkInterval(1); t.setBlinkCounter(0); t.setKey(key);
            t.setBold(bold); t.setItalic(italic); t.setUnderline(underline);
            t.setRemainingTicks(2);
            overheadTexts.add(t);
        } else {
            existing.setText(text);
            existing.setColor(color); existing.setSize(size); existing.setBlink(blink); existing.setBold(bold); existing.setItalic(italic); existing.setUnderline(underline);
            existing.setRemainingTicks(2);
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
