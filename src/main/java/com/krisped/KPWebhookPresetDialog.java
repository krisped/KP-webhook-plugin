package com.krisped;

import net.runelite.api.Skill;
import com.krisped.SimpleDocListener;

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
    private final KPWebhookPreset existing;
    private final KPWebhookPlugin plugin;

    private JTabbedPane tabs;

    /* -------- Preset Tab -------- */
    private JTextField titleField;
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
    private JCheckBox useDefaultWebhookBox;
    private JTextField customWebhookField;
    private JTextArea commandsArea;
    private JCheckBox activeBox;

    /* -------- Settings Tab (kategorier) -------- */
    private JPanel settingsRoot;
    private HighlightCategoryPanel outlinePanel;
    private HighlightCategoryPanel tilePanel;
    private HighlightCategoryPanel hullPanel;
    private HighlightCategoryPanel minimapPanel;
    private TextCategoryPanel textOverPanel;
    private TextCategoryPanel textCenterPanel;
    private TextCategoryPanel textUnderPanel;
    // Removed dropdown & overhead legacy
    /* -------- Info Tab -------- */
    private JTextArea infoArea;

    private KPWebhookPreset result;

    /* Default commands */
    private static final String DEFAULT_COMMANDS =
            "# Kommandoer:\n" +
            "#  NOTIFY <tekst>\n" +
            "#  WEBHOOK <tekst>\n" +
            "#  SCREENSHOT [tekst]\n" +
            "#  HIGHLIGHT_OUTLINE\n" +
            "#  HIGHLIGHT_TILE\n" +
            "#  HIGHLIGHT_HULL\n" +
            "#  HIGHLIGHT_MINIMAP\n" +
            "#  (Varighet, farger, blink settes i Settings)\n" +
            "#  TEXT_OVER <tekst>\n" +
            "#  TEXT_CENTER <tekst>\n" +
            "#  TEXT_UNDER <tekst>\n" +
            "# Tokens: {{player}} {{stat}} {{current}} {{value}} {{widgetGroup}} {{widgetChild}} {{time}}\n" +
            "\n" +
            "NOTIFY Du fikk et level i {{stat}}\n" +
            "HIGHLIGHT_OUTLINE\n" +
            "WEBHOOK Level i {{stat}} nå {{current}}\n" +
            "TEXT_OVER Grattis {{player}}!";

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
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6,6,6,6);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        g.weightx = 1;
        int y=0;

        // Title
        g.gridx=0; g.gridy=y; g.weightx=0;
        panel.add(new JLabel("Title:"), g);
        titleField = new JTextField();
        g.gridx=1; g.weightx=1;
        panel.add(titleField, g); y++;

        // Trigger
        g.gridx=0; g.gridy=y; g.weightx=0;
        panel.add(new JLabel("Trigger:"), g);
        triggerTypeBox = new JComboBox<>(buildTriggerModel());
        g.gridx=1; g.weightx=1;
        panel.add(triggerTypeBox, g); y++;

        // Trigger details card container
        g.gridx=0; g.gridy=y; g.gridwidth=2;
        JPanel triggerDetailsPanel = new JPanel(new BorderLayout());
        triggerDetailsPanel.setBorder(new TitledBorder("Trigger Details"));
        triggerCards = buildTriggerCards();
        triggerDetailsPanel.add(triggerCards, BorderLayout.CENTER);
        panel.add(triggerDetailsPanel, g); g.gridwidth=1; y++;

        // Webhook default
        g.gridx=0; g.gridy=y; g.weightx=0;
        panel.add(new JLabel("Use standard webhook:"), g);
        useDefaultWebhookBox = new JCheckBox();
        g.gridx=1; g.weightx=1;
        panel.add(useDefaultWebhookBox, g); y++;

        // Custom webhook
        g.gridx=0; g.gridy=y; g.weightx=0;
        panel.add(new JLabel("Custom webhook:"), g);
        customWebhookField = new JTextField();
        g.gridx=1; g.weightx=1;
        panel.add(customWebhookField, g); y++;

        // Commands
        g.gridx=0; g.gridy=y; g.weightx=0; g.anchor=GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("Commands:"), g);
        commandsArea = new JTextArea(14, 60);
        commandsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane sp = new JScrollPane(commandsArea);
        g.gridx=1; g.weightx=1;
        panel.add(sp, g); y++;

        // Active
        g.gridx=0; g.gridy=y; g.weightx=0; g.anchor=GridBagConstraints.WEST;
        panel.add(new JLabel("Active:"), g);
        activeBox = new JCheckBox();
        activeBox.setSelected(true);
        g.gridx=1; g.weightx=1;
        panel.add(activeBox, g); y++;

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
        settingsRoot.setBorder(new EmptyBorder(10,10,10,10));

        settingsRoot.add(sectionLabel("Highlight"));
        outlinePanel = new HighlightCategoryPanel(existing!=null?existing.getHlOutlineDuration():5,
                existing!=null?existing.getHlOutlineColor():"#FFFF00",
                existing!=null && Boolean.TRUE.equals(existing.getHlOutlineBlink()),
                existing!=null?existing.getHlOutlineWidth():4,
                "Outline");
        tilePanel = new HighlightCategoryPanel(existing!=null?existing.getHlTileDuration():5,
                existing!=null?existing.getHlTileColor():"#00FF88",
                existing!=null && Boolean.TRUE.equals(existing.getHlTileBlink()),
                existing!=null?existing.getHlTileWidth():2,
                "Tile");
        hullPanel = new HighlightCategoryPanel(existing!=null?existing.getHlHullDuration():5,
                existing!=null?existing.getHlHullColor():"#FF55FF",
                existing!=null && Boolean.TRUE.equals(existing.getHlHullBlink()),
                existing!=null?existing.getHlHullWidth():2,
                "Hull");
        minimapPanel = new HighlightCategoryPanel(existing!=null?existing.getHlMinimapDuration():5,
                existing!=null?existing.getHlMinimapColor():"#00FFFF",
                existing!=null && Boolean.TRUE.equals(existing.getHlMinimapBlink()),
                existing!=null?existing.getHlMinimapWidth():2,
                "Minimap");
        settingsRoot.add(new CollapsibleSection(outlinePanel));
        settingsRoot.add(new CollapsibleSection(tilePanel));
        settingsRoot.add(new CollapsibleSection(hullPanel));
        settingsRoot.add(new CollapsibleSection(minimapPanel));

        settingsRoot.add(Box.createVerticalStrut(12));
        settingsRoot.add(sectionLabel("Text"));
        textOverPanel = new TextCategoryPanel(
                existing!=null?existing.getTextOverDuration():80,
                existing!=null?existing.getTextOverSize():16,
                existing!=null?existing.getTextOverColor():"#FFFFFF",
                existing!=null && Boolean.TRUE.equals(existing.getTextOverBlink()),
                "TEXT_OVER");
        textCenterPanel = new TextCategoryPanel(
                existing!=null?existing.getTextCenterDuration():80,
                existing!=null?existing.getTextCenterSize():16,
                existing!=null?existing.getTextCenterColor():"#FFFFFF",
                existing!=null && Boolean.TRUE.equals(existing.getTextCenterBlink()),
                "TEXT_CENTER");
        textUnderPanel = new TextCategoryPanel(
                existing!=null?existing.getTextUnderDuration():80,
                existing!=null?existing.getTextUnderSize():16,
                existing!=null?existing.getTextUnderColor():"#FFFFFF",
                existing!=null && Boolean.TRUE.equals(existing.getTextUnderBlink()),
                "TEXT_UNDER");
        settingsRoot.add(new CollapsibleSection(textOverPanel));
        settingsRoot.add(new CollapsibleSection(textCenterPanel));
        settingsRoot.add(new CollapsibleSection(textUnderPanel));

        return new JScrollPane(settingsRoot);
    }

    private JComponent sectionLabel(String txt)
    {
        JLabel l = new JLabel(txt);
        l.setBorder(new EmptyBorder(8,0,4,0));
        l.setFont(l.getFont().deriveFont(Font.BOLD, 16f));
        return l;
    }

    private JPanel buildInfoTab()
    {
        JPanel p = new JPanel(new BorderLayout());
        infoArea = new JTextArea();
        infoArea.setEditable(false);
        infoArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        infoArea.setText(
                "KP Webhook Plugin - Commands & Settings\n" +
                        "====================================\n\n" +
                        "Commands:\n" +
                        "  NOTIFY <text>                - Show chat message\n" +
                        "  WEBHOOK <text>               - Send webhook message\n" +
                        "  SCREENSHOT [text]            - Send screenshot to webhook\n" +
                        "  HIGHLIGHT_OUTLINE            - Highlight player outline\n" +
                        "  HIGHLIGHT_TILE               - Highlight tile outline\n" +
                        "  HIGHLIGHT_HULL               - Highlight player hull\n" +
                        "  HIGHLIGHT_MINIMAP            - Highlight on minimap (WIP)\n" +
                        "  TEXT_OVER <text>             - Show text above player (style in Settings)\n" +
                        "  TEXT_CENTER <text>           - Show text centered (style)\n" +
                        "  TEXT_UNDER <text>            - Show text under player (style)\n\n" +
                        "Settings fanen inneholder collapsible seksjoner for hver kommando-type.\n" +
                        "Highlight: Duration (ticks), Blink, Color.\n" +
                        "Text: Size, Blink, Color.\n" +
                        "Legg selve teksten direkte inn i Commands-feltet (f.eks: TEXT_OVER Hei!).\n" +
                        "Tokens: {{player}} {{stat}} {{current}} {{value}} {{widgetGroup}} {{widgetChild}} {{time}}\n"
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
            commandsArea.setText(DEFAULT_COMMANDS);
            triggerTypeBox.setSelectedIndex(0);
            useDefaultWebhookBox.setSelected(false);
            customWebhookField.setText("");
            activeBox.setSelected(true);
            updateTriggerVisibility();
            updateWebhookEnable();
            return;
        }

        titleField.setText(r.getTitle());
        if (r.getTriggerType()!=null)
            triggerTypeBox.setSelectedItem(r.getTriggerType().name());
        else
            triggerTypeBox.setSelectedIndex(0);

        useDefaultWebhookBox.setSelected(r.isUseDefaultWebhook());
        customWebhookField.setText(r.getWebhookUrl()!=null ? r.getWebhookUrl() : "");

        if (r.getCommands()==null || r.getCommands().trim().isEmpty())
            commandsArea.setText(DEFAULT_COMMANDS);
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
    }

    /* ================= Save ================= */
    private void onSave()
    {
        String title = titleField.getText().trim();
        if (title.isEmpty()) { JOptionPane.showMessageDialog(this, "Title cannot be empty"); return; }
        String cmds = commandsArea.getText().trim();
        if (cmds.isEmpty()) { JOptionPane.showMessageDialog(this, "Commands cannot be empty"); return; }

        String trigSel = (String) triggerTypeBox.getSelectedItem();
        if (trigSel == null || TRIGGER_PLACEHOLDER.equals(trigSel)) { JOptionPane.showMessageDialog(this, "Please select a trigger."); return; }

        KPWebhookPreset.TriggerType trig = KPWebhookPreset.TriggerType.valueOf(trigSel);
        KPWebhookPreset.StatConfig statCfg = null;
        KPWebhookPreset.WidgetConfig widgetCfg = null;

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

        boolean useDef = useDefaultWebhookBox.isSelected();
        String custom = customWebhookField.getText(); if (custom == null) custom = "";

        if (commandsNeedWebhook(cmds))
        {
            String eff = effectiveWebhookForSave(useDef, custom);
            if (eff == null)
            {
                JOptionPane.showMessageDialog(this,
                        "WEBHOOK/SCREENSHOT kommando funnet, men ingen standard eller custom webhook er satt.",
                        "Validation",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        KPWebhookPreset.KPWebhookPresetBuilder b = KPWebhookPreset.builder()
                .id(existing != null ? existing.getId() : -1)
                .title(title)
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
                .textCenterColor(textCenterPanel.getColorHex())
                .textCenterBlink(textCenterPanel.isBlink())
                .textCenterSize(textCenterPanel.getSizeValue())
                .textCenterDuration(textCenterPanel.getDuration())
                .textUnderColor(textUnderPanel.getColorHex())
                .textUnderBlink(textUnderPanel.isBlink())
                .textUnderSize(textUnderPanel.getSizeValue())
                .textUnderDuration(textUnderPanel.getDuration());

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
                "Kommandoer: NOTIFY, WEBHOOK, SCREENSHOT, HIGHLIGHT_*, TEXT_*\n" +
                "Settings: Alle highlight og text seksjoner vises samtidig.\n" +
                "Blink = av/på hvert tick.\n" +
                "Fargevelger: klikk knappen eller skriv hex (#RRGGBB).";
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
            c.insets = new Insets(2,4,2,4);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            c.weightx = 1;
            int y=0;
            c.gridx=0; c.gridy=y; c.weightx=0; add(new JLabel("Duration"), c);
            durationSpinner = new JSpinner(new SpinnerNumberModel(duration,1,1000,1));
            Dimension sd = durationSpinner.getPreferredSize();
            durationSpinner.setPreferredSize(new Dimension(70, sd.height));
            c.gridx=1; c.weightx=1; add(durationSpinner, c); y++;
            c.gridx=0; c.gridy=y; c.weightx=0; add(new JLabel("Blink"), c);
            blinkBox = new JCheckBox(); blinkBox.setSelected(blink); blinkBox.setMargin(new Insets(0,0,0,0));
            c.gridx=1; add(blinkBox,c); y++;
            c.gridx=0; c.gridy=y; c.weightx=0; add(new JLabel("Color"), c);
            selectedColor = parse(colorHex, Color.YELLOW);
            colorPreview = new ColorPreview(selectedColor, this::setSelectedColor);
            JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
            JButton customBtn = new JButton("Custom");
            customBtn.setMargin(new Insets(2,4,2,4));
            customBtn.addActionListener(e -> {
                Color picked = JColorChooser.showDialog(this, "Velg farge", selectedColor);
                if (picked != null) setSelectedColor(picked);
            });
            colorRow.add(colorPreview);
            colorRow.add(customBtn);
            c.gridx=1; add(colorRow, c);
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
        private final ColorPreview colorPreview;
        private Color selectedColor;
        TextCategoryPanel(int duration, int size, String colorHex, boolean blink, String title)
        {
            super(new GridBagLayout());
            setBorder(new TitledBorder(title));
            setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(2,4,2,4);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            c.weightx = 1;
            int y=0;
            c.gridx=0; c.gridy=y; add(new JLabel("Duration"), c);
            durationSpinner = new JSpinner(new SpinnerNumberModel(duration,1,5000,1));
            Dimension ds = durationSpinner.getPreferredSize();
            durationSpinner.setPreferredSize(new Dimension(70, ds.height));
            c.gridx=1; add(durationSpinner, c); y++;
            c.gridx=0; c.gridy=y; add(new JLabel("Size"), c);
            sizeSpinner = new JSpinner(new SpinnerNumberModel(size,8,72,1));
            Dimension ss = sizeSpinner.getPreferredSize();
            sizeSpinner.setPreferredSize(new Dimension(70, ss.height));
            c.gridx=1; add(sizeSpinner, c); y++;
            c.gridx=0; c.gridy=y; add(new JLabel("Blink"), c);
            blinkBox = new JCheckBox(); blinkBox.setSelected(blink); blinkBox.setMargin(new Insets(0,0,0,0)); c.gridx=1; add(blinkBox, c); y++;
            c.gridx=0; c.gridy=y; add(new JLabel("Color"), c);
            selectedColor = HighlightCategoryPanel.parse(colorHex, Color.WHITE);
            colorPreview = new ColorPreview(selectedColor, this::setSelectedColor);
            JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
            JButton customBtn = new JButton("Custom");
            customBtn.setMargin(new Insets(2,4,2,4));
            customBtn.addActionListener(e -> {
                Color picked = JColorChooser.showDialog(this, "Velg farge", selectedColor);
                if (picked != null) setSelectedColor(picked);
            });
            colorRow.add(colorPreview);
            colorRow.add(customBtn);
            c.gridx=1; add(colorRow, c);
        }
        private void setSelectedColor(Color c){ if (c!=null){ selectedColor=c; colorPreview.setColor(c);} }
        int getDuration(){ return (Integer)durationSpinner.getValue(); }
        int getSizeValue(){ return (Integer)sizeSpinner.getValue(); }
        boolean isBlink(){ return blinkBox.isSelected(); }
        String getColorHex(){ return String.format("#%02X%02X%02X", selectedColor.getRed(), selectedColor.getGreen(), selectedColor.getBlue()); }
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
        CollapsibleSection(JPanel inner)
        {
            setLayout(new BorderLayout());
            setOpaque(false);
            this.content = inner;
            String title = inner instanceof HighlightCategoryPanel ? ((TitledBorder)inner.getBorder()).getTitle() : (inner instanceof TextCategoryPanel ? ((TitledBorder)inner.getBorder()).getTitle():"Section");
            inner.setBorder(null);
            JButton header = new JButton("[+] " + title);
            header.setFocusPainted(false);
            header.setHorizontalAlignment(SwingConstants.LEFT);
            header.setMargin(new Insets(2,6,2,6));
            header.addActionListener(e -> toggle(header, title));
            add(header, BorderLayout.NORTH);
        }
        private void toggle(JButton header, String title)
        {
            expanded = !expanded;
            removeAll();
            add(header, BorderLayout.NORTH);
            header.setText((expanded?"[-] ":"[+] ") + title);
            if (expanded)
            {
                JPanel wrap = new JPanel(new BorderLayout());
                wrap.setBorder(new EmptyBorder(4,20,4,4));
                wrap.add(content, BorderLayout.CENTER);
                add(wrap, BorderLayout.CENTER);
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
        cards.add(new JPanel(), "NONE");
        cards.add(statCard, KPWebhookPreset.TriggerType.STAT.name());
        cards.add(widgetCard, KPWebhookPreset.TriggerType.WIDGET.name());
        return cards;
    }

    private void updateTriggerVisibility()
    {
        CardLayout cl = (CardLayout) (triggerCards.getLayout());
        String sel = (String) triggerTypeBox.getSelectedItem();
        if (sel == null || TRIGGER_PLACEHOLDER.equals(sel) || KPWebhookPreset.TriggerType.MANUAL.name().equals(sel))
            cl.show(triggerCards, "NONE");
        else
            cl.show(triggerCards, sel);
        updateStatEnable();
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
}
