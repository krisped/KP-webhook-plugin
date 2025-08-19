package com.krisped;

import net.runelite.api.Skill;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dialog med faner for Preset, Settings og Info.
 * Settings-fanen viser kollapsbare seksjoner (en per kommando-type) hvor man kan konfigurere
 * kun de stil-parametrene kravet beskriver.
 */
public class KPWebhookPresetDialog extends JDialog
{
    // Added UI sizing constants
    private static final float BASE_FONT_SIZE = 16f; // increased base font size for better readability
    private static final int COMPACT_PANEL_WIDTH = 230; // width of compact dropdown panels
    private static final float HEADER_FONT_SIZE = 18f;

    private final KPWebhookPreset existing;
    private final KPWebhookPlugin plugin;

    private JTabbedPane tabs;

    /* -------- Preset Tab -------- */
    private JTextField titleField;
    private JTextField categoryField; // new category input
    private JComboBox<String> triggerTypeBox;
    private static final String TRIGGER_PLACEHOLDER = "-- Trigger --";
    private JPanel triggerCards;
    private JPanel statCard;
    private JComboBox<String> statSkillBox;
    private JComboBox<KPWebhookPreset.StatMode> statModeBox;
    private JSpinner levelSpinner;
    private JPanel levelHolderCard;
    private static final String LEVEL_CARD_VISIBLE = "LEVEL";
    private static final String LEVEL_CARD_EMPTY   = "EMPTY";
    private static final String SKILL_PLACEHOLDER  = "-- Skill --";
    private JPanel widgetCard;
    private JTextField widgetField;
    // New player trigger components
    private JPanel playerCard;
    private JRadioButton playerAllRadio;
    private JRadioButton playerNameRadio;
    private JRadioButton playerCombatRadio;
    private JTextField playerNameField; // now supports comma-separated multi names
    private JSpinner playerCombatSpinner;
    // Animation trigger components
    private JPanel animationCard;
    private JTextField animationIdField;
    // Graphic trigger components (reuse animationCard style)
    private JPanel graphicCard; // new
    private JTextField graphicIdField; // new
    // Projectile trigger components
    private JPanel projectileCard; // new
    private JTextField projectileIdField; // new
    // Hitsplat trigger components
    private JPanel hitsplatCard; // new
    private JComboBox<String> hitsplatModeBox; // new
    private JSpinner hitsplatValueSpinner; // new
    // Message trigger components
    private JPanel messageCard;
    private JSpinner messageIdSpinner; // now only ID spinner
    private JTextField messageTextField; // NEW: text pattern field
    // Varbit trigger components
    private JPanel varbitCard;
    private JTextField varbitIdsField; // multi-id text field (replaces spinner)
    private JSpinner varbitValueSpinner;
    // Varplayer trigger components
    private JPanel varplayerCard;
    private JTextField varplayerIdsField; // multi-id text field
    private JSpinner varplayerValueSpinner;
    // Webhook components
    private JCheckBox useDefaultWebhookBox;
    private JTextField customWebhookField;
    private JTextArea commandsArea;
    private JCheckBox activeBox;
    // Holder for trigger details panel to toggle visibility
    private JPanel triggerDetailsPanel;
    private JPanel npcCard; // new NPC trigger card
    private JTextField npcListField; // raw list field

    /* -------- Settings Tab (kategorier) -------- */
    private JPanel settingsRoot;
    private HighlightCategoryPanel outlinePanel;
    private HighlightCategoryPanel tilePanel;
    private HighlightCategoryPanel hullPanel;
    private HighlightCategoryPanel minimapPanel; // restored minimap panel
    private HighlightCategoryPanel screenPanel; // new screen highlight panel
    private TextCategoryPanel textOverPanel;
    private TextCategoryPanel textCenterPanel;
    private TextCategoryPanel textUnderPanel;
    private OverlayTextPanel overlayTextPanel; // new simple overlay text panel
    private InfoboxCategoryPanel infoboxPanel; // new infobox panel
    private ImgCategoryPanel imgPanel; // new IMG panel
    // Screenshot settings removed - keeping it simple
    /* -------- Info Tab -------- */
    private JEditorPane infoPane; // replaced JTextArea

    private KPWebhookPreset result;

    /* Default commands */
    private static final String DEFAULT_COMMANDS_TEXT =
            "# Available commands (alphabetical):\n" +
            "#  HIGHLIGHT_HULL            - Hull highlight (player or target)\n" +
            "#  HIGHLIGHT_MINIMAP        - Minimap marker (reserved)\n" +
            "#  HIGHLIGHT_OUTLINE        - Outline local player (or targeted entity)\n" +
            "#  HIGHLIGHT_TILE           - Highlight tile under entity\n" +
            "#  HIGHLIGHT_SCREEN         - Screen border warning\n" +
            "#  IMG_OVER <id>            - Overhead image above (item id, or negative sprite id)\n" +
            "#  IMG_CENTER <id>          - Overhead image centered\n" +
            "#  IMG_UNDER <id>           - Overhead image under feet\n" +
            "#  INFOBOX <id> [message]   - Show icon with optional custom tooltip text\n" +
            "#  NOTIFY <text>            - In-game notification\n" +
            "#  OVERLAY_TEXT <text>      - Screen overlay box (top center)\n" +
            "#  SCREENSHOT [text]        - Capture client area & upload (optional caption)\n" +
            "#  SLEEP <ms>               - Millisecond delay in sequence\n" +
            "#  STOP                     - Stop all active sequences\n" +
            "#  STOP_RULE [id]           - Stop visuals/sequences for a single rule (default current)\n" +
            "#  TEXT_CENTER <text>       - Text centered on entity\n" +
            "#  TEXT_OVER <text>         - Text above entity\n" +
            "#  TEXT_UNDER <text>        - Text under feet\n" +
            "#  TICK [n]                 - Wait n game ticks (default 1)\n" +
            "#  WEBHOOK <text>           - Send to Discord/webhook\n" +
            "# Targeting prefix (optional for HIGHLIGHT_* & TEXT_* & IMG_*): TARGET | LOCAL_PLAYER | PLAYER <navn> | NPC <navn|id>\n" +
            "# IMG ids: positive = item id icon, negative = sprite id (e.g. -738)\n" +
            "# Tokens: {{player}} {{stat}} {{current}} {{value}} {{widgetGroup}} {{widgetChild}} {{time}} {{otherPlayer}} {{otherCombat}} $TARGET {{TARGET}} $HITSPLAT_SELF $HITSPLAT_TARGET\n" +
            "\n" +
            "NOTIFY You gained a level in {{stat}}\n" +
            "HIGHLIGHT_OUTLINE TARGET\n" +
            "WEBHOOK Level in {{stat}} now {{current}} vs target $TARGET\n" +
            "TEXT_OVER TARGET $TARGET";

    public KPWebhookPresetDialog(Window owner,
                                 KPWebhookPlugin plugin,
                                 KPWebhookPreset existing)
    {
        super(owner, existing == null ? "Preset" : "Preset", ModalityType.APPLICATION_MODAL);
        this.plugin = plugin;
        this.existing = existing;
        buildUI();
        populate(existing);
        updateHeader();
        pack();
        // Increase height by 25% (from 480 -> 600)
        setMinimumSize(new Dimension(720, 600));
        setSize(new Dimension(720, 600));
        setLocationRelativeTo(owner);
        // Live update header when user edits Title field
        if (titleField != null) {
            titleField.getDocument().addDocumentListener(new SimpleDoc(){ @Override protected void changed(){ updateHeader(); }});
        }
    }

    public KPWebhookPreset getResult()
    {
        return result;
    }

    /* ================= UI BUILD ================= */
    private void buildUI()
    {
        tabs = new JTabbedPane();
        tabs.addTab("Preset", buildPresetTab());
        tabs.addTab("Settings", buildSettingsTab());
        tabs.addTab("Info", buildInfoTab());

        JPanel root = new JPanel(new BorderLayout(10,10));
        root.setBorder(new EmptyBorder(10,10,10,10));
        // Removed headerLabel from NORTH
        root.add(tabs, BorderLayout.CENTER);
        root.add(buildButtons(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JPanel buildPresetTab()
    {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(6,10,10,10)); // tighter top spacing
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4,4,2,4);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.NORTHWEST;
        g.weightx = 1;
        int y=0;

        // Create a consistent label width using the widest label text
        JLabel probe = new JLabel("Custom URL:");
        int labelW = probe.getPreferredSize().width + 4;
        Dimension labelDim = new Dimension(labelW, probe.getPreferredSize().height);

        // Title
        JLabel titleLbl = new JLabel("Title:"); titleLbl.setPreferredSize(labelDim);
        g.gridx=0; g.gridy=y; g.weightx=0; panel.add(titleLbl, g);
        titleField = new JTextField();
        g.gridx=1; g.weightx=1; panel.add(titleField, g); y++;

        // Category (optional)
        JLabel catLbl = new JLabel("Category:"); catLbl.setPreferredSize(labelDim);
        g.gridx=0; g.gridy=y; g.weightx=0; panel.add(catLbl, g);
        categoryField = new JTextField();
        categoryField.setToolTipText("Optional category/group label");
        g.gridx=1; g.weightx=1; panel.add(categoryField, g); y++;

        // Trigger
        JLabel trigLbl = new JLabel("Trigger:"); trigLbl.setPreferredSize(labelDim);
        g.gridx=0; g.gridy=y; g.weightx=0; panel.add(trigLbl, g);
        triggerTypeBox = new JComboBox<>(buildTriggerModel());
        g.gridx=1; g.weightx=1; panel.add(triggerTypeBox, g); y++;

        // Trigger details card container (spans full width)
        g.gridx=0; g.gridy=y; g.gridwidth=2; g.weightx=1;
        triggerDetailsPanel = new JPanel(new BorderLayout());
        triggerDetailsPanel.setOpaque(false);
        triggerDetailsPanel.setBorder(new TitledBorder("Trigger Details"));
        triggerCards = buildTriggerCards();
        triggerDetailsPanel.add(triggerCards, BorderLayout.CENTER);
        panel.add(triggerDetailsPanel, g); y++; g.gridwidth=1;

        // Webhook group panel
        JPanel webhookGroup = new JPanel(new GridBagLayout());
        webhookGroup.setOpaque(false);
        webhookGroup.setBorder(new TitledBorder("Webhook"));
        GridBagConstraints wg = new GridBagConstraints();
        wg.insets = new Insets(3,6,3,6);
        wg.fill = GridBagConstraints.HORIZONTAL;
        wg.anchor = GridBagConstraints.WEST;
        wg.weightx = 1;
        int wy = 0;
        useDefaultWebhookBox = new JCheckBox("Use default webhook");
        useDefaultWebhookBox.setOpaque(false);
        wg.gridx=0; wg.gridy=wy; wg.gridwidth=2; webhookGroup.add(useDefaultWebhookBox, wg); wy++; wg.gridwidth=1;
        JLabel customLbl = new JLabel("Custom URL:"); customLbl.setPreferredSize(labelDim);
        wg.gridx=0; wg.gridy=wy; wg.weightx=0; webhookGroup.add(customLbl, wg);
        customWebhookField = new JTextField();
        wg.gridx=1; wg.weightx=1; webhookGroup.add(customWebhookField, wg); wy++;
        // add group to main panel
        g.gridx=0; g.gridy=y; g.gridwidth=2; panel.add(webhookGroup, g); y++; g.gridwidth=1;

        // Commands area (titled border, absorbs extra vertical space) - reduced rows for compact dialog
        commandsArea = new JTextArea(8, 60);
        commandsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane sp = new JScrollPane(commandsArea);
        sp.setBorder(new TitledBorder("Commands"));
        g.gridx=0; g.gridy=y; g.gridwidth=2; g.weightx=1; g.weighty=1; g.fill=GridBagConstraints.BOTH; panel.add(sp, g); y++; g.gridwidth=1; g.weighty=0; g.fill=GridBagConstraints.HORIZONTAL;

        // Active checkbox row
        JLabel activeLbl = new JLabel("Active:"); activeLbl.setPreferredSize(labelDim);
        g.gridx=0; g.gridy=y; g.weightx=0; panel.add(activeLbl, g);
        activeBox = new JCheckBox();
        activeBox.setSelected(true);
        g.gridx=1; g.weightx=1; panel.add(activeBox, g); y++;

        // Listeners
        triggerTypeBox.addActionListener(e -> updateTriggerVisibility());
        useDefaultWebhookBox.addActionListener(e -> updateWebhookEnable());
        commandsArea.getDocument().addDocumentListener(new SimpleDoc(){ @Override protected void changed(){ updateSettingsVisibility(); }});

        return panel;
    }

    private JScrollPane buildSettingsTab()
    {
        settingsRoot = new JPanel();
        settingsRoot.setLayout(new BoxLayout(settingsRoot, BoxLayout.Y_AXIS));
        settingsRoot.setBorder(new EmptyBorder(8,8,8,8));

        // Instantiate all panels (kept for save())
        outlinePanel = new HighlightCategoryPanel(existing!=null?existing.getHlOutlineDuration():10,
                existing!=null?existing.getHlOutlineColor():"#FFFF00",
                existing!=null && Boolean.TRUE.equals(existing.getHlOutlineBlink()),
                existing!=null?existing.getHlOutlineWidth():2,
                "Highlight Outline");
        tilePanel = new HighlightCategoryPanel(existing!=null?existing.getHlTileDuration():10,
                existing!=null?existing.getHlTileColor():"#00FF88",
                existing!=null && Boolean.TRUE.equals(existing.getHlTileBlink()),
                existing!=null?existing.getHlTileWidth():2,
                "Highlight Tile");
        // Added missing hullPanel initialization (was causing NPE in CollapsibleSection)
        hullPanel = new HighlightCategoryPanel(existing!=null?existing.getHlHullDuration():10,
                existing!=null?existing.getHlHullColor():"#FF55FF",
                existing!=null && Boolean.TRUE.equals(existing.getHlHullBlink()),
                existing!=null?existing.getHlHullWidth():2,
                "Highlight Hull");
        minimapPanel = new HighlightCategoryPanel(existing!=null?existing.getHlMinimapDuration():10,
                existing!=null?existing.getHlMinimapColor():"#00FFFF",
                existing!=null && Boolean.TRUE.equals(existing.getHlMinimapBlink()),
                existing!=null?existing.getHlMinimapWidth():2,
                "Highlight Minimap");
        screenPanel = new HighlightCategoryPanel(existing!=null?existing.getHlScreenDuration():10,
                existing!=null?existing.getHlScreenColor():"#FF0000",
                existing!=null && Boolean.TRUE.equals(existing.getHlScreenBlink()),
                existing!=null?existing.getHlScreenWidth():4,
                "Highlight Screen");

        textUnderPanel = new TextCategoryPanel(
                existing!=null?existing.getTextUnderDuration():10,
                existing!=null?existing.getTextUnderSize():16,
                existing!=null?existing.getTextUnderColor():"#FFFFFF",
                existing!=null && Boolean.TRUE.equals(existing.getTextUnderBlink()),
                existing!=null && Boolean.TRUE.equals(existing.getTextUnderBold()),
                existing!=null && Boolean.TRUE.equals(existing.getTextUnderItalic()),
                existing!=null && Boolean.TRUE.equals(existing.getTextUnderUnderline()),
                "TEXT_UNDER");
        textOverPanel = new TextCategoryPanel(
                existing!=null?existing.getTextOverDuration():10,
                existing!=null?existing.getTextOverSize():16,
                existing!=null?existing.getTextOverColor():"#FFFFFF",
                existing!=null && Boolean.TRUE.equals(existing.getTextOverBlink()),
                existing!=null && Boolean.TRUE.equals(existing.getTextOverBold()),
                existing!=null && Boolean.TRUE.equals(existing.getTextOverItalic()),
                existing!=null && Boolean.TRUE.equals(existing.getTextOverUnderline()),
                "TEXT_OVER");
        textCenterPanel = new TextCategoryPanel(
                existing!=null?existing.getTextCenterDuration():10,
                existing!=null?existing.getTextCenterSize():16,
                existing!=null?existing.getTextCenterColor():"#FFFFFF",
                existing!=null && Boolean.TRUE.equals(existing.getTextCenterBlink()),
                existing!=null && Boolean.TRUE.equals(existing.getTextCenterBold()),
                existing!=null && Boolean.TRUE.equals(existing.getTextCenterItalic()),
                existing!=null && Boolean.TRUE.equals(existing.getTextCenterUnderline()),
                "TEXT_CENTER");
        overlayTextPanel = new OverlayTextPanel(
                existing!=null?existing.getOverlayTextDuration():100,
                existing!=null?existing.getOverlayTextSize():16,
                existing!=null?existing.getOverlayTextColor():"#FFFFFF",
                "OVERLAY_TEXT");
        infoboxPanel = new InfoboxCategoryPanel(
                existing!=null?existing.getInfoboxDuration():100,
                "INFOBOX");
        imgPanel = new ImgCategoryPanel(
                existing!=null?existing.getImgDuration():100,
                existing!=null && Boolean.TRUE.equals(existing.getImgBlink()),
                "IMG");

        // Highlight group header
        JLabel hlHeader = new JLabel("Highlight");
        hlHeader.setFont(FontManager.getRunescapeBoldFont().deriveFont(HEADER_FONT_SIZE));
        hlHeader.setBorder(new EmptyBorder(4,2,2,2));
        settingsRoot.add(hlHeader);
        java.util.List<CollapsibleSection> hlSections = new java.util.ArrayList<>();
        hlSections.add(new CollapsibleSection(hullPanel));
        hlSections.add(new CollapsibleSection(minimapPanel));
        hlSections.add(new CollapsibleSection(outlinePanel));
        hlSections.add(new CollapsibleSection(tilePanel));
        hlSections.add(new CollapsibleSection(screenPanel));
        hlSections.sort((a,b)-> a.header.getText().compareToIgnoreCase(b.header.getText()));
        for (CollapsibleSection cs : hlSections) settingsRoot.add(cs);
        settingsRoot.add(Box.createVerticalStrut(6));
        // Text group header
        JLabel txtHeader = new JLabel("Text");
        txtHeader.setFont(FontManager.getRunescapeBoldFont().deriveFont(HEADER_FONT_SIZE));
        txtHeader.setBorder(new EmptyBorder(4,2,2,2));
        settingsRoot.add(txtHeader);
        java.util.List<CollapsibleSection> textSections = new java.util.ArrayList<>();
        textSections.add(new CollapsibleSection(overlayTextPanel));
        textSections.add(new CollapsibleSection(textCenterPanel));
        textSections.add(new CollapsibleSection(textOverPanel));
        textSections.add(new CollapsibleSection(textUnderPanel));
        // removed alphabetical sort to preserve custom order
        for (CollapsibleSection cs : textSections) settingsRoot.add(cs);
        settingsRoot.add(Box.createVerticalStrut(6));
        JLabel ibHeader = new JLabel("Infobox");
        ibHeader.setFont(FontManager.getRunescapeBoldFont().deriveFont(HEADER_FONT_SIZE));
        ibHeader.setBorder(new EmptyBorder(4,2,2,2));
        settingsRoot.add(ibHeader);
        settingsRoot.add(new CollapsibleSection(infoboxPanel));
        settingsRoot.add(Box.createVerticalStrut(6));
        JLabel imgHeader = new JLabel("IMG");
        imgHeader.setFont(FontManager.getRunescapeBoldFont().deriveFont(HEADER_FONT_SIZE));
        imgHeader.setBorder(new EmptyBorder(4,2,2,2));
        settingsRoot.add(imgHeader);
        settingsRoot.add(new CollapsibleSection(imgPanel));

        return new JScrollPane(settingsRoot);
    }

    private JPanel buildInfoTab()
    {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);

        infoPane = new JEditorPane();
        infoPane.setContentType("text/html");
        infoPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        infoPane.setEditable(false);
        infoPane.setOpaque(false);
        infoPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        infoPane.setText(buildInfoHtml());

        JPanel frame = new JPanel(new BorderLayout());
        frame.setOpaque(true);
        Color bg = new Color(32,32,38,245);
        frame.setBackground(bg);
        frame.setBorder(new CompoundBorder(new LineBorder(new Color(70,70,90)), new EmptyBorder(12,14,12,14)));
        frame.add(infoPane, BorderLayout.CENTER);

        JScrollPane sp = new JScrollPane(frame, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(new EmptyBorder(6,6,6,6));
        sp.getViewport().setOpaque(false);
        sp.setOpaque(false);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private String buildInfoHtml() {
        // Build skill token list (alphabetical)
        java.util.List<String> skillList = new java.util.ArrayList<>();
        for (Skill s : Skill.values()) skillList.add(s.name());
        java.util.Collections.sort(skillList, String.CASE_INSENSITIVE_ORDER);
        String skillTokens = String.join(", ", skillList);
        // Trigger descriptions mapping
        java.util.Map<String,String> triggerDesc = new java.util.HashMap<>();
        triggerDesc.put("ANIMATION_ANY","Any animation (self or target) change");
        triggerDesc.put("ANIMATION_SELF","Local player animation id");
        triggerDesc.put("ANIMATION_TARGET","Current target animation id");
        triggerDesc.put("GRAPHIC_ANY","Any graphic (self or target) change");
        triggerDesc.put("GRAPHIC_SELF","Local player graphic id");
        triggerDesc.put("GRAPHIC_TARGET","Current target graphic id");
        triggerDesc.put("PROJECTILE_ANY","Any projectile (optional id filter)"); // new
        triggerDesc.put("PROJECTILE_SELF","Projectile towards current target (id)"); // new
        triggerDesc.put("PROJECTILE_TARGET","Projectile towards you (id)"); // new
        triggerDesc.put("HITSPLAT_SELF","Damage you take (conditional)");
        triggerDesc.put("HITSPLAT_TARGET","Damage your target takes (conditional)");
        triggerDesc.put("MANUAL","Manual trigger");
        triggerDesc.put("MESSAGE","Chat message filter");
        triggerDesc.put("NPC_DESPAWN","NPC despawn – filter via list (id or name)");
        triggerDesc.put("NPC_SPAWN","NPC spawn – filter via list (id or name)");
        triggerDesc.put("PLAYER_DESPAWN","Player leaves area");
        triggerDesc.put("PLAYER_SPAWN","Player enters area");
        triggerDesc.put("STAT","Skill LEVEL_UP / ABOVE / BELOW");
        triggerDesc.put("TICK","Every tick (light visuals only)");
        triggerDesc.put("TARGET","Current combat target (changes / expiry)");
        triggerDesc.put("VARBIT","Varbit changes to specific value");
        triggerDesc.put("VARPLAYER","Varplayer changes to specific value");
        triggerDesc.put("WIDGET","Widget group[:child] loaded");
        java.util.List<String> triggerNames = new java.util.ArrayList<>(triggerDesc.keySet());
        java.util.Collections.sort(triggerNames, String.CASE_INSENSITIVE_ORDER);
        StringBuilder triggerHtml = new StringBuilder();
        for (String n : triggerNames) triggerHtml.append(li(n, triggerDesc.get(n)));
        // Command descriptions mapping
        java.util.Map<String,String> cmdDesc = new java.util.HashMap<>();
        cmdDesc.put("HIGHLIGHT_*","Outline/Tile/Hull/Minimap (<="
                +"0 persistent)");
        cmdDesc.put("HIGHLIGHT_SCREEN","Full client border highlight (<="
                +"0 persistent)");
        cmdDesc.put("IMG_OVER|CENTER|UNDER <id>","Overhead image (item id, negative = sprite id) with optional target");
        cmdDesc.put("INFOBOX <id> [tt]","Info box icon(s)");
        cmdDesc.put("NOTIFY <text>","In-game chat message");
        cmdDesc.put("OVERLAY_TEXT <text>","HUD overlay box");
        cmdDesc.put("SCREENSHOT [text]","Send screenshot (optional caption)");
        cmdDesc.put("SLEEP <ms>","Pause sequence ms");
        cmdDesc.put("STOP","Stop all active sequences");
        cmdDesc.put("STOP_RULE [id]","Stop visuals/sequences for one rule (omit id = current)");
        cmdDesc.put("TEXT_OVER|CENTER|UNDER","Overhead text (<="
                +"0 persistent) with optional target");
        cmdDesc.put("TICK <n>","Delay n game ticks");
        cmdDesc.put("WEBHOOK <text>","Send message to webhook");
        java.util.List<String> cmdNames = new java.util.ArrayList<>(cmdDesc.keySet());
        java.util.Collections.sort(cmdNames, String.CASE_INSENSITIVE_ORDER);
        StringBuilder cmdTable = new StringBuilder();
        cmdTable.append(headRow("Command","Description"));
        for (String c : cmdNames) cmdTable.append(row(c, cmdDesc.get(c)));
        String css = "<style>body{font-family:Segoe UI,Arial,sans-serif;font-size:12px;color:#E4E6EB;line-height:1.42;margin:0;padding:0;}h1{font-size:18px;margin:0 0 8px 0;color:#ffffff;}h2{font-size:14px;margin:18px 0 6px 0;color:#ffffff;border-bottom:1px solid #444;padding-bottom:2px;}table{border-collapse:collapse;width:100%;}th,td{padding:3px 4px;font-size:11px;vertical-align:top;}tr:nth-child(odd){background:#292c33;}code,pre{background:#1e1f24;color:#C6D0E3;border:1px solid #3a3d44;padding:4px 6px;border-radius:4px;font-family:Consolas,monospace;font-size:11px;}pre{overflow-x:auto;}ul{margin:4px 0 8px 18px;padding:0;}li{margin:2px 0;}.dim{color:#9aa0ac;font-size:11px;}</style>";
        return "<html>"+css+"<body>" +
                "<h1>KP Webhook – Info</h1>" +
                "<h2>Triggers</h2><ul>" + triggerHtml + "</ul>" +
                "<h2>Commands</h2><table>" + cmdTable + "</table>" +
                "<h2>Tokens</h2><ul>" +
                li("${player}","Local player name")+
                li("${stat}","Skill name")+
                li("${current}/${value}","Boosted / threshold")+
                li("${widgetGroup}/${widgetChild}","Widget IDs")+
                li("${otherPlayer}/${otherCombat}","Other player name / combat")+
                li("${npcName}/${npcId}","Matched NPC name / id")+
                li("${TARGET}","Current target name (player or NPC, blank if none)")+
                li("${WORLD}","World number")+
                li("${STAT}/${CURRENT_STAT}","Real / boosted skill level")+
                li("${message}/${messageTypeId}","Chat message & type id")+
                li("IMG id","Positive = item id, negative = sprite id (IMG_* commands)")+ // fixed syntax
                li("Skill tokens","Real & CURRENT_ for: "+skillTokens)+
                li("HITSPLAT_SELF / HITSPLAT_TARGET","Latest matching hitsplat number (blank if none)")+ // added explicit hitsplat tokens
                "</ul><div class='dim'>Varianter: ${NAME}, $NAME, {{NAME}}, legacy $SKILL.</div>"+
                "<h2>Persistens <=0</h2><ul>"+
                li("HIGHLIGHT_*","Upsert per rule (persistent if duration <=0)")+
                li("TEXT_*","Persistent if duration <=0 (keyed per rule+pos+target)")+
                li("IMG_*","Persistent if duration <=0 (overhead image stays until STOP / rule reset)")+
                li("INFOBOX","Normal duration unless re-triggered")+
                "</ul><h2>Example</h2><pre>NPC_SPAWN goblin\nHIGHLIGHT_OUTLINE TARGET\nTEXT_OVER TARGET $TARGET\n</pre></body></html>";
    }

    private String headRow(String a, String b){ return "<tr style='background:#333'><th style='text-align:left'>"+a+"</th><th style='text-align:left'>"+b+"</th></tr>"; }
    private String row(String a, String b){ return "<tr><td><code>"+escapeHtml(a)+"</code></td><td>"+escapeHtml(b)+"</td></tr>"; }
    private String li(String head, String desc){ return "<li><b>"+escapeHtml(head)+"</b>: "+escapeHtml(desc)+"</li>"; }
    private String escapeHtml(String s){ if (s==null) return ""; return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }

    private JPanel buildButtons()
    {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8,8));
        JButton helpBtn = new JButton("Help");
        JButton saveBtn = new JButton("Save");
        JButton cancelBtn = new JButton("Cancel");
        helpBtn.addActionListener(e -> showHelp());
        saveBtn.addActionListener(e -> onSave());
        cancelBtn.addActionListener(e -> { result = null; dispose(); });
        buttons.add(helpBtn);
        buttons.add(saveBtn);
        buttons.add(cancelBtn);
        return buttons;
    }

    /* ================= Populate ================= */
    private void populate(KPWebhookPreset r)
    {
        if (r == null)
        {
            // For nye scripts: tom kommando-boks (info finnes i Info-fanen)
            commandsArea.setText("");
            titleField.setText("");
            triggerTypeBox.setSelectedIndex(0);
            useDefaultWebhookBox.setSelected(false);
            customWebhookField.setText("");
            activeBox.setSelected(true); // fixed
            if (categoryField != null) categoryField.setText("");
            updateTriggerVisibility();
            updateWebhookEnable();
            return;
        }

        titleField.setText(r.getTitle());
        if (categoryField != null) categoryField.setText(r.getCategory()!=null? r.getCategory():"");
        if (r.getTriggerType()!=null)
            triggerTypeBox.setSelectedItem(r.getTriggerType().name());
        else
            triggerTypeBox.setSelectedIndex(0);

        useDefaultWebhookBox.setSelected(r.isUseDefaultWebhook());
        customWebhookField.setText(r.getWebhookUrl()!=null ? r.getWebhookUrl() : "");

        if (r.getCommands()==null || r.getCommands().trim().isEmpty())
            commandsArea.setText(DEFAULT_COMMANDS_TEXT);
        else
            commandsArea.setText(r.getCommands());

        activeBox.setSelected(r.isActive());

        updateTriggerVisibility();
        updateWebhookEnable();

        if (r.getTriggerType()== KPWebhookPreset.TriggerType.STAT && r.getStatConfig()!=null)
        {
            statSkillBox.setSelectedItem(r.getStatConfig().getSkill().name());
            statModeBox.setSelectedItem(r.getStatConfig().getMode());
            if (r.getStatConfig().getMode()!= KPWebhookPreset.StatMode.LEVEL_UP)
                levelSpinner.setValue(r.getStatConfig().getThreshold());
            updateStatEnable();
        }
        if (r.getTriggerType()== KPWebhookPreset.TriggerType.WIDGET && r.getWidgetConfig()!=null)
        {
            if (r.getWidgetConfig().getChildId()!=null)
                widgetField.setText(r.getWidgetConfig().getGroupId()+":"+r.getWidgetConfig().getChildId());
            else
                widgetField.setText(String.valueOf(r.getWidgetConfig().getGroupId()));
        }
        if (r.getTriggerType()== KPWebhookPreset.TriggerType.PLAYER_SPAWN || r.getTriggerType()== KPWebhookPreset.TriggerType.PLAYER_DESPAWN)
        {
            KPWebhookPreset.PlayerConfig pc = r.getPlayerConfig();
            if (pc!=null)
            {
                if (pc.isAll()) playerAllRadio.setSelected(true);
                else if (pc.getNames()!=null && !pc.getNames().isEmpty()) {
                    playerNameRadio.setSelected(true);
                    playerNameField.setText(String.join(",", pc.getNames()));
                }
                else if (pc.getName()!=null && !pc.getName().isBlank())
                {
                    playerNameRadio.setSelected(true);
                    playerNameField.setText(pc.getName());
                }
                else if (pc.getCombatRange()!=null)
                {
                    playerCombatRadio.setSelected(true);
                    playerCombatSpinner.setValue(pc.getCombatRange());
                }
            }
        }
        if (r.getTriggerType()== KPWebhookPreset.TriggerType.ANIMATION_SELF && r.getAnimationConfig()!=null)
        {
            KPWebhookPreset.AnimationConfig c = r.getAnimationConfig();
            java.util.List<Integer> list = c.getAnimationIds();
            if (list!=null && !list.isEmpty()) animationIdField.setText(listToString(list));
            else if (c.getAnimationId()!=null) animationIdField.setText(String.valueOf(c.getAnimationId()));
        }
        else if (r.getTriggerType()== KPWebhookPreset.TriggerType.ANIMATION_TARGET && r.getAnimationConfig()!=null) {
            KPWebhookPreset.AnimationConfig c = r.getAnimationConfig();
            java.util.List<Integer> list = c.getAnimationIds();
            if (list!=null && !list.isEmpty()) animationIdField.setText(listToString(list));
            else if (c.getAnimationId()!=null) animationIdField.setText(String.valueOf(c.getAnimationId()));
        }
        if (r.getTriggerType()== KPWebhookPreset.TriggerType.GRAPHIC_SELF && r.getGraphicConfig()!=null) {
            KPWebhookPreset.GraphicConfig c = r.getGraphicConfig();
            if (c.getGraphicIds()!=null && !c.getGraphicIds().isEmpty()) graphicIdField.setText(listToString(c.getGraphicIds()));
            else if (c.getGraphicId()!=null) graphicIdField.setText(String.valueOf(c.getGraphicId()));
        } else if (r.getTriggerType()== KPWebhookPreset.TriggerType.GRAPHIC_TARGET && r.getGraphicConfig()!=null) {
            KPWebhookPreset.GraphicConfig c = r.getGraphicConfig();
            if (c.getGraphicIds()!=null && !c.getGraphicIds().isEmpty()) graphicIdField.setText(listToString(c.getGraphicIds()));
            else if (c.getGraphicId()!=null) graphicIdField.setText(String.valueOf(c.getGraphicId()));
        }
        if (r.getTriggerType()== KPWebhookPreset.TriggerType.PROJECTILE_SELF && r.getProjectileConfig()!=null) {
            KPWebhookPreset.ProjectileConfig c = r.getProjectileConfig();
            if (c.getProjectileIds()!=null && !c.getProjectileIds().isEmpty()) projectileIdField.setText(listToString(c.getProjectileIds()));
            else if (c.getProjectileId()!=null) projectileIdField.setText(String.valueOf(c.getProjectileId()));
        } else if (r.getTriggerType()== KPWebhookPreset.TriggerType.PROJECTILE_TARGET && r.getProjectileConfig()!=null) {
            KPWebhookPreset.ProjectileConfig c = r.getProjectileConfig();
            if (c.getProjectileIds()!=null && !c.getProjectileIds().isEmpty()) projectileIdField.setText(listToString(c.getProjectileIds()));
            else if (c.getProjectileId()!=null) projectileIdField.setText(String.valueOf(c.getProjectileId()));
        } else if (r.getTriggerType()== KPWebhookPreset.TriggerType.PROJECTILE_ANY && r.getProjectileConfig()!=null) {
            KPWebhookPreset.ProjectileConfig c = r.getProjectileConfig();
            if (c.getProjectileIds()!=null && !c.getProjectileIds().isEmpty()) projectileIdField.setText(listToString(c.getProjectileIds()));
            else if (c.getProjectileId()!=null) projectileIdField.setText(String.valueOf(c.getProjectileId()));
        }
        if ((r.getTriggerType()== KPWebhookPreset.TriggerType.HITSPLAT_SELF || r.getTriggerType()== KPWebhookPreset.TriggerType.HITSPLAT_TARGET)) {
            KPWebhookPreset.HitsplatConfig hc = r.getHitsplatConfig();
            if (hc == null) {
                hc = KPWebhookPreset.HitsplatConfig.builder()
                        .mode(KPWebhookPreset.HitsplatConfig.Mode.GREATER_EQUAL)
                        .value(1)
                        .build();
                r.setHitsplatConfig(hc);
            }
            String dir = (hc.getMode()== KPWebhookPreset.HitsplatConfig.Mode.LESS || hc.getMode()== KPWebhookPreset.HitsplatConfig.Mode.LESS_EQUAL)? "Below":"Above";
            hitsplatModeBox.setSelectedItem(dir);
            if (hc.getValue()!=null) hitsplatValueSpinner.setValue(hc.getValue());
        }
        if (r.getTriggerType() == KPWebhookPreset.TriggerType.MESSAGE && r.getMessageConfig() != null)
        {
            if (r.getMessageConfig().getMessageId() != null)
                messageIdSpinner.setValue(r.getMessageConfig().getMessageId());
            if (r.getMessageConfig().getMessageText() != null)
                messageTextField.setText(r.getMessageConfig().getMessageText());
        }
        if (r.getTriggerType() == KPWebhookPreset.TriggerType.VARBIT && r.getVarbitConfig() != null)
        {
            KPWebhookPreset.VarbitConfig cfg = r.getVarbitConfig();
            java.util.List<Integer> ids = cfg.getVarbitIds();
            if (varbitIdsField!=null) {
                if (ids!=null && !ids.isEmpty()) varbitIdsField.setText(listToString(ids));
                else if (cfg.getVarbitId()!=null) varbitIdsField.setText(String.valueOf(cfg.getVarbitId()));
            }
            varbitValueSpinner.setValue(cfg.getValue());
        }
        if (r.getTriggerType() == KPWebhookPreset.TriggerType.VARPLAYER && r.getVarplayerConfig() != null)
        {
            KPWebhookPreset.VarplayerConfig cfg = r.getVarplayerConfig();
            java.util.List<Integer> ids = cfg.getVarplayerIds();
            if (varplayerIdsField!=null) {
                if (ids!=null && !ids.isEmpty()) varplayerIdsField.setText(listToString(ids));
                else if (cfg.getVarplayerId()!=null) varplayerIdsField.setText(String.valueOf(cfg.getVarplayerId()));
            }
            varplayerValueSpinner.setValue(cfg.getValue());
        }
        if (r.getTriggerType()== KPWebhookPreset.TriggerType.NPC_SPAWN || r.getTriggerType()== KPWebhookPreset.TriggerType.NPC_DESPAWN) {
            if (r.getNpcConfig()!=null) {
                if (npcListField!=null) npcListField.setText(r.getNpcConfig().getRawList()!=null? r.getNpcConfig().getRawList():"");
            }
        }
    }

    /* ================= Save ================= */
    private void onSave()
    {
        String title = titleField.getText().trim();
        if (title.isEmpty()) { JOptionPane.showMessageDialog(this, "Title cannot be empty"); return; }
        String category = categoryField!=null? categoryField.getText().trim():""; // optional
        String cmds = commandsArea.getText().trim();
        if (cmds.isEmpty()) { JOptionPane.showMessageDialog(this, "Commands cannot be empty"); return; }

        String trigSel = (String) triggerTypeBox.getSelectedItem();
        if (trigSel == null || TRIGGER_PLACEHOLDER.equals(trigSel)) { JOptionPane.showMessageDialog(this, "Please select a trigger."); return; }

        KPWebhookPreset.TriggerType trig = KPWebhookPreset.TriggerType.valueOf(trigSel);
        KPWebhookPreset.StatConfig statCfg = null;
        KPWebhookPreset.WidgetConfig widgetCfg = null;
        KPWebhookPreset.PlayerConfig playerCfg = null;
        KPWebhookPreset.AnimationConfig animationCfg = null;
        KPWebhookPreset.GraphicConfig graphicCfg = null; // new
        KPWebhookPreset.HitsplatConfig hitsplatCfg = null; // new
        KPWebhookPreset.MessageConfig messageCfg = null;
        KPWebhookPreset.VarbitConfig varbitCfg = null;
        KPWebhookPreset.VarplayerConfig varplayerCfg = null;
        KPWebhookPreset.NpcConfig npcCfg = null; // new
        KPWebhookPreset.ProjectileConfig projectileCfg = null; // new

        if (trig == KPWebhookPreset.TriggerType.STAT)
        {
            String skillSel = (String) statSkillBox.getSelectedItem();
            if (skillSel == null || SKILL_PLACEHOLDER.equals(skillSel)) { JOptionPane.showMessageDialog(this, "Select a skill."); return; }
            KPWebhookPreset.StatMode mode = (KPWebhookPreset.StatMode) statModeBox.getSelectedItem();
            if (mode == null) { JOptionPane.showMessageDialog(this, "Select a mode."); return; }
            int threshold = 0;
            if (mode == KPWebhookPreset.StatMode.ABOVE || mode == KPWebhookPreset.StatMode.BELOW)
                threshold = (Integer) levelSpinner.getValue();
            statCfg = KPWebhookPreset.StatConfig.builder()
                    .skill(Skill.valueOf(skillSel))
                    .mode(mode)
                    .threshold(threshold)
                    .build();
        }
        else if (trig == KPWebhookPreset.TriggerType.WIDGET)
        {
            String txt = widgetField.getText().trim();
            if (txt.isEmpty()) { JOptionPane.showMessageDialog(this, "Enter widget group or group:child."); return; }
            int group; Integer child = null;
            try
            {
                if (txt.contains(":"))
                {
                    String[] parts = txt.split(":",2);
                    group = Integer.parseInt(parts[0].trim());
                    child  = Integer.parseInt(parts[1].trim());
                }
                else group = Integer.parseInt(txt);
            }
            catch (NumberFormatException ex)
            { JOptionPane.showMessageDialog(this, "Invalid widget format. Use group or group:child."); return; }
            widgetCfg = KPWebhookPreset.WidgetConfig.builder().groupId(group).childId(child).build();
        }
        else if (trig == KPWebhookPreset.TriggerType.PLAYER_SPAWN || trig == KPWebhookPreset.TriggerType.PLAYER_DESPAWN)
        {
            if (playerAllRadio.isSelected())
            {
                playerCfg = KPWebhookPreset.PlayerConfig.builder().all(true).build();
            }
            else if (playerNameRadio.isSelected())
            {
                String rawNames = playerNameField.getText().trim();
                if (rawNames.isEmpty()) { JOptionPane.showMessageDialog(this, "Player name(s) cannot be empty"); return; }
                java.util.List<String> names = parseNameList(rawNames);
                playerCfg = KPWebhookPreset.PlayerConfig.builder().all(false)
                        .names(!names.isEmpty()?names:null)
                        .name(names.size()==1?names.get(0):null)
                        .build();
            }
            else if (playerCombatRadio.isSelected())
            {
                int range = (Integer) playerCombatSpinner.getValue();
                playerCfg = KPWebhookPreset.PlayerConfig.builder().all(false).combatRange(range).build();
            }
            else
            {
                JOptionPane.showMessageDialog(this, "Select player option (ALL, Names or +/- range)"); return;
            }
        }
        else if (trig == KPWebhookPreset.TriggerType.ANIMATION_SELF || trig == KPWebhookPreset.TriggerType.ANIMATION_TARGET || trig == KPWebhookPreset.TriggerType.ANIMATION_ANY)
        {
            String raw = animationIdField.getText()!=null? animationIdField.getText().trim():"";
            if (trig != KPWebhookPreset.TriggerType.ANIMATION_ANY && raw.isEmpty()) { JOptionPane.showMessageDialog(this, "Animation ID(s) required for "+trig.name()); return; }
            java.util.List<Integer> ids = parseIntList(raw);
            if (trig != KPWebhookPreset.TriggerType.ANIMATION_ANY && ids.isEmpty()) { JOptionPane.showMessageDialog(this, "Minst én gyldig animasjon ID påkrevd"); return; }
            animationCfg = KPWebhookPreset.AnimationConfig.builder()
                    .animationIds(ids.isEmpty()?null:ids)
                    .animationId(ids.size()==1?ids.get(0):null)
                    .build();
        }
        else if (trig == KPWebhookPreset.TriggerType.GRAPHIC_SELF || trig == KPWebhookPreset.TriggerType.GRAPHIC_TARGET) {
            String raw = graphicIdField.getText()!=null? graphicIdField.getText().trim():"";
            if (raw.isEmpty()) { JOptionPane.showMessageDialog(this, "Graphic ID(s) required"); return; }
            java.util.List<Integer> ids = parseIntList(raw);
            if (ids.isEmpty()) { JOptionPane.showMessageDialog(this, "Minst én gyldig graphic ID påkrevd"); return; }
            graphicCfg = KPWebhookPreset.GraphicConfig.builder()
                    .graphicIds(ids)
                    .graphicId(ids.size()==1?ids.get(0):null)
                    .build();
        }
        else if (trig == KPWebhookPreset.TriggerType.PROJECTILE_SELF || trig == KPWebhookPreset.TriggerType.PROJECTILE_TARGET || trig == KPWebhookPreset.TriggerType.PROJECTILE_ANY) {
            String raw = projectileIdField.getText()!=null? projectileIdField.getText().trim():"";
            java.util.List<Integer> ids = parseIntList(raw);
            if (trig != KPWebhookPreset.TriggerType.PROJECTILE_ANY && ids.isEmpty()) { JOptionPane.showMessageDialog(this, "Minst én projectile ID påkrevd for "+trig.name()); return; }
            projectileCfg = KPWebhookPreset.ProjectileConfig.builder()
                    .projectileIds(ids.isEmpty()?null:ids)
                    .projectileId(ids.size()==1?ids.get(0):null)
                    .build();
        }
        else if (trig == KPWebhookPreset.TriggerType.HITSPLAT_SELF || trig == KPWebhookPreset.TriggerType.HITSPLAT_TARGET) {
            Integer val = (Integer) hitsplatValueSpinner.getValue();
            if (val == null) { JOptionPane.showMessageDialog(this, "Enter hitsplat value"); return; }
            String sel = (String) hitsplatModeBox.getSelectedItem();
            KPWebhookPreset.HitsplatConfig.Mode mode = "Below".equals(sel)? KPWebhookPreset.HitsplatConfig.Mode.LESS_EQUAL : KPWebhookPreset.HitsplatConfig.Mode.GREATER_EQUAL;
            hitsplatCfg = KPWebhookPreset.HitsplatConfig.builder().mode(mode).value(val).build();
        }
        else if (trig == KPWebhookPreset.TriggerType.MESSAGE)
        {
            int messageId = 0;
            try { messageId = Integer.parseInt(messageIdSpinner.getValue().toString().trim()); }
            catch (NumberFormatException e) { JOptionPane.showMessageDialog(this, "Invalid message ID"); return; }
            String msgText = messageTextField.getText()!=null? messageTextField.getText().trim():"";
            if (msgText.equals("*")) msgText = "";
            messageCfg = KPWebhookPreset.MessageConfig.builder().messageId(messageId).messageText(msgText.isEmpty()?null:msgText).build();
        }
        else if (trig == KPWebhookPreset.TriggerType.VARBIT)
        {
            String raw = varbitIdsField!=null? varbitIdsField.getText().trim():"";
            java.util.List<Integer> ids = parseIntList(raw);
            if (ids.isEmpty()) { JOptionPane.showMessageDialog(this, "Varbit ID(s) required"); return; }
            int varbitValue = (Integer) varbitValueSpinner.getValue();
            varbitCfg = KPWebhookPreset.VarbitConfig.builder()
                    .varbitIds(ids)
                    .varbitId(ids.size()==1?ids.get(0):null)
                    .value(varbitValue)
                    .build();
        }
        else if (trig == KPWebhookPreset.TriggerType.VARPLAYER)
        {
            String raw = varplayerIdsField!=null? varplayerIdsField.getText().trim():"";
            java.util.List<Integer> ids = parseIntList(raw);
            if (ids.isEmpty()) { JOptionPane.showMessageDialog(this, "Varplayer ID(s) required"); return; }
            int varplayerValue = (Integer) varplayerValueSpinner.getValue();
            varplayerCfg = KPWebhookPreset.VarplayerConfig.builder()
                    .varplayerIds(ids)
                    .varplayerId(ids.size()==1?ids.get(0):null)
                    .value(varplayerValue)
                    .build();
        }
        else if (trig == KPWebhookPreset.TriggerType.NPC_SPAWN || trig == KPWebhookPreset.TriggerType.NPC_DESPAWN) {
            String raw = npcListField.getText().trim();
            if (raw.isEmpty()) { JOptionPane.showMessageDialog(this, "NPC list cannot be empty"); return; }
            npcCfg = parseNpcList(raw);
            if ((npcCfg.getNpcIds()==null || npcCfg.getNpcIds().isEmpty()) && (npcCfg.getNpcNames()==null || npcCfg.getNpcNames().isEmpty())) {
                JOptionPane.showMessageDialog(this, "NPC list invalid (no valid ids or names)"); return; }
        }

        boolean useDef = useDefaultWebhookBox.isSelected();
        String custom = customWebhookField.getText(); if (custom == null) custom = "";

        if (commandsNeedWebhook(cmds))
        {
            String eff = effectiveWebhookForSave(useDef, custom);
            if (eff == null)
            {
                JOptionPane.showMessageDialog(this,
                        "WEBHOOK or SCREENSHOT command found but no default or custom webhook is configured.",
                        "Validation",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        KPWebhookPreset.KPWebhookPresetBuilder b = KPWebhookPreset.builder()
                .id(existing != null ? existing.getId() : -1)
                .title(title)
                .category(category.isEmpty()?null:category)
                .triggerType(trig)
                .statConfig(statCfg)
                .widgetConfig(widgetCfg)
                .webhookUrl(custom.trim())
                .useDefaultWebhook(useDef)
                .commands(cmds)
                .active(activeBox.isSelected())
                .hlOutlineDuration(outlinePanel.getDuration())
                .hlOutlineBlink(outlinePanel.isBlink())
                .hlOutlineColor(outlinePanel.getColorHex())
                .hlOutlineWidth(outlinePanel.getStoredWidth())
                .hlTileDuration(tilePanel.getDuration())
                .hlTileBlink(tilePanel.isBlink())
                .hlTileColor(tilePanel.getColorHex())
                .hlTileWidth(tilePanel.getStoredWidth())
                .hlHullDuration(hullPanel.getDuration())
                .hlHullBlink(hullPanel.isBlink())
                .hlHullColor(hullPanel.getColorHex())
                .hlHullWidth(hullPanel.getStoredWidth())
                .hlMinimapDuration(minimapPanel.getDuration())
                .hlMinimapBlink(minimapPanel.isBlink())
                .hlMinimapColor(minimapPanel.getColorHex())
                .hlMinimapWidth(minimapPanel.getStoredWidth())
                .hlScreenDuration(screenPanel.getDuration())
                .hlScreenBlink(screenPanel.isBlink())
                .hlScreenColor(screenPanel.getColorHex())
                .hlScreenWidth(screenPanel.getStoredWidth())
                .textOverColor(textOverPanel.getColorHex())
                .textOverBlink(textOverPanel.isBlink())
                .textOverSize(textOverPanel.getSizeValue())
                .textOverDuration(textOverPanel.getDuration())
                .textOverBold(textOverPanel.isBold())
                .textOverItalic(textOverPanel.isItalic())
                .textOverUnderline(textOverPanel.isUnderline())
                .textCenterColor(textCenterPanel.getColorHex())
                .textCenterBlink(textCenterPanel.isBlink())
                .textCenterSize(textCenterPanel.getSizeValue())
                .textCenterDuration(textCenterPanel.getDuration())
                .textCenterBold(textCenterPanel.isBold())
                .textCenterItalic(textCenterPanel.isItalic())
                .textCenterUnderline(textCenterPanel.isUnderline())
                .textUnderColor(textUnderPanel.getColorHex())
                .textUnderBlink(textUnderPanel.isBlink())
                .textUnderSize(textUnderPanel.getSizeValue())
                .textUnderDuration(textUnderPanel.getDuration())
                .textUnderBold(textUnderPanel.isBold())
                .textUnderItalic(textUnderPanel.isItalic())
                .textUnderUnderline(textUnderPanel.isUnderline())
                .overlayTextDuration(overlayTextPanel.getDuration())
                .overlayTextColor(overlayTextPanel.getColorHex())
                .overlayTextSize(overlayTextPanel.getSizeValue())
                .infoboxDuration(infoboxPanel.getDuration())
                .imgDuration(imgPanel.getDuration())
                .imgBlink(imgPanel.isBlink())
                .npcConfig(npcCfg)
                .playerConfig(playerCfg)
                .animationConfig(animationCfg)
                .graphicConfig(graphicCfg)
                .messageConfig(messageCfg)
                .varbitConfig(varbitCfg)
                .varplayerConfig(varplayerCfg)
                .hitsplatConfig(hitsplatCfg)
                .projectileConfig(projectileCfg);

        result = b.build();
        dispose();
    }

    private boolean commandsNeedWebhook(String text)
    {
        for (String line : text.split("\r?\n"))
        {
            String l = line.trim().toUpperCase(Locale.ROOT);
            if (l.startsWith("WEBHOOK ") || l.startsWith("SCREENSHOT")) return true;
        }
        return false;
    }

    private String effectiveWebhookForSave(boolean useDef, String custom)
    {
        if (useDef)
        {
            String def = plugin.getDefaultWebhook();
            if (!def.isBlank()) return def;
        }
        if (!custom.trim().isEmpty()) return custom.trim();
        return null;
    }

    /* ================= Visibility ================= */
    private void updateSettingsVisibility() { }

    private void showHelp()
    {
        String msg =
                "Commands: NOTIFY, WEBHOOK, SCREENSHOT, HIGHLIGHT_*, TEXT_*\n" +
                "Use the Settings tab to configure highlight & text visuals.\n" +
                "Blink toggles visibility each tick.\n" +
                "Color: click the swatch or 'Custom' for custom #RRGGBB.";
        JOptionPane.showMessageDialog(this, msg, "Help", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateHeader(){
        String name = titleField!=null? titleField.getText().trim():"";
        if (name.isEmpty()) setTitle("Preset"); else setTitle(name);
    }

    private abstract static class SimpleDoc implements DocumentListener
    { protected abstract void changed(); @Override public void insertUpdate(DocumentEvent e){ changed(); } @Override public void removeUpdate(DocumentEvent e){ changed(); } @Override public void changedUpdate(DocumentEvent e){ changed(); } }

    /** Highlight panel */
    private static class HighlightCategoryPanel extends JPanel
    {
        private final JSpinner durationSpinner;
        private final JCheckBox blinkBox;
        private final ColorPreview colorPreview;
        private final int storedWidth;
        private Color selectedColor;
        HighlightCategoryPanel(int duration, String colorHex, boolean blink, int storedWidth, String title)
        {
            super(new GridBagLayout());
            setBorder(new TitledBorder(title));
            setOpaque(false);
            this.storedWidth = storedWidth<=0?2:storedWidth;
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3,4,3,4);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            c.weightx = 1; int y=0;
            JLabel durLbl = new JLabel("Duration"); durLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; c.weightx=0; add(durLbl, c);
            durationSpinner = new JSpinner(new SpinnerNumberModel(duration,0,5000,1));
            Dimension sd = durationSpinner.getPreferredSize();
            durationSpinner.setPreferredSize(new Dimension(60, sd.height));
            JPanel durRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0)); durRow.setOpaque(false);
            durRow.add(durationSpinner); durRow.add(new JLabel("ticks"));
            c.gridx=1; c.weightx=1; add(durRow, c); y++;
            JLabel blinkLbl = new JLabel("Blink"); blinkLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; c.weightx=0; add(blinkLbl, c);
            blinkBox = new JCheckBox(); blinkBox.setSelected(blink); blinkBox.setMargin(new Insets(0,0,0,0)); blinkBox.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=1; add(blinkBox,c); y++;
            JLabel colorLbl = new JLabel("Color"); colorLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; c.weightx=0; add(colorLbl, c);
            selectedColor = parse(colorHex, Color.YELLOW);
            colorPreview = new ColorPreview(selectedColor, this::setSelectedColor);
            JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0)); colorRow.setOpaque(false);
            JButton customBtn = new JButton("Custom"); customBtn.setMargin(new Insets(2,6,2,6)); customBtn.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            customBtn.addActionListener(e -> { Color picked = JColorChooser.showDialog(this, "Choose color", selectedColor); if (picked != null) setSelectedColor(picked); });
            colorRow.add(colorPreview); colorRow.add(customBtn);
            c.gridx=1; add(colorRow, c);
            setPreferredSize(new Dimension(COMPACT_PANEL_WIDTH-20, getPreferredSize().height));
            setMaximumSize(new Dimension(COMPACT_PANEL_WIDTH-20, Integer.MAX_VALUE));
        }
        private void setSelectedColor(Color c){ if (c!=null){ selectedColor=c; colorPreview.setColor(c);} }
        private static Color parse(String hex, Color def){ try{ if(hex==null) return def; String h=hex.trim(); if(!h.startsWith("#")) h="#"+h; if(h.length()==7){return new Color(Integer.parseInt(h.substring(1,3),16),Integer.parseInt(h.substring(3,5),16),Integer.parseInt(h.substring(5,7),16));}}catch(Exception ignored){} return def; }
        int getDuration(){ return (Integer)durationSpinner.getValue(); }
        boolean isBlink(){ return blinkBox.isSelected(); }
        String getColorHex(){ return String.format("#%02X%02X%02X", selectedColor.getRed(), selectedColor.getGreen(), selectedColor.getBlue()); }
        int getStoredWidth(){ return storedWidth; }
    }

    private static class TextCategoryPanel extends JPanel
    {
        private final JSpinner durationSpinner;
        private final JSpinner sizeSpinner;
        private final JCheckBox blinkBox;
        private final JCheckBox boldBox;
        private final JCheckBox italicBox;
        private final JCheckBox underlineBox;
        private final ColorPreview colorPreview;
        private Color selectedColor;
        TextCategoryPanel(int duration, int size, String colorHex, boolean blink, boolean bold, boolean italic, boolean underline, String title)
        {
            super(new GridBagLayout());
            setBorder(new TitledBorder(title));
            setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3,4,3,4);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            c.weightx = 1; int y=0;
            JLabel durLbl = new JLabel("Duration"); durLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; add(durLbl, c);
            durationSpinner = new JSpinner(new SpinnerNumberModel(duration,0,5000,1));
            Dimension ds = durationSpinner.getPreferredSize();
            durationSpinner.setPreferredSize(new Dimension(60, ds.height));
            JPanel durRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0)); durRow.setOpaque(false); durRow.add(durationSpinner); durRow.add(new JLabel("ticks"));
            c.gridx=1; add(durRow, c); y++;
            JLabel sizeLbl = new JLabel("Size"); sizeLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; add(sizeLbl, c);
            sizeSpinner = new JSpinner(new SpinnerNumberModel(size,8,72,1));
            Dimension ss = sizeSpinner.getPreferredSize();
            sizeSpinner.setPreferredSize(new Dimension(60, ss.height));
            c.gridx=1; add(sizeSpinner, c); y++;
            JLabel blinkOnlyLbl = new JLabel("Blink"); blinkOnlyLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; add(blinkOnlyLbl, c);
            blinkBox = new JCheckBox(); blinkBox.setMargin(new Insets(0,0,0,0)); blinkBox.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE)); blinkBox.setSelected(blink);
            c.gridx=1; add(blinkBox, c); y++;
            JLabel styleLbl = new JLabel("Style"); styleLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            JPanel styleRow = new JPanel(new FlowLayout(FlowLayout.LEFT,12,0)); styleRow.setOpaque(false);
            boldBox = new JCheckBox("B", bold); boldBox.setToolTipText("Bold"); boldBox.setMargin(new Insets(0,0,0,0));
            italicBox = new JCheckBox("I", italic); italicBox.setToolTipText("Italic"); italicBox.setMargin(new Insets(0,0,0,0));
            underlineBox = new JCheckBox("U", underline); underlineBox.setToolTipText("Underline"); underlineBox.setMargin(new Insets(0,0,0,0));
            for (JCheckBox cb : new JCheckBox[]{boldBox,italicBox,underlineBox}) cb.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            styleRow.add(boldBox); styleRow.add(italicBox); styleRow.add(underlineBox);
            c.gridx=0; c.gridy=y; add(styleLbl, c); c.gridx=1; add(styleRow, c); y++;
            JLabel colorLbl = new JLabel("Color"); colorLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; add(colorLbl, c);
            selectedColor = HighlightCategoryPanel.parse(colorHex, Color.WHITE);
            colorPreview = new ColorPreview(selectedColor, this::setSelectedColor);
            JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0)); colorRow.setOpaque(false);
            JButton customBtn = new JButton("Custom"); customBtn.setMargin(new Insets(2,6,2,6)); customBtn.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            customBtn.addActionListener(e -> { Color picked = JColorChooser.showDialog(this, "Choose color", selectedColor); if (picked!=null) setSelectedColor(picked); });
            colorRow.add(colorPreview); colorRow.add(customBtn);
            c.gridx=1; add(colorRow, c);
            setPreferredSize(new Dimension(COMPACT_PANEL_WIDTH-20, getPreferredSize().height));
            setMaximumSize(new Dimension(COMPACT_PANEL_WIDTH-20, Integer.MAX_VALUE));
        }
        private void setSelectedColor(Color c){ if (c!=null){ selectedColor=c; colorPreview.setColor(c);} }
        int getDuration(){ return (Integer)durationSpinner.getValue(); }
        int getSizeValue(){ return (Integer)sizeSpinner.getValue(); }
        boolean isBlink(){ return blinkBox.isSelected(); }
        boolean isBold(){ return boldBox.isSelected(); }
        boolean isItalic(){ return italicBox.isSelected(); }
        boolean isUnderline(){ return underlineBox.isSelected(); }
        String getColorHex(){ return String.format("#%02X%02X%02X", selectedColor.getRed(), selectedColor.getGreen(), selectedColor.getBlue()); }
    }

    private static class ScreenshotCategoryPanel extends JPanel { /* omitted for brevity (unchanged) */ }

    private static class ColorPreview extends JPanel
    {
        private Color color;
        private final java.util.function.Consumer<Color> setter;
        private final Color[] palette = {
                new Color(0xFFFFFF), new Color(0xC0C0C0), new Color(0x808080), new Color(0x000000),
                new Color(0xFF0000), new Color(0xFFA500), new Color(0xFFFF00), new Color(0x00FF00),
                new Color(0x00FFFF), new Color(0x0000FF), new Color(0x8000FF), new Color(0xFF00FF)
        };
        ColorPreview(Color initial, java.util.function.Consumer<Color> setter)
        {
            this.color = initial; this.setter = setter;
            setPreferredSize(new Dimension(50,20)); setMaximumSize(new Dimension(50,20));
            setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY)); setToolTipText("Click to change color");
            addMouseListener(new java.awt.event.MouseAdapter(){ @Override public void mouseClicked(java.awt.event.MouseEvent e){ showPalette(e.getComponent(), e.getX(), e.getY()); }});
        }
        private void showPalette(Component parent, int x, int y)
        {
            JPopupMenu menu = new JPopupMenu(); JPanel grid = new JPanel(new GridLayout(3,4,2,2)); grid.setBorder(new EmptyBorder(4,4,4,4));
            for (Color c : palette)
            {
                JButton b = new JButton(); b.setBackground(c); b.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY)); b.setPreferredSize(new Dimension(24,20)); b.setToolTipText(String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
                b.addActionListener(ev -> { setColor(c); setter.accept(c); menu.setVisible(false); }); grid.add(b);
            }
            menu.add(grid); menu.show(parent, x, y);
        }
        void setColor(Color c){ this.color=c; repaint(); }
        @Override protected void paintComponent(Graphics g){ super.paintComponent(g); g.setColor(color); g.fillRect(0,0,getWidth(),getHeight()); }
    }

    private static class CollapsibleSection extends JPanel
    {
        private final JPanel content; private boolean expanded = false; private final JButton header; private final String title;
        CollapsibleSection(JPanel inner)
        {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setOpaque(false); this.content = inner; Border tmp = inner.getBorder(); this.title = tmp instanceof TitledBorder ? ((TitledBorder)tmp).getTitle() : "Section"; inner.setBorder(null);
            header = new JButton(labelText()); header.setFocusPainted(false); header.setHorizontalAlignment(SwingConstants.LEFT); header.setBorderPainted(false); header.setContentAreaFilled(false); header.setOpaque(false); header.setMargin(new Insets(0,4,0,4)); header.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f)); header.addActionListener(e -> toggle()); add(header); setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        private String labelText(){ return (expanded ? "[-] " : "[+] ") + title; }
        private void toggle()
        {
            expanded = !expanded; header.setText(labelText());
            if (expanded)
            {
                JPanel wrap = new JPanel(); wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS)); wrap.setOpaque(false); wrap.setBorder(new EmptyBorder(4,18,6,4)); content.setAlignmentX(Component.LEFT_ALIGNMENT); wrap.add(content); wrap.setMaximumSize(new Dimension(COMPACT_PANEL_WIDTH, wrap.getPreferredSize().height)); wrap.setPreferredSize(new Dimension(COMPACT_PANEL_WIDTH, wrap.getPreferredSize().height)); add(wrap);
            }
            else { while (getComponentCount() > 1) remove(1); }
            revalidate(); repaint();
        }
    }

    private String[] buildTriggerModel()
    {
        KPWebhookPreset.TriggerType[] types = KPWebhookPreset.TriggerType.values();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (KPWebhookPreset.TriggerType t : types) names.add(t.name());
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        String[] data = new String[names.size() + 1]; data[0] = TRIGGER_PLACEHOLDER; for (int i=0;i<names.size();i++) data[i+1] = names.get(i); return data;
    }

    private JPanel buildTriggerCards()
    {
        JPanel cards = new JPanel(new CardLayout());
        // STAT card
        statCard = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints(); g.insets = new Insets(4,4,4,4); g.fill = GridBagConstraints.HORIZONTAL; g.anchor = GridBagConstraints.WEST; g.weightx = 1; int y=0;
        g.gridx=0; g.gridy=y; g.weightx=0; statCard.add(new JLabel("Skill"), g);
        List<String> skills = new ArrayList<>(); skills.add(SKILL_PLACEHOLDER); for (Skill s : Skill.values()) skills.add(s.name()); java.util.Collections.sort(skills.subList(1, skills.size()), String.CASE_INSENSITIVE_ORDER);
        statSkillBox = new JComboBox<>(skills.toArray(new String[0])); g.gridx=1; g.weightx=1; statCard.add(statSkillBox, g); y++;
        g.gridx=0; g.gridy=y; g.weightx=0; statCard.add(new JLabel("Mode"), g); statModeBox = new JComboBox<>(KPWebhookPreset.StatMode.values()); g.gridx=1; g.weightx=1; statCard.add(statModeBox, g); y++;
        g.gridx=0; g.gridy=y; g.weightx=0; statCard.add(new JLabel("Threshold"), g); levelHolderCard = new JPanel(new CardLayout()); JPanel levelPanel = new JPanel(new BorderLayout()); levelSpinner = new JSpinner(new SpinnerNumberModel(1,1,200,1)); levelPanel.add(levelSpinner, BorderLayout.WEST); levelHolderCard.add(new JPanel(), LEVEL_CARD_EMPTY); levelHolderCard.add(levelPanel, LEVEL_CARD_VISIBLE); g.gridx=1; g.weightx=1; statCard.add(levelHolderCard, g); y++;
        statSkillBox.addActionListener(e -> updateStatEnable()); statModeBox.addActionListener(e -> updateStatEnable());
        // WIDGET card
        widgetCard = new JPanel(new GridBagLayout()); GridBagConstraints w = new GridBagConstraints(); w.insets = new Insets(4,4,4,4); w.fill = GridBagConstraints.HORIZONTAL; w.anchor=GridBagConstraints.WEST; w.weightx=1; int wy=0; w.gridx=0; w.gridy=wy; w.weightx=0; widgetCard.add(new JLabel("Widget (group[:child])"), w); widgetField = new JTextField(); w.gridx=1; w.weightx=1; widgetCard.add(widgetField, w); wy++;
        // PLAYER card
        playerCard = new JPanel(new GridBagLayout()); GridBagConstraints p = new GridBagConstraints(); p.insets = new Insets(4,4,4,4); p.fill=GridBagConstraints.HORIZONTAL; p.anchor=GridBagConstraints.WEST; p.weightx=1; int py=0; ButtonGroup grp = new ButtonGroup(); playerAllRadio = new JRadioButton("ALL players"); playerNameRadio = new JRadioButton("Specific name(s):"); playerCombatRadio = new JRadioButton("Combat level +/-"); grp.add(playerAllRadio); grp.add(playerNameRadio); grp.add(playerCombatRadio); playerAllRadio.setSelected(true); p.gridx=0; p.gridy=py++; p.gridwidth=2; playerCard.add(playerAllRadio,p); p.gridwidth=1; p.gridx=0; p.gridy=py; playerCard.add(playerNameRadio,p); playerNameField = new JTextField(); playerNameField.setToolTipText("Comma separated names"); p.gridx=1; playerCard.add(playerNameField,p); py++; p.gridx=0; p.gridy=py; playerCard.add(playerCombatRadio,p); playerCombatSpinner = new JSpinner(new SpinnerNumberModel(5,0,126,1)); p.gridx=1; playerCard.add(playerCombatSpinner,p); py++; Runnable upd = ()->{ playerNameField.setEnabled(playerNameRadio.isSelected()); playerCombatSpinner.setEnabled(playerCombatRadio.isSelected()); }; upd.run(); playerAllRadio.addActionListener(e->upd.run()); playerNameRadio.addActionListener(e->upd.run()); playerCombatRadio.addActionListener(e->upd.run());
        // ANIMATION card
        animationCard = new JPanel(new GridBagLayout()); GridBagConstraints a = new GridBagConstraints(); a.insets=new Insets(4,4,4,4); a.fill=GridBagConstraints.HORIZONTAL; a.anchor=GridBagConstraints.WEST; a.weightx=1; int ay=0; a.gridx=0; a.gridy=ay; a.weightx=0; animationCard.add(new JLabel("Animation ID(s)"), a); animationIdField = new JTextField(); a.gridx=1; a.weightx=1; animationCard.add(animationIdField,a); ay++;
        // GRAPHIC card
        graphicCard = new JPanel(new GridBagLayout()); GridBagConstraints gfc = new GridBagConstraints(); gfc.insets=new Insets(4,4,4,4); gfc.fill=GridBagConstraints.HORIZONTAL; gfc.anchor=GridBagConstraints.WEST; gfc.weightx=1; int gfy=0; gfc.gridx=0; gfc.gridy=gfy; gfc.weightx=0; graphicCard.add(new JLabel("Graphic ID(s)"), gfc); graphicIdField = new JTextField(); gfc.gridx=1; gfc.weightx=1; graphicCard.add(graphicIdField,gfc); gfy++;
        // PROJECTILE card
        projectileCard = new JPanel(new GridBagLayout()); GridBagConstraints prc = new GridBagConstraints(); prc.insets=new Insets(4,4,4,4); prc.fill=GridBagConstraints.HORIZONTAL; prc.anchor=GridBagConstraints.WEST; prc.weightx=1; int pry=0; prc.gridx=0; prc.gridy=pry; prc.weightx=0; projectileCard.add(new JLabel("Projectile ID(s)"), prc); projectileIdField = new JTextField(); projectileIdField.setToolTipText("Kommaseparerte projectile id'er. SELF/TARGET krever minst én. ANY kan stå tom for alle."); prc.gridx=1; prc.weightx=1; projectileCard.add(projectileIdField, prc); pry++;
        // HITSPLAT card
        hitsplatCard = new JPanel(new GridBagLayout()); GridBagConstraints hs = new GridBagConstraints(); hs.insets=new Insets(4,4,4,4); hs.fill=GridBagConstraints.HORIZONTAL; hs.anchor=GridBagConstraints.WEST; hs.weightx=1; int hsy=0; hs.gridx=0; hs.gridy=hsy; hs.weightx=0; hitsplatCard.add(new JLabel("Direction"), hs); hitsplatModeBox = new JComboBox<>(new String[]{"Above","Below"}); hs.gridx=1; hs.weightx=1; hitsplatCard.add(hitsplatModeBox,hs); hsy++; hs.gridx=0; hs.gridy=hsy; hs.weightx=0; hitsplatCard.add(new JLabel("Value"), hs); hitsplatValueSpinner = new JSpinner(new SpinnerNumberModel(1,0,5000,1)); hs.gridx=1; hs.weightx=1; hitsplatCard.add(hitsplatValueSpinner,hs); hsy++;
        // MESSAGE card
        messageCard = new JPanel(new GridBagLayout()); GridBagConstraints m = new GridBagConstraints(); m.insets=new Insets(4,4,4,4); m.fill=GridBagConstraints.HORIZONTAL; m.anchor=GridBagConstraints.WEST; m.weightx=1; int my=0; m.gridx=0; m.gridy=my; m.weightx=0; messageCard.add(new JLabel("Message ID"), m); messageIdSpinner = new JSpinner(new SpinnerNumberModel(0,-1,9999,1)); Dimension mid = messageIdSpinner.getPreferredSize(); messageIdSpinner.setPreferredSize(new Dimension(80, mid.height)); m.gridx=1; m.weightx=1; messageCard.add(messageIdSpinner, m); my++; m.gridx=0; m.gridy=my; m.weightx=0; messageCard.add(new JLabel("Message text"), m); messageTextField = new JTextField(); messageTextField.setToolTipText("Optional text to match. Use * for wildcards, '_' = space. ID -1 = ANY chat type."); m.gridx=1; m.weightx=1; messageCard.add(messageTextField,m); my++;
        // VARBIT card multi
        varbitCard = new JPanel(new GridBagLayout()); GridBagConstraints vb = new GridBagConstraints(); vb.insets=new Insets(4,4,4,4); vb.fill=GridBagConstraints.HORIZONTAL; vb.anchor=GridBagConstraints.WEST; vb.weightx=1; int vy=0; vb.gridx=0; vb.gridy=vy; vb.weightx=0; varbitCard.add(new JLabel("Varbit ID(s)"), vb); varbitIdsField = new JTextField(); varbitIdsField.setToolTipText("Kommaseparerte varbit id'er"); vb.gridx=1; vb.weightx=1; varbitCard.add(varbitIdsField, vb); vy++; vb.gridx=0; vb.gridy=vy; vb.weightx=0; varbitCard.add(new JLabel("Value"), vb); varbitValueSpinner = new JSpinner(new SpinnerNumberModel(0,0,255,1)); vb.gridx=1; vb.weightx=1; varbitCard.add(varbitValueSpinner,vb); vy++;
        // VARPLAYER card multi
        varplayerCard = new JPanel(new GridBagLayout()); GridBagConstraints vp = new GridBagConstraints(); vp.insets=new Insets(4,4,4,4); vp.fill=GridBagConstraints.HORIZONTAL; vp.anchor=GridBagConstraints.WEST; vp.weightx=1; int vpy=0; vp.gridx=0; vp.gridy=vpy; vp.weightx=0; varplayerCard.add(new JLabel("Varplayer ID(s)"), vp); varplayerIdsField = new JTextField(); varplayerIdsField.setToolTipText("Kommaseparerte varplayer id'er"); vp.gridx=1; vp.weightx=1; varplayerCard.add(varplayerIdsField,vp); vpy++; vp.gridx=0; vp.gridy=vpy; vp.weightx=0; varplayerCard.add(new JLabel("Value"), vp); varplayerValueSpinner = new JSpinner(new SpinnerNumberModel(0,0,255,1)); vp.gridx=1; vp.weightx=1; varplayerCard.add(varplayerValueSpinner,vp); vpy++;
        // NPC card
        npcCard = new JPanel(new GridBagLayout()); GridBagConstraints nc = new GridBagConstraints(); nc.insets=new Insets(4,4,4,4); nc.fill=GridBagConstraints.HORIZONTAL; nc.anchor=GridBagConstraints.WEST; nc.weightx=1; int ncy=0; nc.gridx=0; nc.gridy=ncy; nc.weightx=0; npcCard.add(new JLabel("NPC list"), nc); npcListField = new JTextField(); npcListField.setToolTipText("Kommaseparert liste: id eller navn. Navn med space -> underscore."); nc.gridx=1; nc.weightx=1; npcCard.add(npcListField,nc); ncy++;
        // Add cards
        cards.add(new JPanel(), "NONE");
        cards.add(statCard, KPWebhookPreset.TriggerType.STAT.name());
        cards.add(widgetCard, KPWebhookPreset.TriggerType.WIDGET.name());
        cards.add(playerCard, "PLAYER");
        cards.add(animationCard, KPWebhookPreset.TriggerType.ANIMATION_SELF.name());
        cards.add(animationCard, KPWebhookPreset.TriggerType.ANIMATION_TARGET.name());
        cards.add(graphicCard, KPWebhookPreset.TriggerType.GRAPHIC_SELF.name());
        cards.add(graphicCard, KPWebhookPreset.TriggerType.GRAPHIC_TARGET.name());
        cards.add(projectileCard, KPWebhookPreset.TriggerType.PROJECTILE_TARGET.name());
        cards.add(hitsplatCard, KPWebhookPreset.TriggerType.HITSPLAT_SELF.name());
        cards.add(hitsplatCard, KPWebhookPreset.TriggerType.HITSPLAT_TARGET.name());
        cards.add(messageCard, KPWebhookPreset.TriggerType.MESSAGE.name());
        cards.add(varbitCard, KPWebhookPreset.TriggerType.VARBIT.name());
        cards.add(varplayerCard, KPWebhookPreset.TriggerType.VARPLAYER.name());
        cards.add(npcCard, "NPC");
        return cards;
    }

    private void updateTriggerVisibility()
    {
        CardLayout cl = (CardLayout) (triggerCards.getLayout());
        String sel = (String) triggerTypeBox.getSelectedItem();
        if (sel == null || TRIGGER_PLACEHOLDER.equals(sel) ||
                KPWebhookPreset.TriggerType.MANUAL.name().equals(sel) ||
                KPWebhookPreset.TriggerType.TICK.name().equals(sel) ||
                KPWebhookPreset.TriggerType.TARGET.name().equals(sel))
        {
            cl.show(triggerCards, "NONE");
            if (triggerDetailsPanel != null) triggerDetailsPanel.setVisible(false);
        }
        else if (KPWebhookPreset.TriggerType.PLAYER_SPAWN.name().equals(sel) || KPWebhookPreset.TriggerType.PLAYER_DESPAWN.name().equals(sel))
        { cl.show(triggerCards, "PLAYER"); if (triggerDetailsPanel != null) triggerDetailsPanel.setVisible(true); }
        else if (KPWebhookPreset.TriggerType.NPC_SPAWN.name().equals(sel) || KPWebhookPreset.TriggerType.NPC_DESPAWN.name().equals(sel))
        { cl.show(triggerCards, "NPC"); if (triggerDetailsPanel != null) triggerDetailsPanel.setVisible(true); }
        else if (sel.startsWith("PROJECTILE_")) { cl.show(triggerCards, KPWebhookPreset.TriggerType.PROJECTILE_TARGET.name()); if (triggerDetailsPanel!=null) triggerDetailsPanel.setVisible(true);}
        else if (sel.startsWith("ANIMATION_")) { cl.show(triggerCards, KPWebhookPreset.TriggerType.ANIMATION_TARGET.name()); if (triggerDetailsPanel!=null) triggerDetailsPanel.setVisible(true);}
        else if (sel.startsWith("GRAPHIC_")) { cl.show(triggerCards, KPWebhookPreset.TriggerType.GRAPHIC_TARGET.name()); if (triggerDetailsPanel!=null) triggerDetailsPanel.setVisible(true);}
        else {
            String cardKey = sel;
            if (KPWebhookPreset.TriggerType.HITSPLAT_SELF.name().equals(sel)) cardKey = KPWebhookPreset.TriggerType.HITSPLAT_TARGET.name();
            cl.show(triggerCards, cardKey);
            if (triggerDetailsPanel != null) triggerDetailsPanel.setVisible(true);
        }
        if (triggerDetailsPanel != null) { triggerDetailsPanel.revalidate(); triggerDetailsPanel.repaint(); }
        updateStatEnable();
        applyTickDisable();
    }

    private void applyTickDisable() {
        boolean isTick = KPWebhookPreset.TriggerType.TICK.name().equals(triggerTypeBox.getSelectedItem());
        setPanelEnabled(outlinePanel, !isTick);
        setPanelEnabled(tilePanel, !isTick);
        setPanelEnabled(hullPanel, !isTick);
        setPanelEnabled(minimapPanel, !isTick);
        setPanelEnabled(screenPanel, !isTick);
        setPanelEnabled(textOverPanel, !isTick);
        setPanelEnabled(textCenterPanel, !isTick);
        setPanelEnabled(textUnderPanel, !isTick);
        setPanelEnabled(overlayTextPanel, !isTick);
    }
    private void setPanelEnabled(Component c, boolean enabled) { if (c == null) return; c.setEnabled(enabled); if (c instanceof Container) for (Component ch : ((Container)c).getComponents()) setPanelEnabled(ch, enabled); }

    private void updateWebhookEnable() { customWebhookField.setEnabled(!useDefaultWebhookBox.isSelected()); }

    private void updateStatEnable()
    {
        String trigSel = (String) triggerTypeBox.getSelectedItem();
        boolean statMode = KPWebhookPreset.TriggerType.STAT.name().equals(trigSel);
        if (!statMode) return;
        KPWebhookPreset.StatMode mode = (KPWebhookPreset.StatMode) statModeBox.getSelectedItem();
        CardLayout cl = (CardLayout) levelHolderCard.getLayout();
        if (mode == KPWebhookPreset.StatMode.ABOVE || mode == KPWebhookPreset.StatMode.BELOW)
            cl.show(levelHolderCard, LEVEL_CARD_VISIBLE);
        else cl.show(levelHolderCard, LEVEL_CARD_EMPTY);
    }

    private static class InfoboxCategoryPanel extends JPanel {
        private final JSpinner durationSpinner;
        InfoboxCategoryPanel(int duration, String title) {
            super(new GridBagLayout()); setBorder(new TitledBorder(title)); setOpaque(false);
            GridBagConstraints c = new GridBagConstraints(); c.insets=new Insets(3,4,3,4); c.fill=GridBagConstraints.HORIZONTAL; c.anchor=GridBagConstraints.WEST; c.weightx=1; int y=0;
            JLabel durLbl = new JLabel("Duration"); durLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE)); c.gridx=0; c.gridy=y; add(durLbl,c);
            durationSpinner = new JSpinner(new SpinnerNumberModel(duration,0,10000,1)); Dimension ds = durationSpinner.getPreferredSize(); durationSpinner.setPreferredSize(new Dimension(70, ds.height)); JPanel durRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0)); durRow.setOpaque(false); durRow.add(durationSpinner); durRow.add(new JLabel("ticks")); c.gridx=1; add(durRow,c); y++;
            setPreferredSize(new Dimension(COMPACT_PANEL_WIDTH-20, getPreferredSize().height)); setMaximumSize(new Dimension(COMPACT_PANEL_WIDTH-20, Integer.MAX_VALUE));
        }
        int getDuration(){ return (Integer)durationSpinner.getValue(); }
        boolean isBlink(){ return false; }
        String getColorHex(){ return "#FFFFFF"; }
    }

    private static class OverlayTextPanel extends JPanel {
        private final JSpinner durationSpinner; private final JSpinner sizeSpinner; private final ColorPreview colorPreview; private Color selectedColor;
        OverlayTextPanel(int duration, int size, String colorHex, String title) {
            super(new GridBagLayout()); setBorder(new TitledBorder(title)); setOpaque(false);
            GridBagConstraints c = new GridBagConstraints(); c.insets=new Insets(3,4,3,4); c.fill=GridBagConstraints.HORIZONTAL; c.anchor=GridBagConstraints.WEST; c.weightx=1; int y=0;
            JLabel durLbl = new JLabel("Duration"); durLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE)); c.gridx=0; c.gridy=y; add(durLbl,c);
            durationSpinner = new JSpinner(new SpinnerNumberModel(duration,0,10000,1)); JPanel durRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0)); durRow.setOpaque(false); durRow.add(durationSpinner); durRow.add(new JLabel("ticks")); c.gridx=1; add(durRow,c); y++;
            JLabel sizeLbl = new JLabel("Size"); sizeLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE)); c.gridx=0; c.gridy=y; add(sizeLbl,c);
            sizeSpinner = new JSpinner(new SpinnerNumberModel(size,8,72,1)); c.gridx=1; add(sizeSpinner,c); y++;
            JLabel colorLbl = new JLabel("Color"); colorLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE)); c.gridx=0; c.gridy=y; add(colorLbl,c);
            selectedColor = HighlightCategoryPanel.parse(colorHex, Color.WHITE); colorPreview = new ColorPreview(selectedColor, this::setSelectedColor);
            JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0)); colorRow.setOpaque(false); JButton customBtn = new JButton("Custom"); customBtn.setMargin(new Insets(2,6,2,6)); customBtn.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE)); customBtn.addActionListener(e -> { Color picked = JColorChooser.showDialog(this, "Choose color", selectedColor); if (picked!=null) setSelectedColor(picked); });
            colorRow.add(colorPreview); colorRow.add(customBtn); c.gridx=1; add(colorRow,c); y++;
            setPreferredSize(new Dimension(COMPACT_PANEL_WIDTH-20, getPreferredSize().height)); setMaximumSize(new Dimension(COMPACT_PANEL_WIDTH-20, Integer.MAX_VALUE));
        }
        private void setSelectedColor(Color c){ if (c!=null){ selectedColor=c; colorPreview.setColor(c);} }
        int getDuration(){ return (Integer)durationSpinner.getValue(); }
        int getSizeValue(){ return (Integer)sizeSpinner.getValue(); }
        String getColorHex(){ return String.format("#%02X%02X%02X", selectedColor.getRed(), selectedColor.getGreen(), selectedColor.getBlue()); }
    }

    private static class ImgCategoryPanel extends JPanel {
        private final JSpinner durationSpinner; private final JCheckBox blinkBox;
        ImgCategoryPanel(int duration, boolean blink, String title) {
            super(new GridBagLayout()); setBorder(new TitledBorder(title)); setOpaque(false);
            GridBagConstraints c = new GridBagConstraints(); c.insets=new Insets(3,4,3,4); c.fill=GridBagConstraints.HORIZONTAL; c.anchor=GridBagConstraints.WEST; c.weightx=1; int y=0;
            JLabel durLbl = new JLabel("Duration"); durLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE)); c.gridx=0; c.gridy=y; add(durLbl,c);
            durationSpinner = new JSpinner(new SpinnerNumberModel(duration,0,10000,1)); Dimension ds = durationSpinner.getPreferredSize(); durationSpinner.setPreferredSize(new Dimension(70, ds.height)); JPanel durRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0)); durRow.setOpaque(false); durRow.add(durationSpinner); durRow.add(new JLabel("Ticks")); c.gridx=1; add(durRow,c); y++;
            JLabel blinkLbl = new JLabel("Blink"); blinkLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE)); c.gridx=0; c.gridy=y; add(blinkLbl,c);
            blinkBox = new JCheckBox(); blinkBox.setSelected(blink); blinkBox.setMargin(new Insets(0,0,0,0)); c.gridx=1; add(blinkBox,c); y++;
            setPreferredSize(new Dimension(COMPACT_PANEL_WIDTH-20, getPreferredSize().height)); setMaximumSize(new Dimension(COMPACT_PANEL_WIDTH-20, Integer.MAX_VALUE));
        }
        int getDuration(){ return (Integer)durationSpinner.getValue(); }
        boolean isBlink(){ return blinkBox.isSelected(); }
    }

    private KPWebhookPreset.NpcConfig parseNpcList(String raw) {
        KPWebhookPreset.NpcConfig.NpcConfigBuilder b = KPWebhookPreset.NpcConfig.builder(); b.rawList(raw);
        java.util.List<Integer> ids = new java.util.ArrayList<>(); java.util.List<String> names = new java.util.ArrayList<>();
        for (String part : raw.split(",")) { String t = part.trim(); if (t.isEmpty()) continue; String norm = t.replace(' ', '_').toLowerCase(Locale.ROOT); if (norm.matches("\\d+")) { try { ids.add(Integer.parseInt(norm)); } catch (NumberFormatException ignored) {} } else { names.add(norm); } }
        if (!ids.isEmpty()) b.npcIds(ids); if (!names.isEmpty()) b.npcNames(names); return b.build();
    }

    private java.util.List<Integer> parseIntList(String raw) { java.util.List<Integer> list = new java.util.ArrayList<>(); if (raw==null || raw.isBlank()) return list; for (String p : raw.split("[;,\\s]+")) { if (p.isBlank()) continue; try { list.add(Integer.parseInt(p.trim())); } catch (Exception ignored) {} } return list; }
    private String listToString(java.util.List<Integer> list) { if (list==null || list.isEmpty()) return ""; StringBuilder sb=new StringBuilder(); for (int i=0;i<list.size();i++){ if(i>0) sb.append(','); sb.append(list.get(i)); } return sb.toString(); }
    private java.util.List<String> parseNameList(String raw) { java.util.List<String> out=new java.util.ArrayList<>(); if (raw==null||raw.isBlank()) return out; for (String p: raw.split(",")) { String t=p.trim(); if(!t.isEmpty()) out.add(t.toLowerCase(Locale.ROOT)); } return out; }
}
