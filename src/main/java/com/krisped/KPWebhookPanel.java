package com.krisped;

import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public class KPWebhookPanel extends PluginPanel
{
    private final KPWebhookPlugin plugin;

    private final RuleTableModel model = new RuleTableModel();
    private final JTable table = new JTable(model);

    private final JButton createBtn = new JButton("Create");
    private final JButton editBtn = new JButton("Edit");
    private final JButton sendBtn = new JButton("Send");
    private final JButton deleteBtn = new JButton("Delete");

    public KPWebhookPanel(KPWebhookPlugin plugin)
    {
        this.plugin = plugin;
        setLayout(new BorderLayout(10,10));
        setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        buildUI();
        wire();
        refreshTable();
    }

    private void buildUI()
    {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(28);
        table.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        table.getTableHeader().setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));

        RowRenderer renderer = new RowRenderer();
        table.setDefaultRenderer(String.class, renderer);
        table.setDefaultRenderer(Boolean.class, renderer);

        table.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0)
                {
                    if (e.getClickCount()==2 && col == 0 && e.getButton()==MouseEvent.BUTTON1)
                    {
                        editSelected();
                    }
                    else if (col == 1 && e.getButton()==MouseEvent.BUTTON1)
                    {
                        KPWebhookPreset r = model.getAt(row);
                        if (r != null)
                        {
                            plugin.toggleActive(r.getId());
                            refreshTable();
                        }
                    }
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        add(scroll, BorderLayout.CENTER);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8,4));
        top.add(createBtn);
        top.add(editBtn);
        top.add(sendBtn);
        top.add(deleteBtn);
        add(top, BorderLayout.NORTH);

        JPopupMenu popup = new JPopupMenu();
        JMenuItem miEdit = new JMenuItem("Edit");
        JMenuItem miSend = new JMenuItem("Send");
        JMenuItem miToggle = new JMenuItem("Enable/Disable");
        JMenuItem miDelete = new JMenuItem("Delete");
        popup.add(miEdit);
        popup.add(miSend);
        popup.add(miToggle);
        popup.addSeparator();
        popup.add(miDelete);

        miEdit.addActionListener(e -> editSelected());
        miSend.addActionListener(e -> sendSelected());
        miToggle.addActionListener(e -> toggleSelected());
        miDelete.addActionListener(e -> deleteSelected());

        table.addMouseListener(new MouseAdapter()
        {
            @Override public void mousePressed(MouseEvent e){ if (e.isPopupTrigger()) showPopup(e); }
            @Override public void mouseReleased(MouseEvent e){ if (e.isPopupTrigger()) showPopup(e); }
            private void showPopup(MouseEvent e)
            {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0)
                    table.setRowSelectionInterval(row, row);
                popup.show(table, e.getX(), e.getY());
            }
        });
    }

    private void wire()
    {
        createBtn.addActionListener(e -> openDialog(null));
        editBtn.addActionListener(e -> editSelected());
        sendBtn.addActionListener(e -> sendSelected());
        deleteBtn.addActionListener(e -> deleteSelected());
    }

    private KPWebhookPreset selected()
    {
        int row = table.getSelectedRow();
        if (row < 0) return null;
        return model.getAt(row);
    }

    private void openDialog(KPWebhookPreset existing)
    {
        KPWebhookPresetDialog d = new KPWebhookPresetDialog(
                SwingUtilities.getWindowAncestor(this),
                plugin,
                existing);
        d.setVisible(true);
        KPWebhookPreset res = d.getResult();
        if (res != null)
        {
            plugin.addOrUpdate(res);
            refreshTable();
        }
    }

    private void editSelected()
    {
        KPWebhookPreset r = selected();
        if (r != null) openDialog(r);
    }

    private void sendSelected()
    {
        KPWebhookPreset r = selected();
        if (r != null) plugin.manualSend(r.getId());
    }

    private void deleteSelected()
    {
        KPWebhookPreset r = selected();
        if (r != null &&
                JOptionPane.showConfirmDialog(this,
                        "Delete '" + r.getTitle() + "'?",
                        "Confirm",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
        {
            plugin.deleteRule(r.getId());
            refreshTable();
        }
    }

    private void toggleSelected()
    {
        KPWebhookPreset r = selected();
        if (r != null)
        {
            plugin.toggleActive(r.getId());
            refreshTable();
        }
    }

    public void refreshTable()
    {
        model.setData(plugin.getRules());
    }

    /* ===== Model ===== */
    private static class RuleTableModel extends AbstractTableModel
    {
        private List<KPWebhookPreset> data = java.util.Collections.emptyList();
        private final String[] cols = {"Name","Active"};

        void setData(List<KPWebhookPreset> d){ data=d; fireTableDataChanged(); }
        KPWebhookPreset getAt(int i){ return data.get(i); }
        @Override public int getRowCount(){ return data.size(); }
        @Override public int getColumnCount(){ return cols.length; }
        @Override public String getColumnName(int c){ return cols[c]; }
        @Override public Class<?> getColumnClass(int c){ return c==1?Boolean.class:String.class; }
        @Override public boolean isCellEditable(int r,int c){ return c==1; }
        @Override public Object getValueAt(int r,int c)
        {
            KPWebhookPreset p=data.get(r);
            if (c==0) return p.getTitle();
            if (c==1) return p.isActive();
            return null;
        }
        @Override public void setValueAt(Object v,int r,int c)
        {
            if (c==1 && r>=0 && r<data.size())
                data.get(r).setActive(Boolean.TRUE.equals(v));
        }
    }

    /* ===== Renderer ===== */
    private class RowRenderer extends DefaultTableCellRenderer
    {
        private final Color even = new Color(245,245,245);
        private final Color odd  = Color.WHITE;
        private final Color inactive = new Color(130,130,130);

        @Override
        public Component getTableCellRendererComponent(JTable tbl, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int col)
        {
            Component c;
            if (col == 1)
            {
                JCheckBox box = new JCheckBox();
                box.setHorizontalAlignment(SwingConstants.CENTER);
                if (value instanceof Boolean) box.setSelected((Boolean) value);
                box.setEnabled(false);
                c = box;
            }
            else
            {
                c = super.getTableCellRendererComponent(tbl,value,isSelected,hasFocus,row,col);
            }

            KPWebhookPreset preset = model.getAt(row);
            boolean isActive = preset.isActive();

            if (!isSelected)
                c.setBackground(row % 2 == 0 ? even : odd);
            else
                c.setBackground(new Color(51,153,255));

            if (c instanceof JLabel)
            {
                JLabel l=(JLabel)c;
                l.setFont(l.getFont().deriveFont(Font.PLAIN, 13f));
                l.setForeground(!isActive
                        ? inactive
                        : (isSelected ? Color.WHITE : Color.BLACK));
            }
            else if (c instanceof JCheckBox)
            {
                c.setBackground(row % 2 == 0 ? even : odd);
            }
            return c;
        }
    }
}