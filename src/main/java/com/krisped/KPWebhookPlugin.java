package com.krisped;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Provides;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.ImageUtil;
import okhttp3.*;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
        name = "KP Webhook",
        description = "Triggers: MANUAL, STAT (LEVEL_UP/ABOVE/BELOW), WIDGET. Commands: NOTIFY, WEBHOOK, SCREENSHOT, HIGHLIGHT_(OUTLINE|TILE|HULL).",
        tags = {"webhook","stat","trigger","screenshot","widget","highlight"}
)
public class KPWebhookPlugin extends Plugin
{
    private static final String PLUGIN_DIR_NAME = "kp-webhook";
    private static final String PRESETS_DIR_NAME = "presets";
    private static final String FILE_PREFIX = "preset-";
    private static final String FILE_SUFFIX = ".json";

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar clientToolbar;
    @Inject private ScheduledExecutorService executorService;
    @Inject private OkHttpClient okHttpClient;
    @Inject private ConfigManager configManager;
    @Inject private KPWebhookConfig config;
    @Inject private OverlayManager overlayManager;
    @Inject private KPWebhookHighlightOverlay highlightOverlay;
    @Inject private ModelOutlineRenderer modelOutlineRenderer;

    private Object drawManager;
    private KPWebhookPanel panel;
    private NavigationButton navButton;

    private final List<KPWebhookPreset> rules = new ArrayList<>();
    private int nextId = 0;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType PNG  = MediaType.parse("image/png");

    private final Map<Skill,Integer> lastRealLevel = new EnumMap<>(Skill.class);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Active highlights
    public enum HighlightType { OUTLINE, TILE, HULL, MINIMAP }

    @Data @AllArgsConstructor
    public static class ActiveHighlight
    {
        HighlightType type;
        int remainingTicks;
        Color color;
        int width;
        boolean blink; // blink toggles visibility every tick when true
        boolean visiblePhase;
    }

    private final List<ActiveHighlight> activeHighlights = new ArrayList<>();

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
    }
    private final java.util.List<ActiveOverheadText> overheadTexts = new ArrayList<>();

    public java.util.List<ActiveOverheadText> getOverheadTexts() { return overheadTexts; }

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

    private static final Pattern P_OUTLINE = Pattern.compile("(?i)^HIGHLIGHT_OUTLINE\\b");
    private static final Pattern P_TILE    = Pattern.compile("(?i)^HIGHLIGHT_TILE\\b");
    private static final Pattern P_HULL    = Pattern.compile("(?i)^HIGHLIGHT_HULL\\b");
    private static final Pattern P_MINIMAP = Pattern.compile("(?i)^HIGHLIGHT_MINIMAP\\b");
    private static final Pattern P_TEXT_OVER = Pattern.compile("(?i)^TEXT_OVER\\s+(.*)");
    private static final Pattern P_TEXT_CENTER = Pattern.compile("(?i)^TEXT_CENTER\\s+(.*)");
    private static final Pattern P_TEXT_UNDER = Pattern.compile("(?i)^TEXT_UNDER\\s+(.*)");

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
                .priority(5)
                .panel(panel)
                .build();
        clientToolbar.addNavigation(navButton);
        overlayManager.add(highlightOverlay);

        captureInitialRealLevels();
        tryInjectDrawManager();
        loadAllPresets();
        for (KPWebhookPreset r : rules)
            if (r.getId() >= nextId) nextId = r.getId() + 1;
        refreshPanel();
        log.info("KP Webhook startet. {} presets lastet. Standard webhook satt? {}",
                rules.size(),
                (config.defaultWebhookUrl()!=null && !config.defaultWebhookUrl().isBlank()));
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(highlightOverlay);
        clientToolbar.removeNavigation(navButton);
        saveAllPresets();
        rules.clear();
        panel = null;
        activeHighlights.clear();
    }

    public String getDefaultWebhook()
    {
        String def = config.defaultWebhookUrl();
        return def != null ? def.trim() : "";
    }

    /* ================= Highlight helpers ================= */
    private Color parseColor(String hex, String fallback)
    {
        if (hex == null || hex.isBlank()) hex = fallback;
        try
        {
            String h = hex.trim();
            if (!h.startsWith("#")) h = "#" + h;
            if (h.length()==7)
            {
                int r = Integer.parseInt(h.substring(1,3),16);
                int g = Integer.parseInt(h.substring(3,5),16);
                int b = Integer.parseInt(h.substring(5,7),16);
                return new Color(r,g,b);
            }
        }
        catch (Exception ignored) {}
        return Color.YELLOW;
    }

    private void addHighlight(HighlightType type,
                              int duration,
                              int width,
                              String colorHex,
                              boolean blink)
    {
        if (duration <= 0) duration = 1;
        if (width <= 0) width = 1;
        Color c = parseColor(colorHex, "#FFFF00");
        activeHighlights.add(new ActiveHighlight(type, duration, c, width, blink, true));
    }

    public List<ActiveHighlight> getActiveHighlights()
    {
        return activeHighlights;
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
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

        if (!activeHighlights.isEmpty())
        {
            Iterator<ActiveHighlight> it = activeHighlights.iterator();
            while (it.hasNext())
            {
                ActiveHighlight h = it.next();
                h.remainingTicks--;
                if (h.remainingTicks <= 0)
                {
                    it.remove();
                    continue;
                }
                if (h.blink)
                {
                    h.visiblePhase = !h.visiblePhase;
                }
            }
        }

        if (!overheadTexts.isEmpty())
        {
            Iterator<ActiveOverheadText> it = overheadTexts.iterator();
            while (it.hasNext())
            {
                ActiveOverheadText t = it.next();
                t.remainingTicks--;
                if (t.remainingTicks <= 0)
                {
                    it.remove();
                    continue;
                }
                if (t.blink)
                {
                    t.blinkCounter++;
                    if (t.blinkCounter >= t.blinkInterval)
                    {
                        t.blinkCounter = 0;
                        t.visiblePhase = !t.visiblePhase;
                    }
                }
            }
        }

        handleThreshold(Skill.HITPOINTS, client.getBoostedSkillLevel(Skill.HITPOINTS));
    }

    /* ================= DrawManager refleksjon ================= */
    private void tryInjectDrawManager()
    {
        try
        {
            Class<?> dmClazz = Class.forName("net.runelite.client.callback.DrawManager");
            java.lang.reflect.Field injectorField = Plugin.class.getDeclaredField("injector");
            injectorField.setAccessible(true);
            Object injector = injectorField.get(this);
            if (injector != null)
            {
                Object dm = injector.getClass().getMethod("getInstance", Class.class).invoke(injector, dmClazz);
                drawManager = dm;
                log.info("DrawManager funnet.");
            }
        }
        catch (Exception e)
        {
            log.info("DrawManager ikke tilgjengelig. Fallback Robot. ({})", e.getClass().getSimpleName());
            drawManager = null;
        }
    }

    private void captureInitialRealLevels()
    {
        for (Skill s : Skill.values())
            lastRealLevel.put(s, client.getRealSkillLevel(s));
    }

    /* ================= Persistence ================= */
    private File getPresetsDir()
    {
        String home = System.getProperty("user.home");
        File kpDir = new File(home, ".kp");
        File pluginDir = new File(kpDir, PLUGIN_DIR_NAME);
        File presetsDir = new File(pluginDir, PRESETS_DIR_NAME);
        if (!presetsDir.exists() && !presetsDir.mkdirs())
            log.warn("Kunne ikke opprette katalog {}", presetsDir.getAbsolutePath());
        return presetsDir;
    }

    private String slugify(String input)
    {
        String s = input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "preset" : s;
    }

    private File fileForPreset(KPWebhookPreset p)
    {
        return new File(getPresetsDir(),
                FILE_PREFIX + p.getId() + "-" + slugify(p.getTitle()==null?("id"+p.getId()):p.getTitle()) + FILE_SUFFIX);
    }

    private synchronized void savePreset(KPWebhookPreset p)
    {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(fileForPreset(p)), StandardCharsets.UTF_8))
        {
            gson.toJson(p, w);
        }
        catch (IOException e)
        {
            log.warn("Feil ved skriving preset {}", p.getId(), e);
        }
    }

    private synchronized void deletePresetFile(int id)
    {
        File[] files = getPresetsDir().listFiles((d,n) -> n.startsWith(FILE_PREFIX + id + "-") && n.endsWith(FILE_SUFFIX));
        if (files != null)
            for (File f : files)
                if (!f.delete())
                    log.warn("Kunne ikke slette {}", f.getAbsolutePath());
    }

    private synchronized void loadAllPresets()
    {
        rules.clear();
        File[] files = getPresetsDir().listFiles((d,n) -> n.startsWith(FILE_PREFIX) && n.endsWith(FILE_SUFFIX));
        if (files == null) return;
        for (File f : files)
        {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))
            {
                KPWebhookPreset preset = gson.fromJson(r, KPWebhookPreset.class);
                if (preset != null) rules.add(preset);
            }
            catch (Exception ex)
            {
                log.warn("Feil ved lesing {}", f.getName(), ex);
            }
        }
    }

    private synchronized void saveAllPresets()
    {
        for (KPWebhookPreset p : rules) savePreset(p);
    }

    /* ================= CRUD ================= */
    public synchronized List<KPWebhookPreset> getRules()
    {
        return new ArrayList<>(rules);
    }

    public synchronized KPWebhookPreset addOrUpdate(KPWebhookPreset r)
    {
        if (r.getId()<0)
        {
            r.setId(nextId++);
            rules.add(r);
        }
        else
        {
            boolean repl=false;
            for (int i=0;i<rules.size();i++)
                if (rules.get(i).getId()==r.getId())
                {
                    rules.set(i, r);
                    repl=true;
                    break;
                }
            if (!repl) rules.add(r);
        }
        savePreset(r);
        refreshPanel();
        return r;
    }

    public synchronized void deleteRule(int id)
    {
        rules.removeIf(r -> r.getId()==id);
        deletePresetFile(id);
        refreshPanel();
    }

    public synchronized void toggleActive(int id)
    {
        for (KPWebhookPreset r : rules)
            if (r.getId()==id)
            {
                r.setActive(!r.isActive());
                savePreset(r);
                break;
            }
        refreshPanel();
    }

    private KPWebhookPreset find(int id)
    {
        for (KPWebhookPreset r : rules)
            if (r.getId()==id) return r;
        return null;
    }

    private void refreshPanel()
    {
        if (panel!=null) panel.refreshTable();
    }

    /* ================= Events ================= */
    @Subscribe
    public void onStatChanged(StatChanged ev)
    {
        Skill skill = ev.getSkill();
        int real = ev.getLevel();
        Integer prev = lastRealLevel.get(skill);
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
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded ev)
    {
        int group = ev.getGroupId();
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
            executeRule(r, null, -1, r.getStatConfig(), cfg);
        }
    }

    private void handleLevelUp(Skill skill, int newReal)
    {
        for (KPWebhookPreset r : getRules())
        {
            if (!r.isActive() || r.getTriggerType()!= KPWebhookPreset.TriggerType.STAT) continue;
            KPWebhookPreset.StatConfig cfg = r.getStatConfig();
            if (cfg==null || cfg.getSkill()!=skill || cfg.getMode()!= KPWebhookPreset.StatMode.LEVEL_UP) continue;
            if (r.getLastSeenRealLevel() >=0 && newReal > r.getLastSeenRealLevel())
                executeRule(r, skill, newReal, cfg, null);
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
            if (cfg==null || cfg.getSkill()!=skill || cfg.getMode()== KPWebhookPreset.StatMode.LEVEL_UP) continue;

            boolean condition = cfg.getMode()== KPWebhookPreset.StatMode.ABOVE
                    ? boostedValue > cfg.getThreshold()
                    : boostedValue < cfg.getThreshold();

            if (condition && !r.isLastConditionMet())
            {
                executeRule(r, skill, boostedValue, cfg, null);
                r.setLastConditionMet(true);
                savePreset(r);
            }
            else if (!condition && r.isLastConditionMet())
            {
                r.setLastConditionMet(false);
                savePreset(r);
            }
        }
    }

    public void manualSend(int id)
    {
        KPWebhookPreset r = find(id);
        if (r!=null)
            executeRule(r, null, -1, r.getStatConfig(), r.getWidgetConfig());
    }

    /* ================= Webhook resolution ================= */
    private String resolveWebhook(KPWebhookPreset rule)
    {
        if (rule.isUseDefaultWebhook())
        {
            String def = getDefaultWebhook();
            if (!def.isBlank()) return def;
        }
        if (rule.getWebhookUrl()!=null && !rule.getWebhookUrl().isBlank())
            return rule.getWebhookUrl().trim();
        return null;
    }

    private boolean webhookAvailable(KPWebhookPreset rule)
    {
        return resolveWebhook(rule) != null;
    }

    /* ================= Execution ================= */
    private void executeRule(KPWebhookPreset rule,
                             Skill stat,
                             int current,
                             KPWebhookPreset.StatConfig statCfg,
                             KPWebhookPreset.WidgetConfig widgetCfg)
    {
        String cmds = rule.getCommands();
        if (cmds==null || cmds.isEmpty()) return;

        Map<String,String> ctx = new HashMap<>();
        ctx.put("player", client.getLocalPlayer()!=null?client.getLocalPlayer().getName():"Unknown");
        ctx.put("stat", stat!=null?stat.name():(statCfg!=null && statCfg.getSkill()!=null?statCfg.getSkill().name():""));
        ctx.put("current", current>=0?String.valueOf(current):"");
        ctx.put("value", statCfg!=null?String.valueOf(statCfg.getThreshold()):"");
        ctx.put("widgetGroup", widgetCfg!=null?String.valueOf(widgetCfg.getGroupId()):"");
        ctx.put("widgetChild", widgetCfg!=null && widgetCfg.getChildId()!=null?String.valueOf(widgetCfg.getChildId()):"");
        ctx.put("time", Instant.now().toString());

        List<PendingCommand> list = new ArrayList<>();
        for (String raw : cmds.split("\r?\n"))
        {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("SLEEP "))
            {
                long ms = 0;
                try { ms = Long.parseLong(line.substring(6).trim()); } catch (Exception ignored) {}
                PendingCommand pc = new PendingCommand();
                pc.type = PendingType.SLEEP_DELAY;
                pc.sleepMs = Math.max(0, ms);
                list.add(pc);
            }
            else if (upper.equals("SLEEP"))
            {
                // Treat bare SLEEP as 0ms (no-op) – skip
            }
            else if (upper.startsWith("TICK"))
            {
                int ticks = 1;
                String[] parts = line.split("\\s+");
                if (parts.length > 1)
                {
                    try { ticks = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
                }
                PendingCommand pc = new PendingCommand();
                pc.type = PendingType.TICK_DELAY;
                pc.ticks = Math.max(1, ticks);
                list.add(pc);
            }
            else
            {
                PendingCommand pc = new PendingCommand();
                pc.type = PendingType.ACTION;
                pc.line = line;
                list.add(pc);
            }
        }
        if (list.isEmpty()) return;
        CommandSequence seq = new CommandSequence();
        seq.rule = rule;
        seq.ctx = ctx;
        seq.commands = list;
        seq.index = 0;
        seq.tickDelayRemaining = 0;
        seq.sleepUntilMillis = 0;
        activeSequences.add(seq);
    }

    private void processActionLine(KPWebhookPreset rule, String line, Map<String,String> ctx)
    {
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.startsWith("NOTIFY "))
        {
            addGameMessage(expand(line.substring("NOTIFY ".length()), ctx));
        }
        else if (upper.startsWith("WEBHOOK "))
        {
            if (!webhookAvailable(rule)) return; // abort action if no webhook
            sendWebhookMessage(resolveWebhook(rule), expand(line.substring("WEBHOOK ".length()), ctx));
        }
        else if (upper.startsWith("SCREENSHOT"))
        {
            if (!webhookAvailable(rule)) return;
            String rest = line.length() > "SCREENSHOT".length()
                    ? line.substring("SCREENSHOT".length()).trim()
                    : "";
            captureAndSendScreenshot(resolveWebhook(rule), expand(rest, ctx));
        }
        else if (P_OUTLINE.matcher(upper).find())
        {
            addHighlight(HighlightType.OUTLINE,
                    safe(rule.getHlOutlineDuration(),5),
                    safe(rule.getHlOutlineWidth(),4),
                    rule.getHlOutlineColor(),
                    bool(rule.getHlOutlineBlink()));
        }
        else if (P_TILE.matcher(upper).find())
        {
            addHighlight(HighlightType.TILE,
                    safe(rule.getHlTileDuration(),5),
                    safe(rule.getHlTileWidth(),2),
                    rule.getHlTileColor(),
                    bool(rule.getHlTileBlink()));
        }
        else if (P_HULL.matcher(upper).find())
        {
            addHighlight(HighlightType.HULL,
                    safe(rule.getHlHullDuration(),5),
                    safe(rule.getHlHullWidth(),2),
                    rule.getHlHullColor(),
                    bool(rule.getHlHullBlink()));
        }
        else if (P_MINIMAP.matcher(upper).find())
        {
            addHighlight(HighlightType.MINIMAP,
                    safe(rule.getHlMinimapDuration(),5),
                    safe(rule.getHlMinimapWidth(),2),
                    rule.getHlMinimapColor(),
                    bool(rule.getHlMinimapBlink()));
        }
        else if (P_TEXT_OVER.matcher(line).find())
        {
            java.util.regex.Matcher m = P_TEXT_OVER.matcher(line);
            if (m.find()) {
                String txt = expand(m.group(1), ctx);
                addOverheadText(rule, txt, "Above");
            }
        }
        else if (P_TEXT_CENTER.matcher(line).find())
        {
            java.util.regex.Matcher m = P_TEXT_CENTER.matcher(line);
            if (m.find()) {
                String txt = expand(m.group(1), ctx);
                addOverheadText(rule, txt, "Center");
            }
        }
        else if (P_TEXT_UNDER.matcher(line).find())
        {
            java.util.regex.Matcher m = P_TEXT_UNDER.matcher(line);
            if (m.find()) {
                String txt = expand(m.group(1), ctx);
                addOverheadText(rule, txt, "Under");
            }
        }
        else
        {
            // unknown action -> ignore silently
        }
    }

    private void addOverheadText(KPWebhookPreset rule, String text, String position)
    {
        // Remove existing text with same position to prevent stacking clutter
        overheadTexts.removeIf(t -> t.position.equals(position));
        ActiveOverheadText t = new ActiveOverheadText();
        t.text = text;
        if (position.equals("Above")) t.color = parseColor(rule.getTextOverColor(), "#FFFFFF");
        else if (position.equals("Center")) t.color = parseColor(rule.getTextCenterColor(), "#FFFFFF");
        else t.color = parseColor(rule.getTextUnderColor(), "#FFFFFF");
        if (position.equals("Above")) { t.blink = bool(rule.getTextOverBlink()); t.size = rule.getTextOverSize()!=null?rule.getTextOverSize():16; t.remainingTicks = rule.getTextOverDuration()!=null?rule.getTextOverDuration():80; }
        else if (position.equals("Center")) { t.blink = bool(rule.getTextCenterBlink()); t.size = rule.getTextCenterSize()!=null?rule.getTextCenterSize():16; t.remainingTicks = rule.getTextCenterDuration()!=null?rule.getTextCenterDuration():80; }
        else { t.blink = bool(rule.getTextUnderBlink()); t.size = rule.getTextUnderSize()!=null?rule.getTextUnderSize():16; t.remainingTicks = rule.getTextUnderDuration()!=null?rule.getTextUnderDuration():80; }
        t.position = position;
        t.visiblePhase = true;
        t.blinkCounter = 0;
        t.blinkInterval = 2;
        overheadTexts.add(t);
    }

    private int safe(Integer v, int def){ return v==null?def:v; }
    private boolean bool(Boolean b){ return b!=null && b; }

    private String expand(String template, Map<String,String> ctx)
    {
        String out = template;
        for (Map.Entry<String,String> e : ctx.entrySet())
            out = out.replace("{{"+e.getKey()+"}}", e.getValue());
        return out;
    }

    /* ================= Screenshot ================= */
    private void captureAndSendScreenshot(String url, String content)
    {
        if (drawManager != null)
        {
            try
            {
                drawManager.getClass()
                        .getMethod("requestNextFrameListener", java.util.function.Consumer.class)
                        .invoke(drawManager, (java.util.function.Consumer<BufferedImage>) image ->
                                executorService.submit(() -> uploadScreenshot(url, content, image)));
                return;
            }
            catch (Exception e)
            {
                log.warn("DrawManager feilet – fallback Robot.", e);
            }
        }
        fallbackRobotScreenshot(url, content);
    }

    private void fallbackRobotScreenshot(String url, String content)
    {
        clientThread.invoke(() -> {
            try
            {
                Component canvas = client.getCanvas();
                if (canvas == null) return;
                Point loc = canvas.getLocationOnScreen();
                Dimension dim = canvas.getSize();
                if (dim.width<=0 || dim.height<=0) return;
                Robot robot = new Robot();
                Rectangle rect = new Rectangle(loc.x, loc.y, dim.width, dim.height);
                BufferedImage img = robot.createScreenCapture(rect);
                executorService.submit(() -> uploadScreenshot(url, content, img));
            }
            catch (Exception ignored) {}
        });
    }

    private void uploadScreenshot(String url, String content, BufferedImage image)
    {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            ImageIO.write(image, "png", baos);
            byte[] bytes = baos.toByteArray();
            MultipartBody.Builder mb = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file","screenshot.png", RequestBody.create(PNG, bytes));
            if (content!=null && !content.isEmpty())
                mb.addFormDataPart("content", content);

            Request req = new Request.Builder()
                    .url(url)
                    .post(mb.build())
                    .header("User-Agent","KPWebhookPlugin/0.9")
                    .build();

            try (Response resp = okHttpClient.newCall(req).execute())
            {
                if (!resp.isSuccessful())
                    addGameMessage("Screenshot feil: HTTP " + resp.code());
            }
        }
        catch (Exception ex)
        {
            log.warn("Screenshot exception", ex);
        }
    }

    /* ================= Webhook message ================= */
    private void sendWebhookMessage(String url, String content)
    {
        Map<String,Object> json = Collections.singletonMap("content", content);
        RequestBody body = RequestBody.create(JSON, gson.toJson(json));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .header("User-Agent","KPWebhookPlugin/0.9")
                .build();

        executorService.submit(() -> {
            try (Response resp = okHttpClient.newCall(request).execute())
            {
                if (!resp.isSuccessful())
                    addGameMessage("Webhook feil: HTTP " + resp.code());
            }
            catch (IOException ignored) {}
        });
    }

    /* ================= Utility ================= */
    private void addGameMessage(final String msg)
    {
        if (msg == null || msg.isBlank()) return;
        clientThread.invoke(() ->
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[KPWebhook] " + msg, null));
    }
}
