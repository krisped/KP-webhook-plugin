package com.krisped;

import net.runelite.client.ui.PluginPanel;

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
    private final JPanel listContainer = new JPanel();
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

        // Create main container with invisible border and much larger area
        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setOpaque(false); // transparent background
        listContainer.setBorder(BorderFactory.createEmptyBorder(20,20,20,20)); // just padding, no visible border

        scroll = new JScrollPane(listContainer, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBorder(null); // no border on scroll pane
        scroll.setPreferredSize(new Dimension(600, 800)); // Much larger area (was 400x600)
        buildUI();
        refreshTable();
    }

    private void buildUI()
    {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6,4));
        top.add(createBtn);
        top.setOpaque(false);
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        createBtn.addActionListener(e -> openDialog(null));
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
        // Group by category key (use placeholder instead of null to avoid NPE in groupingBy)
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
                String catKey = UNDEFINED_KEY.equals(storedKey) ? null : storedKey; // convert placeholder back to null
                String headerText = catKey==null?"undefined":catKey;
                CategorySection section = new CategorySection(headerText, catKey);
                section.setPresets(e.getValue());
                section.setAlignmentX(Component.LEFT_ALIGNMENT); // ensure full-width flow
                listContainer.add(section);
                if (++i < total) listContainer.add(Box.createVerticalStrut(4));
            }
        }
        // Removed vertical glue to avoid large empty expandable space under last category
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

        CategorySection(String label, String categoryKey)
        {
            this.categoryKey = categoryKey; // may be null
            this.expanded = !collapsed.contains(keyForCollapse());
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            headerBtn.setText(titleText(label));
            headerBtn.setFocusPainted(false);
            headerBtn.setContentAreaFilled(false);
            headerBtn.setBorder(new EmptyBorder(2,0,2,0));
            headerBtn.setHorizontalAlignment(SwingConstants.LEFT);
            headerBtn.setFont(headerBtn.getFont().deriveFont(Font.BOLD, 14f));
            headerBtn.addActionListener(e -> toggle());
            headerBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(headerBtn);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setOpaque(false);
            content.setAlignmentX(Component.LEFT_ALIGNMENT);
            // Always add content panel, but control visibility instead of add/remove
            add(content);
            content.setVisible(expanded);
        }

        private String keyForCollapse(){ return categoryKey==null?UNDEFINED_KEY:categoryKey; }

        private String titleText(String label){ return (expanded?"▼ ":"▶ ") + label; }

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
            headerBtn.setText(titleText(categoryKey==null?"undefined":categoryKey));

            // Use visibility instead of add/remove for instant response
            content.setVisible(expanded);

            if (expanded)
            {
                collapsed.remove(keyForCollapse());
            }
            else
            {
                collapsed.add(keyForCollapse());
            }

            // Force immediate layout update
            revalidate();
            repaint();

            // Update parent immediately
            if (getParent() != null)
            {
                getParent().revalidate();
                getParent().repaint();
            }
        }

        @Override public Dimension getMaximumSize(){ return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        @Override public Dimension getPreferredSize()
        {
            Dimension d = super.getPreferredSize();
            // When collapsed, only show header height
            if (!expanded || !content.isVisible())
            {
                return new Dimension(d.width, headerBtn.getPreferredSize().height + 4);
            }
            return d;
        }
    }

    /* ---------------- Row Builder ---------------- */
    private JPanel buildRow(KPWebhookPreset preset)
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Subtle RuneLite-esque background
        Color base = UIManager.getColor("Panel.background");
        if (base == null) base = new Color(45,45,45);
        Color bg = new Color(Math.min(base.getRed()+8,255), Math.min(base.getGreen()+8,255), Math.min(base.getBlue()+8,255));
        row.setOpaque(true);
        row.setBackground(bg);
        row.setBorder(new EmptyBorder(4,8,4,8));
        row.setPreferredSize(new Dimension(0, ROW_HEIGHT));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));

        String titleText = preset.getTitle()!=null? preset.getTitle():"(no title)";
        JLabel titleLbl = new JLabel("> " + titleText);
        titleLbl.setFont(titleLbl.getFont().deriveFont(Font.PLAIN, 15f));
        Color activeColor = new Color(225,225,225);
        Color inactiveColor = new Color(140,140,140);
        titleLbl.setForeground(preset.isActive()?activeColor:inactiveColor);
        titleLbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        titleLbl.addMouseListener(new MouseAdapter(){ @Override public void mouseClicked(MouseEvent e){ if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount()==2) openDialog(preset);} });

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.X_AXIS));
        center.setOpaque(false);
        center.add(titleLbl);

        String trig = preset.prettyTrigger();
        if (trig != null && !trig.isBlank())
        {
            center.add(Box.createHorizontalStrut(8));
            JLabel trigLbl = new JLabel();
            String trigEsc = escapeHtml(trig);
            // Smaller trigger text that stays on one line
            trigLbl.setText(trigEsc); // removed HTML formatting to prevent line wrapping
            trigLbl.setFont(trigLbl.getFont().deriveFont(Font.PLAIN, 12f)); // smaller font
            trigLbl.setForeground(new Color(180,180,180)); // slightly dimmed
            // Ensure single line display
            trigLbl.setHorizontalAlignment(SwingConstants.LEFT);
            center.add(trigLbl);
        }
        center.add(Box.createHorizontalGlue());

        // Simplified checkbox placement - directly in BorderLayout.EAST
        JCheckBox activeBox = new JCheckBox();
        activeBox.setSelected(preset.isActive());
        activeBox.setOpaque(false);
        activeBox.setToolTipText("Aktiver/deaktiver");
        activeBox.addActionListener(e -> { plugin.toggleActive(preset.getId()); refreshTable(); });

        row.add(center, BorderLayout.CENTER);
        row.add(activeBox, BorderLayout.EAST);

        // Tooltip preview for commands applied to all interactive child components
        applyCommandsTooltip(preset, row, titleLbl, activeBox, center);

        // Context menu
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
            @Override public void mousePressed(MouseEvent e){ showPopup(e);}
            @Override public void mouseReleased(MouseEvent e){ showPopup(e);}
        };
        for (JComponent c : new JComponent[]{row, center, titleLbl, activeBox})
        {
            c.addMouseListener(popupHandler);
        }

        return row;
    }

    // Apply tooltip to all provided components (ensures visibility regardless of hover target)
    private void applyTooltipAll(String tooltip, JComponent... comps)
    {
        for (JComponent c : comps) c.setToolTipText(tooltip);
    }

    private void applyCommandsTooltip(KPWebhookPreset preset, JComponent... comps)
    {
        String commands = preset.getCommands();
        String tooltip;
        if (commands != null && !commands.isBlank())
        {
            String trimmed = commands.trim();
            String[] lines = trimmed.split("\\r?\\n");
            StringBuilder sb = new StringBuilder();
            int maxLines = 8;
            for (int i=0;i<lines.length && i<maxLines;i++)
            {
                String ln = lines[i];
                if (ln.length() > 140) ln = ln.substring(0,137) + "...";
                sb.append(escapeHtml(ln)).append("\n");
            }
            if (lines.length > maxLines) sb.append("..." + (lines.length - maxLines) + " more line(s)\n");
            String body = sb.toString();
            tooltip = "<html><b>Commands:</b><br><pre style='margin:2px 0 2px 0;font-family:monospace;font-size:11px;color:#E0E0E0;'>" + escapeHtml(body) + "</pre><i>Double-click title to edit</i></html>";
        }
        else
        {
            tooltip = "<html><b>No commands</b><br><i>Double-click title to edit</i></html>";
        }
        applyTooltipAll(tooltip, comps);
    }

    // Simple HTML escape helper for tooltip
    private String escapeHtml(String s)
    {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
