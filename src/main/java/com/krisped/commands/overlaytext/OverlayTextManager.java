package com.krisped.commands.overlaytext;

import lombok.Getter;

import javax.inject.Singleton;
import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Holds active screen overlay text messages.
 */
@Singleton
public class OverlayTextManager {
    @Getter
    private final List<OverlayTextEntry> entries = new ArrayList<>();

    public synchronized void add(String text, int durationTicks, Color color, boolean shadow, int size) {
        // legacy (no rule association)
        addForRule(-1, text, durationTicks, color, shadow, size);
    }

    public synchronized void addForRule(int ruleId, String text, int durationTicks, Color color, boolean shadow, int size) {
        if (text == null || text.isBlank()) return;
        if (durationTicks <= 0) durationTicks = 100; // default ~60s
        if (size <= 0) size = 16;
        OverlayTextEntry e = new OverlayTextEntry(text.trim(), color == null ? Color.WHITE : color, shadow, size, ruleId);
        e.setRemainingTicks(durationTicks);
        entries.add(e);
    }

    public synchronized void tick() {
        if (entries.isEmpty()) return;
        Iterator<OverlayTextEntry> it = entries.iterator();
        while (it.hasNext()) {
            OverlayTextEntry e = it.next();
            if (!e.tick()) {
                it.remove();
            }
        }
    }

    public synchronized void removeByRule(int ruleId) {
        if (entries.isEmpty()) return;
        entries.removeIf(e -> e.getRuleId() == ruleId);
    }

    public synchronized void clear() { entries.clear(); }
}
