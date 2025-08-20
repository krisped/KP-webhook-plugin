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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
        name = "KP Webhook",
        description = "Triggers: MANUAL, STAT, WIDGET_SPAWN, PLAYER_SPAWN, PLAYER_DESPAWN, NPC_SPAWN, NPC_DESPAWN, ANIMATION_SELF, ANIMATION_TARGET, ANIMATION_ANY, GRAPHIC_SELF, GRAPHIC_TARGET, GRAPHIC_ANY, PROJECTILE_SELF, PROJECTILE_TARGET, PROJECTILE_ANY, HITSPLAT_SELF, HITSPLAT_TARGET, MESSAGE, VARBIT, VARPLAYER, TICK, TARGET. Commands: NOTIFY, CUSTOM_MESSAGE, WEBHOOK, SCREENSHOT, HIGHLIGHT_*, TEXT_*, OVERLAY_TEXT, SLEEP, TICK, STOP.",
        tags = {"webhook","stat","trigger","screenshot","widget","highlight","text","player","npc","varbit","varplayer","tick","overlay","target","graphic","hitsplat","projectile"}
)
public class KPWebhookPlugin extends Plugin {
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

    private final Set<Integer> seenProjectiles = new HashSet<>();
    // New: track projectile identity hashes so each projectile logs only once
    private final Set<Integer> seenProjectileIdentities = new HashSet<>();
    // Dedup varplayer logging
    private final Map<Integer,Integer> lastVarplayerValue = new HashMap<>();
    private final Map<Integer,Integer> lastVarplayerTick = new HashMap<>();

    // Overhead texts
    @Data public static class ActiveOverheadText { String text; Color color; boolean blink; int size; String position; int remainingTicks; boolean visiblePhase; int blinkCounter; int blinkInterval; boolean bold; boolean italic; boolean underline; String key; boolean persistent; int ruleId=-1; public enum TargetType { LOCAL_PLAYER, PLAYER_NAME, NPC_NAME, NPC_ID, TARGET } TargetType targetType=TargetType.LOCAL_PLAYER; Set<String> targetNames; Set<Integer> targetIds; }
    private final List<ActiveOverheadText> overheadTexts = new ArrayList<>(); public List<ActiveOverheadText> getOverheadTexts(){ return overheadTexts; }

    // Overhead images
    @Data public static class ActiveOverheadImage { BufferedImage image; int itemOrSpriteId; boolean sprite; String position; int remainingTicks; boolean persistent; boolean blink; int blinkCounter; int blinkInterval=2; boolean visiblePhase=true; int ruleId=-1; public enum TargetType { LOCAL_PLAYER, PLAYER_NAME, NPC_NAME, NPC_ID, TARGET } TargetType targetType=TargetType.LOCAL_PLAYER; Set<String> targetNames; Set<Integer> targetIds; }
    private final List<ActiveOverheadImage> overheadImages = new ArrayList<>(); public List<ActiveOverheadImage> getOverheadImages(){ return overheadImages; }

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
    @Override protected void shutDown(){ overlayManager.remove(highlightOverlay); overlayManager.remove(minimapHighlightOverlay); overlayManager.remove(overlayTextOverlay); clientToolbar.removeNavigation(navButton); saveAllPresets(); rules.clear(); highlightManager.clear(); overheadTexts.clear(); overheadImages.clear(); if (infoboxCommandHandler!=null) infoboxCommandHandler.clearAll(); if (overlayTextManager!=null) overlayTextManager.clear(); if (debugWindow!=null){ try{debugWindow.dispose();}catch(Exception ignored){} debugWindow=null;} if (presetDebugWindow!=null){ try{presetDebugWindow.dispose();}catch(Exception ignored){} presetDebugWindow=null;} }

    private void captureInitialRealLevels(){ for (Skill s: Skill.values()){ try { lastRealLevel.put(s, client.getRealSkillLevel(s)); } catch(Exception ignored){} } }

    public List<KPWebhookPreset> getRules(){ return rules; }
    public void addOrUpdate(KPWebhookPreset p){ if(p==null) return; // normalize category: trim and reuse existing category casing if present
        String previousTitle=null; if(p.getId()>=0){ KPWebhookPreset existing = find(p.getId()); if(existing!=null) previousTitle = existing.getTitle(); }
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
    private void stopRule(int ruleId){ cancelSequencesForRule(ruleId); removeOverheadsForRule(ruleId,true); removePersistentOverheadsForRule(ruleId); try{highlightManager.removeAllByRule(ruleId);}catch(Exception ignored){} overheadImages.removeIf(i->i!=null && i.ruleId==ruleId); try { overlayTextManager.removeByRule(ruleId);} catch(Exception ignored){} try{ if(infoboxCommandHandler!=null) infoboxCommandHandler.removeByRule(ruleId);}catch(Exception ignored){} }

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

    private Map<String,String> baseContext(Skill skill,int value,KPWebhookPreset.WidgetConfig widgetCfg, Player otherPlayer, NPC npc){ Map<String,String> ctx=new HashMap<>(); String local="Unknown"; try{ if(client.getLocalPlayer()!=null) local= sanitizePlayerName(client.getLocalPlayer().getName()); }catch(Exception ignored){} ctx.put("player",local); ctx.put("TARGET", getCurrentTargetName()); ctx.put("$TARGET", getCurrentTargetName()); ctx.put("stat", skill!=null?skill.name():""); ctx.put("value", value>=0? String.valueOf(value):""); try{ ctx.put("current", skill!=null? String.valueOf(client.getBoostedSkillLevel(skill)):""); }catch(Exception ignored){ ctx.put("current", ""); } if(widgetCfg!=null){ ctx.put("widgetGroup", String.valueOf(widgetCfg.getGroupId())); ctx.put("widgetChild", widgetCfg.getChildId()==null?"": String.valueOf(widgetCfg.getChildId())); } else { ctx.put("widgetGroup",""); ctx.put("widgetChild",""); } ctx.put("time", Instant.now().toString()); if(otherPlayer!=null){ ctx.put("otherPlayer", sanitizePlayerName(otherPlayer.getName())); try { ctx.put("otherCombat", String.valueOf(otherPlayer.getCombatLevel())); } catch(Exception ignored){ ctx.put("otherCombat",""); } } else { ctx.put("otherPlayer",""); ctx.put("otherCombat",""); } if(npc!=null){ ctx.put("npcName", sanitizeNpcName(npc.getName())); ctx.put("npcId", String.valueOf(npc.getId())); } else { ctx.put("npcName",""); ctx.put("npcId",""); } ctx.put("HITSPLAT",""); ctx.put("HITSPLAT_SELF",""); ctx.put("HITSPLAT_TARGET",""); try { ctx.put("WORLD", String.valueOf(client.getWorld())); } catch(Exception ignored){ ctx.put("WORLD",""); } for(Skill s: Skill.values()){ try { int real=client.getRealSkillLevel(s); int boosted=client.getBoostedSkillLevel(s); ctx.put("$"+s.name(), String.valueOf(real)); ctx.put("$CURRENT_"+s.name(), String.valueOf(boosted)); ctx.put(s.name(), String.valueOf(real)); ctx.put("CURRENT_"+s.name(), String.valueOf(boosted)); } catch(Exception ignored){} } return ctx; }

    /* === Game Tick === */
    @Subscribe public void onGameTick(GameTick t){ gameTickCounter++; seenProjectiles.clear(); updateAndProcessTarget(); if(tickTriggerService!=null){ tickTriggerService.process(rules, overheadTexts); if(debugWindow!=null && debugWindow.isVisible()){ for(KPWebhookPreset r: rules){ if(r!=null && r.isActive() && r.getTriggerType()== KPWebhookPreset.TriggerType.TICK){ try{ debugWindow.logTickRule(r.getId(), r.getTitle()); }catch(Exception ignored){} } } } }
        // Sequences
        if(!activeSequences.isEmpty()){ long now=System.currentTimeMillis(); Iterator<CommandSequence> it=activeSequences.iterator(); while(it.hasNext()){ CommandSequence seq=it.next(); if(seq.tickDelayRemaining>0){ seq.tickDelayRemaining--; continue; } if(seq.sleepUntilMillis>0 && now<seq.sleepUntilMillis) continue; int safety=0; while(seq.index<seq.commands.size() && safety<1000){ PendingCommand pc=seq.commands.get(seq.index); if(pc.type==PendingType.TICK_DELAY){ seq.tickDelayRemaining=Math.max(1,pc.ticks); seq.index++; break; } else if(pc.type==PendingType.SLEEP_DELAY){ seq.sleepUntilMillis=now+Math.max(0,pc.sleepMs); seq.index++; break; } else { processActionLine(seq.rule, pc.line, seq.ctx); seq.index++; } safety++; } if(seq.index>=seq.commands.size()) it.remove(); } }
        // Tick visuals
        tickOverheads();
        // Capture frame
        try { SwingUtilities.invokeLater(this::captureCanvasFrame); } catch(Exception ignored){}
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
    @Subscribe public void onVarbitChanged(VarbitChanged ev){ int id=ev.getVarbitId(); int val=ev.getValue(); if(debugWindow!=null && debugWindow.isVisible()){ try{ if(id!=-1) debugWindow.logVarbit(id,val); int varpId=ev.getVarpId(); if(varpId!=-1){ int currentVal = client.getVarpValue(varpId); Integer lastV = lastVarplayerValue.get(varpId); Integer lastT = lastVarplayerTick.get(varpId); if(lastV==null || lastV!=currentVal || (lastT==null || lastT!=gameTickCounter)){ lastVarplayerValue.put(varpId,currentVal); lastVarplayerTick.put(varpId, gameTickCounter); debugWindow.logVarplayer(varpId, currentVal); } } }catch(Exception ignored){} } for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.VARBIT){ KPWebhookPreset.VarbitConfig cfg=r.getVarbitConfig(); if(cfg==null) continue; boolean idMatch = matchesId(cfg.getVarbitId(), cfg.getVarbitIds(), id); boolean valueMatch = idMatch && (cfg.getValue()==null || cfg.getValue()==val); if(r.isForceCancelOnChange()){
                if(idMatch){
                    if(valueMatch){ if(!r.isLastConditionMet()){ executeRule(r,null,-1,null,null); r.setLastConditionMet(true); savePreset(r);} }
                    else { if(r.isLastConditionMet()){ r.setLastConditionMet(false); softCancelOnChange(r); savePreset(r);} }
                }
            } else { if(valueMatch) executeRule(r,null,-1,null,null); } } else if(r.getTriggerType()== KPWebhookPreset.TriggerType.VARPLAYER){ try { int varp=ev.getVarpId(); KPWebhookPreset.VarplayerConfig cfg=r.getVarplayerConfig(); if(cfg==null) continue; boolean idMatch = matchesId(cfg.getVarplayerId(), cfg.getVarplayerIds(), varp); int current= idMatch? client.getVarpValue(varp): -999999; boolean valueMatch = idMatch && (cfg.getValue()==null || current==cfg.getValue()); if(r.isForceCancelOnChange()){
                    if(idMatch){
                        if(valueMatch){ if(!r.isLastConditionMet()){ executeRule(r,null,-1,null,null); r.setLastConditionMet(true); savePreset(r);} }
                        else { if(r.isLastConditionMet()){ r.setLastConditionMet(false); softCancelOnChange(r); savePreset(r);} }
                    }
                } else { if(valueMatch) executeRule(r,null,-1,null,null); } } catch(Exception ignored){} } } }

    @Subscribe public void onStatChanged(StatChanged ev){ Skill skill=ev.getSkill(); int real=ev.getLevel(); Integer prev=lastRealLevel.get(skill); if(prev==null || real>prev){ lastRealLevel.put(skill,real); if(prev!=null) handleLevelUp(skill, real);} else lastRealLevel.put(skill,real); handleThreshold(skill, ev.getBoostedLevel()); if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logStat(skill.name(), real, ev.getBoostedLevel()); }catch(Exception ignored){} } }

    private void handleLevelUp(Skill skill, int newReal){ for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.STAT) continue; KPWebhookPreset.StatConfig cfg=r.getStatConfig(); if(cfg==null || cfg.getSkill()!=skill || cfg.getMode()!= KPWebhookPreset.StatMode.LEVEL_UP) continue; if(r.getLastSeenRealLevel()>=0 && newReal>r.getLastSeenRealLevel()) executeRule(r,skill,newReal,cfg,null); r.setLastSeenRealLevel(newReal); savePreset(r);} }
    private void handleThreshold(Skill skill, int boosted){ for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.STAT) continue; KPWebhookPreset.StatConfig cfg=r.getStatConfig(); if(cfg==null || cfg.getSkill()!=skill) continue; if(cfg.getMode()== KPWebhookPreset.StatMode.LEVEL_UP) continue; boolean cond = cfg.getMode()== KPWebhookPreset.StatMode.ABOVE ? boosted>cfg.getThreshold(): boosted<cfg.getThreshold(); if(cond && !r.isLastConditionMet()){ removeOverheadsForRule(r.getId(), true); executeRule(r,skill,boosted,cfg,null); r.setLastConditionMet(true); r.setLastTriggeredBoosted(boosted); savePreset(r);} else if(cond && r.isLastConditionMet()){ if(boosted!=r.getLastTriggeredBoosted()){ removeOverheadsForRule(r.getId(),true); executeRule(r,skill,boosted,cfg,null); r.setLastTriggeredBoosted(boosted); savePreset(r);} } else if(!cond && r.isLastConditionMet()){ r.setLastConditionMet(false); r.setLastTriggeredBoosted(Integer.MIN_VALUE); removePersistentOverheadsForRule(r.getId()); try{ highlightManager.removePersistentByRule(r.getId()); }catch(Exception ignored){} removeOverheadsForRule(r.getId(),true); if(r.isForceCancelOnChange()){ softCancelOnChange(r); } savePreset(r);} } }

    public void manualSend(int id){ KPWebhookPreset r=find(id); if(r!=null){ if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logManual(r.getId(), r.getTitle()); }catch(Exception ignored){} } executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig()); } }

    // COMMAND PROCESSOR
    private void processActionLine(KPWebhookPreset rule, String line, Map<String,String> ctx){ if(line==null|| line.isBlank()) return; String raw=line.trim(); String up=raw.toUpperCase(Locale.ROOT); try { if(up.equals("STOP")|| up.equals("STOP_RULE")){ if(rule!=null) stopRule(rule.getId()); return;} if(up.startsWith("STOP ")|| up.startsWith("STOP_RULE ")){ String a=raw.substring(raw.indexOf(' ')+1).trim(); if(a.equalsIgnoreCase("ALL")){ for(KPWebhookPreset r: rules) stopRule(r.getId()); } else { try{ int rid=Integer.parseInt(a); stopRule(rid);}catch(Exception ignored){} } return;} if(up.startsWith("NOTIFY ")){ notifyRuneLite(expand(raw.substring(7).trim(), ctx)); return;} if(up.startsWith("CUSTOM_MESSAGE ")){ handleCustomMessage(raw.substring(14).trim(), ctx); return;} if(up.startsWith("WEBHOOK")){ String msg=raw.length()>7? raw.substring(7).trim():""; String webhook = (rule!=null && rule.getWebhookUrl()!=null && !rule.getWebhookUrl().isBlank())? rule.getWebhookUrl().trim(): getDefaultWebhook(); if(!webhook.isBlank()) sendWebhookMessage(webhook, expand(msg, ctx)); return;} if(up.startsWith("SCREENSHOT")){ String msg=raw.length()>10? raw.substring(10).trim():""; String webhook=(rule!=null && rule.getWebhookUrl()!=null && !rule.getWebhookUrl().isBlank())? rule.getWebhookUrl().trim(): getDefaultWebhook(); if(!webhook.isBlank()) captureAndSendSimpleScreenshot(webhook, expand(msg, ctx)); return;} if(up.startsWith("HIGHLIGHT_")){ if(highlightCommandHandler!=null && rule!=null) highlightCommandHandler.handle(raw, rule); return;} if(up.startsWith("OVERLAY_TEXT")){ if(overlayTextCommandHandler!=null && rule!=null) overlayTextCommandHandler.handle(raw, rule); return;} if(up.startsWith("INFOBOX")){ if(infoboxCommandHandler!=null && rule!=null) infoboxCommandHandler.handle(raw, rule, ctx); return;} if(up.startsWith("TEXT_OVER")|| up.startsWith("TEXT_UNDER")|| up.startsWith("TEXT_CENTER")){ handleTargetedTextCommand(rule, raw, ctx); return;} if(up.startsWith("IMG_OVER")|| up.startsWith("IMG_UNDER")|| up.startsWith("IMG_CENTER")){ handleImageCommand(rule, raw, ctx); return;} } catch(Exception e){ log.debug("processActionLine fail: {} => {}", raw, e.toString()); } }

    private void handleCustomMessage(String remainder, Map<String,String> ctx){ if(remainder==null||remainder.isBlank()) return; String[] parts=remainder.split("\\s+",2); if(parts.length<2) return; ChatMessageType type=resolveChatMessageType(parts[0]); String msg=expand(parts[1], ctx); try{ client.addChatMessage(type,"", msg,null);}catch(Exception ignored){} }
    private ChatMessageType resolveChatMessageType(String token){ if(token==null) return ChatMessageType.GAMEMESSAGE; String up=token.trim().toUpperCase(Locale.ROOT);
        // Try alias/name lookup first
        ChatMessageType t=CHAT_TYPE_ALIASES.get(up); if(t!=null) return t; t=CHAT_TYPE_ALIASES.get(normKey(up)); if(t!=null) return t;
        // Fallback: numeric id support (ordinal)
        try { int idx = Integer.parseInt(up); ChatMessageType[] vals = ChatMessageType.values(); if(idx>=0 && idx<vals.length) return vals[idx]; } catch(Exception ignored){}
        return ChatMessageType.GAMEMESSAGE; }

    private void notifyRuneLite(String message){ if(message==null||message.isBlank()) return; try{ if(notifier!=null) notifier.notify(message);}catch(Exception ignored){} try{ client.addChatMessage(ChatMessageType.GAMEMESSAGE,"", message,null);}catch(Exception ignored){} }

    private String expand(String input, Map<String,String> ctx){ if(input==null||ctx==null||ctx.isEmpty()) return input; String out=input; for(Map.Entry<String,String> e: ctx.entrySet()){ String k=e.getKey(); String v=e.getValue()==null?"":e.getValue(); out=out.replace("${"+k+"}", v).replace("$"+k, v).replace("{{"+k+"}}", v); } return out; }

    private boolean matchesId(Integer single,List<Integer> many,int value){ if(single!=null) return single==value; if(many!=null) for(Integer i: many) if(i!=null && i==value) return true; return false; }
    private boolean matchesAnim(KPWebhookPreset.AnimationConfig cfg,int id){ if(cfg==null) return true; if(cfg.getAnimationId()!=null) return cfg.getAnimationId()==id; List<Integer> list=cfg.getAnimationIds(); if(list!=null) for(Integer i: list) if(i!=null && i==id) return true; return false; }
    private boolean matchesGraphic(KPWebhookPreset.GraphicConfig cfg,int id){ if(cfg==null) return true; if(cfg.getGraphicId()!=null) return cfg.getGraphicId()==id; List<Integer> list=cfg.getGraphicIds(); if(list!=null) for(Integer i: list) if(i!=null && i==id) return true; return false; }
    private boolean matchesProjectile(KPWebhookPreset.ProjectileConfig cfg,int id){ if(cfg==null) return true; if(cfg.getProjectileId()!=null) return cfg.getProjectileId()==id; List<Integer> list=cfg.getProjectileIds(); if(list!=null && !list.isEmpty()){ for(Integer i: list) if(i!=null && i==id) return true; return false; } return true; }

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

    @Subscribe public void onAnimationChanged(AnimationChanged ev){ if(ev==null) return; Actor a=ev.getActor(); if(a==null) return; int anim; try{ anim=a.getAnimation(); }catch(Exception e){ return; } if(anim<=0) return; boolean self = a==client.getLocalPlayer(); boolean isTarget = a==currentTarget; if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logAnimation(self,isTarget,anim); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; KPWebhookPreset.TriggerType tt=r.getTriggerType(); if(tt== KPWebhookPreset.TriggerType.ANIMATION_SELF && self){ if(matchesAnim(r.getAnimationConfig(),anim)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); }
            else if(tt== KPWebhookPreset.TriggerType.ANIMATION_TARGET && isTarget){ if(matchesAnim(r.getAnimationConfig(),anim)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); }
            else if(tt== KPWebhookPreset.TriggerType.ANIMATION_ANY){ if(matchesAnim(r.getAnimationConfig(),anim)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); } }
    }

    @Subscribe public void onGraphicChanged(GraphicChanged ev){ if(ev==null) return; Actor a=ev.getActor(); if(a==null) return; int gid; try{ gid=a.getGraphic(); }catch(Exception e){ return; } if(gid<=0) return; boolean self = a==client.getLocalPlayer(); boolean isTarget = a==currentTarget; if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logGraphic(self,isTarget,gid); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; KPWebhookPreset.TriggerType tt=r.getTriggerType(); if(tt== KPWebhookPreset.TriggerType.GRAPHIC_SELF && self){ if(matchesGraphic(r.getGraphicConfig(),gid)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); }
            else if(tt== KPWebhookPreset.TriggerType.GRAPHIC_TARGET && isTarget){ if(matchesGraphic(r.getGraphicConfig(),gid)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); }
            else if(tt== KPWebhookPreset.TriggerType.GRAPHIC_ANY){ if(matchesGraphic(r.getGraphicConfig(),gid)) executeRule(r,null,-1,null,null,(a instanceof Player)?(Player)a:null,(a instanceof NPC)?(NPC)a:null); } }
    }

    @Subscribe public void onHitsplatApplied(HitsplatApplied ev){ if(ev==null) return; Actor target=ev.getActor(); if(target==null) return; int amount= ev.getHitsplat()!=null? ev.getHitsplat().getAmount():0; boolean self = target==client.getLocalPlayer(); boolean isTarget = target==currentTarget; if(debugWindow!=null && debugWindow.isVisible()){ try{ debugWindow.logHitsplat(self,amount, self? sanitizePlayerName(client.getLocalPlayer().getName()): (target instanceof Player? sanitizePlayerName(((Player)target).getName()): (target instanceof NPC? sanitizeNpcName(((NPC)target).getName()):""))); }catch(Exception ignored){} }
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.HITSPLAT_SELF && self){ if(matchesHitsplat(r.getHitsplatConfig(), amount)) executeRule(r,null,-1,null,null,(target instanceof Player)?(Player)target:null,(target instanceof NPC)?(NPC)target:null); }
            else if(r.getTriggerType()== KPWebhookPreset.TriggerType.HITSPLAT_TARGET && isTarget){ if(matchesHitsplat(r.getHitsplatConfig(), amount)) executeRule(r,null,-1,null,null,(target instanceof Player)?(Player)target:null,(target instanceof NPC)?(NPC)target:null); } }
    }
    private boolean matchesHitsplat(KPWebhookPreset.HitsplatConfig cfg,int val){ if(cfg==null) return true; KPWebhookPreset.HitsplatConfig.Mode m=cfg.getMode(); if(m==null) m= KPWebhookPreset.HitsplatConfig.Mode.GREATER; Integer ref=cfg.getValue(); switch(m){ case MAX: return true; case GREATER: return ref!=null && val>ref; case GREATER_EQUAL: return ref!=null && val>=ref; case EQUAL: return ref!=null && val==ref; case LESS_EQUAL: return ref!=null && val<=ref; case LESS: return ref!=null && val<ref; } return false; }

    // Helper: best-effort check whether a projectile originated from (or extremely near) the local player.
    private boolean projectileFromLocal(Projectile p, Actor targetActor){ Player local = client.getLocalPlayer(); if(p==null || local==null) return false; try {
            // Heuristic 1: If local is interacting with the projectile's target (and it's not us), we likely fired it.
            if(targetActor!=null && targetActor!=local){ try { if(local.getInteracting()==targetActor) return true; } catch(Exception ignored){} }
            // Heuristic 2: Proximity of projectile origin to local player's location (within ~1 tile) when not incoming
            if(targetActor!=local){ try {
                    LocalPoint lpLocal = local.getLocalLocation(); if(lpLocal!=null){ double dx=Math.abs(p.getX()-lpLocal.getX()); double dy=Math.abs(p.getY()-lpLocal.getY()); if(dx<=160 && dy<=160) return true; }
                } catch(Exception ignored){}
            }
        } catch(Exception ignored){}
        return false; }

    @Subscribe public void onProjectileMoved(ProjectileMoved ev){ if(ev==null) return; Projectile p=ev.getProjectile(); if(p==null) return; int pid=p.getId();
        int identity = System.identityHashCode(p); if(seenProjectileIdentities.contains(identity)) return; seenProjectileIdentities.add(identity); if(seenProjectileIdentities.size()>4000){ seenProjectileIdentities.clear(); seenProjectileIdentities.add(identity);} // prune
        Player local = client.getLocalPlayer(); Actor targetActor=null; try{ targetActor=p.getInteracting(); }catch(Exception ignored){}
        boolean incomingToLocal = targetActor!=null && targetActor==local; // projectile aimed at us
        // Improved self-shot heuristic
        boolean selfShot = projectileFromLocal(p, targetActor);
        // Determine shooter actor for display (who launched the projectile) (best-effort)
        Actor shooter = null; if(selfShot){ shooter = local; } else if(incomingToLocal){
            // Try find NPC/player whose interacting target is local
            try {
                List<Player> players = client.getPlayers(); if(players!=null){ for(Player pl : players){ if(pl!=null && pl!=local){ try { if(pl.getInteracting()==local){ shooter=pl; break; } } catch(Exception ignored){} } } }
                if(shooter==null){ /* iterate npcs to find one interacting with local */
                    try {
                        List<NPC> npcs = client.getNpcs();
                        if(npcs!=null){
                            for(NPC n: npcs){
                                if(n==null) continue;
                                try { if(n.getInteracting()==local){ shooter=n; break; } } catch(Exception ignored){}
                            }
                        }
                    } catch(Exception ignored){}
                }
            } catch(Exception ignored){}
        }
        // Debug logging: always show specific + ANY simultaneously (like animation/graphic) without duplicating ANY
        if(debugWindow!=null && debugWindow.isVisible()){
            try {
                boolean loggedAny=false;
                if(selfShot){ debugWindow.logProjectile("PROJECTILE_SELF", p, shooter!=null?shooter:targetActor); debugWindow.logProjectile("PROJECTILE_ANY", p, shooter!=null?shooter:targetActor); loggedAny=true; }
                if(incomingToLocal){ debugWindow.logProjectile("PROJECTILE_TARGET", p, shooter!=null?shooter: (selfShot? local : targetActor)); if(!loggedAny){ debugWindow.logProjectile("PROJECTILE_ANY", p, shooter!=null?shooter: (selfShot? local : targetActor)); loggedAny=true; } }
                if(!selfShot && !incomingToLocal){ debugWindow.logProjectile("PROJECTILE_ANY", p, shooter!=null?shooter: targetActor); }
            } catch(Exception ignored){}
        }
        // Trigger execution (unchanged except improved selfShot detection)
        for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; KPWebhookPreset.TriggerType tt=r.getTriggerType(); if(tt== KPWebhookPreset.TriggerType.PROJECTILE_SELF && selfShot){ if(matchesProjectile(r.getProjectileConfig(), pid)) executeRule(r,null,-1,null,null,null,null); } else if(tt== KPWebhookPreset.TriggerType.PROJECTILE_TARGET && incomingToLocal){ if(matchesProjectile(r.getProjectileConfig(), pid)) executeRule(r,null,-1,null,null,null,null); } else if(tt== KPWebhookPreset.TriggerType.PROJECTILE_ANY){ if(matchesProjectile(r.getProjectileConfig(), pid)) executeRule(r,null,-1,null,null,null,null); } }
    }

    @Subscribe public void onConfigChanged(ConfigChanged e){ if(e==null) return; if(!"kpwebhook".equals(e.getGroup())) return; }

    // Utility
    private String sanitizePlayerName(String n){ if(n==null) return ""; try { String nt= Text.removeTags(n); return nt.replace('\u00A0',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').trim(); } }
    private String sanitizeNpcName(String n){ if(n==null) return ""; try { String nt= Text.removeTags(n); return nt.replace('\u00A0',' ').trim(); } catch(Exception e){ return n.replace('\u00A0',' ').trim(); } }
    public String getDefaultWebhook(){ String def=config.defaultWebhookUrl(); return def!=null? def.trim():""; }

    // Expose current target actor for overlays
    public Actor getCurrentTargetActor(){ return currentTarget; }

    // === Debug windows & test helpers ===
    public void openDebugWindow(){ try { if(debugWindow==null) debugWindow=new KPWebhookDebugWindow(this); if(!debugWindow.isVisible()) debugWindow.setVisible(true); else debugWindow.toFront(); } catch(Exception ignored){} }
    public void openPresetDebugWindow(){ try { if(presetDebugWindow==null) presetDebugWindow=new KPWebhookPresetDebugWindow(); if(!presetDebugWindow.isVisible()) presetDebugWindow.setVisible(true); else presetDebugWindow.toFront(); } catch(Exception ignored){} }

    // Overhead helpers & sequence cleanup (restored)
    private void addOverheadTextFromPreset(String text,String pos,KPWebhookPreset rule){ if(text==null||text.isBlank()||rule==null) return; ActiveOverheadText ot=new ActiveOverheadText(); ot.text=text; ot.position=pos; ot.ruleId=rule.getId(); ot.color=Color.WHITE; ot.size=16; ot.remainingTicks=80; overheadTexts.add(ot);}
    public void addOverheadText(KPWebhookPreset rule,String text,String pos){ if(rule!=null){ addOverheadTextFromPreset(text,pos!=null?pos:"Above",rule); return;} ActiveOverheadText ot=new ActiveOverheadText(); ot.text=text; ot.position=pos!=null?pos:"Above"; ot.remainingTicks=80; overheadTexts.add(ot);}
    private void handleTargetedTextCommand(KPWebhookPreset rule,String line,Map<String,String> ctx){ java.util.regex.Matcher m; String raw=line.trim(); String pos=null; if((m=P_TEXT_OVER.matcher(raw)).find()) pos="Above"; else if((m=P_TEXT_UNDER.matcher(raw)).find()) pos="Under"; else if((m=P_TEXT_CENTER.matcher(raw)).find()) pos="Center"; else return; String content=m.group(1).trim(); if(content.isEmpty()) return; ActiveOverheadText.TargetType targetType= ActiveOverheadText.TargetType.LOCAL_PLAYER; List<String> tokens=new ArrayList<>(Arrays.asList(content.split("\\s+"))); if(!tokens.isEmpty()){ String first=tokens.get(0).toUpperCase(Locale.ROOT); if(first.equals("TARGET")){ targetType= ActiveOverheadText.TargetType.TARGET; tokens.remove(0);} } String finalText=expand(String.join(" ",tokens), ctx); if(finalText.isBlank()) return; ActiveOverheadText ot=new ActiveOverheadText(); ot.text=finalText; ot.position=pos; ot.ruleId=rule!=null?rule.getId():-1; ot.targetType=targetType; // style from rule if available
        if(rule!=null){ if(pos.equals("Above")){ applyStyleFromRuleOver(ot, rule); } else if(pos.equals("Under")){ applyStyleFromRuleUnder(ot, rule); } else if(pos.equals("Center")){ applyStyleFromRuleCenter(ot, rule); } }
        if(ot.color==null) ot.color=Color.WHITE; if(ot.size<=0) ot.size=16; if(!ot.persistent && ot.remainingTicks<=0) ot.remainingTicks=80; overheadTexts.add(ot); }
    private void handleImageCommand(KPWebhookPreset rule,String line,Map<String,String> ctx){ java.util.regex.Matcher m; String raw=line.trim(); String pos=null; if((m=P_IMG_OVER.matcher(raw)).find()) pos="Above"; else if((m=P_IMG_UNDER.matcher(raw)).find()) pos="Under"; else if((m=P_IMG_CENTER.matcher(raw)).find()) pos="Center"; else return; String rest=m.group(1).trim(); if(rest.isEmpty()) return; String[] parts=rest.split("\\s+"); int id; try{ id=Integer.parseInt(parts[0]); }catch(Exception e){ return; } boolean sprite=id<0; int abs=Math.abs(id); BufferedImage img=sprite? loadSprite(abs): loadItem(abs); if(img==null) return; ActiveOverheadImage oi=new ActiveOverheadImage(); oi.image=img; oi.sprite=sprite; oi.itemOrSpriteId=id; oi.position=pos; oi.ruleId=rule!=null?rule.getId():-1; oi.remainingTicks=80; overheadImages.add(oi); }
    private BufferedImage loadItem(int itemId){ try { return itemManager.getImage(itemId); } catch(Exception e){ return null;} }
    private BufferedImage loadSprite(int spriteId){ try { return spriteManager.getSprite(spriteId,0);} catch(Exception e){ return null;} }
    private void removeOverheadsForRule(int ruleId, boolean includePersistent){ overheadTexts.removeIf(t-> t!=null && t.ruleId==ruleId && (includePersistent || !t.persistent)); }
    private void removePersistentOverheadsForRule(int ruleId){ overheadTexts.removeIf(t-> t!=null && t.ruleId==ruleId && t.persistent); }
    private void cancelSequencesForRule(int ruleId){ activeSequences.removeIf(seq-> seq!=null && seq.rule!=null && seq.rule.getId()==ruleId); }

    // === Overhead text styling helpers (restored) ===
    private void applyPresetOverheadTexts(KPWebhookPreset rule, Map<String,String> ctx){ if(rule==null) return; try {
            if(rule.getTextOver()!=null && !rule.getTextOver().isBlank()){
                overheadTexts.removeIf(t-> t!=null && t.ruleId==rule.getId() && "Above".equals(t.position) && !t.persistent);
                String txt=expand(rule.getTextOver(), ctx); if(!txt.isBlank()){ ActiveOverheadText ot=new ActiveOverheadText(); ot.text=txt; ot.position="Above"; ot.ruleId=rule.getId(); applyStyleFromRuleOver(ot, rule); finalizeOverheadDefaults(ot); overheadTexts.add(ot);} }
            if(rule.getTextUnder()!=null && !rule.getTextUnder().isBlank()){
                overheadTexts.removeIf(t-> t!=null && t.ruleId==rule.getId() && "Under".equals(t.position) && !t.persistent);
                String txt=expand(rule.getTextUnder(), ctx); if(!txt.isBlank()){ ActiveOverheadText ot=new ActiveOverheadText(); ot.text=txt; ot.position="Under"; ot.ruleId=rule.getId(); applyStyleFromRuleUnder(ot, rule); finalizeOverheadDefaults(ot); overheadTexts.add(ot);} }
            if(rule.getTextCenter()!=null && !rule.getTextCenter().isBlank()){
                overheadTexts.removeIf(t-> t!=null && t.ruleId==rule.getId() && "Center".equals(t.position) && !t.persistent);
                String txt=expand(rule.getTextCenter(), ctx); if(!txt.isBlank()){ ActiveOverheadText ot=new ActiveOverheadText(); ot.text=txt; ot.position="Center"; ot.ruleId=rule.getId(); applyStyleFromRuleCenter(ot, rule); finalizeOverheadDefaults(ot); overheadTexts.add(ot);} }
        } catch(Exception ignored){} }
    private void finalizeOverheadDefaults(ActiveOverheadText ot){ if(ot==null) return; if(ot.color==null) ot.color=Color.WHITE; if(ot.size<=0) ot.size=16; if(!ot.persistent && ot.remainingTicks<=0) ot.remainingTicks=80; }
    private Color parseColor(String hex){ if(hex==null||hex.isBlank()) return Color.WHITE; String h=hex.trim(); try{ if(h.startsWith("#")) h=h.substring(1); if(h.length()==6){ int r=Integer.parseInt(h.substring(0,2),16); int g=Integer.parseInt(h.substring(2,4),16); int b=Integer.parseInt(h.substring(4,6),16); return new Color(r,g,b); } else if(h.length()==8){ int a=Integer.parseInt(h.substring(0,2),16); int r=Integer.parseInt(h.substring(2,4),16); int g=Integer.parseInt(h.substring(4,6),16); int b=Integer.parseInt(h.substring(6,8),16); return new Color(r,g,b,a); } }catch(Exception ignored){} return Color.WHITE; }
    private void applyStyleFromRuleOver(ActiveOverheadText ot, KPWebhookPreset rule){ if(ot==null||rule==null) return; ot.color=parseColor(rule.getTextOverColor()); ot.blink=Boolean.TRUE.equals(rule.getTextOverBlink()); ot.size=rule.getTextOverSize()!=null? rule.getTextOverSize():16; ot.remainingTicks=rule.getTextOverDuration()!=null? rule.getTextOverDuration():80; ot.bold=Boolean.TRUE.equals(rule.getTextOverBold()); ot.italic=Boolean.TRUE.equals(rule.getTextOverItalic()); ot.underline=Boolean.TRUE.equals(rule.getTextOverUnderline()); }
    private void applyStyleFromRuleUnder(ActiveOverheadText ot, KPWebhookPreset rule){ if(ot==null||rule==null) return; ot.color=parseColor(rule.getTextUnderColor()); ot.blink=Boolean.TRUE.equals(rule.getTextUnderBlink()); ot.size=rule.getTextUnderSize()!=null? rule.getTextUnderSize():16; ot.remainingTicks=rule.getTextUnderDuration()!=null? rule.getTextUnderDuration():80; ot.bold=Boolean.TRUE.equals(rule.getTextUnderBold()); ot.italic=Boolean.TRUE.equals(rule.getTextUnderItalic()); ot.underline=Boolean.TRUE.equals(rule.getTextUnderUnderline()); }
    private void applyStyleFromRuleCenter(ActiveOverheadText ot, KPWebhookPreset rule){ if(ot==null||rule==null) return; ot.color=parseColor(rule.getTextCenterColor()); ot.blink=Boolean.TRUE.equals(rule.getTextCenterBlink()); ot.size=rule.getTextCenterSize()!=null? rule.getTextCenterSize():16; ot.remainingTicks=rule.getTextCenterDuration()!=null? rule.getTextCenterDuration():80; ot.bold=Boolean.TRUE.equals(rule.getTextCenterBold()); ot.italic=Boolean.TRUE.equals(rule.getTextCenterItalic()); ot.underline=Boolean.TRUE.equals(rule.getTextCenterUnderline()); }

    // Screenshot & webhook helpers (restored simplified)
    private void captureAndSendSimpleScreenshot(String webhook, String message){ if(webhook==null||webhook.isBlank()) return; long now=System.currentTimeMillis(); if(now-lastScreenshotRequestMs<SCREENSHOT_COOLDOWN_MS) return; lastScreenshotRequestMs=now; BufferedImage img= lastFrame; if(img==null) return; sendImageToWebhook(img, webhook, message); }
    private void sendImageToWebhook(BufferedImage img,String webhook,String message){ executorService.execute(()->{ try(ByteArrayOutputStream baos=new ByteArrayOutputStream()){ ImageIO.write(img,"png",baos); byte[] png=baos.toByteArray(); MultipartBody.Builder mb=new MultipartBody.Builder().setType(MultipartBody.FORM); if(message!=null&&!message.isBlank()) mb.addFormDataPart("content", message); mb.addFormDataPart("file","screenshot.png", RequestBody.create(PNG,png)); Request request=new Request.Builder().url(webhook).post(mb.build()).build(); try(Response resp= okHttpClient.newCall(request).execute()){ if(!resp.isSuccessful()) log.warn("Screenshot webhook failed {}", resp.code()); } }catch(Exception e){ log.warn("Screenshot send fail", e);} }); }
    private boolean isMostlyBlack(BufferedImage img){ return false; } private boolean isMostlyWhite(BufferedImage img){ return false; }
    private void sendWebhookMessage(String webhook, String message){ if(webhook==null || webhook.isBlank()) return; executorService.execute(()->{ try { String json=gson.toJson(Map.of("content", message)); RequestBody body=RequestBody.create(JSON,json); Request req=new Request.Builder().url(webhook).post(body).build(); try(Response r= okHttpClient.newCall(req).execute()){ if(!r.isSuccessful()) log.warn("Webhook failed {}", r.code()); } } catch(Exception e){ log.warn("Webhook error", e);} }); }

    // Added: deactivate rule helper (missing earlier)
    private void deactivateRule(KPWebhookPreset r){
        if(r==null) return;
        boolean wasActive = r.isActive();
        if(wasActive) r.setActive(false);
        stopRule(r.getId());
        savePreset(r);
        try { if(panel!=null) panel.refreshTable(); } catch(Exception ignored){}
    }
    // Soft cancel: clear visuals & sequences but keep preset active
    private void softCancelOnChange(KPWebhookPreset r){ if(r==null) return; stopRule(r.getId()); /* stopRule does not flip active flag */ }
    private void ensureUniqueIds(){ Set<Integer> seen=new HashSet<>(); for(KPWebhookPreset r: rules){ if(r.getId()<0 || seen.contains(r.getId())){ r.setId(nextId++); savePreset(r); } seen.add(r.getId()); } }
}
