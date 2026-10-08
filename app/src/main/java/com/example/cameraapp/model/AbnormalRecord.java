package com.example.cameraapp.model;

import java.io.Serializable;

public class AbnormalRecord implements Serializable {
    private final int cellId;
    private final String plantType;
    private final String imagePath;
    private final String imageUri;
    private final float score;
    private final long timestamp;
    private final Double latitude;
    private final Double longitude;
    private final String label;

    public AbnormalRecord(int cellId, String plantType, String imagePath, String imageUri, float score, long timestamp, Double latitude, Double longitude, String label) {
        this.cellId = cellId;
        this.plantType = plantType;
        this.imagePath = imagePath;
        this.imageUri = imageUri;
        this.score = score;
        this.timestamp = timestamp;
        this.latitude = latitude;
        this.longitude = longitude;
        this.label = label;
    }

    public AbnormalRecord(int cellId, String plantType, String imagePath, float score, long timestamp, String label) {
        this(cellId, plantType, imagePath, null, score, timestamp, null, null, label);
    }

    public int getCellId() { return cellId; }
    public String getPlantType() { return plantType; }
    public String getImagePath() { return imagePath; }
    public String getImageUri() { return imageUri; }
    public float getScore() { return score; }
    public long getTimestamp() { return timestamp; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getLabel() { return label; }
}
