package com.example.cameraapp;

import android.graphics.Bitmap;
import com.example.cameraapp.yolo.YoloDetector;

public class CropUtils {
    public static Bitmap crop(Bitmap bitmap, YoloDetector.Detection det) {
        return crop(bitmap, det, 0f);
    }

    /**
     * 検出枠の領域を切り出す。
     * @param margin 枠の幅・高さに対して周囲に広げる割合（0.1 なら上下左右に 10% ずつ広げる）
     * @return 切り出した画像。枠が画像の外にある場合は null
     */
    public static Bitmap crop(Bitmap bitmap, YoloDetector.Detection det, float margin) {
        float halfW = det.w / 2f + det.w * margin;
        float halfH = det.h / 2f + det.h * margin;
        int left = Math.max((int) (det.x - halfW), 0);
        int top = Math.max((int) (det.y - halfH), 0);
        int right = Math.min((int) (det.x + halfW), bitmap.getWidth());
        int bottom = Math.min((int) (det.y + halfH), bitmap.getHeight());

        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) return null;

        return Bitmap.createBitmap(bitmap, left, top, width, height);
    }
}
