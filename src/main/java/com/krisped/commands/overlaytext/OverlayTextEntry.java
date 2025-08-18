package com.krisped.commands.overlaytext;

import lombok.Data;

import java.awt.*;

/**
 * Represents a single overlay text message with remaining duration in game ticks.
 */
@Data
public class OverlayTextEntry {
    private final String text;
    private final Color color;
    private int remainingTicks;
    private final boolean shadow;
    private final int size; // font size
    private final int ruleId; // owning preset id (-1 if ad-hoc)

    public OverlayTextEntry(String text, Color color, boolean shadow, int size, int ruleId) {
        this.text = text;
        this.color = color;
        this.shadow = shadow;
        this.size = size;
        this.ruleId = ruleId;
        this.remainingTicks = 0; // set later by manager
    }

    public boolean tick() {
        remainingTicks--;
        return remainingTicks > 0;
    }
}
