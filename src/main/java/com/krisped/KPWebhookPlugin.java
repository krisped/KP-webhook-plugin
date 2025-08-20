package com.krisped;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Provides;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
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

    @Override protected void startUp(){ panel = new KPWebhookPanel(this); navButton = NavigationButton.builder().tooltip("KP Webhook").icon(ImageUtil.loadImageResource(KPWebhookPlugin.class, "webhook.png")).priority(1).panel(panel).build(); clientToolbar.addNavigation(navButton); overlayManager.add(highlightOverlay); overlayManager.add(minimapHighlightOverlay); overlayManager.add(overlayTextOverlay); captureInitialRealLevels(); storage = new KPWebhookStorage(configManager, gson); rules.clear(); rules.addAll(storage.loadAll()); for (KPWebhookPreset r: rules) if (r.getId()>=nextId) nextId=r.getId()+1; panel.refreshTable(); }
    @Override protected void shutDown(){ overlayManager.remove(highlightOverlay); overlayManager.remove(minimapHighlightOverlay); overlayManager.remove(overlayTextOverlay); clientToolbar.removeNavigation(navButton); saveAllPresets(); rules.clear(); highlightManager.clear(); overheadTexts.clear(); overheadImages.clear(); if (infoboxCommandHandler!=null) infoboxCommandHandler.clearAll(); if (overlayTextManager!=null) overlayTextManager.clear(); if (debugWindow!=null){ try{debugWindow.dispose();}catch(Exception ignored){} debugWindow=null;} if (presetDebugWindow!=null){ try{presetDebugWindow.dispose();}catch(Exception ignored){} presetDebugWindow=null;} }

    private void captureInitialRealLevels(){ for (Skill s: Skill.values()){ try { lastRealLevel.put(s, client.getRealSkillLevel(s)); } catch(Exception ignored){} } }

    public List<KPWebhookPreset> getRules(){ return rules; }
    public void addOrUpdate(KPWebhookPreset p){ if(p==null) return; rules.removeIf(r->r.getId()==p.getId()); rules.add(p); savePreset(p);} public void deleteRule(int id){ KPWebhookPreset p=find(id); if(p!=null){ rules.remove(p); if (storage!=null) storage.delete(p);} }
    public void toggleActive(int id){ KPWebhookPreset p=find(id); if(p!=null){ p.setActive(!p.isActive()); savePreset(p);} }
    private KPWebhookPreset find(int id){ return rules.stream().filter(r->r.getId()==id).findFirst().orElse(null); }
    private void savePreset(KPWebhookPreset p){ if(storage!=null && p!=null) storage.save(p,null); }
    private void saveAllPresets(){ if(storage!=null) for(KPWebhookPreset p: rules) storage.save(p,null); }

    // Stop rule cleanup
    private void stopRule(int ruleId){ cancelSequencesForRule(ruleId); removeOverheadsForRule(ruleId,true); removePersistentOverheadsForRule(ruleId); try{highlightManager.removePersistentByRule(ruleId);}catch(Exception ignored){} overheadImages.removeIf(i->i!=null && i.ruleId==ruleId); try { overlayTextManager.removeByRule(ruleId);} catch(Exception ignored){} }

    // EXECUTION
    private void executeRule(KPWebhookPreset rule, Skill skill, int value, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg){ executeRule(rule,skill,value,statCfg,widgetCfg,null,null); }
    private void executeRule(KPWebhookPreset rule, Skill skill, int value, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg, Player other){ executeRule(rule,skill,value,statCfg,widgetCfg,other,null); }
    private void executeRule(KPWebhookPreset rule, Skill skill, int value, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg, Player otherPlayer, NPC npc){ if(rule==null) return; String cmds = rule.getCommands(); if(cmds==null || cmds.isBlank()) return; Map<String,String> ctx = baseContext(skill,value,widgetCfg,otherPlayer,npc); List<PendingCommand> list=new ArrayList<>(); List<String> original=new ArrayList<>(); for(String rawLine: cmds.split("\r?\n")){ String line=rawLine.trim(); if(line.isEmpty()|| line.startsWith("#")) continue; original.add(line); String up=line.toUpperCase(Locale.ROOT); if(up.startsWith("SLEEP ")){ long ms=0; try{ms=Long.parseLong(line.substring(6).trim());}catch(Exception ignored){} PendingCommand pc=new PendingCommand(); pc.type=PendingType.SLEEP_DELAY; pc.sleepMs=Math.max(0,ms); list.add(pc);} else if (up.equals("SLEEP")){} else if (up.startsWith("TICK")){ int t=1; String[] parts=line.split("\\s+"); if(parts.length>1){ try{ t=Integer.parseInt(parts[1]); }catch(Exception ignored){} } PendingCommand pc=new PendingCommand(); pc.type=PendingType.TICK_DELAY; pc.ticks=Math.max(1,t); list.add(pc);} else { PendingCommand pc=new PendingCommand(); pc.type=PendingType.ACTION; pc.line=line; list.add(pc);} }
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
    private void fireTargetChangeTriggers(Actor oldT, Actor newT){ if(oldT==newT) return; for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.TARGET) continue; executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig(), (newT instanceof Player)?(Player)newT:null, (newT instanceof NPC)?(NPC)newT:null); } }
    private void stopAllTargetPresets(){ for(KPWebhookPreset r: rules) if(r.getTriggerType()== KPWebhookPreset.TriggerType.TARGET) stopRule(r.getId()); }
    private String getCurrentTargetName(){ if(currentTarget==null) return ""; if(currentTarget instanceof Player) return sanitizePlayerName(((Player)currentTarget).getName()); if(currentTarget instanceof NPC) return sanitizeNpcName(((NPC)currentTarget).getName()); return ""; }

    // EVENT SUBSCRIBERS (subset)
    @Subscribe public void onVarbitChanged(VarbitChanged ev){ int id=ev.getVarbitId(); int val=ev.getValue(); for(KPWebhookPreset r: rules){ if(!r.isActive()) continue; if(r.getTriggerType()== KPWebhookPreset.TriggerType.VARBIT){ KPWebhookPreset.VarbitConfig cfg=r.getVarbitConfig(); if(cfg==null || cfg.getValue()==null) continue; if(matchesId(cfg.getVarbitId(), cfg.getVarbitIds(), id) && cfg.getValue()==val) executeRule(r,null,-1,null,null); } else if(r.getTriggerType()== KPWebhookPreset.TriggerType.VARPLAYER){ try { int varp=ev.getVarpId(); KPWebhookPreset.VarplayerConfig cfg=r.getVarplayerConfig(); if(cfg==null || cfg.getValue()==null) continue; if(matchesId(cfg.getVarplayerId(), cfg.getVarplayerIds(), varp)){ if(client.getVarpValue(varp)==cfg.getValue()) executeRule(r,null,-1,null,null); } } catch(Exception ignored){} } } }

    @Subscribe public void onStatChanged(StatChanged ev){ Skill skill=ev.getSkill(); int real=ev.getLevel(); Integer prev=lastRealLevel.get(skill); if(prev==null || real>prev){ lastRealLevel.put(skill,real); if(prev!=null) handleLevelUp(skill, real);} else lastRealLevel.put(skill,real); handleThreshold(skill, ev.getBoostedLevel()); }

    private void handleLevelUp(Skill skill, int newReal){ for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.STAT) continue; KPWebhookPreset.StatConfig cfg=r.getStatConfig(); if(cfg==null || cfg.getSkill()!=skill || cfg.getMode()!= KPWebhookPreset.StatMode.LEVEL_UP) continue; if(r.getLastSeenRealLevel()>=0 && newReal>r.getLastSeenRealLevel()) executeRule(r,skill,newReal,cfg,null); r.setLastSeenRealLevel(newReal); savePreset(r);} }
    private void handleThreshold(Skill skill, int boosted){ for(KPWebhookPreset r: rules){ if(!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.STAT) continue; KPWebhookPreset.StatConfig cfg=r.getStatConfig(); if(cfg==null || cfg.getSkill()!=skill) continue; if(cfg.getMode()== KPWebhookPreset.StatMode.LEVEL_UP) continue; boolean cond = cfg.getMode()== KPWebhookPreset.StatMode.ABOVE ? boosted>cfg.getThreshold(): boosted<cfg.getThreshold(); if(cond && !r.isLastConditionMet()){ removeOverheadsForRule(r.getId(), true); executeRule(r,skill,boosted,cfg,null); r.setLastConditionMet(true); r.setLastTriggeredBoosted(boosted); savePreset(r);} else if(cond && r.isLastConditionMet()){ if(boosted!=r.getLastTriggeredBoosted()){ removeOverheadsForRule(r.getId(),true); executeRule(r,skill,boosted,cfg,null); r.setLastTriggeredBoosted(boosted); savePreset(r);} } else if(!cond && r.isLastConditionMet()){ r.setLastConditionMet(false); r.setLastTriggeredBoosted(Integer.MIN_VALUE); removePersistentOverheadsForRule(r.getId()); try{ highlightManager.removePersistentByRule(r.getId()); }catch(Exception ignored){} removeOverheadsForRule(r.getId(),true); savePreset(r);} } }

    public void manualSend(int id){ KPWebhookPreset r=find(id); if(r!=null) executeRule(r,null,-1,r.getStatConfig(), r.getWidgetConfig()); }

    // COMMAND PROCESSOR
    private void processActionLine(KPWebhookPreset rule, String line, Map<String,String> ctx){ if(line==null|| line.isBlank()) return; String raw=line.trim(); String up=raw.toUpperCase(Locale.ROOT); try { if(up.equals("STOP")){ if(rule!=null) stopRule(rule.getId()); return;} if(up.startsWith("STOP ")){ String a=raw.substring(5).trim(); if(a.equalsIgnoreCase("ALL")){ for(KPWebhookPreset r: rules) stopRule(r.getId()); } else { try{ int rid=Integer.parseInt(a); stopRule(rid);}catch(Exception ignored){} } return;} if(up.startsWith("NOTIFY ")){ notifyRuneLite(expand(raw.substring(7).trim(), ctx)); return;} if(up.startsWith("CUSTOM_MESSAGE ")){ handleCustomMessage(raw.substring(14).trim(), ctx); return;} if(up.startsWith("WEBHOOK")){ String msg=raw.length()>7? raw.substring(7).trim():""; String webhook = (rule!=null && rule.getWebhookUrl()!=null && !rule.getWebhookUrl().isBlank())? rule.getWebhookUrl().trim(): getDefaultWebhook(); if(!webhook.isBlank()) sendWebhookMessage(webhook, expand(msg, ctx)); return;} if(up.startsWith("SCREENSHOT")){ String msg=raw.length()>10? raw.substring(10).trim():""; String webhook=(rule!=null && rule.getWebhookUrl()!=null && !rule.getWebhookUrl().isBlank())? rule.getWebhookUrl().trim(): getDefaultWebhook(); if(!webhook.isBlank()) captureAndSendSimpleScreenshot(webhook, expand(msg, ctx)); return;} if(up.startsWith("HIGHLIGHT_")){ if(highlightCommandHandler!=null && rule!=null) highlightCommandHandler.handle(raw, rule); return;} if(up.startsWith("OVERLAY_TEXT")){ if(overlayTextCommandHandler!=null && rule!=null) overlayTextCommandHandler.handle(raw, rule); return;} if(up.startsWith("TEXT_OVER")|| up.startsWith("TEXT_UNDER")|| up.startsWith("TEXT_CENTER")){ handleTargetedTextCommand(rule, raw, ctx); return;} if(up.startsWith("IMG_OVER")|| up.startsWith("IMG_UNDER")|| up.startsWith("IMG_CENTER")){ handleImageCommand(rule, raw, ctx); return;} } catch(Exception e){ log.debug("processActionLine fail: {} => {}", raw, e.toString()); } }

    private void handleCustomMessage(String remainder, Map<String,String> ctx){ if(remainder==null||remainder.isBlank()) return; String[] parts=remainder.split("\\s+",2); if(parts.length<2) return; ChatMessageType type=resolveChatMessageType(parts[0]); String msg=expand(parts[1], ctx); try{ client.addChatMessage(type,"", msg,null);}catch(Exception ignored){} }
    private ChatMessageType resolveChatMessageType(String token){ if(token==null) return ChatMessageType.GAMEMESSAGE; String up=token.trim().toUpperCase(Locale.ROOT); ChatMessageType t=CHAT_TYPE_ALIASES.get(up); if(t!=null) return t; t=CHAT_TYPE_ALIASES.get(normKey(up)); return t!=null? t: ChatMessageType.GAMEMESSAGE; }

    private void notifyRuneLite(String message){ if(message==null||message.isBlank()) return; try{ if(notifier!=null) notifier.notify(message);}catch(Exception ignored){} try{ client.addChatMessage(ChatMessageType.GAMEMESSAGE,"", message,null);}catch(Exception ignored){} }

    private String expand(String input, Map<String,String> ctx){ if(input==null||ctx==null||ctx.isEmpty()) return input; String out=input; for(Map.Entry<String,String> e: ctx.entrySet()){ String k=e.getKey(); String v=e.getValue()==null?"":e.getValue(); out=out.replace("${"+k+"}", v).replace("$"+k, v); } return out; }

    private boolean matchesId(Integer single,List<Integer> many,int value){ if(single!=null) return single==value; if(many!=null) for(Integer i: many) if(i!=null && i==value) return true; return false; }

    // Overhead helpers
    private void addOverheadTextFromPreset(String text,String pos,KPWebhookPreset rule){ if(text==null||text.isBlank()||rule==null) return; ActiveOverheadText ot=new ActiveOverheadText(); ot.text=text; ot.position=pos; ot.ruleId=rule.getId(); ot.color=Color.WHITE; ot.size=16; ot.remainingTicks=80; overheadTexts.add(ot);} public void addOverheadText(KPWebhookPreset rule,String text,String pos){ if(rule!=null){ addOverheadTextFromPreset(text,pos!=null?pos:"Above",rule); return;} ActiveOverheadText ot=new ActiveOverheadText(); ot.text=text; ot.position=pos!=null?pos:"Above"; ot.remainingTicks=80; overheadTexts.add(ot);}
    private void handleTargetedTextCommand(KPWebhookPreset rule,String line,Map<String,String> ctx){ java.util.regex.Matcher m; String raw=line.trim(); String pos=null; if((m=P_TEXT_OVER.matcher(raw)).find()) pos="Above"; else if((m=P_TEXT_UNDER.matcher(raw)).find()) pos="Under"; else if((m=P_TEXT_CENTER.matcher(raw)).find()) pos="Center"; else return; String content=m.group(1).trim(); if(content.isEmpty()) return; ActiveOverheadText.TargetType targetType= ActiveOverheadText.TargetType.LOCAL_PLAYER; Set<String> targetNames=null; Set<Integer> targetIds=null; List<String> tokens=new ArrayList<>(Arrays.asList(content.split("\\s+"))); if(!tokens.isEmpty()){ String first=tokens.get(0).toUpperCase(Locale.ROOT); if(first.equals("TARGET")){ targetType= ActiveOverheadText.TargetType.TARGET; tokens.remove(0);} }
        String finalText=expand(String.join(" ",tokens), ctx); if(finalText.isBlank()) return; ActiveOverheadText ot=new ActiveOverheadText(); ot.text=finalText; ot.position=pos; ot.ruleId=rule!=null?rule.getId():-1; ot.targetType=targetType; ot.remainingTicks=80; ot.color=Color.WHITE; overheadTexts.add(ot); }
    private void handleImageCommand(KPWebhookPreset rule,String line,Map<String,String> ctx){ java.util.regex.Matcher m; String raw=line.trim(); String pos=null; if((m=P_IMG_OVER.matcher(raw)).find()) pos="Above"; else if((m=P_IMG_UNDER.matcher(raw)).find()) pos="Under"; else if((m=P_IMG_CENTER.matcher(raw)).find()) pos="Center"; else return; String rest=m.group(1).trim(); if(rest.isEmpty()) return; String[] parts=rest.split("\\s+"); int id; try{ id=Integer.parseInt(parts[0]); }catch(Exception e){ return; } boolean sprite=id<0; int abs=Math.abs(id); BufferedImage img=sprite? loadSprite(abs): loadItem(abs); if(img==null) return; ActiveOverheadImage oi=new ActiveOverheadImage(); oi.image=img; oi.sprite=sprite; oi.itemOrSpriteId=id; oi.position=pos; oi.ruleId=rule!=null?rule.getId():-1; oi.remainingTicks=80; overheadImages.add(oi); }
    private BufferedImage loadItem(int itemId){ try { return itemManager.getImage(itemId); } catch(Exception e){ return null;} } private BufferedImage loadSprite(int spriteId){ try { return spriteManager.getSprite(spriteId,0);} catch(Exception e){ return null;} }
    private void removeOverheadsForRule(int ruleId, boolean includePersistent){ overheadTexts.removeIf(t-> t!=null && t.ruleId==ruleId && (includePersistent || !t.persistent)); }
    private void removePersistentOverheadsForRule(int ruleId){ overheadTexts.removeIf(t-> t!=null && t.ruleId==ruleId && t.persistent); }
    private void cancelSequencesForRule(int ruleId){ activeSequences.removeIf(seq-> seq!=null && seq.rule!=null && seq.rule.getId()==ruleId); }

    // Screenshot helpers (simplified)
    private void captureAndSendSimpleScreenshot(String webhook, String message){ if(webhook==null||webhook.isBlank()) return; long now=System.currentTimeMillis(); if(now-lastScreenshotRequestMs<SCREENSHOT_COOLDOWN_MS) return; lastScreenshotRequestMs=now; BufferedImage img= lastFrame; if(img==null) return; sendImageToWebhook(img, webhook, message); }
    private void sendImageToWebhook(BufferedImage img,String webhook,String message){ executorService.execute(()->{ try(ByteArrayOutputStream baos=new ByteArrayOutputStream()){ ImageIO.write(img,"png",baos); byte[] png=baos.toByteArray(); MultipartBody.Builder mb=new MultipartBody.Builder().setType(MultipartBody.FORM); if(message!=null&&!message.isBlank()) mb.addFormDataPart("content", message); mb.addFormDataPart("file","screenshot.png", RequestBody.create(PNG,png)); Request request=new Request.Builder().url(webhook).post(mb.build()).build(); try(Response resp= okHttpClient.newCall(request).execute()){ if(!resp.isSuccessful()) log.warn("Screenshot webhook failed {}", resp.code()); } }catch(Exception e){ log.warn("Screenshot send fail", e);} }); }
    private boolean isMostlyBlack(BufferedImage img){ return false; } private boolean isMostlyWhite(BufferedImage img){ return false; }

    private void sendWebhookMessage(String webhook, String message){ if(webhook==null || webhook.isBlank()) return; executorService.execute(()->{ try { String json=gson.toJson(Map.of("content", message)); RequestBody body=RequestBody.create(JSON,json); Request req=new Request.Builder().url(webhook).post(body).build(); try(Response r= okHttpClient.newCall(req).execute()){ if(!r.isSuccessful()) log.warn("Webhook failed {}", r.code()); } } catch(Exception e){ log.warn("Webhook error", e);} }); }

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
}
