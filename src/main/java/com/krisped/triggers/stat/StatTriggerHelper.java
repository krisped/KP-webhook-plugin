package com.krisped.triggers.stat;

import com.krisped.KPWebhookPreset;
import net.runelite.api.Client;
import net.runelite.api.Skill;

import java.util.List;

/**
 * Utility helper for STAT trigger initialization tasks.
 */
public final class StatTriggerHelper {
    private StatTriggerHelper() {}

    /**
     * Initializes lastSeenRealLevel for all active STAT presets so they don't
     * fire LEVEL_UP immediately on first StatChanged after plugin startup.
     */
    public static void initializeBaselines(Client client, List<KPWebhookPreset> presets){
        if(client==null || presets==null) return;
        for(KPWebhookPreset p : presets){
            if(p==null) continue;
            if(p.getTriggerType()== KPWebhookPreset.TriggerType.STAT && p.getStatConfig()!=null){
                try {
                    Skill s = p.getStatConfig().getSkill();
                    if(s!=null && p.getLastSeenRealLevel()<0){
                        int real = client.getRealSkillLevel(s);
                        p.setLastSeenRealLevel(real);
                    }
                } catch(Exception ignored){}
            }
        }
    }
}

