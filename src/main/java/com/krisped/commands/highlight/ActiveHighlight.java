package com.krisped.commands.highlight;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.awt.*;

@Data
@AllArgsConstructor
public class ActiveHighlight {
    private HighlightType type;
    private int remainingTicks;
    private Color color;
    private int width;
    private boolean blink; // toggles visibility each tick
    private boolean visiblePhase;
    private Integer ruleId; // source rule for persistence (TICK trigger)
    private boolean persistent; // if true, manager refreshes instead of normal decay
}
