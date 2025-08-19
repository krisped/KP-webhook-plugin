package com.krisped;

import net.runelite.api.ChatMessageType;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
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

    private static final String[] TRIGGERS = {"MESSAGE","WIDGET","PLAYER_SPAWN","PLAYER_DESPAWN","VARBIT","STAT","NPC_SPAWN","NPC_DESPAWN","HITSPLAT_SELF","HITSPLAT_TARGET"};
    private final Set<String> selectedTriggers = new LinkedHashSet<>(); // now: actual selected set (empty = none)
    private final MultiSelectCombo triggerCombo;
    private final TableRowSorter<DefaultTableModel> sorter;

    public KPWebhookDebugWindow(KPWebhookPlugin plugin) {
        super("KP Webhook Debug");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(1000, 480);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(6,6));

        // Columns: Trigger, ID, Player, Value, ChatType
        String[] cols = {"Trigger","ID","Player","Value","ChatType"};
        model = new DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r,int c){ return false; } };
        table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {110,100,180,400,140};
        for (int i=0;i<widths.length;i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.setFillsViewportHeight(true);
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6,4));
        // initialize with ALL selected
        selectedTriggers.addAll(Arrays.asList(TRIGGERS));
        triggerCombo = new MultiSelectCombo(TRIGGERS, selectedTriggers, this::onTriggerSelectionChanged);
        triggerCombo.setPreferredSize(new Dimension(180, triggerCombo.getPreferredSize().height));
        autoScroll = new JCheckBox("Auto-scroll", true);
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> clear());
        searchField = new JTextField(20);
        searchField.setToolTipText("Live search across all columns (case-insensitive)");
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
        private final Set<String> selection;
        private final Runnable onChange;
        private final JButton button = new JButton();
        private final JPopupMenu menu = new JPopupMenu();
        private final java.util.List<JCheckBox> itemChecks = new ArrayList<>();
        private final JCheckBox allCheck = new JCheckBox("All");
        private final String[] sourceItems;
        private static final Dimension POPUP_SIZE = new Dimension(220, 180);
        MultiSelectCombo(String[] items, Set<String> selection, Runnable onChange) {
            this.selection = selection; this.onChange = onChange; this.sourceItems = items;
            setLayout(new BorderLayout());
            buildMenu();
            button.addActionListener(e -> toggleMenu());
            add(button, BorderLayout.CENTER);
            updateVisualState();
        }
        private void buildMenu(){
            menu.removeAll();
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            // All checkbox
            allCheck.setSelected(selection.size() == sourceItems.length);
            allCheck.addActionListener(e -> {
                if (allCheck.isSelected()) {
                    selection.clear();
                    selection.addAll(Arrays.asList(sourceItems));
                } else {
                    selection.clear(); // none selected
                }
                syncItemChecks();
                updateVisualState();
                onChange.run();
                keepOpen();
            });
            panel.add(allCheck);
            panel.add(new JSeparator());
            itemChecks.clear();
            for (String s : sourceItems) {
                JCheckBox cb = new JCheckBox(s, selection.contains(s));
                cb.addActionListener(e -> {
                    if (cb.isSelected()) selection.add(s); else selection.remove(s);
                    // Update All checkbox state
                    allCheck.setSelected(selection.size() == sourceItems.length);
                    updateVisualState();
                    onChange.run();
                    keepOpen();
                });
                itemChecks.add(cb);
                panel.add(cb);
            }
            JScrollPane scroll = new JScrollPane(panel);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            // fixed compact size for readability
            scroll.setPreferredSize(POPUP_SIZE);
            scroll.setMinimumSize(POPUP_SIZE);
            scroll.setMaximumSize(POPUP_SIZE);
            menu.add(scroll);
        }
        private void syncItemChecks(){
            for (JCheckBox cb : itemChecks) {
                cb.setSelected(selection.contains(cb.getText()));
            }
            allCheck.setSelected(selection.size() == sourceItems.length);
        }
        private void updateVisualState(){
            int count = selection.size();
            boolean all = count == sourceItems.length;
            String text;
            if (all) text = "All ("+count+")"; else text = count + " ON"; // show numeric count only when not all
            button.setText(text);
            StringBuilder tip = new StringBuilder();
            if (count == 0) tip.append("None active");
            else if (all) tip.append("All triggers active");
            else tip.append("Active: ").append(String.join(", ", selection));
            button.setToolTipText(tip.toString());
        }
        private void toggleMenu(){
            if (menu.isVisible()) menu.setVisible(false); else menu.show(button, 0, button.getHeight());
        }
        private void keepOpen(){
            SwingUtilities.invokeLater(() -> {
                if (!menu.isVisible()) menu.show(button,0,button.getHeight());
            });
        }
    }

    private void onTriggerSelectionChanged() { /* nothing else needed now */ }

    private boolean acceptsTrigger(String trigger) {
        // empty = none selected; full = all
        if (selectedTriggers.isEmpty()) return false;
        if (selectedTriggers.size() == TRIGGERS.length) return true;
        return selectedTriggers.contains(trigger);
    }

    /* ================= Public logging API ================= */
    public void addMessage(ChatMessageType type, int typeId, String player, String value, String raw) {
        // value prefers plain; raw we can ignore or append if different
        logRow("MESSAGE", String.valueOf(typeId), player, value, type.name());
    }
    public void logPlayerSpawn(boolean despawn, String name, int combat) { logRow(despawn?"PLAYER_DESPAWN":"PLAYER_SPAWN", "", name, "cmb="+combat, ""); }
    public void logWidget(int groupId, Integer childId) { logRow("WIDGET", String.valueOf(groupId), "", childId==null?"":"child="+childId, ""); }
    public void logVarbit(int id, int value) { logRow("VARBIT", String.valueOf(id), "", "value="+value, ""); }
    public void logStat(String skillName, int real, int boosted) { logRow("STAT", skillName, "", "real="+real+" boost="+boosted, ""); }
    public void logNpcSpawn(boolean despawn, String name, int npcId, int combat) { logRow(despawn?"NPC_DESPAWN":"NPC_SPAWN", String.valueOf(npcId), name, "cmb="+combat, ""); }
    public void logHitsplat(boolean self, int amount, String actorName) { logRow(self?"HITSPLAT_SELF":"HITSPLAT_TARGET", "", actorName, "dmg="+amount, ""); }

    private void logRow(String trigger, String id, String player, String val, String chatType) {
        if (!acceptsTrigger(trigger) || !isDisplayable()) return;
        SwingUtilities.invokeLater(() -> {
            if (totalRows >= MAX_ROWS) {
                while (model.getRowCount() > MAX_ROWS - 1) { model.removeRow(0); totalRows--; }
            }
            totalRows++;
            model.addRow(new Object[]{trigger, nz(id), nz(player), nz(val), nz(chatType)});
            updateCount();
            if (autoScroll.isSelected()) {
                int last = model.getRowCount()-1; if (last>=0) table.scrollRectToVisible(table.getCellRect(last,0,true));
            }
        });
    }

    /* ================= Search ================= */
    private void applySearch() {
        String q = searchField.getText();
        if (q == null || q.isBlank()) { sorter.setRowFilter(null); return; }
        final String needle = q.toLowerCase(Locale.ROOT);
        sorter.setRowFilter(new RowFilter<>() {
            @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                for (int i=0;i<entry.getValueCount();i++) {
                    Object v = entry.getValue(i);
                    if (v!=null && v.toString().toLowerCase(Locale.ROOT).contains(needle)) return true;
                }
                return false;
            }
        });
    }

    /* ================= Utility ================= */
    private void clear() { model.setRowCount(0); totalRows = 0; updateCount(); }
    private void updateCount() { countLabel.setText(totalRows + " rows"); }
    private String nz(String s){ return s==null?"":s; }
}
