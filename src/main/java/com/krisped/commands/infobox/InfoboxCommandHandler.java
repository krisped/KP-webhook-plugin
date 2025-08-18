package com.krisped.commands.infobox;

import com.krisped.KPWebhookPlugin;
import com.krisped.KPWebhookPreset;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified INFOBOX command:
 *   INFOBOX <id>
 * If id is negative => sprite id = abs(id)
 * If id is positive => item id = id
 * Uses preset infoboxDuration / infoboxBlink / infoboxColor.
 */
@Singleton
public class InfoboxCommandHandler {
    // Accept: INFOBOX <id> [optional custom message]
    private static final Pattern P_CMD = Pattern.compile("(?i)^INFOBOX\\s+(-?\\d+)(?:\\s+(.*))?$");

    private final InfoBoxManager infoBoxManager;
    private final ItemManager itemManager;
    private final SpriteManager spriteManager;
    private final KPWebhookPlugin plugin;

    private final List<RuntimeBox> active = new ArrayList<>();
    private final BufferedImage transparent36;

    @Inject
    public InfoboxCommandHandler(InfoBoxManager infoBoxManager,
                                 ItemManager itemManager,
                                 SpriteManager spriteManager,
                                 KPWebhookPlugin plugin) {
        this.infoBoxManager = infoBoxManager;
        this.itemManager = itemManager;
        this.spriteManager = spriteManager;
        this.plugin = plugin;
        transparent36 = new BufferedImage(36,36,BufferedImage.TYPE_INT_ARGB);
    }

    public boolean handle(String line, KPWebhookPreset rule, Map<String,String> ctx) {
        if (line == null) return false;
        Matcher m = P_CMD.matcher(line.trim());
        if (!m.find()) return false;
        int rawId = parseInt(m.group(1), 0);
        String custom = m.group(2) != null ? m.group(2).trim() : null;
        if (rawId == 0) return true; // consumed but nothing produced
        boolean sprite = rawId < 0;
        int id = Math.abs(rawId);
        int duration = rule != null && rule.getInfoboxDuration()!=null? rule.getInfoboxDuration():100;
        BufferedImage icon = sprite ? loadSprite(id) : loadItem(id);
        if (icon == null) return true; // invalid id but treat as consumed
        int ruleId = rule != null? rule.getId(): -1;
        IconInfoBox ib = new IconInfoBox(icon, plugin, duration, (custom!=null && !custom.isEmpty()) ? custom : (sprite?"SPRITE:"+id:"ITEM:"+id), ruleId);
        active.add(new RuntimeBox(ib, ruleId));
        infoBoxManager.addInfoBox(ib);
        return true;
    }

    private BufferedImage loadItem(int itemId) {
        try { return itemManager.getImage(itemId); } catch (Exception e) { return null; }
    }
    private BufferedImage loadSprite(int spriteId) {
        try { return spriteManager.getSprite(spriteId, 0); } catch (Exception e) { return null; }
    }
    private int parseInt(String s, int def){ try { return Integer.parseInt(s); } catch(Exception e){ return def; } }

    public void tick() {
        if (active.isEmpty()) return;
        active.removeIf(rb -> {
            rb.info.remaining--;
            if (rb.info.remaining <= 0) {
                try { infoBoxManager.removeInfoBox(rb.info); } catch (Exception ignored) {}
                return true;
            }
            return false; // no blink logic anymore
        });
    }

    public void clearAll() {
        for (RuntimeBox rb : active) {
            try { infoBoxManager.removeInfoBox(rb.info); } catch (Exception ignored) {}
        }
        active.clear();
    }

    public void removeByRule(int ruleId) {
        if (ruleId < 0 || active.isEmpty()) return;
        active.removeIf(rb -> {
            if (rb.ruleId == ruleId) {
                try { infoBoxManager.removeInfoBox(rb.info); } catch (Exception ignored) {}
                return true;
            }
            return false;
        });
    }

    private static class RuntimeBox { IconInfoBox info; int ruleId; RuntimeBox(IconInfoBox i, int ruleId){ this.info = i; this.ruleId = ruleId; } }

    private static class IconInfoBox extends InfoBox {
        private int remaining;
        private final String tooltip;
        private final int ruleId;
        IconInfoBox(BufferedImage image, KPWebhookPlugin plugin, int duration, String tooltip, int ruleId) {
            super(image, plugin);
            this.remaining = duration;
            this.tooltip = tooltip;
            this.ruleId = ruleId;
        }
        public int getRuleId(){ return ruleId; }
        @Override public String getTooltip() { return tooltip; }
        @Override public String getName() { return "INFOBOX"; }
        @Override public String getText() { return ""; }
        @Override public Color getTextColor() { return Color.WHITE; }
        @Override public BufferedImage getImage() { return getImageOriginal(); }
        private BufferedImage getImageOriginal() { return super.getImage(); }
    }
}
