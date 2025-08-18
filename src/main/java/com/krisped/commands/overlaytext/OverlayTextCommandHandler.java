package com.krisped.commands.overlaytext;

import com.krisped.KPWebhookPreset;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and executes OVERLAY_TEXT commands.
 * Syntax (simple):
 *   OVERLAY_TEXT <text>
 * Optional advanced (all optional, order flexible until text):
 *   OVERLAY_TEXT [DUR=ticks] [COLOR=#RRGGBB] -- actual text here
 * Use -- to force remaining tokens as text if they could be parsed as params.
 */
@Singleton
public class OverlayTextCommandHandler {
    private static final Pattern P_CMD = Pattern.compile("(?i)^OVERLAY_TEXT\\s*(.*)");
    private final OverlayTextManager manager;

    @Inject
    public OverlayTextCommandHandler(OverlayTextManager manager) { this.manager = manager; }

    /**
     * Handle OVERLAY_TEXT line. Uses only preset settings (duration/color/size) from rule.
     * @param line raw command line
     * @param rule preset providing style settings
     * @return true if matched
     */
    public boolean handle(String line, KPWebhookPreset rule) {
        Matcher m = P_CMD.matcher(line);
        if (!m.find()) return false;
        String text = m.group(1)!=null? m.group(1).trim():"";
        if (text.isEmpty()) return true; // allow empty (no-op) but command consumed
        int duration = safe(rule.getOverlayTextDuration(), 100);
        int size = safe(rule.getOverlayTextSize(), 16);
        Color color = parseColor(rule.getOverlayTextColor(), Color.WHITE);
        manager.addForRule(rule!=null?rule.getId():-1, text, duration, color, true, size);
        return true;
    }

    private int safe(Integer v, int def){ return v==null?def:v; }

    private Color parseColor(String hex, Color fallback) {
        if (hex==null || hex.isBlank()) return fallback;
        String h = hex.trim();
        if (!h.startsWith("#")) h = "#"+h;
        if (h.matches("(?i)^#[0-9A-F]{6}$")) {
            try { return new Color(Integer.parseInt(h.substring(1),16)); } catch (Exception ignored) {}
        }
        return fallback;
    }
}
