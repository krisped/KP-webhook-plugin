package com.krisped.commands.highlight;

import com.krisped.KPWebhookPreset;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.regex.Pattern;

@Singleton
public class HighlightCommandHandler {
    private static final Pattern P_OUTLINE = Pattern.compile("(?i)^HIGHLIGHT_OUTLINE\\b");
    private static final Pattern P_TILE    = Pattern.compile("(?i)^HIGHLIGHT_TILE\\b");
    private static final Pattern P_HULL    = Pattern.compile("(?i)^HIGHLIGHT_HULL\\b");
    private static final Pattern P_MINIMAP = Pattern.compile("(?i)^HIGHLIGHT_MINIMAP\\b");

    private final HighlightManager highlightManager;

    @Inject
    public HighlightCommandHandler(HighlightManager highlightManager) {
        this.highlightManager = highlightManager;
    }

    public boolean handle(String lineUpper, KPWebhookPreset rule) {
        if (P_OUTLINE.matcher(lineUpper).find()) {
            int dur = safe(rule.getHlOutlineDuration(),5);
            if (dur <= 0) {
                highlightManager.upsertHighlight(rule.getId(), HighlightType.OUTLINE,
                        safe(rule.getHlOutlineWidth(),2), rule.getHlOutlineColor(), bool(rule.getHlOutlineBlink()));
            } else {
                highlightManager.addHighlight(HighlightType.OUTLINE,
                        dur,
                        safe(rule.getHlOutlineWidth(),2),
                        rule.getHlOutlineColor(),
                        bool(rule.getHlOutlineBlink()));
            }
            return true;
        }
        if (P_TILE.matcher(lineUpper).find()) {
            int dur = safe(rule.getHlTileDuration(),5);
            if (dur <= 0) {
                highlightManager.upsertHighlight(rule.getId(), HighlightType.TILE,
                        safe(rule.getHlTileWidth(),2), rule.getHlTileColor(), bool(rule.getHlTileBlink()));
            } else {
                highlightManager.addHighlight(HighlightType.TILE,
                        dur,
                        safe(rule.getHlTileWidth(),2),
                        rule.getHlTileColor(),
                        bool(rule.getHlTileBlink()));
            }
            return true;
        }
        if (P_HULL.matcher(lineUpper).find()) {
            int dur = safe(rule.getHlHullDuration(),5);
            if (dur <= 0) {
                highlightManager.upsertHighlight(rule.getId(), HighlightType.HULL,
                        safe(rule.getHlHullWidth(),2), rule.getHlHullColor(), bool(rule.getHlHullBlink()));
            } else {
                highlightManager.addHighlight(HighlightType.HULL,
                        dur,
                        safe(rule.getHlHullWidth(),2),
                        rule.getHlHullColor(),
                        bool(rule.getHlHullBlink()));
            }
            return true;
        }
        if (P_MINIMAP.matcher(lineUpper).find()) {
            int dur = safe(rule.getHlMinimapDuration(),5);
            if (dur <= 0) {
                highlightManager.upsertHighlight(rule.getId(), HighlightType.MINIMAP,
                        safe(rule.getHlMinimapWidth(),2), rule.getHlMinimapColor(), bool(rule.getHlMinimapBlink()));
            } else {
                highlightManager.addHighlight(HighlightType.MINIMAP,
                        dur,
                        safe(rule.getHlMinimapWidth(),2),
                        rule.getHlMinimapColor(),
                        bool(rule.getHlMinimapBlink()));
            }
            return true;
        }
        return false;
    }

    private int safe(Integer v, int def){ return v==null?def:v; }
    private boolean bool(Boolean b){ return b!=null && b; }
}
