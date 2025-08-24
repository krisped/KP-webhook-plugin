package com.krisped.triggers.stat;

import com.krisped.KPWebhookPreset;
import com.krisped.KPWebhookPlugin;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.api.Client;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

/**
 * Handles STAT trigger evaluation logic (LEVEL_UP / ABOVE / BELOW) separated from main plugin.
 * Uses each preset's lastSeenRealLevel + lastConditionMet for state transitions.
 */
@Singleton
public class StatTriggerService {
    private final Client client;

    @Inject
    public StatTriggerService(Client client){
        this.client = client;
    }

    public void process(StatChanged ev, List<KPWebhookPreset> rules, KPWebhookPlugin plugin){
        if(ev==null || rules==null || rules.isEmpty() || plugin==null) return;
        Skill skill = ev.getSkill();
        int real;
        int boosted;
        try { real = client.getRealSkillLevel(skill); } catch(Exception e){ real = ev.getLevel(); }
        try { boosted = client.getBoostedSkillLevel(skill); } catch(Exception e){ boosted = real; }

        for(KPWebhookPreset r : rules){
            if(r==null || !r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.STAT) continue;
            KPWebhookPreset.StatConfig cfg = r.getStatConfig();
            if(cfg==null || cfg.getSkill()==null || cfg.getSkill()!= skill) continue;
            KPWebhookPreset.StatMode mode = cfg.getMode();
            if(mode==null) continue;
            switch(mode){
                case LEVEL_UP: {
                    int lastSeen = r.getLastSeenRealLevel();
                    if(lastSeen < 0){
                        r.setLastSeenRealLevel(real); // initialize baseline
                        continue; // don't fire on initial snapshot
                    }
                    if(real > lastSeen){
                        plugin.executeStatRule(r, skill, real);
                        r.setLastSeenRealLevel(real);
                        r.setLastConditionMet(true);
                        plugin.savePresetPublic(r);
                    }
                    break;
                }
                case ABOVE: {
                    int compare = (skill == Skill.HITPOINTS)? boosted : real;
                    boolean cond = compare > cfg.getThreshold();
                    handleThreshold(r, skill, compare, cond, plugin);
                    break; }
                case BELOW: {
                    int compare = (skill == Skill.HITPOINTS)? boosted : real;
                    boolean cond = compare < cfg.getThreshold();
                    handleThreshold(r, skill, compare, cond, plugin);
                    break; }
            }
        }
    }

    // For ABOVE/BELOW: fire every time value changes while condition holds (and once immediately when entering condition)
    private void handleThreshold(KPWebhookPreset r, Skill skill, int valueUsed, boolean cond, KPWebhookPlugin plugin){
        boolean prev = r.isLastConditionMet();
        int lastFiredVal = r.getLastSeenRealLevel(); // reused to store last fired value in threshold modes
        if(cond){
            boolean valueChanged = (lastFiredVal != valueUsed);
            if(!prev || valueChanged){
                plugin.executeStatRule(r, skill, valueUsed);
                r.setLastConditionMet(true);
                r.setLastSeenRealLevel(valueUsed);
                plugin.savePresetPublic(r);
            }
        } else if(prev){
            r.setLastConditionMet(false);
            // keep lastSeenRealLevel so re-entry with same value still fires because prev=false triggers fire logic
            if(r.isForceCancelOnChange()) plugin.softCancelOnChangePublic(r);
            plugin.savePresetPublic(r);
        }
    }
}
