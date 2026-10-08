package com.example.cameraapp.inference;

import android.graphics.Bitmap;

/**
 * 一定時間（ウィンドウ）ごとに、最もブレの少ないフレームを1枚選ぶクラス。
 * ウィンドウは最初の候補が届いた時点から始まる。
 * ブレの少なさはラプラシアンの分散（エッジの鮮明さ）で評価する。
 */
public class BestFrameSelector {

    /** 選ばれたフレームと、その判別情報 */
    public static class Candidate {
        public final Bitmap bitmap;
        public final float sharpness;
        public final int cellIndex;
        public final String label;
        public final float confidence;

        Candidate(Bitmap bitmap, float sharpness, int cellIndex, String label, float confidence) {
            this.bitmap = bitmap;
            this.sharpness = sharpness;
            this.cellIndex = cellIndex;
            this.label = label;
            this.confidence = confidence;
        }
    }

    // ブレ評価用に縮小する幅（処理を軽くするため）
    private static final int SHARPNESS_WIDTH = 320;

    private final long windowMs;
    private Candidate best;
    private long windowStart = -1;

    public BestFrameSelector(long windowMs) {
        this.windowMs = windowMs;
    }

    /** 候補フレームを追加し、ウィンドウ内で最もシャープなものを保持する */
    public synchronized void offer(Bitmap bitmap, int cellIndex, String label, float confidence, long now) {
        // 区画が変わった場合は前の区画の候補を引き継がない
        if (best != null && best.cellIndex != cellIndex) clear();

        float sharpness = sharpness(bitmap);
        if (windowStart < 0) windowStart = now;
        if (best == null || sharpness > best.sharpness) {
            best = new Candidate(bitmap, sharpness, cellIndex, label, confidence);
        }
    }

    /** ウィンドウの時間が経過していれば最良フレームを返してリセットする（未経過なら null） */
    public synchronized Candidate pollIfExpired(long now) {
        if (best == null || now - windowStart < windowMs) return null;
        return flush();
    }

    /** 時間に関係なく現在の最良フレームを返してリセットする（候補がなければ null） */
    public synchronized Candidate flush() {
        Candidate c = best;
        clear();
        return c;
    }

    /** 保存待ちの候補フレームがあるか */
    public synchronized boolean hasCandidate() {
        return best != null;
    }

    public synchronized void clear() {
        best = null;
        windowStart = -1;
    }

    /** ラプラシアンの分散を計算する（値が大きいほどブレが少ない） */
    public static float sharpness(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        Bitmap small = bitmap;
        if (w > SHARPNESS_WIDTH) {
            int sh = Math.max(3, h * SHARPNESS_WIDTH / w);
            small = Bitmap.createScaledBitmap(bitmap, SHARPNESS_WIDTH, sh, true);
            w = small.getWidth();
            h = small.getHeight();
        }
        if (w < 3 || h < 3) return 0f;

        int[] pixels = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        if (small != bitmap) small.recycle();

        // グレースケール化
        int[] gray = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            gray[i] = (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
        }

        // 4近傍ラプラシアンの分散
        double sum = 0;
        double sumSq = 0;
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int lap = gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4 * gray[i];
                sum += lap;
                sumSq += (double) lap * lap;
                count++;
            }
        }
        double mean = sum / count;
        return (float) (sumSq / count - mean * mean);
    }
}
