package com.krisped;

import net.runelite.api.ChatMessageType;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/** Enhanced debug window with multi-select trigger dropdown, live search, and concise columns. */
public class KPWebhookDebugWindow extends JFrame {
    private final DefaultTableModel model;
    private final JTable table;
    private final JCheckBox autoScroll;
    private final JTextField searchField;
    private final JLabel countLabel;
    private int totalRows = 0;
    private static final int MAX_ROWS = 800;

    private static final String[] TRIGGERS = {
            // Alphabetical list
            "ANIMATION_ANY","ANIMATION_SELF","ANIMATION_TARGET",
            "GRAPHIC_ANY","GRAPHIC_SELF","GRAPHIC_TARGET",
            "HITSPLAT_SELF","HITSPLAT_TARGET",
            "MESSAGE","NPC_DESPAWN","NPC_SPAWN","PLAYER_DESPAWN","PLAYER_SPAWN",
            "STAT","VARBIT","VARPLAYER","WIDGET"
    };
    private final Set<String> selectedTriggers = new LinkedHashSet<>();
    private final MultiSelectCombo triggerCombo;
    private final TableRowSorter<DefaultTableModel> sorter;

    public KPWebhookDebugWindow(KPWebhookPlugin plugin) {
        super("KP Webhook Debug");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(960, 460);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(6,6));

        // New professional layout: Trigger | ID | Type (with embedded value) | Name
        String[] cols = {"Trigger","ID","Type","Name"};
        model = new DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r,int c){ return false; } };
        table = new JTable(model);
        sorter = new TableRowSorter<>(model); // initialize sorter before using
        table.setRowSorter(sorter);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {140,110,300,360};
        for (int i=0;i<widths.length;i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        // Adjust table look for clearer row separation
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(20);
        table.setFillsViewportHeight(true);
        table.setBackground(new Color(32,32,32));
        table.setForeground(new Color(230,230,230));
        table.setGridColor(new Color(70,70,70));
        table.setSelectionBackground(new Color(55,90,160));
        table.setSelectionForeground(Color.WHITE);
        // Custom renderer adds a subtle bottom divider and striping
        DefaultTableCellRenderer base = new DefaultTableCellRenderer();
        base.setOpaque(true);
        TableCellRenderer striped = (tbl, value, isSelected, hasFocus, row, col) -> {
            Component c = base.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col);
            if (!isSelected) {
                c.setBackground(row % 2 == 0 ? new Color(38,38,38) : new Color(44,44,44));
                c.setForeground(new Color(225,225,225));
            }
            if (c instanceof JComponent) {
                ((JComponent)c).setBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(60,60,60)));
            }
            return c;
        };
        for (int i=0;i<table.getColumnCount();i++) table.getColumnModel().getColumn(i).setCellRenderer(striped);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6,4));
        selectedTriggers.addAll(Arrays.asList(TRIGGERS));
        triggerCombo = new MultiSelectCombo(TRIGGERS, selectedTriggers, this::onTriggerSelectionChanged);
        triggerCombo.setPreferredSize(new Dimension(200, triggerCombo.getPreferredSize().height));
        autoScroll = new JCheckBox("Auto-scroll", true);
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> clear());
        searchField = new JTextField(22);
        searchField.setToolTipText("Live search (all columns, case-insensitive)");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e){ applySearch(); }
            public void removeUpdate(DocumentEvent e){ applySearch(); }
            public void changedUpdate(DocumentEvent e){ applySearch(); }
        });
        countLabel = new JLabel("0 rows");

        top.add(new JLabel("Triggers:"));
        top.add(triggerCombo);
        top.add(autoScroll);
        top.add(new JLabel("Search:"));
        top.add(searchField);
        top.add(clearBtn);
        top.add(countLabel);
        add(top, BorderLayout.NORTH);
    }

    /* ================= Multi-select Combo ================= */
    private static class MultiSelectCombo extends JPanel {
        private final Set<String> selection; private final Runnable onChange;
        private final JButton button = new JButton(); private final JPopupMenu menu = new JPopupMenu();
        private final java.util.List<JCheckBox> itemChecks = new ArrayList<>(); private final JCheckBox allCheck = new JCheckBox("All");
        private final String[] sourceItems; private static final Dimension POPUP_SIZE = new Dimension(220, 180);
        MultiSelectCombo(String[] items, Set<String> selection, Runnable onChange) {
            this.selection = selection; this.onChange = onChange; this.sourceItems = items;
            setLayout(new BorderLayout()); buildMenu(); button.addActionListener(e -> toggleMenu()); add(button, BorderLayout.CENTER); updateVisualState(); }
        private void buildMenu(){
            menu.removeAll(); JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            allCheck.setSelected(selection.size() == sourceItems.length);
            allCheck.addActionListener(e -> { if (allCheck.isSelected()) { selection.clear(); selection.addAll(Arrays.asList(sourceItems)); } else selection.clear(); syncItemChecks(); updateVisualState(); onChange.run(); keepOpen(); });
            panel.add(allCheck); panel.add(new JSeparator()); itemChecks.clear();
            for (String s : sourceItems) { JCheckBox cb = new JCheckBox(s, selection.contains(s)); cb.addActionListener(e -> { if (cb.isSelected()) selection.add(s); else selection.remove(s); allCheck.setSelected(selection.size()==sourceItems.length); updateVisualState(); onChange.run(); keepOpen(); }); itemChecks.add(cb); panel.add(cb);}            JScrollPane scroll = new JScrollPane(panel); scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.setPreferredSize(POPUP_SIZE); scroll.setMinimumSize(POPUP_SIZE); scroll.setMaximumSize(POPUP_SIZE); menu.add(scroll); }
        private void syncItemChecks(){ for (JCheckBox cb : itemChecks) cb.setSelected(selection.contains(cb.getText())); allCheck.setSelected(selection.size()==sourceItems.length); }
        private void updateVisualState(){ int count = selection.size(); boolean all = count == sourceItems.length; String text = all?"All ("+count+")":count+" ON"; button.setText(text); button.setToolTipText(all?"All triggers active":(count==0?"None active":"Active: "+String.join(", ", selection))); }
        private void toggleMenu(){ if (menu.isVisible()) menu.setVisible(false); else menu.show(button, 0, button.getHeight()); }
        private void keepOpen(){ SwingUtilities.invokeLater(() -> { if (!menu.isVisible()) menu.show(button,0,button.getHeight()); }); }
    }

    private void onTriggerSelectionChanged() { }

    private boolean acceptsTrigger(String trigger) {
        if (selectedTriggers.isEmpty()) return false; if (selectedTriggers.size() == TRIGGERS.length) return true; return selectedTriggers.contains(trigger);
    }

    /* ================= Public logging API ================= */
    public void addMessage(ChatMessageType type, int typeId, String player, String value, String raw) {
        logRow("MESSAGE", typeId>=0?String.valueOf(typeId):"", type.name(), nz(player));
    }
    public void logPlayerSpawn(boolean despawn, String name, int combat) { logRow(despawn?"PLAYER_DESPAWN":"PLAYER_SPAWN", "", "PLAYER", nz(name)); }
    public void logWidget(int groupId, Integer childId) { logRow("WIDGET", childId==null?String.valueOf(groupId):groupId+":"+childId, "WIDGET", ""); }
    public void logVarbit(int id, int value) { logRow("VARBIT", String.valueOf(id), String.valueOf(value), ""); }
    public void logVarplayer(int id, int value) { logRow("VARPLAYER", String.valueOf(id), String.valueOf(value), ""); }
    public void logStat(String skillName, int real, int boosted) { logRow("STAT", "", skillName, skillName); }
    public void logNpcSpawn(boolean despawn, String name, int npcId, int combat) { logRow(despawn?"NPC_DESPAWN":"NPC_SPAWN", String.valueOf(npcId), "NPC", nz(name)); }
    public void logHitsplat(boolean self, int amount, String actorName) { logRow(self?"HITSPLAT_SELF":"HITSPLAT_TARGET", "", self?"PLAYER":"NPC", nz(actorName)); }
    public void logAnimation(boolean self, boolean target, int animId) {
        String trig = self? (target?"ANIMATION_ANY":"ANIMATION_SELF") : (target?"ANIMATION_TARGET":"ANIMATION_ANY");
        if (!self && !target) trig = "ANIMATION_ANY";
        logRow(trig, String.valueOf(animId), self?"PLAYER":(target?"NPC":""), "");
        if (!"ANIMATION_ANY".equals(trig)) logRow("ANIMATION_ANY", String.valueOf(animId), self?"PLAYER":(target?"NPC":""), "");
    }
    public void logGraphic(boolean self, boolean target, int graphicId) {
        String trig = self? (target?"GRAPHIC_ANY":"GRAPHIC_SELF") : (target?"GRAPHIC_TARGET":"GRAPHIC_ANY");
        if (!self && !target) trig = "GRAPHIC_ANY";
        logRow(trig, String.valueOf(graphicId), self?"PLAYER":(target?"NPC":""), "");
        if (!"GRAPHIC_ANY".equals(trig)) logRow("GRAPHIC_ANY", String.valueOf(graphicId), self?"PLAYER":(target?"NPC":""), "");
    }
    public void logAnimationActor(String trigger, net.runelite.api.Actor a, int animId) {
        if (a == null) return; String type = a instanceof net.runelite.api.NPC?"NPC": a instanceof net.runelite.api.Player?"PLAYER":"";
        String name = ""; String idCol = String.valueOf(animId); // ID = animation id here
        try {
            if (a instanceof net.runelite.api.NPC) { name=((net.runelite.api.NPC)a).getName(); }
            else if (a instanceof net.runelite.api.Player) { name=((net.runelite.api.Player)a).getName(); }
        } catch (Exception ignored) {}
        logRow(trigger, idCol, type, nz(name));
    }
    public void logGraphicActor(String trigger, net.runelite.api.Actor a, int graphicId) {
        if (a == null) return; String type = a instanceof net.runelite.api.NPC?"NPC": a instanceof net.runelite.api.Player?"PLAYER":"";
        String name = ""; String idCol = String.valueOf(graphicId); // ID = graphic id here
        try {
            if (a instanceof net.runelite.api.NPC) { name=((net.runelite.api.NPC)a).getName(); }
            else if (a instanceof net.runelite.api.Player) { name=((net.runelite.api.Player)a).getName(); }
        } catch (Exception ignored) {}
        logRow(trigger, idCol, type, nz(name));
    }

    private String build(String base, String detail) { return detail==null||detail.isBlank()? base : base+" "+detail; }

    private void logRow(String trigger, String id, String type, String name) {
        if (!acceptsTrigger(trigger) || !isDisplayable()) return;
        SwingUtilities.invokeLater(() -> {
            if (totalRows >= MAX_ROWS) {
                while (model.getRowCount() > MAX_ROWS - 1) { model.removeRow(0); totalRows--; }
            }
            totalRows++;
            model.addRow(new Object[]{nz(trigger), nz(id), nz(type), nz(name)});
            updateCount();
            if (autoScroll.isSelected()) {
                int last = model.getRowCount()-1; if (last>=0) table.scrollRectToVisible(table.getCellRect(last,0,true));
            }
        });
    }

    /* ================= Search ================= */
    private void applySearch() {
        String q = searchField.getText(); if (q == null || q.isBlank()) { sorter.setRowFilter(null); return; }
        final String needle = q.toLowerCase(Locale.ROOT);
        sorter.setRowFilter(new RowFilter<>() { @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) { for (int i=0;i<entry.getValueCount();i++) { Object v = entry.getValue(i); if (v!=null && v.toString().toLowerCase(Locale.ROOT).contains(needle)) return true; } return false; } });
    }

    /* ================= Utility ================= */
    private void clear() { model.setRowCount(0); totalRows = 0; updateCount(); }
    private void updateCount() { countLabel.setText(totalRows + " rows"); }
    private String nz(String s){ return s==null?"":s; }
}
