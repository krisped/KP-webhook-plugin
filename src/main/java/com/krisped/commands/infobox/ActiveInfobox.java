package com.krisped.commands.infobox;

import lombok.Data;

import java.util.List;

/** Immutable-ish runtime state for an active infobox. */
@Data
public class ActiveInfobox {
    private List<String> lines;          // wrapped lines
    private int width;                   // pixel width inc. padding
    private int height;                  // pixel height inc. padding
    private int remainingTicks;          // lifetime counter
    private int padding;                 // padding used when measuring
    private float arc;                   // rounded corner radius
}

