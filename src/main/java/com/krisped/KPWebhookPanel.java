package com.krisped;

import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class KPWebhookPanel extends PluginPanel {
    private final KPWebhookPlugin plugin;
    private final JButton createBtn = new JButton("Create");
    private final JButton debugTriggersBtn = new JButton("Debug Triggers");
    private final JButton debugPresetsBtn = new JButton("Debug Presets");
    // Track category expanded states so refreshes don't auto-expand everything
    private final Map<String, Boolean> categoryExpandedStates = new HashMap<>();
    // Stretch panel tracks viewport width without transient re-layout artifacts
    private static class StretchPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize(){ return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction){ return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction){ return Math.max(visibleRect.height - 16, 16); }
        @Override public boolean getScrollableTracksViewportWidth(){ return true; }
        @Override public boolean getScrollableTracksViewportHeight(){ return false; }
    }
    private final JPanel listContainer = new StretchPanel();
    private final JScrollPane scroll;
    private JTabbedPane tabs;
    private JCheckBox showLastTriggeredBox;
    private final Map<Integer, JLabel> lastTriggeredLabels = new HashMap<>();
    private javax.swing.Timer lastTriggeredSecondTimer; // per-second timer for <60s age

    private static final int ROW_HEIGHT = 28;
    private static final String UNDEFINED_KEY = "__undefined__";
    private static final String DND_PREFIX = "PRESET_ID:";

    // ===== Utilities =====
    private static class CrispLabel extends JLabel {
        CrispLabel(String t){ super(t); }
        @Override protected void paintComponent(Graphics g){
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            super.paintComponent(g);
        }
    }
    private static Font standardFont(float size, boolean bold){
        int style = bold? Font.BOLD: Font.PLAIN;
        String[] preferred = {"Segoe UI","Arial","Verdana","SansSerif","Dialog"};
        for(String name: preferred){
            try {
                Font f = new Font(name, style, Math.max(1, Math.round(size)));
                if(f.canDisplay('a') && f.canDisplay('0')) return f.deriveFont(style, size);
            } catch (Exception ignored) {}
        }
        try { Font ui = UIManager.getFont("Label.font"); if(ui!=null) return ui.deriveFont(style, size);} catch (Exception ignored) {}
        return new Font(Font.SANS_SERIF, style, Math.max(1, Math.round(size)));
    }
    private static class ChevronIcon implements Icon {
        private final boolean down; private static final int SZ=11; ChevronIcon(boolean d){down=d;}
        @Override public void paintIcon(Component c, Graphics g, int x, int y){
            Graphics2D g2=(Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(205,205,205));
            int w=SZ,h=SZ;
            if(down){
                int mx=x+w/2; int top=y+3; int left=x+2; int right=x+w-2; int bottom=y+h-3;
                g2.drawLine(left,top,mx,bottom); g2.drawLine(mx,bottom,right,top);
            } else {
                int left=x+2; int topY=y+2; int right=x+w-2; int midY=y+h/2;
                g2.drawLine(left,midY,right,topY); g2.drawLine(right,topY,right,topY); g2.drawLine(right,topY,left,topY);
            }
            g2.dispose();
        }
        @Override public int getIconWidth(){return SZ;}
        @Override public int getIconHeight(){return SZ;}
    }

    private class CategorySection extends JPanel {
        private final String categoryKey;
        private final JButton headerBtn = new JButton();
        private final JPanel content = new JPanel();
        private boolean expanded; // no default here; set via constructor
        private final Icon CHEVRON_RIGHT = new ChevronIcon(false);
        private final Icon CHEVRON_DOWN = new ChevronIcon(true);
        private static final float CAT_FONT = 15f;
        CategorySection(String label, String key, boolean initialExpanded){
            this.expanded = initialExpanded;
            final int IND_W = 5;
            this.categoryKey = key;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            headerBtn.setText(label);
            headerBtn.setFocusPainted(false);
            headerBtn.setContentAreaFilled(false);
            headerBtn.setBorder(new EmptyBorder(2,0,2,0));
            headerBtn.setHorizontalAlignment(SwingConstants.LEFT);
            headerBtn.setFont(FontManager.getRunescapeBoldFont().deriveFont(CAT_FONT));
            headerBtn.setIcon(expanded?CHEVRON_DOWN:CHEVRON_RIGHT);
            headerBtn.setIconTextGap(6);
            headerBtn.addActionListener(e -> toggle());
            JPanel headerRow = new JPanel(new BorderLayout());
            headerRow.setOpaque(false);
            headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel spacer = new JPanel(); spacer.setOpaque(true); spacer.setBackground(new Color(0,0,0,0)); spacer.setPreferredSize(new Dimension(IND_W, ROW_HEIGHT));
            headerRow.add(spacer, BorderLayout.WEST);
            headerRow.add(headerBtn, BorderLayout.CENTER);
            add(headerRow);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS)); content.setOpaque(false); content.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(content);
            content.setVisible(expanded);
            setTransferHandler(new TransferHandler(){
                private Color orig = headerBtn.getForeground();
                @Override public boolean canImport(TransferSupport support){
                    boolean ok=false;
                    try {
                        if(support.isDataFlavorSupported(DataFlavor.stringFlavor)){
                            String s = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                            ok = s!=null && s.startsWith(DND_PREFIX);
                        }
                    }catch(Exception ignored){}
                    headerBtn.setForeground(ok? new Color(255,215,80): orig);
                    return ok;
                }
                @Override public boolean importData(TransferSupport support){
                    headerBtn.setForeground(orig);
                    if(!canImport(support)) return false;
                    try {
                        String s=(String)support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                        int id = Integer.parseInt(s.substring(DND_PREFIX.length()));
                        updatePresetCategory(id, categoryKey);
                        return true;
                    } catch (Exception e){ return false; }
                }
                @Override protected void exportDone(JComponent source, Transferable data, int action){ headerBtn.setForeground(orig); }
            });
        }
        void setPresets(List<KPWebhookPreset> list){
            content.removeAll();
            for(int i=0;i<list.size();i++){
                content.add(buildRow(list.get(i)));
                if(i<list.size()-1) content.add(Box.createVerticalStrut(2));
            }
        }
        private void toggle(){
            expanded = !expanded;
            headerBtn.setIcon(expanded?CHEVRON_DOWN:CHEVRON_RIGHT);
            content.setVisible(expanded);
            // persist state (local + external)
            categoryExpandedStates.put(categoryKey, expanded);
            try { plugin.setCategoryExpandedState(categoryKey, expanded); } catch(Exception ignored){}
            revalidate(); repaint();
            forceLayoutStabilize();
        }
        boolean isExpanded(){ return expanded; }
        String getCategoryKey(){ return categoryKey; }
    }

    // Drag state & glass pane
    private static class DragState { boolean active; KPWebhookPreset preset; Point start; Point current; CategorySection hover; BufferedImage ghostImg; Point ghostOffset=new Point(0,0);}
    private final DragState dragState = new DragState();
    private DragGlassPane dragGlassPane;
    private class DragGlassPane extends JComponent {
        String text; Point loc; BufferedImage img;
        @Override protected void paintComponent(Graphics g){
            if((text==null && img==null) || loc==null) return;
            Graphics2D g2=(Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x=loc.x+12; int y=loc.y+12;
            if(img!=null){
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.85f));
                g2.drawImage(img,x+dragState.ghostOffset.x,y+dragState.ghostOffset.y,null);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,1f));
                g2.setColor(new Color(255,215,80,180));
                g2.drawRect(x+dragState.ghostOffset.x,y+dragState.ghostOffset.y,img.getWidth()-1,img.getHeight()-1);
            } else {
                Font f=FontManager.getRunescapeBoldFont().deriveFont(14f); g2.setFont(f); FontMetrics fm=g2.getFontMetrics();
                int w=fm.stringWidth(text)+14; int h=fm.getHeight()+8;
                g2.setColor(new Color(0,0,0,170)); g2.fillRoundRect(x,y,w,h,10,10);
                g2.setColor(new Color(255,215,80)); g2.drawRoundRect(x,y,w,h,10,10);
                g2.setColor(Color.WHITE); g2.drawString(text, x+7, y+(h-fm.getDescent()-4));
            }
            g2.dispose();
        }
    }
    private void ensureGlassPane(){
        Window w = SwingUtilities.getWindowAncestor(this);
        if(w instanceof RootPaneContainer){
            RootPaneContainer rpc=(RootPaneContainer)w;
            if(!(rpc.getGlassPane() instanceof DragGlassPane)){
                DragGlassPane gp=new DragGlassPane(); gp.setOpaque(false); rpc.setGlassPane(gp); gp.setVisible(true);
            }
            dragGlassPane=(DragGlassPane) rpc.getGlassPane();
        }
    }

    public KPWebhookPanel(KPWebhookPlugin plugin){
        this.plugin = plugin;
        // Load persisted category expanded states from user settings
        try { categoryExpandedStates.putAll(plugin.getCategoryExpandedStates()); } catch(Exception ignored){}
        try { FontManager.getRunescapeFont(); FontManager.getRunescapeBoldFont(); } catch (Exception ignored) {}
        setLayout(new BorderLayout());
        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setOpaque(false);
        listContainer.setBorder(new EmptyBorder(4,0,8,0));
        listContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll = new JScrollPane(listContainer, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null); scroll.setViewportBorder(null); scroll.getViewport().setOpaque(false); scroll.setOpaque(false);
        buildUI();
        refreshTable();
        SwingUtilities.invokeLater(this::ensureGlassPane);
    }

    private void buildUI(){
        tabs = new JTabbedPane();
        tabs.addTab("Presets", buildPresetsTab());
        tabs.addTab("Settings", buildSettingsTab());
        tabs.addTab("Debug", buildDebugTab());
        add(tabs, BorderLayout.CENTER);
    }
    private JPanel buildSettingsTab(){
        JPanel wrap = new JPanel(); wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS)); wrap.setOpaque(false); wrap.setBorder(new EmptyBorder(8,8,8,8));
        showLastTriggeredBox = new JCheckBox("Show last time triggered"); showLastTriggeredBox.setOpaque(false); showLastTriggeredBox.setSelected(plugin.isShowLastTriggered());
        showLastTriggeredBox.addActionListener(e -> { plugin.setShowLastTriggered(showLastTriggeredBox.isSelected()); refreshTable(); });
        wrap.add(showLastTriggeredBox); wrap.add(Box.createVerticalStrut(8));
        JLabel hint=new JLabel("Viser hvor lenge siden hvert preset trigget sist."); hint.setForeground(new Color(190,190,190)); wrap.add(hint);
        wrap.add(Box.createVerticalGlue());
        return wrap;
    }
    private JPanel buildPresetsTab(){
        JPanel wrap = new JPanel(new BorderLayout()); wrap.setOpaque(false);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT,6,4)); top.setOpaque(false); top.add(createBtn);
        wrap.add(top, BorderLayout.NORTH); wrap.add(scroll, BorderLayout.CENTER);
        createBtn.addActionListener(e -> openDialog(null));
        debugTriggersBtn.addActionListener(e -> plugin.openDebugWindow());
        debugPresetsBtn.addActionListener(e -> plugin.openPresetDebugWindow());
        return wrap;
    }
    private JPanel buildDebugTab(){
        JPanel dbg = new JPanel(); dbg.setLayout(new BoxLayout(dbg, BoxLayout.Y_AXIS)); dbg.setOpaque(false); dbg.setBorder(new EmptyBorder(8,8,8,8));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT,6,4)); row.setOpaque(false); row.add(debugTriggersBtn); row.add(debugPresetsBtn);
        JLabel info = new JLabel("Preset debug viser når presets trigges og hvilke kommandoer som kjørte.");
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        dbg.add(row); dbg.add(Box.createVerticalStrut(6)); dbg.add(info); dbg.add(Box.createVerticalGlue());
        return dbg;
    }

    private void openDialog(KPWebhookPreset existing){
        KPWebhookPresetDialog d = new KPWebhookPresetDialog(SwingUtilities.getWindowAncestor(this), plugin, existing);
        d.setVisible(true);
        KPWebhookPreset res = d.getResult();
        if(res!=null){ plugin.addOrUpdate(res); refreshTable(); }
    }

    private void forceLayoutStabilize(){
        // Two-phase revalidation to let BoxLayout recalc after dynamic expand/collapse
        Runnable phase2 = () -> {
            listContainer.invalidate();
            listContainer.validate();
            listContainer.repaint();
            scroll.getViewport().revalidate();
            scroll.getViewport().repaint();
            KPWebhookPanel.this.revalidate();
            KPWebhookPanel.this.repaint();
        };
        listContainer.revalidate();
        scroll.getViewport().revalidate();
        SwingUtilities.invokeLater(phase2);
    }
    public void refreshTable(){
        stopSecondTimer();
        // Capture current expanded states before wiping and persist externally
        for(Component c: listContainer.getComponents()){
            if(c instanceof CategorySection){
                CategorySection cs = (CategorySection)c;
                categoryExpandedStates.put(cs.getCategoryKey(), cs.isExpanded());
                try { plugin.setCategoryExpandedState(cs.getCategoryKey(), cs.isExpanded()); } catch(Exception ignored){}
            }
        }
        listContainer.removeAll(); lastTriggeredLabels.clear();
        List<KPWebhookPreset> presets = plugin.getRules();
        Map<String, List<KPWebhookPreset>> grouped = new LinkedHashMap<>();
        for(KPWebhookPreset p: presets){
            String cat = p.getCategory();
            String key = (cat==null||cat.isBlank())? UNDEFINED_KEY : cat;
            grouped.computeIfAbsent(key,k->new ArrayList<>()).add(p);
        }
        List<String> keys = new ArrayList<>(grouped.keySet());
        keys.sort((a,b)->{
            if(a.equals(UNDEFINED_KEY) && b.equals(UNDEFINED_KEY)) return 0;
            if(a.equals(UNDEFINED_KEY)) return 1;
            if(b.equals(UNDEFINED_KEY)) return -1;
            return a.compareToIgnoreCase(b);
        });
        if(keys.isEmpty()){
            JLabel none=new JLabel("No presets yet"); none.setAlignmentX(Component.LEFT_ALIGNMENT); listContainer.add(none);
        } else {
            for(int i=0;i<keys.size();i++){
                String key = keys.get(i);
                List<KPWebhookPreset> items = grouped.get(key).stream()
                        .sorted(Comparator.comparing((KPWebhookPreset p)->Optional.ofNullable(p.getTitle()).orElse("").toLowerCase(Locale.ROOT))
                                .thenComparingInt(KPWebhookPreset::getId))
                        .collect(Collectors.toList());
                int total = items.size();
                int active = (int) items.stream().filter(KPWebhookPreset::isActive).count();
                String baseLabel = key.equals(UNDEFINED_KEY)? "Undefined" : key;
                String headerLabel = baseLabel + " (" + active + "/" + total + ")";
                CategorySection section = new CategorySection(headerLabel, key, categoryExpandedStates.getOrDefault(key, true));
                section.setPresets(items);
                section.setAlignmentX(Component.LEFT_ALIGNMENT);
                listContainer.add(section);
                if(i<keys.size()-1) listContainer.add(Box.createVerticalStrut(4));
            }
        }
        listContainer.revalidate(); listContainer.repaint();
        maybeStartSecondTimerForAll();
        forceLayoutStabilize();
    }

    public void updateAllLastTriggeredTimes(){
        if(!plugin.isShowLastTriggered()) return;
        try {
            List<KPWebhookPreset> presets = plugin.getRules();
            Map<Integer, Long> idToTs = new HashMap<>();
            for(KPWebhookPreset p: presets){ if(p!=null) idToTs.put(p.getId(), p.getLastTriggeredAt()); }
            long now = System.currentTimeMillis();
            for(Map.Entry<Integer, JLabel> e: lastTriggeredLabels.entrySet()){
                JLabel lbl = e.getValue(); if(lbl==null) continue;
                long ts = idToTs.getOrDefault(e.getKey(), 0L);
                lbl.setText(formatLastTriggered(ts));
            }
            maybeStartOrStopSecondTimer(now);
        } catch (Exception ignored){}
    }

    private static class RowPanel extends JPanel {
        RowPanel(LayoutManager lm){ super(lm); }
        @Override public Dimension getMaximumSize(){ Dimension p=getPreferredSize(); return new Dimension(Integer.MAX_VALUE, p.height);}
        @Override public float getAlignmentX(){ return Component.LEFT_ALIGNMENT; }
    }

    private JPanel buildRow(KPWebhookPreset preset){
        final int IND_W=5;
        RowPanel row = new RowPanel(new BorderLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT+4));
        row.setMinimumSize(new Dimension(0, ROW_HEIGHT+4));
        // Provide a preferred height only (width will be stretched by BoxLayout via max size and listContainer width override)
        row.setPreferredSize(new Dimension(0, ROW_HEIGHT+4));
        Color activeGreen=new Color(40,160,60); Color inactiveBar=new Color(55,55,55);
        Color base = UIManager.getColor("Panel.background"); if(base==null) base=new Color(45,45,45);
        row.setOpaque(true); row.setBackground(new Color(base.getRed(), base.getGreen(), base.getBlue())); row.setBorder(new EmptyBorder(2,0,2,4));
        JPanel indicator=new JPanel(); indicator.setPreferredSize(new Dimension(IND_W, ROW_HEIGHT)); indicator.setBackground(preset.isActive()?activeGreen:inactiveBar); indicator.setOpaque(true); row.add(indicator, BorderLayout.WEST);
        String titleText = preset.getTitle()!=null? preset.getTitle():"(no title)"; JLabel titleLbl=new JLabel(titleText); titleLbl.setFont(FontManager.getRunescapeFont().deriveFont(Font.PLAIN,15f)); titleLbl.setForeground(preset.isActive()?new Color(225,225,225):new Color(150,150,150)); titleLbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); titleLbl.addMouseListener(new MouseAdapter(){ @Override public void mouseClicked(MouseEvent e){ if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==2) openDialog(preset); }});
        JPanel center=new JPanel(); center.setLayout(new BoxLayout(center, BoxLayout.X_AXIS)); center.setOpaque(false); center.setBorder(new EmptyBorder(0,4,0,0)); center.add(titleLbl);
        if(preset.getTriggerType()!=null){ center.add(Box.createHorizontalStrut(6)); JLabel trigLbl=new CrispLabel("["+preset.getTriggerType().name()+"]"); trigLbl.setFont(standardFont(11f,true)); trigLbl.setForeground(new Color(205,205,205)); center.add(trigLbl);} center.add(Box.createHorizontalGlue());
        center.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
        row.add(center, BorderLayout.CENTER);
        JCheckBox activeBox=new JCheckBox(); activeBox.setSelected(preset.isActive()); activeBox.setOpaque(false); activeBox.setFocusable(false); activeBox.setToolTipText("Activate / deactivate"); activeBox.addActionListener(e -> { plugin.toggleActive(preset.getId()); refreshTable(); });
        JPanel east=new JPanel(); east.setOpaque(false); east.setLayout(new BoxLayout(east, BoxLayout.X_AXIS)); east.add(activeBox); east.add(Box.createHorizontalStrut(2));
        east.setAlignmentY(Component.CENTER_ALIGNMENT);
        east.setBorder(new EmptyBorder(0,4,0,0));
        row.add(east, BorderLayout.EAST);
        applyPresetTooltip(preset, row, titleLbl, activeBox, indicator, center);
        JPopupMenu popup=new JPopupMenu(); JMenuItem editItem=new JMenuItem("Edit"); JMenuItem runItem=new JMenuItem("Run script"); JMenuItem duplicateItem=new JMenuItem("Duplicate"); JMenuItem deleteItem=new JMenuItem("Delete"); editItem.addActionListener(e->openDialog(preset)); runItem.addActionListener(e->plugin.manualSend(preset.getId())); duplicateItem.addActionListener(e->{ plugin.duplicateRule(preset.getId()); refreshTable(); }); deleteItem.addActionListener(e->{ if(JOptionPane.showConfirmDialog(this, "Delete '"+preset.getTitle()+"'?", "Confirm", JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION){ plugin.deleteRule(preset.getId()); refreshTable(); }}); popup.add(editItem); popup.add(runItem); popup.add(duplicateItem); popup.addSeparator(); popup.add(deleteItem); MouseAdapter pop=new MouseAdapter(){ private void showPop(MouseEvent e){ if(e.isPopupTrigger()) popup.show(e.getComponent(), e.getX(), e.getY()); } @Override public void mousePressed(MouseEvent e){showPop(e);} @Override public void mouseReleased(MouseEvent e){showPop(e);} }; for(JComponent c:new JComponent[]{row,center,titleLbl,activeBox,indicator}) c.addMouseListener(pop);
        MouseAdapter dragAdapter = new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){ dragState.start=e.getPoint(); dragState.current=null; dragState.active=false; dragState.preset=preset; dragState.ghostImg=captureRowImage(row); dragState.ghostOffset=new Point(-e.getX(), -e.getY()); }
            @Override public void mouseDragged(MouseEvent e){ if(dragState.preset!=preset) return; Point pScreen=e.getLocationOnScreen(); if(dragState.start!=null && !dragState.active){ Point now=e.getPoint(); if(now.distance(dragState.start)>5){ dragState.active=true; ensureGlassPane(); if(dragGlassPane!=null){ dragGlassPane.text=preset.getTitle()!=null? preset.getTitle():"(no title)"; dragGlassPane.img=dragState.ghostImg; } } } if(dragState.active){ dragState.current=pScreen; updateHoverCategory(pScreen); updateGhost(); } }
            @Override public void mouseReleased(MouseEvent e){ if(dragState.active){ commitDrag(); } resetDrag(); }
        }; for(JComponent c:new JComponent[]{row,center,titleLbl,indicator}){ c.addMouseListener(dragAdapter); c.addMouseMotionListener(dragAdapter);}
        // Common border wrapper for clearer grouping (including last-triggered label if present)
        Color borderColor = new Color(70,70,70); // subtle
        if(plugin.isShowLastTriggered() && preset.isActive()){
            JLabel lastLbl = new CrispLabel(formatLastTriggered(preset.getLastTriggeredAt())); lastLbl.setFont(standardFont(11f,false)); lastLbl.setForeground(new Color(175,175,175)); lastLbl.setBorder(new EmptyBorder(0,8,2,0)); lastTriggeredLabels.put(preset.getId(), lastLbl);
            JPanel holder=new JPanel(new BorderLayout()); holder.setOpaque(false); holder.add(row, BorderLayout.CENTER); holder.setAlignmentX(Component.LEFT_ALIGNMENT); holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT+4));
            JPanel below=new JPanel(); below.setLayout(new BoxLayout(below, BoxLayout.X_AXIS)); below.setOpaque(false); below.add(lastLbl); below.add(Box.createHorizontalGlue()); below.setAlignmentX(Component.LEFT_ALIGNMENT); below.setMaximumSize(new Dimension(Integer.MAX_VALUE, lastLbl.getPreferredSize().height+4));
            JPanel wrap=new JPanel(); wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS)); wrap.setOpaque(false); wrap.add(holder); wrap.add(below); wrap.setAlignmentX(Component.LEFT_ALIGNMENT); wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, holder.getPreferredSize().height + below.getPreferredSize().height));
            JPanel outer = new JPanel(); outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS)); outer.setOpaque(false); outer.setAlignmentX(Component.LEFT_ALIGNMENT); outer.add(wrap); outer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(borderColor,1,true), new EmptyBorder(2,2,2,2))); outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrap.getMaximumSize().height+4));
            return outer;
        }
        JPanel outer = new JPanel(); outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS)); outer.setOpaque(false); outer.setAlignmentX(Component.LEFT_ALIGNMENT); outer.add(row); outer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(borderColor,1,true), new EmptyBorder(2,2,2,2))); outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getMaximumSize().height+4));
        return outer;
    }

    private String formatLastTriggered(long ts){ if(ts<=0) return "(aldri trigget)"; long diff=System.currentTimeMillis()-ts; long sec=diff/1000; if(sec<60) return "Sist trigget for "+sec+"s siden"; long min=sec/60; if(min<60) return "Sist trigget for "+min+" min siden"; long hr=min/60; if(hr<24) return "Sist trigget for "+hr+" t siden"; long days=hr/24; return "Sist trigget for "+days+" d siden"; }
    public void updateLastTriggered(int ruleId){ if(!plugin.isShowLastTriggered()) return; JLabel lbl=lastTriggeredLabels.get(ruleId); if(lbl!=null){ SwingUtilities.invokeLater(() -> { long ts = plugin.getRules().stream().filter(r->r.getId()==ruleId).map(KPWebhookPreset::getLastTriggeredAt).findFirst().orElse(0L); lbl.setText(formatLastTriggered(ts)); lbl.revalidate(); lbl.repaint(); maybeStartSecondTimerForTimestamp(ts); }); } }

    // === Second-level timer logic ===
    private void maybeStartSecondTimerForTimestamp(long ts){ if(ts<=0) return; long now=System.currentTimeMillis(); if(now-ts<60_000L) startSecondTimer(); }
    private void maybeStartSecondTimerForAll(){ long now=System.currentTimeMillis(); try { for(KPWebhookPreset p: plugin.getRules()){ if(p!=null && now - p.getLastTriggeredAt() < 60_000L){ startSecondTimer(); break; } } } catch (Exception ignored){} }
    private void maybeStartOrStopSecondTimer(long now){ boolean need=false; try { for(KPWebhookPreset p: plugin.getRules()){ if(p!=null && now - p.getLastTriggeredAt() < 60_000L){ need=true; break; } } } catch(Exception ignored){} if(need) startSecondTimer(); else stopSecondTimer(); }
    private void startSecondTimer(){ if(lastTriggeredSecondTimer!=null && lastTriggeredSecondTimer.isRunning()) return; lastTriggeredSecondTimer=new javax.swing.Timer(1000, e->updateSecondPrecision()); lastTriggeredSecondTimer.setRepeats(true); lastTriggeredSecondTimer.start(); }
    private void stopSecondTimer(){ if(lastTriggeredSecondTimer!=null){ lastTriggeredSecondTimer.stop(); lastTriggeredSecondTimer=null; } }
    private void updateSecondPrecision(){ if(!plugin.isShowLastTriggered()){ stopSecondTimer(); return;} long now=System.currentTimeMillis(); boolean any=false; try { for(KPWebhookPreset p: plugin.getRules()){ if(p==null || !p.isActive()) continue; long ts=p.getLastTriggeredAt(); if(ts<=0) continue; long age=now-ts; JLabel lbl=lastTriggeredLabels.get(p.getId()); if(lbl!=null) lbl.setText(formatLastTriggered(ts)); if(age<60_000L) any=true; } } catch(Exception ignored){} if(!any) stopSecondTimer(); }

    private void applyPresetTooltip(KPWebhookPreset preset, JComponent... comps){
        String trigType = preset.getTriggerType()==null?"?":preset.getTriggerType().name();
        String trigPretty = Optional.ofNullable(preset.prettyTrigger()).orElse("?");
        String commands = preset.getCommands();
        StringBuilder body=new StringBuilder();
        body.append("<html><b>").append(escapeHtml(preset.getTitle()==null?"(no title)":preset.getTitle())).append("</b><br>");
        body.append("<b>Trigger:</b> ").append(escapeHtml(trigType)).append("<br>");
        body.append("<b>Detail:</b> ").append(escapeHtml(trigPretty)).append("<br>");
        if(commands!=null && !commands.isBlank()){
            String[] lines = commands.trim().split("\r?\n");
            body.append("<b>Commands (").append(lines.length).append("):</b><br><pre style='margin:2px 0;font-family:monospace;font-size:11px;color:#E0E0E0;'>");
            int max=8; for(int i=0;i<lines.length && i<max;i++){ String ln=lines[i]; if(ln.length()>140) ln=ln.substring(0,137)+"..."; body.append(escapeHtml(ln)).append("\n"); }
            if(lines.length>max) body.append("...").append(lines.length-max).append(" more\n");
            body.append("</pre>");
        } else body.append("<i>No commands</i>");
        body.append("</html>");
        for(JComponent c: comps) c.setToolTipText(body.toString());
    }
    private String escapeHtml(String s){ return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }

    private void updatePresetCategory(int id, String targetKey){
        List<KPWebhookPreset> list = plugin.getRules();
        for(KPWebhookPreset p: list){
            if(p.getId()==id){
                String newCat = UNDEFINED_KEY.equals(targetKey)? null: targetKey;
                if(Objects.equals(p.getCategory(), newCat)) return;
                p.setCategory(newCat); plugin.addOrUpdate(p); refreshTable(); return;
            }
        }
    }
    private void movePresetCategory(KPWebhookPreset preset, String category){ if(preset==null) return; preset.setCategory(category); plugin.addOrUpdate(preset); refreshTable(); }

    private void updateHoverCategory(Point screen){
        CategorySection newHover=null;
        if(screen!=null){
            for(Component c: listContainer.getComponents()){
                if(c instanceof CategorySection){
                    Rectangle b=c.getBounds(); Point topLeft=new Point(0,0); SwingUtilities.convertPointToScreen(topLeft, c); Rectangle sb=new Rectangle(topLeft.x, topLeft.y, b.width, b.height);
                    if(sb.contains(screen)){ newHover=(CategorySection)c; break; }
                }
            }
        }
        if(newHover!=dragState.hover){ if(dragState.hover!=null) setHeaderHighlight(dragState.hover,false); dragState.hover=newHover; if(dragState.hover!=null) setHeaderHighlight(dragState.hover,true); }
    }
    private void setHeaderHighlight(CategorySection sec, boolean on){ if(sec==null) return; for(Component ch: sec.getComponents()){ if(ch instanceof JPanel){ for(Component inner: ((JPanel)ch).getComponents()){ if(inner instanceof JButton){ JButton b=(JButton)inner; b.setForeground(on? new Color(255,215,80): UIManager.getColor("Label.foreground")); } } } } sec.repaint(); }
    private void updateGhost(){ if(dragGlassPane==null) return; if(!dragState.active){ dragGlassPane.text=null; dragGlassPane.loc=null; dragGlassPane.img=null; dragGlassPane.repaint(); return; } Point gpPoint=new Point(dragState.current); SwingUtilities.convertPointFromScreen(gpPoint, dragGlassPane); dragGlassPane.loc=gpPoint; dragGlassPane.repaint(); }
    private void commitDrag(){ if(dragState.preset!=null && dragState.hover!=null){ String key=dragState.hover.getCategoryKey(); String target= key.equals(UNDEFINED_KEY)? null: key; movePresetCategory(dragState.preset, target); } }
    private void resetDrag(){ if(dragState.hover!=null) setHeaderHighlight(dragState.hover,false); dragState.active=false; dragState.preset=null; dragState.start=null; dragState.current=null; dragState.ghostImg=null; if(dragGlassPane!=null){ dragGlassPane.text=null; dragGlassPane.loc=null; dragGlassPane.img=null; dragGlassPane.repaint(); } }
    private BufferedImage captureRowImage(JComponent comp){ int w=comp.getWidth(); int h=comp.getHeight(); if(w<=0||h<=0) return null; BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB); Graphics2D g2=img.createGraphics(); comp.paint(g2); g2.dispose(); return img; }
}
