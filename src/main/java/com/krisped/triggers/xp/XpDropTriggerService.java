package com.krisped.triggers.xp;

import com.krisped.KPWebhookPreset;
import com.krisped.KPWebhookPlugin;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * XP_DROP trigger now evaluates the size of the XP drop (gained amount) for the skill.
 * ABOVE: fires when a single gained amount > threshold.
 * BELOW: fires when a single gained amount < threshold (and >0).
 * Fires per qualifying drop (not just boundary crossing), so we do NOT use lastConditionMet gating.
 */
@Singleton
public class XpDropTriggerService {
    private final Client client;
    private final Map<Skill,Integer> lastTotalXp = new EnumMap<>(Skill.class);

    @Inject
    public XpDropTriggerService(Client client){
        this.client = client;
    }

    public void process(StatChanged ev, List<KPWebhookPreset> rules, KPWebhookPlugin plugin){
        if(ev==null || rules==null || rules.isEmpty() || plugin==null) return;
        Skill skill = ev.getSkill();
        int current;
        try { current = client.getSkillExperience(skill); } catch(Exception e){ return; }
        if(current <= 0) return; // ignore invalid
        Integer prev = lastTotalXp.get(skill);
        if(prev == null){
            lastTotalXp.put(skill, current);
            return; // establish baseline, don't fire on first snapshot
        }
        int gained = current - prev;
        if(gained <= 0){
            lastTotalXp.put(skill, current); // update even if decreased (unlikely) or unchanged
            return; // only positive gains considered
        }
        lastTotalXp.put(skill, current);
        for(KPWebhookPreset r : rules){
            if(r==null || !r.isActive()) continue;
            if(r.getTriggerType() != KPWebhookPreset.TriggerType.XP_DROP) continue;
            KPWebhookPreset.XpConfig cfg = r.getXpConfig();
            if(cfg==null || cfg.getSkill()==null || cfg.getSkill()!=skill) continue;
            if(cfg.getMode()==null) continue;
            boolean fire=false;
            switch(cfg.getMode()){
                case ABOVE: fire = gained > cfg.getXpThreshold(); break;
                case BELOW: fire = gained < cfg.getXpThreshold(); break;
                default: break; // ignore LEVEL_UP
            }
            if(fire){
                // Pass gained amount as value
                plugin.executeXpRule(r, skill, gained);
                // Mark lastConditionMet true briefly (not strictly needed, but keeps UI consistent)
                r.setLastConditionMet(true);
                plugin.savePresetPublic(r);
            } else {
                // reset condition flag so UI shows not currently matched
                if(r.isLastConditionMet()){
                    r.setLastConditionMet(false);
                    plugin.savePresetPublic(r);
                }
            }
        }
    }
}
