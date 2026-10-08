package com.example.cameraapp;

public class ClassificationResult {
    private final String label;
    private final float confidence;
    private final float rawProb;

    public ClassificationResult(String label, float confidence, float rawProb) {
        this.label = label;
        this.confidence = confidence;
        this.rawProb = rawProb;
    }

    public ClassificationResult(String label, float confidence) {
        this(label, confidence, 0f);
    }

    public String getLabel() { return label; }
    public float getConfidence() { return confidence; }
    public float getRawProb() { return rawProb; }
}
