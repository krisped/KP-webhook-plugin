package com.krisped;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Provides;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.util.Text;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import okhttp3.*;
import com.krisped.commands.highlight.HighlightManager;
import com.krisped.commands.highlight.HighlightCommandHandler;
import com.krisped.commands.highlight.HighlightOverlay;
import com.krisped.commands.highlight.MinimapHighlightOverlay;
import com.krisped.triggers.tick.TickTriggerService;
import com.krisped.commands.infobox.InfoboxCommandHandler; // retain handler
import com.krisped.commands.overlaytext.OverlayTextManager;
import com.krisped.commands.overlaytext.OverlayTextOverlay;
import com.krisped.commands.overlaytext.OverlayTextCommandHandler;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
        name = "KP Webhook",
        description = "Triggers: MANUAL, STAT, WIDGET, PLAYER_SPAWN, PLAYER_DESPAWN, NPC_SPAWN, NPC_DESPAWN, ANIMATION_SELF, ANIMATION_TARGET, ANIMATION_ANY, GRAPHIC_SELF, GRAPHIC_TARGET, GRAPHIC_ANY, HITSPLAT_SELF, HITSPLAT_TARGET, MESSAGE, VARBIT, VARPLAYER, TICK, TARGET. Commands: NOTIFY, WEBHOOK, SCREENSHOT, HIGHLIGHT_*, TEXT_*, OVERLAY_TEXT, SLEEP, TICK, STOP.",
        tags = {"webhook","stat","trigger","screenshot","widget","highlight","text","player","npc","varbit","varplayer","tick","overlay","target","graphic","hitsplat"}
)
public class KPWebhookPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar clientToolbar;
    @Inject private ScheduledExecutorService executorService;
    @Inject private OkHttpClient okHttpClient;
    @Inject private ConfigManager configManager;
    @Inject private KPWebhookConfig config;
    @Inject private OverlayManager overlayManager;
    @Inject private HighlightManager highlightManager;
    @Inject private HighlightCommandHandler highlightCommandHandler;
    @Inject private HighlightOverlay highlightOverlay; // below widgets
    @Inject private MinimapHighlightOverlay minimapHighlightOverlay; // minimap only
    @Inject private TickTriggerService tickTriggerService;
    @Inject private InfoboxCommandHandler infoboxCommandHandler; // unified handler
    @Inject private OverlayTextManager overlayTextManager;
    @Inject private OverlayTextOverlay overlayTextOverlay;
    @Inject private OverlayTextCommandHandler overlayTextCommandHandler;
    @Inject private ItemManager itemManager; // for IMG_* item icons
    @Inject private SpriteManager spriteManager; // for IMG_* sprite icons

    private KPWebhookPanel panel;
    private NavigationButton navButton;
    private KPWebhookStorage storage; // Storage for presets

    private final List<KPWebhookPreset> rules = new ArrayList<>();
    private int nextId = 0;
    private KPWebhookDebugWindow debugWindow; // debug chat/message window

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType PNG  = MediaType.parse("image/png");

    private final Map<Skill,Integer> lastRealLevel = new EnumMap<>(Skill.class);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private BufferedImage lastFrame; // Store the last captured frame for screenshots
    private volatile long lastScreenshotRequestMs = 0; // debounce to prevent double screenshot
    private static final long SCREENSHOT_COOLDOWN_MS = 800; // ms cooldown

    // === Target tracking state (added) ===
    private Actor currentTarget; // may be NPC or Player
    private int targetLastActiveTick = -1; // last tick we saw interaction/combat
    private int gameTickCounter = 0; // incremented each GameTick
    private int lastTargetAnimationId = -1; // last animation id for current target
    private int lastTargetGraphicId = -1; // new: last graphic id for current target
    private int lastLocalGraphicId = -1; // new: last local player graphic id
    private int lastLocalAnimationId = -1; // new: track local animation
    private static final int TARGET_RETENTION_TICKS = 50; // ~30s retention (50 * 0.6s)
    // === End target tracking state ===

    // Overhead text rendering state
    @Data
    public static class ActiveOverheadText {
        String text;
        Color color;
        boolean blink;
        int size;
        String position; // "Above", "Center", "Under"
        int remainingTicks;
        boolean visiblePhase;
        int blinkCounter;
        int blinkInterval;
        boolean bold;
        boolean italic;
        boolean underline;
        String key; // optional unique key for persistent (TICK) texts
        boolean persistent; // if true, do not decrement/remove automatically
        int ruleId = -1; // owning rule id for cleanup
        // Targeting support
        public enum TargetType { LOCAL_PLAYER, PLAYER_NAME, NPC_NAME, NPC_ID, TARGET } // added TARGET
        TargetType targetType = TargetType.LOCAL_PLAYER;
        java.util.Set<String> targetNames; // lowercase normalized (players or NPC names)
        java.util.Set<Integer> targetIds; // npc ids
    }
    private final java.util.List<ActiveOverheadText> overheadTexts = new ArrayList<>();

    public java.util.List<ActiveOverheadText> getOverheadTexts() { return overheadTexts; }

    // Overhead images rendering state
    @Data
    public static class ActiveOverheadImage {
        BufferedImage image;
        int itemOrSpriteId; // original id (negative means sprite)
        boolean sprite; // true if sprite
        String position; // Above / Center / Under
        int remainingTicks;
        boolean persistent;
        boolean blink; int blinkCounter; int blinkInterval = 2; boolean visiblePhase = true;
        int ruleId = -1;
        public enum TargetType { LOCAL_PLAYER, PLAYER_NAME, NPC_NAME, NPC_ID, TARGET }
        TargetType targetType = TargetType.LOCAL_PLAYER;
        java.util.Set<String> targetNames;
        java.util.Set<Integer> targetIds;
    }
    private final java.util.List<ActiveOverheadImage> overheadImages = new ArrayList<>();
    public java.util.List<ActiveOverheadImage> getOverheadImages() { return overheadImages; }

    // Command sequencing support
    private enum PendingType { ACTION, TICK_DELAY, SLEEP_DELAY }
    @Data
    private static class PendingCommand {
        PendingType type;
        String line; // original action line for ACTION
        int ticks;   // for TICK_DELAY
        long sleepMs; // for SLEEP_DELAY
    }
    @Data
    private static class CommandSequence {
        KPWebhookPreset rule;
        Map<String,String> ctx;
        List<PendingCommand> commands;
        int index;
        int tickDelayRemaining; // counts down each game tick
        long sleepUntilMillis;  // wall-clock time to resume
    }
    private final List<CommandSequence> activeSequences = new ArrayList<>();

    // Patterns - simplified to only support TEXT_UNDER
    private static final Pattern P_TEXT_UNDER = Pattern.compile("(?i)^TEXT_UNDER\\s+(.*)");
    private static final Pattern P_TEXT_OVER = Pattern.compile("(?i)^TEXT_OVER\\s+(.*)");
    private static final Pattern P_TEXT_CENTER = Pattern.compile("(?i)^TEXT_CENTER\\s+(.*)");
    // New image command patterns
    private static final Pattern P_IMG_OVER = Pattern.compile("(?i)^IMG_OVER\\s+(.+)");
    private static final Pattern P_IMG_UNDER = Pattern.compile("(?i)^IMG_UNDER\\s+(.+)");
    private static final Pattern P_IMG_CENTER = Pattern.compile("(?i)^IMG_CENTER\\s+(.+)");

    @Provides
    KPWebhookConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(KPWebhookConfig.class);
    }

    @Override
    protected void startUp()
    {
        panel = new KPWebhookPanel(this);
        navButton = NavigationButton.builder()
                .tooltip("KP Webhook")
                .icon(ImageUtil.loadImageResource(KPWebhookPlugin.class, "webhook.png"))
                .priority(1)
                .panel(panel)
                .build();
        clientToolbar.addNavigation(navButton);
        overlayManager.add(highlightOverlay);
        overlayManager.add(minimapHighlightOverlay);
        overlayManager.add(overlayTextOverlay); // register overlay text boxes

        captureInitialRealLevels();
        storage = new KPWebhookStorage(configManager, gson);
        rules.clear();
        rules.addAll(storage.loadAll());
        for (KPWebhookPreset r : rules)
            if (r.getId() >= nextId) nextId = r.getId() + 1;
        refreshPanelTable();
        log.info("KP Webhook started. {} presets loaded. Default webhook set? {}",
                rules.size(),
                (config.defaultWebhookUrl()!=null && !config.defaultWebhookUrl().isBlank()));
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(highlightOverlay);
        overlayManager.remove(minimapHighlightOverlay);
        overlayManager.remove(overlayTextOverlay);
        clientToolbar.removeNavigation(navButton);
        saveAllPresets();
        rules.clear();
        panel = null;
        highlightManager.clear();
        overheadTexts.clear();
        overheadImages.clear();
        if (infoboxCommandHandler != null) infoboxCommandHandler.clearAll(); // clear native infoboxes (if any used earlier)
        if (overlayTextManager != null) overlayTextManager.clear();
        if (debugWindow != null) { try { debugWindow.dispose(); } catch (Exception ignored) {} debugWindow = null; }
    }

    public String getDefaultWebhook()
    {
        String def = config.defaultWebhookUrl();
        return def != null ? def.trim() : "";
    }

    private void captureInitialRealLevels()
    {
        for (Skill skill : Skill.values())
        {
            try { lastRealLevel.put(skill, client.getRealSkillLevel(skill)); } catch (Exception ignored) {}
        }
    }

    /* ================= Game Tick ================= */
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        gameTickCounter++;
        updateAndProcessTarget();
        // Self animation change detection
        try {
            Player local = client.getLocalPlayer();
            if (local != null) {
                int anim = local.getAnimation();
                if (anim != lastLocalAnimationId) {
                    lastLocalAnimationId = anim;
                    if (debugWindow != null && debugWindow.isVisible()) {
                        try { debugWindow.logAnimationActor("ANIMATION_SELF", local, anim); debugWindow.logAnimationActor("ANIMATION_ANY", local, anim); } catch (Exception ignored) {}
                    }
                    fireSelfAnimationTriggers(anim);
                    // ANY handled in AnimationChanged event now
                }
            } else {
                lastLocalAnimationId = -1;
            }
        } catch (Exception ignored) {}
        // Self graphic change detection
        try {
            Player local = client.getLocalPlayer();
            if (local != null) {
                int g = local.getGraphic();
                if (g != lastLocalGraphicId) {
                    lastLocalGraphicId = g;
                    if (debugWindow != null && debugWindow.isVisible()) {
                        try { debugWindow.logGraphicActor("GRAPHIC_SELF", local, g); debugWindow.logGraphicActor("GRAPHIC_ANY", local, g); } catch (Exception ignored) {}
                    }
                    fireSelfGraphicTriggers(g);
                    // ANY handled in GraphicChanged event now
                }
            } else {
                lastLocalGraphicId = -1;
            }
        } catch (Exception ignored) {}

        // Continuous TICK trigger processing via service
        if (tickTriggerService != null)
        {
            tickTriggerService.process(rules, overheadTexts);
        }

        // Process sequences (delays and actions)
        if (!activeSequences.isEmpty())
        {
            long now = System.currentTimeMillis();
            Iterator<CommandSequence> seqIt = activeSequences.iterator();
            while (seqIt.hasNext())
            {
                CommandSequence seq = seqIt.next();
                // Handle tick delay
                if (seq.tickDelayRemaining > 0)
                {
                    seq.tickDelayRemaining--;
                    continue;
                }
                // Handle sleep delay
                if (seq.sleepUntilMillis > 0 && now < seq.sleepUntilMillis)
                {
                    continue;
                }
                // Execute as many immediate ACTION commands as possible until a delay or end
                int safety = 0;
                while (seq.index < seq.commands.size() && safety < 1000) // safety loop guard
                {
                    PendingCommand pc = seq.commands.get(seq.index);
                    if (pc.type == PendingType.TICK_DELAY)
                    {
                        seq.tickDelayRemaining = Math.max(1, pc.ticks);
                        seq.index++;
                        break; // wait for next tick(s)
                    }
                    else if (pc.type == PendingType.SLEEP_DELAY)
                    {
                        seq.sleepUntilMillis = now + Math.max(0, pc.sleepMs);
                        seq.index++;
                        break; // wait until time passes
                    }
                    else // ACTION
                    {
                        processActionLine(seq.rule, pc.line, seq.ctx);
                        seq.index++;
                    }
                    safety++;
                }
                if (seq.index >= seq.commands.size())
                {
                    seqIt.remove();
                }
            }
        }

        // Replace manual highlight tick processing with manager
        highlightManager.tick();
        overlayTextManager.tick();
        if (infoboxCommandHandler != null) infoboxCommandHandler.tick(); // tick INFOBOX icons

        if (!overheadTexts.isEmpty())
        {
            Iterator<ActiveOverheadText> it = overheadTexts.iterator();
            while (it.hasNext())
            {
                ActiveOverheadText t = it.next();
                if (t.blink)
                {
                    t.blinkCounter++;
                    if (t.blinkCounter % Math.max(1, t.blinkInterval) == 0) {
                        t.visiblePhase = !t.visiblePhase;
                    }
                }
                else
                {
                    t.visiblePhase = true;
                }
                if (!t.persistent) // only countdown non-persistent
                {
                    t.remainingTicks--;
                    if (t.remainingTicks <= 0)
                    {
                        it.remove();
                    }
                }
            }
        }
        // Process overhead images (blink & duration)
        if (!overheadImages.isEmpty()) {
            java.util.Iterator<ActiveOverheadImage> ii = overheadImages.iterator();
            while (ii.hasNext()) {
                ActiveOverheadImage img = ii.next();
                if (img.blink) {
                    img.blinkCounter++; if (img.blinkCounter % Math.max(1,img.blinkInterval)==0) img.visiblePhase = !img.visiblePhase;
                } else { img.visiblePhase = true; }
                if (!img.persistent) { img.remainingTicks--; if (img.remainingTicks <= 0) ii.remove(); }
            }
        }

        // Schedule capture of current frame on EDT (after game state updated this tick)
        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    Canvas canvas = client.getCanvas();
                    if (canvas == null) return;
                    int w = canvas.getWidth();
                    int h = canvas.getHeight();
                    if (w <= 0 || h <= 0) return;
                    // Use TYPE_INT_RGB to avoid transparent -> white compression artifact
                    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2 = img.createGraphics();
                    g2.setComposite(AlphaComposite.SrcOver);
                    // paint() instead of printAll() for heavyweight Canvas using BufferStrategy
                    try {
                        canvas.paint(g2);
                    } catch (Exception ignored) {}
                    g2.dispose();
                    // Only publish if not mostly white/black (so next fallback can try Robot etc.)
                    if (!isMostlyBlack(img) && !isMostlyWhite(img)) {
                        lastFrame = img; // publish valid frame
                    }
                } catch (Exception e) {
                    // ignore per tick
                }
            });
        } catch (Exception ignored) {}
    }

    private void updateAndProcessTarget() {
        try {
            Player local = client.getLocalPlayer();
            Actor interacting = null;
            if (local != null) {
                Actor raw = local.getInteracting();
                if (raw != null && isAttackable(raw)) {
                    interacting = raw; // only accept attackable entities
                }
            }
            boolean targetExpired = false;
            if (currentTarget != null && (gameTickCounter - targetLastActiveTick) > TARGET_RETENTION_TICKS) {
                targetExpired = true;
            }
            boolean targetChanged = false;
            if (interacting != null) {
                if (currentTarget == null || currentTarget != interacting) {
                    targetChanged = true;
                }
            } else if (targetExpired && currentTarget != null) {
                targetChanged = true; // will clear due to expiry
            }
            if (targetChanged) {
                Actor old = currentTarget;
                if (targetExpired || interacting == null) {
                    // Timeout / lost target -> stop TARGET trigger presets before clearing
                    if (targetExpired) {
                        stopAllTargetPresets();
                    }
                    currentTarget = null;
                    lastTargetAnimationId = -1;
                    lastTargetGraphicId = -1; // reset graphic
                } else {
                    currentTarget = interacting;
                    lastTargetAnimationId = currentTarget.getAnimation();
                    try { lastTargetGraphicId = currentTarget.getGraphic(); } catch (Exception ignored) { lastTargetGraphicId = -1; }
                    targetLastActiveTick = gameTickCounter; // refresh on new acquisition
                }
                // Fire change trigger only when new target acquired or old explicitly lost (we still allow existing behavior)
                fireTargetChangeTriggers(old, currentTarget);
            } else if (interacting != null) {
                // still interacting with attackable entity -> refresh activity timer
                targetLastActiveTick = gameTickCounter;
            }
            // Animation change for target
            if (currentTarget != null) {
                int anim = currentTarget.getAnimation();
                if (anim != lastTargetAnimationId) {
                    lastTargetAnimationId = anim;
                    if (debugWindow != null && debugWindow.isVisible()) {
                        try { debugWindow.logAnimationActor("ANIMATION_TARGET", currentTarget, anim); debugWindow.logAnimationActor("ANIMATION_ANY", currentTarget, anim); } catch (Exception ignored) {}
                    }
                    fireTargetAnimationTriggers(anim);
                    // ANY handled globally
                }
                try {
                    int g = currentTarget.getGraphic();
                    if (g != lastTargetGraphicId) {
                        lastTargetGraphicId = g;
                        if (debugWindow != null && debugWindow.isVisible()) {
                            try { debugWindow.logGraphicActor("GRAPHIC_TARGET", currentTarget, g); debugWindow.logGraphicActor("GRAPHIC_ANY", currentTarget, g); } catch (Exception ignored) {}
                        }
                        fireTargetGraphicTriggers(g);
                        // ANY handled globally
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void fireSelfAnimationTriggers(int newAnim) {
        for (KPWebhookPreset r : getRules()) {
            if (!r.isActive() || r.getTriggerType() != KPWebhookPreset.TriggerType.ANIMATION_SELF) continue;
            KPWebhookPreset.AnimationConfig cfg = r.getAnimationConfig();
            if (cfg == null || cfg.getAnimationId() == null) continue;
            if (cfg.getAnimationId() == newAnim) {
                executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig());
            }
        }
    }
    private void fireAnyAnimationTriggers(int newAnim, boolean self, boolean target) {
        if (!self && !target) return;
        for (KPWebhookPreset r : getRules()) {
            if (!r.isActive() || r.getTriggerType() != KPWebhookPreset.TriggerType.ANIMATION_ANY) continue;
            executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), (target && currentTarget instanceof Player)?(Player)currentTarget:null, (target && currentTarget instanceof NPC)?(NPC)currentTarget:null);
        }
    }
    private void fireAnyGraphicTriggers(int newGraphic, boolean self, boolean target) {
        if (!self && !target) return;
        for (KPWebhookPreset r : getRules()) {
            if (!r.isActive() || r.getTriggerType() != KPWebhookPreset.TriggerType.GRAPHIC_ANY) continue;
            executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), (target && currentTarget instanceof Player)?(Player)currentTarget:null, (target && currentTarget instanceof NPC)?(NPC)currentTarget:null);
        }
    }

    // === Added missing helper methods for graphics / target handling ===
    private void fireSelfGraphicTriggers(int newGraphic) {
        for (KPWebhookPreset r : getRules()) {
            if (!r.isActive() || r.getTriggerType() != KPWebhookPreset.TriggerType.GRAPHIC_SELF) continue;
            KPWebhookPreset.GraphicConfig cfg = r.getGraphicConfig();
            if (cfg == null || cfg.getGraphicId() == null) continue;
            if (cfg.getGraphicId() == newGraphic) {
                executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig());
            }
        }
    }
    private void fireTargetAnimationTriggers(int newAnim) {
        for (KPWebhookPreset r : getRules()) {
            if (!r.isActive() || r.getTriggerType() != KPWebhookPreset.TriggerType.ANIMATION_TARGET) continue;
            KPWebhookPreset.AnimationConfig cfg = r.getAnimationConfig();
            if (cfg == null || cfg.getAnimationId() == null) continue;
            if (cfg.getAnimationId() == newAnim) {
                executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), (currentTarget instanceof Player)?(Player)currentTarget:null, (currentTarget instanceof NPC)?(NPC)currentTarget:null);
            }
        }
    }
    private void fireTargetGraphicTriggers(int newGraphic) {
        for (KPWebhookPreset r : getRules()) {
            if (!r.isActive() || r.getTriggerType() != KPWebhookPreset.TriggerType.GRAPHIC_TARGET) continue;
            KPWebhookPreset.GraphicConfig cfg = r.getGraphicConfig();
            if (cfg == null || cfg.getGraphicId() == null) continue;
            if (cfg.getGraphicId() == newGraphic) {
                executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), (currentTarget instanceof Player)?(Player)currentTarget:null, (currentTarget instanceof NPC)?(NPC)currentTarget:null);
            }
        }
    }
    private void fireTargetChangeTriggers(Actor oldTarget, Actor newTarget) {
        if (oldTarget == newTarget) return; // no change
        for (KPWebhookPreset r : getRules()) {
            if (!r.isActive() || r.getTriggerType() != KPWebhookPreset.TriggerType.TARGET) continue;
            executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), (newTarget instanceof Player)?(Player)newTarget:null, (newTarget instanceof NPC)?(NPC)newTarget:null);
        }
    }
    private boolean isAttackable(Actor a) {
        if (a == null) return false;
        if (a instanceof NPC) return true; // treat all NPCs as attackable for simplicity
        if (a instanceof Player) return a != client.getLocalPlayer();
        return false;
    }
    private void stopAllTargetPresets() {
        for (KPWebhookPreset r : getRules()) {
            if (r.getTriggerType() == KPWebhookPreset.TriggerType.TARGET) {
                try { stopRule(r.getId()); } catch (Exception ignored) {}
            }
        }
    }
    private String getCurrentTargetName() {
        if (currentTarget == null) return "";
        if (currentTarget instanceof Player) return sanitizePlayerName(((Player)currentTarget).getName());
        if (currentTarget instanceof NPC) return sanitizeNpcName(((NPC)currentTarget).getName());
        return "";
    }
    // === End added helpers ===

    // === New global animation/graphic event subscribers ===
    @Subscribe
    public void onAnimationChanged(AnimationChanged ev) {
        try {
            Actor a = ev.getActor(); if (a == null) return;
            int anim = a.getAnimation();
            // Skip if self/target already processed in tick? still log for completeness
            if (debugWindow != null && debugWindow.isVisible()) {
                debugWindow.logAnimationActor("ANIMATION_ANY", a, anim);
                if (a == client.getLocalPlayer()) debugWindow.logAnimationActor("ANIMATION_SELF", a, anim);
                else if (a == currentTarget) debugWindow.logAnimationActor("ANIMATION_TARGET", a, anim);
            }
            // Fire ANY presets
            for (KPWebhookPreset r : getRules()) {
                if (!r.isActive()) continue;
                if (r.getTriggerType() == KPWebhookPreset.TriggerType.ANIMATION_ANY) {
                    if (a instanceof Player) executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), (Player)a, null);
                    else if (a instanceof NPC) executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), null, (NPC)a);
                    else executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig());
                } else if (r.getTriggerType() == KPWebhookPreset.TriggerType.ANIMATION_SELF && a == client.getLocalPlayer()) {
                    KPWebhookPreset.AnimationConfig cfg = r.getAnimationConfig(); if (cfg!=null && cfg.getAnimationId()!=null && cfg.getAnimationId()==anim) executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig());
                } else if (r.getTriggerType() == KPWebhookPreset.TriggerType.ANIMATION_TARGET && a == currentTarget) {
                    KPWebhookPreset.AnimationConfig cfg = r.getAnimationConfig(); if (cfg!=null && cfg.getAnimationId()!=null && cfg.getAnimationId()==anim) {
                        if (a instanceof Player) executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), (Player)a, null); else if (a instanceof NPC) executeRule(r,null,-1,r.getStatConfig(),r.getWidgetConfig(),null,(NPC)a); else executeRule(r,null,-1,r.getStatConfig(),r.getWidgetConfig());
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @Subscribe
    public void onGraphicChanged(GraphicChanged ev) {
        try {
            Actor a = ev.getActor(); if (a == null) return;
            int g = a.getGraphic();
            if (debugWindow != null && debugWindow.isVisible()) {
                debugWindow.logGraphicActor("GRAPHIC_ANY", a, g);
                if (a == client.getLocalPlayer()) debugWindow.logGraphicActor("GRAPHIC_SELF", a, g);
                else if (a == currentTarget) debugWindow.logGraphicActor("GRAPHIC_TARGET", a, g);
            }
            for (KPWebhookPreset r : getRules()) {
                if (!r.isActive()) continue;
                if (r.getTriggerType() == KPWebhookPreset.TriggerType.GRAPHIC_ANY) {
                    if (a instanceof Player) executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), (Player)a, null);
                    else if (a instanceof NPC) executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), null, (NPC)a);
                    else executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig());
                } else if (r.getTriggerType() == KPWebhookPreset.TriggerType.GRAPHIC_SELF && a == client.getLocalPlayer()) {
                    KPWebhookPreset.GraphicConfig cfg = r.getGraphicConfig(); if (cfg!=null && cfg.getGraphicId()!=null && cfg.getGraphicId()==g) executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig());
                } else if (r.getTriggerType() == KPWebhookPreset.TriggerType.GRAPHIC_TARGET && a == currentTarget) {
                    KPWebhookPreset.GraphicConfig cfg = r.getGraphicConfig(); if (cfg!=null && cfg.getGraphicId()!=null && cfg.getGraphicId()==g) {
                        if (a instanceof Player) executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig(), (Player)a, null); else if (a instanceof NPC) executeRule(r,null,-1,r.getStatConfig(),r.getWidgetConfig(),null,(NPC)a); else executeRule(r,null,-1,r.getStatConfig(),r.getWidgetConfig());
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    // === End global subscribers ===


    @Subscribe
    public void onVarbitChanged(VarbitChanged ev)
    {
        int varbitId = ev.getVarbitId();
        int newValue = ev.getValue();
        if (debugWindow != null && debugWindow.isVisible()) {
            try { debugWindow.logVarbit(varbitId, newValue); } catch (Exception ignored) {}
            try {
                int varpId = ev.getVarpId();
                if (varpId >= 0) { debugWindow.logVarplayer(varpId, client.getVarpValue(varpId)); }
            } catch (Exception ignored) {}
        }
        for (KPWebhookPreset r : getRules())
        {
            if (!r.isActive()) continue;
            if (r.getTriggerType() == KPWebhookPreset.TriggerType.VARBIT) {
                KPWebhookPreset.VarbitConfig cfg = r.getVarbitConfig();
                if (cfg == null || cfg.getVarbitId() == null || cfg.getValue() == null) continue;
                if (cfg.getVarbitId() == varbitId && cfg.getValue() == newValue) {
                    executeRule(r, null, -1, null, null, null);
                }
            } else if (r.getTriggerType() == KPWebhookPreset.TriggerType.VARPLAYER) {
                try {
                    int varpId = ev.getVarpId();
                    KPWebhookPreset.VarplayerConfig cfg = r.getVarplayerConfig();
                    if (cfg == null || cfg.getVarplayerId() == null || cfg.getValue() == null) continue;
                    if (varpId == cfg.getVarplayerId()) {
                        int val = client.getVarpValue(varpId);
                        if (val == cfg.getValue()) {
                            executeRule(r, null, -1, null, null, null);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged ev)
    {
        Skill skill = ev.getSkill();
        int real = ev.getLevel();
        Integer prev = lastRealLevel.get(skill);
        if (debugWindow != null && debugWindow.isVisible()) {
            try { debugWindow.logStat(skill.name(), real, ev.getBoostedLevel()); } catch (Exception ignored) {}
        }
        if (prev==null || real>prev)
        {
            lastRealLevel.put(skill, real);
            if (prev!=null) handleLevelUp(skill, real);
        }
        else
        {
            lastRealLevel.put(skill, real);
        }
        handleThreshold(skill, ev.getBoostedLevel());
        // If threshold already satisfied and stat changed while still inside condition, refresh persistent visuals
        if (ev.getSkill() != null) {
            refreshPersistentStatVisuals(ev.getSkill(), ev.getBoostedLevel());
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded ev)
    {
        int group = ev.getGroupId();
        if (debugWindow != null && debugWindow.isVisible()) {
            try { debugWindow.logWidget(group, null); } catch (Exception ignored) {}
        }
        for (KPWebhookPreset r : getRules())
        {
            if (!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.WIDGET) continue;
            KPWebhookPreset.WidgetConfig cfg = r.getWidgetConfig();
            if (cfg==null || cfg.getGroupId()!=group) continue;
            if (cfg.getChildId()!=null)
            {
                Widget w = client.getWidget(group, cfg.getChildId());
                if (w==null) continue;
            }
            executeRule(r, null, -1, r.getStatConfig(), cfg); // legacy call
        }
    }

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned ev)
    {
        Player p = ev.getPlayer(); if (p==null) return;
        if (debugWindow != null && debugWindow.isVisible()) {
            try { debugWindow.logPlayerSpawn(false, sanitizePlayerName(p.getName()), p.getCombatLevel()); } catch (Exception ignored) {}
        }
        for (KPWebhookPreset r : getRules())
        {
            if (!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.PLAYER_SPAWN) continue;
            KPWebhookPreset.PlayerConfig cfg = r.getPlayerConfig(); if (cfg==null) continue;
            if (matchesPlayer(cfg, p)) executeRule(r, null, -1, null, null, p);
        }
    }

    @Subscribe
    public void onPlayerDespawned(PlayerDespawned ev)
    {
        Player p = ev.getPlayer(); if (p==null) return;
        if (debugWindow != null && debugWindow.isVisible()) {
            try { debugWindow.logPlayerSpawn(true, sanitizePlayerName(p.getName()), p.getCombatLevel()); } catch (Exception ignored) {}
        }
        for (KPWebhookPreset r : getRules())
        {
            if (!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.PLAYER_DESPAWN) continue;
            KPWebhookPreset.PlayerConfig cfg = r.getPlayerConfig(); if (cfg==null) continue;
            if (matchesPlayer(cfg, p)) executeRule(r, null, -1, null, null, p);
        }
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned ev) {
        NPC npc = ev.getNpc(); if (npc==null) return;
        if (debugWindow != null && debugWindow.isVisible()) {
            try { debugWindow.logNpcSpawn(false, sanitizeNpcName(npc.getName()), npc.getId(), npc.getCombatLevel()); } catch (Exception ignored) {}
        }
        for (KPWebhookPreset r : getRules()) {
            if (!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.NPC_SPAWN) continue;
            KPWebhookPreset.NpcConfig cfg = r.getNpcConfig(); if (!matchesNpc(cfg, npc)) continue;
            executeRule(r, null, -1, null, null, null, npc);
        }
    }
    @Subscribe
    public void onNpcDespawned(NpcDespawned ev) {
        NPC npc = ev.getNpc(); if (npc==null) return;
        if (debugWindow != null && debugWindow.isVisible()) {
            try { debugWindow.logNpcSpawn(true, sanitizeNpcName(npc.getName()), npc.getId(), npc.getCombatLevel()); } catch (Exception ignored) {}
        }
        for (KPWebhookPreset r : getRules()) {
            if (!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.NPC_DESPAWN) continue;
            KPWebhookPreset.NpcConfig cfg = r.getNpcConfig(); if (!matchesNpc(cfg, npc)) continue;
            executeRule(r, null, -1, null, null, null, npc);
        }
    }


    // ===== Restored helper methods for STAT / PLAYER triggers and execution =====
    private boolean matchesPlayer(KPWebhookPreset.PlayerConfig cfg, Player p)
    {
        if (cfg == null || p == null) return false;
        if (cfg.isAll()) return true;
        if (cfg.getName()!=null && !cfg.getName().isBlank())
        {
            String target = sanitizePlayerName(cfg.getName()).trim();
            String actual = sanitizePlayerName(p.getName()).trim();
            if (actual.equalsIgnoreCase(target)) return true;
        }
        if (cfg.getCombatRange()!=null)
        {
            Player local = client.getLocalPlayer();
            if (local == null) return false;
            int range = Math.max(0, cfg.getCombatRange());
            int base = local.getCombatLevel();
            int cl = p.getCombatLevel();
            return cl >= base - range && cl <= base + range;
        }
        return false;
    }

    private void handleLevelUp(Skill skill, int newReal)
    {
        for (KPWebhookPreset r : getRules())
        {
            if (!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.STAT) continue;
            KPWebhookPreset.StatConfig cfg = r.getStatConfig();
            if (cfg==null || cfg.getSkill()!=skill || cfg.getMode()!= KPWebhookPreset.StatMode.LEVEL_UP) continue;
            if (r.getLastSeenRealLevel() >=0 && newReal > r.getLastSeenRealLevel())
            {
                executeRule(r, skill, newReal, cfg, null);
            }
            r.setLastSeenRealLevel(newReal);
            savePreset(r);
        }
    }

    private void handleThreshold(Skill skill, int boostedValue)
    {
        for (KPWebhookPreset r : getRules())
        {
            if (!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.STAT) continue;
            KPWebhookPreset.StatConfig cfg = r.getStatConfig();
            if (cfg==null || cfg.getSkill()!=skill) continue;
            if (cfg.getMode()== KPWebhookPreset.StatMode.LEVEL_UP) continue; // handled separately
            boolean condition = cfg.getMode()== KPWebhookPreset.StatMode.ABOVE ? boostedValue > cfg.getThreshold() : boostedValue < cfg.getThreshold();
            if (condition && !r.isLastConditionMet())
            {
                // First enter: fire
                removeOverheadsForRule(r.getId(), true); // clear any previous visuals for fresh start
                executeRule(r, skill, boostedValue, cfg, null);
                r.setLastConditionMet(true);
                r.setLastTriggeredBoosted(boostedValue);
                savePreset(r);
            }
            else if (condition && r.isLastConditionMet())
            {
                // Already inside condition window; re-trigger if boosted value changed
                if (boostedValue != r.getLastTriggeredBoosted())
                {
                    removeOverheadsForRule(r.getId(), true);
                    executeRule(r, skill, boostedValue, cfg, null);
                    r.setLastTriggeredBoosted(boostedValue);
                    savePreset(r);
                }
            }
            else if (!condition && r.isLastConditionMet())
            {
                r.setLastConditionMet(false);
                r.setLastTriggeredBoosted(Integer.MIN_VALUE);
                removePersistentOverheadsForRule(r.getId());
                try { highlightManager.removePersistentByRule(r.getId()); } catch (Exception ignored) {}
                removeOverheadsForRule(r.getId(), true); // also clear any lingering ephemeral texts
                savePreset(r);
            }
        }
    }

    public void manualSend(int id)
    {
        KPWebhookPreset r = find(id);
        if (r!=null)
        {
            executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig());
        }
    }

    private String resolveWebhook(KPWebhookPreset rule)
    {
        if (rule == null) return null;
        if (rule.isUseDefaultWebhook())
        {
            String def = getDefaultWebhook();
            if (!def.isBlank()) return def;
        }
        if (rule.getWebhookUrl()!=null && !rule.getWebhookUrl().isBlank()) return rule.getWebhookUrl().trim();
        return null;
    }
    private boolean webhookAvailable(KPWebhookPreset rule) { return resolveWebhook(rule) != null; }

    private void executeRule(KPWebhookPreset rule, Skill stat, int current, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg)
    { executeRule(rule, stat, current, statCfg, widgetCfg, null, null); }
    private void executeRule(KPWebhookPreset rule, Skill stat, int current, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg, Player otherPlayer)
    { executeRule(rule, stat, current, statCfg, widgetCfg, otherPlayer, null); }
    private void executeRule(KPWebhookPreset rule, Skill stat, int current, KPWebhookPreset.StatConfig statCfg, KPWebhookPreset.WidgetConfig widgetCfg, Player otherPlayer, NPC otherNpc)
    {
        if (rule == null) return;
        stopRule(rule.getId());
        if (rule.getTriggerType()== KPWebhookPreset.TriggerType.STAT) {
            removeOverheadsForRule(rule.getId(), false);
        }
        String cmds = rule.getCommands();
        if (cmds == null || cmds.isBlank()) return;
        Map<String,String> ctx = new HashMap<>();
        ctx.put("player", client.getLocalPlayer()!=null?client.getLocalPlayer().getName():"Unknown");
        ctx.put("TARGET", getCurrentTargetName());
        ctx.put("$TARGET", getCurrentTargetName());
        ctx.put("stat", stat!=null?stat.name():(statCfg!=null && statCfg.getSkill()!=null? statCfg.getSkill().name():""));
        ctx.put("current", current>=0? String.valueOf(current):"");
        ctx.put("value", statCfg!=null? String.valueOf(statCfg.getThreshold()):"");
        ctx.put("widgetGroup", widgetCfg!=null? String.valueOf(widgetCfg.getGroupId()):"");
        ctx.put("widgetChild", widgetCfg!=null && widgetCfg.getChildId()!=null? String.valueOf(widgetCfg.getChildId()):"");
        ctx.put("time", Instant.now().toString());
        ctx.put("otherPlayer", otherPlayer!=null? sanitizePlayerName(otherPlayer.getName()):"");
        ctx.put("otherCombat", otherPlayer!=null? String.valueOf(otherPlayer.getCombatLevel()):"");
        if (otherNpc != null) { ctx.put("npcName", sanitizeNpcName(otherNpc.getName())); ctx.put("npcId", String.valueOf(otherNpc.getId())); } else { ctx.put("npcName", ""); ctx.put("npcId", ""); }
        ctx.put("HITSPLAT_SELF", "");
        ctx.put("HITSPLAT_TARGET", "");
        ctx.put("HITSPLAT", "");
        try { ctx.put("WORLD", String.valueOf(client.getWorld())); } catch (Exception ignored) { ctx.put("WORLD", ""); }
        Skill skillCtx = stat!=null? stat : (statCfg!=null? statCfg.getSkill(): null);
        if (skillCtx != null) {
            try { ctx.put("STAT", String.valueOf(client.getRealSkillLevel(skillCtx))); } catch (Exception ignored) { ctx.put("STAT"," "); }
            try { ctx.put("CURRENT_STAT", String.valueOf(client.getBoostedSkillLevel(skillCtx))); } catch (Exception ignored) { ctx.put("CURRENT_STAT"," "); }
        } else { ctx.put("STAT", ""); ctx.put("CURRENT_STAT", ""); }
        for (Skill s : Skill.values()) {
            try { int real = client.getRealSkillLevel(s); int boosted = client.getBoostedSkillLevel(s);
                ctx.put("$"+s.name(), String.valueOf(real));
                ctx.put("$CURRENT_"+s.name(), String.valueOf(boosted));
                ctx.put(s.name(), String.valueOf(real));
                ctx.put("CURRENT_"+s.name(), String.valueOf(boosted)); } catch (Exception ignored) {}
        }
        List<PendingCommand> list = new ArrayList<>();
        for (String rawLine : cmds.split("\r?\n")) {
            String line = rawLine.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("SLEEP ")) { long ms=0; try { ms = Long.parseLong(line.substring(6).trim()); } catch (Exception ignored) {} PendingCommand pc=new PendingCommand(); pc.type=PendingType.SLEEP_DELAY; pc.sleepMs=Math.max(0,ms); list.add(pc);} else if (upper.equals("SLEEP")) {}
            else if (upper.startsWith("TICK")) { int ticks=1; String[] parts=line.split("\\s+"); if (parts.length>1){ try { ticks=Integer.parseInt(parts[1]); } catch (Exception ignored) {} } PendingCommand pc=new PendingCommand(); pc.type=PendingType.TICK_DELAY; pc.ticks=Math.max(1,ticks); list.add(pc);} else { PendingCommand pc=new PendingCommand(); pc.type=PendingType.ACTION; pc.line=line; list.add(pc);} }
        if (list.isEmpty()) return;
        CommandSequence seq = new CommandSequence();
        seq.rule = rule; seq.ctx = ctx; seq.commands = list; seq.index = 0; seq.tickDelayRemaining = 0; seq.sleepUntilMillis = 0L;
        activeSequences.add(seq);
    }

    private boolean wildcardMatch(String text, String pattern)
    {
        if (pattern == null || pattern.isEmpty()) return true; // no filter
        if (text == null) return false;
        String p = pattern.trim();
        if (p.equals("*")) return true; // match all
        // user uses _ for space
        p = p.replace('_', ' ');
        String src = text;
        // case-insensitive compare using lowercase
        String pLower = p.toLowerCase(Locale.ROOT);
        String srcLower = src.toLowerCase(Locale.ROOT);
        // No wildcard => substring search
        if (!pLower.contains("*")) {
            return srcLower.contains(pLower);
        }
        // Split on * and ensure order
        String[] parts = pLower.split("\\*", -1); // keep empties for leading/trailing *
        int index = 0;
        boolean firstPart = true;
        for (int i = 0; i < parts.length; i++)
        {
            String part = parts[i];
            if (part.isEmpty()) {
                // Consecutive * or leading/trailing * -> skip
                continue;
            }
            int found = srcLower.indexOf(part, index);
            if (found == -1) return false;
            // If this is the first non-empty part and pattern doesn't start with * it must be at start
            if (firstPart && !pLower.startsWith("*") && found != 0) return false;
            index = found + part.length();
            firstPart = false;
        }
        // If pattern does not end with * then last non-empty part must align with end of string
        if (!pLower.endsWith("*"))
        {
            // find last non-empty part
            for (int i = parts.length - 1; i >= 0; i--)
            {
                if (!parts[i].isEmpty())
                {
                    return srcLower.endsWith(parts[i]);
                }
            }
        }
        return true;
    }

    private void executeMessageRule(KPWebhookPreset rule, String messageText, int messageTypeId)
    {
        stopRule(rule.getId());
        String cmds = rule.getCommands(); if (cmds==null || cmds.isEmpty()) return;
        Map<String,String> ctx = new HashMap<>();
        ctx.put("player", client.getLocalPlayer()!=null?client.getLocalPlayer().getName():"Unknown");
        ctx.put("stat", "");
        ctx.put("current", "");
        ctx.put("value", "");
        ctx.put("widgetGroup", "");
        ctx.put("widgetChild", "");
        ctx.put("time", Instant.now().toString());
        ctx.put("otherPlayer", "");
        ctx.put("otherCombat", "");
        ctx.put("message", messageText);
        ctx.put("messageTypeId", String.valueOf(messageTypeId));
        ctx.put("TARGET", getCurrentTargetName());
        ctx.put("$TARGET", getCurrentTargetName());
        ctx.put("HITSPLAT_SELF", "");
        ctx.put("HITSPLAT_TARGET", "");
        ctx.put("HITSPLAT", "");
        try { ctx.put("WORLD", String.valueOf(client.getWorld())); } catch (Exception ignored) { ctx.put("WORLD", ""); }
        ctx.put("STAT", "");
        ctx.put("CURRENT_STAT", "");
        for (Skill s : Skill.values()) {
            try { int real = client.getRealSkillLevel(s); int boosted = client.getBoostedSkillLevel(s);
                ctx.put("$"+s.name(), String.valueOf(real));
                ctx.put("$CURRENT_"+s.name(), String.valueOf(boosted));
                ctx.put(s.name(), String.valueOf(real));
                ctx.put("CURRENT_"+s.name(), String.valueOf(boosted)); } catch (Exception ignored) {}
        }
        List<PendingCommand> list = new ArrayList<>();
        for (String rawLine : cmds.split("\r?\n")) {
            String line = rawLine.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("SLEEP ")) { long ms=0; try{ ms=Long.parseLong(line.substring(6).trim()); }catch(Exception ignored){} PendingCommand pc=new PendingCommand(); pc.type=PendingType.SLEEP_DELAY; pc.sleepMs=Math.max(0,ms); list.add(pc);} else if (upper.equals("SLEEP")) {}
            else if (upper.startsWith("TICK")) { int ticks=1; String[] parts=line.split("\\s+"); if (parts.length>1){ try{ ticks=Integer.parseInt(parts[1]); }catch(Exception ignored){} } PendingCommand pc=new PendingCommand(); pc.type=PendingType.TICK_DELAY; pc.ticks=Math.max(1,ticks); list.add(pc);} else { PendingCommand pc=new PendingCommand(); pc.type=PendingType.ACTION; pc.line=line; list.add(pc);} }
        if (list.isEmpty()) return;
        CommandSequence seq = new CommandSequence();
        seq.rule = rule; seq.ctx = ctx; seq.commands = list; seq.index = 0; seq.tickDelayRemaining = 0; seq.sleepUntilMillis = 0;
        activeSequences.add(seq);
    }


    private void processActionLine(KPWebhookPreset rule, String line, Map<String,String> ctx)
    {
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.equals("STOP")) { stopAll(); return; }
        if (upper.startsWith("STOP_RULE")) {
            // Formats: STOP_RULE (current) or STOP_RULE <id>
            String[] parts = line.split("\\s+");
            int rid = (rule!=null? rule.getId(): -1);
            if (parts.length > 1) {
                try { rid = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
            }
            if (rid >= 0) stopRule(rid);
            return;
        }
        if (upper.startsWith("NOTIFY ")) { addGameMessage(expand(line.substring("NOTIFY ".length()), ctx)); }
        else if (upper.startsWith("WEBHOOK ")) { if (!webhookAvailable(rule)) return; sendWebhookMessage(resolveWebhook(rule), expand(line.substring("WEBHOOK ".length()), ctx)); }
        else if (upper.startsWith("SCREENSHOT")) { if (!webhookAvailable(rule)) return; String rest = line.length()>"SCREENSHOT".length()? line.substring("SCREENSHOT".length()).trim():""; captureAndSendSimpleScreenshot(resolveWebhook(rule), expand(rest, ctx)); }
        else if (P_TEXT_UNDER.matcher(line).find() || P_TEXT_OVER.matcher(line).find() || P_TEXT_CENTER.matcher(line).find()) {
            handleTargetedTextCommand(rule, line, ctx);
        }
        else if (P_IMG_OVER.matcher(line).find() || P_IMG_UNDER.matcher(line).find() || P_IMG_CENTER.matcher(line).find()) {
            handleImageCommand(rule, line, ctx);
        }
        else if (overlayTextCommandHandler.handle(line, rule)) { /* OVERLAY_TEXT handled */ }
        else if (infoboxCommandHandler.handle(line, rule, ctx)) { /* highlight handled */ }
        else if (highlightCommandHandler.handle(line, rule)) { /* highlight handled */ }
        else { /* unknown command */ }
    }

    private void stopRule(int ruleId) {
        // Remove all visuals and sequences belonging to a specific rule without changing its active flag.
        try { highlightManager.removeAllByRule(ruleId); } catch (Exception ignored) {}
        try { overlayTextManager.removeByRule(ruleId); } catch (Exception ignored) {}
        try { infoboxCommandHandler.removeByRule(ruleId); } catch (Exception ignored) {}
        removeOverheadsForRule(ruleId, true);
        removePersistentOverheadsForRule(ruleId);
        cancelSequencesForRule(ruleId);
        overheadImages.removeIf(i -> i != null && i.ruleId == ruleId); // remove rule images
    }

    private void captureAndSendSimpleScreenshot(String webhook, String message)
    {
        if (webhook == null || webhook.isBlank()) return;
        long now = System.currentTimeMillis();
        if (now - lastScreenshotRequestMs < SCREENSHOT_COOLDOWN_MS)
        { log.debug("Screenshot skipped due to cooldown ({} ms since last)", now - lastScreenshotRequestMs); return; }
        lastScreenshotRequestMs = now;
        // Prefer internal buffers first (works even when minimized) before canvas/robot.
        if (tryClientBufferedImage(webhook, message)) return;
        // Only attempt canvas / repaint path if canvas is visible (avoid black/minimized issues)
        if (isCanvasVisible()) {
            if (tryRuneLiteScreenshot(webhook, message)) return;
            if (tryRobotScreenshot(webhook, message)) return; // last resort only when visible
        }
        // If still nothing, schedule fallback attempts to internal capture
        clientThread.invokeLater(() -> SwingUtilities.invokeLater(() -> captureCanvasEDT(webhook, message, 0)));
    }

    private boolean tryRuneLiteScreenshot(String webhook, String message)
    {
        try {
            Canvas canvas = client.getCanvas(); if (canvas == null) return false;
            SwingUtilities.invokeAndWait(() -> { try { canvas.repaint(); Thread.sleep(40); } catch (Exception ignored) {} });
            BufferedImage img = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try { canvas.paint(g); if (!isImageValid(img)) canvas.update(g); } finally { g.dispose(); }
            if (isImageValid(img)) { sendImageToWebhook(img, webhook, message); return true; }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean tryClientBufferedImage(String webhook, String message)
    {
        try {
            String[] names = {"getGameImage","getBufferedImage","getCanvasImage","getScreenshot","createScreenshot","getDrawImage"};
            for (String n : names) {
                try {
                    java.lang.reflect.Method m = client.getClass().getMethod(n);
                    if (BufferedImage.class.isAssignableFrom(m.getReturnType())) {
                        Object o = m.invoke(client);
                        if (o instanceof BufferedImage) {
                            BufferedImage img = copyBuffered((BufferedImage)o);
                            if (isImageValid(img)) { sendImageToWebhook(img, webhook, message); return true; }
                        }
                    }
                } catch (Exception ignored) {}
            }
            for (java.lang.reflect.Method m : client.getClass().getMethods()) {
                try {
                    if (m.getParameterCount()==0 && BufferedImage.class.isAssignableFrom(m.getReturnType())) {
                        Object o = m.invoke(client);
                        if (o instanceof BufferedImage) {
                            BufferedImage img = copyBuffered((BufferedImage)o);
                            if (isImageValid(img)) { sendImageToWebhook(img, webhook, message); return true; }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean tryRobotScreenshot(String webhook, String message)
    {
        try {
            Canvas canvas = client.getCanvas(); if (canvas == null) return false;
            java.awt.Point p = canvas.getLocationOnScreen(); Dimension d = canvas.getSize();
            if (d.width <=0 || d.height<=0) return false;
            Thread.sleep(60);
            Robot r = new Robot();
            BufferedImage img = r.createScreenCapture(new Rectangle(p.x, p.y, d.width, d.height));
            if (isImageValid(img)) { sendImageToWebhook(img, webhook, message); return true; }
        } catch (Exception ignored) {}
        return false;
    }

    private void captureCanvasEDT(String webhook, String message, int attempt)
    {
        final int MAX=4;
        try {
            if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> captureCanvasEDT(webhook, message, attempt)); return; }
            Canvas canvas = client.getCanvas(); if (canvas==null) return;
            int w = canvas.getWidth(), h = canvas.getHeight(); if (w<=0||h<=0){ scheduleRetry(webhook,message,attempt,MAX); return; }
            BufferedImage img = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try { canvas.paint(g); if (!isImageValid(img)) canvas.update(g);} finally { g.dispose(); }
            if (isImageValid(img)) { sendImageToWebhook(img, webhook, message); return; }
            if (attempt < MAX) { scheduleRetry(webhook,message,attempt,MAX); }
        } catch (Exception e) {
            if (attempt < MAX) scheduleRetry(webhook,message,attempt,MAX);
        }
    }

    private void scheduleRetry(String webhook, String message, int attempt, int max)
    { if (attempt >= max) return; executorService.schedule(() -> SwingUtilities.invokeLater(() -> captureCanvasEDT(webhook, message, attempt+1)), 60, TimeUnit.MILLISECONDS); }

    private boolean isImageValid(BufferedImage img) { return img != null && !isMostlyBlack(img) && !isMostlyWhite(img); }

    // Image luminance helpers (re-added)
    private boolean isMostlyBlack(BufferedImage img) {
        if (img == null) return false;
        try {
            int w = img.getWidth(), h = img.getHeight();
            long dark = 0, total = 0; int stepX = Math.max(1, w/50), stepY = Math.max(1, h/50);
            for (int y=0; y<h; y+=stepY) {
                for (int x=0; x<w; x+=stepX) {
                    int rgb = img.getRGB(x,y);
                    int r=(rgb>>16)&0xFF, g=(rgb>>8)&0xFF, b=rgb&0xFF;
                    int lum = (r*30 + g*59 + b*11)/100;
                    if (lum < 5) dark++;
                    total++;
                }
            }
            return total>0 && dark*100/total > 95;
        } catch (Exception e) { return false; }
    }
    private boolean isMostlyWhite(BufferedImage img) {
        if (img == null) return false;
        try {
            int w = img.getWidth(), h = img.getHeight();
            long bright=0, total=0; int stepX=Math.max(1,w/50), stepY=Math.max(1,h/50);
            for (int y=0; y<h; y+=stepY) {
                for (int x=0; x<w; x+=stepX) {
                    int rgb = img.getRGB(x,y);
                    int r=(rgb>>16)&0xFF, g=(rgb>>8)&0xFF, b=rgb&0xFF;
                    int lum = (r*30 + g*59 + b*11)/100;
                    if (lum > 240) bright++;
                    total++;
                }
            }
            return total>0 && bright*100/total > 90;
        } catch (Exception e) { return false; }
    }

    private String expand(String text, Map<String,String> ctx)
    {
        if (text == null || ctx == null) return text;
        // Replace longer keys first to avoid partial collisions (e.g. HITSPLAT_SELF vs HITSPLAT)
        java.util.List<String> keys = new java.util.ArrayList<>(ctx.keySet());
        keys.sort((a,b)->Integer.compare(b.length(), a.length()));
        for (String key : keys)
        {
            String value = ctx.get(key);
            if (key == null || value == null) continue;
            // Exact token patterns
            text = text.replace("${" + key + "}", value);
            text = text.replace("{{" + key + "}}", value);
            text = text.replace("$" + key, value);
            // Legacy keys that themselves start with $
            if (key.startsWith("$")) {
                text = text.replace(key, value);
            }
        }
        return text;
    }

    private BufferedImage copyBuffered(BufferedImage src)
    {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = copy.createGraphics();
            g2.setComposite(AlphaComposite.Src);
            g2.drawImage(src, 0, 0, null);
            g2.dispose();
            return copy;
        } catch (Exception e) {
            return src;
        }
    }

    private void addGameMessage(String message)
    {
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
    }

    private void sendWebhookMessage(String webhook, String message)
    {
        executorService.execute(() -> {
            try {
                String json = gson.toJson(Map.of("content", message));
                RequestBody body = RequestBody.create(JSON, json);
                Request request = new Request.Builder().url(webhook).post(body).build();
                try (Response response = okHttpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.warn("Webhook failed: {}", response.code());
                    } else {
                        log.info("✅ Message sent to Discord");
                    }
                }
            } catch (Exception e) {
                log.error("Error sending webhook", e);
            }
        });
    }

    // Added back: stop all active sequences, texts, highlights
    private void stopAll()
    {
        activeSequences.clear();
        overheadTexts.clear();
        overheadImages.clear();
        if (highlightManager != null) highlightManager.clear();
        if (infoboxCommandHandler != null) infoboxCommandHandler.clearAll();
        if (overlayTextManager != null) overlayTextManager.clear();
    }

    // === Utility helpers (restored) ===
    private Color parseColor(String hex, Color def) {
        if (hex == null || hex.isBlank()) return def;
        String h = hex.trim();
        try {
            if (!h.startsWith("#")) h = "#" + h;
            return Color.decode(h);
        } catch (Exception e) { return def; }
    }
    private int nz(Integer v, int def) { return v != null ? v : def; }
    private boolean safe(Boolean b) { return b != null && b; }
    private String or(String v, String def) { return (v != null && !v.isBlank()) ? v : def; }
    private String normalizeName(String s) { return s==null?"":s.replace('_',' ').trim().toLowerCase(Locale.ROOT); }
    public Actor getCurrentTargetActor() { return currentTarget; }
    private boolean isCanvasVisible() { try { Canvas c = client.getCanvas(); return c != null && c.isShowing() && c.getWidth()>0 && c.getHeight()>0; } catch (Exception e){ return false; } }
    private void sendImageToWebhook(BufferedImage img, String webhook, String message) {
        if (img == null || webhook == null || webhook.isBlank()) return;
        executorService.execute(() -> {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(img, "png", baos);
                byte[] png = baos.toByteArray();
                MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM);
                if (message != null && !message.isBlank()) mb.addFormDataPart("content", message);
                mb.addFormDataPart("file", "screenshot.png", RequestBody.create(PNG, png));
                Request request = new Request.Builder().url(webhook).post(mb.build()).build();
                try (Response response = okHttpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) log.warn("Webhook image failed: {}", response.code());
                }
            } catch (Exception e) { log.warn("Failed to send image webhook", e); }
        });
    }
    // ===== Restored panel refresh helper =====
    private void refreshPanelTable() {
        if (panel == null) return;
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                panel.refreshTable();
            } else {
                SwingUtilities.invokeLater(() -> { try { panel.refreshTable(); } catch (Exception ignored) {} });
            }
        } catch (Exception ignored) {}
    }
    // ===== Overhead text removal helpers (restored) =====
    private void removeOverheadsForRule(int ruleId, boolean includePersistent) {
        if (overheadTexts.isEmpty()) return;
        final String prefixRule = "RULE_" + ruleId + "_";
        final String prefixPersist = "PERSIST_" + ruleId + "_";
        overheadTexts.removeIf(t -> {
            if (t == null) return false;
            boolean owned = t.ruleId == ruleId;
            if (!owned && t.key != null) {
                if (t.key.startsWith(prefixRule) || t.key.startsWith(prefixPersist)) owned = true;
            }
            if (!owned) return false;
            if (!includePersistent && t.persistent) return false;
            return true;
        });
    }
    private void removePersistentOverheadsForRule(int ruleId) {
        if (overheadTexts.isEmpty()) return;
        final String prefixPersist = "PERSIST_" + ruleId + "_";
        overheadTexts.removeIf(t -> t != null && t.persistent && (t.ruleId == ruleId || (t.key != null && t.key.startsWith(prefixPersist))));
    }
    // ===== Overhead text creation (simplified) =====
    private void addOverheadTextFromPreset(String text, String position, KPWebhookPreset rule) {
        if (text == null || text.isBlank() || rule == null) return;
        ActiveOverheadText ot = new ActiveOverheadText();
        ot.text = text; ot.position = position; ot.ruleId = rule.getId();
        int duration = 80; String colorHex = "#FFFFFF"; boolean blink=false; int size=16; boolean bold=false, italic=false, underline=false;
        switch (position) {
            case "Above": colorHex = or(rule.getTextOverColor(), colorHex); blink = safe(rule.getTextOverBlink()); size = nz(rule.getTextOverSize(), size); duration = nz(rule.getTextOverDuration(), duration); bold=safe(rule.getTextOverBold()); italic=safe(rule.getTextOverItalic()); underline=safe(rule.getTextOverUnderline()); break;
            case "Under": colorHex = or(rule.getTextUnderColor(), colorHex); blink = safe(rule.getTextUnderBlink()); size = nz(rule.getTextUnderSize(), size); duration = nz(rule.getTextUnderDuration(), duration); bold=safe(rule.getTextUnderBold()); italic=safe(rule.getTextUnderItalic()); underline=safe(rule.getTextUnderUnderline()); break;
            case "Center": colorHex = or(rule.getTextCenterColor(), colorHex); blink = safe(rule.getTextCenterBlink()); size = nz(rule.getTextCenterSize(), size); duration = nz(rule.getTextCenterDuration(), duration); bold=safe(rule.getTextCenterBold()); italic=safe(rule.getTextCenterItalic()); underline=safe(rule.getTextCenterUnderline()); break;
        }
        if (duration <= 0) { ot.persistent = true; duration = 0; }
        ot.remainingTicks = duration; ot.color = parseColor(colorHex, Color.WHITE); ot.blink = blink; ot.blinkInterval = 2; ot.size = size; ot.bold=bold; ot.italic=italic; ot.underline=underline; ot.visiblePhase = true;
        overheadTexts.add(ot);
    }
    public void addOverheadText(KPWebhookPreset rule, String text, String position) {
        if (text == null || text.isBlank()) return;
        if (rule != null) { addOverheadTextFromPreset(text, position!=null?position:"Above", rule); return; }
        ActiveOverheadText ot = new ActiveOverheadText(); ot.text=text; ot.position= position!=null?position:"Above"; ot.ruleId=-1; ot.persistent=false; ot.remainingTicks=80; ot.color=Color.WHITE; ot.blink=false; ot.size=16; ot.visiblePhase=true;
        overheadTexts.add(ot);
    }
    // ===== Targeted text command parser (restored) =====
    private void handleTargetedTextCommand(KPWebhookPreset rule, String line, Map<String,String> ctx) {
        String raw = line.trim(); java.util.regex.Matcher m; String pos=null;
        if ((m=P_TEXT_OVER.matcher(raw)).find()) pos="Above"; else if ((m=P_TEXT_UNDER.matcher(raw)).find()) pos="Under"; else if ((m=P_TEXT_CENTER.matcher(raw)).find()) pos="Center"; else return;
        String content = m.group(1).trim(); if (content.isEmpty()) return;
        ActiveOverheadText.TargetType targetType = ActiveOverheadText.TargetType.LOCAL_PLAYER; java.util.Set<String> targetNames=null; java.util.Set<Integer> targetIds=null;
        List<String> prelim = new ArrayList<>(Arrays.asList(content.split("\\s+")));
        if (!prelim.isEmpty()) {
            String first = prelim.get(0).toUpperCase(Locale.ROOT);
            if (first.equals("TARGET")) { targetType= ActiveOverheadText.TargetType.TARGET; prelim.remove(0);} else if (first.equals("LOCAL_PLAYER")) {prelim.remove(0);} else if (first.equals("PLAYER") && prelim.size()>=2) { targetType= ActiveOverheadText.TargetType.PLAYER_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(prelim.get(1))); prelim.subList(0,2).clear(); } else if (first.equals("NPC") && prelim.size()>=2) { String spec=prelim.get(1); if (spec.matches("\\d+")) { targetType= ActiveOverheadText.TargetType.NPC_ID; targetIds=new HashSet<>(); try{targetIds.add(Integer.parseInt(spec));}catch(Exception ignored){} } else { targetType= ActiveOverheadText.TargetType.NPC_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(spec)); } prelim.subList(0,2).clear(); }
        }
        boolean blink=false,bold=false,italic=false,underline=false; int size=-1; Color color=null; StringBuilder sb=new StringBuilder();
        for (String tok : prelim) { String tu=tok.toUpperCase(Locale.ROOT); if (tu.startsWith("SIZE=")) { try{size=Integer.parseInt(tok.substring(5));}catch(Exception ignored){} continue; } if (tu.startsWith("COLOR=")) { color=parseColor(tok.substring(6), null); continue; } if (tu.equals("BLINK")) { blink=true; continue; } if (tu.equals("BOLD")) { bold=true; continue; } if (tu.equals("ITALIC")) { italic=true; continue; } if (tu.equals("UNDERLINE")) { underline=true; continue; } sb.append(tok).append(' '); }
        String finalText = expand(sb.toString().trim(), ctx); if (finalText.isBlank()) return;
        ActiveOverheadText ot = new ActiveOverheadText(); ot.text=finalText; ot.position=pos; ot.ruleId= rule!=null?rule.getId():-1; ot.targetType=targetType; ot.targetNames=targetNames; ot.targetIds=targetIds;
        switch (pos) {
            case "Above": ot.size=size>0?size:nz(rule.getTextOverSize(),16); ot.color=color!=null?color:parseColor(rule.getTextOverColor(),Color.WHITE); ot.remainingTicks=nz(rule.getTextOverDuration(),80); ot.bold=bold||safe(rule.getTextOverBold()); ot.italic=italic||safe(rule.getTextOverItalic()); ot.underline=underline||safe(rule.getTextOverUnderline()); ot.blink=blink||safe(rule.getTextOverBlink()); break;
            case "Under": ot.size=size>0?size:nz(rule.getTextUnderSize(),16); ot.color=color!=null?color:parseColor(rule.getTextUnderColor(),Color.WHITE); ot.remainingTicks=nz(rule.getTextUnderDuration(),80); ot.bold=bold||safe(rule.getTextUnderBold()); ot.italic=italic||safe(rule.getTextUnderItalic()); ot.underline=underline||safe(rule.getTextUnderUnderline()); ot.blink=blink||safe(rule.getTextUnderBlink()); break;
            case "Center": ot.size=size>0?size:nz(rule.getTextCenterSize(),16); ot.color=color!=null?color:parseColor(rule.getTextCenterColor(),Color.WHITE); ot.remainingTicks=nz(rule.getTextCenterDuration(),80); ot.bold=bold||safe(rule.getTextCenterBold()); ot.italic=italic||safe(rule.getTextCenterItalic()); ot.underline=underline||safe(rule.getTextCenterUnderline()); ot.blink=blink||safe(rule.getTextCenterBlink()); break;
        }
        if (ot.remainingTicks<=0) { ot.persistent=true; ot.remainingTicks=0; }
        ot.visiblePhase=true; overheadTexts.add(ot);
    }
    // ===== Image command handler (restored) =====
    private void handleImageCommand(KPWebhookPreset rule, String line, Map<String,String> ctx) {
        String raw=line.trim(); java.util.regex.Matcher m; String pos=null;
        if ((m=P_IMG_OVER.matcher(raw)).find()) pos="Above"; else if ((m=P_IMG_UNDER.matcher(raw)).find()) pos="Under"; else if ((m=P_IMG_CENTER.matcher(raw)).find()) pos="Center"; else return;
        String rest = m.group(1).trim(); if (rest.isEmpty()) return;
        List<String> tokens = new ArrayList<>(Arrays.asList(rest.split("\\s+")));
        ActiveOverheadImage.TargetType targetType = ActiveOverheadImage.TargetType.LOCAL_PLAYER; Set<String> targetNames=null; Set<Integer> targetIds=null;
        if (!tokens.isEmpty()) {
            String first = tokens.get(0).toUpperCase(Locale.ROOT);
            if (first.equals("TARGET")) { targetType= ActiveOverheadImage.TargetType.TARGET; tokens.remove(0);} else if (first.equals("LOCAL_PLAYER")) { tokens.remove(0);} else if (first.equals("PLAYER") && tokens.size()>=2) { targetType= ActiveOverheadImage.TargetType.PLAYER_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(tokens.get(1))); tokens.subList(0,2).clear(); } else if (first.equals("NPC") && tokens.size()>=2) { String spec=tokens.get(1); if (spec.matches("\\d+")) { targetType= ActiveOverheadImage.TargetType.NPC_ID; targetIds=new HashSet<>(); try { targetIds.add(Integer.parseInt(spec)); } catch(Exception ignored){} } else { targetType= ActiveOverheadImage.TargetType.NPC_NAME; targetNames=new HashSet<>(); targetNames.add(normalizeName(spec)); } tokens.subList(0,2).clear(); }
        }
        if (tokens.isEmpty()) return;
        int rawId; try { rawId=Integer.parseInt(tokens.get(0)); } catch(Exception e){ return; }
        boolean sprite = rawId < 0; int id = Math.abs(rawId);
        BufferedImage img = sprite? loadSprite(id): loadItem(id); if (img==null) return;
        int duration = 80; boolean blink=false;
        if (rule != null) {
            duration = nz(rule.getImgDuration(), duration);
            blink = safe(rule.getImgBlink());
        }
        ActiveOverheadImage oi = new ActiveOverheadImage(); oi.image=img; oi.sprite=sprite; oi.itemOrSpriteId=rawId; oi.position=pos; oi.ruleId = rule!=null?rule.getId():-1; oi.targetType=targetType; oi.targetNames=targetNames; oi.targetIds=targetIds;
        oi.blink = blink; oi.blinkInterval = 2; oi.visiblePhase = true;
        if (duration<=0) { oi.persistent=true; oi.remainingTicks=0; } else { oi.remainingTicks=duration; }
        overheadImages.add(oi);
    }
    private BufferedImage loadItem(int itemId) { try { return itemManager!=null? itemManager.getImage(itemId): null; } catch(Exception e){ return null; } }
    private BufferedImage loadSprite(int spriteId) { try { return spriteManager!=null? spriteManager.getSprite(spriteId,0): null; } catch(Exception e){ return null; } }

    // ===== Added missing helper methods to resolve compile errors =====
    public java.util.List<KPWebhookPreset> getRules() { return java.util.Collections.unmodifiableList(rules); }
    private void savePreset(KPWebhookPreset p) { savePreset(p, null); }
    private void savePreset(KPWebhookPreset p, String previousTitle) {
        if (p == null || storage == null) return; try { storage.save(p, previousTitle); } catch (Exception ignored) {}
    }
    private void saveAllPresets() { if (storage==null) return; for (KPWebhookPreset p : rules) savePreset(p); }
    public KPWebhookPreset find(int id) { for (KPWebhookPreset r : rules) if (r.getId()==id) return r; return null; }

   // === Added CRUD helpers used by panel ===
    public synchronized void addOrUpdate(KPWebhookPreset preset) {
        if (preset == null) return;
        if (preset.getId() < 0) { // new preset
            preset.setId(nextId++);
            rules.add(preset);
            savePreset(preset, null);
            refreshPanelTable();
            return;
        }
        KPWebhookPreset existing = find(preset.getId());
        if (existing == null) { // treat as new with provided id (avoid clash with nextId)
            if (preset.getId() >= nextId) nextId = preset.getId() + 1;
            rules.add(preset);
            savePreset(preset, null);
        } else {
            String previousTitle = existing.getTitle();
            // Replace existing object in list (keep ordering)
            int idx = rules.indexOf(existing);
            rules.set(idx, preset);
            savePreset(preset, previousTitle);
        }
        refreshPanelTable();
    }

    public synchronized void toggleActive(int id) {
        KPWebhookPreset r = find(id); if (r == null) return;
        r.setActive(!r.isActive());
        if (!r.isActive()) { // deactivate -> stop visuals & sequences
            try { stopRule(r.getId()); } catch (Exception ignored) {}
        } else {
            // Reset transient runtime flags when reactivating
            r.setLastConditionMet(false);
            r.setLastTriggeredBoosted(Integer.MIN_VALUE);
        }
        savePreset(r, null);
    }

    public synchronized void deleteRule(int id) {
        KPWebhookPreset r = find(id); if (r == null) return;
        try { stopRule(r.getId()); } catch (Exception ignored) {}
        rules.remove(r);
        try { if (storage != null) storage.delete(r); } catch (Exception ignored) {}
        refreshPanelTable();
    }

    // === Utility / helper methods restored (previously removed by truncation) ===
    private String sanitizePlayerName(String name) {
        if (name == null) return ""; try { String noTags = net.runelite.client.util.Text.removeTags(name); noTags = noTags.replace('\u00A0',' ').trim(); return noTags; } catch (Exception e){ return name.replace('\u00A0',' ').trim(); }
    }
    private String sanitizeNpcName(String name) {
        if (name==null) return ""; try { String noTags = net.runelite.client.util.Text.removeTags(name); return noTags.replace('\u00A0',' ').trim(); } catch(Exception e){ return name.replace('\u00A0',' ').trim(); }
    }
    private boolean matchesNpc(KPWebhookPreset.NpcConfig cfg, NPC npc) {
        if (cfg==null || npc==null) return false; int id = npc.getId(); String n = sanitizeNpcName(npc.getName());
        if (cfg.getNpcIds()!=null) for (Integer i : cfg.getNpcIds()) if (i!=null && i==id) return true;
        if (n!=null && !n.isEmpty() && cfg.getNpcNames()!=null) { String ln = n.toLowerCase(java.util.Locale.ROOT); for (String s : cfg.getNpcNames()) if (ln.equals(s)) return true; }
        return false;
    }
    private void refreshPersistentStatVisuals(Skill skill, int boostedValue) { /* placeholder for future persistent stat visuals */ }
    private void cancelSequencesForRule(int ruleId) { if (activeSequences.isEmpty()) return; activeSequences.removeIf(seq -> seq!=null && seq.rule!= null && seq.rule.getId()==ruleId); }
    public void openDebugWindow() {
        try {
            if (debugWindow == null) { debugWindow = new KPWebhookDebugWindow(this); }
            if (!debugWindow.isVisible()) { debugWindow.setVisible(true); }
            debugWindow.toFront();
        } catch (Exception e) { log.warn("Failed to open debug window", e); }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied ev) {
        try {
            if (ev == null) return;
            Actor a = ev.getActor();
            if (a == null) return;
            Hitsplat hs = ev.getHitsplat();
            if (hs == null) return;
            int amount = hs.getAmount();
            boolean self = (a instanceof Player) && a == client.getLocalPlayer();
            boolean target = (currentTarget != null && a == currentTarget);
            if (debugWindow != null && debugWindow.isVisible()) {
                try { debugWindow.logHitsplat(self, amount, sanitizeActorName(a)); } catch (Exception ignored) {}
            }
            if (!self && !target) return; // only process for self or current target
            for (KPWebhookPreset r : getRules()) {
                if (!r.isActive()) continue;
                if (self && r.getTriggerType() == KPWebhookPreset.TriggerType.HITSPLAT_SELF) {
                    if (hitsplatMatches(r.getHitsplatConfig(), amount)) {
                        executeHitsplatRule(r, amount, true);
                    }
                } else if (target && r.getTriggerType() == KPWebhookPreset.TriggerType.HITSPLAT_TARGET) {
                    if (hitsplatMatches(r.getHitsplatConfig(), amount)) {
                        executeHitsplatRule(r, amount, false);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private String sanitizeActorName(Actor a) {
        try {
            if (a == null) return "";
            if (a instanceof Player) return sanitizePlayerName(((Player)a).getName());
            if (a instanceof NPC) return sanitizeNpcName(((NPC)a).getName());
        } catch (Exception ignored) {}
        return "";
    }

    private boolean hitsplatMatches(KPWebhookPreset.HitsplatConfig cfg, int amount) {
        if (cfg == null || cfg.getValue() == null) return false;
        int thr = cfg.getValue();
        KPWebhookPreset.HitsplatConfig.Mode m = cfg.getMode();
        if (m == null) m = KPWebhookPreset.HitsplatConfig.Mode.GREATER_EQUAL;
        switch (m) {
            case GREATER: return amount > thr;
            case GREATER_EQUAL: return amount >= thr;
            case EQUAL: return amount == thr;
            case LESS_EQUAL: return amount <= thr;
            case LESS: return amount < thr;
        }
        return false;
    }

    private void executeHitsplatRule(KPWebhookPreset rule, int amount, boolean self) {
        if (rule == null) return;
        stopRule(rule.getId());
        String cmds = rule.getCommands();
        if (cmds == null || cmds.isBlank()) return;
        Map<String,String> ctx = new HashMap<>();
        ctx.put("player", client.getLocalPlayer()!=null?client.getLocalPlayer().getName():"Unknown");
        ctx.put("stat", ""); ctx.put("current","" ); ctx.put("value","");
        ctx.put("widgetGroup","" ); ctx.put("widgetChild","" );
        ctx.put("time", Instant.now().toString());
        ctx.put("otherPlayer","" ); ctx.put("otherCombat","" );
        ctx.put("TARGET", getCurrentTargetName()); ctx.put("$TARGET", getCurrentTargetName());
        if (self) { ctx.put("HITSPLAT_SELF", String.valueOf(amount)); ctx.put("HITSPLAT_TARGET", ""); }
        else { ctx.put("HITSPLAT_SELF", ""); ctx.put("HITSPLAT_TARGET", String.valueOf(amount)); }
        ctx.put("HITSPLAT", String.valueOf(amount));
        try { ctx.put("WORLD", String.valueOf(client.getWorld())); } catch (Exception ignored) { ctx.put("WORLD", ""); }
        ctx.put("STAT", ""); ctx.put("CURRENT_STAT", "");
        for (Skill s : Skill.values()) {
            try { int real = client.getRealSkillLevel(s); int boosted = client.getBoostedSkillLevel(s);
                ctx.put("$"+s.name(), String.valueOf(real));
                ctx.put("$CURRENT_"+s.name(), String.valueOf(boosted));
                ctx.put(s.name(), String.valueOf(real));
                ctx.put("CURRENT_"+s.name(), String.valueOf(boosted)); } catch (Exception ignored) {}
        }
        List<PendingCommand> list = new ArrayList<>();
        for (String rawLine : cmds.split("\r?\n")) {
            String line = rawLine.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("SLEEP ")) { long ms=0; try { ms = Long.parseLong(line.substring(6).trim()); } catch (Exception ignored) {} PendingCommand pc=new PendingCommand(); pc.type=PendingType.SLEEP_DELAY; pc.sleepMs=Math.max(0,ms); list.add(pc);} else if (upper.equals("SLEEP")) {}
            else if (upper.startsWith("TICK")) { int ticks=1; String[] parts=line.split("\\s+"); if (parts.length>1){ try { ticks=Integer.parseInt(parts[1]); } catch (Exception ignored) {} } PendingCommand pc=new PendingCommand(); pc.type=PendingType.TICK_DELAY; pc.ticks=Math.max(1,ticks); list.add(pc);} else { PendingCommand pc=new PendingCommand(); pc.type=PendingType.ACTION; pc.line=line; list.add(pc);} }
        if (list.isEmpty()) return;
        CommandSequence seq = new CommandSequence();
        seq.rule = rule; seq.ctx = ctx; seq.commands = list; seq.index = 0; seq.tickDelayRemaining = 0; seq.sleepUntilMillis = 0;
        activeSequences.add(seq);
    }
}
