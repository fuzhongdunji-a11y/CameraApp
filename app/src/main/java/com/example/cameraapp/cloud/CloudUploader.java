package com.example.cameraapp.cloud;

import android.content.Context;
import android.util.Log;

import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin;
import com.amplifyframework.core.Amplify;
import com.amplifyframework.core.configuration.AmplifyOutputs;
import com.amplifyframework.storage.s3.AWSS3StoragePlugin;
import com.example.cameraapp.R;
import com.example.cameraapp.data.CellResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Amplify の初期化と S3 へのアップロードをまとめたクラス
 */
public class CloudUploader {

    private static final String TAG = "AmplifyGen2";
    private static final String S3_ROOT = "public/captures/";

    private static boolean initialized = false;

    public interface Callback {
        void onComplete(int success, int failure);
    }

    /** Amplify は1回しか configure できないため、初回のみ初期化する */
    public static synchronized boolean ensureInitialized(Context context) {
        if (initialized) return true;
        try {
            Amplify.addPlugin(new AWSCognitoAuthPlugin());
            Amplify.addPlugin(new AWSS3StoragePlugin());
            Amplify.configure(AmplifyOutputs.fromResource(R.raw.amplify_outputs), context.getApplicationContext());
            Log.i(TAG, "Amplifyの初期化に成功しました！");
            initialized = true;
        } catch (Exception error) {
            Log.e(TAG, "初期化エラー", error);
        }
        return initialized;
    }

    /**
     * 保存した写真と判定結果テキストを S3 にアップロードする。
     * 前回までにアップロード済みの写真は除外する。
     * 保存先: public/captures/{アップロード日時}/Cell{区画}-{結果}-{撮影日時 yyyyMMdd_HHmmss}.jpg / .txt
     * 同じ名前が複数ある場合は _2, _3 ... を付ける。
     * 写真とテキストの両方が成功した記録のみアップロード済みにする（件数は写真単位）。
     */
    public static void uploadSavedResults(Context context, List<CellResult> records, Callback callback) {
        List<CellResult> targets = new ArrayList<>();
        for (CellResult r : records) {
            if (r.isUploaded() || r.getImagePath() == null) continue;
            if (new File(r.getImagePath()).exists()) targets.add(r);
        }
        if (targets.isEmpty()) {
            callback.onComplete(0, 0);
            return;
        }

        String folder = new SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault()).format(new Date());
        File tempDir = new File(context.getFilesDir(), "upload/" + folder);
        if (!tempDir.exists()) tempDir.mkdirs();

        AtomicInteger remaining = new AtomicInteger(targets.size());
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        Map<String, Integer> nameCount = new HashMap<>();

        for (CellResult r : targets) {
            // 撮影日時をファイル名に含め、バックナンバー画面で撮影日ごとに集計できるようにする
            String shotAt = new SimpleDateFormat(CloudBrowser.SHOT_AT_FORMAT, Locale.getDefault()).format(new Date(r.getTimestamp()));
            String baseName = "Cell" + r.getIndex() + "-" + sanitize(r.getResult()) + "-" + shotAt;
            int n = nameCount.getOrDefault(baseName, 0) + 1;
            nameCount.put(baseName, n);
            if (n > 1) baseName += "_" + n;

            String prefix = S3_ROOT + folder + "/" + baseName;
            File image = new File(r.getImagePath());
            File txt = writeResultText(tempDir, baseName, r, baseName + ".jpg");

            // 写真とテキストの2ファイルが終わった時点で、この記録の成否を確定する
            AtomicInteger filesLeft = new AtomicInteger(2);
            AtomicBoolean allOk = new AtomicBoolean(true);
            ResultListener onFileDone = ok -> {
                if (!ok) allOk.set(false);
                if (filesLeft.decrementAndGet() > 0) return;
                if (allOk.get()) {
                    r.setUploaded(true);
                    success.incrementAndGet();
                } else {
                    failure.incrementAndGet();
                }
                if (remaining.decrementAndGet() == 0) callback.onComplete(success.get(), failure.get());
            };

            uploadFile(image, prefix + ".jpg", onFileDone);
            uploadFile(txt, prefix + ".txt", onFileDone);
        }
    }

    private static File writeResultText(File dir, String baseName, CellResult r, String imageFileName) {
        String content = "======== 判定結果レポート ========\n" +
                "撮影日時: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(r.getTimestamp())) + "\n" +
                "区画(Cell): " + r.getIndex() + "\n" +
                "判定結果(異常/病気/状態): " + r.getResult() + "\n" +
                "確信度(Confidence): " + String.format(Locale.getDefault(), "%.2f%%", r.getConfidence()) + "\n" +
                "関連画像ファイル: " + imageFileName + "\n" +
                "==================================\n";

        File txtFile = new File(dir, baseName + ".txt");
        try (FileOutputStream fos = new FileOutputStream(txtFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "結果テキストファイルの生成失敗", e);
            return null;
        }
        return txtFile;
    }

    private interface ResultListener {
        void onDone(boolean ok);
    }

    /** ファイルを指定して S3 にアップロードする */
    public static void uploadFile(File file, String s3Key) {
        uploadFile(file, s3Key, ok -> {});
    }

    private static void uploadFile(File file, String s3Key, ResultListener listener) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "アップロード失敗: ファイルが存在しません");
            listener.onDone(false);
            return;
        }

        Log.i(TAG, "ファイルアップロード開始: " + s3Key);
        try {
            Amplify.Storage.uploadFile(
                    s3Key,
                    file,
                    result -> {
                        Log.i(TAG, "S3アップロード成功！キー: " + result.getKey());
                        listener.onDone(true);
                    },
                    error -> {
                        Log.e(TAG, "S3アップロード失敗", error);
                        listener.onDone(false);
                    }
            );
        } catch (Exception e) {
            Log.e(TAG, "S3アップロード失敗", e);
            listener.onDone(false);
        }
    }

    /** S3 のキーに使えない文字を置き換える */
    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "unknown";
        return s.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }
}
