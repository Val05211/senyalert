package com.senyalert;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.json.JSONObject;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;

public class DashboardFrame extends JFrame {
    private final DefaultTableModel tableModel;
    private final JTable auditTable;
    private final JLabel statusBanner;
    private final JLabel cameraCardLabel;
    private final JPanel cameraTile;
    private int lastIncidentId = -1;
    private SenyAlertServer serverRef;

    // Media Player Components
    private JFXPanel jfxPanel;
    private MediaPlayer mediaPlayer;

    public void setServerReference(SenyAlertServer server) {
        this.serverRef = server;
    }

    public DashboardFrame() {
        setTitle("SenyAlert - Silent Distress Dispatch & Triage System");
        setExtendedState(JFrame.MAXIMIZED_BOTH); // Fullscreen requirement
        setUndecorated(true);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JTabbedPane mainTabs = new JTabbedPane();
        mainTabs.setFont(new Font("Segoe UI", Font.BOLD, 14));

        // Initialization for Live Dispatch Panel
        String[] columns = {"ID", "Camera", "Triage Tier", "Confidence", "Status", "Time", "Video Path"};
        tableModel = new DefaultTableModel(columns, 0);
        auditTable = new JTable(tableModel);
        
        statusBanner = new JLabel("SYSTEM MONITORING ACTIVE | NORMAL");
        cameraTile = new JPanel(new BorderLayout());
        cameraCardLabel = new JLabel("<html><center>CAMERA FEED TILE<br>Awaiting Incident...</center></html>", SwingConstants.CENTER);
        
        mainTabs.addTab("Live Dispatch", buildDashboardPanel());
        mainTabs.addTab("Database & Video Viewer", buildDatabasePanel());
        mainTabs.addTab("Engine Settings", buildSettingsPanel());
        
        add(mainTabs, BorderLayout.CENTER);
        DatabaseManager.loadHistoricalIncidents(tableModel); // Requires updated DatabaseManager
    }

    // Fixed compilation error: Added 'JPanel' return type
    private JPanel buildDashboardPanel() {
        JPanel dispatchPanel = new JPanel(new BorderLayout());
        
        // Header
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

        // Center Content
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

        // Footer
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton resolveBtn = new JButton("Acknowledge & Resolve Incident");
        resolveBtn.setBackground(new Color(52, 152, 219));
        resolveBtn.setForeground(Color.WHITE);
        resolveBtn.addActionListener(e -> resolveActiveIncident());

        JButton exitBtn = new JButton("Exit Fullscreen");
        exitBtn.addActionListener(e -> System.exit(0));

        footerPanel.add(resolveBtn);
        footerPanel.add(exitBtn);
        dispatchPanel.add(footerPanel, BorderLayout.SOUTH);

        return dispatchPanel;
    }

    private JPanel buildDatabasePanel() {
        JPanel dbPanel = new JPanel(new BorderLayout(10, 10));
        dbPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // CRUD Table (Shares model with Live Dispatch for synchronization)
        JTable crudTable = new JTable(tableModel);
        dbPanel.add(new JScrollPane(crudTable), BorderLayout.WEST);

        // JavaFX Embedded Media Player
        jfxPanel = new JFXPanel();
        jfxPanel.setPreferredSize(new Dimension(640, 360));
        jfxPanel.setBorder(BorderFactory.createTitledBorder("Incident Video Evidence"));
        dbPanel.add(jfxPanel, BorderLayout.CENTER);

        // Event Selection Listener to load MP4
        crudTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && crudTable.getSelectedRow() != -1) {
                int row = crudTable.getSelectedRow();
                String videoPath = (String) tableModel.getValueAt(row, 6);
                if (videoPath != null && !videoPath.isEmpty()) {
                    loadVideo(videoPath);
                }
            }
        });

        // CRUD Controls
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton deleteBtn = new JButton("Delete Selected Incident");
        deleteBtn.addActionListener(e -> {
            int row = crudTable.getSelectedRow();
            if (row != -1) {
                int id = (int) tableModel.getValueAt(row, 0);
                DatabaseManager.deleteIncidentAsync(id);
                tableModel.removeRow(row);
            }
        });
        
        JButton exportBtn = new JButton("Export Selected MP4");
        exportBtn.addActionListener(e -> {
             int row = crudTable.getSelectedRow();
             if(row != -1) {
                 String sourcePath = (String) tableModel.getValueAt(row, 6);
                 JFileChooser fileChooser = new JFileChooser();
                 if(fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                     try {
                         Files.copy(Paths.get(sourcePath), fileChooser.getSelectedFile().toPath());
                         JOptionPane.showMessageDialog(this, "Exported successfully!");
                     } catch (Exception ex) { ex.printStackTrace(); }
                 }
             }
        });

        controlPanel.add(deleteBtn);
        controlPanel.add(exportBtn);
        dbPanel.add(controlPanel, BorderLayout.SOUTH);

        return dbPanel;
    }

    private void loadVideo(String filePath) {
        File videoFile = new File(filePath);
        if (!videoFile.exists()) return;

        Platform.runLater(() -> {
            if (mediaPlayer != null) mediaPlayer.dispose();
            Media media = new Media(videoFile.toURI().toString());
            mediaPlayer = new MediaPlayer(media);
            MediaView mediaView = new MediaView(mediaPlayer);
            
            BorderPane pane = new BorderPane();
            pane.setCenter(mediaView);
            mediaView.fitWidthProperty().bind(pane.widthProperty());
            
            jfxPanel.setScene(new Scene(pane));
            mediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);
            mediaPlayer.play();
        });
    }

    private JPanel buildSettingsPanel() {
        JPanel wrapper = new JPanel(new BorderLayout());
        JPanel settings = new JPanel(new GridLayout(8, 2, 10, 10));
        settings.setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

        JSlider confidenceSlider = new JSlider(0, 100, 70);
        confidenceSlider.setMajorTickSpacing(10);
        confidenceSlider.setPaintTicks(true);
        confidenceSlider.setPaintLabels(true);

        // Simulating the 2-handle timeline with two distinct sliders around a visual center
        JSlider preEventSlider = new JSlider(1, 15, 5); 
        JSlider postEventSlider = new JSlider(1, 15, 5);

        JCheckBox reqThumb = new JCheckBox("Require Tucked Thumb", true);
        JCheckBox reqIndex = new JCheckBox("Require Folded Index", true);

        JButton saveBtn = new JButton("Save Config & Reload Engine");
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
                JOptionPane.showMessageDialog(this, "Configuration Saved and Engine Reloaded.");
            } catch (Exception ex) { ex.printStackTrace(); }

            if (serverRef != null) {
                JSONObject payload = new JSONObject();
                payload.put("action", "UPDATE_SETTINGS");
                payload.put("config", config);
                serverRef.broadcast(payload.toString());
            }
        });
        
        JButton enginePauseBtn = new JButton("Pause Engine Detection");
        enginePauseBtn.addActionListener(e -> {
            if (serverRef != null) {
                JSONObject payload = new JSONObject();
                payload.put("action", "PAUSE");
                payload.put("state", true);
                serverRef.broadcast(payload.toString());
            }
        });

        settings.add(new JLabel("Target Confidence (%):")); settings.add(confidenceSlider);
        settings.add(new JLabel("Finger Prerequisites:")); settings.add(reqThumb);
        settings.add(new JLabel("")); settings.add(reqIndex);
        settings.add(new JLabel("Pre-Event Record Buffer (Sec):")); settings.add(preEventSlider);
        settings.add(new JLabel("Post-Event Record Buffer (Sec):")); settings.add(postEventSlider);
        settings.add(new JLabel("Engine Controls:")); settings.add(enginePauseBtn);
        settings.add(new JLabel("")); settings.add(saveBtn);

        wrapper.add(settings, BorderLayout.NORTH);
        return wrapper;
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