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
            highlightManager.addHighlight(HighlightType.OUTLINE,
                    safe(rule.getHlOutlineDuration(),5),
                    safe(rule.getHlOutlineWidth(),2),
                    rule.getHlOutlineColor(),
                    bool(rule.getHlOutlineBlink()));
            return true;
        }
        if (P_TILE.matcher(lineUpper).find()) {
            highlightManager.addHighlight(HighlightType.TILE,
                    safe(rule.getHlTileDuration(),5),
                    safe(rule.getHlTileWidth(),2),
                    rule.getHlTileColor(),
                    bool(rule.getHlTileBlink()));
            return true;
        }
        if (P_HULL.matcher(lineUpper).find()) {
            highlightManager.addHighlight(HighlightType.HULL,
                    safe(rule.getHlHullDuration(),5),
                    safe(rule.getHlHullWidth(),2),
                    rule.getHlHullColor(),
                    bool(rule.getHlHullBlink()));
            return true;
        }
        if (P_MINIMAP.matcher(lineUpper).find()) {
            highlightManager.addHighlight(HighlightType.MINIMAP,
                    safe(rule.getHlMinimapDuration(),5),
                    safe(rule.getHlMinimapWidth(),2),
                    rule.getHlMinimapColor(),
                    bool(rule.getHlMinimapBlink()));
            return true;
        }
        return false;
    }

    private int safe(Integer v, int def){ return v==null?def:v; }
    private boolean bool(Boolean b){ return b!=null && b; }
}
