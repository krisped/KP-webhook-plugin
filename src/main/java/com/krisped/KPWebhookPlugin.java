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
import net.runelite.client.eventbus.EventBus; // added for registering external services
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
import com.krisped.triggers.xp.XpDropTriggerService; // added

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
        description = "Triggers: MANUAL, STAT, XP_DROP, WIDGET_SPAWN, PLAYER_SPAWN, PLAYER_DESPAWN, NPC_SPAWN, NPC_DESPAWN, ANIMATION_SELF, ANIMATION_TARGET, ANIMATION_ANY, GRAPHIC_SELF, GRAPHIC_TARGET, GRAPHIC_ANY, PROJECTILE_SELF, PROJECTILE, HITSPLAT_SELF, HITSPLAT_TARGET, MESSAGE, VARBIT, VARPLAYER, TICK, TARGET, IDLE, GEAR_CHANGED, TARGET_GEAR_CHANGED, REGION, INVENTORY_FULL, INVENTORY_ITEM_ADDED, INVENTORY_ITEM_REMOVED, INVENTORY_CONTAINS_NONE, LOOT_DROP, ITEM_SPAWN, DEATH, INTERACTING.",
        tags = {"webhook","stat","trigger","screenshot","widget","highlight","text","player","npc","varbit","varplayer","tick","overlay","target","graphic","hitsplat","projectile","idle","gear","region","inventory","loot","item","death","xp","interacting"}
)
public class KPWebhookPlugin extends Plugin {
    // Anti-spam cache for heavy commands in TICK presets
    private final Map<Integer, Set<String>> tickRulePersistentCommands = new HashMap<>();
    // Track remote players currently interacting with local player (for INTERACTING trigger dedupe)
    private final Set<String> interactingPlayers = new HashSet<>();

    // Injected chat dedupe (prevent CUSTOM_MESSAGE from retriggering MESSAGE rules)
    private static class InjectedChatEntry { ChatMessageType type; String msg; long ts; InjectedChatEntry(ChatMessageType t,String m,long ts){this.type=t;this.msg=m;this.ts=ts;} }
    private final java.util.Deque<InjectedChatEntry> injectedChatBuffer = new java.util.ArrayDeque<>();
    private String normChat(String m){ if(m==null) return ""; return m.replaceAll("<[^>]*>", "").trim(); }
    public void markInjectedChat(ChatMessageType type, String message){ long now=System.currentTimeMillis(); String norm=normChat(message); synchronized(injectedChatBuffer){ injectedChatBuffer.addLast(new InjectedChatEntry(type,norm,now)); // also store tail variant if name stripped
            int sp = norm.indexOf(' '); if(sp>0 && sp+1<norm.length()){ String tail = norm.substring(sp+1).trim(); if(!tail.isEmpty()) injectedChatBuffer.addLast(new InjectedChatEntry(type, tail, now)); }
            // NEW: also store head (everything before last word) to suppress MESSAGE rules with wildcard patterns like "KILL TARGET *"
            int ls = norm.lastIndexOf(' ');
            if(ls>0){ String head = norm.substring(0, ls).trim(); if(!head.isEmpty()) injectedChatBuffer.addLast(new InjectedChatEntry(type, head, now)); }
            while(!injectedChatBuffer.isEmpty()){ InjectedChatEntry first=injectedChatBuffer.peekFirst(); if(first==null) break; if(now-first.ts>4000L || injectedChatBuffer.size()>64) injectedChatBuffer.removeFirst(); else break; } } }
    private boolean isInjectedChat(ChatMessageType type, String message){ long now=System.currentTimeMillis(); String norm=normChat(message); synchronized(injectedChatBuffer){ java.util.Iterator<InjectedChatEntry> it=injectedChatBuffer.iterator(); while(it.hasNext()){ InjectedChatEntry e=it.next(); if(now-e.ts>4000L){ it.remove(); continue; } if(e.type==type && e.msg.equals(norm)) return true; } } return false; }

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
    @Inject private XpDropTriggerService xpDropTriggerService; // new XP_DROP trigger service
    @Inject private EventBus eventBus; // added event bus to register external subscribers

    private KPWebhookPanel panel; private NavigationButton navButton; private KPWebhookStorage storage;
    private final List<KPWebhookPreset> rules = new ArrayList<>(); private int nextId=0; private KPWebhookDebugWindow debugWindow; private KPWebhookPresetDebugWindow presetDebugWindow;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8"); private static final MediaType PNG = MediaType.parse("image/png");
    private final Map<Skill,Integer> lastRealLevel = new EnumMap<>(Skill.class); private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    // New: track last logged values for debug window (separate from baseline) to avoid spam
    private final Map<Skill,Integer> lastLoggedRealLevel = new EnumMap<>(Skill.class);
    private final Map<Skill,Integer> lastLoggedBoostedLevel = new EnumMap<>(Skill.class);
    private final Map<Skill,Integer> lastLoggedXp = new EnumMap<>(Skill.class); // new: track previous XP for XP_DROP debug
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

    private final Set<Integer> seenProjectiles = new HashSet<>(); // restored per-tick projectile dedupe
    private final Set<Integer> seenProjectileIdentities = new HashSet<>();
    // Removed projectileSelf/Target caches (use single classification on first move)
    // Dedup varplayer logging
    private final Map<Integer,Integer> lastVarplayerValue = new HashMap<>();
    private final Map<Integer,Integer> lastVarplayerTick = new HashMap<>();
    // Restore baseline stats logged flag (used by debug window)
    private boolean baselineStatsLogged = false;

    // External settings and periodic refresh
    private KPWebhookUserSettings userSettings; // external UI settings
    private java.util.concurrent.ScheduledFuture<?> lastTriggeredRefreshFuture; // periodic refresh task

    // Overhead texts
    @Data public static class ActiveOverheadText { String text; Color color; boolean blink; int size; String position; int remainingTicks; boolean visiblePhase; int blinkCounter; int blinkInterval; boolean bold; boolean italic; boolean underline; String key; boolean persistent; int ruleId=-1; public enum TargetType { LOCAL_PLAYER, PLAYER_NAME, NPC_NAME, NPC_ID, TARGET, FRIEND_LIST, IGNORE_LIST, PARTY_MEMBERS, FRIENDS_CHAT, TEAM_MEMBERS, CLAN_MEMBERS, OTHERS, PLAYER_SPAWN, INTERACTION, ITEM_SPAWN, LOOT_DROP, NPC_SPAWN } TargetType targetType=TargetType.LOCAL_PLAYER; Set<String> targetNames; Set<Integer> targetIds; }
    private final List<ActiveOverheadText> overheadTexts = new ArrayList<>(); public List<ActiveOverheadText> getOverheadTexts(){ return overheadTexts; }

    // Overhead images
    @Data public static class ActiveOverheadImage { BufferedImage image; int itemOrSpriteId; boolean sprite; String position; int remainingTicks; boolean persistent; boolean blink; int blinkCounter; int blinkInterval=2; boolean visiblePhase=true; int ruleId=-1; public enum TargetType { LOCAL_PLAYER, PLAYER_NAME, NPC_NAME, NPC_ID, TARGET, FRIEND_LIST, IGNORE_LIST, PARTY_MEMBERS, FRIENDS_CHAT, TEAM_MEMBERS, CLAN_MEMBERS, OTHERS, PLAYER_SPAWN, INTERACTION, ITEM_SPAWN, LOOT_DROP, NPC_SPAWN } TargetType targetType=TargetType.LOCAL_PLAYER; Set<String> targetNames; Set<Integer> targetIds; }
    private final List<ActiveOverheadImage> overheadImages = new ArrayList<>(); public List<ActiveOverheadImage> getOverheadImages(){ return overheadImages; }

    // Flag controlling whether to bypass legacy player spawn handlers (kept false to preserve original behavior)
    @SuppressWarnings("unused") private static final boolean delegatePlayerSpawnHandling = true; // now handled by PlayerTriggerService

    // Sequencing
    private enum PendingType { ACTION, TICK_DELAY, SLEEP_DELAY }
    @Data private static class PendingCommand { PendingType type; String line; int ticks; long sleepMs; }
    @Data private static class CommandSequence { KPWebhookPreset rule; Map<String,String> ctx; List<PendingCommand> commands; int index; int tickDelayRemaining; long sleepUntilMillis; }
    private final List<CommandSequence> activeSequences = new ArrayList<>();

    // Patterns
    private static final Pattern P_TEXT_UNDER = Pattern.compile("(?i)^TEXT_UNDER\\s+(.*)"); private static final Pattern P_TEXT_OVER = Pattern.compile("(?i)^TEXT_OVER\\s+(.*)"); private static final Pattern P_TEXT_CENTER = Pattern.compile("(?i)^TEXT_CENTER\\s+(.*)"); private static final Pattern P_IMG_OVER = Pattern.compile("(?i)^IMG_OVER\\s+(.+)"); private static final Pattern P_IMG_UNDER = Pattern.compile("(?i)^IMG_UNDER\\s+(.+)"); private static final Pattern P_IMG_CENTER = Pattern.compile("(?i)^IMG_CENTER\\s+(.+)");

    @Provides KPWebhookConfig provideConfig(ConfigManager cm){ return cm.getConfig(KPWebhookConfig.class); }

    @Override protected void startUp(){
        userSettings = KPWebhookUserSettings.load();
        // One-time migration from legacy Runelite config value (if any) -> external file
        try {
            if(userSettings!=null && !userSettings.isMigratedShowLastTriggered()){
                if(config!=null && config.showLastTriggered()){
                    userSettings.setShowLastTriggered(true);
                }
                userSettings.setMigratedShowLastTriggered(true);
                userSettings.save();
            }
        } catch(Exception ignored){}
        panel = new KPWebhookPanel(this); navButton = NavigationButton.builder().tooltip("KP Webhook").icon(ImageUtil.loadImageResource(KPWebhookPlugin.class, "webhook.png")).priority(1).panel(panel).build(); clientToolbar.addNavigation(navButton); overlayManager.add(highlightOverlay); overlayManager.add(minimapHighlightOverlay); overlayManager.add(overlayTextOverlay); captureInitialRealLevels(); storage = new KPWebhookStorage(configManager, gson); rules.clear(); rules.addAll(storage.loadAll()); // establish nextId
        nextId = 0; for (KPWebhookPreset r: rules) if (r.getId()>=nextId) nextId=r.getId()+1; // repair any invalid / duplicate ids
        ensureUniqueIds(); panel.refreshTable();
        try { if(playerTriggerService!=null) playerTriggerService.setPlugin(this); } catch(Exception ignored){}
        // Register external subscriber services (player spawn/despawn)
        try { if(eventBus!=null && playerTriggerService!=null) eventBus.register(playerTriggerService); } catch(Exception ignored){}
        // removed npcTriggerService.setPlugin call (handled directly in plugin now)
        try { com.krisped.triggers.stat.StatTriggerHelper.initializeBaselines(client, rules); } catch(Exception ignored){}
        scheduleLastTriggeredRefresh();
    }
    @Override protected void shutDown(){ if(lastTriggeredRefreshFuture!=null){ try { lastTriggeredRefreshFuture.cancel(true);} catch(Exception ignored){} lastTriggeredRefreshFuture=null;} overlayManager.remove(highlightOverlay); overlayManager.remove(minimapHighlightOverlay); overlayManager.remove(overlayTextOverlay); clientToolbar.removeNavigation(navButton); saveAllPresets(); rules.clear(); highlightManager.clear(); overheadTexts.clear(); overheadImages.clear(); if (infoboxCommandHandler!=null) infoboxCommandHandler.clearAll(); if (overlayTextManager!=null) overlayTextManager.clear(); if (debugWindow!=null){ try{debugWindow.dispose();}catch(Exception ignored){} debugWindow=null;} if (presetDebugWindow!=null){ try{presetDebugWindow.dispose();}catch(Exception ignored){} presetDebugWindow=null;}
        // Unregister external subscribers
        try { if(eventBus!=null && playerTriggerService!=null) eventBus.unregister(playerTriggerService); } catch(Exception ignored){}
    }
    private void scheduleLastTriggeredRefresh(){
        if(executorService==null) return;
        int interval = 60;
        try { if(userSettings!=null) interval = Math.max(5, userSettings.getLastTriggeredRefreshSeconds()); } catch(Exception ignored){}
        if(lastTriggeredRefreshFuture!=null){
            try { lastTriggeredRefreshFuture.cancel(true); } catch(Exception ignored){}
            lastTriggeredRefreshFuture=null;
        }
        final int useInterval = interval;
        lastTriggeredRefreshFuture = executorService.scheduleAtFixedRate(() -> {
            try {
                if(panel!=null && isShowLastTriggered()){
                    javax.swing.SwingUtilities.invokeLater(() -> { if(panel!=null) panel.updateAllLastTriggeredTimes(); });
                }
            } catch(Exception ignored){}
        }, useInterval, useInterval, java.util.concurrent.TimeUnit.SECONDS);
    }
    // Added: ensure uniqueness of rule IDs after loading from storage (handles duplicates / negative ids)
    private void ensureUniqueIds(){
        if(rules.isEmpty()) return;
        Set<Integer> used = new HashSet<>();
        int max = -1;
        for(KPWebhookPreset r : rules){
            if(r==null) continue;
            int id = r.getId();
            if(id < 0 || used.contains(id)){
                // find next free id
                while(used.contains(nextId)) nextId++;
                r.setId(nextId++);
            }
            used.add(r.getId());
            if(r.getId() > max) max = r.getId();
        }
        if(nextId <= max) nextId = max + 1;
        // Persist any id corrections
        try { saveAllPresets(); } catch(Exception ignored){}
    }
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

    // === User settings accessors ===
    public boolean isShowLastTriggered(){
        try { if(userSettings!=null) return userSettings.isShowLastTriggered(); } catch(Exception ignored){}
        // Fallback to legacy config (one-way migrated already, but keep for safety)
        try { if(config!=null) return config.showLastTriggered(); } catch(Exception ignored){}
        return false;
    }
    public void setShowLastTriggered(boolean v){
        try { if(userSettings!=null){ userSettings.setShowLastTriggered(v); userSettings.save(); } } catch(Exception ignored){}
        // Reschedule to apply new interval logic (or stop refresh if disabled)
        try { scheduleLastTriggeredRefresh(); } catch(Exception ignored){}
    }
    public Map<String,Boolean> getCategoryExpandedStates(){
        try { if(userSettings!=null && userSettings.getCategoryExpandedStates()!=null) return userSettings.getCategoryExpandedStates(); } catch(Exception ignored){}
        return Collections.emptyMap();
    }
    public void setCategoryExpandedState(String key, boolean expanded){
        if(key==null) return;
        try {
            if(userSettings!=null){
                Map<String,Boolean> map = userSettings.getCategoryExpandedStates();
                if(map==null){ map = new HashMap<>(); userSettings.setCategoryExpandedStates(map); }
                map.put(key, expanded);
                userSettings.save();
            }
        } catch(Exception ignored){}
    }

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
    public void executeXpRule(KPWebhookPreset r, Skill skill, int totalXp){ executeRule(r, skill, totalXp, null, r.getWidgetConfig()); } // new XP helper
    public void executeNpcTrigger(KPWebhookPreset r, NPC npc){ if(r==null || npc==null) return; executeRule(r,null,-1,null,null,null,npc); } // consolidated npc wrapper
    public void executePlayerTrigger(KPWebhookPreset r, Player p){ if(r==null || p==null) return; executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig(), p, null); }
    // Added: open debug windows + manual send API used by panel
    public void openDebugWindow(){
        try {
            if(debugWindow==null) debugWindow = new KPWebhookDebugWindow(this);
            debugWindow.setVisible(true);
            debugWindow.toFront();
        } catch(Exception ignored){}
    }
    public void openPresetDebugWindow(){
        try {
            if(presetDebugWindow==null) presetDebugWindow = new KPWebhookPresetDebugWindow();
            presetDebugWindow.setVisible(true);
            presetDebugWindow.toFront();
        } catch(Exception ignored){}
    }
    public void manualSend(int id){
        KPWebhookPreset r = find(id);
        if(r==null) return;
        try { if(debugWindow!=null && debugWindow.isVisible()) debugWindow.logManual(r.getId(), r.getTitle()); } catch(Exception ignored){}
        try { executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig()); } catch(Exception ignored){}
    }
    // Expose default webhook URL for dialogs
    public String getDefaultWebhook(){
        try { if(config!=null) return config.defaultWebhookUrl(); } catch(Exception ignored){}
        return null;
    }
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
        if(!isTick){ // record last trigger time (skip continuous TICK spam)
            try { rule.setLastTriggeredAt(System.currentTimeMillis()); if(panel!=null) panel.updateLastTriggered(rule.getId()); } catch(Exception ignored){}
        }
        if(cmds.isBlank()){ rule.setLastConditionMet(true); return; }
        List<PendingCommand> list=new ArrayList<>(); List<String> original=new ArrayList<>();
        for(String rawLine: cmds.split("\r?\n")){
            String line=rawLine.trim(); if(line.isEmpty()|| line.startsWith("#")) continue; original.add(line); String up=line.toUpperCase(Locale.ROOT);
            if(up.startsWith("SLEEP ")){ long ms=0; try{ms=Long.parseLong(line.substring(6).trim());}catch(Exception ignored){} PendingCommand pc=new PendingCommand(); pc.type=PendingType.SLEEP_DELAY; pc.sleepMs=Math.max(0,ms); list.add(pc);} else if(up.equals("SLEEP")){/* ignore bare */}
            else if(up.startsWith("TICK")){ int t=1; String[] parts=line.split("\\s+"); if(parts.length>1){ try{ t=Integer.parseInt(parts[1]); }catch(Exception ignored){} } PendingCommand pc=new PendingCommand(); pc.type=PendingType.TICK_DELAY; pc.ticks=Math.max(1,t); list.add(pc);} else { PendingCommand pc=new PendingCommand(); pc.type=PendingType.ACTION; pc.line=line; list.add(pc);} }
        // Always log execution (create window lazily; user can open later to inspect history)
        try { if(presetDebugWindow==null) presetDebugWindow = new KPWebhookPresetDebugWindow(); presetDebugWindow.logExecution(rule, original, ctx); } catch(Exception ignored){}
        if(list.isEmpty()){ rule.setLastConditionMet(true); return; }
        CommandSequence seq=new CommandSequence(); seq.rule=rule; seq.ctx=ctx; seq.commands=list; seq.index=0; seq.tickDelayRemaining=0; seq.sleepUntilMillis=0L; activeSequences.add(seq); rule.setLastConditionMet(true); }
    private Map<String,String> baseContext(Skill skill,int value,KPWebhookPreset.WidgetConfig widgetCfg, Player otherPlayer, NPC npc){ return tokenService.buildContext(skill,value,widgetCfg,otherPlayer,npc,getCurrentTargetName()); }

    // === STAT event (re-added) ===
    @Subscribe public void onStatChanged(StatChanged ev){
        if(ev==null) return;
        if(client.getGameState()!=GameState.LOGGED_IN) return; // ignore pre-login zeros
        Skill skill = ev.getSkill();
        int real; int boosted; int currentXp=-1;
        try { real = client.getRealSkillLevel(skill); } catch(Exception e){ real = ev.getLevel(); }
        try { boosted = client.getBoostedSkillLevel(skill); } catch(Exception e){ boosted = real; }
        try { currentXp = client.getSkillExperience(skill); } catch(Exception ignored){}
        if(real<=0 && boosted<=0) return; // ignore zero updates
        lastRealLevel.put(skill, real);
        if(debugWindow!=null && debugWindow.isVisible()){
            Integer prevR = lastLoggedRealLevel.get(skill); Integer prevB = lastLoggedBoostedLevel.get(skill);
            if(prevR==null || prevB==null || prevR!=real || prevB!=boosted){
                try { debugWindow.logStat(skill.name(), real, boosted); } catch(Exception ignored){}
                lastLoggedRealLevel.put(skill, real); lastLoggedBoostedLevel.put(skill, boosted);
            }
            // XP drop logging (only when gain >0)
            if(currentXp>0){ Integer prevXp = lastLoggedXp.get(skill); if(prevXp!=null && currentXp>prevXp){ int gained=currentXp-prevXp; try { debugWindow.logXpDrop(skill.name(), gained); } catch(Exception ignored){} } lastLoggedXp.put(skill, currentXp); }
        } else { // still advance xp baseline even if debug window hidden
            if(currentXp>0){ lastLoggedXp.put(skill, currentXp); }
        }
        if(statTriggerService!=null){ try { statTriggerService.process(ev, rules, this); } catch(Exception e){ log.warn("Stat trigger processing error", e); } }
        if(xpDropTriggerService!=null){ try { xpDropTriggerService.process(ev, rules, this); } catch(Exception e){ log.warn("XP drop trigger processing error", e); } } // new XP processing
    }

    /* === Game Tick === */
    @Subscribe public void onGameTick(GameTick t){ gameTickCounter++; seenProjectiles.clear(); /* removed seenProjectileIdentities.clear() */ updateAndProcessTarget(); if(tickTriggerService!=null){ tickTriggerService.process(rules, overheadTexts); /* removed TICK preset debug logging */ }
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

    private void updateAndProcessTarget(){ try { Player local=client.getLocalPlayer(); Actor interacting=null; if(local!=null){ Actor raw = local.getInteracting(); if(raw!=null && isAttackable(raw)) interacting=raw; }
            // Adjusted: Always clear stored target immediately when local is no longer interacting (remove lingering retention based on target's aggression)
            if(interacting==null && currentTarget!=null){
                Actor old=currentTarget; stopAllTargetPresets(); currentTarget=null; lastTargetAnimationId=-1; lastTargetGraphicId=-1; fireTargetChangeTriggers(old,null); return; }
            boolean targetExpired = currentTarget!=null && (gameTickCounter - targetLastActiveTick) > TARGET_RETENTION_TICKS;
            // Retention only applies now when we still have an interaction; if lost interaction we already returned above
            boolean targetChanged=false; if(interacting!=null){ if(currentTarget==null || currentTarget!=interacting) targetChanged=true; } else if(targetExpired && currentTarget!=null){ targetChanged=true; }
            if(targetChanged){ Actor old=currentTarget; if(targetExpired || interacting==null){ if(targetExpired) stopAllTargetPresets(); currentTarget=null; lastTargetAnimationId=-1; lastTargetGraphicId=-1; } else { currentTarget=interacting; lastTargetAnimationId=currentTarget.getAnimation(); try { lastTargetGraphicId=currentTarget.getGraphic(); } catch(Exception ignored){ lastTargetGraphicId=-1; } targetLastActiveTick=gameTickCounter; } fireTargetChangeTriggers(old,currentTarget); } else if(interacting!=null){ targetLastActiveTick=gameTickCounter; } } catch(Exception ignored){} }

    private boolean isAttackable(Actor a){ if(a==null) return false; if(a instanceof NPC) return true; if(a instanceof Player) return a!=client.getLocalPlayer(); return false; }
    private void fireTargetChangeTriggers(Actor oldT, Actor newT){ if(oldT==newT) return; if(debugWindow!=null && debugWindow.isVisible()){ try { if(newT!=null) debugWindow.logTargetChangeActor(newT); else debugWindow.logTargetLost(); } catch(Exception ignored){} }
        Player local=null; try { local=client.getLocalPlayer(); } catch(Exception ignored){}
        for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.TARGET) continue; // apply force cancel on change BEFORE re-trigger
            // If target lost, just reset state and skip executing rule again (prevents duplicate message with empty $TARGET)
            if(newT==null){
                if(r.isLastConditionMet()) r.setLastConditionMet(false);
                if(r.isForceCancelOnChange()) softCancelOnChange(r); // ensure overlays cleared
                savePreset(r);
                continue;
            }
            KPWebhookPreset.TargetConfig tc = r.getTargetConfig(); boolean allow=true; if(tc!=null && newT!=null){
                boolean inPvPOrWild = isInWilderness() || isPvPWorld();
                if(tc.isOnlyPvPOrWilderness() && !inPvPOrWild) allow=false;
                if(newT instanceof Player){ Player tp=(Player)newT; if(tc.isOnlyAttackable() && !isAttackablePerRules(local,tp)) allow=false; }
                else if(newT instanceof NPC){ if(!tc.isEnableNpc()) allow=false; // NPC disabled
                    if(tc.isOnlyPvPOrWilderness() && !inPvPOrWild) allow=false; // still enforce area for NPC if requested
                }
            }
            if(r.isForceCancelOnChange() && r.isLastConditionMet()) { softCancelOnChange(r); r.setLastConditionMet(false); }
            if(!allow){ r.setLastConditionMet(false); savePreset(r); continue; }
            executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig(), (newT instanceof Player)?(Player)newT:null, (newT instanceof NPC)?(NPC)newT:null); r.setLastConditionMet(true); savePreset(r); }
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

    @Subscribe public void onChatMessage(ChatMessage ev){ if(ev==null) return; ChatMessageType type=ev.getType(); String msg=ev.getMessage(); if(isInjectedChat(type,msg)) return; if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.addMessage(type, type.ordinal(), ev.getName(), "", msg); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.MESSAGE) continue; KPWebhookPreset.MessageConfig cfg=r.getMessageConfig(); if(cfg==null) continue; boolean idOk=true; if(cfg.getMessageId()!=null) idOk = cfg.getMessageId()==type.ordinal(); boolean textOk=true; if(cfg.getMessageText()!=null && !cfg.getMessageText().isBlank()){ String pat=cfg.getMessageText().trim(); String regex = Pattern.quote(pat).replace("*", "\\E.*\\Q"); try { textOk = msg!=null && msg.toUpperCase(Locale.ROOT).matches("(?i)"+regex); } catch(Exception ignored){ textOk=false; } } if(idOk && textOk) executeRule(r,null,-1,null,null); }
    }
    public List<KPWebhookPreset> findMatchingMessageRules(ChatMessageType type, String rawMessage){
        List<KPWebhookPreset> out = new ArrayList<>();
        if(type==null || rawMessage==null) return out;
        String msg = rawMessage; // already expanded
        for(KPWebhookPreset r: rules){
            if(r==null || !r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.MESSAGE) continue;
            KPWebhookPreset.MessageConfig cfg = r.getMessageConfig();
            if(cfg==null) continue;
            boolean idOk = cfg.getMessageId()==null || cfg.getMessageId()==type.ordinal();
            if(!idOk) continue;
            boolean textOk = true;
            if(cfg.getMessageText()!=null && !cfg.getMessageText().isBlank()){
                String pat = cfg.getMessageText().trim();
                String regex = Pattern.quote(pat).replace("*", "\\E.*\\Q");
                try{ textOk = msg.toUpperCase(java.util.Locale.ROOT).matches("(?i)"+regex);}catch(Exception e){ textOk=false; }
            }
            if(textOk) out.add(r);
        }
        return out;
    }
    public void debugCustomMessage(ChatMessageType type, String name, String msg, String original, java.util.List<KPWebhookPreset> matches){
        try {
            StringBuilder sb=new StringBuilder();
            sb.append("CUSTOM_MESSAGE_DEBUG type=").append(type!=null?type.name():"null").append("(")
              .append(type!=null?type.ordinal():-1).append(") name='").append(name).append("' msg='")
              .append(msg).append("' raw='").append(original).append("' matches=");
            if(matches==null||matches.isEmpty()) sb.append("[]"); else {
                sb.append("[");
                boolean first=true; for(KPWebhookPreset r: matches){ if(!first) sb.append(", "); first=false; sb.append(r.getId()).append(":").append(r.getTitle()); }
                sb.append("]");
            }
            log.info(sb.toString());
            if(debugWindow!=null && debugWindow.isVisible()){
                try { debugWindow.addMessage(type!=null?type:ChatMessageType.GAMEMESSAGE, type!=null?type.ordinal():-1, name, "DBG", sb.toString()); } catch(Exception ignored){}
            }
        } catch(Exception ignored){}
    }
    @Subscribe public void onWidgetLoaded(WidgetLoaded ev){ if(ev==null) return; int grp=ev.getGroupId(); if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logWidget(grp,null); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.WIDGET_SPAWN) continue; KPWebhookPreset.WidgetConfig cfg=r.getWidgetConfig(); if(cfg==null) continue; if(cfg.getGroupId()==grp){ if(cfg.getChildId()==null) executeRule(r,null,-1,null,cfg); else { try{ Widget w= client.getWidget(grp, cfg.getChildId()); if(w!=null) executeRule(r,null,-1,null,cfg);}catch(Exception ignored){} } } }
    }

    @Subscribe public void onPlayerSpawned(PlayerSpawned ev){ if(delegatePlayerSpawnHandling) return; if(ev==null) return; Player p=ev.getPlayer(); if(p==null) return; try{ if(tokenService!=null) tokenService.updatePlayerSpawnToken(p,false); }catch(Exception ignored){} boolean self = client.getLocalPlayer()==p; String name=sanitizePlayerName(p.getName()); if(debugWindow!=null && debugWindow.isVisible()){ try{ int localCombat=0; try{ if(client.getLocalPlayer()!=null) localCombat=client.getLocalPlayer().getCombatLevel(); }catch(Exception ignored){} debugWindow.logPlayerSpawn(false,name,p.getCombatLevel(), localCombat); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.PLAYER_SPAWN){ if(playerMatches(r.getPlayerConfig(), p, self)) executeRule(r,null,-1,null,null,p); } }
    }
    @Subscribe public void onPlayerDespawned(PlayerDespawned ev){ if(delegatePlayerSpawnHandling) return; if(ev==null) return; Player p=ev.getPlayer(); if(p==null) return; try{ if(tokenService!=null) tokenService.updatePlayerSpawnToken(p,true); }catch(Exception ignored){} boolean self = client.getLocalPlayer()==p; String name=sanitizePlayerName(p.getName()); interactingPlayers.remove(name.toLowerCase(Locale.ROOT)); if(debugWindow!=null && debugWindow.isVisible()){ try{ int localCombat=0; try{ if(client.getLocalPlayer()!=null) localCombat=client.getLocalPlayer().getCombatLevel(); }catch(Exception ignored){} debugWindow.logPlayerSpawn(true,name,p.getCombatLevel(), localCombat); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.PLAYER_DESPAWN){ if(playerMatches(r.getPlayerConfig(), p, self)) executeRule(r,null,-1,null,null,p); } } }
    @Subscribe public void onNpcSpawned(NpcSpawned ev){ if(ev==null) return; NPC npc=ev.getNpc(); if(npc==null) return; logNpcSpawn(false, npc); try { if(tokenService!=null) tokenService.updateNpcSpawnToken(npc); } catch(Exception ignored){} for(KPWebhookPreset r: rules){ if(r==null||!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.NPC_SPAWN){ if(npcMatches(r.getNpcConfig(), npc)) executeNpcTrigger(r, npc); } } }
    @Subscribe public void onNpcDespawned(NpcDespawned ev){ if(ev==null) return; NPC npc=ev.getNpc(); if(npc==null) return; logNpcSpawn(true, npc); for(KPWebhookPreset r: rules){ if(r==null||!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.NPC_DESPAWN){ if(npcMatches(r.getNpcConfig(), npc)) executeNpcTrigger(r, npc); } } }
    // === ITEM_SPAWN (ground item) ===
    @Subscribe public void onItemSpawned(ItemSpawned ev){ if(ev==null) return; TileItem ti=ev.getItem(); if(ti==null) return; int itemId=ti.getId(); int qty=ti.getQuantity(); String nm=""; try { nm=itemManager.getItemComposition(itemId).getName(); } catch(Exception ignored){}
        try { if(tokenService!=null){ net.runelite.api.Tile tile = ev.getTile(); net.runelite.api.coords.WorldPoint wp = tile!=null? tile.getWorldLocation(): null; tokenService.updateLastItemSpawn(nm, wp); } } catch(Exception ignored){}
        logItemSpawn(itemId, nm, qty); for(KPWebhookPreset r: rules){ if(r==null||!r.isActive()) continue; KPWebhookPreset.TriggerType tt=r.getTriggerType(); if(tt== KPWebhookPreset.TriggerType.ITEM_SPAWN){ if(matchesItemSpawn(r.getItemSpawnConfig(), itemId, nm)) executeRule(r,null,-1,null,null); } else if(tt== KPWebhookPreset.TriggerType.LOOT_DROP){ // backward compatible mapping + min value
                KPWebhookPreset.LootConfig lc = r.getLootConfig();
                boolean valueOk = true; if(lc!=null && lc.getMinValue()!=null && lc.getMinValue()>0){ int price=0; try { price = itemManager.getItemPrice(itemId); } catch(Exception ignored){} long total=(long)price * (long)qty; valueOk = total >= lc.getMinValue(); }
                if(valueOk) {
                    try { if(tokenService!=null){ net.runelite.api.Tile tile = ev.getTile(); net.runelite.api.coords.WorldPoint wp = tile!=null? tile.getWorldLocation(): null; tokenService.updateLastLootDrop(nm, wp); } } catch(Exception ignored){}
                    executeRule(r,null,-1,null,null);
                } } }
    }

    /* ===================== Restored / Recreated Helper Methods (simplified) ===================== */
    private void cancelSequencesForRule(int ruleId){ try { activeSequences.removeIf(seq -> seq!=null && seq.rule!=null && seq.rule.getId()==ruleId); } catch(Exception ignored){} }
    private void removeOverheadsForRule(int ruleId, boolean includeTexts){ try { if(includeTexts){ overheadTexts.removeIf(t-> t!=null && t.ruleId==ruleId); } overheadImages.removeIf(i-> i!=null && i.ruleId==ruleId); } catch(Exception ignored){} }
    private void removePersistentOverheadsForRule(int ruleId){ try { overheadTexts.removeIf(t-> t!=null && t.ruleId==ruleId && t.persistent); overheadImages.removeIf(i-> i!=null && i.ruleId==ruleId && i.persistent); } catch(Exception ignored){} }

    public String sanitizePlayerName(String n){ if(n==null) return ""; try { String nt = net.runelite.client.util.Text.removeTags(n); return nt.replace('\u00A0',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').trim(); } }
    public String sanitizeNpcName(String n){ if(n==null) return ""; try { String nt = net.runelite.client.util.Text.removeTags(n); return nt.replace('\u00A0',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').trim(); } }

    // Exposed for PlayerTriggerService to unify debug spawn logging and interaction cleanup
    public void logPlayerSpawn(boolean despawn, Player p){ if(p==null) return; try { String name = sanitizePlayerName(p.getName()); if(despawn){ try { interactingPlayers.remove(name.toLowerCase(Locale.ROOT)); } catch(Exception ignored){} } if(debugWindow!=null && debugWindow.isVisible()){ try { int localCombat=0; try { if(client.getLocalPlayer()!=null) localCombat=client.getLocalPlayer().getCombatLevel(); } catch(Exception ignored){} debugWindow.logPlayerSpawn(despawn, name, p.getCombatLevel(), localCombat); } catch(Exception ignored){} } } catch(Exception ignored){} }
    public void logNpcSpawn(boolean despawn, NPC npc){ if(debugWindow!=null && debugWindow.isVisible()){ try { int combat=0; try{ combat = npc.getCombatLevel(); } catch(Exception ignored){} debugWindow.logNpcSpawn(despawn, sanitizeNpcName(npc.getName()), npc.getId(), combat); } catch(Exception ignored){} } }

    // Added: delegate item spawn logging (called in onItemSpawned)
    private void logItemSpawn(int itemId, String itemName, int qty){
        if(debugWindow!=null && debugWindow.isVisible()){
            try { debugWindow.logItemSpawn(itemId, itemName, qty); } catch(Exception ignored){}
        }
    }
    // ==== Restored helper methods (re-added after refactor) ====
    private boolean matchesItemSpawn(KPWebhookPreset.ItemSpawnConfig cfg, int itemId, String name){ if(cfg==null) return true; String lowName = name==null?"": name.toLowerCase(Locale.ROOT); if(cfg.getItemIds()!=null && !cfg.getItemIds().isEmpty() && cfg.getItemIds().contains(itemId)) return true; if(cfg.getNames()!=null){ for(String n: cfg.getNames()){ if(n!=null && !n.isBlank() && lowName.equals(n.toLowerCase(Locale.ROOT))) return true; } } if(cfg.getWildcards()!=null){ for(String w: cfg.getWildcards()){ if(w==null||w.isBlank()) continue; String pat = Pattern.quote(w.toLowerCase(Locale.ROOT)).replace("*", "\\E.*\\Q"); try { if(lowName.matches(pat)) return true; } catch(Exception ignored){} } } return false; }
    private boolean matchesGear(KPWebhookPreset.GearConfig cfg, int itemId, String name){ if(cfg==null) return true; String lowName = name==null?"": name.toLowerCase(Locale.ROOT); if(cfg.getItemIds()!=null && !cfg.getItemIds().isEmpty() && cfg.getItemIds().contains(itemId)) return true; if(cfg.getNames()!=null){ for(String n: cfg.getNames()){ if(n!=null && !n.isBlank() && lowName.equals(n.toLowerCase(Locale.ROOT))) return true; } } if(cfg.getWildcards()!=null){ for(String w: cfg.getWildcards()){ if(w==null||w.isBlank()) continue; String pat = Pattern.quote(w.toLowerCase(Locale.ROOT)).replace("*", "\\E.*\\Q"); try { if(lowName.matches(pat)) return true; } catch(Exception ignored){} } } return false; }
    private void applyPresetOverheadTexts(KPWebhookPreset rule, Map<String,String> ctx){ if(rule==null) return; try { if(rule.getTextOver()!=null && !rule.getTextOver().isBlank()) addConfiguredOverheadText(rule, rule.getTextOver(), "Above", rule.getTextOverColor(), rule.getTextOverBlink(), rule.getTextOverDuration(), rule.getTextOverSize(), rule.getTextOverBold(), rule.getTextOverItalic(), rule.getTextOverUnderline(), ActiveOverheadText.TargetType.LOCAL_PLAYER, ctx); if(rule.getTextUnder()!=null && !rule.getTextUnder().isBlank()) addConfiguredOverheadText(rule, rule.getTextUnder(), "Under", rule.getTextUnderColor(), rule.getTextUnderBlink(), rule.getTextUnderDuration(), rule.getTextUnderSize(), rule.getTextUnderBold(), rule.getTextUnderItalic(), rule.getTextUnderUnderline(), ActiveOverheadText.TargetType.LOCAL_PLAYER, ctx); if(rule.getTextCenter()!=null && !rule.getTextCenter().isBlank()) addConfiguredOverheadText(rule, rule.getTextCenter(), "Center", rule.getTextCenterColor(), rule.getTextCenterBlink(), rule.getTextCenterDuration(), rule.getTextCenterSize(), rule.getTextCenterBold(), rule.getTextCenterItalic(), rule.getTextCenterUnderline(), ActiveOverheadText.TargetType.LOCAL_PLAYER, ctx); } catch(Exception ignored){} }
    private void addConfiguredOverheadText(KPWebhookPreset rule, String raw, String position, String colorHex, Boolean blink, Integer duration, Integer size, Boolean bold, Boolean italic, Boolean underline, ActiveOverheadText.TargetType target, Map<String,String> ctx){ if(raw==null) return; String txt = tokenService!=null? tokenService.expand(raw, ctx): raw; ActiveOverheadText t=new ActiveOverheadText(); t.text=txt; t.color=parseColor(colorHex, Color.WHITE); t.blink=blink!=null && blink; t.size=size!=null? size:16; t.position=position; t.remainingTicks=Math.max(1,duration!=null? duration:80); t.bold=bold!=null&&bold; t.italic=italic!=null&&italic; t.underline=underline!=null&&underline; t.targetType=target; t.ruleId=rule.getId(); overheadTexts.add(t); }
    private boolean tryProcessTextCommand(KPWebhookPreset rule, String expanded){ if(expanded==null) return false; String u=expanded.toUpperCase(Locale.ROOT); String position=null; if(u.startsWith("TEXT_OVER")) position="Above"; else if(u.startsWith("TEXT_UNDER")) position="Under"; else if(u.startsWith("TEXT_CENTER")) position="Center"; else return false; String text=""; int sp=expanded.indexOf(' '); if(sp>0) text=expanded.substring(sp+1).trim(); if(text.isEmpty()) return true; Color col=Color.WHITE; int size=16; boolean blink=false; int duration=8; if("Above".equals(position)){ col=parseColor(rule.getTextOverColor(), col); size=rule.getTextOverSize()!=null?rule.getTextOverSize():size; blink=Boolean.TRUE.equals(rule.getTextOverBlink()); duration=rule.getTextOverDuration()!=null?rule.getTextOverDuration():duration; } else if("Under".equals(position)){ col=parseColor(rule.getTextUnderColor(), col); size=rule.getTextUnderSize()!=null?rule.getTextUnderSize():size; blink=Boolean.TRUE.equals(rule.getTextUnderBlink()); duration=rule.getTextUnderDuration()!=null?rule.getTextUnderDuration():duration; } else if("Center".equals(position)){ col=parseColor(rule.getTextCenterColor(), col); size=rule.getTextCenterSize()!=null?rule.getTextCenterSize():size; blink=Boolean.TRUE.equals(rule.getTextCenterBlink()); duration=rule.getTextCenterDuration()!=null?rule.getTextCenterDuration():duration; } ActiveOverheadText t=new ActiveOverheadText(); t.text=text; t.color=col; t.size=size; t.blink=blink; t.position=position; t.remainingTicks=Math.max(1,duration); t.targetType=ActiveOverheadText.TargetType.LOCAL_PLAYER; t.ruleId=rule.getId(); overheadTexts.add(t); return true; }
    private void processActionLine(KPWebhookPreset rule, String line, Map<String,String> ctx){ if(line==null) return; String expanded = tokenService!=null? tokenService.expand(line, ctx): line; String up=expanded.toUpperCase(Locale.ROOT); try { if(tryProcessTextCommand(rule, expanded)) return; if(up.startsWith("SCREENSHOT")){ String cap=""; int sp=expanded.indexOf(' '); if(sp>0) cap=expanded.substring(sp+1).trim(); uploadScreenshot(rule, cap, ctx); return; } if(up.startsWith("NOTIFY ")){ String msg=expanded.substring(7).trim(); if(!msg.isEmpty() && notifier!=null) notifier.notify(msg); return; } if(up.equals("NOTIFY")){ if(notifier!=null) notifier.notify(rule.getTitle()!=null? rule.getTitle(): "Triggered"); return; } if(up.startsWith("CUSTOM_MESSAGE ") && customMessageCommandHandler!=null){ customMessageCommandHandler.handle(expanded, rule, ctx); return; } if(up.startsWith("HIGHLIGHT_") && highlightCommandHandler!=null){ highlightCommandHandler.handle(expanded, rule); return; } if(up.startsWith("MARK_TILE") && markTileCommandHandler!=null){ markTileCommandHandler.handle(expanded, rule); return; } if(up.startsWith("OVERLAY_TEXT") && overlayTextCommandHandler!=null){ overlayTextCommandHandler.handle(expanded, rule); return; } if(up.startsWith("INFOBOX") && infoboxCommandHandler!=null){ infoboxCommandHandler.handle(expanded, rule, ctx); return; } if(up.startsWith("STOP")){ stopRule(rule.getId()); return; } if(up.startsWith("WEBHOOK ")){ String body=expanded.substring(8).trim(); postWebhook(rule, body, ctx); return; } if(up.equals("WEBHOOK")){ postWebhook(rule, rule.getCommands(), ctx); return; } } catch(Exception e){ log.warn("Action line error: {}", line, e); } }
    private void postWebhook(KPWebhookPreset rule, String body, Map<String,String> ctx){ if(rule==null) return; String rawUrl=null; try { if(rule.isUseDefaultWebhook()){ if(config!=null) rawUrl=config.defaultWebhookUrl(); } else rawUrl=rule.getWebhookUrl(); } catch(Exception ignored){} final String url = rawUrl; if(url==null || url.isBlank()) return; String content = body!=null? body.trim(): ""; if(tokenService!=null) try { content = tokenService.expand(content, ctx); } catch(Exception ignored){} if(content.isBlank()){ content = rule.getTitle()!=null? rule.getTitle(): "Triggered"; } if(content.length()>1900) content = content.substring(0,1900)+"..."; Map<String,Object> json=new HashMap<>(); json.put("content", content); RequestBody rb = RequestBody.create(JSON, gson.toJson(json)); Request req = new Request.Builder().url(url).post(rb).build(); try { okHttpClient.newCall(req).enqueue(new Callback(){ public void onFailure(Call call, IOException e){ log.warn("Webhook post failed: {}", url, e);} public void onResponse(Call call, Response response){ try(Response r=response){ if(!r.isSuccessful()) log.warn("Webhook HTTP {} posting to {}", r.code(), url); } catch(Exception ignored){} } }); } catch(Exception e){ log.warn("Webhook post error", e); } }
    private void uploadScreenshot(KPWebhookPreset rule, String caption, Map<String,String> ctx){ if(rule==null) return; long now=System.currentTimeMillis(); if(now - lastScreenshotRequestMs < SCREENSHOT_COOLDOWN_MS) return; lastScreenshotRequestMs=now; BufferedImage img=lastFrame; if(img==null){ try { captureCanvasFrame(); img=lastFrame; } catch(Exception ignored){} } if(img==null){ postWebhook(rule, caption!=null?caption:"(no frame)", ctx); return; } String rawUrl=null; try { if(rule.isUseDefaultWebhook()){ if(config!=null) rawUrl=config.defaultWebhookUrl(); } else rawUrl=rule.getWebhookUrl(); } catch(Exception ignored){} final String url=rawUrl; if(url==null||url.isBlank()) return; String cap=caption!=null? caption.trim():""; if(tokenService!=null) try { cap=tokenService.expand(cap, ctx); } catch(Exception ignored){} try { ByteArrayOutputStream baos=new ByteArrayOutputStream(); ImageIO.write(img, "png", baos); byte[] data=baos.toByteArray(); MultipartBody.Builder mb=new MultipartBody.Builder().setType(MultipartBody.FORM); if(!cap.isBlank()){ Map<String,Object> payload=new HashMap<>(); payload.put("content", cap.length()>1900? cap.substring(0,1900)+"...": cap); mb.addFormDataPart("payload_json", gson.toJson(payload)); } mb.addFormDataPart("file", "screenshot.png", RequestBody.create(PNG, data)); Request req=new Request.Builder().url(url).post(mb.build()).build(); okHttpClient.newCall(req).enqueue(new Callback(){ public void onFailure(Call call, IOException e){ log.warn("Screenshot upload failed", e);} public void onResponse(Call call, Response response){ try(Response r=response){ if(!r.isSuccessful()) log.warn("Screenshot HTTP {}", r.code()); } catch(Exception ignored){} } }); } catch(Exception e){ log.warn("Screenshot error", e); } }
    private boolean isMostlyBlack(BufferedImage img){ if(img==null) return true; int w=img.getWidth(), h=img.getHeight(); long dark=0; int step = Math.max(1, Math.min(w,h)/64); for(int y=0;y<h;y+=step){ for(int x=0;x<w;x+=step){ int rgb=img.getRGB(x,y); int r=(rgb>>16)&0xFF,g=(rgb>>8)&0xFF,b=rgb&0xFF; if(r<25 && g<25 && b<25) dark++; } } long total = (long)((h+step-1)/step)*((w+step-1)/step); return dark*100/Math.max(1,total) > 85; }
    private boolean isMostlyWhite(BufferedImage img){ if(img==null) return true; int w=img.getWidth(), h=img.getHeight(); long light=0; int step = Math.max(1, Math.min(w,h)/64); for(int y=0;y<h;y+=step){ for(int x=0;x<w;x+=step){ int rgb=img.getRGB(x,y); int r=(rgb>>16)&0xFF,g=(rgb>>8)&0xFF,b=rgb&0xFF; if(r>230 && g>230 && b>230) light++; } } long total = (long)((h+step-1)/step)*((w+step-1)/step); return light*100/Math.max(1,total) > 85; }
    private Color parseColor(String hex, Color fallback){ if(hex==null||hex.isBlank()) return fallback; try { String h=hex.trim(); if(h.startsWith("#")) h=h.substring(1); if(h.length()==3){ String r=""+h.charAt(0)+h.charAt(0); String g=""+h.charAt(1)+h.charAt(1); String b=""+h.charAt(2)+h.charAt(2); return new Color(Integer.parseInt(r,16),Integer.parseInt(g,16),Integer.parseInt(b,16)); } if(h.length()==6){ int r=Integer.parseInt(h.substring(0,2),16); int g=Integer.parseInt(h.substring(2,4),16); int b=Integer.parseInt(h.substring(4,6),16); return new Color(r,g,b); } } catch(Exception ignored){} return fallback; }
    private boolean isPvPWorld(){ try { EnumSet<WorldType> types = client.getWorldType(); return types!=null && (types.contains(WorldType.PVP)||types.contains(WorldType.HIGH_RISK)||types.contains(WorldType.LAST_MAN_STANDING)||types.contains(WorldType.DEADMAN)); } catch(Exception ignored){} return false; }
    private boolean isInWilderness(){ try { Player p=client.getLocalPlayer(); if(p==null) return false; WorldPoint wp=p.getWorldLocation(); if(wp==null) return false; int y=wp.getY(); return y>=3520 && y<4000; } catch(Exception ignored){} return false; }
    private boolean isAttackablePerRules(Player local, Player target){ if(local==null||target==null||local==target) return false; try { int diff=Math.abs(local.getCombatLevel()-target.getCombatLevel()); if(isPvPWorld()) return diff<=30; if(isInWilderness()) return diff<=15; return false; } catch(Exception ignored){} return false; }
    private boolean playerMatches(KPWebhookPreset.PlayerConfig cfg, Player p, boolean self){ if(p==null) return false; if(cfg==null) return true; if(cfg.isAll()) return !self; String nm=sanitizePlayerName(p.getName()).toLowerCase(Locale.ROOT); if(cfg.getName()!=null && !cfg.getName().isBlank()) return nm.equals(cfg.getName().toLowerCase(Locale.ROOT)); if(cfg.getNames()!=null && ! cfg.getNames().isEmpty()) return cfg.getNames().stream().filter(Objects::nonNull).map(s->s.toLowerCase(Locale.ROOT)).anyMatch(nm::equals); if(cfg.getCombatRange()!=null){ try { Player local=client.getLocalPlayer(); if(local!=null){ int diff=Math.abs(local.getCombatLevel()-p.getCombatLevel()); return diff <= cfg.getCombatRange(); } } catch(Exception ignored){} } return false; }
    private boolean npcMatches(KPWebhookPreset.NpcConfig cfg, NPC npc){ if(npc==null || cfg==null) return false; boolean idMatch=false; if(cfg.getNpcIds()!=null && !cfg.getNpcIds().isEmpty()) idMatch = cfg.getNpcIds().contains(npc.getId()); String nm = sanitizeNpcName(npc.getName()).toLowerCase(Locale.ROOT).replace(' ','_'); boolean nameMatch=false; if(cfg.getNpcNames()!=null && !cfg.getNpcNames().isEmpty()) nameMatch = cfg.getNpcNames().contains(nm); if((cfg.getNpcIds()==null||cfg.getNpcIds().isEmpty()) && (cfg.getNpcNames()==null||cfg.getNpcNames().isEmpty())) return false; return idMatch || nameMatch; }
    // === Gear change handling (added)===
    private void processGearChanged(){
        Player local=null; try { local=client.getLocalPlayer(); } catch(Exception ignored){}
        if(local!=null){
            Set<Integer> current=new HashSet<>();
            try { ItemContainer eq=client.getItemContainer(InventoryID.EQUIPMENT); if(eq!=null){ for(Item it: eq.getItems()){ if(it==null) continue; int id=it.getId(); if(id>0) current.add(id); } } } catch(Exception ignored){}
            boolean changed = !current.equals(lastLocalGear);
            if(!localGearInitialized){ lastLocalGear.clear(); lastLocalGear.addAll(current); localGearInitialized=true; changed=false; }
            if(changed){
                Set<Integer> added=new HashSet<>(current); added.removeAll(lastLocalGear);
                Set<Integer> removed=new HashSet<>(lastLocalGear); removed.removeAll(current);
                if(debugWindow!=null && debugWindow.isVisible()){
                    try { debugWindow.logGearChange("GEAR_CHANGED", sanitizePlayerName(local.getName()), joinItemNames(added), joinItemNames(removed)); } catch(Exception ignored){}
                }
                for(KPWebhookPreset r: rules){
                    if(r==null || !r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.GEAR_CHANGED) continue;
                    KPWebhookPreset.GearConfig gc=r.getGearConfig(); boolean match=true;
                    if(gc!=null){ boolean anyFilter=(gc.getItemIds()!=null&&!gc.getItemIds().isEmpty())||(gc.getNames()!=null&&!gc.getNames().isEmpty())||(gc.getWildcards()!=null&&!gc.getWildcards().isEmpty());
                        if(anyFilter){ match = gearFilterMatches(gc, added) || gearFilterMatches(gc, removed) || gearFilterMatches(gc, current); }
                    }
                    if(match) executeRule(r,null,-1,null,null);
                }
                lastLocalGear.clear(); lastLocalGear.addAll(current);
            }
        }
        // Target player gear
        if(currentTarget instanceof Player){
            Player tp=(Player)currentTarget;
            if(lastTargetPlayerForGear!=tp){ // new target baseline
                lastTargetPlayerForGear=tp; lastTargetGear.clear(); lastTargetGear.addAll(extractPlayerEquipmentIds(tp));
            } else {
                Set<Integer> currT=extractPlayerEquipmentIds(tp); boolean tChanged=!currT.equals(lastTargetGear);
                if(tChanged){
                    Set<Integer> addedT=new HashSet<>(currT); addedT.removeAll(lastTargetGear);
                    Set<Integer> removedT=new HashSet<>(lastTargetGear); removedT.removeAll(currT);
                    if(debugWindow!=null && debugWindow.isVisible()){
                        try { debugWindow.logGearChange("TARGET_GEAR_CHANGED", sanitizePlayerName(tp.getName()), joinItemNames(addedT), joinItemNames(removedT)); } catch(Exception ignored){}
                    }
                    for(KPWebhookPreset r: rules){
                        if(r==null || !r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.TARGET_GEAR_CHANGED) continue;
                        KPWebhookPreset.GearConfig gc=r.getGearConfig(); boolean match=true;
                        if(gc!=null){ boolean anyFilter=(gc.getItemIds()!=null&&!gc.getItemIds().isEmpty())||(gc.getNames()!=null&&!gc.getNames().isEmpty())||(gc.getWildcards()!=null&&!gc.getWildcards().isEmpty());
                            if(anyFilter){ match = gearFilterMatches(gc, addedT) || gearFilterMatches(gc, removedT) || gearFilterMatches(gc, currT); }
                        }
                        if(match) executeRule(r,null,-1,null,null,tp,null);
                    }
                    lastTargetGear.clear(); lastTargetGear.addAll(currT);
                }
            }
        } else { lastTargetPlayerForGear=null; lastTargetGear.clear(); }
    }
    private Set<Integer> extractPlayerEquipmentIds(Player p){ Set<Integer> out=new HashSet<>(); if(p==null) return out; try { PlayerComposition comp=p.getPlayerComposition(); if(comp!=null){ int[] arr=comp.getEquipmentIds(); if(arr!=null){ for(int v: arr){ if(v<=0) continue; int id=v>=512? v-512: v; if(id>0) out.add(id); } } } } catch(Exception ignored){} return out; }
    private boolean gearFilterMatches(KPWebhookPreset.GearConfig gc, Collection<Integer> ids){ if(gc==null) return true; if(ids==null||ids.isEmpty()) return false; for(Integer id: ids){ if(id==null) continue; String nm=null; try { nm=itemManager.getItemComposition(id).getName(); } catch(Exception ignored){} if(matchesGear(gc,id,nm)) return true; } return false; }
    private String itemName(int id){ try { return itemManager.getItemComposition(id).getName(); } catch(Exception e){ return String.valueOf(id); } }
    private String joinItemNames(Collection<Integer> ids){ if(ids==null||ids.isEmpty()) return ""; StringBuilder sb=new StringBuilder(); int c=0; for(Integer id: ids){ if(id==null) continue; if(c>0) sb.append(','); sb.append(itemName(id)); c++; if(c>=12){ if(ids.size()>c) sb.append("..."); break; } } return sb.toString(); }

    // === Animation & Graphic Event Handling (restored) ===
    @Subscribe
    public void onAnimationChanged(AnimationChanged ev){
        if(ev==null) return; Actor a = ev.getActor(); if(a==null) return; int anim; try { anim = a.getAnimation(); } catch(Exception e){ return; }
        // Previously skipped anim <0; now we always log even negative (e.g. -1 reset)
        boolean isReset = anim < 0;
        Player local=null; try { local=client.getLocalPlayer(); } catch(Exception ignored){}
        boolean isSelf = local!=null && a==local;
        boolean isTarget = currentTarget!=null && a==currentTarget;
        if(debugWindow!=null && debugWindow.isVisible()){
            try { debugWindow.logAnimation(a, isSelf, isTarget, anim); } catch(Exception ignored){}
        }
        // Process rules (still allow negative only if specifically filtered, avoid spam on wildcard)
        for(KPWebhookPreset r: rules){ if(r==null || !r.isActive()) continue; KPWebhookPreset.TriggerType tt = r.getTriggerType(); if(tt!= KPWebhookPreset.TriggerType.ANIMATION_SELF && tt!= KPWebhookPreset.TriggerType.ANIMATION_TARGET && tt!= KPWebhookPreset.TriggerType.ANIMATION_ANY) continue;
            boolean applies = (tt== KPWebhookPreset.TriggerType.ANIMATION_SELF && isSelf) || (tt== KPWebhookPreset.TriggerType.ANIMATION_TARGET && isTarget) || (tt== KPWebhookPreset.TriggerType.ANIMATION_ANY && (isSelf || isTarget || a instanceof Player || a instanceof NPC));
            if(!applies) continue;
            KPWebhookPreset.AnimationConfig cfg = r.getAnimationConfig();
            boolean idMatch;
            if(cfg==null || (cfg.getAnimationId()==null && (cfg.getAnimationIds()==null || cfg.getAnimationIds().isEmpty()))){
                // Wildcard: do NOT trigger on reset animations (negative), only log
                idMatch = !isReset;
            } else {
                idMatch = matchesId(cfg.getAnimationId(), cfg.getAnimationIds(), anim);
            }
            if(r.isForceCancelOnChange()){
                if(idMatch){
                    if(!r.isLastConditionMet()){
                        executeRule(r,null,-1,null,null, (a instanceof Player && a!=local)? (Player)a: null, (a instanceof NPC)? (NPC)a: null);
                        r.setLastConditionMet(true); savePreset(r);
                    }
                } else if(r.isLastConditionMet()){
                    r.setLastConditionMet(false); if(r.isForceCancelOnChange()) softCancelOnChange(r); savePreset(r);
                }
            } else if(idMatch){
                executeRule(r,null,-1,null,null, (a instanceof Player && a!=local)? (Player)a: null, (a instanceof NPC)? (NPC)a: null);
            }
        }
    }

    @Subscribe
    public void onGraphicChanged(GraphicChanged ev){
        if(ev==null) return; Actor a = ev.getActor(); if(a==null) return; int graphicId;
        try { graphicId = a.getGraphic(); } catch(Exception e){ return; }
        if(graphicId < 0) return;
        Player local=null; try { local=client.getLocalPlayer(); } catch(Exception ignored){}
        boolean isSelf = local!=null && a==local;
        boolean isTarget = currentTarget!=null && a==currentTarget;
        if(debugWindow!=null && debugWindow.isVisible()){
            try { debugWindow.logGraphic(a, isSelf, isTarget, graphicId); } catch(Exception ignored){}
        }
        for(KPWebhookPreset r: rules){ if(r==null || !r.isActive()) continue; KPWebhookPreset.TriggerType tt = r.getTriggerType(); if(tt!= KPWebhookPreset.TriggerType.GRAPHIC_SELF && tt!= KPWebhookPreset.TriggerType.GRAPHIC_TARGET && tt!= KPWebhookPreset.TriggerType.GRAPHIC_ANY) continue;
            boolean applies = (tt== KPWebhookPreset.TriggerType.GRAPHIC_SELF && isSelf) || (tt== KPWebhookPreset.TriggerType.GRAPHIC_TARGET && isTarget) || (tt== KPWebhookPreset.TriggerType.GRAPHIC_ANY && (isSelf || isTarget || a instanceof Player || a instanceof NPC));
            if(!applies) continue;
            KPWebhookPreset.GraphicConfig cfg = r.getGraphicConfig();
            boolean idMatch;
            if(cfg==null || (cfg.getGraphicId()==null && (cfg.getGraphicIds()==null || cfg.getGraphicIds().isEmpty()))){
                idMatch = true;
            } else {
                idMatch = matchesId(cfg.getGraphicId(), cfg.getGraphicIds(), graphicId);
            }
            if(r.isForceCancelOnChange()){
                if(idMatch){
                    if(!r.isLastConditionMet()){
                        executeRule(r,null,-1,null,null, (a instanceof Player && a!=local)? (Player)a: null, (a instanceof NPC)? (NPC)a: null);
                        r.setLastConditionMet(true); savePreset(r);
                    }
                } else if(r.isLastConditionMet()){
                    r.setLastConditionMet(false); if(r.isForceCancelOnChange()) softCancelOnChange(r); savePreset(r);
                }
            } else if(idMatch){
                executeRule(r,null,-1,null,null, (a instanceof Player && a!=local)? (Player)a: null, (a instanceof NPC)? (NPC)a: null);
            }
        }
    }

    // === Projectile Event Handling (restored) ===
    @Subscribe
    public void onProjectileMoved(ProjectileMoved ev){
        if(ev==null) return;
        Projectile p;
        try { p = ev.getProjectile(); } catch(Exception e){ return; }
        if(p==null) return;
        int pid; try { pid = p.getId(); } catch(Exception e){ return; }
        int identity = System.identityHashCode(p);
        if(seenProjectileIdentities.contains(identity)) return; // only classify/log first time we see it
        seenProjectileIdentities.add(identity);
        if(seenProjectileIdentities.size() > 5000) { seenProjectileIdentities.clear(); }
        Actor tgt = null; try { tgt = p.getInteracting(); } catch(Exception ignored){}
        Player local = null; try { local = client.getLocalPlayer(); } catch(Exception ignored){}
        boolean incoming = tgt!=null && local!=null && tgt==local; // projectile aimed at us
        boolean outgoing = false;
        if(!incoming && local!=null){
            // Primary: projectile interacting actor matches our current target (and not us)
            Actor localTarget = null; try { localTarget = local.getInteracting(); } catch(Exception ignored){}
            if(tgt!=null && tgt==localTarget && tgt!=local){
                outgoing = true;
            } else {
                // Fallback heuristic: start tile very close to local player origin (within 1 tile)
                try {
                    int sx = p.getX1() >> 7; // convert from 1/128 tile units
                    int sy = p.getY1() >> 7;
                    LocalPoint lp = local.getLocalLocation();
                    if(lp!=null){
                        int lx = lp.getX() >> 7;
                        int ly = lp.getY() >> 7;
                        if(Math.abs(lx - sx) <= 1 && Math.abs(ly - sy) <= 1){
                            outgoing = true;
                        }
                    }
                } catch(Exception ignored){}
            }
        }
        // Debug logging (only if window visible) – log only when classified as incoming/outgoing
        if(debugWindow!=null && debugWindow.isVisible()){
            try {
                if(outgoing){ debugWindow.logProjectile("PROJECTILE_SELF", p, tgt); }
                if(incoming){ debugWindow.logProjectile("PROJECTILE", p, tgt); }
            } catch(Exception ignored){}
        }
        if(!incoming && !outgoing) return; // ignore unrelated projectiles for trigger processing
        for(KPWebhookPreset r: rules){
            if(r==null || !r.isActive()) continue;
            KPWebhookPreset.TriggerType tt = r.getTriggerType();
            if(tt!= KPWebhookPreset.TriggerType.PROJECTILE_SELF && tt!= KPWebhookPreset.TriggerType.PROJECTILE) continue;
            boolean applies = (tt== KPWebhookPreset.TriggerType.PROJECTILE_SELF && outgoing) || (tt== KPWebhookPreset.TriggerType.PROJECTILE && incoming);
            if(!applies) continue;
            KPWebhookPreset.ProjectileConfig cfg = r.getProjectileConfig();
            boolean idMatch;
            if(tt== KPWebhookPreset.TriggerType.PROJECTILE){
                if(cfg==null || (cfg.getProjectileId()==null && (cfg.getProjectileIds()==null || cfg.getProjectileIds().isEmpty()))){
                    idMatch = true; // allow wildcard for incoming
                } else {
                    idMatch = matchesId(cfg.getProjectileId(), cfg.getProjectileIds(), pid);
                }
            } else { // PROJECTILE_SELF requires ids
                if(cfg==null) continue;
                idMatch = matchesId(cfg.getProjectileId(), cfg.getProjectileIds(), pid);
            }
            if(!idMatch) continue;
            executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), (tgt instanceof Player && incoming)? (Player)tgt: null, (tgt instanceof NPC && incoming)? (NPC)tgt: null);
        }
    }

    // Public helper for external text command classes (TEXT_OVER / TEXT_UNDER / TEXT_CENTER)
    // Provides a simple API with defaults (white, size 16, 80 ticks, no blink) and optional rule linkage
    public void addOverheadText(KPWebhookPreset rule, String text, String position){
        if(text==null || text.isBlank()) return; // ignore empty
        ActiveOverheadText t = new ActiveOverheadText();
        t.text = text;
        t.color = Color.WHITE;
        t.size = 16;
        t.blink = false;
        t.position = position==null? "Above": position; // fallback
        t.remainingTicks = 80; // default duration
        t.targetType = ActiveOverheadText.TargetType.LOCAL_PLAYER;
        t.ruleId = rule!=null? rule.getId(): -1;
        overheadTexts.add(t);
    }

    // Added: generic helper to match either a single id or list of ids against an event id.
    // Returns true only if at least one filter is defined AND matches.
    private boolean matchesId(Integer single, java.util.List<Integer> many, int id){
        if(id < 0) return false; // ignore invalid ids
        boolean anyDefined = (single != null) || (many != null && !many.isEmpty());
        if(!anyDefined) return false; // no filter specified -> no match (prevents firing on all changes unintentionally)
        if(single != null && single == id) return true;
        if(many != null){ for(Integer v: many){ if(v != null && v == id) return true; } }
        return false;
    }
}
