package com.example.cameraapp;

public class CsvRecord {
    private final int cellId;
    private final int row;
    private final int col;
    private final String imageName;
    private final long timestamp;
    private final Double latitude;
    private final Double longitude;
    private final Float accuracy;

    public CsvRecord(int cellId, int row, int col, String imageName, long timestamp, Double latitude, Double longitude, Float accuracy) {
        this.cellId = cellId;
        this.row = row;
        this.col = col;
        this.imageName = imageName;
        this.timestamp = timestamp;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
    }

    public CsvRecord(int cellId, int row, int col, String imageName) {
        this(cellId, row, col, imageName, System.currentTimeMillis(), null, null, null);
    }

    public int getCellId() { return cellId; }
    public int getRow() { return row; }
    public int getCol() { return col; }
    public String getImageName() { return imageName; }
    public long getTimestamp() { return timestamp; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Float getAccuracy() { return accuracy; }
}
