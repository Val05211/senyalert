package com.senyalert;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.json.JSONObject;

public class DashboardFrame extends JFrame {
    private final DefaultTableModel tableModel;
    private final JTable auditTable;
    private final JLabel statusBanner;
    private final JLabel cameraCardLabel;
    private final JPanel cameraTile;
    private int lastIncidentId = -1;
    private SenyAlertServer serverRef;
    private boolean isEnginePaused = false;

    public void setServerReference(SenyAlertServer server) {
        this.serverRef = server;
    }

    public DashboardFrame() {
        setTitle("SenyAlert - Silent Distress Dispatch & Triage System");
        setExtendedState(JFrame.MAXIMIZED_BOTH); 
        setUndecorated(true);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JTabbedPane mainTabs = new JTabbedPane();
        mainTabs.setFont(new Font("Segoe UI", Font.BOLD, 14));

        String[] columns = {"ID", "Camera", "Triage Tier", "Confidence", "Status", "Time", "Video Path"};
        tableModel = new DefaultTableModel(columns, 0);
        auditTable = new JTable(tableModel);
        
        statusBanner = new JLabel("SYSTEM MONITORING ACTIVE | NORMAL");
        cameraTile = new JPanel(new BorderLayout());
        cameraCardLabel = new JLabel("<html><center>CAMERA FEED TILE<br>Awaiting Incident...</center></html>", SwingConstants.CENTER);
        
        mainTabs.addTab("Live Dispatch", buildDashboardPanel());
        mainTabs.addTab("Engine Settings", buildSettingsPanel());
        mainTabs.addTab("Database & Video Viewer", buildDatabasePanel());
        
        add(mainTabs, BorderLayout.CENTER);
        DatabaseManager.loadHistoricalIncidents(tableModel); 
    }

    private JPanel buildDashboardPanel() {
        JPanel dispatchPanel = new JPanel(new BorderLayout());
        
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(25, 35, 45));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(12, 15, 12, 15));

        JLabel titleLabel = new JLabel("SENYALERT DISPATCH CONSOLE");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(Color.WHITE);
        headerPanel.add(titleLabel, BorderLayout.WEST);

        statusBanner.setFont(new Font("Segoe UI", Font.BOLD, 13));
        statusBanner.setForeground(new Color(46, 204, 113));
        headerPanel.add(statusBanner, BorderLayout.EAST);
        dispatchPanel.add(headerPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 12, 12));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

        cameraTile.setBackground(new Color(40, 44, 52));
        cameraTile.setBorder(BorderFactory.createLineBorder(new Color(70, 75, 85), 2));
        cameraCardLabel.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        cameraCardLabel.setForeground(Color.LIGHT_GRAY);
        cameraTile.add(cameraCardLabel, BorderLayout.CENTER);
        centerPanel.add(cameraTile);

        auditTable.setRowHeight(24);
        auditTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        JScrollPane scrollPane = new JScrollPane(auditTable);
        centerPanel.add(scrollPane);
        dispatchPanel.add(centerPanel, BorderLayout.CENTER);

        // Control Footer with Engine Controls
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        
        JButton reloadBtn = new JButton("Reload Engine Config");
        reloadBtn.addActionListener(e -> triggerEngineReload());
        
        JButton pauseBtn = new JButton("Pause Engine");
        pauseBtn.addActionListener(e -> {
            if (serverRef != null) {
                isEnginePaused = !isEnginePaused;
                JSONObject payload = new JSONObject();
                payload.put("action", "PAUSE");
                payload.put("state", isEnginePaused);
                serverRef.broadcast(payload.toString());
                pauseBtn.setText(isEnginePaused ? "Resume Engine" : "Pause Engine");
            }
        });

        JButton resolveBtn = new JButton("Acknowledge & Resolve");
        resolveBtn.setBackground(new Color(52, 152, 219));
        resolveBtn.setForeground(Color.WHITE);
        resolveBtn.addActionListener(e -> resolveActiveIncident());

        JButton exitBtn = new JButton("Exit System");
        exitBtn.addActionListener(e -> System.exit(0));

        footerPanel.add(reloadBtn);
        footerPanel.add(pauseBtn);
        footerPanel.add(resolveBtn);
        footerPanel.add(exitBtn);
        dispatchPanel.add(footerPanel, BorderLayout.SOUTH);

        return dispatchPanel;
    }

    private JPanel buildDatabasePanel() {
        JPanel dbPanel = new JPanel(new BorderLayout(10, 10));
        dbPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JTable crudTable = new JTable(tableModel);
        dbPanel.add(new JScrollPane(crudTable), BorderLayout.CENTER);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton playBtn = new JButton("Play Native Video");
        playBtn.addActionListener(e -> {
            int row = crudTable.getSelectedRow();
            if (row != -1) {
                String path = (String) tableModel.getValueAt(row, 6);
                try {
                    File videoFile = new File(path);
                    if (videoFile.exists()) {
                        Desktop.getDesktop().open(videoFile);
                    } else {
                        JOptionPane.showMessageDialog(this, "Video file not found at: " + path, "Playback Error", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ex) { ex.printStackTrace(); }
            }
        });

        JButton exportBtn = new JButton("Export Selected MP4");
        exportBtn.addActionListener(e -> {
             int row = crudTable.getSelectedRow();
             if(row != -1) {
                 String sourcePath = (String) tableModel.getValueAt(row, 6);
                 JFileChooser fileChooser = new JFileChooser();
                 fileChooser.setSelectedFile(new File("Exported_Incident.mp4"));
                 if(fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                     try {
                         Files.copy(Paths.get(sourcePath), fileChooser.getSelectedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
                         JOptionPane.showMessageDialog(this, "Exported successfully!");
                     } catch (Exception ex) { 
                         JOptionPane.showMessageDialog(this, "Export Failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                     }
                 }
             }
        });
        
        JButton deleteBtn = new JButton("Delete Record");
        deleteBtn.addActionListener(e -> {
            int row = crudTable.getSelectedRow();
            if (row != -1) {
                int id = (int) tableModel.getValueAt(row, 0);
                DatabaseManager.deleteIncidentAsync(id);
                tableModel.removeRow(row);
            }
        });

        controlPanel.add(playBtn);
        controlPanel.add(exportBtn);
        controlPanel.add(deleteBtn);
        dbPanel.add(controlPanel, BorderLayout.SOUTH);

        return dbPanel;
    }

    private JPanel buildSettingsPanel() {
        JPanel wrapper = new JPanel(new BorderLayout());
        JPanel settings = new JPanel(new GridLayout(8, 2, 10, 10));
        settings.setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

        JSlider confidenceSlider = new JSlider(0, 100, 70);
        confidenceSlider.setMajorTickSpacing(10);
        confidenceSlider.setPaintTicks(true);
        confidenceSlider.setPaintLabels(true);

        JSlider preEventSlider = new JSlider(1, 15, 5); 
        JSlider postEventSlider = new JSlider(1, 15, 5);

        JCheckBox reqThumb = new JCheckBox("Require Tucked Thumb", true);
        JCheckBox reqIndex = new JCheckBox("Require Folded Index", true);

        JButton saveBtn = new JButton("Save Configuration");
        saveBtn.addActionListener(e -> {
            JSONObject config = new JSONObject();
            config.put("confidence_threshold", confidenceSlider.getValue() / 100.0);
            config.put("pre_event_sec", preEventSlider.getValue());
            config.put("post_event_sec", postEventSlider.getValue());
            
            JSONObject fingers = new JSONObject();
            fingers.put("thumb", reqThumb.isSelected());
            fingers.put("index", reqIndex.isSelected());
            fingers.put("middle", true);
            fingers.put("ring", true);
            fingers.put("pinky", true);
            config.put("fingers", fingers);

            try (java.io.FileWriter file = new java.io.FileWriter("senyalert-config.json")) {
                file.write(config.toString(4));
                triggerEngineReload();
            } catch (Exception ex) { ex.printStackTrace(); }
        });

        settings.add(new JLabel("Target Confidence (%):")); settings.add(confidenceSlider);
        settings.add(new JLabel("Finger Prerequisites:")); settings.add(reqThumb);
        settings.add(new JLabel("")); settings.add(reqIndex);
        settings.add(new JLabel("Pre-Event Record Buffer (Sec):")); settings.add(preEventSlider);
        settings.add(new JLabel("Post-Event Record Buffer (Sec):")); settings.add(postEventSlider);
        settings.add(new JLabel("")); settings.add(saveBtn);

        wrapper.add(settings, BorderLayout.NORTH);
        return wrapper;
    }
    
    private void triggerEngineReload() {
        if (serverRef != null) {
            try {
                String content = new String(Files.readAllBytes(Paths.get("senyalert-config.json")));
                JSONObject payload = new JSONObject();
                payload.put("action", "UPDATE_SETTINGS");
                payload.put("config", new JSONObject(content));
                serverRef.broadcast(payload.toString());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Save a configuration first.", "Notice", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    public void handleDistressEvent(DistressEvent event, String videoPath) {
        this.lastIncidentId = event.getIncidentId();
        String timeStr = new SimpleDateFormat("HH:mm:ss").format(new Date(event.getTimestamp() * 1000));
        boolean isSilent = "HIGH_TRAFFIC".equalsIgnoreCase(event.getTriageContext());
        String triageLevel = isSilent ? "SILENT WATCH" : "AUDIBLE ALARM";

        if (isSilent) {
            statusBanner.setText("TRIAGE ALERT: DISCREET SILENT WATCH (" + event.getLocation() + ")");
            statusBanner.setForeground(new Color(241, 196, 15));
            cameraTile.setBorder(BorderFactory.createLineBorder(new Color(241, 196, 15), 4));
        } else {
            statusBanner.setText("CRITICAL ALERT: PERIMETER ALARM (" + event.getLocation() + ")");
            statusBanner.setForeground(new Color(231, 76, 60));
            cameraTile.setBorder(BorderFactory.createLineBorder(new Color(231, 76, 60), 4));
            Toolkit.getDefaultToolkit().beep();
        }

        cameraCardLabel.setText(String.format(
            "<html><center><b style='color:#E74C3C;'>FEED LOCKED</b><br><br>" +
            "Incident ID: #%d<br>Camera: %s<br>Confidence: %.1f%%<br>Video: %s</center></html>",
            event.getIncidentId(), event.getCameraId(), event.getConfidence() * 100, videoPath
        ));

        tableModel.insertRow(0, new Object[]{
            event.getIncidentId(), event.getCameraId(), triageLevel,
            String.format("%.2f", event.getConfidence()), "PENDING", timeStr, videoPath
        });
    }

    private void resolveActiveIncident() {
        if (lastIncidentId != -1) {
            DatabaseManager.updateStatusAsync(lastIncidentId, "RESOLVED", "Cleared.");
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if ((int) tableModel.getValueAt(i, 0) == lastIncidentId) {
                    tableModel.setValueAt("RESOLVED", i, 4);
                    break;
                }
            }
            statusBanner.setText("SYSTEM MONITORING ACTIVE | NORMAL");
            statusBanner.setForeground(new Color(46, 204, 113));
            cameraTile.setBorder(BorderFactory.createLineBorder(new Color(70, 75, 85), 2));
            cameraCardLabel.setText("<html><center>CAMERA FEED TILE<br>Incident Resolved.</center></html>");
            lastIncidentId = -1;
        }
    }
}