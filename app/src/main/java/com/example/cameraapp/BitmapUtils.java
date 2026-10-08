package com.example.cameraapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.documentfile.provider.DocumentFile;
import java.io.InputStream;

public class BitmapUtils {

    /**
     * DocumentFile から Bitmap を安全に読み込む
     * @return Bitmap? 失敗時は null
     */
    public static Bitmap loadBitmap(Context context, DocumentFile imageDoc) {
        try {
            InputStream input = context.getContentResolver().openInputStream(imageDoc.getUri());
            if (input == null) return null;

            // メモリ節約のためのオプション
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            return BitmapFactory.decodeStream(input, null, options);
        } catch (Exception e) {
            return null;
        }
    }
}
