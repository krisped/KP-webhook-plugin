package com.krisped.triggers.player;

import com.krisped.KPWebhookPlugin;
import com.krisped.KPWebhookPreset;
import com.krisped.commands.tokens.TokenService;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Locale;

/**
 * Extracted handler for PLAYER_SPAWN / PLAYER_DESPAWN triggers to reduce size of KPWebhookPlugin.
 * (Original logic preserved; functionality unchanged.)
 */
@Singleton
public class PlayerTriggerService {
    private final Client client;
    private volatile KPWebhookPlugin plugin; // set post-construction to avoid circular injection
    @Inject private TokenService tokenService;

    @Inject
    public PlayerTriggerService(Client client){
        this.client = client;
    }

    public void setPlugin(KPWebhookPlugin plugin){ this.plugin = plugin; }

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned ev){
        if(ev==null) return; Player p = ev.getPlayer(); if(p==null) return; KPWebhookPlugin pl = plugin; if(pl==null) return; boolean self = client.getLocalPlayer()==p;
        if(tokenService!=null) try { tokenService.updatePlayerSpawnToken(p,false); } catch(Exception ignored){}
        pl.logPlayerSpawn(false, p); // debug window logging
        List<KPWebhookPreset> rules = pl.getRules();
        for(KPWebhookPreset r: rules){
            if(r==null || !r.isActive()) continue;
            if(r.getTriggerType()== KPWebhookPreset.TriggerType.PLAYER_SPAWN){
                if(playerMatches(r.getPlayerConfig(), p, self)) pl.executePlayerTrigger(r, p);
            }
        }
    }

    @Subscribe
    public void onPlayerDespawned(PlayerDespawned ev){
        if(ev==null) return; Player p = ev.getPlayer(); if(p==null) return; KPWebhookPlugin pl = plugin; if(pl==null) return; boolean self = client.getLocalPlayer()==p;
        if(tokenService!=null) try { tokenService.updatePlayerSpawnToken(p,true); } catch(Exception ignored){}
        pl.logPlayerSpawn(true, p);
        List<KPWebhookPreset> rules = pl.getRules();
        for(KPWebhookPreset r: rules){
            if(r==null || !r.isActive()) continue;
            if(r.getTriggerType()== KPWebhookPreset.TriggerType.PLAYER_DESPAWN){
                if(playerMatches(r.getPlayerConfig(), p, self)) pl.executePlayerTrigger(r, p);
            }
        }
    }

    private boolean playerMatches(KPWebhookPreset.PlayerConfig cfg, Player p, boolean self){
        if(cfg==null) return true; // no filter
        if(self) return false; // never match self (original behavior)
        if(cfg.isAll()) return true;
        if(cfg.getCombatRange()!=null){
            try {
                int local = client.getLocalPlayer()!=null? client.getLocalPlayer().getCombatLevel():0;
                return Math.abs(local - p.getCombatLevel()) <= cfg.getCombatRange();
            } catch(Exception ignored){ return false; }
        }
        List<String> names = cfg.getNames();
        if(names!=null && !names.isEmpty()){
            KPWebhookPlugin pl = plugin; if(pl==null) return false;
            String pn = pl.sanitizePlayerName(p.getName()).toLowerCase(Locale.ROOT);
            for(String n: names){ if(pn.equals(n)) return true; }
            return false;
        }
        if(cfg.getName()!=null && !cfg.getName().isBlank()){
            KPWebhookPlugin pl = plugin; if(pl==null) return false;
            return pl.sanitizePlayerName(p.getName()).equalsIgnoreCase(cfg.getName());
        }
        return true;
    }
}
