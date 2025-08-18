package com.krisped.commands.highlight;

import lombok.Getter;

import javax.inject.Singleton;
import java.awt.Color; // narrowed import to avoid clash with java.awt.List
import java.util.*;

@Singleton
public class HighlightManager {
    @Getter
    private final List<ActiveHighlight> activeHighlights = new ArrayList<>();

    public void addHighlight(HighlightType type, int duration, int width, String colorHex, boolean blink) {
        addHighlightTargeted(type, duration, width, colorHex, blink, ActiveHighlight.TargetType.LOCAL_PLAYER, null, null);
    }

    public void addHighlightTargeted(HighlightType type, int duration, int width, String colorHex, boolean blink,
                                     ActiveHighlight.TargetType targetType,
                                     Set<String> targetNames,
                                     Set<Integer> targetIds) {
        if (duration <= 0) duration = 1;
        if (width <= 0) width = 1;
        Color c = parseColor(colorHex, "#FFFF00");
        activeHighlights.add(new ActiveHighlight(type, duration, c, width, blink, true, null, false, targetType, targetNames, targetIds));
    }

    // New: add non-persistent highlight with rule association for later cleanup
    public void addHighlightTargetedForRule(int ruleId, HighlightType type, int duration, int width, String colorHex, boolean blink,
                                            ActiveHighlight.TargetType targetType,
                                            Set<String> targetNames,
                                            Set<Integer> targetIds) {
        if (duration <= 0) duration = 1;
        if (width <= 0) width = 1;
        Color c = parseColor(colorHex, "#FFFF00");
        activeHighlights.add(new ActiveHighlight(type, duration, c, width, blink, true, ruleId, false, targetType, targetNames, targetIds));
    }

    /** Upsert a persistent highlight keyed by (ruleId,type,target signature). */
    public void upsertHighlight(int ruleId, HighlightType type, int width, String colorHex, boolean blink) {
        upsertHighlightTargeted(ruleId, type, width, colorHex, blink, ActiveHighlight.TargetType.LOCAL_PLAYER, null, null);
    }

    public void upsertHighlightTargeted(int ruleId, HighlightType type, int width, String colorHex, boolean blink,
                                        ActiveHighlight.TargetType targetType,
                                        Set<String> targetNames,
                                        Set<Integer> targetIds) {
        if (width <= 0) width = 1;
        Color c = parseColor(colorHex, "#FFFF00");
        ActiveHighlight found = null;
        for (ActiveHighlight h : activeHighlights) {
            if (Boolean.TRUE.equals(h.isPersistent()) && h.getRuleId()!=null && h.getRuleId()==ruleId && h.getType()==type) {
                // Also require same target signature
                boolean sameTarget = Objects.equals(h.getTargetType(), targetType)
                        && Objects.equals(h.getTargetNames(), targetNames)
                        && Objects.equals(h.getTargetIds(), targetIds);
                if (sameTarget) { found = h; break; }
            }
        }
        if (found == null) {
            activeHighlights.add(new ActiveHighlight(type, 2, c, width, blink, true, ruleId, true, targetType, targetNames, targetIds));
        } else {
            found.setColor(c);
            found.setWidth(width);
            found.setBlink(blink);
            found.setRemainingTicks(2); // refresh lifespan
            // Keep steady visible when not blinking
            if (!blink) found.setVisiblePhase(true);
        }
    }

    /** Remove all persistent highlights belonging to a specific rule. */
    public void removePersistentByRule(int ruleId) {
        Iterator<ActiveHighlight> it = activeHighlights.iterator();
        while (it.hasNext()) {
            ActiveHighlight h = it.next();
            if (h.isPersistent() && h.getRuleId()!=null && h.getRuleId()==ruleId) {
                it.remove();
            }
        }
    }

    // New: remove any highlight (persistent or not) linked to ruleId
    public void removeAllByRule(int ruleId) {
        Iterator<ActiveHighlight> it = activeHighlights.iterator();
        while (it.hasNext()) {
            ActiveHighlight h = it.next();
            if (h.getRuleId()!=null && h.getRuleId()==ruleId) {
                it.remove();
            }
        }
    }

    /** Keep only persistent highlights whose ruleId is in active set. */
    public void cleanupPersistent(Set<Integer> activeRuleIds) {
        Iterator<ActiveHighlight> it = activeHighlights.iterator();
        while (it.hasNext()) {
            ActiveHighlight h = it.next();
            if (h.isPersistent() && (h.getRuleId()==null || !activeRuleIds.contains(h.getRuleId()))) {
                it.remove();
            }
        }
    }

    public void tick() {
        if (activeHighlights.isEmpty()) return;
        Iterator<ActiveHighlight> it = activeHighlights.iterator();
        while (it.hasNext()) {
            ActiveHighlight h = it.next();
            // First handle blink visibility so both persistent & non-persistent stay in sync with game ticks
            if (h.isBlink()) {
                h.setVisiblePhase(!h.isVisiblePhase());
            } else if (!h.isVisiblePhase()) {
                // Ensure visible when not blinking
                h.setVisiblePhase(true);
            }

            if (h.isPersistent()) {
                // Persistent entries are externally refreshed; never decay here
                continue;
            }
            h.setRemainingTicks(h.getRemainingTicks() - 1);
            if (h.getRemainingTicks() <= 0) {
                it.remove();
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
