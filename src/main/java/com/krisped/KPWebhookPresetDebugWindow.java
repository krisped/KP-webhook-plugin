package com.krisped;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Simple window to inspect preset executions (now only time, trigger & commands). */
public class KPWebhookPresetDebugWindow extends JFrame {
    private final DefaultTableModel model;
    private final JTable table;
    private final JCheckBox autoScroll;
    private final JLabel countLabel;
    private final List<ExecutionEntry> entries = new ArrayList<>();
    private static final int MAX_ROWS = 600;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public KPWebhookPresetDebugWindow() {
        super("Preset Debug");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(750, 380);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(6,6));

        // Columns: Time, Trigger, Commands (concatenated)
        model = new DefaultTableModel(new String[]{"Time","Trigger","Commands"},0){ @Override public boolean isCellEditable(int r,int c){return false;} };
        table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowHeight(20);
        JScrollPane sp = new JScrollPane(table);
        add(sp, BorderLayout.CENTER);

        autoScroll = new JCheckBox("Auto-scroll", true);
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> clear());
        JButton detailsBtn = new JButton("Details");
        detailsBtn.addActionListener(e -> showSelectedDetails());
        countLabel = new JLabel("0");

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
        top.add(autoScroll); top.add(clearBtn); top.add(detailsBtn); top.add(countLabel);
        add(top, BorderLayout.NORTH);

        table.addMouseListener(new MouseAdapter(){ @Override public void mouseClicked(MouseEvent e){ if (e.getClickCount()==2 && table.getSelectedRow()>=0) showSelectedDetails(); }});
    }

    /** Log an execution (callable even when window not visible). */
    public void logExecution(KPWebhookPreset rule, List<String> commands, Map<String,String> ctx) { // ctx kept for future needs
        if (rule == null) return;
        String ts = LocalTime.now().format(TIME_FMT);
        ExecutionEntry entry = new ExecutionEntry();
        entry.time = ts;
        entry.trigger = rule.getTriggerType()!=null? rule.getTriggerType().name():"?";
        entry.commands = commands!=null? new ArrayList<>(commands): List.of();
        SwingUtilities.invokeLater(() -> {
            entries.add(entry);
            while (entries.size() > MAX_ROWS) { entries.remove(0); if (model.getRowCount()>0) model.removeRow(0); }
            model.addRow(new Object[]{entry.time, entry.trigger, joinCommandsShort(entry.commands)});
            updateCount();
            if (autoScroll.isSelected()) {
                int last = model.getRowCount()-1; if (last>=0) table.scrollRectToVisible(table.getCellRect(last,0,true));
            }
        });
    }

    private String joinCommandsShort(List<String> cmds){
        if(cmds==null||cmds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<cmds.size();i++){
            if(i>0) sb.append("; ");
            String c = cmds.get(i);
            if(c.length()>60) c = c.substring(0,57)+"...";
            sb.append(c);
        }
        return sb.toString();
    }

    private void showSelectedDetails() {
        int viewRow = table.getSelectedRow(); if (viewRow<0) return; int row = table.convertRowIndexToModel(viewRow);
        if (row < 0 || row >= entries.size()) return; ExecutionEntry e = entries.get(row);
        JTextArea area = new JTextArea(); area.setEditable(false); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        StringBuilder sb = new StringBuilder();
        sb.append("Time: ").append(e.time).append('\n');
        sb.append("Trigger: ").append(e.trigger).append('\n');
        sb.append("Commands ("+e.commands.size()+"):\n");
        for (String c : e.commands) sb.append("  ").append(c).append('\n');
        area.setText(sb.toString()); area.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(640, 320));
        JOptionPane.showMessageDialog(this, sp, "Preset Execution", JOptionPane.INFORMATION_MESSAGE);
    }

    private void clear() {
        entries.clear();
        model.setRowCount(0);
        updateCount();
    }
    private void updateCount(){ countLabel.setText(model.getRowCount()+" rows"); }

    private static class ExecutionEntry { String time; String trigger; List<String> commands; }
}
