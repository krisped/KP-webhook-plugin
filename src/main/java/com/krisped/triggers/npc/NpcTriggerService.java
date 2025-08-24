package com.krisped.triggers.npc;

import com.krisped.KPWebhookPlugin;
import com.krisped.KPWebhookPreset;
import net.runelite.api.NPC;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Locale;

/**
 * Handles NPC_SPAWN / NPC_DESPAWN triggers (previously unimplemented).
 */
@Singleton
public class NpcTriggerService {
    private volatile KPWebhookPlugin plugin; // set after construction

    @Inject
    public NpcTriggerService() {}

    public void setPlugin(KPWebhookPlugin plugin){ this.plugin = plugin; }

    @Subscribe
    public void onNpcSpawned(NpcSpawned ev){ if(ev==null) return; NPC npc = ev.getNpc(); if(npc==null) return; KPWebhookPlugin pl = plugin; if(pl==null) return; pl.logNpcSpawn(false, npc); List<KPWebhookPreset> rules = pl.getRules(); for(KPWebhookPreset r: rules){ if(r==null || !r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.NPC_SPAWN){ if(npcMatches(r.getNpcConfig(), npc, pl)) pl.executeNpcTrigger(r, npc); } } }

    @Subscribe
    public void onNpcDespawned(NpcDespawned ev){ if(ev==null) return; NPC npc = ev.getNpc(); if(npc==null) return; KPWebhookPlugin pl = plugin; if(pl==null) return; pl.logNpcSpawn(true, npc); List<KPWebhookPreset> rules = pl.getRules(); for(KPWebhookPreset r: rules){ if(r==null || !r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.NPC_DESPAWN){ if(npcMatches(r.getNpcConfig(), npc, pl)) pl.executeNpcTrigger(r, npc); } } }

    private boolean npcMatches(KPWebhookPreset.NpcConfig cfg, NPC npc, KPWebhookPlugin pl){ if(cfg==null) return true; boolean anyIds = cfg.getNpcIds()!=null && !cfg.getNpcIds().isEmpty(); boolean anyNames = cfg.getNpcNames()!=null && !cfg.getNpcNames().isEmpty(); if(!anyIds && !anyNames) return true; int id = -1; try { id = npc.getId(); } catch(Exception ignored){} if(anyIds){ for(Integer i: cfg.getNpcIds()){ if(i!=null && i == id) return true; } }
        if(anyNames){ String nameNorm=""; try { nameNorm = pl.sanitizeNpcName(npc.getName()).replace(' ','_').toLowerCase(Locale.ROOT); } catch(Exception ignored){} for(String n: cfg.getNpcNames()){ if(nameNorm.equals(n)) return true; } }
        return false; }
}

