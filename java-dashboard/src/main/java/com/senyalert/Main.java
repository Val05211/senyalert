package com.senyalert;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        // 1. Initialize SQLite Database Schema
        DatabaseManager.initializeDatabase();

        // 2. Launch Swing UI on the Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            DashboardFrame dashboard = new DashboardFrame();
            dashboard.setVisible(true);

            // 3. Start Background WebSocket Server on port 8080
            SenyAlertServer server = new SenyAlertServer(8080, dashboard);
            server.start();
        });
    }
}