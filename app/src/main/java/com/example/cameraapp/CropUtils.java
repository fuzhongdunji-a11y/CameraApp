package com.example.cameraapp;

import android.graphics.Bitmap;
import com.example.cameraapp.yolo.YoloDetector;

public class CropUtils {
    public static Bitmap crop(Bitmap bitmap, YoloDetector.Detection det) {
        int left = Math.max((int) (det.x - det.w / 2), 0);
        int top = Math.max((int) (det.y - det.h / 2), 0);
        int right = Math.min((int) (det.x + det.w / 2), bitmap.getWidth());
        int bottom = Math.min((int) (det.y + det.h / 2), bitmap.getHeight());

        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) return null;

        return Bitmap.createBitmap(bitmap, left, top, width, height);
    }
}
