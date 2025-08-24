package com.krisped;

import net.runelite.api.ChatMessageType;
import net.runelite.api.AnimationID; // added
import net.runelite.api.GraphicID; // added
import net.runelite.api.Projectile; // new
import net.runelite.api.Actor; // new
import net.runelite.api.Player; // new
import net.runelite.api.NPC; // new

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.lang.reflect.Modifier;
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
    // New: item spawn filter UI + parsed tokens
    private final JTextField itemSpawnFilterField = new JTextField(18);
    private volatile java.util.List<java.util.function.Predicate<ItemSpawnRecord>> itemSpawnPredicates = java.util.List.of();
    private static class ItemSpawnRecord { final int id; final String name; final int qty; ItemSpawnRecord(int id,String name,int qty){this.id=id; this.name=name==null?"":name; this.qty=qty;} }

    private static final String[] TRIGGERS = {
            // Alphabetically sorted trigger keys used in debug window
            "ANIMATION_ANY","ANIMATION_SELF","ANIMATION_TARGET",
            "GEAR_CHANGED",
            "GRAPHIC_ANY","GRAPHIC_SELF","GRAPHIC_TARGET",
            "HITSPLAT_SELF","HITSPLAT_TARGET",
            "INTERACTING",
            "ITEM_SPAWN",
            "MANUAL",
            "MESSAGE","NPC_DESPAWN","NPC_SPAWN","PLAYER_DESPAWN","PLAYER_SPAWN",
            "PROJECTILE","PROJECTILE_SELF",
            "REGION",
            "STAT","TARGET","TARGET_GEAR_CHANGED","TICK","VARBIT","VARPLAYER","WIDGET",
            "XP_DROP"
    };
    private final Set<String> selectedTriggers = new LinkedHashSet<>();
    private final MultiSelectCombo triggerCombo;
    private final TableRowSorter<DefaultTableModel> sorter;

    private Map<Integer,String> animationNames = Collections.emptyMap();
    private Map<Integer,String> graphicNames = Collections.emptyMap();
    private Map<Integer,String> projectileNames = Collections.emptyMap(); // stays empty (no constant class)

    public KPWebhookDebugWindow(KPWebhookPlugin plugin) {
        super("KP Webhook Debug");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(960, 460);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(6,6));

        // New professional layout WITH Desc column restored: Trigger | ID | Type | Name | Desc
        String[] cols = {"Trigger","ID","Type","Name","Desc"};
        model = new DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r,int c){ return false; } };
        table = new JTable(model);
        sorter = new TableRowSorter<>(model); // initialize sorter before using
        table.setRowSorter(sorter);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {110,70,120,160,360}; // shorter, more compact
        for (int i=0;i<widths.length;i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        // Adjust table look for clearer row separation + vertical dividers
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
        // Custom renderer with subtle striping + right border as skillevegg
        DefaultTableCellRenderer base = new DefaultTableCellRenderer();
        base.setOpaque(true);
        TableCellRenderer striped = (tbl, value, isSelected, hasFocus, row, col) -> {
            Component c = base.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col);
            if (!isSelected) {
                c.setBackground(row % 2 == 0 ? new Color(38,38,38) : new Color(44,44,44));
                c.setForeground(new Color(225,225,225));
            }
            if (c instanceof JComponent) {
                Color divider = new Color(60,60,60);
                int right = (col < tbl.getColumnCount()-1)?1:0; // vertical divider except last
                ((JComponent)c).setBorder(BorderFactory.createMatteBorder(0,0,1,right,divider));
            }
            return c;
        };
        for (int i=0;i<table.getColumnCount();i++) table.getColumnModel().getColumn(i).setCellRenderer(striped);
        add(new JScrollPane(table), BorderLayout.CENTER);
        // build name maps after UI
        buildNameMaps();

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
        top.add(new JLabel("Item filter:"));
        itemSpawnFilterField.setToolTipText("Filter ITEM_SPAWN: kommaseparerte id, navn, delnavn eller *wildcards*. Eksempel: bronze_bar,plate,2345");
        itemSpawnFilterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
            private void upd(){ rebuildItemSpawnPredicates(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e){ upd(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e){ upd(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e){ upd(); }
        });
        top.add(itemSpawnFilterField);
        add(top, BorderLayout.NORTH);
    }

    private void buildNameMaps() {
        animationNames = buildConstantIndex(AnimationID.class);
        graphicNames = buildConstantIndex(GraphicID.class);
        projectileNames = Collections.emptyMap(); // no projectile constant ids available
    }
    private Map<Integer,String> buildConstantIndex(Class<?> cls) {
        Map<Integer,String> map = new HashMap<>();
        for (var f : cls.getDeclaredFields()) {
            if (f.getType()==int.class && Modifier.isStatic(f.getModifiers())) {
                try {
                    if (!f.canAccess(null)) {
                        try { f.setAccessible(true); } catch (Exception ignored) {}
                    }
                    int val = f.getInt(null);
                    // Keep first encountered name for a value (avoid later duplicates replacing a more meaningful earlier one)
                    map.putIfAbsent(val, f.getName());
                } catch (Exception ignored) {}
            }
        }
        return map;
    }
    private String animDesc(int id){ return animationNames.getOrDefault(id, ""); }
    private String graphicDesc(int id){ return graphicNames.getOrDefault(id, ""); }
    private String projectileDesc(int id){ return projectileNames.getOrDefault(id, ""); } // will be empty

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
        // Show player name in Name, raw message in Desc
        logRow("MESSAGE", typeId>=0?String.valueOf(typeId):"", type.name(), nz(player), nz(raw));
    }
    public void logPlayerSpawn(boolean despawn, String name, int combat, int localCombat) { /* Changed: show CB <combat> instead of +/- diff */
        String desc = combat>0? ("CB "+combat):""; // new format
        logRow(despawn?"PLAYER_DESPAWN":"PLAYER_SPAWN", combat>0?String.valueOf(combat):"", "PLAYER", nz(name), desc);
    }
    public void logWidget(int groupId, Integer childId) { logRow("WIDGET", childId==null?String.valueOf(groupId):groupId+":"+childId, "WIDGET", "", ""); }
    public void logVarbit(int id, int value) { logRow("VARBIT", String.valueOf(id), String.valueOf(value), "", ""); }
    public void logVarplayer(int id, int value) { logRow("VARPLAYER", String.valueOf(id), String.valueOf(value), "", ""); }
    public void logStat(String skillName, int real, int boosted) {
        String idCol = boosted + "/" + real; // boosted/base
        logRow("STAT", idCol, "STAT", skillName, "");
    }
    public void logXpDrop(String skillName, int gained){
        // Added: XP drop logging (gained XP in ID column, skill name in Name)
        logRow("XP_DROP", String.valueOf(gained), "SKILL", nz(skillName), "");
    }
    public void logNpcSpawn(boolean despawn, String name, int npcId, int combat) { logRow(despawn?"NPC_DESPAWN":"NPC_SPAWN", String.valueOf(npcId), "NPC", nz(name)+(npcId>0?" ("+npcId+")":""), combat>0?"cb="+combat:""); }
    public void logHitsplat(boolean self, int amount, String actorName) { logRow(self?"HITSPLAT_SELF":"HITSPLAT_TARGET", "", self?"PLAYER":"NPC", nz(actorName), "dmg="+amount); }
    // New overload including Actor reference and target flag
    public void logHitsplat(Actor actor, boolean self, boolean isTarget, int amount){
        String name=""; String type=""; if(actor instanceof Player){ type="PLAYER"; try{ name=((Player)actor).getName(); }catch(Exception ignored){} } else if(actor instanceof NPC){ type="NPC"; try{ NPC n=(NPC)actor; name=n.getName(); if(name!=null) name=name+" ("+n.getId()+")"; }catch(Exception ignored){} }
        // Only log TARGET hitsplats for current target; skip non-self, non-target actors
        if(self){
            logRow("HITSPLAT_SELF", "", type, nz(name), "dmg="+amount);
        } else if(isTarget){
            logRow("HITSPLAT_TARGET", "", type, nz(name), "dmg="+amount);
        } // else skip
    }
    public void logAnimation(boolean self, boolean target, int animId) {
        String trig = self? (target?"ANIMATION_ANY":"ANIMATION_SELF") : (target?"ANIMATION_TARGET":"ANIMATION_ANY");
        if (!self && !target) trig = "ANIMATION_ANY";
        logRow(trig, String.valueOf(animId), self?"PLAYER":(target?"NPC":""), "", animDesc(animId));
        if (!"ANIMATION_ANY".equals(trig)) logRow("ANIMATION_ANY", String.valueOf(animId), self?"PLAYER":(target?"NPC":""), "", animDesc(animId));
    }
    // New overload with Actor including name/id
    public void logAnimation(Actor actor, boolean self, boolean target, int animId){
        String type = actor instanceof Player?"PLAYER": actor instanceof NPC?"NPC":""; String name=""; if(actor instanceof Player){ try{name=((Player)actor).getName();}catch(Exception ignored){} } else if(actor instanceof NPC){ try{ NPC n=(NPC)actor; name=n.getName(); if(name!=null) name=name+" ("+n.getId()+")"; }catch(Exception ignored){} }
        String trig = self? (target?"ANIMATION_ANY":"ANIMATION_SELF") : (target?"ANIMATION_TARGET":"ANIMATION_ANY"); if(!self && !target) trig="ANIMATION_ANY"; logRow(trig, String.valueOf(animId), type, nz(name), animDesc(animId)); if(!"ANIMATION_ANY".equals(trig)) logRow("ANIMATION_ANY", String.valueOf(animId), type, nz(name), animDesc(animId)); }
    public void logGraphic(boolean self, boolean target, int graphicId) {
        String trig = self? (target?"GRAPHIC_ANY":"GRAPHIC_SELF") : (target?"GRAPHIC_TARGET":"GRAPHIC_ANY");
        if (!self && !target) trig = "GRAPHIC_ANY";
        logRow(trig, String.valueOf(graphicId), self?"PLAYER":(target?"NPC":""), "", graphicDesc(graphicId));
        if (!"GRAPHIC_ANY".equals(trig)) logRow("GRAPHIC_ANY", String.valueOf(graphicId), self?"PLAYER":(target?"NPC":""), "", graphicDesc(graphicId));
    }
    // New overload with Actor including name/id
    public void logGraphic(Actor actor, boolean self, boolean target, int graphicId){
        String type = actor instanceof Player?"PLAYER": actor instanceof NPC?"NPC":""; String name=""; if(actor instanceof Player){ try{name=((Player)actor).getName();}catch(Exception ignored){} } else if(actor instanceof NPC){ try{ NPC n=(NPC)actor; name=n.getName(); if(name!=null) name=name+" ("+n.getId()+")"; }catch(Exception ignored){} }
        String trig = self? (target?"GRAPHIC_ANY":"GRAPHIC_SELF") : (target?"GRAPHIC_TARGET":"GRAPHIC_ANY"); if(!self && !target) trig="GRAPHIC_ANY"; logRow(trig, String.valueOf(graphicId), type, nz(name), graphicDesc(graphicId)); if(!"GRAPHIC_ANY".equals(trig)) logRow("GRAPHIC_ANY", String.valueOf(graphicId), type, nz(name), graphicDesc(graphicId)); }
    public void logProjectile(String trigger, Projectile p, Actor target) { // new
        if (p == null) return;
        String tgtType = ""; String tgtName = "";
        if (target instanceof net.runelite.api.Player) { tgtType = "PLAYER"; try { tgtName = ((net.runelite.api.Player)target).getName(); } catch (Exception ignored) {} }
        else if (target instanceof net.runelite.api.NPC) { tgtType = "NPC"; try { int nid=((net.runelite.api.NPC)target).getId(); tgtName = ((net.runelite.api.NPC)target).getName(); if(tgtName!=null) tgtName = tgtName + " ("+nid+")"; } catch (Exception ignored) {} }
        logRow(trigger, String.valueOf(p.getId()), tgtType.isEmpty()?"PROJECTILE":tgtType, nz(tgtName), projectileDesc(p.getId()));
    }
    public void logAnimationActor(String trigger, net.runelite.api.Actor a, int animId) {
        if (a == null) return; String type = a instanceof net.runelite.api.NPC?"NPC": a instanceof net.runelite.api.Player?"PLAYER":"";
        String name = ""; String idCol = String.valueOf(animId);
        try { if (a instanceof net.runelite.api.NPC) { name=((net.runelite.api.NPC)a).getName(); } else if (a instanceof net.runelite.api.Player) { name=((net.runelite.api.Player)a).getName(); } } catch (Exception ignored) {}
        logRow(trigger, idCol, type, nz(name), animDesc(animId));
    }
    public void logGraphicActor(String trigger, net.runelite.api.Actor a, int graphicId) {
        if (a == null) return; String type = a instanceof net.runelite.api.NPC?"NPC": a instanceof net.runelite.api.Player?"PLAYER":"";
        String name = ""; String idCol = String.valueOf(graphicId);
        try { if (a instanceof net.runelite.api.NPC) { name=((net.runelite.api.NPC)a).getName(); } else if (a instanceof net.runelite.api.Player) { name=((net.runelite.api.Player)a).getName(); } } catch (Exception ignored) {}
        logRow(trigger, idCol, type, nz(name), graphicDesc(graphicId));
    }
    public void logManual(int ruleId, String title) { logRow("MANUAL", String.valueOf(ruleId), "", nz(title), ""); }
    public void logTargetChange(String oldName, String newName) {
        // Legacy/simple version kept for backward compatibility (no type/id)
        if (newName!=null && !newName.isBlank()) {
            logRow("TARGET", "", "", nz(newName), "");
        } else {
            logRow("TARGET", "", "", "(lost)", "");
        }
    }
    // New: detailed target change with type (PLAYER/NPC) and npc id in parentheses after name
    public void logTargetChangeActor(Actor a){
        if(a==null){ logTargetLost(); return; }
        String type = a instanceof Player? "PLAYER" : a instanceof NPC? "NPC" : "";
        String name = "";
        try {
            if(a instanceof Player){ name = ((Player)a).getName(); }
            else if(a instanceof NPC){ NPC n=(NPC)a; name = n.getName(); int nid = n.getId(); if(name==null||name.isBlank()) name = nid>0? "("+nid+")" : ""; else if(nid>0) name = name + " ("+nid+")"; }
        } catch(Exception ignored){}
        // ID column left blank per requested format (only parentheses in name for NPC)
        logRow("TARGET", "", type, nz(name), "");
    }
    public void logTargetLost(){ logRow("TARGET", "", "", "(lost)", ""); }
    public void logInteractionPlayer(String playerName, int combat){ // remove combat in ID; only show type+name
        logRow("INTERACTING", "", "PLAYER", nz(playerName), "");
    }
    public void logInteractionNpc(String npcName, int npcId){
        String nm = nz(npcName);
        if(!nm.isBlank()) nm = nm + (npcId>0?" ("+npcId+")":"");
        // Blank ID column; npc id only in name parentheses
        logRow("INTERACTING", "", "NPC", nm, "");
    } // new
    public void logItemSpawn(int itemId, String itemName, int qty){
        ItemSpawnRecord rec = new ItemSpawnRecord(itemId, itemName, qty);
        if(!itemSpawnMatches(rec)) return; // filter out
        logRow("ITEM_SPAWN", String.valueOf(itemId), "ITEM", nz(itemName), "qty="+qty);
    } // new
    public void logGearChange(String trigger, String playerName, String addedDesc, String removedDesc){
        // trigger expected: GEAR_CHANGED or TARGET_GEAR_CHANGED
        if(trigger==null) return; String name = playerName==null?"":playerName;
        StringBuilder desc = new StringBuilder();
        if(addedDesc!=null && !addedDesc.isBlank()) desc.append("+"+addedDesc.trim());
        if(removedDesc!=null && !removedDesc.isBlank()){
            if(desc.length()>0) desc.append(' ');
            desc.append("-"+removedDesc.trim());
        }
        logRow(trigger, "", "GEAR", nz(name), desc.toString());
    }

    private String build(String base, String detail) { return detail==null||detail.isBlank()? base : base+" "+detail; }

    private void logRow(String trigger, String id, String type, String name, String desc) {
        if (!acceptsTrigger(trigger) || !isDisplayable()) return;
        SwingUtilities.invokeLater(() -> {
            if (totalRows >= MAX_ROWS) {
                while (model.getRowCount() > MAX_ROWS - 1) { model.removeRow(0); totalRows--; }
            }
            totalRows++;
            model.addRow(new Object[]{nz(trigger), nz(id), nz(type), nz(name), nz(desc)});
            updateCount();
            if (autoScroll.isSelected()) {
                int last = model.getRowCount()-1; if (last>=0) table.scrollRectToVisible(table.getCellRect(last,0,true));
            }
        });
    }

    // New: build predicates for item spawn filtering
    private void rebuildItemSpawnPredicates(){
        String raw = itemSpawnFilterField.getText();
        if(raw==null || raw.isBlank()){ itemSpawnPredicates = java.util.List.of(); return; }
        String[] parts = raw.split(",");
        java.util.List<java.util.function.Predicate<ItemSpawnRecord>> preds = new java.util.ArrayList<>();
        for(String p: parts){
            String tok = p.trim(); if(tok.isEmpty()) continue;
            // numeric id
            try { int id = Integer.parseInt(tok); preds.add(r -> r.id == id); continue; } catch (Exception ignored) {}
            String norm = tok.toLowerCase(Locale.ROOT).replace('_',' ');
            if(norm.contains("*")){
                // wildcard -> regex
                String regex = norm.replace("*", ".*?");
                try { java.util.regex.Pattern pat = java.util.regex.Pattern.compile("^"+regex+"$"); preds.add(r -> pat.matcher(r.name.toLowerCase(Locale.ROOT)).find()); continue; } catch (Exception ignored) {}
            }
            // substring match (case-insensitive) on normalized name
            preds.add(r -> r.name.toLowerCase(Locale.ROOT).contains(norm));
        }
        itemSpawnPredicates = preds.isEmpty()? java.util.List.of() : java.util.List.copyOf(preds);
    }
    private boolean itemSpawnMatches(ItemSpawnRecord rec){
        java.util.List<java.util.function.Predicate<ItemSpawnRecord>> preds = itemSpawnPredicates;
        if(preds==null || preds.isEmpty()) return true; // no filter => accept all
        for(java.util.function.Predicate<ItemSpawnRecord> pr: preds){ try { if(pr.test(rec)) return true; } catch (Exception ignored) {} }
        return false;
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
