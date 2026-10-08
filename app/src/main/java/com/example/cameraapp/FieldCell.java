package com.example.cameraapp;

public class FieldCell {
    private final int index;
    private final String plantType;
    private FieldStatus status;

    public FieldCell(int index, String plantType, FieldStatus status) {
        this.index = index;
        this.plantType = plantType;
        this.status = status;
    }

    public int getIndex() {
        return index;
    }

    public String getPlantType() {
        return plantType;
    }

    public FieldStatus getStatus() {
        return status;
    }

    public void setStatus(FieldStatus status) {
        this.status = status;
    }
}
