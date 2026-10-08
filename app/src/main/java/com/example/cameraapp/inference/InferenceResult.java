package com.example.cameraapp.inference;

import androidx.annotation.Nullable;

public class InferenceResult {
    public enum Status {
        ACCEPTED,
        UNSTABLE,
        REJECTED
    }

    private final Status status;
    private final String label;
    private final float finalScore;
    private final float yoloScore;
    private final float frameConfidence;

    public InferenceResult(Status status, @Nullable String label, float finalScore, float yoloScore, float frameConfidence) {
        this.status = status;
        this.label = label;
        this.finalScore = finalScore;
        this.yoloScore = yoloScore;
        this.frameConfidence = frameConfidence;
    }

    public Status getStatus() { return status; }
    @Nullable public String getLabel() { return label; }
    public float getFinalScore() { return finalScore; }
    public float getYoloScore() { return yoloScore; }
    public float getFrameConfidence() { return frameConfidence; }

    public static InferenceResult accepted(String label, float finalScore, float yolo, float conf) {
        return new InferenceResult(Status.ACCEPTED, label, finalScore, yolo, conf);
    }

    public static InferenceResult unstable(float conf) {
        return new InferenceResult(Status.UNSTABLE, null, 0f, 0f, conf);
    }

    public static InferenceResult rejected() {
        return new InferenceResult(Status.REJECTED, null, 0f, 0f, 0f);
    }
}
