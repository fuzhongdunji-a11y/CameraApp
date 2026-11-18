package com.example.cameraapp;

import android.graphics.Bitmap;
import android.graphics.RectF;

public class CropUtils {
    /** 枠（画像の座標、左上と右下）で画像を切り出す。枠が画像の外なら null。 */
    public static Bitmap crop(Bitmap bitmap, RectF box) {
        int left = Math.max((int) box.left, 0);
        int top = Math.max((int) box.top, 0);
        int right = Math.min((int) box.right, bitmap.getWidth());
        int bottom = Math.min((int) box.bottom, bitmap.getHeight());

        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) return null;

        return Bitmap.createBitmap(bitmap, left, top, width, height);
    }
}
