package com.senyalert;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import javax.swing.SwingUtilities;
import javax.swing.JOptionPane;
import java.net.InetSocketAddress;

public class SenyAlertServer extends WebSocketServer {
    private final DashboardFrame dashboard;
    private WebSocket activeEngineConnection;

    public SenyAlertServer(int port, DashboardFrame dashboard) {
        super(new InetSocketAddress(port));
        this.dashboard = dashboard;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        this.activeEngineConnection = conn;
        System.out.println("[SOCKET] Ingestion engine connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("[SOCKET] Ingestion engine disconnected");
        if (this.activeEngineConnection == conn) {
            this.activeEngineConnection = null;
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JSONObject json = new JSONObject(message);
            
            // Handle Responsive Confirmation from Python Engine
            if (json.has("event") && json.getString("event").equals("CONFIG_ACK")) {
                SwingUtilities.invokeLater(() -> 
                    JOptionPane.showMessageDialog(dashboard, json.getString("status"), "Engine Confirmed", JOptionPane.INFORMATION_MESSAGE)
                );
                return;
            }

            String cameraId = json.optString("cameraId", "UNKNOWN_CAM");
            double confidence = json.optDouble("confidence", 0.0);
            long timestamp = json.optLong("timestamp", System.currentTimeMillis() / 1000);
            String location = json.optString("location", "Unassigned Zone");
            String triageContext = json.optString("triage_context", "STANDARD");
            String videoPath = json.optString("video_path", "");

            DistressEvent event = new DistressEvent(this, cameraId, confidence, timestamp, location, triageContext);

            DatabaseManager.insertIncidentAsync(cameraId, confidence, triageContext, (generatedId) -> {
                event.setIncidentId(generatedId);
                // Pass both the event and the MP4 video path to the Swing EDT
                SwingUtilities.invokeLater(() -> dashboard.handleDistressEvent(event, videoPath));
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void pushConfigurationToEngine(JSONObject configPayload) {
        if (activeEngineConnection != null && activeEngineConnection.isOpen()) {
            JSONObject command = new JSONObject();
            command.put("action", "UPDATE_SETTINGS");
            command.put("config", configPayload);
            activeEngineConnection.send(command.toString());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("[SERVER] SenyAlert WebSocket dispatch running on port " + getPort());
    }
}