package com.example.cameraapp.cloud;

import android.content.Context;
import android.util.Log;

import com.amplifyframework.core.Amplify;
import com.amplifyframework.storage.StorageItem;
import com.amplifyframework.storage.options.StoragePagedListOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S3 にアップロードした写真と判定結果テキストを参照するクラス。
 * キーは CloudUploader のアップロード時と同じ形式（public/captures/{日付}/Cell{区画}-{結果}.jpg / .txt）で扱う。
 */
public class CloudBrowser {

    private static final String TAG = "AmplifyGen2";
    private static final String S3_ROOT = "public/captures/";
    private static final String CAPTURES_DIR = "captures/";
    /** ファイル名に含める撮影日時の形式 */
    public static final String SHOT_AT_FORMAT = "yyyyMMdd_HHmmss";
    // Cell{区画}-{結果}[-{撮影日時}][_{連番}]（撮影日時はこの形式に変更する前のファイルにはない）
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^Cell(\\d+)-(.+?)(?:-(\\d{8}_\\d{6}))?(?:_(\\d+))?$");
    // 結果テキストの「撮影日時: yyyy-MM-dd HH:mm:ss」
    private static final Pattern TEXT_SHOT_AT_PATTERN =
            Pattern.compile("撮影日時:\\s*(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");

    /** アップロードした1件分（写真と結果テキストの組） */
    public static class Capture {
        /** アップロード日時のフォルダ名（yyyy_MM_dd_HHmmss） */
        public final String folder;
        /** Cell{区画}-{結果}-{撮影日時}（同名が複数ある場合は _2, _3 ...） */
        public final String name;
        public final int cellIndex;
        public final String result;
        /** 撮影日時（ファイル名または結果テキストから取得。不明な場合は null） */
        public Date shotAt;
        public String imageKey;
        public String textKey;

        Capture(String folder, String name) {
            this.folder = folder;
            this.name = name;
            Matcher m = NAME_PATTERN.matcher(name);
            if (m.matches()) {
                cellIndex = Integer.parseInt(m.group(1));
                result = m.group(2);
                shotAt = parse(m.group(3), SHOT_AT_FORMAT);
            } else {
                cellIndex = -1;
                result = name;
            }
        }
    }

    /** 結果テキストから撮影日時を取り出す（見つからなければ null） */
    public static Date parseShotAt(String text) {
        if (text == null) return null;
        Matcher m = TEXT_SHOT_AT_PATTERN.matcher(text);
        return m.find() ? parse(m.group(1), "yyyy-MM-dd HH:mm:ss") : null;
    }

    private static Date parse(String s, String format) {
        if (s == null) return null;
        try {
            return new SimpleDateFormat(format, Locale.getDefault()).parse(s);
        } catch (ParseException e) {
            return null;
        }
    }

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(Exception e);
    }

    /**
     * アップロード済みの写真の一覧を取得する（新しいアップロード順、同じアップロード内は区画順）。
     * 写真のない項目（接続テスト用のテキストなど）は除外する。
     * コールバックはバックグラウンドスレッドで呼ばれる。
     */
    public static void listCaptures(Callback<List<Capture>> callback) {
        listPage(null, new ArrayList<>(), callback);
    }

    private static void listPage(String nextToken, List<StorageItem> items, Callback<List<Capture>> callback) {
        StoragePagedListOptions options = nextToken == null
                ? StoragePagedListOptions.builder().setPageSize(1000).build()
                : StoragePagedListOptions.builder().setPageSize(1000).setNextToken(nextToken).build();
        try {
            Amplify.Storage.list(S3_ROOT, options,
                    result -> {
                        items.addAll(result.getItems());
                        if (result.getNextToken() != null) {
                            listPage(result.getNextToken(), items, callback);
                        } else {
                            callback.onSuccess(group(items));
                        }
                    },
                    error -> {
                        Log.e(TAG, "S3一覧の取得失敗", error);
                        callback.onError(error);
                    });
        } catch (Exception e) {
            Log.e(TAG, "S3一覧の取得失敗", e);
            callback.onError(e);
        }
    }

    /** 写真とテキストを「フォルダ/名前」ごとにまとめる */
    private static List<Capture> group(List<StorageItem> items) {
        Map<String, Capture> map = new LinkedHashMap<>();
        for (StorageItem item : items) {
            // 返されるキーにアクセスレベルの接頭辞が付く場合があるため、captures/ 以降からキーを組み立て直す
            String key = item.getKey();
            int idx = key.indexOf(CAPTURES_DIR);
            if (idx < 0) continue;
            String rest = key.substring(idx + CAPTURES_DIR.length());
            int slash = rest.indexOf('/');
            int dot = rest.lastIndexOf('.');
            if (slash <= 0 || dot <= slash) continue;

            String folder = rest.substring(0, slash);
            String name = rest.substring(slash + 1, dot);
            String ext = rest.substring(dot + 1).toLowerCase();
            String fullKey = S3_ROOT + rest;

            Capture c = map.get(folder + "/" + name);
            if (c == null) {
                c = new Capture(folder, name);
                map.put(folder + "/" + name, c);
            }
            if (ext.equals("jpg")) c.imageKey = fullKey;
            else if (ext.equals("txt")) c.textKey = fullKey;
        }

        List<Capture> list = new ArrayList<>();
        for (Capture c : map.values()) {
            if (c.imageKey != null) list.add(c);
        }
        Collections.sort(list, (a, b) -> {
            int cmp = b.folder.compareTo(a.folder);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.cellIndex, b.cellIndex);
            if (cmp != 0) return cmp;
            return a.name.compareTo(b.name);
        });
        return list;
    }

    /** 写真を表示するための一時的な URL を取得する（バックグラウンドスレッドで呼ばれる） */
    public static void getUrl(String key, Callback<String> callback) {
        try {
            Amplify.Storage.getUrl(key,
                    result -> callback.onSuccess(result.getUrl().toString()),
                    error -> {
                        Log.e(TAG, "URLの取得失敗: " + key, error);
                        callback.onError(error);
                    });
        } catch (Exception e) {
            Log.e(TAG, "URLの取得失敗: " + key, e);
            callback.onError(e);
        }
    }

    /**
     * 判定結果テキストをダウンロードして文字列で返す（バックグラウンドスレッドで呼ばれる）。
     * アップロード済みのテキストは変わらないため、一度ダウンロードしたものは端末のキャッシュから読む。
     */
    public static void downloadText(Context context, String key, Callback<String> callback) {
        File dir = new File(context.getCacheDir(), "back_number");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, key.replaceAll("[\\\\/:*?\"<>|]", "_"));
        if (file.length() > 0) {
            try {
                callback.onSuccess(readText(file));
                return;
            } catch (IOException e) {
                Log.w(TAG, "キャッシュの読み込み失敗。再ダウンロードします: " + key, e);
            }
        }
        try {
            Amplify.Storage.downloadFile(key, file,
                    result -> {
                        try {
                            callback.onSuccess(readText(result.getFile()));
                        } catch (IOException e) {
                            callback.onError(e);
                        }
                    },
                    error -> {
                        Log.e(TAG, "テキストのダウンロード失敗: " + key, error);
                        callback.onError(error);
                    });
        } catch (Exception e) {
            Log.e(TAG, "テキストのダウンロード失敗: " + key, e);
            callback.onError(e);
        }
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
