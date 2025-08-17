package com.krisped;

import net.runelite.api.Skill;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
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
    private JTextField playerNameField;
    private JSpinner playerCombatSpinner;
    // Animation trigger components
    private JPanel animationCard;
    private JTextField animationIdField;
    // Message trigger components
    private JPanel messageCard;
    private JSpinner messageIdSpinner; // now only ID spinner
    // Varbit trigger components
    private JPanel varbitCard;
    private JSpinner varbitIdSpinner;
    private JSpinner varbitValueSpinner;
    // Varplayer trigger components
    private JPanel varplayerCard;
    private JSpinner varplayerIdSpinner;
    private JSpinner varplayerValueSpinner;
    // Webhook components
    private JCheckBox useDefaultWebhookBox;
    private JTextField customWebhookField;
    private JTextArea commandsArea;
    private JCheckBox activeBox;
    // Holder for trigger details panel to toggle visibility
    private JPanel triggerDetailsPanel;

    /* -------- Settings Tab (kategorier) -------- */
    private JPanel settingsRoot;
    private HighlightCategoryPanel outlinePanel;
    private HighlightCategoryPanel tilePanel;
    private HighlightCategoryPanel hullPanel;
    private HighlightCategoryPanel minimapPanel; // restored minimap panel
    private TextCategoryPanel textOverPanel;
    private TextCategoryPanel textCenterPanel;
    private TextCategoryPanel textUnderPanel;
    private OverlayTextPanel overlayTextPanel; // new simple overlay text panel
    private InfoboxCategoryPanel infoboxPanel; // new infobox panel
    // Screenshot settings removed - keeping it simple
    /* -------- Info Tab -------- */
    private JTextArea infoArea;

    private KPWebhookPreset result;

    /* Default commands */
    private static final String DEFAULT_COMMANDS_TEXT =
            "# Available commands:\n" +
            "#  NOTIFY <text>            - In-game notification\n" +
            "#  WEBHOOK <text>          - Send to Discord/webhook\n" +
            "#  SCREENSHOT [text]        - Capture client area & upload (optional caption)\n" +
            "#  HIGHLIGHT_OUTLINE        - Outline local player\n" +
            "#  HIGHLIGHT_TILE           - Highlight tile under local player\n" +
            "#  HIGHLIGHT_HULL           - Hull highlight (player)\n" +
            "#  HIGHLIGHT_MINIMAP        - Minimap marker (reserved)\n" +
            "#  TEXT_OVER <text>         - Text above player\n" +
            "#  TEXT_CENTER <text>       - Text centered on player\n" +
            "#  TEXT_UNDER <text>        - Text under feet\n" +
            "#  OVERLAY_TEXT <text>      - Screen overlay box (top center)\n" +
            "#  INFOBOX <id> [message]     Show icon with optional custom tooltip text\n" +
            "#  SLEEP <ms>               - Millisecond delay in sequence\n" +
            "#  TICK [n]                 - Wait n game ticks (default 1)\n" +
            "#  STOP                     - Stop all active sequences\n" +
            "# Tokens: {{player}} {{stat}} {{current}} {{value}} {{widgetGroup}} {{widgetChild}} {{time}} {{otherPlayer}} {{otherCombat}}\n" +
            "\n" +
            "NOTIFY You gained a level in {{stat}}\n" +
            "HIGHLIGHT_OUTLINE\n" +
            "WEBHOOK Level in {{stat}} now {{current}}\n" +
            "TEXT_OVER Grats {{player}}!";

    public KPWebhookPresetDialog(Window owner,
                                 KPWebhookPlugin plugin,
                                 KPWebhookPreset existing)
    {
        super(owner, existing == null ? "Create" : "Edit", ModalityType.APPLICATION_MODAL);
        this.plugin = plugin;
        this.existing = existing;
        buildUI();
        populate(existing);
        pack();
        setMinimumSize(new Dimension(820, 600));
        setLocationRelativeTo(owner);
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
        root.setBorder(new EmptyBorder(12,12,12,12));
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

        // Commands area (titled border, absorbs extra vertical space)
        commandsArea = new JTextArea(14, 60);
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
        minimapPanel = new HighlightCategoryPanel(existing!=null?existing.getHlMinimapDuration():10,
                existing!=null?existing.getHlMinimapColor():"#00FFFF",
                existing!=null && Boolean.TRUE.equals(existing.getHlMinimapBlink()),
                existing!=null?existing.getHlMinimapWidth():2,
                "Highlight Minimap");
        hullPanel = new HighlightCategoryPanel(existing!=null?existing.getHlHullDuration():10,
                existing!=null?existing.getHlHullColor():"#FF55FF",
                existing!=null && Boolean.TRUE.equals(existing.getHlHullBlink()),
                existing!=null?existing.getHlHullWidth():2,
                "Highlight Hull");

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

        // Highlight group header
        JLabel hlHeader = new JLabel("Highlight");
        hlHeader.setFont(FontManager.getRunescapeBoldFont().deriveFont(HEADER_FONT_SIZE));
        hlHeader.setBorder(new EmptyBorder(4,2,2,2));
        settingsRoot.add(hlHeader);
        settingsRoot.add(new CollapsibleSection(outlinePanel));
        settingsRoot.add(new CollapsibleSection(tilePanel));
        settingsRoot.add(new CollapsibleSection(minimapPanel));
        settingsRoot.add(new CollapsibleSection(hullPanel));
        settingsRoot.add(Box.createVerticalStrut(6));
        // Text group header
        JLabel txtHeader = new JLabel("Text");
        txtHeader.setFont(FontManager.getRunescapeBoldFont().deriveFont(HEADER_FONT_SIZE));
        txtHeader.setBorder(new EmptyBorder(4,2,2,2));
        settingsRoot.add(txtHeader);
        settingsRoot.add(new CollapsibleSection(textUnderPanel));
        settingsRoot.add(new CollapsibleSection(textOverPanel));
        settingsRoot.add(new CollapsibleSection(textCenterPanel));
        settingsRoot.add(new CollapsibleSection(overlayTextPanel));
        settingsRoot.add(Box.createVerticalStrut(6));
        JLabel ibHeader = new JLabel("Infobox");
        ibHeader.setFont(FontManager.getRunescapeBoldFont().deriveFont(HEADER_FONT_SIZE));
        ibHeader.setBorder(new EmptyBorder(4,2,2,2));
        settingsRoot.add(ibHeader);
        settingsRoot.add(new CollapsibleSection(infoboxPanel));

        return new JScrollPane(settingsRoot);
    }

    private JPanel buildInfoTab()
    {
        JPanel p = new JPanel(new BorderLayout());
        infoArea = new JTextArea();
        infoArea.setEditable(false);
        infoArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        infoArea.setText(
                "KP Webhook Plugin - Commands & Triggers\n" +
                        "====================================\n\n" +
                        "Commands (one per line in the Commands box):\n" +
                        "  NOTIFY <text>               In‑game chat notification (tokens allowed)\n" +
                        "  WEBHOOK <text>              Send text to effective webhook (default/custom)\n" +
                        "  SCREENSHOT [text]           Capture client & upload (optional caption)\n" +
                        "  HIGHLIGHT_OUTLINE           Outline local player (duration / blink / color in Settings)\n" +
                        "  HIGHLIGHT_TILE              Highlight tile under local player\n" +
                        "  HIGHLIGHT_HULL              Player hull highlight\n" +
                        "  HIGHLIGHT_MINIMAP           Minimap marker (reserved – still configurable)\n" +
                        "  TEXT_OVER <text>            Overhead text above player\n" +
                        "  TEXT_CENTER <text>          Centered text on player position\n" +
                        "  TEXT_UNDER <text>           Text under player feet\n" +
                        "  OVERLAY_TEXT <text>         Screen overlay text box (top center). Style (color/size/duration) set in Settings.\n" +
                        "  INFOBOX <id> [message]     Show icon: positive=item ID, negative=sprite ID; optional tooltip text after id\n" +
                        "  SLEEP <ms>                  Millisecond delay in a sequence\n" +
                        "  TICK [n]                    Wait n game ticks (default 1) inside a sequence\n" +
                        "  STOP                        Stop all active sequences & visuals\n" +
                        "\n" +
                        "Triggers (choose in Preset tab):\n" +
                        "  MANUAL                      Only runs when manually triggered\n" +
                        "  STAT                        Skill condition (LEVEL_UP / ABOVE / BELOW threshold)\n" +
                        "  WIDGET                      When widget group (and optional child) appears\n" +
                        "  PLAYER_SPAWN                Player spawn (ALL / name / combat +/- range)\n" +
                        "  PLAYER_DESPAWN              Player despawn (same matching rules)\n" +
                        "  ANIMATION_SELF              Your player performs specific animation ID\n" +
                        "  MESSAGE                     Chat message type ID occurs (use ordinal)\n" +
                        "  VARBIT                      Varbit changes to value\n" +
                        "  VARPLAYER                   Varplayer changes to value\n" +
                        "  TICK                        Re-applies visual commands every tick (durations ignored)\n" +
                        "\n" +
                        "Tokens available in text commands: \n" +
                        "  {{player}} {{stat}} {{current}} {{value}} {{widgetGroup}} {{widgetChild}} {{time}} {{otherPlayer}} {{otherCombat}}\n" +
                        "\n" +
                        "INFOBOX Usage & Testing:\n" +
                        "  1. Open or create a preset.\n" +
                        "  2. In Commands area add a line: INFOBOX 4151 YOU NEED ABYSSAL WHIP FOR THIS RUN\n" +
                        "     Or a sprite: INFOBOX -502 Custom sprite tooltip text\n" +
                        "  3. Open Settings > Infobox section: set Duration (ticks).\n" +
                        "  4. Choose a Trigger (e.g. MANUAL) and Save.\n" +
                        "  5. Trigger preset – icon appears; hover for your custom tooltip.\n" +
                        "  6. Multiple INFOBOX lines stack.\n" +
                        "\n" +
                        "Durations: Visual commands use configured durations unless Trigger is TICK (then they refresh).\n" +
                        "Blink: Toggles visibility each tick for that visual element.\n" +
                        "Color: Applies tint to text/highlight; for INFOBOX it tints (or colorizes overlay label if implemented).\n" +
                        "\n" +
                        "Examples:\n" +
                        "  NOTIFY Level up in {{stat}}!\n" +
                        "  HIGHLIGHT_OUTLINE\n" +
                        "  INFOBOX 4151 YOU NEED ABYSSAL WHIP FOR THIS RUN\n" +
                        "  SLEEP 500\n" +
                        "  INFOBOX -502\n" +
                        "  TEXT_OVER Grats {{player}}!\n" +
                        "  OVERLAY_TEXT Low health! Eat food now!\n" +
                        "\n" +
                        "STOP command immediately clears active highlight/text sequences, overlay texts and infobox entries.\n"
        );
        p.add(new JScrollPane(infoArea), BorderLayout.CENTER);
        return p;
    }

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
            activeBox.setSelected(true);
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
            animationIdField.setText(String.valueOf(r.getAnimationConfig().getAnimationId()));
        }
        if (r.getTriggerType() == KPWebhookPreset.TriggerType.MESSAGE && r.getMessageConfig() != null)
        {
            if (r.getMessageConfig().getMessageId() != null)
                messageIdSpinner.setValue(r.getMessageConfig().getMessageId());
        }
        if (r.getTriggerType() == KPWebhookPreset.TriggerType.VARBIT && r.getVarbitConfig() != null)
        {
            varbitIdSpinner.setValue(r.getVarbitConfig().getVarbitId());
            varbitValueSpinner.setValue(r.getVarbitConfig().getValue());
        }
        if (r.getTriggerType() == KPWebhookPreset.TriggerType.VARPLAYER && r.getVarplayerConfig() != null)
        {
            varplayerIdSpinner.setValue(r.getVarplayerConfig().getVarplayerId());
            varplayerValueSpinner.setValue(r.getVarplayerConfig().getValue());
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
        KPWebhookPreset.MessageConfig messageCfg = null;
        KPWebhookPreset.VarbitConfig varbitCfg = null;
        KPWebhookPreset.VarplayerConfig varplayerCfg = null;

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
                String name = playerNameField.getText().trim();
                if (name.isEmpty()) { JOptionPane.showMessageDialog(this, "Player name cannot be empty"); return; }
                playerCfg = KPWebhookPreset.PlayerConfig.builder().all(false).name(name).build();
            }
            else if (playerCombatRadio.isSelected())
            {
                int range = (Integer) playerCombatSpinner.getValue();
                playerCfg = KPWebhookPreset.PlayerConfig.builder().all(false).combatRange(range).build();
            }
            else
            {
                JOptionPane.showMessageDialog(this, "Select player option (ALL, Name or +/- range)"); return;
            }
        }
        else if (trig == KPWebhookPreset.TriggerType.ANIMATION_SELF)
        {
            int animId = 0;
            try { animId = Integer.parseInt(animationIdField.getText().trim()); }
            catch (NumberFormatException e) { JOptionPane.showMessageDialog(this, "Invalid animation ID"); return; }
            animationCfg = KPWebhookPreset.AnimationConfig.builder().animationId(animId).build();
        }
        else if (trig == KPWebhookPreset.TriggerType.MESSAGE)
        {
            int messageId = 0;
            try { messageId = Integer.parseInt(messageIdSpinner.getValue().toString().trim()); }
            catch (NumberFormatException e) { JOptionPane.showMessageDialog(this, "Invalid message ID"); return; }
            messageCfg = KPWebhookPreset.MessageConfig.builder().messageId(messageId).messageText(null).build();
        }
        else if (trig == KPWebhookPreset.TriggerType.VARBIT)
        {
            int varbitId = 0;
            try { varbitId = Integer.parseInt(varbitIdSpinner.getValue().toString().trim()); }
            catch (NumberFormatException e) { JOptionPane.showMessageDialog(this, "Invalid varbit ID"); return; }
            int varbitValue = (Integer) varbitValueSpinner.getValue();
            varbitCfg = KPWebhookPreset.VarbitConfig.builder().varbitId(varbitId).value(varbitValue).build();
        }
        else if (trig == KPWebhookPreset.TriggerType.VARPLAYER)
        {
            int varplayerId = 0;
            try { varplayerId = Integer.parseInt(varplayerIdSpinner.getValue().toString().trim()); }
            catch (NumberFormatException e) { JOptionPane.showMessageDialog(this, "Invalid varplayer ID"); return; }
            int varplayerValue = (Integer) varplayerValueSpinner.getValue();
            varplayerCfg = KPWebhookPreset.VarplayerConfig.builder().varplayerId(varplayerId).value(varplayerValue).build();
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
                .textCenterItalic(textCenterPanel.isItalic()) // corrected
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
                .playerConfig(playerCfg)
                .animationConfig(animationCfg)
                .messageConfig(messageCfg)
                .varbitConfig(varbitCfg)
                .varplayerConfig(varplayerCfg);

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
    private void updateSettingsVisibility()
    {
        // Now just rebuild dropdown contents based on commands
    }

    private void showHelp()
    {
        String msg =
                "Commands: NOTIFY, WEBHOOK, SCREENSHOT, HIGHLIGHT_*, TEXT_*\n" +
                "Use the Settings tab to configure highlight & text visuals.\n" +
                "Blink toggles visibility each tick.\n" +
                "Color: click the swatch or 'Custom' for custom #RRGGBB.";
        JOptionPane.showMessageDialog(this, msg, "Help", JOptionPane.INFORMATION_MESSAGE);
    }

    /* ================= SUPPORT CLASSES ================= */
    private abstract static class SimpleDoc implements DocumentListener
    { protected abstract void changed(); @Override public void insertUpdate(DocumentEvent e){ changed(); } @Override public void removeUpdate(DocumentEvent e){ changed(); } @Override public void changedUpdate(DocumentEvent e){ changed(); } }

    /** Highlight panel (Duration, Blink, Color). Width holdes internt for kompatibilitet. */
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
            c.insets = new Insets(3,4,3,4); // uniform insets
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            c.weightx = 1;
            int y=0;
            JLabel durLbl = new JLabel("Duration"); durLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; c.weightx=0; add(durLbl, c);
            durationSpinner = new JSpinner(new SpinnerNumberModel(duration,1,5000,1));
            Dimension sd = durationSpinner.getPreferredSize();
            durationSpinner.setPreferredSize(new Dimension(60, sd.height));
            JPanel durRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0)); durRow.setOpaque(false);
            durRow.add(durationSpinner);
            durRow.add(new JLabel("ticks"));
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
            JButton customBtn = new JButton("Custom");
            customBtn.setMargin(new Insets(2,6,2,6));
            customBtn.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            customBtn.addActionListener(e -> {
                Color picked = JColorChooser.showDialog(this, "Choose color", selectedColor);
                if (picked != null) setSelectedColor(picked);
            });
            colorRow.add(colorPreview);
            colorRow.add(customBtn);
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
            c.insets = new Insets(3,4,3,4); // uniform insets
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            c.weightx = 1;
            int y=0;
            JLabel durLbl = new JLabel("Duration"); durLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; add(durLbl, c);
            durationSpinner = new JSpinner(new SpinnerNumberModel(duration,1,5000,1));
            Dimension ds = durationSpinner.getPreferredSize();
            durationSpinner.setPreferredSize(new Dimension(60, ds.height));
            JPanel durRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0)); durRow.setOpaque(false);
            durRow.add(durationSpinner);
            durRow.add(new JLabel("ticks"));
            c.gridx=1; add(durRow, c); y++;
            JLabel sizeLbl = new JLabel("Size"); sizeLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; add(sizeLbl, c);
            sizeSpinner = new JSpinner(new SpinnerNumberModel(size,8,72,1));
            Dimension ss = sizeSpinner.getPreferredSize();
            sizeSpinner.setPreferredSize(new Dimension(60, ss.height));
            c.gridx=1; add(sizeSpinner, c); y++;
            JLabel blinkOnlyLbl = new JLabel("Blink"); blinkOnlyLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; add(blinkOnlyLbl, c);
            blinkBox = new JCheckBox(); blinkBox.setMargin(new Insets(0,0,0,0)); blinkBox.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            blinkBox.setSelected(blink);
            c.gridx=1; add(blinkBox, c); y++;
            JLabel styleLbl = new JLabel("Style"); styleLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            JPanel styleRow = new JPanel(new FlowLayout(FlowLayout.LEFT,12,0)); styleRow.setOpaque(false); // more space between boxes
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
            JButton customBtn = new JButton("Custom"); customBtn.setMargin(new Insets(2,6,2,6));
            customBtn.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
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

    /** Screenshot panel for configuring screenshot-specific options */
    private static class ScreenshotCategoryPanel extends JPanel
    {
        private final JSpinner delaySpinner;
        private final JCheckBox notifyBox;
        private final JSpinner qualitySpinner;
        private final JSpinner widthSpinner;
        private final JSpinner heightSpinner;
        private final JCheckBox hideTooltipsBox;

        ScreenshotCategoryPanel(int delay, boolean notify, int quality, int width, int height, boolean hideTooltips)
        {
            super(new GridBagLayout());
            setBorder(new TitledBorder("SCREENSHOT"));
            setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3,4,3,4);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            c.weightx = 1;
            int y=0;

            // Delay
            JLabel delayLbl = new JLabel("Delay");
            delayLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; c.weightx=0; add(delayLbl, c);
            delaySpinner = new JSpinner(new SpinnerNumberModel(delay,0,5000,10));
            Dimension ds = delaySpinner.getPreferredSize();
            delaySpinner.setPreferredSize(new Dimension(60, ds.height));
            JPanel delayRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
            delayRow.setOpaque(false);
            delayRow.add(delaySpinner);
            delayRow.add(new JLabel("ms"));
            c.gridx=1; c.weightx=1; add(delayRow, c); y++;

            // Notify
            JLabel notifyLbl = new JLabel("Notify on capture");
            notifyLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; c.weightx=0; add(notifyLbl, c);
            notifyBox = new JCheckBox();
            notifyBox.setSelected(notify);
            notifyBox.setMargin(new Insets(0,0,0,0));
            notifyBox.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=1; add(notifyBox,c); y++;

            // Quality
            JLabel qualityLbl = new JLabel("Quality");
            qualityLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; c.weightx=0; add(qualityLbl, c);
            qualitySpinner = new JSpinner(new SpinnerNumberModel(quality,10,100,5));
            Dimension qs = qualitySpinner.getPreferredSize();
            qualitySpinner.setPreferredSize(new Dimension(60, qs.height));
            JPanel qualityRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
            qualityRow.setOpaque(false);
            qualityRow.add(qualitySpinner);
            qualityRow.add(new JLabel("%"));
            c.gridx=1; c.weightx=1; add(qualityRow, c); y++;

            // Width
            JLabel widthLbl = new JLabel("Width");
            widthLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; c.weightx=0; add(widthLbl, c);
            widthSpinner = new JSpinner(new SpinnerNumberModel(width, 100, 4096, 10));
            Dimension ws = widthSpinner.getPreferredSize();
            widthSpinner.setPreferredSize(new Dimension(70, ws.height));
            c.gridx=1; add(widthSpinner, c); y++;

            // Height
            JLabel heightLbl = new JLabel("Height");
            heightLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; c.weightx=0; add(heightLbl, c);
            heightSpinner = new JSpinner(new SpinnerNumberModel(height, 100, 4096, 10));
            Dimension hs = heightSpinner.getPreferredSize();
            heightSpinner.setPreferredSize(new Dimension(70, hs.height));
            c.gridx=1; add(heightSpinner, c); y++;

            // Hide tooltips checkbox
            JLabel tooltipLbl = new JLabel("Skjul tooltips");
            tooltipLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; c.weightx=0; add(tooltipLbl, c);
            hideTooltipsBox = new JCheckBox();
            hideTooltipsBox.setSelected(hideTooltips);
            hideTooltipsBox.setMargin(new Insets(0,0,0,0));
            hideTooltipsBox.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=1; add(hideTooltipsBox,c); y++;

            setPreferredSize(new Dimension(COMPACT_PANEL_WIDTH-20, getPreferredSize().height));
            setMaximumSize(new Dimension(COMPACT_PANEL_WIDTH-20, Integer.MAX_VALUE));
        }

        int getDelay(){ return (Integer)delaySpinner.getValue(); }
        boolean isNotify(){ return notifyBox.isSelected(); }
        int getQuality(){ return (Integer)qualitySpinner.getValue(); }
        int getScreenWidth(){ return (Integer)widthSpinner.getValue(); }
        int getScreenHeight(){ return (Integer)heightSpinner.getValue(); }
        boolean isHideTooltips(){ return hideTooltipsBox.isSelected(); }
        void setHideTooltips(boolean b){ hideTooltipsBox.setSelected(b); }
    }

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
            this.color = initial;
            this.setter = setter;
            setPreferredSize(new Dimension(50,20));
            setMaximumSize(new Dimension(50,20));
            setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
            setToolTipText("Click to change color");
            addMouseListener(new java.awt.event.MouseAdapter(){
                @Override public void mouseClicked(java.awt.event.MouseEvent e){ showPalette(e.getComponent(), e.getX(), e.getY()); }
            });
        }
        private void showPalette(Component parent, int x, int y)
        {
            JPopupMenu menu = new JPopupMenu();
            JPanel grid = new JPanel(new GridLayout(3,4,2,2));
            grid.setBorder(new EmptyBorder(4,4,4,4));
            for (Color c : palette)
            {
                JButton b = new JButton();
                b.setBackground(c);
                b.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
                b.setPreferredSize(new Dimension(24,20));
                b.setToolTipText(String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
                b.addActionListener(ev -> { setColor(c); setter.accept(c); menu.setVisible(false); });
                grid.add(b);
            }
            menu.add(grid);
            menu.show(parent, x, y);
        }
        void setColor(Color c){ this.color=c; repaint(); }
        @Override protected void paintComponent(Graphics g){ super.paintComponent(g); g.setColor(color); g.fillRect(0,0,getWidth(),getHeight()); }
    }

    private static class CollapsibleSection extends JPanel
    {
        private final JPanel content;
        private boolean expanded = false;
        private final JButton header;
        private final String title;
        CollapsibleSection(JPanel inner)
        {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            this.content = inner;
            this.title = inner instanceof HighlightCategoryPanel ? ((TitledBorder)inner.getBorder()).getTitle() : (inner instanceof TextCategoryPanel ? ((TitledBorder)inner.getBorder()).getTitle() : "Section");
            inner.setBorder(null); // remove original titled border for compact layout
            header = new JButton(labelText());
            header.setFocusPainted(false);
            header.setHorizontalAlignment(SwingConstants.LEFT);
            header.setBorderPainted(false);
            header.setContentAreaFilled(false);
            header.setOpaque(false);
            header.setMargin(new Insets(0,4,0,4));
            header.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
            header.addActionListener(e -> toggle());
            add(header);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        private String labelText(){ return (expanded ? "[-] " : "[+] ") + title; }
        private void toggle()
        {
            expanded = !expanded;
            header.setText(labelText());
            if (expanded)
            {
                JPanel wrap = new JPanel();
                wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
                wrap.setOpaque(false);
                wrap.setBorder(new EmptyBorder(4,18,6,4));
                content.setAlignmentX(Component.LEFT_ALIGNMENT);
                wrap.add(content);
                wrap.setMaximumSize(new Dimension(COMPACT_PANEL_WIDTH, wrap.getPreferredSize().height));
                wrap.setPreferredSize(new Dimension(COMPACT_PANEL_WIDTH, wrap.getPreferredSize().height));
                add(wrap);
            }
            else
            {
                while (getComponentCount() > 1) remove(1);
            }
            revalidate();
            repaint();
        }
    }

    private String[] buildTriggerModel()
    {
        KPWebhookPreset.TriggerType[] types = KPWebhookPreset.TriggerType.values();
        String[] data = new String[types.length + 1];
        data[0] = TRIGGER_PLACEHOLDER;
        for (int i=0;i<types.length;i++) data[i+1] = types[i].name();
        return data;
    }

    private JPanel buildTriggerCards()
    {
        JPanel cards = new JPanel(new CardLayout());
        // STAT card
        statCard = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4,4,4,4);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        g.weightx = 1;
        int y=0;
        g.gridx=0; g.gridy=y; g.weightx=0; statCard.add(new JLabel("Skill"), g);
        List<String> skills = new ArrayList<>();
        skills.add(SKILL_PLACEHOLDER);
        for (Skill s : Skill.values()) skills.add(s.name());
        statSkillBox = new JComboBox<>(skills.toArray(new String[0]));
        g.gridx=1; g.weightx=1; statCard.add(statSkillBox, g); y++;
        g.gridx=0; g.gridy=y; g.weightx=0; statCard.add(new JLabel("Mode"), g);
        statModeBox = new JComboBox<>(KPWebhookPreset.StatMode.values());
        g.gridx=1; g.weightx=1; statCard.add(statModeBox, g); y++;
        g.gridx=0; g.gridy=y; g.weightx=0; statCard.add(new JLabel("Threshold"), g);
        levelHolderCard = new JPanel(new CardLayout());
        JPanel levelPanel = new JPanel(new BorderLayout());
        levelSpinner = new JSpinner(new SpinnerNumberModel(1,1,200,1));
        levelPanel.add(levelSpinner, BorderLayout.WEST);
        levelHolderCard.add(new JPanel(), LEVEL_CARD_EMPTY);
        levelHolderCard.add(levelPanel, LEVEL_CARD_VISIBLE);
        g.gridx=1; g.weightx=1; statCard.add(levelHolderCard, g); y++;
        statSkillBox.addActionListener(e -> updateStatEnable());
        statModeBox.addActionListener(e -> updateStatEnable());
        // WIDGET card
        widgetCard = new JPanel(new GridBagLayout());
        GridBagConstraints w = new GridBagConstraints();
        w.insets = new Insets(4,4,4,4); w.fill = GridBagConstraints.HORIZONTAL; w.anchor=GridBagConstraints.WEST; w.weightx=1; int wy=0;
        w.gridx=0; w.gridy=wy; w.weightx=0; widgetCard.add(new JLabel("Widget (group[:child])"), w);
        widgetField = new JTextField();
        w.gridx=1; w.weightx=1; widgetCard.add(widgetField, w); wy++;
        // PLAYER card (shared for spawn & despawn)
        playerCard = new JPanel(new GridBagLayout());
        GridBagConstraints p = new GridBagConstraints();
        p.insets = new Insets(4,4,4,4);
        p.fill = GridBagConstraints.HORIZONTAL;
        p.anchor = GridBagConstraints.WEST;
        p.weightx = 1;
        int py=0;
        ButtonGroup grp = new ButtonGroup();
        playerAllRadio = new JRadioButton("ALL players");
        playerNameRadio = new JRadioButton("Specific name:");
        playerCombatRadio = new JRadioButton("Combat level +/-");
        grp.add(playerAllRadio); grp.add(playerNameRadio); grp.add(playerCombatRadio);
        playerAllRadio.setSelected(true);
        p.gridx=0; p.gridy=py++; p.gridwidth=2; playerCard.add(playerAllRadio, p); p.gridwidth=1;
        p.gridx=0; p.gridy=py; playerCard.add(playerNameRadio, p);
        playerNameField = new JTextField();
        p.gridx=1; playerCard.add(playerNameField, p); py++;
        p.gridx=0; p.gridy=py; playerCard.add(playerCombatRadio, p);
        playerCombatSpinner = new JSpinner(new SpinnerNumberModel(5,0,126,1));
        p.gridx=1; playerCard.add(playerCombatSpinner, p); py++;
        Runnable upd = () -> {
            playerNameField.setEnabled(playerNameRadio.isSelected());
            playerCombatSpinner.setEnabled(playerCombatRadio.isSelected());
        }; upd.run();
        playerAllRadio.addActionListener(e -> upd.run());
        playerNameRadio.addActionListener(e -> upd.run());
        playerCombatRadio.addActionListener(e -> upd.run());
        // ANIMATION card
        animationCard = new JPanel(new GridBagLayout());
        GridBagConstraints a = new GridBagConstraints();
        a.insets = new Insets(4,4,4,4);
        a.fill = GridBagConstraints.HORIZONTAL;
        a.anchor = GridBagConstraints.WEST;
        a.weightx = 1;
        int ay=0;
        a.gridx=0; a.gridy=ay; a.weightx=0; animationCard.add(new JLabel("Animation ID"), a);
        animationIdField = new JTextField();
        a.gridx=1; a.weightx=1; animationCard.add(animationIdField, a); ay++;
        // MESSAGE card
        messageCard = new JPanel(new GridBagLayout());
        GridBagConstraints m = new GridBagConstraints();
        m.insets = new Insets(4,4,4,4);
        m.fill = GridBagConstraints.HORIZONTAL;
        m.anchor = GridBagConstraints.WEST;
        m.weightx = 1;
        int my=0;
        m.gridx=0; m.gridy=my; m.weightx=0; messageCard.add(new JLabel("Message ID"), m);
        messageIdSpinner = new JSpinner(new SpinnerNumberModel(0,0,9999,1));
        Dimension mid = messageIdSpinner.getPreferredSize();
        messageIdSpinner.setPreferredSize(new Dimension(80, mid.height));
        m.gridx=1; m.weightx=1; messageCard.add(messageIdSpinner, m); my++;
        // VARBIT card
        varbitCard = new JPanel(new GridBagLayout());
        GridBagConstraints vb = new GridBagConstraints();
        vb.insets = new Insets(4,4,4,4);
        vb.fill = GridBagConstraints.HORIZONTAL;
        vb.anchor = GridBagConstraints.WEST;
        vb.weightx = 1;
        int vy=0;
        vb.gridx=0; vb.gridy=vy; vb.weightx=0; varbitCard.add(new JLabel("Varbit ID"), vb);
        varbitIdSpinner = new JSpinner(new SpinnerNumberModel(0,0,32767,1));
        Dimension vid = varbitIdSpinner.getPreferredSize();
        varbitIdSpinner.setPreferredSize(new Dimension(80, vid.height));
        vb.gridx=1; vb.weightx=1; varbitCard.add(varbitIdSpinner, vb); vy++;
        vb.gridx=0; vb.gridy=vy; vb.weightx=0; varbitCard.add(new JLabel("Value"), vb);
        varbitValueSpinner = new JSpinner(new SpinnerNumberModel(0,0,255,1));
        vb.gridx=1; vb.weightx=1; varbitCard.add(varbitValueSpinner, vb); vy++;
        // VARPLAYER card
        varplayerCard = new JPanel(new GridBagLayout());
        GridBagConstraints vp = new GridBagConstraints();
        vp.insets = new Insets(4,4,4,4);
        vp.fill = GridBagConstraints.HORIZONTAL;
        vp.anchor = GridBagConstraints.WEST;
        vp.weightx = 1;
        int vpy=0;
        vp.gridx=0; vp.gridy=vpy; vp.weightx=0; varplayerCard.add(new JLabel("Varplayer ID"), vp);
        varplayerIdSpinner = new JSpinner(new SpinnerNumberModel(0,0,32767,1));
        Dimension pid = varplayerIdSpinner.getPreferredSize();
        varplayerIdSpinner.setPreferredSize(new Dimension(80, pid.height));
        vp.gridx=1; vp.weightx=1; varplayerCard.add(varplayerIdSpinner, vp); vpy++;
        vp.gridx=0; vp.gridy=vpy; vp.weightx=0; varplayerCard.add(new JLabel("Value"), vp);
        varplayerValueSpinner = new JSpinner(new SpinnerNumberModel(0,0,255,1));
        vp.gridx=1; vp.weightx=1; varplayerCard.add(varplayerValueSpinner, vp); vpy++;
        // removed message text field (was messageTextField) so layout ends here
        // Add cards
        cards.add(new JPanel(), "NONE");
        cards.add(statCard, KPWebhookPreset.TriggerType.STAT.name());
        cards.add(widgetCard, KPWebhookPreset.TriggerType.WIDGET.name());
        cards.add(playerCard, "PLAYER");
        cards.add(animationCard, KPWebhookPreset.TriggerType.ANIMATION_SELF.name());
        cards.add(messageCard, KPWebhookPreset.TriggerType.MESSAGE.name());
        cards.add(varbitCard, KPWebhookPreset.TriggerType.VARBIT.name());
        cards.add(varplayerCard, KPWebhookPreset.TriggerType.VARPLAYER.name());
        return cards;
    }

    private void updateTriggerVisibility()
    {
        CardLayout cl = (CardLayout) (triggerCards.getLayout());
        String sel = (String) triggerTypeBox.getSelectedItem();
        if (sel == null || TRIGGER_PLACEHOLDER.equals(sel) || KPWebhookPreset.TriggerType.MANUAL.name().equals(sel) || KPWebhookPreset.TriggerType.TICK.name().equals(sel))
        {
            cl.show(triggerCards, "NONE");
        }
        else if (KPWebhookPreset.TriggerType.PLAYER_SPAWN.name().equals(sel) || KPWebhookPreset.TriggerType.PLAYER_DESPAWN.name().equals(sel))
        {
            cl.show(triggerCards, "PLAYER");
        }
        else
        {
            cl.show(triggerCards, sel);
        }
        if (triggerDetailsPanel != null && !triggerDetailsPanel.isVisible())
            triggerDetailsPanel.setVisible(true);
        if (triggerDetailsPanel != null) { triggerDetailsPanel.revalidate(); triggerDetailsPanel.repaint(); }
        updateStatEnable();
        applyTickDisable();
    }

    private void applyTickDisable() {
        boolean isTick = KPWebhookPreset.TriggerType.TICK.name().equals(triggerTypeBox.getSelectedItem());
        // When TICK trigger selected: disable config panels (durations irrelevant). When leaving: re-enable.
        setPanelEnabled(outlinePanel, !isTick);
        setPanelEnabled(tilePanel, !isTick);
        setPanelEnabled(hullPanel, !isTick);
        setPanelEnabled(minimapPanel, !isTick);
        setPanelEnabled(textOverPanel, !isTick);
        setPanelEnabled(textCenterPanel, !isTick);
        setPanelEnabled(textUnderPanel, !isTick);
        setPanelEnabled(overlayTextPanel, !isTick);
    }
    private void setPanelEnabled(Component c, boolean enabled) {
        if (c == null) return;
        c.setEnabled(enabled);
        if (c instanceof Container) {
            for (Component ch : ((Container)c).getComponents()) setPanelEnabled(ch, enabled);
        }
    }

    private void updateWebhookEnable()
    {
        boolean useDef = useDefaultWebhookBox.isSelected();
        customWebhookField.setEnabled(!useDef);
    }

    private void updateStatEnable()
    {
        String trigSel = (String) triggerTypeBox.getSelectedItem();
        boolean statMode = KPWebhookPreset.TriggerType.STAT.name().equals(trigSel);
        if (!statMode) return;
        KPWebhookPreset.StatMode mode = (KPWebhookPreset.StatMode) statModeBox.getSelectedItem();
        CardLayout cl = (CardLayout) levelHolderCard.getLayout();
        if (mode == KPWebhookPreset.StatMode.ABOVE || mode == KPWebhookPreset.StatMode.BELOW)
            cl.show(levelHolderCard, LEVEL_CARD_VISIBLE);
        else
            cl.show(levelHolderCard, LEVEL_CARD_EMPTY);
    }

    /** Infobox panel (Duration only). */
    private static class InfoboxCategoryPanel extends JPanel {
        private final JSpinner durationSpinner;
        InfoboxCategoryPanel(int duration, String title) {
            super(new GridBagLayout());
            setBorder(new TitledBorder(title));
            setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3,4,3,4);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST; c.weightx=1; int y=0;
            JLabel durLbl = new JLabel("Duration"); durLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; add(durLbl,c);
            durationSpinner = new JSpinner(new SpinnerNumberModel(duration,1,10000,1));
            Dimension ds = durationSpinner.getPreferredSize();
            durationSpinner.setPreferredSize(new Dimension(70, ds.height));
            JPanel durRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0)); durRow.setOpaque(false);
            durRow.add(durationSpinner); durRow.add(new JLabel("ticks"));
            c.gridx=1; add(durRow,c); y++;
            setPreferredSize(new Dimension(COMPACT_PANEL_WIDTH-20, getPreferredSize().height));
            setMaximumSize(new Dimension(COMPACT_PANEL_WIDTH-20, Integer.MAX_VALUE));
        }
        int getDuration(){ return (Integer)durationSpinner.getValue(); }
        // Backwards compatibility methods (no-op / defaults)
        boolean isBlink(){ return false; }
        String getColorHex(){ return "#FFFFFF"; }
    }

    private static class OverlayTextPanel extends JPanel {
        private final JSpinner durationSpinner;
        private final JSpinner sizeSpinner;
        private final ColorPreview colorPreview;
        private Color selectedColor;
        OverlayTextPanel(int duration, int size, String colorHex, String title) {
            super(new GridBagLayout());
            setBorder(new TitledBorder(title));
            setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3,4,3,4);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            c.weightx = 1; int y=0;
            JLabel durLbl = new JLabel("Duration"); durLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; add(durLbl,c);
            durationSpinner = new JSpinner(new SpinnerNumberModel(duration,1,10000,1));
            JPanel durRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0)); durRow.setOpaque(false); durRow.add(durationSpinner); durRow.add(new JLabel("ticks"));
            c.gridx=1; add(durRow,c); y++;
            JLabel sizeLbl = new JLabel("Size"); sizeLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; add(sizeLbl,c);
            sizeSpinner = new JSpinner(new SpinnerNumberModel(size,8,72,1));
            c.gridx=1; add(sizeSpinner,c); y++;
            JLabel colorLbl = new JLabel("Color"); colorLbl.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            c.gridx=0; c.gridy=y; add(colorLbl,c);
            selectedColor = HighlightCategoryPanel.parse(colorHex, Color.WHITE);
            colorPreview = new ColorPreview(selectedColor, this::setSelectedColor);
            JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0)); colorRow.setOpaque(false);
            JButton customBtn = new JButton("Custom"); customBtn.setMargin(new Insets(2,6,2,6)); customBtn.setFont(FontManager.getRunescapeFont().deriveFont(BASE_FONT_SIZE));
            customBtn.addActionListener(e -> { Color picked = JColorChooser.showDialog(this, "Choose color", selectedColor); if (picked!=null) setSelectedColor(picked); });
            colorRow.add(colorPreview); colorRow.add(customBtn);
            c.gridx=1; add(colorRow,c); y++;
            setPreferredSize(new Dimension(COMPACT_PANEL_WIDTH-20, getPreferredSize().height));
            setMaximumSize(new Dimension(COMPACT_PANEL_WIDTH-20, Integer.MAX_VALUE));
        }
        private void setSelectedColor(Color c){ if (c!=null){ selectedColor=c; colorPreview.setColor(c);} }
        int getDuration(){ return (Integer)durationSpinner.getValue(); }
        int getSizeValue(){ return (Integer)sizeSpinner.getValue(); }
        String getColorHex(){ return String.format("#%02X%02X%02X", selectedColor.getRed(), selectedColor.getGreen(), selectedColor.getBlue()); }
    }
}
