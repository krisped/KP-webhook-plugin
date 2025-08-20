package com.krisped;

import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class KPWebhookPanel extends PluginPanel {
    private final KPWebhookPlugin plugin;
    private final JButton createBtn = new JButton("Create");
    private final JButton debugTriggersBtn = new JButton("Debug Triggers"); // renamed
    private final JButton debugPresetsBtn = new JButton("Debug Presets"); // new
    private final JPanel listContainer = new JPanel(){ @Override public Dimension getPreferredSize(){ Dimension d=super.getPreferredSize(); Container p=getParent(); if(p instanceof JViewport){int vw=((JViewport)p).getWidth(); if(vw>0) d.width=vw;} return d; } };
    private final JScrollPane scroll;
    private JTabbedPane tabs; // new

    private static final int ROW_HEIGHT = 28;
    private static final String UNDEFINED_KEY = "__undefined__";

    public KPWebhookPanel(KPWebhookPlugin plugin){
        this.plugin=plugin;
        setLayout(new BorderLayout());
        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setOpaque(false);
        listContainer.setBorder(new EmptyBorder(4,0,8,0));
        scroll = new JScrollPane(listContainer, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null); scroll.setViewportBorder(null); scroll.getViewport().setOpaque(false); scroll.setOpaque(false);
        buildUI();
        refreshTable();
    }
    private void buildUI(){
        tabs = new JTabbedPane();
        tabs.addTab("Presets", buildPresetsTab());
        tabs.addTab("Debug", buildDebugTab());
        add(tabs, BorderLayout.CENTER);
    }
    private JPanel buildPresetsTab(){
        JPanel wrap = new JPanel(new BorderLayout()); wrap.setOpaque(false);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT,6,4)); top.setOpaque(false);
        top.add(createBtn);
        wrap.add(top, BorderLayout.NORTH); wrap.add(scroll, BorderLayout.CENTER);
        createBtn.addActionListener(e -> openDialog(null));
        debugTriggersBtn.addActionListener(e -> plugin.openDebugWindow()); // keep wiring (even if button on other tab)
        debugPresetsBtn.addActionListener(e -> plugin.openPresetDebugWindow());
        return wrap;
    }
    private JPanel buildDebugTab(){
        JPanel dbg = new JPanel(); dbg.setLayout(new BoxLayout(dbg, BoxLayout.Y_AXIS)); dbg.setOpaque(false); dbg.setBorder(new EmptyBorder(8,8,8,8));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT,6,4)); row.setOpaque(false);
        row.add(debugTriggersBtn); row.add(debugPresetsBtn);
        JLabel info = new JLabel("Preset debug viser når presets trigges og hvilke kommandoer som kjørte.");
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        dbg.add(row);
        dbg.add(Box.createVerticalStrut(6));
        dbg.add(info);
        dbg.add(Box.createVerticalGlue());
        return dbg;
    }
    private void openDialog(KPWebhookPreset existing){
        KPWebhookPresetDialog d = new KPWebhookPresetDialog(SwingUtilities.getWindowAncestor(this), plugin, existing);
        d.setVisible(true);
        KPWebhookPreset res = d.getResult();
        if(res!=null){ plugin.addOrUpdate(res); refreshTable(); }
    }
    public void refreshTable(){
        listContainer.removeAll();
        List<KPWebhookPreset> presets = plugin.getRules();
        // Group by category (preserve original insertion order within category based on appearance in list)
        Map<String, List<KPWebhookPreset>> grouped = new LinkedHashMap<>();
        for (KPWebhookPreset p : presets){
            String cat = p.getCategory();
            String key = (cat==null || cat.isBlank())? UNDEFINED_KEY : cat;
            grouped.computeIfAbsent(key,k->new ArrayList<>()).add(p);
        }
        // Sort category keys alphabetically but keep UNDEFINED_KEY last
        List<String> keys = new ArrayList<>(grouped.keySet());
        keys.sort((a,b)->{
            if(a.equals(UNDEFINED_KEY) && b.equals(UNDEFINED_KEY)) return 0;
            if(a.equals(UNDEFINED_KEY)) return 1; // undefined last
            if(b.equals(UNDEFINED_KEY)) return -1;
            return a.compareToIgnoreCase(b);
        });
        if (keys.isEmpty()){
            JLabel none = new JLabel("No presets yet"); none.setAlignmentX(Component.LEFT_ALIGNMENT); listContainer.add(none);
        } else {
            for (int i=0;i<keys.size();i++){
                String key = keys.get(i);
                String headerLabel = key.equals(UNDEFINED_KEY)? "Undefined" : key;
                CategorySection section = new CategorySection(headerLabel, key);
                // Sort presets inside category alphabetically by title (case-insensitive), stable by id secondarily for ties
                List<KPWebhookPreset> items = grouped.get(key).stream()
                        .sorted(Comparator.comparing((KPWebhookPreset p) -> Optional.ofNullable(p.getTitle()).orElse("").toLowerCase(Locale.ROOT))
                                .thenComparingInt(KPWebhookPreset::getId))
                        .collect(Collectors.toList());
                section.setPresets(items);
                section.setAlignmentX(Component.LEFT_ALIGNMENT);
                listContainer.add(section);
                if (i < keys.size()-1) listContainer.add(Box.createVerticalStrut(4));
            }
        }
        listContainer.revalidate(); listContainer.repaint();
    }

    // Helper panel that always stretches horizontally and left-aligns content in BoxLayout Y axis parent
    private static class RowPanel extends JPanel {
        RowPanel(LayoutManager lm){ super(lm); }
        @Override public Dimension getMaximumSize(){ Dimension p = getPreferredSize(); return new Dimension(Integer.MAX_VALUE, p.height); }
        @Override public float getAlignmentX(){ return Component.LEFT_ALIGNMENT; }
    }
    private JPanel buildRow(KPWebhookPreset preset){
        final int IND_W = 5;
        RowPanel row = new RowPanel(new BorderLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT+4));
        row.setMinimumSize(new Dimension(0, ROW_HEIGHT+4));
        Color activeGreen = new Color(40,160,60);
        Color inactiveBar = new Color(55,55,55);
        Color base = UIManager.getColor("Panel.background"); if(base==null) base = new Color(45,45,45);
        Color bg = new Color(base.getRed(), base.getGreen(), base.getBlue());
        row.setOpaque(true); row.setBackground(bg); row.setBorder(new EmptyBorder(2,0,2,4));

        JPanel indicator = new JPanel(); indicator.setPreferredSize(new Dimension(IND_W, ROW_HEIGHT)); indicator.setBackground(preset.isActive()? activeGreen : inactiveBar); indicator.setOpaque(true);
        row.add(indicator, BorderLayout.WEST);

        String titleText = preset.getTitle()!=null? preset.getTitle() : "(no title)";
        JLabel titleLbl = new JLabel(titleText);
        titleLbl.setFont(FontManager.getRunescapeFont().deriveFont(Font.PLAIN, 15f));
        titleLbl.setForeground(preset.isActive()?new Color(225,225,225):new Color(150,150,150));
        titleLbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        titleLbl.addMouseListener(new MouseAdapter(){ @Override public void mouseClicked(MouseEvent e){ if(SwingUtilities.isLeftMouseButton(e) && e.getClickCount()==2) openDialog(preset); }});

        JPanel center = new JPanel(); center.setLayout(new BoxLayout(center, BoxLayout.X_AXIS)); center.setOpaque(false); center.setBorder(new EmptyBorder(0,4,0,0));
        center.add(titleLbl);
        if (preset.getTriggerType()!=null){
            center.add(Box.createHorizontalStrut(6));
            JLabel trigLbl = new JLabel("[" + preset.getTriggerType().name() + "]");
            trigLbl.setFont(FontManager.getRunescapeFont().deriveFont(Font.PLAIN, 12f));
            trigLbl.setForeground(new Color(190,190,190));
            center.add(trigLbl);
        }
        center.add(Box.createHorizontalGlue());
        row.add(center, BorderLayout.CENTER);

        JCheckBox activeBox = new JCheckBox(); activeBox.setSelected(preset.isActive()); activeBox.setOpaque(false); activeBox.setFocusable(false); activeBox.setToolTipText("Activate / deactivate");
        activeBox.addActionListener(e -> { plugin.toggleActive(preset.getId()); indicator.setBackground(activeBox.isSelected()?activeGreen:inactiveBar); titleLbl.setForeground(activeBox.isSelected()?new Color(225,225,225):new Color(150,150,150)); row.repaint(); });
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT,4,2)); east.setOpaque(false); east.add(activeBox); row.add(east, BorderLayout.EAST);

        applyPresetTooltip(preset, row, titleLbl, activeBox, indicator, center);
        JPopupMenu popup = new JPopupMenu();
        JMenuItem editItem = new JMenuItem("Edit"); JMenuItem runItem = new JMenuItem("Run script"); JMenuItem duplicateItem = new JMenuItem("Duplicate"); JMenuItem deleteItem = new JMenuItem("Delete");
        editItem.addActionListener(e -> openDialog(preset)); runItem.addActionListener(e -> plugin.manualSend(preset.getId())); duplicateItem.addActionListener(e -> { plugin.duplicateRule(preset.getId()); refreshTable(); }); deleteItem.addActionListener(e -> { if(JOptionPane.showConfirmDialog(this, "Delete '"+preset.getTitle()+"'?", "Confirm", JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION){ plugin.deleteRule(preset.getId()); refreshTable(); }});
        popup.add(editItem); popup.add(runItem); popup.add(duplicateItem); popup.addSeparator(); popup.add(deleteItem);
        MouseAdapter pop = new MouseAdapter(){ private void showPop(MouseEvent e){ if(e.isPopupTrigger()) popup.show(e.getComponent(), e.getX(), e.getY()); } @Override public void mousePressed(MouseEvent e){showPop(e);} @Override public void mouseReleased(MouseEvent e){showPop(e);} };
        for (JComponent c : new JComponent[]{row, center, titleLbl, activeBox, indicator}) c.addMouseListener(pop);
        return row;
    }

    private class CategorySection extends JPanel {
        private final String categoryKey;
        private final JButton headerBtn = new JButton();
        private final JPanel content = new JPanel();
        private boolean expanded = true; // default expanded
        private final Icon CHEVRON_RIGHT = new ChevronIcon(false);
        private final Icon CHEVRON_DOWN = new ChevronIcon(true);
        private static final float CAT_FONT = 15f;
        CategorySection(String label, String key){
            final int IND_W = 5;
            this.categoryKey = key;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setOpaque(false); setAlignmentX(Component.LEFT_ALIGNMENT);
            headerBtn.setText(label); headerBtn.setFocusPainted(false); headerBtn.setContentAreaFilled(false); headerBtn.setBorder(new EmptyBorder(2,0,2,0)); headerBtn.setHorizontalAlignment(SwingConstants.LEFT);
            headerBtn.setFont(FontManager.getRunescapeBoldFont().deriveFont(CAT_FONT)); headerBtn.setIcon(expanded?CHEVRON_DOWN:CHEVRON_RIGHT); headerBtn.setIconTextGap(6);
            headerBtn.addActionListener(e -> toggle());
            JPanel headerRow = new JPanel(new BorderLayout()); headerRow.setOpaque(false); headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel spacer = new JPanel(); spacer.setOpaque(true); spacer.setBackground(new Color(0,0,0,0)); spacer.setPreferredSize(new Dimension(IND_W, ROW_HEIGHT)); headerRow.add(spacer, BorderLayout.WEST);
            headerRow.add(headerBtn, BorderLayout.CENTER);
            add(headerRow);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS)); content.setOpaque(false); content.setAlignmentX(Component.LEFT_ALIGNMENT); add(content); content.setVisible(expanded);
        }
        void setPresets(List<KPWebhookPreset> list){ content.removeAll(); for (int i=0;i<list.size();i++){ content.add(buildRow(list.get(i))); if (i<list.size()-1) content.add(Box.createVerticalStrut(2)); } }
        private void toggle(){ expanded=!expanded; headerBtn.setIcon(expanded?CHEVRON_DOWN:CHEVRON_RIGHT); content.setVisible(expanded); revalidate(); repaint(); }
        @Override public Dimension getMaximumSize(){ return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override public Dimension getPreferredSize(){ Dimension d=super.getPreferredSize(); if(!expanded) return new Dimension(d.width, headerBtn.getPreferredSize().height+4); return d; }
    }
    private static class ChevronIcon implements Icon { private final boolean down; private static final int SZ=11; ChevronIcon(boolean d){down=d;} @Override public void paintIcon(Component c, Graphics g, int x, int y){ Graphics2D g2=(Graphics2D)g.create(); g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g2.setColor(new Color(205,205,205)); int w=SZ,h=SZ; if(down){int mx=x+w/2; int top=y+3; int left=x+2; int right=x+w-2; int bottom=y+h-3; g2.drawLine(left,top,mx,bottom); g2.drawLine(mx,bottom,right,top);} else {int left=x+2; int topY=y+2; int right=x+w-2; int midY=y+h/2; g2.drawLine(left,midY, right, topY); g2.drawLine(right, topY, right, topY); g2.drawLine(right, topY, left, topY);} g2.dispose(); } @Override public int getIconWidth(){return SZ;} @Override public int getIconHeight(){return SZ;} }

    private void applyPresetTooltip(KPWebhookPreset preset, JComponent... comps){
        String trigType = preset.getTriggerType()==null?"?":preset.getTriggerType().name();
        String trigPretty = Optional.ofNullable(preset.prettyTrigger()).orElse("?");
        String commands = preset.getCommands();
        StringBuilder body = new StringBuilder();
        body.append("<html><b>").append(escapeHtml(preset.getTitle()==null?"(no title)":preset.getTitle())).append("</b><br>");
        body.append("<b>Trigger:</b> ").append(escapeHtml(trigType)).append("<br>");
        body.append("<b>Detail:</b> ").append(escapeHtml(trigPretty)).append("<br>");
        if(commands!=null && !commands.isBlank()){
            String[] lines = commands.trim().split("\r?\n");
            body.append("<b>Commands ("+lines.length+"):</b><br><pre style='margin:2px 0;font-family:monospace;font-size:11px;color:#E0E0E0;'>");
            int max=8; for(int i=0;i<lines.length && i<max;i++){ String ln=lines[i]; if(ln.length()>140) ln=ln.substring(0,137)+"..."; body.append(escapeHtml(ln)).append("\n"); }
            if(lines.length>max) body.append("..."+(lines.length-max)+" more\n"); body.append("</pre>");
        } else body.append("<i>No commands</i>");
        body.append("</html>");
        for(JComponent c: comps) c.setToolTipText(body.toString());
    }
    private String escapeHtml(String s){ return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }
}
