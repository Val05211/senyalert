package com.senyalert;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import javax.swing.SwingUtilities;
import java.net.InetSocketAddress;

public class SenyAlertServer extends WebSocketServer {
    private final DashboardFrame dashboard;

    public SenyAlertServer(int port, DashboardFrame dashboard) {
        super(new InetSocketAddress(port));
        this.dashboard = dashboard;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[SOCKET] Ingestion engine connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("[SOCKET] Ingestion engine disconnected");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JSONObject json = new JSONObject(message);
            String cameraId = json.optString("cameraId", "UNKNOWN_CAM");
            double confidence = json.optDouble("confidence", 0.0);
            long timestamp = json.optLong("timestamp", System.currentTimeMillis() / 1000);
            String location = json.optString("location", "Unassigned Zone");
            String triageContext = json.optString("triage_context", "STANDARD");

            DistressEvent event = new DistressEvent(this, cameraId, confidence, timestamp, location, triageContext);

            // 1. Asynchronous SQLite write on detached thread[cite: 3, 4]
            DatabaseManager.insertIncidentAsync(cameraId, confidence, triageContext, (generatedId) -> {
                event.setIncidentId(generatedId);

                // 2. Safe marshalling to Swing Event Dispatch Thread (EDT)[cite: 3, 4]
                SwingUtilities.invokeLater(() -> dashboard.handleDistressEvent(event));
            });

        } catch (Exception e) {
            e.printStackTrace();
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