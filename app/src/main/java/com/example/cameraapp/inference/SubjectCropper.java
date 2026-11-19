package com.example.cameraapp.inference;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.Subject;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ML Kit の Subject Segmentation（被写体の切り抜き）で、写真から被写体を切り出す。
 *
 * 切り出し方は2通り（学習データの作成と、アプリでの判定で同じものを使うこと）:
 *   - MASK: 被写体の輪郭の外を塗りつぶし色で塗り、被写体の外接枠で切り出す
 *   - BOX : 元の写真を被写体の外接枠で切り出すだけ（背景は残る）
 *
 * process() は ML Kit の結果を待つので、メインスレッドでは呼ばないこと。
 * 端末に ML Kit のモデルがまだない場合は、Google Play 開発者サービスが初回にダウンロードする。
 */
public class SubjectCropper implements AutoCloseable {

    /** 1つの切り出し結果。 */
    public static class Crop {
        public final String kind;      // "fg"（被写体全部）か "subject"（被写体1つずつ）
        public final int index;        // subject の番号（面積の大きい順）。fg は 0
        public final Rect box;         // 元の写真での外接枠
        public final float areaFrac;   // 写真に対する被写体（輪郭の内側）の面積の割合
        public final Bitmap masked;    // 輪郭の外を塗りつぶしたもの
        public final Bitmap boxed;     // 外接枠で切り出しただけのもの

        Crop(String kind, int index, Rect box, float areaFrac, Bitmap masked, Bitmap boxed) {
            this.kind = kind; this.index = index; this.box = box; this.areaFrac = areaFrac;
            this.masked = masked; this.boxed = boxed;
        }
    }

    /** 輪郭の外の塗りつぶし色。学習スクリプト（tools/train_pepper_seg_classifier.py）と合わせる。 */
    public static final int FILL_COLOR = Color.BLACK;

    private final SubjectSegmenter segmenter;

    public SubjectCropper() {
        SubjectSegmenterOptions options = new SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .enableMultipleSubjects(new SubjectSegmenterOptions.SubjectResultOptions.Builder()
                        .enableSubjectBitmap()
                        .build())
                .build();
        segmenter = SubjectSegmentation.getClient(options);
    }

    /**
     * 写真から、被写体全部（fg）と被写体1つずつ（subject、面積の大きい順に最大 maxSubjects 個）を切り出す。
     * minAreaFrac より小さい被写体は捨てる。何も見つからなければ空のリスト。
     */
    public List<Crop> process(Bitmap src, int maxSubjects, float minAreaFrac) throws Exception {
        SubjectSegmentationResult r = Tasks.await(segmenter.process(InputImage.fromBitmap(src, 0)));
        List<Crop> out = new ArrayList<>();
        float total = (float) src.getWidth() * src.getHeight();

        Bitmap fg = r.getForegroundBitmap();
        if (fg != null) {
            int[] st = alphaBox(fg);
            if (st != null && st[4] / total >= minAreaFrac) {
                Rect b = new Rect(st[0], st[1], st[2], st[3]);
                out.add(new Crop("fg", 0, b, st[4] / total,
                        fill(Bitmap.createBitmap(fg, b.left, b.top, b.width(), b.height())),
                        Bitmap.createBitmap(src, b.left, b.top, b.width(), b.height())));
            }
        }

        List<Subject> subjects = new ArrayList<>(r.getSubjects());
        List<int[]> stats = new ArrayList<>();
        for (Subject s : subjects) {
            Bitmap sb = s.getBitmap();
            int[] st = sb == null ? null : alphaBox(sb);
            stats.add(st);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < subjects.size(); i++) if (stats.get(i) != null) order.add(i);
        Collections.sort(order, (a, b) -> Integer.compare(stats.get(b)[4], stats.get(a)[4]));
        int n = 0;
        for (int i : order) {
            if (n >= maxSubjects) break;
            int[] st = stats.get(i);
            if (st[4] / total < minAreaFrac) continue;
            Subject s = subjects.get(i);
            Bitmap sb = s.getBitmap();  // 被写体の枠の大きさ。枠の外は透明
            Rect inner = new Rect(st[0], st[1], st[2], st[3]);
            Rect b = new Rect(s.getStartX() + inner.left, s.getStartY() + inner.top,
                    s.getStartX() + inner.right, s.getStartY() + inner.bottom);
            b.intersect(0, 0, src.getWidth(), src.getHeight());
            if (b.width() < 8 || b.height() < 8) continue;
            out.add(new Crop("subject", n, b, st[4] / total,
                    fill(Bitmap.createBitmap(sb, inner.left, inner.top, inner.width(), inner.height())),
                    Bitmap.createBitmap(src, b.left, b.top, b.width(), b.height())));
            n++;
        }
        return out;
    }

    /** 透明でない画素の外接枠 {left, top, right, bottom, 画素数}。なければ null。 */
    private static int[] alphaBox(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, rr = -1, bb = -1, cnt = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 127) {
                    cnt++;
                    if (x < l) l = x;
                    if (x > rr) rr = x;
                    if (y < t) t = y;
                    if (y > bb) bb = y;
                }
            }
        }
        return cnt == 0 ? null : new int[]{l, t, rr + 1, bb + 1, cnt};
    }

    /** 透明な部分を FILL_COLOR で塗った不透明の画像にする。 */
    private static Bitmap fill(Bitmap withAlpha) {
        Bitmap out = Bitmap.createBitmap(withAlpha.getWidth(), withAlpha.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(FILL_COLOR);
        c.drawBitmap(withAlpha, 0, 0, null);
        return out;
    }

    @Override
    public void close() {
        segmenter.close();
    }
}
