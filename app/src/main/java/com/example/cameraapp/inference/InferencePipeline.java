package com.example.cameraapp.inference;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.example.cameraapp.ClassificationResult;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.example.cameraapp.yolo.YoloDetector;

public class InferencePipeline {

    public interface ClassifierCallback {
        ClassificationResult classify(Bitmap bitmap);
    }

    private final YoloDetector yoloDetector;
    private final Config config;
    private final ClassifierCallback classifier;
    private Context context;

    public static class Config {
        private final float greenThreshold;
        public Config(float greenThreshold) { this.greenThreshold = greenThreshold; }
        public Config() { this(0.25f); } // 緑色の閾値を 25% に設定
        public float getGreenThreshold() { return greenThreshold; }
    }

    public static class Result {
        private final String label;
        private final float confidence;
        private final String savedImagePath;
        private final float greenRatio;

        public Result(String label, float confidence, String savedImagePath, float greenRatio) {
            this.label = label;
            this.confidence = confidence;
            this.savedImagePath = savedImagePath;
            this.greenRatio = greenRatio;
        }
        public String getLabel() { return label; }
        public float getConfidence() { return confidence; }
        public String getSavedImagePath() { return savedImagePath; }
        public float getGreenRatio() { return greenRatio; }
    }

    public InferencePipeline(YoloDetector yoloDetector, Config config, ClassifierCallback classifier) {
        this.yoloDetector = yoloDetector;
        this.config = config != null ? config : new Config();
        this.classifier = classifier;
    }

    public InferencePipeline(YoloDetector yoloDetector, ClassifierCallback classifier) {
        this(yoloDetector, new Config(), classifier);
    }

    public Config getConfig() { return config; }

    public void setContext(Context context) { this.context = context; }

    /**
     * @param bitmap 処理対象の画像
     * @param cellIndex セル番号
     * @param shouldSaveImage 条件(時間間隔など)が満たされている場合に保存を試みるか
     * @param skipHealthy true の場合、判定結果が正常(healthy)のフレームは保存しない
     */
    public Result process(Bitmap bitmap, int cellIndex, boolean shouldSaveImage, boolean skipHealthy) {
        float greenRatio = calculateGreenRatioFast(bitmap);

        String label;
        float confidence;
        String imagePath = null;

        boolean enoughGreen = greenRatio >= config.getGreenThreshold();
        if (enoughGreen) {
            // 閾値以上の場合のみ、毎フレーム AI による分類を実行
            ClassificationResult classResult = classifier.classify(bitmap);
            label = classResult.getLabel();
            confidence = classResult.getConfidence();
        } else {
            // 緑色の割合が閾値未満の場合、判定をスキップし、ラベルを "Low Green"、スコアを 0 に固定
            label = "Low Green";
            confidence = 0.0f;
        }

        // 緑不足のフレームは保存しない。正常フレームは skipHealthy の場合保存しない
        boolean healthy = "healthy".equalsIgnoreCase(label);
        if (shouldSaveImage && enoughGreen && !(skipHealthy && healthy) && context != null) {
            imagePath = saveClassified(bitmap, cellIndex, label, confidence);
        }

        Log.d("Pipeline", String.format(Locale.getDefault(), "Ratio: %.3f, Threshold: %.2f, Label: %s, Score: %.1f", 
                greenRatio, config.getGreenThreshold(), label, confidence));
        
        return new Result(label, confidence, imagePath, greenRatio);
    }

    public Result process(Bitmap bitmap, int cellIndex, boolean shouldSaveImage) {
        return process(bitmap, cellIndex, shouldSaveImage, false);
    }

    /** 判別結果（ラベル・スコア帯・日付）のフォルダに画像を保存する */
    public String saveClassified(Bitmap bitmap, int cellIndex, String label, float confidence) {
        int bracket = (int)(confidence / 10);
        if (bracket > 9) bracket = 9;
        if (bracket < 0) bracket = 0;
        String scoreBracket = String.format(Locale.getDefault(), "%d-%d%%", bracket * 10, (bracket + 1) * 10);

        String dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String nestedPath = "Cell_" + cellIndex + "/" + label + "/" + scoreBracket + "/" + dateStr;
        return saveToNestedFolder(bitmap, nestedPath, "img_" + System.nanoTime() + ".jpg");
    }

    public String saveToNestedFolder(Bitmap bmp, String subDir, String fileName) {
        if (context == null) return null;
        File dir = new File(context.getExternalFilesDir(null), "categories/" + subDir);
        if (!dir.exists()) dir.mkdirs();
        
        File file = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out);
            return file.getAbsolutePath();
        } catch (Exception e) {
            Log.e("Pipeline", "Save failed", e);
            return null;
        }
    }

    /**
     * 植物以外の緑っぽいノイズ（土や影）を徹底的に排除する厳格な緑判定
     * HSV空間での彩度(Saturation)と色相(Hue)を厳しくチェックします。
     */
    private float calculateGreenRatioFast(Bitmap bitmap) {
        int greenCount = 0;
        int totalCount = 0;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        float[] hsv = new float[3];
        
        // 5ピクセルおきにサンプリングして精度と速度を両立
        for (int y = 0; y < h; y += 5) {
            for (int x = 0; x < w; x += 5) {
                int p = bitmap.getPixel(x, y);
                totalCount++;
                
                // 手動ビットシフトで RGB 取得
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                
                // 【超厳格判定】
                // 1. GがRおよびBよりも明らかに強い
                // 2. Gがある程度の明るさ（45以上）を持っている
                if (g > r * 1.25f && g > b * 1.15f && g > 45) {
                    Color.RGBToHSV(r, g, b, hsv);
                    
                    // 3. Hue: 80(黄緑)〜160(青緑) ※茶色や黄色、青い影を完全に除外
                    // 4. Saturation: 0.50以上 (重要：これで土やグレーを確実に弾く)
                    // 5. Value: 0.25以上
                    if (hsv[0] >= 80 && hsv[0] <= 160 && hsv[1] >= 0.50f && hsv[2] >= 0.25f) {
                        greenCount++;
                    }
                }
            }
        }
        return totalCount == 0 ? 0f : (float) greenCount / totalCount;
    }
}
