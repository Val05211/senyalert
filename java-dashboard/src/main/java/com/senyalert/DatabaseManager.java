package com.senyalert;

import java.sql.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:incidents.db";
    // Detached thread pool to perform asynchronous persistence off the Swing EDT
    private static final ExecutorService dbWorker = Executors.newSingleThreadExecutor();

    public static void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS cameras (
                    camera_id TEXT PRIMARY KEY,
                    zone_name TEXT NOT NULL,
                    threat_profile TEXT DEFAULT 'STANDARD'
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS incident_logs (
                    incident_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    camera_id TEXT NOT NULL,
                    detection_timestamp TEXT DEFAULT (datetime('now', 'localtime')),
                    confidence REAL NOT NULL,
                    triage_level TEXT NOT NULL,
                    status TEXT DEFAULT 'PENDING',
                    operator_notes TEXT
                );
            """);

            stmt.execute("INSERT OR IGNORE INTO cameras (camera_id, zone_name, threat_profile) " +
                         "VALUES ('CAM-01-LAPTOP', 'Public Intake Counter A', 'HIGH_TRAFFIC');");
                         
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void insertIncidentAsync(String cameraId, double confidence, String triageLevel, Consumer<Integer> callback) {
        dbWorker.submit(() -> {
            String sql = "INSERT INTO incident_logs (camera_id, confidence, triage_level, status) VALUES (?, ?, ?, 'PENDING');";
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                
                pstmt.setString(1, cameraId);
                pstmt.setDouble(2, confidence);
                pstmt.setString(3, triageLevel);
                pstmt.executeUpdate();

                ResultSet rs = pstmt.getGeneratedKeys();
                int generatedId = -1;
                if (rs.next()) {
                    generatedId = rs.getInt(1);
                }
                if (callback != null) {
                    callback.accept(generatedId);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    public static void updateStatusAsync(int incidentId, String newStatus, String notes) {
        dbWorker.submit(() -> {
            String sql = "UPDATE incident_logs SET status = ?, operator_notes = ? WHERE incident_id = ?;";
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, newStatus);
                pstmt.setString(2, notes);
                pstmt.setInt(3, incidentId);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    public static void loadHistoricalIncidents(javax.swing.table.DefaultTableModel tableModel) {
        dbWorker.submit(() -> {
            String sql = "SELECT incident_id, camera_id, triage_level, confidence, status, detection_timestamp FROM incident_logs ORDER BY incident_id DESC;";
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                
                while (rs.next()) {
                    Object[] row = {
                        rs.getInt("incident_id"),
                        rs.getString("camera_id"),
                        rs.getString("triage_level"),
                        String.format("%.2f", rs.getDouble("confidence")),
                        rs.getString("status"),
                        rs.getString("detection_timestamp")
                    };
                    // Marshal back to EDT
                    javax.swing.SwingUtilities.invokeLater(() -> tableModel.addRow(row));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    public static void deleteIncidentAsync(int incidentId) {
        dbWorker.submit(() -> {
            String sql = "DELETE FROM incident_logs WHERE incident_id = ?;";
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, incidentId);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }
}