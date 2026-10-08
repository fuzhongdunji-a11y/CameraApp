package com.example.cameraapp;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PlantClassifier {

    private final Context context;
    private String currentPlant;
    private Interpreter tflite;
    private List<String> classNames = Collections.emptyList();
    private int inputSize = 256; 

    // 数値の偏りを和らげるパラメータ (値を大きくするほど信頼度がマイルドになる)
    // 99%以上に張り付くのを防ぐため 2.5f から 5.0f に引き上げ
    private static final float TEMPERATURE = 5.0f;

    private static final Map<String, String> modelMap = new HashMap<>();
    static {
        modelMap.put("りんご", "apple_model.tflite");
        modelMap.put("トマト", "Tomato_model.tflite");
        modelMap.put("ブドウ", "grape_model.tflite");
        modelMap.put("ポテト", "potato_model.tflite");
        modelMap.put("イチゴ", "strawberry_model.tflite");
        modelMap.put("コーン", "corn_model.tflite");
        modelMap.put("ピーマン", "pepper_model.tflite");
        modelMap.put("チェリー", "cherry_model.tflite");
        modelMap.put("もも", "peach_model.tflite");
    }

    public PlantClassifier(Context context, String currentPlant) {
        this.context = context;
        this.currentPlant = currentPlant;
        loadModel(currentPlant);
    }

    private void loadModel(String plantName) {
        String modelName = modelMap.get(plantName);
        if (modelName == null) throw new IllegalArgumentException("モデルが存在しません: " + plantName);

        try {
            if (tflite != null) tflite.close();
            tflite = loadModelFile(modelName);
            inputSize = tflite.getInputTensor(0).shape()[1]; 
            int numClasses = tflite.getOutputTensor(0).shape()[1];

            Map<Integer, List<String>> labelMap = new HashMap<>();
            labelMap.put(2, Arrays.asList("diseased", "healthy"));
            labelMap.put(3, Arrays.asList("early_blight", "late_blight", "healthy"));

            classNames = labelMap.get(numClasses);
            if (classNames == null) {
                classNames = new ArrayList<>();
                for (int i = 0; i < numClasses; i++) classNames.add("class_" + i);
            }
        } catch (Exception e) {
            Log.e("CLASSIFIER", "Load failed", e);
        }
    }

    public synchronized ClassificationResult classifyImage(Bitmap bitmap) {
        if (tflite == null) return new ClassificationResult("Error", 0f);

        ByteBuffer input = preprocess(bitmap);
        float[][] output = new float[1][classNames.size()];
        tflite.run(input, output);

        float[] rawProbs = output[0];
        
        // 温度スケーリングを適用して数値をマイルドにする
        float[] softenedProbs = applyTemperatureScaling(rawProbs, TEMPERATURE);
        
        int maxIdx = 0;
        for (int i = 1; i < softenedProbs.length; i++) {
            if (softenedProbs[i] > softenedProbs[maxIdx]) maxIdx = i;
        }

        String label = !classNames.isEmpty() ? classNames.get(maxIdx) : "Unknown";
        float confidence = softenedProbs[maxIdx] * 100f;

        return new ClassificationResult(label, confidence, softenedProbs[maxIdx]);
    }

    private float[] applyTemperatureScaling(float[] probs, float t) {
        float[] adjusted = new float[probs.length];
        float sum = 0;
        for (int i = 0; i < probs.length; i++) {
            // 確率 p を p^(1/T) に変換して正規化
            adjusted[i] = (float) Math.pow(Math.max(probs[i], 1e-9), 1.0 / t);
            sum += adjusted[i];
        }
        for (int i = 0; i < adjusted.length; i++) adjusted[i] /= sum;
        return adjusted;
    }

    private ByteBuffer preprocess(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3);
        buffer.order(ByteOrder.nativeOrder());
        int[] pixels = new int[inputSize * inputSize];
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize);
        for (int p : pixels) {
            buffer.putFloat(((p >> 16) & 0xFF) / 255f);
            buffer.putFloat(((p >> 8) & 0xFF) / 255f);
            buffer.putFloat((p & 0xFF) / 255f);
        }
        if (resized != bitmap) resized.recycle();
        buffer.rewind();
        return buffer;
    }

    private Interpreter loadModelFile(String modelName) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(modelName);
        FileInputStream input = new FileInputStream(fd.getFileDescriptor());
        return new Interpreter(input.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength()));
    }

    public synchronized void close() { if (tflite != null) tflite.close(); }
}
