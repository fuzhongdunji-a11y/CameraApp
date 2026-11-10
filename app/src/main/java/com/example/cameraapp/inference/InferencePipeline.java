package com.example.cameraapp.inference;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.example.cameraapp.ClassificationResult;
import com.example.cameraapp.CropUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
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

    /** 判定に使った画像の種類 */
    public static final String SOURCE_YOLO = "YOLO";   // YOLO で検出した領域を切り出して判定
    public static final String SOURCE_FULL = "FULL";   // 画面全体で判定（YOLO で植物領域が見つからなかった場合）
    public static final String SOURCE_NONE = "NONE";   // 判定していない（緑不足など）

    public static class Config {
        private final float greenThreshold;
        private final boolean useYoloCrop;
        private final boolean fullFrameFallback;
        private final float cropGreenThreshold;

        /**
         * @param greenThreshold    画面全体で判定する場合に必要な緑色画素の割合
         * @param useYoloCrop       YOLO で検出した領域を切り出して分類モデルに渡すか
         * @param fullFrameFallback YOLO で植物領域が見つからないとき、画面全体で判定するか
         * @param cropGreenThreshold YOLO の切り出し領域を植物とみなすのに必要な緑色画素の割合。
         *                          枠で背景がある程度除かれているが、つやのある果実や影の部分は
         *                          緑判定から外れやすいため、画面全体より低めにしている
         */
        public Config(float greenThreshold, boolean useYoloCrop, boolean fullFrameFallback, float cropGreenThreshold) {
            this.greenThreshold = greenThreshold;
            this.useYoloCrop = useYoloCrop;
            this.fullFrameFallback = fullFrameFallback;
            this.cropGreenThreshold = cropGreenThreshold;
        }
        public Config(float greenThreshold) { this(greenThreshold, true, true, 0.15f); }
        public Config() { this(0.25f); } // 緑色の閾値を 25% に設定
        public float getGreenThreshold() { return greenThreshold; }
        public boolean isUseYoloCrop() { return useYoloCrop; }
        public boolean isFullFrameFallback() { return fullFrameFallback; }
        public float getCropGreenThreshold() { return cropGreenThreshold; }
    }

    // 切り出し時に検出枠の周囲を広げる割合（葉の縁が切れないように上下左右 10% ずつ）
    private static final float CROP_MARGIN = 0.10f;
    // 小さすぎる枠は分類に向かないので使わない（短辺のピクセル数）
    private static final int MIN_CROP_SIZE = 64;
    // 切り出しを試す検出枠の数（スコアの高い順）
    private static final int MAX_CROP_CANDIDATES = 5;

    public static class Result {
        private final String label;
        private final float confidence;
        private final String savedImagePath;
        private final float greenRatio;
        private final String source;
        private final float yoloScore;
        private final YoloDetector.Detection detection;

        public Result(String label, float confidence, String savedImagePath, float greenRatio,
                      String source, float yoloScore, YoloDetector.Detection detection) {
            this.label = label;
            this.confidence = confidence;
            this.savedImagePath = savedImagePath;
            this.greenRatio = greenRatio;
            this.source = source;
            this.yoloScore = yoloScore;
            this.detection = detection;
        }
        public Result(String label, float confidence, String savedImagePath, float greenRatio) {
            this(label, confidence, savedImagePath, greenRatio, SOURCE_NONE, 0f, null);
        }
        public String getLabel() { return label; }
        public float getConfidence() { return confidence; }
        public String getSavedImagePath() { return savedImagePath; }
        /** 判定に使った画像（切り出し画像または画面全体）の緑色画素の割合 */
        public float getGreenRatio() { return greenRatio; }
        /** SOURCE_YOLO / SOURCE_FULL / SOURCE_NONE のいずれか */
        public String getSource() { return source; }
        /** 分類モデルで判定したか（false なら緑不足などで判定していない） */
        public boolean isClassified() { return !SOURCE_NONE.equals(source); }
        /** 切り出しに使った YOLO 検出のスコア（0〜1）。切り出していない場合は 0 */
        public float getYoloScore() { return yoloScore; }
        /** 切り出しに使った検出枠（元画像の座標）。切り出していない場合は null */
        public YoloDetector.Detection getDetection() { return detection; }
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
        float frameGreenRatio = calculateGreenRatioFast(bitmap);

        // 1. YOLO で検出した領域のうち、緑色が十分なものを切り出す
        PlantCrop crop = config.isUseYoloCrop() ? findPlantCrop(bitmap) : null;

        String label;
        float confidence;
        float greenRatio;
        String source;
        String imagePath = null;

        if (crop != null) {
            // 2. 切り出した画像を分類モデルに渡す
            ClassificationResult classResult = classifier.classify(crop.bitmap);
            label = classResult.getLabel();
            confidence = classResult.getConfidence();
            greenRatio = crop.greenRatio;
            source = SOURCE_YOLO;
            if (crop.bitmap != bitmap) crop.bitmap.recycle();
        } else if (config.isFullFrameFallback() && frameGreenRatio >= config.getGreenThreshold()) {
            // 3. 植物領域が見つからない場合は、従来どおり画面全体で判定する
            ClassificationResult classResult = classifier.classify(bitmap);
            label = classResult.getLabel();
            confidence = classResult.getConfidence();
            greenRatio = frameGreenRatio;
            source = SOURCE_FULL;
        } else {
            // 緑色の割合が閾値未満（または植物領域なし）の場合は判定をスキップし、スコアを 0 に固定
            label = config.isFullFrameFallback() || frameGreenRatio < config.getGreenThreshold()
                    ? "Low Green" : "No Plant";
            confidence = 0.0f;
            greenRatio = frameGreenRatio;
            source = SOURCE_NONE;
        }

        // 判定していないフレームは保存しない。正常フレームは skipHealthy の場合保存しない
        // （保存するのは記録として見やすい画面全体の画像）
        boolean classified = !SOURCE_NONE.equals(source);
        boolean healthy = "healthy".equalsIgnoreCase(label);
        if (shouldSaveImage && classified && !(skipHealthy && healthy) && context != null) {
            imagePath = saveClassified(bitmap, cellIndex, label, confidence);
        }

        Log.d("Pipeline", String.format(Locale.getDefault(),
                "Source: %s, YOLO: %.2f, Ratio: %.3f (frame %.3f), Threshold: %.2f, Label: %s, Score: %.1f",
                source, crop != null ? crop.detection.score : 0f, greenRatio, frameGreenRatio,
                config.getGreenThreshold(), label, confidence));

        return new Result(label, confidence, imagePath, greenRatio, source,
                crop != null ? crop.detection.score : 0f,
                crop != null ? crop.detection : null);
    }

    /** 切り出した植物領域 */
    private static class PlantCrop {
        final Bitmap bitmap;
        final YoloDetector.Detection detection;
        final float greenRatio;

        PlantCrop(Bitmap bitmap, YoloDetector.Detection detection, float greenRatio) {
            this.bitmap = bitmap;
            this.detection = detection;
            this.greenRatio = greenRatio;
        }
    }

    /**
     * YOLO の検出枠をスコアの高い順に切り出し、緑色の割合が切り出し用の閾値以上の最初の領域を返す。
     * 公開されている YOLO モデル（COCO 学習）は作物を「植物」として学習していないため、
     * クラス名では絞らず、切り出した領域が緑色かどうかで植物領域とみなす。
     * 見つからなければ null。
     */
    private PlantCrop findPlantCrop(Bitmap bitmap) {
        if (yoloDetector == null) return null;

        List<YoloDetector.Detection> detections;
        try {
            detections = yoloDetector.detect(bitmap); // スコアの高い順
        } catch (Exception e) {
            Log.e("Pipeline", "YOLO detect failed", e);
            return null;
        }

        Log.d("PipelineYolo", "YOLO detections: " + detections.size());

        int tried = 0;
        for (YoloDetector.Detection det : detections) {
            if (tried >= MAX_CROP_CANDIDATES) break;
            tried++;

            Bitmap cropped = CropUtils.crop(bitmap, det, CROP_MARGIN);
            if (cropped == null) continue;
            if (Math.min(cropped.getWidth(), cropped.getHeight()) < MIN_CROP_SIZE) {
                if (cropped != bitmap) cropped.recycle();
                continue;
            }

            float ratio = calculateGreenRatioFast(cropped);
            Log.d("PipelineYolo", String.format(Locale.getDefault(),
                    "  #%d class=%d score=%.2f box=(%.0f,%.0f %.0fx%.0f) cropGreen=%.3f (need %.2f)",
                    tried, det.classId, det.score, det.x, det.y, det.w, det.h, ratio,
                    config.getCropGreenThreshold()));
            if (ratio >= config.getCropGreenThreshold()) {
                return new PlantCrop(cropped, det, ratio);
            }
            if (cropped != bitmap) cropped.recycle();
        }
        return null;
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
