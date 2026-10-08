package com.example.cameraapp.data;

public class CellResult {
    private final int index;
    private final int row;
    private final int col;
    private final String result;
    private final long timestamp;
    private final String imagePath;
    private final double latitude;
    private final double longitude;
    private final float accuracy; // GPS精度
    private float confidence;      // AIの自信度 (0-100)
    private volatile boolean uploaded; // クラウド(S3)へアップロード済みか

    public CellResult(int index, int row, int col, String result, long timestamp, String imagePath, double latitude, double longitude, float accuracy) {
        this.index = index;
        this.row = row;
        this.col = col;
        this.result = result;
        this.timestamp = timestamp;
        this.imagePath = imagePath;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
        this.confidence = 100.0f; // デフォルト
    }

    public int getIndex() { return index; }
    public int getRow() { return row; }
    public int getCol() { return col; }
    public String getResult() { return result; }
    public long getTimestamp() { return timestamp; }
    public String getImagePath() { return imagePath; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public float getAccuracy() { return accuracy; }
    
    public float getConfidence() { return confidence; }
    public void setConfidence(float confidence) { this.confidence = confidence; }

    public boolean isUploaded() { return uploaded; }
    public void setUploaded(boolean uploaded) { this.uploaded = uploaded; }

    @Override
    public String toString() {
        return "CellResult{index=" + index + ", result='" + result + "', conf=" + confidence + "}";
    }
}
