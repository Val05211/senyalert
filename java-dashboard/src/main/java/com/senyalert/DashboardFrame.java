package com.senyalert;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DashboardFrame extends JFrame {
    private final DefaultTableModel tableModel;
    private final JTable auditTable;
    private final JLabel statusBanner;
    private final JLabel cameraCardLabel;
    private final JPanel cameraTile;
    private int lastIncidentId = -1;

    public DashboardFrame() {
        setTitle("SenyAlert - Silent Distress Dispatch & Triage System");
        setSize(950, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // Top Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(25, 35, 45));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(12, 15, 12, 15));

        JLabel titleLabel = new JLabel("SENYALERT DISPATCH CONSOLE");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(Color.WHITE);
        headerPanel.add(titleLabel, BorderLayout.WEST);

        statusBanner = new JLabel("SYSTEM MONITORING ACTIVE | NORMAL");
        statusBanner.setFont(new Font("Segoe UI", Font.BOLD, 13));
        statusBanner.setForeground(new Color(46, 204, 113));
        headerPanel.add(statusBanner, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        // Center Content: Left = Feed Tile, Right = Audit Table
        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 12, 12));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

        cameraTile = new JPanel(new BorderLayout());
        cameraTile.setBackground(new Color(40, 44, 52));
        cameraTile.setBorder(BorderFactory.createLineBorder(new Color(70, 75, 85), 2));

        cameraCardLabel = new JLabel("<html><center>CAMERA FEED TILE<br>Awaiting Incident Trigger or Selection...</center></html>", SwingConstants.CENTER);
        cameraCardLabel.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        cameraCardLabel.setForeground(Color.LIGHT_GRAY);
        cameraTile.add(cameraCardLabel, BorderLayout.CENTER);
        centerPanel.add(cameraTile);

        // Right Panel: Audit Log Table
        String[] columns = {"ID", "Camera", "Triage Tier", "Confidence", "Status", "Time"};
        tableModel = new DefaultTableModel(columns, 0);
        auditTable = new JTable(tableModel);
        auditTable.setRowHeight(24);
        auditTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        
        // Incident Selection Listener for DVR Rollback feature
        auditTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && auditTable.getSelectedRow() != -1) {
                int row = auditTable.getSelectedRow();
                String id = auditTable.getValueAt(row, 0).toString();
                String cam = auditTable.getValueAt(row, 1).toString();
                String time = auditTable.getValueAt(row, 5).toString();
                
                cameraCardLabel.setText(String.format(
                    "<html><center><b style='color:#3498DB;'>INCIDENT SELECTED FOR REVIEW</b><br><br>" +
                    "Incident ID: #%s<br>" +
                    "Camera: %s<br>" +
                    "<b style='color:#F1C40F;'>Rollback Timestamp: %s</b><br><br>" +
                    "<i>Use this timestamp to retrieve DVR footage.</i></center></html>",
                    id, cam, time
                ));
            }
        });
        
        JScrollPane scrollPane = new JScrollPane(auditTable);
        centerPanel.add(scrollPane);
        add(centerPanel, BorderLayout.CENTER);

        // Bottom Controls
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        
        JButton testTriggerBtn = new JButton("Simulate Fallback Distress");
        testTriggerBtn.addActionListener(e -> {
            DistressEvent testEvent = new DistressEvent(this, "CAM-01-LAPTOP", 0.99, System.currentTimeMillis() / 1000, "Intake Desk", "HIGH_TRAFFIC");
            DatabaseManager.insertIncidentAsync(testEvent.getCameraId(), testEvent.getConfidence(), testEvent.getTriageContext(), (id) -> {
                testEvent.setIncidentId(id);
                SwingUtilities.invokeLater(() -> handleDistressEvent(testEvent));
            });
        });

        JButton resolveBtn = new JButton("Acknowledge & Resolve Incident");
        resolveBtn.setBackground(new Color(52, 152, 219));
        resolveBtn.setForeground(Color.WHITE);
        resolveBtn.addActionListener(e -> resolveActiveIncident());

        footerPanel.add(testTriggerBtn);
        footerPanel.add(resolveBtn);
        add(footerPanel, BorderLayout.SOUTH);

        // Load saved incidents from SQLite on startup
        DatabaseManager.loadHistoricalIncidents(tableModel);
    }

    public void handleDistressEvent(DistressEvent event) {
        this.lastIncidentId = event.getIncidentId();
        String timeStr = new SimpleDateFormat("HH:mm:ss").format(new Date(event.getTimestamp() * 1000));

        // Cascading Triage Engine: Context-based routing[cite: 2, 4, 11]
        boolean isSilent = "HIGH_TRAFFIC".equalsIgnoreCase(event.getTriageContext());
        String triageLevel = isSilent ? "SILENT WATCH" : "AUDIBLE ALARM";

        if (isSilent) {
            statusBanner.setText("TRIAGE ALERT: DISCREET SILENT WATCH (" + event.getLocation() + ")");
            statusBanner.setForeground(new Color(241, 196, 15));
            cameraTile.setBorder(BorderFactory.createLineBorder(new Color(241, 196, 15), 4));
        } else {
            statusBanner.setText("CRITICAL ALERT: PERIMETER ALARM ACTIVE (" + event.getLocation() + ")");
            statusBanner.setForeground(new Color(231, 76, 60));
            cameraTile.setBorder(BorderFactory.createLineBorder(new Color(231, 76, 60), 4));
            Toolkit.getDefaultToolkit().beep(); // Acoustic fallback chime
        }

        cameraCardLabel.setText(String.format(
            "<html><center><b style='color:#E74C3C;'>FEED LOCKED - DISTRESS RECORDED</b><br><br>" +
            "Incident ID: #%d<br>" +
            "Camera: %s<br>" +
            "Location: %s<br>" +
            "Confidence: %.1f%%<br>" +
            "Captured At: %s</center></html>",
            event.getIncidentId(), event.getCameraId(), event.getLocation(), event.getConfidence() * 100, timeStr
        ));

        // Reactive Table Append[cite: 2]
        tableModel.insertRow(0, new Object[]{
            event.getIncidentId(),
            event.getCameraId(),
            triageLevel,
            String.format("%.2f", event.getConfidence()),
            "PENDING",
            timeStr
        });
    }

    private void resolveActiveIncident() {
        if (lastIncidentId != -1) {
            DatabaseManager.updateStatusAsync(lastIncidentId, "RESOLVED", "Cleared by desk operator.");
            
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if ((int) tableModel.getValueAt(i, 0) == lastIncidentId) {
                    tableModel.setValueAt("RESOLVED", i, 4);
                    break;
                }
            }

            statusBanner.setText("SYSTEM MONITORING ACTIVE | NORMAL");
            statusBanner.setForeground(new Color(46, 204, 113));
            cameraTile.setBorder(BorderFactory.createLineBorder(new Color(70, 75, 85), 2));
            cameraCardLabel.setText("<html><center>CAMERA FEED TILE<br>Incident #" + lastIncidentId + " Resolved.<br>Monitoring Live Feeds...</center></html>");
            lastIncidentId = -1;
        }
    }
}