package com.krisped.commands.infobox;

import lombok.Getter;

import javax.inject.Singleton;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Singleton
public class InfoboxManager {
    @Getter
    private final List<ActiveInfobox> active = new ArrayList<>();

    private static final int DEFAULT_PADDING = 8;
    private static final int MAX_WIDTH = 260; // max interior text width before wrapping
    private static final int LINE_SPACING = 2; // extra pixels between lines

    public void clear() { active.clear(); }

    public void tick() {
        if (active.isEmpty()) return;
        Iterator<ActiveInfobox> it = active.iterator();
        while (it.hasNext()) {
            ActiveInfobox box = it.next();
            box.setRemainingTicks(box.getRemainingTicks() - 1);
            if (box.getRemainingTicks() <= 0) it.remove();
        }
    }

    public void add(String text, int durationTicks) {
        if (text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;
        if (durationTicks <= 0) durationTicks = 100; // fail-safe

        // Measure/wrap using a temporary graphics context
        BufferedImage img = new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            Font font = new Font("Arial", Font.PLAIN, 16); // use a common readable font
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = wrap(text, fm, MAX_WIDTH);
            int maxLineW = 0;
            for (String l : lines) maxLineW = Math.max(maxLineW, fm.stringWidth(l));
            int lineHeight = fm.getHeight();
            int boxW = maxLineW + DEFAULT_PADDING * 2;
            int boxH = lines.size() * lineHeight + (lines.size()-1)*LINE_SPACING + DEFAULT_PADDING * 2;

            ActiveInfobox box = new ActiveInfobox();
            box.setLines(lines);
            box.setWidth(boxW);
            box.setHeight(boxH);
            box.setPadding(DEFAULT_PADDING);
            box.setRemainingTicks(durationTicks);
            box.setArc(14f);
            active.add(box);
        } finally {
            g.dispose();
        }
    }

    private List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            if (line.length() == 0) {
                line.append(w);
                continue;
            }
            String candidate = line + " " + w;
            if (fm.stringWidth(candidate) <= maxWidth) {
                line.append(' ').append(w);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(w);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        // Ensure at least one line
        if (out.isEmpty()) out.add(text);
        return out;
    }
}

