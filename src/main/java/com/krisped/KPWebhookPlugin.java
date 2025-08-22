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

@Slf4j
@PluginDescriptor(
        name = "KP Webhook",
        description = "Triggers: MANUAL, STAT, XP_DROP, WIDGET_SPAWN, PLAYER_SPAWN, PLAYER_DESPAWN, NPC_SPAWN, NPC_DESPAWN, ANIMATION_SELF, ANIMATION_TARGET, ANIMATION_ANY, GRAPHIC_SELF, GRAPHIC_TARGET, GRAPHIC_ANY, PROJECTILE_SELF, PROJECTILE_TARGET, PROJECTILE_ANY, HITSPLAT_SELF, HITSPLAT_TARGET, MESSAGE, VARBIT, VARPLAYER, TICK, TARGET, IDLE, GEAR_CHANGED, TARGET_GEAR_CHANGED, REGION, INVENTORY_FULL, INVENTORY_ITEM_ADDED, INVENTORY_ITEM_REMOVED, INVENTORY_CONTAINS_NONE, LOOT_DROP, DEATH. Commands: NOTIFY, CUSTOM_MESSAGE, WEBHOOK, SCREENSHOT, HIGHLIGHT_*, MARK_TILE, TEXT_*, OVERLAY_TEXT, SLEEP, TICK, STOP, TOGGLEPRESET, TOGGLEGROUP.",
        tags = {"webhook","stat","trigger","screenshot","widget","highlight","text","player","npc","varbit","varplayer","tick","overlay","target","graphic","hitsplat","projectile","idle","gear","region","inventory","loot","death","xp"}
)
public class KPWebhookPlugin extends Plugin {
    // Anti-spam cache for heavy commands in TICK presets
    private final Map<Integer, Set<String>> tickRulePersistentCommands = new HashMap<>();

    // === Chat message type alias map ===
    private static final Map<String, ChatMessageType> CHAT_TYPE_ALIASES = new HashMap<>();
    private static final Set<String> CHAT_SUFFIXES = new LinkedHashSet<>(Arrays.asList("RECEIVED","SENT","IN","OUT","MESSAGE","MSG","CHAT"));
    private static String normKey(String s){ if (s==null) return ""; return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]",""); }
    private static void registerAlias(Map<String,ChatMessageType> map,String canonical,String...aliases){ ChatMessageType base=map.get(canonical); if(base==null) return; for(String a:aliases){ if(a==null||a.isBlank()) continue; String up=a.toUpperCase(Locale.ROOT); map.put(up,base); map.put(normKey(up), base);} }
    static { try { for (ChatMessageType t : ChatMessageType.values()){ String up=t.name().toUpperCase(Locale.ROOT); CHAT_TYPE_ALIASES.put(up,t); CHAT_TYPE_ALIASES.put(normKey(up),t);} registerAlias(CHAT_TYPE_ALIASES,"GAMEMESSAGE","GAME","GAME_MESSAGE","SYSTEM","INFO","GAMEMSG","GAME_MSG","GAMECHAT","SYSTEMMESSAGE","SYSTEM_MESSAGE","SYSTEMMSG"); } catch(Exception ignored){} }

    @Inject private Client client; @Inject private ClientThread clientThread; @Inject private ClientToolbar clientToolbar; @Inject private ScheduledExecutorService executorService; @Inject private OkHttpClient okHttpClient; @Inject private ConfigManager configManager; @Inject private KPWebhookConfig config; @Inject private OverlayManager overlayManager; @Inject private HighlightManager highlightManager; @Inject private HighlightCommandHandler highlightCommandHandler; @Inject private HighlightOverlay highlightOverlay; @Inject private MinimapHighlightOverlay minimapHighlightOverlay; @Inject private TickTriggerService tickTriggerService; @Inject private InfoboxCommandHandler infoboxCommandHandler; @Inject private OverlayTextManager overlayTextManager; @Inject private OverlayTextOverlay overlayTextOverlay; @Inject private OverlayTextCommandHandler overlayTextCommandHandler; @Inject private ItemManager itemManager; @Inject private SpriteManager spriteManager; @Inject private Notifier notifier;

    private KPWebhookPanel panel; private NavigationButton navButton; private KPWebhookStorage storage;
    private final List<KPWebhookPreset> rules = new ArrayList<>(); private int nextId=0; private KPWebhookDebugWindow debugWindow; private KPWebhookPresetDebugWindow presetDebugWindow;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8"); private static final MediaType PNG = MediaType.parse("image/png");
    private final Map<Skill,Integer> lastRealLevel = new EnumMap<>(Skill.class); private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private BufferedImage lastFrame; private volatile long lastScreenshotRequestMs = 0; private static final long SCREENSHOT_COOLDOWN_MS = 800;

    // Target tracking
    private Actor currentTarget; private int targetLastActiveTick=-1; private int gameTickCounter=0; private static final int TARGET_RETENTION_TICKS=50; private int lastTargetAnimationId=-1; private int lastTargetGraphicId=-1;

    // Hitsplat tracking for context tokens
    private int lastHitsplatSelf = -1; private int lastHitsplatTarget = -1; private int lastHitsplatAny = -1; // new

    // Gear tracking snapshots (added)
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

    // Overhead texts
    @Data public static class ActiveOverheadText { String text; Color color; boolean blink; int size; String position; int remainingTicks; boolean visiblePhase; int blinkCounter; int blinkInterval; boolean bold; boolean italic; boolean underline; String key; boolean persistent; int ruleId=-1; public enum TargetType { LOCAL_PLAYER, PLAYER_NAME, NPC_NAME, NPC_ID, TARGET, FRIEND_LIST, IGNORE_LIST, PARTY_MEMBERS, FRIENDS_CHAT, TEAM_MEMBERS, CLAN_MEMBERS, OTHERS } TargetType targetType=TargetType.LOCAL_PLAYER; Set<String> targetNames; Set<Integer> targetIds; }
    private final List<ActiveOverheadText> overheadTexts = new ArrayList<>(); public List<ActiveOverheadText> getOverheadTexts(){ return overheadTexts; }

    // Overhead images
    @Data public static class ActiveOverheadImage { BufferedImage image; int itemOrSpriteId; boolean sprite; String position; int remainingTicks; boolean persistent; boolean blink; int blinkCounter; int blinkInterval=2; boolean visiblePhase=true; int ruleId=-1; public enum TargetType { LOCAL_PLAYER, PLAYER_NAME, NPC_NAME, NPC_ID, TARGET, FRIEND_LIST, IGNORE_LIST, PARTY_MEMBERS, FRIENDS_CHAT, TEAM_MEMBERS, CLAN_MEMBERS, OTHERS } TargetType targetType=TargetType.LOCAL_PLAYER; Set<String> targetNames; Set<Integer> targetIds; }
    private final List<ActiveOverheadImage> overheadImages = new ArrayList<>(); public List<ActiveOverheadImage> getOverheadImages(){ return overheadImages; }
    // MARK_TILE storage
    @Data public static class MarkedTile { WorldPoint worldPoint; Color color; String text; int width; int ruleId; }
    private final List<MarkedTile> markedTiles = new ArrayList<>(); public List<MarkedTile> getMarkedTiles(){ return markedTiles; }

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
        ensureUniqueIds(); panel.refreshTable(); }
    @Override protected void shutDown(){ overlayManager.remove(highlightOverlay); overlayManager.remove(minimapHighlightOverlay); overlayManager.remove(overlayTextOverlay); clientToolbar.removeNavigation(navButton); saveAllPresets(); rules.clear(); highlightManager.clear(); overheadTexts.clear(); overheadImages.clear(); markedTiles.clear(); if (infoboxCommandHandler!=null) infoboxCommandHandler.clearAll(); if (overlayTextManager!=null) overlayTextManager.clear(); if (debugWindow!=null){ try{debugWindow.dispose();}catch(Exception ignored){} debugWindow=null;} if (presetDebugWindow!=null){ try{presetDebugWindow.dispose();}catch(Exception ignored){} presetDebugWindow=null;} }

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
    private void stopRule(int ruleId){ cancelSequencesForRule(ruleId); removeOverheadsForRule(ruleId,true); removePersistentOverheadsForRule(ruleId); try{highlightManager.removeAllByRule(ruleId);}catch(Exception ignored){} overheadImages.removeIf(i->i!=null && i.ruleId==ruleId); try { overlayTextManager.removeByRule(ruleId);} catch(Exception ignored){} try{ if(infoboxCommandHandler!=null) infoboxCommandHandler.removeByRule(ruleId);}catch(Exception ignored){} // remove marked tiles
        markedTiles.removeIf(mt-> mt!=null && mt.ruleId==ruleId); tickRulePersistentCommands.remove(ruleId); }

    // Soft cancel invoked when forceCancelOnChange conditions revert; keeps rule active for future triggers
    private void softCancelOnChange(KPWebhookPreset r){
        if(r==null) return;
        // Perform same cleanup as stopRule but do not toggle active flag
        stopRule(r.getId());
    }

    // EXECUTION
    private void executeRule(KPWebhookPreset rule, Skill skill, int value, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg){ executeRule(rule,skill,value,statCfg,widgetCfg,null,null); }
    private void executeRule(KPWebhookPreset rule, Skill skill, int value, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg, Player other){ executeRule(rule,skill,value,statCfg,widgetCfg,other,null); }
    private void executeRule(KPWebhookPreset rule, Skill skill, int value, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg, Player otherPlayer, NPC npc){ if(rule==null) return; String cmds = rule.getCommands(); if(cmds==null || cmds.isBlank()) return; Map<String,String> ctx = baseContext(skill,value,widgetCfg,otherPlayer,npc); // apply preset overhead texts before command sequencing
        applyPresetOverheadTexts(rule, ctx);
        // Avoid spawning a brand new sequence every tick for TICK trigger so TICK delays function
        if(rule.getTriggerType()== KPWebhookPreset.TriggerType.TICK){
            for(CommandSequence existing : activeSequences){
                if(existing!=null && existing.rule!=null && existing.rule.getId()==rule.getId() && existing.index < existing.commands.size()){
                    return; // active sequence still running; do not restart
                }
            }
        }
        List<PendingCommand> list=new ArrayList<>(); List<String> original=new ArrayList<>(); for(String rawLine: cmds.split("\r?\n")){ String line=rawLine.trim(); if(line.isEmpty()|| line.startsWith("#")) continue; original.add(line); String up=line.toUpperCase(Locale.ROOT); if(up.startsWith("SLEEP ")){ long ms=0; try{ms=Long.parseLong(line.substring(6).trim());}catch(Exception ignored){} PendingCommand pc=new PendingCommand(); pc.type=PendingType.SLEEP_DELAY; pc.sleepMs=Math.max(0,ms); list.add(pc);} else if (up.equals("SLEEP")){} else if (up.startsWith("TICK")){ int t=1; String[] parts=line.split("\\s+"); if(parts.length>1){ try{ t=Integer.parseInt(parts[1]); }catch(Exception ignored){} } PendingCommand pc=new PendingCommand(); pc.type=PendingType.TICK_DELAY; pc.ticks=Math.max(1,t); list.add(pc);} else { PendingCommand pc=new PendingCommand(); pc.type=PendingType.ACTION; pc.line=line; list.add(pc);} }
        if(presetDebugWindow!=null && presetDebugWindow.isVisible()){ try { presetDebugWindow.logExecution(rule, original, ctx);} catch(Exception ignored){} }
        if(list.isEmpty()) return; CommandSequence seq=new CommandSequence(); seq.rule=rule; seq.ctx=ctx; seq.commands=list; seq.index=0; seq.tickDelayRemaining=0; seq.sleepUntilMillis=0L; activeSequences.add(seq); }

    private Map<String,String> baseContext(Skill skill,int value,KPWebhookPreset.WidgetConfig widgetCfg, Player otherPlayer, NPC npc){ Map<String,String> ctx=new HashMap<>(); String local="Unknown"; try{ if(client.getLocalPlayer()!=null) local= sanitizePlayerName(client.getLocalPlayer().getName()); }catch(Exception ignored){} ctx.put("player",local); ctx.put("TARGET", getCurrentTargetName()); ctx.put("$TARGET", getCurrentTargetName()); ctx.put("stat", skill!=null?skill.name():""); ctx.put("value", value>=0? String.valueOf(value):""); try{ ctx.put("current", skill!=null? String.valueOf(client.getBoostedSkillLevel(skill)):""); }catch(Exception ignored){ ctx.put("current", ""); } if(widgetCfg!=null){ ctx.put("widgetGroup", String.valueOf(widgetCfg.getGroupId())); ctx.put("widgetChild", widgetCfg.getChildId()==null?"": String.valueOf(widgetCfg.getChildId())); } else { ctx.put("widgetGroup",""); ctx.put("widgetChild",""); } ctx.put("time", Instant.now().toString()); if(otherPlayer!=null){ ctx.put("otherPlayer", sanitizePlayerName(otherPlayer.getName())); try { ctx.put("otherCombat", String.valueOf(otherPlayer.getCombatLevel())); } catch(Exception ignored){ ctx.put("otherCombat",""); } } else { ctx.put("otherPlayer",""); ctx.put("otherCombat",""); } if(npc!=null){ ctx.put("npcName", sanitizeNpcName(npc.getName())); ctx.put("npcId", String.valueOf(npc.getId())); } else { ctx.put("npcName",""); ctx.put("npcId",""); }
        // Updated hitsplat tokens
        ctx.put("HITSPLAT", lastHitsplatAny>=0? String.valueOf(lastHitsplatAny):"");
        ctx.put("HITSPLAT_SELF", lastHitsplatSelf>=0? String.valueOf(lastHitsplatSelf):"");
        ctx.put("HITSPLAT_TARGET", lastHitsplatTarget>=0? String.valueOf(lastHitsplatTarget):"");
        // lowercase aliases to allow $hitsplat_self usage
        ctx.put("hitsplat", ctx.get("HITSPLAT"));
        ctx.put("hitsplat_self", ctx.get("HITSPLAT_SELF"));
        ctx.put("hitsplat_target", ctx.get("HITSPLAT_TARGET"));
        try { ctx.put("WORLD", String.valueOf(client.getWorld())); } catch(Exception ignored){ ctx.put("WORLD",""); } for(Skill s: Skill.values()){ try { int real=client.getRealSkillLevel(s); int boosted=client.getBoostedSkillLevel(s); ctx.put("$"+s.name(), String.valueOf(real)); ctx.put("$CURRENT_"+s.name(), String.valueOf(boosted)); ctx.put(s.name(), String.valueOf(real)); ctx.put("CURRENT_"+s.name(), String.valueOf(boosted)); } catch(Exception ignored){} } return ctx; }

    /* === Game Tick === */
    @Subscribe public void onGameTick(GameTick t){ gameTickCounter++; seenProjectiles.clear(); updateAndProcessTarget(); if(tickTriggerService!=null){ tickTriggerService.process(rules, overheadTexts); /* removed TICK preset debug logging */ }
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

    private void updateAndProcessTarget(){ try { Player local=client.getLocalPlayer(); Actor interacting=null; if(local!=null){ Actor raw = local.getInteracting(); if(raw!=null && isAttackable(raw)) interacting=raw; } boolean targetExpired = currentTarget!=null && (gameTickCounter - targetLastActiveTick) > TARGET_RETENTION_TICKS; boolean targetChanged=false; if(interacting!=null){ if(currentTarget==null || currentTarget!=interacting) targetChanged=true; } else if(targetExpired && currentTarget!=null){ targetChanged=true; }
            if(targetChanged){ Actor old=currentTarget; if(targetExpired || interacting==null){ if(targetExpired) stopAllTargetPresets(); currentTarget=null; lastTargetAnimationId=-1; lastTargetGraphicId=-1; } else { currentTarget=interacting; lastTargetAnimationId=currentTarget.getAnimation(); try { lastTargetGraphicId=currentTarget.getGraphic(); } catch(Exception ignored){ lastTargetGraphicId=-1; } targetLastActiveTick=gameTickCounter; } fireTargetChangeTriggers(old,currentTarget); } else if(interacting!=null){ targetLastActiveTick=gameTickCounter; } } catch(Exception ignored){} }

    private boolean isAttackable(Actor a){ if(a==null) return false; if(a instanceof NPC) return true; if(a instanceof Player) return a!=client.getLocalPlayer(); return false; }
    private void fireTargetChangeTriggers(Actor oldT, Actor newT){ if(oldT==newT) return; if(debugWindow!=null && debugWindow.isVisible()){ try { String oldName = oldT instanceof Player? sanitizePlayerName(((Player)oldT).getName()) : (oldT instanceof NPC? sanitizeNpcName(((NPC)oldT).getName()):""); String newName = newT instanceof Player? sanitizePlayerName(((Player)newT).getName()) : (newT instanceof NPC? sanitizeNpcName(((NPC)newT).getName()):""); debugWindow.logTargetChange(oldName, newName); } catch(Exception ignored){} } for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.TARGET) continue; executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig(), (newT instanceof Player)?(Player)newT:null, (newT instanceof NPC)?(NPC)newT:null); } }
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

    @Subscribe public void onPlayerSpawned(PlayerSpawned ev){ if(ev==null) return; Player p=ev.getPlayer(); if(p==null) return; boolean self = client.getLocalPlayer()==p; String name=sanitizePlayerName(p.getName()); if(debugWindow!=null && debugWindow.isVisible()){ try{ int localCombat=0; try{ if(client.getLocalPlayer()!=null) localCombat=client.getLocalPlayer().getCombatLevel(); }catch(Exception ignored){} debugWindow.logPlayerSpawn(false,name,p.getCombatLevel(), localCombat); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.PLAYER_SPAWN){ if(playerMatches(r.getPlayerConfig(), p, self)) executeRule(r,null,-1,null,null,p); } }
    }
    @Subscribe public void onPlayerDespawned(PlayerDespawned ev){ if(ev==null) return; Player p=ev.getPlayer(); if(p==null) return; boolean self = client.getLocalPlayer()==p; String name=sanitizePlayerName(p.getName()); if(debugWindow!=null && debugWindow.isVisible()){ try{ int localCombat=0; try{ if(client.getLocalPlayer()!=null) localCombat=client.getLocalPlayer().getCombatLevel(); }catch(Exception ignored){} debugWindow.logPlayerSpawn(true,name,p.getCombatLevel(), localCombat); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.PLAYER_DESPAWN){ if(playerMatches(r.getPlayerConfig(), p, self)) executeRule(r,null,-1,null,null,p); } }
    }
    private boolean playerMatches(KPWebhookPreset.PlayerConfig cfg, Player p, boolean self){ if(cfg==null) return true; if(self) return false; if(cfg.isAll()) return true; if(cfg.getCombatRange()!=null){ try{ int local= client.getLocalPlayer()!=null? client.getLocalPlayer().getCombatLevel():0; return Math.abs(local - p.getCombatLevel())<= cfg.getCombatRange(); }catch(Exception ignored){ return false; } } List<String> names=cfg.getNames(); if(names!=null && !names.isEmpty()){ String pn=sanitizePlayerName(p.getName()).toLowerCase(Locale.ROOT); for(String n: names) if(pn.equals(n)) return true; return false; } if(cfg.getName()!=null && !cfg.getName().isBlank()){ return sanitizePlayerName(p.getName()).equalsIgnoreCase(cfg.getName()); } return true; }

    @Subscribe public void onNpcSpawned(NpcSpawned ev){ if(ev==null) return; NPC n=ev.getNpc(); if(n==null) return; String name=sanitizeNpcName(n.getName()); if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logNpcSpawn(false,name,n.getId(), n.getCombatLevel()); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.NPC_SPAWN){ if(npcMatches(r.getNpcConfig(), n)) executeRule(r,null,-1,null,null,null,n); } }
    }
    @Subscribe public void onNpcDespawned(NpcDespawned ev){ if(ev==null) return; NPC n=ev.getNpc(); if(n==null) return; String name=sanitizeNpcName(n.getName()); if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logNpcSpawn(true,name,n.getId(), n.getCombatLevel()); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.NPC_DESPAWN){ if(npcMatches(r.getNpcConfig(), n)) executeRule(r,null,-1,null,null,null,n); } }
    }
    private boolean npcMatches(KPWebhookPreset.NpcConfig cfg, NPC n){ if(cfg==null) return true; boolean idsOk=true; if(cfg.getNpcIds()!=null && !cfg.getNpcIds().isEmpty()){ idsOk=false; for(Integer i: cfg.getNpcIds()) if(i!=null && i==n.getId()){ idsOk=true; break; } }
        boolean namesOk=true; if(cfg.getNpcNames()!=null && !cfg.getNpcNames().isEmpty()){ namesOk=false; String norm=sanitizeNpcName(n.getName()).toLowerCase(Locale.ROOT).replace(" ","_"); for(String s: cfg.getNpcNames()) if(norm.equals(s)){ namesOk=true; break; } }
        return idsOk && namesOk; }

    @Subscribe public void onAnimationChanged(AnimationChanged ev){ if(ev==null) return; Actor a=ev.getActor(); if(a==null) return; int anim; try{ anim=a.getAnimation(); }catch(Exception e){ return; } boolean self = a==client.getLocalPlayer(); boolean isTarget = a==currentTarget; if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logAnimation(a,self,isTarget,anim); }catch(Exception ignored){} } if(anim<=0) return; for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; KPWebhookPreset.TriggerType tt=r.getTriggerType(); if(tt== KPWebhookPreset.TriggerType.ANIMATION_SELF && self){ if(matchesAnim(r.getAnimationConfig(),anim)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); }
            else if(tt== KPWebhookPreset.TriggerType.ANIMATION_TARGET && isTarget){ if(matchesAnim(r.getAnimationConfig(),anim)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); }
            else if(tt== KPWebhookPreset.TriggerType.ANIMATION_ANY){ if(matchesAnim(r.getAnimationConfig(),anim)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); } }
    }

    @Subscribe public void onGraphicChanged(GraphicChanged ev){ if(ev==null) return; Actor a=ev.getActor(); if(a==null) return; int gid; try{ gid=a.getGraphic(); }catch(Exception e){ return; } if(gid<=0) return; boolean self = a==client.getLocalPlayer(); boolean isTarget = a==currentTarget; if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logGraphic(a,self,isTarget,gid); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; KPWebhookPreset.TriggerType tt=r.getTriggerType(); if(tt== KPWebhookPreset.TriggerType.GRAPHIC_SELF && self){ if(matchesGraphic(r.getGraphicConfig(),gid)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); }
            else if(tt== KPWebhookPreset.TriggerType.GRAPHIC_TARGET && isTarget){ if(matchesGraphic(r.getGraphicConfig(),gid)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); }
            else if(tt== KPWebhookPreset.TriggerType.GRAPHIC_ANY){ if(matchesGraphic(r.getGraphicConfig(),gid)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); } }
    }

    @Subscribe public void onHitsplatApplied(HitsplatApplied ev){ if(ev==null) return; Actor target=ev.getActor(); if(target==null) return; int amount= ev.getHitsplat()!=null? ev.getHitsplat().getAmount():0; boolean self = target==client.getLocalPlayer(); boolean isTarget = target==currentTarget; if(self){ lastHitsplatSelf = amount; lastHitsplatAny = amount; } else if(isTarget){ lastHitsplatTarget = amount; lastHitsplatAny = amount; } if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logHitsplat(target,self,isTarget,amount); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.HITSPLAT_SELF && self){ if(matchesHitsplat(r.getHitsplatConfig(), amount)) executeRule(r,null,-1,null,null,(target instanceof Player)?(Player)target:null,(target instanceof NPC)?(NPC)target:null); }
            else if(r.getTriggerType()== KPWebhookPreset.TriggerType.HITSPLAT_TARGET && isTarget){ if(matchesHitsplat(r.getHitsplatConfig(), amount)) executeRule(r,null,-1,null,null,(target instanceof Player)?(Player)target:null,(target instanceof NPC)?(NPC)target:null); } }
    }
    private boolean matchesHitsplat(KPWebhookPreset.HitsplatConfig cfg,int val){ if(cfg==null) return true; KPWebhookPreset.HitsplatConfig.Mode m=cfg.getMode(); if(m==null) m= KPWebhookPreset.HitsplatConfig.Mode.GREATER; Integer ref=cfg.getValue(); switch(m){ case MAX: return true; case GREATER: return ref!=null && val>ref; case GREATER_EQUAL: return ref!=null && val>=ref; case EQUAL: return ref!=null && val==ref; case LESS_EQUAL: return ref!=null && val<=ref; case LESS: return ref!=null && val<ref; } return false; }

    private boolean projectileFromLocal(Projectile p, Actor targetActor){ Player local = client.getLocalPlayer(); if(p==null || local==null) return false; try { if(targetActor!=null && targetActor!=local){ try { if(local.getInteracting()==targetActor) return true; } catch(Exception ignored){} } if(targetActor!=local){ try { LocalPoint lpLocal = local.getLocalLocation(); if(lpLocal!=null){ double dx=Math.abs(p.getX()-lpLocal.getX()); double dy=Math.abs(p.getY()-lpLocal.getY()); if(dx<=160 && dy<=160) return true; } } catch(Exception ignored){} } } catch(Exception ignored){} return false; }

    @Subscribe public void onProjectileMoved(ProjectileMoved ev){ if(ev==null) return; Projectile p=ev.getProjectile(); if(p==null) return; int pid=p.getId(); int identity = System.identityHashCode(p); if(seenProjectileIdentities.contains(identity)) return; seenProjectileIdentities.add(identity); if(seenProjectileIdentities.size()>4000){ seenProjectileIdentities.clear(); seenProjectileIdentities.add(identity);} Player local = client.getLocalPlayer(); Actor targetActor=null; try{ targetActor=p.getInteracting(); }catch(Exception ignored){}
        boolean incomingToLocal = targetActor!=null && targetActor==local; boolean selfShot = projectileFromLocal(p, targetActor); Actor shooter = null; if(selfShot){ shooter = local; } else if(incomingToLocal){ try { List<Player> players = client.getPlayers(); if(players!=null){ for(Player pl : players){ if(pl!=null && pl!=local){ try { if(pl.getInteracting()==local){ shooter=pl; break; } } catch(Exception ignored){} } } } if(shooter==null){ try { List<NPC> npcs = client.getNpcs(); if(npcs!=null){ for(NPC n: npcs){ if(n==null) continue; try { if(n.getInteracting()==local){ shooter=n; break; } } catch(Exception ignored){} } } } catch(Exception ignored){} } } catch(Exception ignored){} }
        if(debugWindow!=null && debugWindow.isVisible()){ try { boolean loggedAny=false; if(selfShot){ debugWindow.logProjectile("PROJECTILE_SELF", p, shooter!=null?shooter:targetActor); debugWindow.logProjectile("PROJECTILE_ANY", p, shooter!=null?shooter:targetActor); loggedAny=true; } if(incomingToLocal){ debugWindow.logProjectile("PROJECTILE_TARGET", p, shooter!=null?shooter: (selfShot? local : targetActor)); if(!loggedAny){ debugWindow.logProjectile("PROJECTILE_ANY", p, shooter!=null?shooter: (selfShot? local : targetActor)); loggedAny=true; } } if(!selfShot && !incomingToLocal){ debugWindow.logProjectile("PROJECTILE_ANY", p, shooter!=null?shooter: targetActor); } } catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; KPWebhookPreset.TriggerType tt=r.getTriggerType(); if(tt== KPWebhookPreset.TriggerType.PROJECTILE_SELF && selfShot){ if(matchesProjectile(r.getProjectileConfig(), pid)) executeRule(r,null,-1,null,null,null,null); } else if(tt== KPWebhookPreset.TriggerType.PROJECTILE_TARGET && incomingToLocal){ if(matchesProjectile(r.getProjectileConfig(), pid)) executeRule(r,null,-1,null,null,null,null); } else if(tt== KPWebhookPreset.TriggerType.PROJECTILE_ANY){ if(matchesProjectile(r.getProjectileConfig(), pid)) executeRule(r,null,-1,null,null,null,null); } }
    }

    @Subscribe public void onConfigChanged(ConfigChanged e){ if(e==null) return; if(!"kpwebhook".equals(e.getGroup())) return; }

    private String sanitizePlayerName(String n){ if(n==null) return ""; try { String nt= Text.removeTags(n); return nt.replace('\u00A0',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').trim(); } }
    private String sanitizeNpcName(String n){ if(n==null) return ""; try { String nt= Text.removeTags(n); return nt.replace('\u00A0',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').trim(); } }
    public String getDefaultWebhook(){ String def=config.defaultWebhookUrl(); return def!=null? def.trim():""; }

    public void openDebugWindow(){ try { if(debugWindow==null) debugWindow=new KPWebhookDebugWindow(this); if(!debugWindow.isVisible()) debugWindow.setVisible(true); else debugWindow.toFront(); } catch(Exception ignored){} }
    public void openPresetDebugWindow(){ try { if(presetDebugWindow==null) presetDebugWindow=new KPWebhookPresetDebugWindow(); if(!presetDebugWindow.isVisible()) presetDebugWindow.setVisible(true); else presetDebugWindow.toFront(); } catch(Exception ignored){} }

    private void addOverheadTextFromPreset(String text,String pos,KPWebhookPreset rule){ if(text==null||text.isBlank()||rule==null) return; ActiveOverheadText ot=new ActiveOverheadText(); ot.text=text; ot.position=pos; ot.ruleId=rule.getId(); ot.color=Color.WHITE; ot.size=16; ot.remainingTicks=80; overheadTexts.add(ot);}
    public void addOverheadText(KPWebhookPreset rule,String text,String pos){ if(rule!=null){ addOverheadTextFromPreset(text,pos!=null?pos:"Above",rule); return;} ActiveOverheadText ot=new ActiveOverheadText(); ot.text=text; ot.position=pos!=null?pos:"Above"; ot.remainingTicks=80; overheadTexts.add(ot);}

    private String expand(String input, Map<String,String> ctx){ if(input==null||ctx==null||ctx.isEmpty()) return input; String out=input; List<String> keys=new ArrayList<>(ctx.keySet()); keys.sort((a,b)->Integer.compare(b.length(), a.length())); for(String k: keys){ String v=ctx.get(k); if(v==null) v=""; out = out.replace("${"+k+"}", v).replace("{{"+k+"}}", v); if(!k.equals(k.toLowerCase(Locale.ROOT))){ String lk=k.toLowerCase(Locale.ROOT); out = out.replace("${"+lk+"}", v).replace("{{"+lk+"}}", v); } out = safeReplaceDollarToken(out, k, v); } return out; }
    private String safeReplaceDollarToken(String text,String key,String value){ if(text==null || text.indexOf('$')<0) return text; String pattern="(?i)\\$"+Pattern.quote(key)+"(?![A-Za-z0-9_])"; return text.replaceAll(pattern, java.util.regex.Matcher.quoteReplacement(value)); }

    // Core per-line command processor for sequences
    private void processActionLine(KPWebhookPreset rule, String rawLine, Map<String,String> ctx){
        if(rawLine==null) return; String line=rawLine.trim(); if(line.isEmpty() || line.startsWith("#")) return;
        String expanded = expand(line, ctx!=null?ctx:Collections.emptyMap());
        try { if(overlayTextCommandHandler!=null && overlayTextCommandHandler.handle(expanded, rule)) return; } catch(Exception ignored){}
        try { if(infoboxCommandHandler!=null && infoboxCommandHandler.handle(expanded, rule, ctx)) return; } catch(Exception ignored){}
        try { if(highlightCommandHandler!=null && highlightCommandHandler.handle(line, rule)) return; } catch(Exception ignored){}
        if(P_TEXT_OVER.matcher(line).find() || P_TEXT_UNDER.matcher(line).find() || P_TEXT_CENTER.matcher(line).find()){ try { handleTargetedTextCommand(rule, expanded, ctx); } catch(Exception ignored){} return; }
        if(P_IMG_OVER.matcher(line).find() || P_IMG_UNDER.matcher(line).find() || P_IMG_CENTER.matcher(line).find()){ try { handleImageCommand(rule, expanded, ctx); } catch(Exception ignored){} return; }
        String upper = line.toUpperCase(Locale.ROOT);
        if(upper.equals("STOP")){ try { activeSequences.clear(); highlightManager.clear(); overlayTextManager.clear(); if(infoboxCommandHandler!=null) infoboxCommandHandler.clearAll(); overheadTexts.clear(); overheadImages.clear(); markedTiles.clear(); } catch(Exception ignored){} return; }
        if(upper.equals("STOP_RULE")){ try { cancelSequencesForRule(rule.getId()); removeOverheadsForRule(rule.getId(),true); if(infoboxCommandHandler!=null) infoboxCommandHandler.removeByRule(rule.getId()); overlayTextManager.removeByRule(rule.getId()); highlightManager.removeAllByRule(rule.getId()); } catch(Exception ignored){} return; }
        if(upper.startsWith("NOTIFY")){ try { notifier.notify(expanded.length()>6? expanded.substring(6).trim():"Event"); } catch(Exception ignored){} return; }
        if(upper.startsWith("CUSTOM_MESSAGE")){ try { notifier.notify(expanded.substring("CUSTOM_MESSAGE".length()).trim()); } catch(Exception ignored){} return; }
        if(upper.startsWith("WEBHOOK")){ // format: WEBHOOK optionalUrl text...
            try { String rest=expanded.substring(7).trim(); String[] parts=rest.split("\\s+",2); String url=getDefaultWebhook(); String payload=""; if(parts.length==1){ payload=parts[0]; } else { if(parts[0].startsWith("http")) { url=parts[0]; payload=parts[1]; } else payload=rest; }
                if(url!=null && !url.isBlank() && payload!=null){ sendWebhook(url.trim(), payload); }
            } catch(Exception ignored){} return; }
        if(upper.startsWith("SCREENSHOT")){ try { requestScreenshot(rule, ctx); } catch(Exception ignored){} return; }
        if(upper.startsWith("MARK_TILE")){ /* stub: tile marking omitted */ return; }
        if(upper.startsWith("TOGGLEPRESET")){ try { String idStr=expanded.substring("TOGGLEPRESET".length()).trim(); int rid=Integer.parseInt(idStr); toggleActive(rid); if(panel!=null) panel.refreshTable(); } catch(Exception ignored){} return; }
        if(upper.startsWith("TOGGLEGROUP")){ /* group toggle not implemented */ return; }
        // Fallback: treat as simple overhead text for visibility
        try { addOverheadText(rule, expanded, "Above"); } catch(Exception ignored){}
    }

    private void cancelSequencesForRule(int ruleId){ try { activeSequences.removeIf(s-> s!=null && s.rule!=null && s.rule.getId()==ruleId); } catch(Exception ignored){} }
    private void removeOverheadsForRule(int ruleId, boolean includeImages){ try { overheadTexts.removeIf(t->t!=null && t.ruleId==ruleId); if(includeImages) overheadImages.removeIf(i->i!=null && i.ruleId==ruleId); } catch(Exception ignored){} }
    private void removePersistentOverheadsForRule(int ruleId){ try { overheadTexts.removeIf(t->t!=null && t.ruleId==ruleId && t.persistent); overheadImages.removeIf(i->i!=null && i.ruleId==ruleId && i.persistent); } catch(Exception ignored){} }
    private void applyPresetOverheadTexts(KPWebhookPreset rule, Map<String,String> ctx){ if(rule==null) return; try {
        // Helper lambda to build and add
        java.util.function.BiConsumer<String, Runnable> add = (pos, filler) -> {
            // filler runnable sets text/style fields on object after creation
        };
        // Text OVER
        if(rule.getTextOver()!=null && !rule.getTextOver().isBlank()){
            ActiveOverheadText ot=new ActiveOverheadText();
            ot.ruleId=rule.getId();
            ot.position="Above"; // OVER maps to above
            ot.text=expand(rule.getTextOver(), ctx);
            ot.color=parseColor(rule.getTextOverColor(), Color.WHITE);
            ot.blink=Boolean.TRUE.equals(rule.getTextOverBlink());
            ot.size=safeInt(rule.getTextOverSize(),16);
            ot.remainingTicks=Math.max(1,safeInt(rule.getTextOverDuration(),80));
            ot.bold=Boolean.TRUE.equals(rule.getTextOverBold());
            ot.italic=Boolean.TRUE.equals(rule.getTextOverItalic());
            ot.underline=Boolean.TRUE.equals(rule.getTextOverUnderline());
            ot.blinkInterval=2; // could expose later
            overheadTexts.add(ot);
        }
        // Text CENTER
        if(rule.getTextCenter()!=null && !rule.getTextCenter().isBlank()){
            ActiveOverheadText ot=new ActiveOverheadText();
            ot.ruleId=rule.getId();
            ot.position="Center";
            ot.text=expand(rule.getTextCenter(), ctx);
            ot.color=parseColor(rule.getTextCenterColor(), Color.WHITE);
            ot.blink=Boolean.TRUE.equals(rule.getTextCenterBlink());
            ot.size=safeInt(rule.getTextCenterSize(),16);
            ot.remainingTicks=Math.max(1,safeInt(rule.getTextCenterDuration(),80));
            ot.bold=Boolean.TRUE.equals(rule.getTextCenterBold());
            ot.italic=Boolean.TRUE.equals(rule.getTextCenterItalic());
            ot.underline=Boolean.TRUE.equals(rule.getTextCenterUnderline());
            ot.blinkInterval=2;
            overheadTexts.add(ot);
        }
        // Text UNDER
        if(rule.getTextUnder()!=null && !rule.getTextUnder().isBlank()){
            ActiveOverheadText ot=new ActiveOverheadText();
            ot.ruleId=rule.getId();
            ot.position="Under";
            ot.text=expand(rule.getTextUnder(), ctx);
            ot.color=parseColor(rule.getTextUnderColor(), Color.WHITE);
            ot.blink=Boolean.TRUE.equals(rule.getTextUnderBlink());
            ot.size=safeInt(rule.getTextUnderSize(),16);
            ot.remainingTicks=Math.max(1,safeInt(rule.getTextUnderDuration(),80));
            ot.bold=Boolean.TRUE.equals(rule.getTextUnderBold());
            ot.italic=Boolean.TRUE.equals(rule.getTextUnderItalic());
            ot.underline=Boolean.TRUE.equals(rule.getTextUnderUnderline());
            ot.blinkInterval=2;
            overheadTexts.add(ot);
        }
    } catch(Exception ignored){} }

    private int safeInt(Integer v,int def){ return v==null?def:v; }
    private Color parseColor(String hex, Color def){ if(hex==null||hex.isBlank()) return def; try {
        String h=hex.trim(); if(h.startsWith("#")) h=h.substring(1); if(h.length()==6){ return new Color(Integer.parseInt(h,16)); } if(h.length()==8){ return new Color((int)Long.parseLong(h,16), true); }
    } catch(Exception ignored){} return def; }

    // === Debugging / Testing Hooks ===
    public void _testSend(String url, String payload){ sendWebhook(url, payload); }
    public void _testScreenshot(){ requestScreenshot(null, null); }
    public void _testRule(int id){ manualSend(id); }

    /* ===================== ADDED UTILITY / RESTORED METHODS ===================== */
    private void ensureUniqueIds(){
        // Reassign duplicate/invalid IDs and advance nextId
        Set<Integer> used = new HashSet<>();
        for(KPWebhookPreset r: new ArrayList<>(rules)){
            if(r==null) continue;
            if(r.getId()<0 || used.contains(r.getId())){
                r.setId(nextId++);
            }
            used.add(r.getId());
        }
        // Ensure nextId above max
        for(KPWebhookPreset r: rules){ if(r!=null && r.getId()>=nextId) nextId = r.getId()+1; }
    }

    private boolean matchesId(Integer single, List<Integer> list, int id){
        if(list!=null && !list.isEmpty()) return list.contains(id);
        if(single!=null) return single==id;
        return false;
    }
    private boolean matchesAnim(KPWebhookPreset.AnimationConfig cfg, int anim){
        if(cfg==null) return true;
        if(cfg.getAnimationIds()!=null && !cfg.getAnimationIds().isEmpty()) return cfg.getAnimationIds().contains(anim);
        if(cfg.getAnimationId()!=null) return cfg.getAnimationId()==anim;
        return false;
    }
    private boolean matchesGraphic(KPWebhookPreset.GraphicConfig cfg, int gid){
        if(cfg==null) return true;
        if(cfg.getGraphicIds()!=null && !cfg.getGraphicIds().isEmpty()) return cfg.getGraphicIds().contains(gid);
        if(cfg.getGraphicId()!=null) return cfg.getGraphicId()==gid;
        return false;
    }
    private boolean matchesProjectile(KPWebhookPreset.ProjectileConfig cfg, int pid){
        if(cfg==null) return true;
        if(cfg.getProjectileIds()!=null && !cfg.getProjectileIds().isEmpty()) return cfg.getProjectileIds().contains(pid);
        if(cfg.getProjectileId()!=null) return cfg.getProjectileId()==pid;
        return cfg.getProjectileIds()==null || cfg.getProjectileIds().isEmpty(); // ANY
    }

    public void manualSend(int id){
        KPWebhookPreset r = find(id);
        if(r==null) return;
        executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig());
        if(debugWindow!=null && debugWindow.isVisible()){
            try { debugWindow.logManual(r.getId(), r.getTitle()); } catch(Exception ignored){}
        }
    }

    private void sendWebhook(String url, String payload){
        if(url==null || url.isBlank() || payload==null) return;
        // OkHttp3 signature: RequestBody.create(MediaType, String)
        RequestBody body = RequestBody.create(JSON, payload);
        Request req = new Request.Builder().url(url).post(body).build();
        okHttpClient.newCall(req).enqueue(new Callback(){
            @Override public void onFailure(Call call, IOException e){ log.debug("Webhook failed: {}", e.getMessage()); }
            @Override public void onResponse(Call call, Response response){ try(response){ /* noop */ } catch(Exception ignored){} }
        });
    }

    private void requestScreenshot(KPWebhookPreset rule, Map<String,String> ctx){
        long now = System.currentTimeMillis();
        if(now - lastScreenshotRequestMs < SCREENSHOT_COOLDOWN_MS) return;
        lastScreenshotRequestMs = now;
        BufferedImage img = lastFrame;
        if(img==null) return;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            String payload = "{\"screenshot\":\""+b64+"\"}";
            String url = rule!=null && !rule.isUseDefaultWebhook() && rule.getWebhookUrl()!=null && !rule.getWebhookUrl().isBlank()? rule.getWebhookUrl(): getDefaultWebhook();
            if(url!=null && !url.isBlank()) sendWebhook(url, payload);
        } catch(Exception ignored){}
    }

    private boolean isMostlyBlack(BufferedImage img){
        if(img==null) return true; int w=img.getWidth(), h=img.getHeight(); long dark=0, total=0; for(int y=0;y<h;y+=10){ for(int x=0;x<w;x+=10){ int rgb=img.getRGB(x,y); int r=(rgb>>16)&255, g=(rgb>>8)&255, b=rgb&255; int lum=(r+g+b); if(lum<60) dark++; total++; } } return total>0 && dark> (total*80/100); }
    private boolean isMostlyWhite(BufferedImage img){
        if(img==null) return false; int w=img.getWidth(), h=img.getHeight(); long light=0,total=0; for(int y=0;y<h;y+=10){ for(int x=0;x<w;x+=10){ int rgb=img.getRGB(x,y); int r=(rgb>>16)&255, g=(rgb>>8)&255, b=rgb&255; int lum=(r+g+b); if(lum>700) light++; total++; } } return total>0 && light> (total*80/100); }

    // ================= Targeted TEXT_* commands (non-TICK sequences) =================
    private void handleTargetedTextCommand(KPWebhookPreset rule, String expanded, Map<String,String> ctx){
        if(expanded==null) return;
        String line = expanded.trim();
        java.util.regex.Matcher mUnder = P_TEXT_UNDER.matcher(line);
        java.util.regex.Matcher mOver = P_TEXT_OVER.matcher(line);
        java.util.regex.Matcher mCenter = P_TEXT_CENTER.matcher(line);
        String position=null; String payload=null;
        if(mUnder.find()){ position="Under"; payload=mUnder.group(1).trim(); }
        else if(mOver.find()){ position="Above"; payload=mOver.group(1).trim(); }
        else if(mCenter.find()){ position="Center"; payload=mCenter.group(1).trim(); }
        if(position==null || payload==null || payload.isBlank()) return;
        // Parse optional target selector first token
        KPWebhookPlugin.ActiveOverheadText.TargetType targetType = KPWebhookPlugin.ActiveOverheadText.TargetType.LOCAL_PLAYER;
        Set<String> targetNames=null; Set<Integer> targetIds=null;
        String working = payload;
        String[] toks = working.split("\\s+",3);
        if(toks.length>=1){
            String t0=toks[0].toUpperCase(Locale.ROOT);
            boolean consumed=false;
            if(t0.equals("TARGET")){ targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.TARGET; consumed=true; }
            else if(t0.equals("LOCAL_PLAYER")){ consumed=true; }
            else if(t0.equals("PLAYER") && toks.length>=2){ targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.PLAYER_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(toks[1])); if(toks.length>=3) working=toks[2]; else working=""; consumed=false; /* already rebuilt below */ }
            else if(t0.equals("NPC") && toks.length>=2){ String spec=toks[1]; if(spec.matches("\\d+")){ targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.NPC_ID; targetIds=new HashSet<>(); try{ targetIds.add(Integer.parseInt(spec)); }catch(Exception ignored){} } else { targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.NPC_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(spec)); } consumed=true; }
            else if(t0.equals("FRIEND_LIST")){ targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.FRIEND_LIST; consumed=true; }
            else if(t0.equals("IGNORE_LIST")){ targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.IGNORE_LIST; consumed=true; }
            else if(t0.equals("PARTY_MEMBERS")){ targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.PARTY_MEMBERS; consumed=true; }
            else if(t0.equals("FRIENDS_CHAT")){ targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.FRIENDS_CHAT; consumed=true; }
            else if(t0.equals("TEAM_MEMBERS")){ targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.TEAM_MEMBERS; consumed=true; }
            else if(t0.equals("CLAN_MEMBERS")){ targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.CLAN_MEMBERS; consumed=true; }
            else if(t0.equals("OTHERS")){ targetType= KPWebhookPlugin.ActiveOverheadText.TargetType.OTHERS; consumed=true; }
            if(consumed){ working = working.substring(toks[0].length()).trim(); }
        }
        if(working.isEmpty()) return;
        String finalText = expand(working, ctx!=null?ctx:Collections.emptyMap());
        ActiveOverheadText ot = new ActiveOverheadText();
        ot.setRuleId(rule!=null?rule.getId():-1);
        ot.setPosition(position);
        ot.setText(finalText);
        // Pull styling from preset per position
        Color color = Color.WHITE; int size=16; boolean blink=false; boolean bold=false; boolean italic=false; boolean underline=false; int duration=80;
        if(rule!=null){
            if("Above".equals(position)){ color=parseColor(rule.getTextOverColor(), color); size=safeInt(rule.getTextOverSize(),16); blink=Boolean.TRUE.equals(rule.getTextOverBlink()); bold=Boolean.TRUE.equals(rule.getTextOverBold()); italic=Boolean.TRUE.equals(rule.getTextOverItalic()); underline=Boolean.TRUE.equals(rule.getTextOverUnderline()); duration=safeInt(rule.getTextOverDuration(),80);} else if("Under".equals(position)){ color=parseColor(rule.getTextUnderColor(), color); size=safeInt(rule.getTextUnderSize(),16); blink=Boolean.TRUE.equals(rule.getTextUnderBlink()); bold=Boolean.TRUE.equals(rule.getTextUnderBold()); italic=Boolean.TRUE.equals(rule.getTextUnderItalic()); underline=Boolean.TRUE.equals(rule.getTextUnderUnderline()); duration=safeInt(rule.getTextUnderDuration(),80);} else if("Center".equals(position)){ color=parseColor(rule.getTextCenterColor(), color); size=safeInt(rule.getTextCenterSize(),16); blink=Boolean.TRUE.equals(rule.getTextCenterBlink()); bold=Boolean.TRUE.equals(rule.getTextCenterBold()); italic=Boolean.TRUE.equals(rule.getTextCenterItalic()); underline=Boolean.TRUE.equals(rule.getTextCenterUnderline()); duration=safeInt(rule.getTextCenterDuration(),80);} }
        ot.setColor(color); ot.setSize(size); ot.setBlink(blink); ot.setBold(bold); ot.setItalic(italic); ot.setUnderline(underline); ot.setBlinkInterval(blink?2:0); ot.setBlinkCounter(0);
        if(duration<=0){ ot.setPersistent(true); ot.setRemainingTicks(0);} else { ot.setPersistent(false); ot.setRemainingTicks(Math.max(1,duration)); }
        ot.setTargetType(targetType); ot.setTargetNames(targetNames); ot.setTargetIds(targetIds);
        overheadTexts.add(ot);
    }
    private String normalizeName(String n){ if(n==null) return ""; return n.replace('_',' ').trim().toLowerCase(Locale.ROOT); }

    // ================= Image Commands (IMG_OVER/UNDER/CENTER) =================
    private void handleImageCommand(KPWebhookPreset rule, String expanded, Map<String,String> ctx){
        String line = expanded==null?"":expanded.trim();
        java.util.regex.Matcher mO = P_IMG_OVER.matcher(line);
        java.util.regex.Matcher mU = P_IMG_UNDER.matcher(line);
        java.util.regex.Matcher mC = P_IMG_CENTER.matcher(line);
        String position=null; String rest=null;
        if(mO.find()){ position="Above"; rest=mO.group(1).trim(); }
        else if(mU.find()){ position="Under"; rest=mU.group(1).trim(); }
        else if(mC.find()){ position="Center"; rest=mC.group(1).trim(); }
        if(position==null || rest==null || rest.isBlank()) return;
        // Optional target then id
        String[] toks = rest.split("\\s+",3);
        KPWebhookPlugin.ActiveOverheadImage.TargetType targetType = KPWebhookPlugin.ActiveOverheadImage.TargetType.LOCAL_PLAYER;
        Set<String> targetNames=null; Set<Integer> targetIds=null; int idx=0;
        if(toks.length>0){ String t0=toks[0].toUpperCase(Locale.ROOT); boolean consumed=false; if(t0.equals("TARGET")){ targetType= KPWebhookPlugin.ActiveOverheadImage.TargetType.TARGET; consumed=true;} else if(t0.equals("LOCAL_PLAYER")){ consumed=true; } else if(t0.equals("PLAYER") && toks.length>=2){ targetType= KPWebhookPlugin.ActiveOverheadImage.TargetType.PLAYER_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(toks[1])); idx=2; } else if(t0.equals("NPC") && toks.length>=2){ String spec=toks[1]; if(spec.matches("\\d+")){ targetType= KPWebhookPlugin.ActiveOverheadImage.TargetType.NPC_ID; targetIds=new HashSet<>(); try{ targetIds.add(Integer.parseInt(spec)); }catch(Exception ignored){} } else { targetType= KPWebhookPlugin.ActiveOverheadImage.TargetType.NPC_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(spec)); } idx=2; } else if(t0.equals("FRIEND_LIST")){ targetType= KPWebhookPlugin.ActiveOverheadImage.TargetType.FRIEND_LIST; consumed=true;} else if(t0.equals("IGNORE_LIST")){ targetType= KPWebhookPlugin.ActiveOverheadImage.TargetType.IGNORE_LIST; consumed=true;} else if(t0.equals("PARTY_MEMBERS")){ targetType= KPWebhookPlugin.ActiveOverheadImage.TargetType.PARTY_MEMBERS; consumed=true;} else if(t0.equals("FRIENDS_CHAT")){ targetType= KPWebhookPlugin.ActiveOverheadImage.TargetType.FRIENDS_CHAT; consumed=true;} else if(t0.equals("TEAM_MEMBERS")){ targetType= KPWebhookPlugin.ActiveOverheadImage.TargetType.TEAM_MEMBERS; consumed=true;} else if(t0.equals("CLAN_MEMBERS")){ targetType= KPWebhookPlugin.ActiveOverheadImage.TargetType.CLAN_MEMBERS; consumed=true;} else if(t0.equals("OTHERS")){ targetType= KPWebhookPlugin.ActiveOverheadImage.TargetType.OTHERS; consumed=true;} if(consumed){ idx=1; } }
        if(idx>=toks.length) return; // no id
        String idTok = toks[idx];
        int itemOrSpriteId=0; boolean sprite=false;
        try { if(idTok.startsWith("-")){ sprite=true; itemOrSpriteId = Integer.parseInt(idTok.substring(1)); } else itemOrSpriteId=Integer.parseInt(idTok); } catch(Exception ignored){ return; }
        int duration = rule!=null? safeInt(rule.getImgDuration(),100):100; boolean blink = rule!=null && Boolean.TRUE.equals(rule.getImgBlink());
        ActiveOverheadImage oi = new ActiveOverheadImage();
        oi.setItemOrSpriteId(itemOrSpriteId); oi.setSprite(sprite); oi.setPosition(position); oi.setBlink(blink); oi.setBlinkInterval(blink?2:0); oi.setBlinkCounter(0); oi.setVisiblePhase(true); oi.setRuleId(rule!=null?rule.getId():-1); oi.setTargetType(targetType); oi.setTargetNames(targetNames); oi.setTargetIds(targetIds);
        if(duration<=0){ oi.setPersistent(true); oi.setRemainingTicks(0);} else { oi.setPersistent(false); oi.setRemainingTicks(Math.max(1,duration)); }
        // Image loading: if sprite we use spriteManager, else itemManager
        try {
            if(sprite){ spriteManager.getSpriteAsync(itemOrSpriteId,0, s->{ if(s!=null) oi.setImage(s); }); }
            else { itemManager.getImage(itemOrSpriteId).flush(); BufferedImage img = itemManager.getImage(itemOrSpriteId); if(img!=null) oi.setImage(img); }
        } catch(Exception ignored){}
        overheadImages.add(oi);
    }

    // ================= Gear Change Detection =================
    private void processGearChanged(){
        Player local = null; try { local = client.getLocalPlayer(); } catch(Exception ignored){}
        if(local!=null){ try { ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT); if(equip!=null){ int[] ids = Arrays.stream(equip.getItems()).mapToInt(i-> i!=null? i.getId():-1).toArray(); Set<Integer> current = new HashSet<>(); for(int id: ids){ if(id>0) current.add(id); }
                    if(!localGearInitialized){ lastLocalGear.clear(); lastLocalGear.addAll(current); localGearInitialized=true; }
                    else if(!current.equals(lastLocalGear)){
                        // Determine added items (simple diff)
                        Set<Integer> added = new HashSet<>(current); added.removeAll(lastLocalGear);
                        for(Integer add: added){ if(add==null) continue; gearChanged(false, add); }
                        lastLocalGear.clear(); lastLocalGear.addAll(current);
                    }
                } } catch(Exception ignored){}
        }
        // Target player gear
        Player tPlayer = (currentTarget instanceof Player)? (Player)currentTarget : null;
        if(tPlayer!=null){ if(lastTargetPlayerForGear!=tPlayer){ lastTargetPlayerForGear = tPlayer; lastTargetGear.clear(); // reset baseline
            try { ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT); } catch(Exception ignored){} }
            try { ItemContainer equip = tPlayer.getPlayerComposition()!=null? client.getItemContainer(InventoryID.EQUIPMENT): null; } catch(Exception ignored){}
            // We cannot directly read target equipment container via API; approximate via hinted equipment IDs through model? (Simplified: skip if not local). RuneLite API may not expose others' equipment easily; we keep simple: no diff detection if not accessible.
        }
    }

    private void gearChanged(boolean target, int itemId){
        String name=""; try { name = itemManager.getItemComposition(itemId).getName(); } catch(Exception ignored){}
        if(debugWindow!=null && debugWindow.isVisible()){
            try { debugWindow.logGearChange(target, itemId, name); } catch(Exception ignored){}
        }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; KPWebhookPreset.TriggerType tt = r.getTriggerType(); if((!target && tt== KPWebhookPreset.TriggerType.GEAR_CHANGED) || (target && tt== KPWebhookPreset.TriggerType.TARGET_GEAR_CHANGED)){
                if(matchesGear(r.getGearConfig(), itemId, name)) executeRule(r,null,-1,null,null); }
        }
    }
    private boolean matchesGear(KPWebhookPreset.GearConfig cfg, int itemId, String name){ if(cfg==null) return true; String low = name!=null? name.toLowerCase(Locale.ROOT):""; if(cfg.getItemIds()!=null && cfg.getItemIds().contains(itemId)) return true; if(cfg.getNames()!=null && cfg.getNames().contains(low)) return true; if(cfg.getWildcards()!=null){ for(String w: cfg.getWildcards()){ if(w==null) continue; String pattern=w.toLowerCase(Locale.ROOT); if(!pattern.contains("*")){ if(low.contains(pattern)) return true; } else { String regex = Pattern.quote(pattern).replace("*","\\E.*\\Q"); if(low.matches("(?i)"+regex)) return true; } } } return false; }

    // ================= END ADDED METHODS =================
}
