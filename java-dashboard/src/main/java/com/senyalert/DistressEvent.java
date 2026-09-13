package com.senyalert;

import java.util.EventObject;

public class DistressEvent extends EventObject {
    private final String cameraId;
    private final double confidence;
    private final long timestamp;
    private final String location;
    private final String triageContext;
    private int incidentId;

    public DistressEvent(Object source, String cameraId, double confidence, long timestamp, String location, String triageContext) {
        super(source);
        this.cameraId = cameraId;
        this.confidence = confidence;
        this.timestamp = timestamp;
        this.location = location;
        this.triageContext = triageContext;
    }

    public String getCameraId() { return cameraId; }
    public double getConfidence() { return confidence; }
    public long getTimestamp() { return timestamp; }
    public String getLocation() { return location; }
    public String getTriageContext() { return triageContext; }
    public int getIncidentId() { return incidentId; }
    public void setIncidentId(int incidentId) { this.incidentId = incidentId; }
}