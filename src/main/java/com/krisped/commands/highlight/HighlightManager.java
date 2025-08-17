package com.krisped.commands.highlight;

import lombok.Getter;

import javax.inject.Singleton;
import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Singleton
public class HighlightManager {
    @Getter
    private final List<ActiveHighlight> activeHighlights = new ArrayList<>();

    public void addHighlight(HighlightType type, int duration, int width, String colorHex, boolean blink) {
        if (duration <= 0) duration = 1;
        if (width <= 0) width = 1;
        Color c = parseColor(colorHex, "#FFFF00");
        activeHighlights.add(new ActiveHighlight(type, duration, c, width, blink, true));
    }

    public void tick() {
        if (activeHighlights.isEmpty()) return;
        Iterator<ActiveHighlight> it = activeHighlights.iterator();
        while (it.hasNext()) {
            ActiveHighlight h = it.next();
            h.setRemainingTicks(h.getRemainingTicks() - 1);
            if (h.getRemainingTicks() <= 0) {
                it.remove();
                continue;
            }
            if (h.isBlink()) {
                h.setVisiblePhase(!h.isVisiblePhase());
            }
        }
    }

    public void clear() { activeHighlights.clear(); }

    private Color parseColor(String hex, String fallback) {
        if (hex==null || hex.isBlank()) hex=fallback;
        try {
            String h=hex.trim();
            if (!h.startsWith("#")) h="#"+h;
            if (h.length()==7) {
                int r=Integer.parseInt(h.substring(1,3),16);
                int g=Integer.parseInt(h.substring(3,5),16);
                int b=Integer.parseInt(h.substring(5,7),16);
                return new Color(r,g,b);
            }
        } catch (Exception ignored) {}
        return Color.YELLOW;
    }
}

