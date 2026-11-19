package com.example.cameraapp.tools;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.exifinterface.media.ExifInterface;

import com.example.cameraapp.inference.SubjectCropper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 学習データ作成用（debug ビルドだけに入る）。ML Kit の Subject Segmentation で写真を一括で切り抜く。
 *
 * 使い方（PC から adb で。詳しくは tools/train_pepper_seg_classifier.py の説明）:
 *   adb push seg_in /sdcard/Android/data/com.example.cameraapp/files/
 *   adb shell am start -n com.example.cameraapp/.tools.SegExportActivity
 *   （画面に「完了」と出るまで待つ）
 *   adb pull /sdcard/Android/data/com.example.cameraapp/files/seg_out
 *
 * files/seg_in の下の画像（サブフォルダも）を処理し、files/seg_out に同じフォルダ構成で
 *   <名前>__fg_mask.jpg / __fg_box.jpg / __subject0_mask.jpg / __subject0_box.jpg ...
 * と manifest.csv（元画像・種類・番号・外接枠・面積の割合・出力ファイル）を書き出す。
 */
public class SegExportActivity extends Activity {

    private static final int MAX_SIDE = 1024;      // 長い辺をここまで縮めてから切り抜く
    private static final int MAX_SUBJECTS = 5;
    private static final float MIN_AREA = 0.01f;   // 写真の 1% より小さい被写体は捨てる

    private TextView log;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log = new TextView(this);
        log.setPadding(24, 24, 24, 24);
        scroll = new ScrollView(this);
        scroll.addView(log);
        setContentView(scroll);
        new Thread(this::runExport).start();
    }

    private void print(String s) {
        runOnUiThread(() -> {
            log.append(s + "\n");
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void runExport() {
        File in = new File(getExternalFilesDir(null), "seg_in");
        File out = new File(getExternalFilesDir(null), "seg_out");
        List<File> files = new ArrayList<>();
        collect(in, files);
        Collections.sort(files);
        print("入力: " + in + "（" + files.size() + " 枚）");
        if (files.isEmpty()) {
            print("画像がありません。adb push で seg_in に画像を入れてください。");
            return;
        }
        out.mkdirs();
        long t0 = System.currentTimeMillis();
        int done = 0, empty = 0, failed = 0;
        try (SubjectCropper cropper = new SubjectCropper();
             PrintWriter man = new PrintWriter(new FileOutputStream(new File(out, "manifest.csv")))) {
            man.println("src,kind,index,x0,y0,x1,y1,img_w,img_h,area_frac,mask_file,box_file");
            for (File f : files) {
                String rel = in.toURI().relativize(f.toURI()).getPath();
                try {
                    Bitmap bm = load(f);
                    if (bm == null) { failed++; print("読めない: " + rel); continue; }
                    List<SubjectCropper.Crop> crops = cropper.process(bm, MAX_SUBJECTS, MIN_AREA);
                    if (crops.isEmpty()) {
                        empty++;
                        man.println(csv(rel) + ",none,0,0,0,0,0," + bm.getWidth() + "," + bm.getHeight() + ",0,,");
                    }
                    String stem = rel.contains(".") ? rel.substring(0, rel.lastIndexOf('.')) : rel;
                    for (SubjectCropper.Crop c : crops) {
                        String base = stem + "__" + c.kind + (c.kind.equals("fg") ? "" : String.valueOf(c.index));
                        String mf = base + "_mask.jpg", bf = base + "_box.jpg";
                        save(c.masked, new File(out, mf));
                        save(c.boxed, new File(out, bf));
                        man.println(String.format(Locale.US, "%s,%s,%d,%d,%d,%d,%d,%d,%d,%.5f,%s,%s",
                                csv(rel), c.kind, c.index, c.box.left, c.box.top, c.box.right, c.box.bottom,
                                bm.getWidth(), bm.getHeight(), c.areaFrac, csv(mf), csv(bf)));
                    }
                } catch (Exception e) {
                    failed++;
                    print("失敗: " + rel + " " + e);
                }
                done++;
                if (done % 20 == 0) {
                    man.flush();
                    print(done + " / " + files.size() + "（" + (System.currentTimeMillis() - t0) / 1000 + " 秒）");
                }
            }
        } catch (Exception e) {
            print("エラー: " + e);
            return;
        }
        print("完了: " + done + " 枚（被写体なし " + empty + "、失敗 " + failed + "）。出力: " + out);
    }

    private static void collect(File dir, List<File> acc) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, acc);
            else {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) acc.add(f);
            }
        }
    }

    /** EXIF の向きを直し、長い辺を MAX_SIDE までに縮めて読む。 */
    private static Bitmap load(File f) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= MAX_SIDE) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
        if (bm == null) return null;
        int deg = new ExifInterface(f.getPath()).getRotationDegrees();
        float sc = Math.min(1f, (float) MAX_SIDE / Math.max(bm.getWidth(), bm.getHeight()));
        if (deg != 0 || sc < 1f) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            m.postScale(sc, sc);
            bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        }
        return bm;
    }

    private static void save(Bitmap bm, File f) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream os = new FileOutputStream(f)) {
            bm.compress(Bitmap.CompressFormat.JPEG, 95, os);
        }
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
