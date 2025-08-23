package com.krisped;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Provides;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.coords.LocalPoint; // added for projectile origin proximity
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.client.Notifier;
import net.runelite.client.events.ConfigChanged;
import okhttp3.*;
import com.krisped.commands.highlight.*;
import com.krisped.commands.infobox.InfoboxCommandHandler;
import com.krisped.commands.overlaytext.*;
import com.krisped.triggers.tick.TickTriggerService;
import com.krisped.commands.tokens.TokenService; // added
import com.krisped.commands.custom_message.CustomMessageCommandHandler;
import com.krisped.triggers.player.PlayerTriggerService;
import com.krisped.triggers.stat.StatTriggerService;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.io.IOException; // added
import java.util.Base64;
import net.runelite.api.WorldType; // added for PvP world detection

@Slf4j
@PluginDescriptor(
        name = "KP Webhook",
        description = "Triggers: MANUAL, STAT, XP_DROP, WIDGET_SPAWN, PLAYER_SPAWN, PLAYER_DESPAWN, NPC_SPAWN, NPC_DESPAWN, ANIMATION_SELF, ANIMATION_TARGET, ANIMATION_ANY, GRAPHIC_SELF, GRAPHIC_TARGET, GRAPHIC_ANY, PROJECTILE_SELF, PROJECTILE_TARGET, PROJECTILE_ANY, HITSPLAT_SELF, HITSPLAT_TARGET, MESSAGE, VARBIT, VARPLAYER, TICK, TARGET, IDLE, GEAR_CHANGED, TARGET_GEAR_CHANGED, REGION, INVENTORY_FULL, INVENTORY_ITEM_ADDED, INVENTORY_ITEM_REMOVED, INVENTORY_CONTAINS_NONE, LOOT_DROP, DEATH, INTERACTING. Commands: NOTIFY, CUSTOM_MESSAGE, WEBHOOK, SCREENSHOT, HIGHLIGHT_*, MARK_TILE, TEXT_*, OVERLAY_TEXT, SLEEP, TICK, STOP, TOGGLEPRESET, TOGGLEGROUP.",
        tags = {"webhook","stat","trigger","screenshot","widget","highlight","text","player","npc","varbit","varplayer","tick","overlay","target","graphic","hitsplat","projectile","idle","gear","region","inventory","loot","death","xp","interacting"}
)
public class KPWebhookPlugin extends Plugin {
    // Anti-spam cache for heavy commands in TICK presets
    private final Map<Integer, Set<String>> tickRulePersistentCommands = new HashMap<>();
    // Track remote players currently interacting with local player (for INTERACTING trigger dedupe)
    private final Set<String> interactingPlayers = new HashSet<>();

    // === Chat message type alias map ===
    private static final Map<String, ChatMessageType> CHAT_TYPE_ALIASES = new HashMap<>();
    private static final Set<String> CHAT_SUFFIXES = new LinkedHashSet<>(Arrays.asList("RECEIVED","SENT","IN","OUT","MESSAGE","MSG","CHAT"));
    private static String normKey(String s){ if (s==null) return ""; return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]",""); }
    private static void registerAlias(Map<String,ChatMessageType> map,String canonical,String...aliases){ ChatMessageType base=map.get(canonical); if(base==null) return; for(String a:aliases){ if(a==null||a.isBlank()) continue; String up=a.toUpperCase(Locale.ROOT); map.put(up,base); map.put(normKey(up), base);} }
    static { try { for (ChatMessageType t : ChatMessageType.values()){ String up=t.name().toUpperCase(Locale.ROOT); CHAT_TYPE_ALIASES.put(up,t); CHAT_TYPE_ALIASES.put(normKey(up),t);} registerAlias(CHAT_TYPE_ALIASES,"GAMEMESSAGE","GAME","GAME_MESSAGE","SYSTEM","INFO","GAMEMSG","GAME_MSG","GAMECHAT","SYSTEMMESSAGE","SYSTEM_MESSAGE","SYSTEMMSG"); } catch(Exception ignored){} }

    // Expose chat type resolution for command handlers
    public static ChatMessageType resolveChatType(String token){
        if(token==null||token.isBlank()) return null;
        String up = token.toUpperCase(Locale.ROOT);
        ChatMessageType t = CHAT_TYPE_ALIASES.get(up);
        if(t==null) t = CHAT_TYPE_ALIASES.get(normKey(up));
        if(t==null){
            try { int id = Integer.parseInt(token.trim()); for(ChatMessageType cmt: ChatMessageType.values()){ if(cmt.ordinal()==id) return cmt; } } catch(Exception ignored){}
        }
        return t;
    }

    @Inject private Client client; @Inject private ClientThread clientThread; @Inject private ClientToolbar clientToolbar; @Inject private ScheduledExecutorService executorService; @Inject private OkHttpClient okHttpClient; @Inject private ConfigManager configManager; @Inject private KPWebhookConfig config; @Inject private OverlayManager overlayManager; @Inject private HighlightManager highlightManager; @Inject private HighlightCommandHandler highlightCommandHandler; @Inject private HighlightOverlay highlightOverlay; @Inject private MinimapHighlightOverlay minimapHighlightOverlay; // retain minimap
    @Inject private MarkTileManager markTileManager; @Inject private MarkTileCommandHandler markTileCommandHandler;
    @Inject private TickTriggerService tickTriggerService; @Inject private InfoboxCommandHandler infoboxCommandHandler; @Inject private OverlayTextManager overlayTextManager; @Inject private OverlayTextOverlay overlayTextOverlay; @Inject private OverlayTextCommandHandler overlayTextCommandHandler; @Inject private ItemManager itemManager; @Inject private SpriteManager spriteManager; @Inject private Notifier notifier;
    @Inject private TokenService tokenService; // added
    @Inject private CustomMessageCommandHandler customMessageCommandHandler; // added
    @Inject private PlayerTriggerService playerTriggerService; // new service for player spawn/despawn
    @Inject private StatTriggerService statTriggerService; // new STAT trigger service

    private KPWebhookPanel panel; private NavigationButton navButton; private KPWebhookStorage storage;
    private final List<KPWebhookPreset> rules = new ArrayList<>(); private int nextId=0; private KPWebhookDebugWindow debugWindow; private KPWebhookPresetDebugWindow presetDebugWindow;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8"); private static final MediaType PNG = MediaType.parse("image/png");
    private final Map<Skill,Integer> lastRealLevel = new EnumMap<>(Skill.class); private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    // New: track last logged values for debug window (separate from baseline) to avoid spam
    private final Map<Skill,Integer> lastLoggedRealLevel = new EnumMap<>(Skill.class);
    private final Map<Skill,Integer> lastLoggedBoostedLevel = new EnumMap<>(Skill.class);
    private BufferedImage lastFrame; private volatile long lastScreenshotRequestMs = 0; private static final long SCREENSHOT_COOLDOWN_MS = 800;

    // Target tracking
    private Actor currentTarget; private int targetLastActiveTick=-1; private int gameTickCounter=0; private static final int TARGET_RETENTION_TICKS=50; private int lastTargetAnimationId=-1; private int lastTargetGraphicId=-1;

    // Gear tracking snapshots (restored after refactor)
    private final Set<Integer> lastLocalGear = new HashSet<>();
    private final Set<Integer> lastTargetGear = new HashSet<>();
    private boolean localGearInitialized = false; // avoid firing on initial snapshot
    private Player lastTargetPlayerForGear = null; // track target player reference for baseline

    // Public accessor for current target (used by overlays)
    public Actor getCurrentTargetActor() { return currentTarget; }

    // Idle tracking (local player activity)
    private long lastLocalActivityMs = System.currentTimeMillis();
    private WorldPoint lastLocalWorldPoint = null;
    private int lastLocalAnimation = -2; // sentinel

    private final Set<Integer> seenProjectiles = new HashSet<>();
    // New: track projectile identity hashes so each projectile logs only once
    private final Set<Integer> seenProjectileIdentities = new HashSet<>();
    // Dedup varplayer logging
    private final Map<Integer,Integer> lastVarplayerValue = new HashMap<>();
    private final Map<Integer,Integer> lastVarplayerTick = new HashMap<>();
    // Restore baseline stats logged flag (used by debug window)
    private boolean baselineStatsLogged = false;

    // Overhead texts
    @Data public static class ActiveOverheadText { String text; Color color; boolean blink; int size; String position; int remainingTicks; boolean visiblePhase; int blinkCounter; int blinkInterval; boolean bold; boolean italic; boolean underline; String key; boolean persistent; int ruleId=-1; public enum TargetType { LOCAL_PLAYER, PLAYER_NAME, NPC_NAME, NPC_ID, TARGET, FRIEND_LIST, IGNORE_LIST, PARTY_MEMBERS, FRIENDS_CHAT, TEAM_MEMBERS, CLAN_MEMBERS, OTHERS } TargetType targetType=TargetType.LOCAL_PLAYER; Set<String> targetNames; Set<Integer> targetIds; }
    private final List<ActiveOverheadText> overheadTexts = new ArrayList<>(); public List<ActiveOverheadText> getOverheadTexts(){ return overheadTexts; }

    // Overhead images
    @Data public static class ActiveOverheadImage { BufferedImage image; int itemOrSpriteId; boolean sprite; String position; int remainingTicks; boolean persistent; boolean blink; int blinkCounter; int blinkInterval=2; boolean visiblePhase=true; int ruleId=-1; public enum TargetType { LOCAL_PLAYER, PLAYER_NAME, NPC_NAME, NPC_ID, TARGET, FRIEND_LIST, IGNORE_LIST, PARTY_MEMBERS, FRIENDS_CHAT, TEAM_MEMBERS, CLAN_MEMBERS, OTHERS } TargetType targetType=TargetType.LOCAL_PLAYER; Set<String> targetNames; Set<Integer> targetIds; }
    private final List<ActiveOverheadImage> overheadImages = new ArrayList<>(); public List<ActiveOverheadImage> getOverheadImages(){ return overheadImages; }

    // Flag controlling whether to bypass legacy player spawn handlers (kept false to preserve original behavior)
    @SuppressWarnings("unused") private static final boolean delegatePlayerSpawnHandling = false;

    // Sequencing
    private enum PendingType { ACTION, TICK_DELAY, SLEEP_DELAY }
    @Data private static class PendingCommand { PendingType type; String line; int ticks; long sleepMs; }
    @Data private static class CommandSequence { KPWebhookPreset rule; Map<String,String> ctx; List<PendingCommand> commands; int index; int tickDelayRemaining; long sleepUntilMillis; }
    private final List<CommandSequence> activeSequences = new ArrayList<>();

    // Patterns
    private static final Pattern P_TEXT_UNDER = Pattern.compile("(?i)^TEXT_UNDER\\s+(.*)"); private static final Pattern P_TEXT_OVER = Pattern.compile("(?i)^TEXT_OVER\\s+(.*)"); private static final Pattern P_TEXT_CENTER = Pattern.compile("(?i)^TEXT_CENTER\\s+(.*)"); private static final Pattern P_IMG_OVER = Pattern.compile("(?i)^IMG_OVER\\s+(.+)"); private static final Pattern P_IMG_UNDER = Pattern.compile("(?i)^IMG_UNDER\\s+(.+)"); private static final Pattern P_IMG_CENTER = Pattern.compile("(?i)^IMG_CENTER\\s+(.+)");

    @Provides KPWebhookConfig provideConfig(ConfigManager cm){ return cm.getConfig(KPWebhookConfig.class); }

    @Override protected void startUp(){ panel = new KPWebhookPanel(this); navButton = NavigationButton.builder().tooltip("KP Webhook").icon(ImageUtil.loadImageResource(KPWebhookPlugin.class, "webhook.png")).priority(1).panel(panel).build(); clientToolbar.addNavigation(navButton); overlayManager.add(highlightOverlay); overlayManager.add(minimapHighlightOverlay); overlayManager.add(overlayTextOverlay); captureInitialRealLevels(); storage = new KPWebhookStorage(configManager, gson); rules.clear(); rules.addAll(storage.loadAll()); // establish nextId
        nextId = 0; for (KPWebhookPreset r: rules) if (r.getId()>=nextId) nextId=r.getId()+1; // repair any invalid / duplicate ids
        ensureUniqueIds(); panel.refreshTable();
        try { if(playerTriggerService!=null) playerTriggerService.setPlugin(this); } catch(Exception ignored){}
        // Initialize stat baselines so LEVEL_UP doesn't instantly fire on startup
        try { com.krisped.triggers.stat.StatTriggerHelper.initializeBaselines(client, rules); } catch(Exception ignored){}
    }
    @Override protected void shutDown(){ overlayManager.remove(highlightOverlay); overlayManager.remove(minimapHighlightOverlay); overlayManager.remove(overlayTextOverlay); clientToolbar.removeNavigation(navButton); saveAllPresets(); rules.clear(); highlightManager.clear(); overheadTexts.clear(); overheadImages.clear(); if (infoboxCommandHandler!=null) infoboxCommandHandler.clearAll(); if (overlayTextManager!=null) overlayTextManager.clear(); if (debugWindow!=null){ try{debugWindow.dispose();}catch(Exception ignored){} debugWindow=null;} if (presetDebugWindow!=null){ try{presetDebugWindow.dispose();}catch(Exception ignored){} presetDebugWindow=null;} }

    private void captureInitialRealLevels(){ for (Skill s: Skill.values()){ try { lastRealLevel.put(s, client.getRealSkillLevel(s)); } catch(Exception ignored){} } }

    public List<KPWebhookPreset> getRules(){ return rules; }
    public void addOrUpdate(KPWebhookPreset p){ if(p==null) return; // normalize category: trim and reuse existing category casing if present
        String previousTitle=null; if(p.getId()>=0){ KPWebhookPreset existing = find(p.getId()); if(existing!=null) previousTitle = existing.getTitle(); }
        // Clear spam cache for rule being updated so changes reapply
        if(p.getId()>=0) tickRulePersistentCommands.remove(p.getId());
        if(p.getCategory()!=null){ String raw=p.getCategory().trim(); if(raw.isEmpty()) p.setCategory(null); else {
                for(KPWebhookPreset existing: rules){ if(existing.getCategory()!=null && existing.getCategory().equalsIgnoreCase(raw)){ p.setCategory(existing.getCategory()); break; } }
                if(p.getCategory()!=null) p.setCategory(p.getCategory().trim());
            } }
        if(p.getId()<0){ p.setId(nextId++); } else { boolean idInUse = rules.stream().anyMatch(r->r.getId()==p.getId()); if(idInUse){ /* replace same id */ } }
        rules.removeIf(r->r.getId()==p.getId()); rules.add(p); savePreset(p, previousTitle);}
    // Duplicate an existing preset (deep copy) with new id and unique title
    public void duplicateRule(int id){ KPWebhookPreset orig = find(id); if(orig==null) return; try {
            KPWebhookPreset copy = gson.fromJson(gson.toJson(orig), KPWebhookPreset.class);
            copy.setId(-1); // new id
            // Generate name: duplicate N (first free number)
            Set<String> existingTitlesLower = new HashSet<>(); for(KPWebhookPreset r: rules){ if(r.getTitle()!=null) existingTitlesLower.add(r.getTitle().toLowerCase(Locale.ROOT)); }
            int n=1; String candidate; do { candidate = "duplicate " + n; n++; } while(existingTitlesLower.contains(candidate.toLowerCase(Locale.ROOT)) && n<10000);
            copy.setTitle(candidate);
            addOrUpdate(copy); if(panel!=null) panel.refreshTable();
        } catch(Exception e){ log.warn("Failed to duplicate preset {}", id, e); } }
    public void deleteRule(int id){ KPWebhookPreset p=find(id); if(p!=null){ // ensure full cleanup on delete
            stopRule(p.getId());
            rules.remove(p); if (storage!=null) storage.delete(p);} }
    public void toggleActive(int id){ KPWebhookPreset p=find(id); if(p!=null){ boolean wasActive=p.isActive(); p.setActive(!p.isActive()); savePreset(p); if(wasActive && !p.isActive()){ // fully stop when turning off (client thread to sync with overlays)
                try { clientThread.invokeLater(() -> stopRule(p.getId())); } catch(Exception e){ stopRule(p.getId()); }
            } } }
    private KPWebhookPreset find(int id){ return rules.stream().filter(r->r.getId()==id).findFirst().orElse(null); }
    private void savePreset(KPWebhookPreset p){ savePreset(p, null); }
    private void savePreset(KPWebhookPreset p, String previousTitle){ if(storage!=null && p!=null) storage.save(p, previousTitle); }
    private void saveAllPresets(){ if(storage!=null) for(KPWebhookPreset p: rules) storage.save(p,null); }

    // Stop rule cleanup
    private void stopRule(int ruleId){ cancelSequencesForRule(ruleId); removeOverheadsForRule(ruleId,true); removePersistentOverheadsForRule(ruleId); try{highlightManager.removeAllByRule(ruleId);}catch(Exception ignored){} overheadImages.removeIf(i->i!=null && i.ruleId==ruleId); try { overlayTextManager.removeByRule(ruleId);} catch(Exception ignored){} try{ if(infoboxCommandHandler!=null) infoboxCommandHandler.removeByRule(ruleId);}catch(Exception ignored){} markTileManager.removeByRule(ruleId); tickRulePersistentCommands.remove(ruleId); }

    // Soft cancel invoked when forceCancelOnChange conditions revert; keeps rule active for future triggers
    private void softCancelOnChange(KPWebhookPreset r){
        if(r==null) return;
        // Perform same cleanup as stopRule but do not toggle active flag
        stopRule(r.getId());
    }
    // Public wrappers for services
    public void softCancelOnChangePublic(KPWebhookPreset r){ softCancelOnChange(r); }
    public void savePresetPublic(KPWebhookPreset r){ savePreset(r); }
    public void executeStatRule(KPWebhookPreset r, Skill skill, int real){ executeRule(r, skill, real, r.getStatConfig(), r.getWidgetConfig()); }

    // === Restored executeRule overloads & baseContext for trigger execution ===
    private void executeRule(KPWebhookPreset rule, Skill skill, int value, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg){ executeRule(rule,skill,value,statCfg,widgetCfg,null,null); }
    private void executeRule(KPWebhookPreset rule, Skill skill, int value, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg, Player other){ executeRule(rule,skill,value,statCfg,widgetCfg,other,null); }
    private void executeRule(KPWebhookPreset rule, Skill skill, int value, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg, Player otherPlayer, NPC npc){ if(rule==null) return; String cmds = rule.getCommands(); if(cmds==null) cmds=""; boolean isTick = rule.getTriggerType()== KPWebhookPreset.TriggerType.TICK;
        // For TICK triggers: if an active sequence already running, skip entirely (prevents overhead duplication)
        if(isTick){ for(CommandSequence existing : activeSequences){ if(existing!=null && existing.rule!=null && existing.rule.getId()==rule.getId() && existing.index < existing.commands.size()){ return; } } }
        else { // clear previous visuals for non-tick triggers to avoid stacking
            try { stopRule(rule.getId()); } catch(Exception ignored){}
        }
        Map<String,String> ctx = baseContext(skill,value,widgetCfg,otherPlayer,npc);
        if(!isTick || (isTick && !cmds.isBlank())){ applyPresetOverheadTexts(rule, ctx); }
        if(cmds.isBlank()){ rule.setLastConditionMet(true); return; }
        List<PendingCommand> list=new ArrayList<>(); List<String> original=new ArrayList<>();
        for(String rawLine: cmds.split("\r?\n")){
            String line=rawLine.trim(); if(line.isEmpty()|| line.startsWith("#")) continue; original.add(line); String up=line.toUpperCase(Locale.ROOT);
            if(up.startsWith("SLEEP ")){ long ms=0; try{ms=Long.parseLong(line.substring(6).trim());}catch(Exception ignored){} PendingCommand pc=new PendingCommand(); pc.type=PendingType.SLEEP_DELAY; pc.sleepMs=Math.max(0,ms); list.add(pc);} else if(up.equals("SLEEP")){/* ignore bare */}
            else if(up.startsWith("TICK")){ int t=1; String[] parts=line.split("\\s+"); if(parts.length>1){ try{ t=Integer.parseInt(parts[1]); }catch(Exception ignored){} } PendingCommand pc=new PendingCommand(); pc.type=PendingType.TICK_DELAY; pc.ticks=Math.max(1,t); list.add(pc);} else { PendingCommand pc=new PendingCommand(); pc.type=PendingType.ACTION; pc.line=line; list.add(pc);} }
        if(presetDebugWindow!=null && presetDebugWindow.isVisible()){ try { presetDebugWindow.logExecution(rule, original, ctx);} catch(Exception ignored){} }
        if(list.isEmpty()){ rule.setLastConditionMet(true); return; }
        CommandSequence seq=new CommandSequence(); seq.rule=rule; seq.ctx=ctx; seq.commands=list; seq.index=0; seq.tickDelayRemaining=0; seq.sleepUntilMillis=0L; activeSequences.add(seq); rule.setLastConditionMet(true); }
    private Map<String,String> baseContext(Skill skill,int value,KPWebhookPreset.WidgetConfig widgetCfg, Player otherPlayer, NPC npc){ return tokenService.buildContext(skill,value,widgetCfg,otherPlayer,npc,getCurrentTargetName()); }

    // === STAT event (re-added) ===
    @Subscribe public void onStatChanged(StatChanged ev){
        if(ev==null) return;
        if(client.getGameState()!=GameState.LOGGED_IN) return; // ignore pre-login zeros
        Skill skill = ev.getSkill();
        int real; int boosted;
        try { real = client.getRealSkillLevel(skill); } catch(Exception e){ real = ev.getLevel(); }
        try { boosted = client.getBoostedSkillLevel(skill); } catch(Exception e){ boosted = real; }
        if(real<=0 && boosted<=0) return; // ignore zero updates
        lastRealLevel.put(skill, real);
        if(debugWindow!=null && debugWindow.isVisible()){
            Integer prevR = lastLoggedRealLevel.get(skill); Integer prevB = lastLoggedBoostedLevel.get(skill);
            if(prevR==null || prevB==null || prevR!=real || prevB!=boosted){
                try { debugWindow.logStat(skill.name(), real, boosted); } catch(Exception ignored){}
                lastLoggedRealLevel.put(skill, real); lastLoggedBoostedLevel.put(skill, boosted);
            }
        }
        if(statTriggerService!=null){ try { statTriggerService.process(ev, rules, this); } catch(Exception e){ log.warn("Stat trigger processing error", e); } }
    }

    /* === Game Tick === */
    @Subscribe public void onGameTick(GameTick t){ gameTickCounter++; seenProjectiles.clear(); updateAndProcessTarget(); if(tickTriggerService!=null){ tickTriggerService.process(rules, overheadTexts); /* removed TICK preset debug logging */ }
        // Fallback polling for STAT debug only when logged in
        if(client.getGameState()==GameState.LOGGED_IN && debugWindow!=null && debugWindow.isVisible()){
            try {
                for(Skill s: Skill.values()){
                    if(s==null) continue; int real=-1, boosted=-1; try { real=client.getRealSkillLevel(s); } catch(Exception ignored){}
                    try { boosted=client.getBoostedSkillLevel(s); } catch(Exception ignored) { boosted=real; }
                    if(real<=0 && boosted<=0) continue; // ignore zero snapshot
                    Integer prevR = lastLoggedRealLevel.get(s); Integer prevB = lastLoggedBoostedLevel.get(s);
                    if(prevR==null || prevB==null || prevR!=real || prevB!=boosted){
                        debugWindow.logStat(s.name(), real, boosted);
                        lastLoggedRealLevel.put(s, real); lastLoggedBoostedLevel.put(s, boosted);
                    }
                }
                baselineStatsLogged=true;
            } catch(Exception ignored){}
        }
        // Update local player activity state BEFORE firing idle rules
        try {
            Player local = client.getLocalPlayer();
            if(local!=null){
                boolean active=false;
                // Movement detection via world point change
                WorldPoint wp = null; try { wp = local.getWorldLocation(); } catch(Exception ignored){}
                if(wp!=null){ if(lastLocalWorldPoint==null || !wp.equals(lastLocalWorldPoint)) active=true; lastLocalWorldPoint = wp; }
                // Animation change (only treat as activity when it changes to a non -1 value)
                int anim=-1; try { anim = local.getAnimation(); } catch(Exception ignored){}
                if(anim != lastLocalAnimation && anim!=-1) active=true; // animation changed
                lastLocalAnimation = anim;
                // Interacting (e.g., in combat or skilling)
                try { if(local.getInteracting()!=null) active=true; } catch(Exception ignored){}
                if(active) lastLocalActivityMs = System.currentTimeMillis();
            }
        } catch(Exception ignored){}
        // Execute all TICK rules each tick (sequence guard prevents duplicates)
        for(KPWebhookPreset r: rules){ if(r!=null && r.isActive() && r.getTriggerType()== KPWebhookPreset.TriggerType.TICK){ executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig()); } }
        // IDLE trigger evaluation (after activity update)
        long now = System.currentTimeMillis();
        for(KPWebhookPreset r: rules){
            if(r==null || !r.isActive()) continue;
            if(r.getTriggerType()== KPWebhookPreset.TriggerType.IDLE){
                KPWebhookPreset.IdleConfig ic = r.getIdleConfig();
                int threshold = ic!=null? ic.getThresholdMs(): 0; // default 0 means 'idle as soon as detected'
                if (threshold < 0) threshold = 0;
                boolean idleCond = (now - lastLocalActivityMs) >= threshold;
                if(idleCond && !r.isLastConditionMet()){
                    executeRule(r,null,-1,null,null);
                    r.setLastConditionMet(true);
                    savePreset(r);
                } else if(!idleCond && r.isLastConditionMet()){
                    r.setLastConditionMet(false);
                    if(r.isForceCancelOnChange()) softCancelOnChange(r);
                    savePreset(r);
                }
            } else if(r.getTriggerType()== KPWebhookPreset.TriggerType.REGION){
                Player local = client.getLocalPlayer();
                int regionId = -1;
                try { if(local!=null){ WorldPoint wp = local.getWorldLocation(); if(wp!=null) regionId = wp.getRegionID(); } } catch(Exception ignored){}
                KPWebhookPreset.RegionConfig rc = r.getRegionConfig();
                boolean match = false;
                if(rc!=null && rc.getRegionIds()!=null){ for(Integer id: rc.getRegionIds()){ if(id!=null && id == regionId){ match = true; break; } } }
                if(match && !r.isLastConditionMet()){
                    executeRule(r,null,-1,null,null);
                    r.setLastConditionMet(true);
                    savePreset(r);
                } else if(!match && r.isLastConditionMet()){
                    r.setLastConditionMet(false);
                    if(r.isForceCancelOnChange()) softCancelOnChange(r);
                    savePreset(r);
                }
            }
        }
        // Sequences
        if(!activeSequences.isEmpty()){ long seqNow=System.currentTimeMillis(); Iterator<CommandSequence> it=activeSequences.iterator(); while(it.hasNext()){ CommandSequence seq=it.next(); if(seq.tickDelayRemaining>0){ seq.tickDelayRemaining--; continue; } if(seq.sleepUntilMillis>0 && seqNow<seq.sleepUntilMillis) continue; int safety=0; while(seq.index<seq.commands.size() && safety<1000){ PendingCommand pc=seq.commands.get(seq.index); if(pc.type==PendingType.TICK_DELAY){ seq.tickDelayRemaining=Math.max(1,pc.ticks); seq.index++; break; } else if(pc.type==PendingType.SLEEP_DELAY){ seq.sleepUntilMillis=seqNow+Math.max(0,pc.sleepMs); seq.index++; break; } else { processActionLine(seq.rule, pc.line, seq.ctx); seq.index++; } safety++; } if(seq.index>=seq.commands.size()) it.remove(); } }
        // Tick visuals
        tickOverheads();
        // Capture frame
        try { SwingUtilities.invokeLater(this::captureCanvasFrame); } catch(Exception ignored){}
        // Gear change processing at end of tick after target update
        try { processGearChanged(); } catch(Exception ignored){}
    }

    private void captureCanvasFrame(){ try{ Canvas canvas=client.getCanvas(); if(canvas==null) return; int w=canvas.getWidth(), h=canvas.getHeight(); if(w<=0||h<=0) return; BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB); Graphics2D g2=img.createGraphics(); try { canvas.paint(g2);} catch(Exception ignored){} g2.dispose(); if(!isMostlyBlack(img)&&!isMostlyWhite(img)) lastFrame=img; }catch(Exception ignored){} }

    private void tickOverheads(){ if(!overheadTexts.isEmpty()){ Iterator<ActiveOverheadText> it=overheadTexts.iterator(); while(it.hasNext()){ ActiveOverheadText t=it.next(); if(t.blink){ t.blinkCounter++; if(t.blinkCounter % Math.max(1,t.blinkInterval)==0) t.visiblePhase=!t.visiblePhase; } else t.visiblePhase=true; if(!t.persistent){ t.remainingTicks--; if(t.remainingTicks<=0) it.remove(); } } } if(!overheadImages.isEmpty()){ Iterator<ActiveOverheadImage> ii=overheadImages.iterator(); while(ii.hasNext()){ ActiveOverheadImage oi=ii.next(); if(oi.blink){ oi.blinkCounter++; if(oi.blinkCounter % Math.max(1,oi.blinkInterval)==0) oi.visiblePhase=!oi.visiblePhase; } else oi.visiblePhase=true; if(!oi.persistent){ oi.remainingTicks--; if(oi.remainingTicks<=0) ii.remove(); } } } highlightManager.tick(); overlayTextManager.tick(); if(infoboxCommandHandler!=null) infoboxCommandHandler.tick(); }

    private void updateAndProcessTarget(){ try { Player local=client.getLocalPlayer(); Actor interacting=null; if(local!=null){ Actor raw = local.getInteracting(); if(raw!=null && isAttackable(raw)) interacting=raw; } boolean targetExpired = currentTarget!=null && (gameTickCounter - targetLastActiveTick) > TARGET_RETENTION_TICKS;
            // NEW: force-cancel immediate removal when target lost (no interacting) if any TARGET preset has forceCancelOnChange
            if(interacting==null && currentTarget!=null && !targetExpired){
                boolean anyForce=false; for(KPWebhookPreset r: rules){ if(r!=null && r.isActive() && r.getTriggerType()== KPWebhookPreset.TriggerType.TARGET && r.isForceCancelOnChange()){ anyForce=true; break; } }
                if(anyForce){ Actor old=currentTarget; // soft cancel all TARGET presets with flag
                    for(KPWebhookPreset r: rules){ if(r!=null && r.isActive() && r.getTriggerType()== KPWebhookPreset.TriggerType.TARGET && r.isForceCancelOnChange()){ softCancelOnChange(r); r.setLastConditionMet(false); savePreset(r); } }
                    currentTarget=null; lastTargetAnimationId=-1; lastTargetGraphicId=-1; // do NOT fire TARGET trigger again on loss
                    return; }
            }
            boolean targetChanged=false; if(interacting!=null){ if(currentTarget==null || currentTarget!=interacting) targetChanged=true; } else if(targetExpired && currentTarget!=null){ targetChanged=true; }
            if(targetChanged){ Actor old=currentTarget; if(targetExpired || interacting==null){ if(targetExpired) stopAllTargetPresets(); currentTarget=null; lastTargetAnimationId=-1; lastTargetGraphicId=-1; } else { currentTarget=interacting; lastTargetAnimationId=currentTarget.getAnimation(); try { lastTargetGraphicId=currentTarget.getGraphic(); } catch(Exception ignored){ lastTargetGraphicId=-1; } targetLastActiveTick=gameTickCounter; } fireTargetChangeTriggers(old,currentTarget); } else if(interacting!=null){ targetLastActiveTick=gameTickCounter; } } catch(Exception ignored){} }

    private boolean isAttackable(Actor a){ if(a==null) return false; if(a instanceof NPC) return true; if(a instanceof Player) return a!=client.getLocalPlayer(); return false; }
    private void fireTargetChangeTriggers(Actor oldT, Actor newT){ if(oldT==newT) return; if(debugWindow!=null && debugWindow.isVisible()){ try { String oldName = oldT instanceof Player? sanitizePlayerName(((Player)oldT).getName()) : (oldT instanceof NPC? sanitizeNpcName(((NPC)oldT).getName()):""); String newName = newT instanceof Player? sanitizePlayerName(((Player)newT).getName()) : (newT instanceof NPC? sanitizeNpcName(((NPC)newT).getName()):""); debugWindow.logTargetChange(oldName, newName); } catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.TARGET) continue; // apply force cancel on change BEFORE re-trigger
            if(r.isForceCancelOnChange() && r.isLastConditionMet()) { softCancelOnChange(r); r.setLastConditionMet(false); }
            executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig(), (newT instanceof Player)?(Player)newT:null, (newT instanceof NPC)?(NPC)newT:null); r.setLastConditionMet(true); }
    }
    private void stopAllTargetPresets(){ for(KPWebhookPreset r: rules) if(r.getTriggerType()== KPWebhookPreset.TriggerType.TARGET) stopRule(r.getId()); }
    private String getCurrentTargetName(){ if(currentTarget==null) return ""; if(currentTarget instanceof Player) return sanitizePlayerName(((Player)currentTarget).getName()); if(currentTarget instanceof NPC) return sanitizeNpcName(((NPC)currentTarget).getName()); return ""; }

    // EVENT SUBSCRIBERS (subset)
    @Subscribe
    public void onVarbitChanged(VarbitChanged ev){
        if(ev==null) return;
        int id = ev.getVarbitId();
        int val = ev.getValue();
        if(debugWindow!=null && debugWindow.isVisible()){
            try {
                if(id!=-1) debugWindow.logVarbit(id,val);
                int varpId = ev.getVarpId();
                if(varpId!=-1){
                    int currentVal = client.getVarpValue(varpId);
                    Integer lastV = lastVarplayerValue.get(varpId);
                    Integer lastT = lastVarplayerTick.get(varpId);
                    if(lastV==null || lastV!=currentVal || lastT==null || lastT!=gameTickCounter){
                        lastVarplayerValue.put(varpId,currentVal);
                        lastVarplayerTick.put(varpId, gameTickCounter);
                        debugWindow.logVarplayer(varpId, currentVal);
                    }
                }
            } catch(Exception ignored){}
        }
        for(KPWebhookPreset r: rules){
            if(!r.isActive()) continue;
            if(r.getTriggerType()== KPWebhookPreset.TriggerType.VARBIT){
                KPWebhookPreset.VarbitConfig cfg = r.getVarbitConfig();
                if(cfg==null) continue;
                boolean idMatch = matchesId(cfg.getVarbitId(), cfg.getVarbitIds(), id);
                boolean valueMatch = idMatch && (cfg.getValue()==null || cfg.getValue()==val);
                if(r.isForceCancelOnChange()){
                    if(idMatch){
                        if(valueMatch){
                            if(!r.isLastConditionMet()){
                                executeRule(r,null,-1,null,null);
                                r.setLastConditionMet(true);
                                savePreset(r);
                            }
                        } else if(r.isLastConditionMet()){
                            r.setLastConditionMet(false);
                            softCancelOnChange(r);
                            savePreset(r);
                        }
                    }
                } else if(valueMatch){
                    executeRule(r,null,-1,null,null);
                }
            } else if(r.getTriggerType()== KPWebhookPreset.TriggerType.VARPLAYER){
                KPWebhookPreset.VarplayerConfig cfg = r.getVarplayerConfig();
                if(cfg==null) continue;
                int varpId = ev.getVarpId();
                boolean idMatch = matchesId(cfg.getVarplayerId(), cfg.getVarplayerIds(), varpId);
                int current = idMatch? client.getVarpValue(varpId): -999999;
                boolean valueMatch = idMatch && (cfg.getValue()==null || current==cfg.getValue());
                if(r.isForceCancelOnChange()){
                    if(idMatch){
                        if(valueMatch){
                            if(!r.isLastConditionMet()){
                                executeRule(r,null,-1,null,null);
                                r.setLastConditionMet(true);
                                savePreset(r);
                            }
                        } else if(r.isLastConditionMet()){
                            r.setLastConditionMet(false);
                            softCancelOnChange(r);
                            savePreset(r);
                        }
                    }
                } else if(valueMatch){
                    executeRule(r,null,-1,null,null);
                }
            }
        }
    }

    @Subscribe public void onChatMessage(ChatMessage ev){ if(ev==null) return; ChatMessageType type=ev.getType(); String msg=ev.getMessage(); if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.addMessage(type, type.ordinal(), ev.getName(), "", msg); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.MESSAGE) continue; KPWebhookPreset.MessageConfig cfg=r.getMessageConfig(); if(cfg==null) continue; boolean idOk=true; if(cfg.getMessageId()!=null) idOk = cfg.getMessageId()==type.ordinal(); boolean textOk=true; if(cfg.getMessageText()!=null && !cfg.getMessageText().isBlank()){ String pat=cfg.getMessageText().trim(); String regex = Pattern.quote(pat).replace("*", "\\E.*\\Q"); try { textOk = msg!=null && msg.toUpperCase(Locale.ROOT).matches("(?i)"+regex); } catch(Exception ignored){ textOk=false; } } if(idOk && textOk) executeRule(r,null,-1,null,null); }
    }

    @Subscribe public void onWidgetLoaded(WidgetLoaded ev){ if(ev==null) return; int grp=ev.getGroupId(); if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logWidget(grp,null); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.WIDGET_SPAWN) continue; KPWebhookPreset.WidgetConfig cfg=r.getWidgetConfig(); if(cfg==null) continue; if(cfg.getGroupId()==grp){ if(cfg.getChildId()==null) executeRule(r,null,-1,null,cfg); else { try{ Widget w= client.getWidget(grp, cfg.getChildId()); if(w!=null) executeRule(r,null,-1,null,cfg);}catch(Exception ignored){} } } }
    }

    @Subscribe public void onPlayerSpawned(PlayerSpawned ev){ if(delegatePlayerSpawnHandling) return; if(ev==null) return; Player p=ev.getPlayer(); if(p==null) return; try{ if(tokenService!=null) tokenService.updatePlayerSpawnToken(p,false); }catch(Exception ignored){} boolean self = client.getLocalPlayer()==p; String name=sanitizePlayerName(p.getName()); if(debugWindow!=null && debugWindow.isVisible()){ try{ int localCombat=0; try{ if(client.getLocalPlayer()!=null) localCombat=client.getLocalPlayer().getCombatLevel(); }catch(Exception ignored){} debugWindow.logPlayerSpawn(false,name,p.getCombatLevel(), localCombat); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.PLAYER_SPAWN){ if(playerMatches(r.getPlayerConfig(), p, self)) executeRule(r,null,-1,null,null,p); } }
    }
    @Subscribe public void onPlayerDespawned(PlayerDespawned ev){ if(delegatePlayerSpawnHandling) return; if(ev==null) return; Player p=ev.getPlayer(); if(p==null) return; try{ if(tokenService!=null) tokenService.updatePlayerSpawnToken(p,true); }catch(Exception ignored){} boolean self = client.getLocalPlayer()==p; String name=sanitizePlayerName(p.getName()); interactingPlayers.remove(name.toLowerCase(Locale.ROOT)); if(debugWindow!=null && debugWindow.isVisible()){ try{ int localCombat=0; try{ if(client.getLocalPlayer()!=null) localCombat=client.getLocalPlayer().getCombatLevel(); }catch(Exception ignored){} debugWindow.logPlayerSpawn(true,name,p.getCombatLevel(), localCombat); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.PLAYER_DESPAWN){ if(playerMatches(r.getPlayerConfig(), p, self)) executeRule(r,null,-1,null,null,p); } } }
    // New INTERACTING trigger handler
    @Subscribe public void onInteractingChanged(InteractingChanged ev){ if(ev==null) return; Actor src=ev.getSource(); Actor tgt=ev.getTarget(); Player local; try { local=client.getLocalPlayer(); } catch(Exception e){ return; } if(local==null) return; if(!(src instanceof Player)) return; Player other=(Player)src; if(other==local) return; String norm = sanitizePlayerName(other.getName()).toLowerCase(Locale.ROOT); boolean now= tgt==local; boolean was=interactingPlayers.contains(norm); if(now && !was){ interactingPlayers.add(norm); try { if(tokenService!=null) tokenService.updateInteraction(other); } catch(Exception ignored){} for(KPWebhookPreset r: rules){ if(r==null||!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.INTERACTING){ // apply optional player filter (names/combat range)
                    if(!playerMatches(r.getPlayerConfig(), other, false)) continue; KPWebhookPreset.InteractingConfig ic = r.getInteractingConfig(); if(ic!=null){ if(ic.isOnlyPvPOrWilderness() && !(isInWilderness() || isPvPWorld())) continue; if(ic.isOnlyAttackable() && !isAttackablePerRules(local, other)) continue; }
                    if(r.isForceCancelOnChange() && r.isLastConditionMet()) softCancelOnChange(r); executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig(), other, null); r.setLastConditionMet(true); savePreset(r); } } } else if(!now && was){ interactingPlayers.remove(norm); for(KPWebhookPreset r: rules){ if(r==null||!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.INTERACTING && r.isForceCancelOnChange() && r.isLastConditionMet()){ softCancelOnChange(r); r.setLastConditionMet(false); savePreset(r); } } } }
    private void addOverheadTextFromPreset(String text,String pos,KPWebhookPreset rule){
        if(text==null||text.isBlank()||rule==null) return;
        if(rule.isForceCancelOnChange()){
            try { removeOverheadsForRule(rule.getId(), true); } catch(Exception ignored){}
        }
        ActiveOverheadText ot=new ActiveOverheadText();
        ot.text=text; ot.position=pos; ot.ruleId=rule.getId(); ot.color=Color.WHITE; ot.size=16; ot.remainingTicks=80;
        overheadTexts.add(ot);
    }
    public void addOverheadText(KPWebhookPreset rule,String text,String pos){
        if(rule!=null){
            if(rule.isForceCancelOnChange()){
                try { removeOverheadsForRule(rule.getId(), true); } catch(Exception ignored){}
            }
            addOverheadTextFromPreset(text,pos!=null?pos:"Above",rule); return;
        }
        ActiveOverheadText ot=new ActiveOverheadText(); ot.text=text; ot.position=pos!=null?pos:"Above"; ot.remainingTicks=80; overheadTexts.add(ot);
    }

    private void applyPresetOverheadTexts(KPWebhookPreset rule, Map<String,String> ctx){ if(rule==null) return; try {
        if(rule.isForceCancelOnChange()) removeOverheadsForRule(rule.getId(), true);
        if(rule.getTextOver()!=null && !rule.getTextOver().isBlank()){
            ActiveOverheadText ot=new ActiveOverheadText(); ot.ruleId=rule.getId(); ot.position="Above"; ot.text=expand(rule.getTextOver(), ctx); ot.color=parseColor(rule.getTextOverColor(), Color.WHITE); ot.blink=Boolean.TRUE.equals(rule.getTextOverBlink()); ot.size=safeInt(rule.getTextOverSize(),16); ot.remainingTicks=Math.max(1,safeInt(rule.getTextOverDuration(),80)); ot.bold=Boolean.TRUE.equals(rule.getTextOverBold()); ot.italic=Boolean.TRUE.equals(rule.getTextOverItalic()); ot.underline=Boolean.TRUE.equals(rule.getTextOverUnderline()); ot.blinkInterval=2; overheadTexts.add(ot); }
        if(rule.getTextCenter()!=null && !rule.getTextCenter().isBlank()){
            ActiveOverheadText ot=new ActiveOverheadText(); ot.ruleId=rule.getId(); ot.position="Center"; ot.text=expand(rule.getTextCenter(), ctx); ot.color=parseColor(rule.getTextCenterColor(), Color.WHITE); ot.blink=Boolean.TRUE.equals(rule.getTextCenterBlink()); ot.size=safeInt(rule.getTextCenterSize(),16); ot.remainingTicks=Math.max(1,safeInt(rule.getTextCenterDuration(),80)); ot.bold=Boolean.TRUE.equals(rule.getTextCenterBold()); ot.italic=Boolean.TRUE.equals(rule.getTextCenterItalic()); ot.underline=Boolean.TRUE.equals(rule.getTextCenterUnderline()); ot.blinkInterval=2; overheadTexts.add(ot); }
        if(rule.getTextUnder()!=null && !rule.getTextUnder().isBlank()){
            ActiveOverheadText ot=new ActiveOverheadText(); ot.ruleId=rule.getId(); ot.position="Under"; ot.text=expand(rule.getTextUnder(), ctx); ot.color=parseColor(rule.getTextUnderColor(), Color.WHITE); ot.blink=Boolean.TRUE.equals(rule.getTextUnderBlink()); ot.size=safeInt(rule.getTextUnderSize(),16); ot.remainingTicks=Math.max(1,safeInt(rule.getTextUnderDuration(),80)); ot.bold=Boolean.TRUE.equals(rule.getTextUnderBold()); ot.italic=Boolean.TRUE.equals(rule.getTextUnderItalic()); ot.underline=Boolean.TRUE.equals(rule.getTextUnderUnderline()); ot.blinkInterval=2; overheadTexts.add(ot); }
    } catch(Exception ignored){} }
    private int safeInt(Integer v,int def){ return v==null?def:v; }
    private Color parseColor(String hex, Color def){ if(hex==null||hex.isBlank()) return def; try { String h=hex.trim(); if(h.startsWith("#")) h=h.substring(1); if(h.length()==6){ return new Color(Integer.parseInt(h,16)); } if(h.length()==8){ return new Color((int)Long.parseLong(h,16), true); } } catch(Exception ignored){} return def; }

    private String expand(String input, Map<String,String> ctx){ return tokenService!=null? tokenService.expand(input, ctx): input; }
    private String safeReplaceDollarToken(String text,String key,String value){ return text; }

    private void processActionLine(KPWebhookPreset rule, String rawLine, Map<String,String> ctx){
        if(rawLine==null) return; String line=rawLine.trim(); if(line.isEmpty()||line.startsWith("#")) return;
        String expanded = expand(line, ctx!=null?ctx:Collections.emptyMap());
        try { if(overlayTextCommandHandler!=null && overlayTextCommandHandler.handle(expanded, rule)) return; } catch(Exception ignored){}
        try { if(infoboxCommandHandler!=null && infoboxCommandHandler.handle(expanded, rule, ctx)) return; } catch(Exception ignored){}
        try { if(highlightCommandHandler!=null && highlightCommandHandler.handle(line, rule)) return; } catch(Exception ignored){}
        try { if(markTileCommandHandler!=null && markTileCommandHandler.handle(line, rule)) return; } catch(Exception ignored){}
        try { if(customMessageCommandHandler!=null && customMessageCommandHandler.handle(expanded, rule, ctx)) return; } catch(Exception ignored){}
        try {
            if(P_TEXT_OVER.matcher(line).find()||P_TEXT_UNDER.matcher(line).find()||P_TEXT_CENTER.matcher(line).find()){ handleTargetedTextCommand(rule, expanded, ctx); return; }
            if(P_IMG_OVER.matcher(line).find()||P_IMG_UNDER.matcher(line).find()||P_IMG_CENTER.matcher(line).find()){ handleImageCommand(rule, expanded, ctx); return; }
        } catch(Exception ignored){}
        String upper=line.toUpperCase(Locale.ROOT);
        if(upper.equals("STOP")){ try { activeSequences.clear(); highlightManager.clear(); overlayTextManager.clear(); if(infoboxCommandHandler!=null) infoboxCommandHandler.clearAll(); overheadTexts.clear(); overheadImages.clear(); markTileManager.clear(); } catch(Exception ignored){} return; }
        if(upper.equals("STOP_RULE")){ try { cancelSequencesForRule(rule.getId()); removeOverheadsForRule(rule.getId(),true); if(infoboxCommandHandler!=null) infoboxCommandHandler.removeByRule(rule.getId()); overlayTextManager.removeByRule(rule.getId()); highlightManager.removeAllByRule(rule.getId()); markTileManager.removeByRule(rule.getId()); } catch(Exception ignored){} return; }
        if(upper.startsWith("NOTIFY")){ try { notifier.notify(expanded.length()>6? expanded.substring(6).trim():"Event"); } catch(Exception ignored){} return; }
        if(upper.startsWith("WEBHOOK")){ try { String rest=expanded.substring(7).trim(); String[] parts=rest.split("\\s+",2); String url=getDefaultWebhook(); String payload=""; if(parts.length==1){ payload=parts[0]; } else { if(parts[0].startsWith("http")){ url=parts[0]; payload=parts[1]; } else payload=rest; } if(url!=null && !url.isBlank() && payload!=null){ sendWebhook(url.trim(), payload); } } catch(Exception ignored){} return; }
        if(upper.startsWith("SCREENSHOT")){ try { requestScreenshot(rule, ctx); } catch(Exception ignored){} return; }
        if(upper.startsWith("TOGGLEPRESET")){ try { int rid=Integer.parseInt(expanded.substring("TOGGLEPRESET".length()).trim()); toggleActive(rid); if(panel!=null) panel.refreshTable(); } catch(Exception ignored){} return; }
        if(upper.startsWith("TOGGLEGROUP")){ return; }
        try { addOverheadText(rule, expanded, "Above"); } catch(Exception ignored){}
    }
    private void cancelSequencesForRule(int ruleId){ try { activeSequences.removeIf(s-> s!=null && s.rule!=null && s.rule.getId()==ruleId); } catch(Exception ignored){} }
    private void removeOverheadsForRule(int ruleId, boolean includeImages){ try { overheadTexts.removeIf(t->t!=null && t.ruleId==ruleId); if(includeImages) overheadImages.removeIf(i->i!=null && i.ruleId==ruleId); } catch(Exception ignored){} }
    private void removePersistentOverheadsForRule(int ruleId){ try { overheadTexts.removeIf(t->t!=null && t.ruleId==ruleId && t.persistent); overheadImages.removeIf(i->i!=null && i.ruleId==ruleId && i.persistent); } catch(Exception ignored){} }

    private void handleTargetedTextCommand(KPWebhookPreset rule, String expanded, Map<String,String> ctx){
        if(expanded==null) return; if(rule!=null && rule.isForceCancelOnChange()) try { removeOverheadsForRule(rule.getId(), true);} catch(Exception ignored){}
        String line=expanded.trim();
        java.util.regex.Matcher mUnder=P_TEXT_UNDER.matcher(line), mOver=P_TEXT_OVER.matcher(line), mCenter=P_TEXT_CENTER.matcher(line);
        String position=null,payload=null; if(mUnder.find()){ position="Under"; payload=mUnder.group(1).trim(); } else if(mOver.find()){ position="Above"; payload=mOver.group(1).trim(); } else if(mCenter.find()){ position="Center"; payload=mCenter.group(1).trim(); }
        if(position==null||payload==null||payload.isBlank()) return;
        ActiveOverheadText.TargetType targetType=ActiveOverheadText.TargetType.LOCAL_PLAYER; Set<String> targetNames=null; Set<Integer> targetIds=null; String working=payload; String[] toks=working.split("\\s+",3);
        if(toks.length>=1){ String t0=toks[0].toUpperCase(Locale.ROOT); boolean consumed=false; if(t0.equals("TARGET")){ targetType=ActiveOverheadText.TargetType.TARGET; consumed=true; } else if(t0.equals("LOCAL_PLAYER")){ consumed=true; } else if(t0.equals("PLAYER") && toks.length>=2){ targetType=ActiveOverheadText.TargetType.PLAYER_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(toks[1])); if(toks.length>=3) working=toks[2]; else working=""; consumed=false; } else if(t0.equals("NPC") && toks.length>=2){ String spec=toks[1]; if(spec.matches("\\d+")){ targetType=ActiveOverheadText.TargetType.NPC_ID; targetIds=new HashSet<>(); try{ targetIds.add(Integer.parseInt(spec)); }catch(Exception ignored){} } else { targetType=ActiveOverheadText.TargetType.NPC_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(spec)); } consumed=true; } if(consumed) working=working.substring(toks[0].length()).trim(); }
        if(working.isEmpty()) return; String finalText=expand(working, ctx!=null?ctx:Collections.emptyMap()); ActiveOverheadText ot=new ActiveOverheadText(); ot.setRuleId(rule!=null?rule.getId():-1); ot.setPosition(position); ot.setText(finalText);
        Color color=Color.WHITE; int size=16; boolean blink=false,bold=false,italic=false,underline=false; int duration=80;
        if(rule!=null){ if("Above".equals(position)){ color=parseColor(rule.getTextOverColor(), color); size=safeInt(rule.getTextOverSize(),16); blink=Boolean.TRUE.equals(rule.getTextOverBlink()); bold=Boolean.TRUE.equals(rule.getTextOverBold()); italic=Boolean.TRUE.equals(rule.getTextOverItalic()); underline=Boolean.TRUE.equals(rule.getTextOverUnderline()); duration=safeInt(rule.getTextOverDuration(),80);} else if("Under".equals(position)){ color=parseColor(rule.getTextUnderColor(), color); size=safeInt(rule.getTextUnderSize(),16); blink=Boolean.TRUE.equals(rule.getTextUnderBlink()); bold=Boolean.TRUE.equals(rule.getTextUnderBold()); italic=Boolean.TRUE.equals(rule.getTextUnderItalic()); underline=Boolean.TRUE.equals(rule.getTextUnderUnderline()); duration=safeInt(rule.getTextUnderDuration(),80);} else if("Center".equals(position)){ color=parseColor(rule.getTextCenterColor(), color); size=safeInt(rule.getTextCenterSize(),16); blink=Boolean.TRUE.equals(rule.getTextCenterBlink()); bold=Boolean.TRUE.equals(rule.getTextCenterBold()); italic=Boolean.TRUE.equals(rule.getTextCenterItalic()); underline=Boolean.TRUE.equals(rule.getTextCenterUnderline()); duration=safeInt(rule.getTextCenterDuration(),80);} }
        ot.setColor(color); ot.setSize(size); ot.setBlink(blink); ot.setBold(bold); ot.setItalic(italic); ot.setUnderline(underline); ot.setBlinkInterval(blink?2:0); ot.setBlinkCounter(0); if(duration<=0){ ot.setPersistent(true); ot.setRemainingTicks(0);} else { ot.setPersistent(false); ot.setRemainingTicks(Math.max(1,duration)); } ot.setTargetType(targetType); ot.setTargetNames(targetNames); ot.setTargetIds(targetIds); overheadTexts.add(ot);
    }
    private String normalizeName(String n){ if(n==null) return ""; return n.replace('_',' ').trim().toLowerCase(Locale.ROOT); }

    private void handleImageCommand(KPWebhookPreset rule, String expanded, Map<String,String> ctx){
        String line=expanded==null?"":expanded.trim(); if(rule!=null && rule.isForceCancelOnChange()) try { removeOverheadsForRule(rule.getId(), true);}catch(Exception ignored){}
        java.util.regex.Matcher mO=P_IMG_OVER.matcher(line), mU=P_IMG_UNDER.matcher(line), mC=P_IMG_CENTER.matcher(line); String position=null, rest=null; if(mO.find()){ position="Above"; rest=mO.group(1).trim(); } else if(mU.find()){ position="Under"; rest=mU.group(1).trim(); } else if(mC.find()){ position="Center"; rest=mC.group(1).trim(); } if(position==null||rest==null||rest.isBlank()) return;
        String[] toks=rest.split("\\s+",3); ActiveOverheadImage.TargetType targetType=ActiveOverheadImage.TargetType.LOCAL_PLAYER; Set<String> targetNames=null; Set<Integer> targetIds=null; int idx=0; if(toks.length>0){ String t0=toks[0].toUpperCase(Locale.ROOT); boolean consumed=false; if(t0.equals("TARGET")){ targetType=ActiveOverheadImage.TargetType.TARGET; consumed=true;} else if(t0.equals("LOCAL_PLAYER")){ consumed=true;} else if(t0.equals("PLAYER") && toks.length>=2){ targetType=ActiveOverheadImage.TargetType.PLAYER_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(toks[1])); idx=2; } else if(t0.equals("NPC") && toks.length>=2){ String spec=toks[1]; if(spec.matches("\\d+")){ targetType=ActiveOverheadImage.TargetType.NPC_ID; targetIds=new HashSet<>(); try{ targetIds.add(Integer.parseInt(spec)); }catch(Exception ignored){} } else { targetType=ActiveOverheadImage.TargetType.NPC_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(spec)); } idx=2; } if(consumed) idx=1; }
        if(idx>=toks.length) return; String idTok=toks[idx]; int itemOrSpriteId; boolean sprite=false; try { if(idTok.startsWith("-")){ sprite=true; itemOrSpriteId=Integer.parseInt(idTok.substring(1)); } else itemOrSpriteId=Integer.parseInt(idTok); } catch(Exception e){ return; }
        int duration=rule!=null? safeInt(rule.getImgDuration(),100):100; boolean blink=rule!=null && Boolean.TRUE.equals(rule.getImgBlink()); ActiveOverheadImage oi=new ActiveOverheadImage(); oi.setItemOrSpriteId(itemOrSpriteId); oi.setSprite(sprite); oi.setPosition(position); oi.setBlink(blink); oi.setBlinkInterval(blink?2:0); oi.setBlinkCounter(0); oi.setVisiblePhase(true); oi.setRuleId(rule!=null?rule.getId():-1); oi.setTargetType(targetType); oi.setTargetNames(targetNames); oi.setTargetIds(targetIds); if(duration<=0){ oi.setPersistent(true); oi.setRemainingTicks(0);} else { oi.setPersistent(false); oi.setRemainingTicks(Math.max(1,duration)); }
        try { if(sprite){ spriteManager.getSpriteAsync(itemOrSpriteId,0, s->{ if(s!=null) oi.setImage(s); }); } else { BufferedImage img=itemManager.getImage(itemOrSpriteId); if(img!=null) oi.setImage(img); } } catch(Exception ignored){}
        overheadImages.add(oi);
    }

    private void processGearChanged(){
        Player local=null; try { local=client.getLocalPlayer(); } catch(Exception ignored){}
        if(local!=null){ try { ItemContainer equip=client.getItemContainer(InventoryID.EQUIPMENT); if(equip!=null){ int[] ids=Arrays.stream(equip.getItems()).mapToInt(i-> i!=null? i.getId():-1).toArray(); Set<Integer> current=new HashSet<>(); for(int id: ids) if(id>0) current.add(id); if(!localGearInitialized){ lastLocalGear.clear(); lastLocalGear.addAll(current); localGearInitialized=true; } else if(!current.equals(lastLocalGear)){ Set<Integer> added=new HashSet<>(current); added.removeAll(lastLocalGear); for(Integer add: added){ if(add!=null) gearChanged(false, add); } lastLocalGear.clear(); lastLocalGear.addAll(current); } } } catch(Exception ignored){} }
    }
    private void gearChanged(boolean target,int itemId){ String name=""; try { name=itemManager.getItemComposition(itemId).getName(); } catch(Exception ignored){} if(debugWindow!=null && debugWindow.isVisible()){ try { debugWindow.logGearChange(target,itemId,name);}catch(Exception ignored){} } for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; KPWebhookPreset.TriggerType tt=r.getTriggerType(); if((!target && tt== KPWebhookPreset.TriggerType.GEAR_CHANGED) || (target && tt== KPWebhookPreset.TriggerType.TARGET_GEAR_CHANGED)){ if(matchesGear(r.getGearConfig(), itemId, name)) executeRule(r,null,-1,null,null); } } }
    private boolean matchesGear(KPWebhookPreset.GearConfig cfg,int itemId,String name){ if(cfg==null) return true; String low=name!=null? name.toLowerCase(Locale.ROOT):""; if(cfg.getItemIds()!=null && cfg.getItemIds().contains(itemId)) return true; if(cfg.getNames()!=null && cfg.getNames().contains(low)) return true; if(cfg.getWildcards()!=null){ for(String w: cfg.getWildcards()){ if(w==null) continue; String pattern=w.toLowerCase(Locale.ROOT); if(!pattern.contains("*")){ if(low.contains(pattern)) return true; } else { String regex=Pattern.quote(pattern).replace("*","\\E.*\\Q"); if(low.matches("(?i)"+regex)) return true; } } } return false; }

    private void ensureUniqueIds(){ Set<Integer> used=new HashSet<>(); for(KPWebhookPreset r: new ArrayList<>(rules)){ if(r==null) continue; if(r.getId()<0 || used.contains(r.getId())) r.setId(nextId++); used.add(r.getId()); } for(KPWebhookPreset r: rules) if(r!=null && r.getId()>=nextId) nextId=r.getId()+1; }
    private boolean matchesId(Integer single,List<Integer> list,int id){ if(list!=null && !list.isEmpty()) return list.contains(id); if(single!=null) return single==id; return false; }
    private boolean matchesAnim(KPWebhookPreset.AnimationConfig cfg,int anim){ if(cfg==null) return true; if(cfg.getAnimationIds()!=null && !cfg.getAnimationIds().isEmpty()) return cfg.getAnimationIds().contains(anim); if(cfg.getAnimationId()!=null) return cfg.getAnimationId()==anim; return false; }
    private boolean matchesGraphic(KPWebhookPreset.GraphicConfig cfg,int gid){ if(cfg==null) return true; if(cfg.getGraphicIds()!=null && !cfg.getGraphicIds().isEmpty()) return cfg.getGraphicIds().contains(gid); if(cfg.getGraphicId()!=null) return cfg.getGraphicId()==gid; return false; }
    private boolean matchesProjectile(KPWebhookPreset.ProjectileConfig cfg,int pid){ if(cfg==null) return true; if(cfg.getProjectileIds()!=null && !cfg.getProjectileIds().isEmpty()) return cfg.getProjectileIds().contains(pid); if(cfg.getProjectileId()!=null) return cfg.getProjectileId()==pid; return cfg.getProjectileIds()==null || cfg.getProjectileIds().isEmpty(); }

    public void manualSend(int id){ KPWebhookPreset r=find(id); if(r==null) return; executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig()); if(debugWindow!=null && debugWindow.isVisible()){ try { debugWindow.logManual(r.getId(), r.getTitle()); } catch(Exception ignored){} } }
    private void sendWebhook(String url,String payload){ if(url==null||url.isBlank()||payload==null) return; RequestBody body=RequestBody.create(JSON, payload); Request req=new Request.Builder().url(url).post(body).build(); okHttpClient.newCall(req).enqueue(new Callback(){ @Override public void onFailure(Call call,IOException e){ log.debug("Webhook failed: {}", e.getMessage()); } @Override public void onResponse(Call call,Response response){ try(response){ } catch(Exception ignored){} } }); }
    private void requestScreenshot(KPWebhookPreset rule, Map<String,String> ctx){ long now=System.currentTimeMillis(); if(now-lastScreenshotRequestMs<SCREENSHOT_COOLDOWN_MS) return; lastScreenshotRequestMs=now; BufferedImage img=lastFrame; if(img==null) return; try { ByteArrayOutputStream baos=new ByteArrayOutputStream(); ImageIO.write(img,"png",baos); String b64=Base64.getEncoder().encodeToString(baos.toByteArray()); String payload="{\"screenshot\":\""+b64+"\"}"; String url= rule!=null && !rule.isUseDefaultWebhook() && rule.getWebhookUrl()!=null && !rule.getWebhookUrl().isBlank()? rule.getWebhookUrl(): getDefaultWebhook(); if(url!=null && !url.isBlank()) sendWebhook(url,payload); } catch(Exception ignored){} }
    private boolean isMostlyBlack(BufferedImage img){ if(img==null) return true; int w=img.getWidth(), h=img.getHeight(); long dark=0,total=0; for(int y=0;y<h;y+=10){ for(int x=0;x<w;x+=10){ int rgb=img.getRGB(x,y); int r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255; int lum=r+g+b; if(lum<60) dark++; total++; } } return total>0 && dark>(total*80/100); }
    private boolean isMostlyWhite(BufferedImage img){ if(img==null) return false; int w=img.getWidth(), h=img.getHeight(); long light=0,total=0; for(int y=0;y<h;y+=10){ for(int x=0;x<w;x+=10){ int rgb=img.getRGB(x,y); int r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255; int lum=r+g+b; if(lum>700) light++; total++; } } return total>0 && light>(total*80/100); }

    // ===== Restored helper methods & public accessors referenced elsewhere =====
    public String sanitizePlayerName(String n){ if(n==null) return ""; try { String nt=net.runelite.client.util.Text.removeTags(n); return nt.replace('\u00A0',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').trim(); } }
    private String sanitizeNpcName(String n){ if(n==null) return ""; try { String nt=net.runelite.client.util.Text.removeTags(n); return nt.replace('\u00A0',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').trim(); } }
    public String getDefaultWebhook(){ String def=config!=null? config.defaultWebhookUrl():null; return def!=null? def.trim():""; }
    public void logPlayerSpawn(boolean despawn, Player p){ if(p==null) return; if(debugWindow!=null && debugWindow.isVisible()){ try { String name=sanitizePlayerName(p.getName()); int localCombat=0; try { if(client.getLocalPlayer()!=null) localCombat=client.getLocalPlayer().getCombatLevel(); } catch(Exception ignored){} debugWindow.logPlayerSpawn(despawn,name,p.getCombatLevel(), localCombat); } catch(Exception ignored){} } }
    public void executePlayerTrigger(KPWebhookPreset rule, Player p){ if(rule==null || p==null) return; executeRule(rule,null,-1,null,null,p); }
    private boolean playerMatches(KPWebhookPreset.PlayerConfig cfg, Player p, boolean self){ if(cfg==null) return true; if(self) return false; if(cfg.isAll()) return true; if(cfg.getCombatRange()!=null){ try { int local= client.getLocalPlayer()!=null? client.getLocalPlayer().getCombatLevel():0; return Math.abs(local - p.getCombatLevel())<= cfg.getCombatRange(); } catch(Exception ignored){ return false; } } List<String> names=cfg.getNames(); if(names!=null && !names.isEmpty()){ String pn=sanitizePlayerName(p.getName()).toLowerCase(Locale.ROOT); for(String n: names) if(pn.equals(n)) return true; return false; } if(cfg.getName()!=null && !cfg.getName().isBlank()){ return sanitizePlayerName(p.getName()).equalsIgnoreCase(cfg.getName()); } return true; }
    public void openDebugWindow(){ try { if(debugWindow==null) debugWindow=new KPWebhookDebugWindow(this); if(!debugWindow.isVisible()) { debugWindow.setVisible(true); if(client.getGameState()==GameState.LOGGED_IN){ // log baseline
                    baselineStatsLogged=true; for(Skill s: Skill.values()){ if(s==null) continue; int real=-1, boosted=-1; try { real=client.getRealSkillLevel(s);}catch(Exception ignored){} try { boosted=client.getBoostedSkillLevel(s);}catch(Exception ignored){ boosted=real; } if(real<=0 && boosted<=0) continue; debugWindow.logStat(s.name(), real, boosted); lastLoggedRealLevel.put(s, real); lastLoggedBoostedLevel.put(s, boosted); } }
                }
            else debugWindow.toFront(); } catch(Exception ignored){} }
    public void openPresetDebugWindow(){ try { if(presetDebugWindow==null) presetDebugWindow=new KPWebhookPresetDebugWindow(); if(!presetDebugWindow.isVisible()) presetDebugWindow.setVisible(true); else presetDebugWindow.toFront(); } catch(Exception ignored){} }

    // === Wilderness / PvP helpers for INTERACTING trigger ===
    private static final int VARBIT_WILDERNESS_LEVEL = 5963; // fallback id for wilderness level
    private int getWildernessLevel(){ try { return client.getVarbitValue(VARBIT_WILDERNESS_LEVEL); } catch(Exception e){ return 0; } }
    private boolean isInWilderness(){ return getWildernessLevel() > 0; }
    private boolean isPvPWorld(){ try { EnumSet<WorldType> types = client.getWorldType(); return types!=null && (types.contains(WorldType.PVP) || types.contains(WorldType.HIGH_RISK) || types.contains(WorldType.DEADMAN) || types.contains(WorldType.SEASONAL)); } catch(Exception e){ return false; } }
    private boolean isAttackablePerRules(Player local, Player other){ if(local==null || other==null) return false; int l=0,o=0; try { l=local.getCombatLevel(); o=other.getCombatLevel(); } catch(Exception ignored){} int diff=Math.abs(l-o); boolean pvp=isPvPWorld(); int wild=getWildernessLevel(); int maxDiff; if(wild>0){ maxDiff = wild + (pvp?15:0); } else if(pvp){ maxDiff=15; } else return false; return diff <= maxDiff; }
}
