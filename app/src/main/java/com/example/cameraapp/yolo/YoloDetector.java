package com.example.cameraapp.yolo;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class YoloDetector {

    public static final int INPUT_SIZE = 640;
    public static final float CONF_THRESHOLD = 0.005f;
    public static final float IOU_THRESHOLD = 0.5f;

    public static class Detection {
        public final float x, y, w, h, score;
        public final int classId;
        public final String label;

        public Detection(float x, float y, float w, float h, float score, int classId, String label) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.score = score;
            this.classId = classId;
            this.label = label;
        }
    }

    private final Interpreter interpreter;
    private float[][][] outputBuffer;
    private int outputClasses;
    private int outputAnchors;

    public YoloDetector(Context context) throws IOException {
        MappedByteBuffer model = loadModelFile(context, "yolo_plant_detect.tflite");
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        interpreter = new Interpreter(model, options);

        int[] shape = interpreter.getOutputTensor(0).shape(); // e.g., [1, 84, 8400]
        Log.d("YOLO_INIT", "Output shape: " + java.util.Arrays.toString(shape));

        int dim1 = shape[1];
        int dim2 = shape[2];

        outputBuffer = new float[1][dim1][dim2];
        outputClasses = dim1 - 4;
        outputAnchors = dim2;
    }

    public List<Detection> detect(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        ByteBuffer input = bitmapToBuffer(resized);

        interpreter.run(input, outputBuffer);

        List<Detection> raw = parseOutput(outputBuffer);
        return nms(raw);
    }

    private List<Detection> parseOutput(float[][][] output) {
        List<Detection> detections = new ArrayList<>();

        for (int i = 0; i < outputAnchors; i++) {
            float maxScore = 0f;
            int bestClassId = -1;

            for (int c = 0; c < outputClasses; c++) {
                float score = output[0][4 + c][i];
                if (score > maxScore) {
                    maxScore = score;
                    bestClassId = c;
                }
            }

            if (maxScore > CONF_THRESHOLD) {
                float rawCx = output[0][0][i];
                float rawCy = output[0][1][i];
                float rawW  = output[0][2][i];
                float rawH  = output[0][3][i];

                boolean isNormalized = rawCx >= 0f && rawCx <= 1.5f && rawCy >= 0f && rawCy <= 1.5f && rawW >= 0f && rawW <= 1.5f && rawH >= 0f && rawH <= 1.5f;

                float cx = isNormalized ? rawCx * INPUT_SIZE : rawCx;
                float cy = isNormalized ? rawCy * INPUT_SIZE : rawCy;
                float w  = isNormalized ? rawW  * INPUT_SIZE : rawW;
                float h  = isNormalized ? rawH  * INPUT_SIZE : rawH;

                if (w < 10f || h < 10f) continue;

                detections.add(new Detection(cx, cy, w, h, maxScore, bestClassId, String.valueOf(bestClassId)));
            }
        }
        return detections;
    }

    private List<Detection> nms(List<Detection> detections) {
        List<Detection> sorted = new ArrayList<>(detections);
        Collections.sort(sorted, new Comparator<Detection>() {
            @Override
            public int compare(Detection o1, Detection o2) {
                return Float.compare(o2.score, o1.score);
            }
        });

        List<Detection> results = new ArrayList<>();
        while (!sorted.isEmpty()) {
            Detection best = sorted.remove(0);
            results.add(best);

            Iterator<Detection> it = sorted.iterator();
            while (it.hasNext()) {
                Detection next = it.next();
                if (calculateIoU(best, next) > IOU_THRESHOLD) {
                    it.remove();
                }
            }
        }
        return results;
    }

    private float calculateIoU(Detection a, Detection b) {
        float areaA = a.w * a.h;
        float areaB = b.w * b.h;

        float x1 = Math.max(a.x - a.w / 2, b.x - b.w / 2);
        float y1 = Math.max(a.y - a.h / 2, b.y - b.h / 2);
        float x2 = Math.min(a.x + a.w / 2, b.x + b.w / 2);
        float y2 = Math.min(a.y + a.h / 2, b.y + b.h / 2);

        float iw = Math.max(0f, x2 - x1);
        float ih = Math.max(0f, y2 - y1);
        float inter = iw * ih;

        return inter / (areaA + areaB - inter);
    }

    private ByteBuffer bitmapToBuffer(Bitmap bitmap) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        buffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int v : intValues) {
            buffer.putFloat(((v >> 16) & 0xFF) / 255f);
            buffer.putFloat(((v >> 8) & 0xFF) / 255f);
            buffer.putFloat((v & 0xFF) / 255f);
        }
        buffer.rewind();
        return buffer;
    }

    private MappedByteBuffer loadModelFile(Context context, String name) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(name);
        FileInputStream input = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = input.getChannel();
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    public void close() {
        interpreter.close();
    }
}
