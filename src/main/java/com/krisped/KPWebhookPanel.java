package com.krisped;

import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.FontManager; // added for Runescape font

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class KPWebhookPanel extends PluginPanel
{
    private final KPWebhookPlugin plugin;

    private final JButton createBtn = new JButton("Create");
    private final JButton debugBtn = new JButton("Debug");
    // Custom panel that will always track the viewport width so headers/rows fill full width
    private final JPanel listContainer = new JPanel() {
        @Override public Dimension getPreferredSize()
        {
            Dimension d = super.getPreferredSize();
            Container p = getParent();
            if (p instanceof JViewport)
            {
                int vw = ((JViewport) p).getWidth();
                if (vw > 0) d.width = vw; // force width to viewport width
            }
            return d;
        }
    };
    private final JScrollPane scroll;

    // Remember collapsed categories between refreshes
    private final Set<String> collapsed = new HashSet<>(); // category key (null -> "undefined")
    private static final String UNDEFINED_KEY = "__undefined__"; // placeholder to avoid null classifier keys
    private static final int ROW_HEIGHT = 28; // fixed visual height for each preset row

    public KPWebhookPanel(KPWebhookPlugin plugin)
    {
        this.plugin = plugin;
        setLayout(new BorderLayout(8,8));
        setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setOpaque(false);
        listContainer.setBorder(BorderFactory.createEmptyBorder(4,0,8,0));
        listContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        listContainer.setAlignmentY(Component.TOP_ALIGNMENT);

        scroll = new JScrollPane(listContainer, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBorder(null);
        scroll.setViewportBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);

        buildUI();
        refreshTable();
    }

    private void buildUI()
    {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6,4));
        top.add(createBtn);
        top.add(debugBtn);
        top.setOpaque(false);
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        createBtn.addActionListener(e -> openDialog(null));
        debugBtn.addActionListener(e -> plugin.openDebugWindow());
    }

    private void openDialog(KPWebhookPreset existing)
    {
        KPWebhookPresetDialog d = new KPWebhookPresetDialog(SwingUtilities.getWindowAncestor(this), plugin, existing);
        d.setVisible(true);
        KPWebhookPreset res = d.getResult();
        if (res != null)
        {
            plugin.addOrUpdate(res);
            refreshTable();
        }
    }

    public void refreshTable()
    {
        listContainer.removeAll();
        List<KPWebhookPreset> presets = plugin.getRules();
        Map<String,List<KPWebhookPreset>> grouped = presets.stream()
                .sorted(Comparator.comparing((KPWebhookPreset p) -> p.getCategory()==null||p.getCategory().isBlank()?"~" : p.getCategory().toLowerCase())
                        .thenComparing(p -> Optional.ofNullable(p.getTitle()).orElse("").toLowerCase()))
                .collect(Collectors.groupingBy(p -> {
                    String c = p.getCategory();
                    return (c==null || c.isBlank())? UNDEFINED_KEY : c;
                }, LinkedHashMap::new, Collectors.toList()));

        if (grouped.isEmpty())
        {
            JLabel none = new JLabel("No presets yet");
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            listContainer.add(none);
        }
        else
        {
            int i = 0; int total = grouped.size();
            for (Map.Entry<String,List<KPWebhookPreset>> e : grouped.entrySet())
            {
                String storedKey = e.getKey();
                String catKey = UNDEFINED_KEY.equals(storedKey) ? null : storedKey;
                String headerText = catKey==null?"undefined":catKey;
                CategorySection section = new CategorySection(headerText, catKey);
                section.setPresets(e.getValue());
                section.setAlignmentX(Component.LEFT_ALIGNMENT);
                listContainer.add(section);
                if (++i < total) listContainer.add(Box.createVerticalStrut(4));
            }
        }
        listContainer.revalidate();
        listContainer.repaint();
    }

    /* ---------------- Category Section ---------------- */
    private class CategorySection extends JPanel
    {
        private final String categoryKey; // null for undefined
        private final JButton headerBtn = new JButton();
        private final JPanel content = new JPanel();
        private boolean expanded;
        private static final float CATEGORY_FONT_SIZE = 16f;
        private final Icon CHEVRON_RIGHT = new ChevronIcon(false);
        private final Icon CHEVRON_DOWN = new ChevronIcon(true);

        CategorySection(String label, String categoryKey)
        {
            this.categoryKey = categoryKey; // may be null
            this.expanded = !collapsed.contains(keyForCollapse());
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            headerBtn.setText(label);
            headerBtn.setFocusPainted(false);
            headerBtn.setContentAreaFilled(false);
            headerBtn.setBorder(new EmptyBorder(4,4,4,4));
            headerBtn.setHorizontalAlignment(SwingConstants.LEFT);
            headerBtn.setFont(FontManager.getRunescapeBoldFont().deriveFont(CATEGORY_FONT_SIZE));
            headerBtn.setIcon(expanded?CHEVRON_DOWN:CHEVRON_RIGHT);
            headerBtn.setIconTextGap(8);
            headerBtn.addActionListener(e -> toggle());
            headerBtn.setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel headerWrapper = new JPanel(new BorderLayout());
            headerWrapper.setOpaque(false);
            headerWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
            headerWrapper.add(headerBtn, BorderLayout.CENTER);
            headerWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, headerBtn.getPreferredSize().height + 8));
            add(headerWrapper);

            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setOpaque(false);
            content.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(content);
            content.setVisible(expanded);
        }

        private String keyForCollapse(){ return categoryKey==null?UNDEFINED_KEY:categoryKey; }

        void setPresets(List<KPWebhookPreset> list)
        {
            content.removeAll();
            for (KPWebhookPreset p : list)
            {
                JPanel row = buildRow(p);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                content.add(row);
                content.add(Box.createVerticalStrut(2)); // small gap between presets
            }
            if (content.getComponentCount() > 0) content.remove(content.getComponentCount()-1); // remove last gap
        }

        private void toggle()
        {
            expanded = !expanded;
            headerBtn.setIcon(expanded?CHEVRON_DOWN:CHEVRON_RIGHT);
            headerBtn.setText(categoryKey==null?"undefined":categoryKey);
            content.setVisible(expanded);
            if (expanded) collapsed.remove(keyForCollapse()); else collapsed.add(keyForCollapse());
            revalidate();
            repaint();
            if (getParent() != null)
            { getParent().revalidate(); getParent().repaint(); }
        }

        @Override public Dimension getMaximumSize(){ return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override public Dimension getPreferredSize()
        {
            Dimension d = super.getPreferredSize();
            if (!expanded || !content.isVisible())
            { return new Dimension(d.width, headerBtn.getPreferredSize().height + 4); }
            return d;
        }
    }

    /* Small chevron icon for category expand/collapse */
    private static class ChevronIcon implements Icon
    {
        private final boolean down;
        private static final int SZ = 12;
        ChevronIcon(boolean down){ this.down = down; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(200,200,200));
            int w = getIconWidth(); int h = getIconHeight();
            if (down)
            {
                // draw chevron down
                int mx = x + w/2;
                int top = y + h/3 - 1;
                int leftX = x + 3;
                int rightX = x + w - 3;
                int bottomY = y + h - 4;
                g2.drawLine(leftX, top, mx, bottomY);
                g2.drawLine(mx, bottomY, rightX, top);
            }
            else
            {
                // draw chevron right
                int left = x + 3;
                int topY = y + 3;
                int bottomY = y + h - 4;
                int rightX = x + w - 4;
                g2.drawLine(left, topY, rightX, (topY + bottomY)/2);
                g2.drawLine(rightX, (topY + bottomY)/2, left, bottomY);
            }
            g2.dispose();
        }
        @Override public int getIconWidth(){ return SZ; }
        @Override public int getIconHeight(){ return SZ; }
    }

    /* ---------------- Row Builder ---------------- */
    private JPanel buildRow(KPWebhookPreset preset)
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Left color indicator (different color if inactive)
        Color accentActive = new Color(0x3A,0x8F,0xE0); // blue-ish accent
        Color accentInactive = new Color(90,90,90);
        JPanel indicator = new JPanel();
        indicator.setPreferredSize(new Dimension(5, ROW_HEIGHT));
        indicator.setMinimumSize(new Dimension(5, ROW_HEIGHT));
        indicator.setMaximumSize(new Dimension(5, ROW_HEIGHT));
        indicator.setBackground(preset.isActive()?accentActive:accentInactive);

        // Background
        Color base = UIManager.getColor("Panel.background");
        if (base == null) base = new Color(45,45,45);
        Color bg = new Color(Math.min(base.getRed()+8,255), Math.min(base.getGreen()+8,255), Math.min(base.getBlue()+8,255));
        row.setOpaque(true);
        row.setBackground(bg);
        row.setBorder(new EmptyBorder(2,0,2,8)); // no left padding so indicator hugs edge
        row.setPreferredSize(new Dimension(0, ROW_HEIGHT));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));

        String titleText = preset.getTitle()!=null? preset.getTitle():"(no title)";
        JLabel titleLbl = new JLabel(titleText);
        titleLbl.setFont(titleLbl.getFont().deriveFont(Font.PLAIN, 15f));
        Color activeColor = new Color(225,225,225);
        Color inactiveColor = new Color(140,140,140);
        titleLbl.setForeground(preset.isActive()?activeColor:inactiveColor);
        titleLbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        titleLbl.addMouseListener(new MouseAdapter(){ @Override public void mouseClicked(MouseEvent e){ if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount()==2) openDialog(preset);} });

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.X_AXIS));
        center.setOpaque(false);
        center.setBorder(new EmptyBorder(0,8,0,0)); // gap between indicator and title
        center.add(titleLbl);

        // Only show trigger TYPE (simplified)
        KPWebhookPreset.TriggerType tt = preset.getTriggerType();
        if (tt != null)
        {
            center.add(Box.createHorizontalStrut(14)); // space between title and trigger label
            JLabel trigLbl = new JLabel(formatTriggerType(tt));
            trigLbl.setFont(trigLbl.getFont().deriveFont(Font.PLAIN, 13f));
            trigLbl.setForeground(new Color(180,180,180));
            center.add(trigLbl);
        }
        center.add(Box.createHorizontalGlue());

        JCheckBox activeBox = new JCheckBox();
        activeBox.setSelected(preset.isActive());
        activeBox.setOpaque(false);
        activeBox.setToolTipText("Aktiver / deaktiver");
        activeBox.setFocusable(false); // avoid focus highlight ripple
        activeBox.setRequestFocusEnabled(false);
        activeBox.addActionListener(e -> { plugin.toggleActive(preset.getId()); refreshTable(); });

        row.add(indicator, BorderLayout.WEST);
        row.add(center, BorderLayout.CENTER);
        row.add(activeBox, BorderLayout.EAST);

        applyPresetTooltip(preset, row, titleLbl, activeBox, center, indicator);

        // Context menu remains same
        JPopupMenu popup = new JPopupMenu();
        JMenuItem editItem = new JMenuItem("Edit");
        JMenuItem runItem = new JMenuItem("Run script");
        JMenuItem deleteItem = new JMenuItem("Delete");
        editItem.addActionListener(e -> openDialog(preset));
        runItem.addActionListener(e -> plugin.manualSend(preset.getId()));
        deleteItem.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Delete '"+preset.getTitle()+"'?", "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
            { plugin.deleteRule(preset.getId()); refreshTable(); }
        });
        popup.add(editItem);
        popup.add(runItem);
        popup.addSeparator();
        popup.add(deleteItem);

        MouseAdapter popupHandler = new MouseAdapter() {
            private void showPopup(MouseEvent e){ if (e.isPopupTrigger()) popup.show(e.getComponent(), e.getX(), e.getY()); }
            @Override public void mousePressed(MouseEvent e){ showPopup(e);} @Override public void mouseReleased(MouseEvent e){ showPopup(e);} };
        for (JComponent c : new JComponent[]{row, center, titleLbl, activeBox, indicator}) c.addMouseListener(popupHandler);

        return row;
    }

    private String formatTriggerType(KPWebhookPreset.TriggerType t)
    {
        String n = t.name().toLowerCase(Locale.ROOT); // e.g. player_spawn
        if (n.isEmpty()) return n;
        // capitalize first char only, keep underscore
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // New tooltip builder for presets
    private void applyPresetTooltip(KPWebhookPreset preset, JComponent... comps)
    {
        String trigType = preset.getTriggerType()==null?"?":preset.getTriggerType().name();
        String trigPretty = Optional.ofNullable(preset.prettyTrigger()).orElse("?");
        String commands = preset.getCommands();
        StringBuilder body = new StringBuilder();
        body.append("<html><b>").append(escapeHtml(preset.getTitle()==null?"(no title)":preset.getTitle())).append("</b><br>");
        body.append("<b>Trigger:</b> ").append(escapeHtml(trigType)).append("<br>");
        body.append("<b>Detalj:</b> ").append(escapeHtml(trigPretty)).append("<br>");
        if (commands != null && !commands.isBlank())
        {
            String[] lines = commands.trim().split("\\r?\\n");
            body.append("<b>Commands ("+lines.length+"):</b><br><pre style='margin:2px 0 2px 0;font-family:monospace;font-size:11px;color:#E0E0E0;'>");
            int maxLines = 8;
            for (int i=0;i<lines.length && i<maxLines;i++)
            {
                String ln = lines[i];
                if (ln.length() > 140) ln = ln.substring(0,137) + "...";
                body.append(escapeHtml(ln)).append("\n");
            }
            if (lines.length > maxLines) body.append("..." + (lines.length-maxLines) + " flere linjer\n");
            body.append("</pre>");
        }
        else
        {
            body.append("<i>Ingen commands</i>");
        }
        body.append("</html>");
        applyTooltipAll(body.toString(), comps);
    }

    // Added back utility to apply tooltip to multiple components
    private void applyTooltipAll(String tooltip, JComponent... comps)
    {
        for (JComponent c : comps) c.setToolTipText(tooltip);
    }

    // Removed old applyCommandsTooltip usage

    // Simple HTML escape helper for tooltip
    private String escapeHtml(String s)
    {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
