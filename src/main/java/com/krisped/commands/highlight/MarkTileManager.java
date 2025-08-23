package com.krisped.commands.highlight;

import lombok.Data;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Singleton;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Singleton
public class MarkTileManager {
    @Data
    public static class MarkedTile {
        private WorldPoint worldPoint;
        private Color color;
        private String text;
        private int width;
        private int ruleId;
    }

    private final List<MarkedTile> markedTiles = new ArrayList<>();

    public List<MarkedTile> getMarkedTiles(){ return Collections.unmodifiableList(markedTiles); }

    public void addOrReplace(int ruleId, WorldPoint wp, Color color, String text, int width){
        if(wp==null) return;
        try { markedTiles.removeIf(existing -> existing!=null && existing.getWorldPoint()!=null && existing.getWorldPoint().equals(wp) && (ruleId<0 || existing.getRuleId()==ruleId)); } catch(Exception ignored){}
        MarkedTile mt = new MarkedTile();
        mt.setWorldPoint(wp); mt.setColor(color); mt.setText(text); mt.setWidth(width); mt.setRuleId(ruleId);
        markedTiles.add(mt);
    }

    public void removeByRule(int ruleId){ try { markedTiles.removeIf(mt-> mt!=null && mt.getRuleId()==ruleId); } catch(Exception ignored){} }
    public void clear(){ markedTiles.clear(); }
}

