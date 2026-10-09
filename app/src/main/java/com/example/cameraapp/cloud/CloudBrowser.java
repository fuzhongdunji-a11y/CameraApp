package com.example.cameraapp.cloud;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.amplifyframework.AmplifyException;
import com.amplifyframework.core.Amplify;
import com.amplifyframework.storage.StoragePath;
import com.amplifyframework.storage.StorageItem;
import com.amplifyframework.storage.options.StoragePagedListOptions;
import com.amplifyframework.storage.result.StorageListResult;

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
    // 一覧を取得する範囲（Gen2 のストレージ設定で guest に get / list が許可されているパス）
    private static final String LIST_ROOT = "public/";
    // 1回の一覧取得の件数（応答を小さくし、通信が切れた場合の再試行を軽くする）
    private static final int LIST_PAGE_SIZE = 200;
    // 通信が途中で切れた場合の試行回数と、再試行までの待ち時間（回数に比例して延ばす）
    private static final int MAX_ATTEMPTS = 4;
    private static final long RETRY_DELAY_MS = 700;
    private static final Handler RETRY_HANDLER = new Handler(Looper.getMainLooper());
    private static final String CAPTURES_DIR = "captures/";
    /** ファイル名に含める撮影日時の形式 */
    public static final String SHOT_AT_FORMAT = "yyyyMMdd_HHmmss";
    // Cell{区画}-{結果}[-{撮影日時}][_{連番}]（撮影日時はこの形式に変更する前のファイルにはない）
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^Cell(\\d+)-(.+?)(?:-(\\d{8}_\\d{6}))?(?:_(\\d+))?$");
    // 結果テキストの「撮影日時: yyyy-MM-dd HH:mm:ss」
    private static final Pattern TEXT_SHOT_AT_PATTERN =
            Pattern.compile("撮影日時:\\s*(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    // 結果テキストの「確信度(Confidence): 85.00%」
    private static final Pattern TEXT_CONFIDENCE_PATTERN =
            Pattern.compile("確信度\\(Confidence\\):\\s*([0-9]+(?:[.,][0-9]+)?)");

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

    /** 結果テキストから確信度（0-100）を取り出す（見つからなければ null） */
    public static Float parseConfidence(String text) {
        if (text == null) return null;
        Matcher m = TEXT_CONFIDENCE_PATTERN.matcher(text);
        if (!m.find()) return null;
        try {
            return Float.parseFloat(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 写真のキーから、同じ名前の結果テキストのキーを求める */
    public static String textKeyFor(String imageKey) {
        if (imageKey == null || !imageKey.endsWith(".jpg")) return null;
        return imageKey.substring(0, imageKey.length() - 4) + ".txt";
    }

    /** CloudUploader でアップロードした S3 のキーか（端末内のファイルパスと区別する） */
    public static boolean isCloudKey(String path) {
        return path != null && path.startsWith(LIST_ROOT);
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
                ? StoragePagedListOptions.builder().setPageSize(LIST_PAGE_SIZE).build()
                : StoragePagedListOptions.builder().setPageSize(LIST_PAGE_SIZE).setNextToken(nextToken).build();
        withRetry("S3一覧の取得", (Callback<StorageListResult> cb) ->
                // Gen2 のパス指定 API で public/ 以下を一覧する。
                // キー指定の API でアップロードした写真は、アクセスレベルの接頭辞が付いて
                // public/public/captures/... に保存されている場合があるため、public/ 全体から captures/ を探す
                Amplify.Storage.list(StoragePath.fromString(LIST_ROOT), options, cb::onSuccess, cb::onError),
                new Callback<StorageListResult>() {
                    @Override
                    public void onSuccess(StorageListResult result) {
                        items.addAll(result.getItems());
                        if (result.getNextToken() != null) {
                            listPage(result.getNextToken(), items, callback);
                        } else {
                            callback.onSuccess(group(items));
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        callback.onError(e);
                    }
                });
    }

    /** 1回分の S3 の操作。結果を callback に返す */
    private interface Attempt<T> {
        void run(Callback<T> callback);
    }

    /**
     * S3 の操作を実行し、通信が途中で切れた場合（unexpected end of stream など）は少し待って再試行する。
     * 端末の HTTP クライアントが、S3 側で閉じられた接続を再利用すると発生するため。
     */
    private static <T> void withRetry(String label, Attempt<T> attempt, Callback<T> callback) {
        runAttempt(label, attempt, callback, 1);
    }

    private static <T> void runAttempt(String label, Attempt<T> attempt, Callback<T> callback, int count) {
        Callback<T> onResult = new Callback<T>() {
            @Override
            public void onSuccess(T value) {
                callback.onSuccess(value);
            }

            @Override
            public void onError(Exception e) {
                if (count < MAX_ATTEMPTS && isConnectionError(e)) {
                    Log.w(TAG, label + ": 通信が切れたため再試行します（" + count + "/" + MAX_ATTEMPTS + "）", e);
                    RETRY_HANDLER.postDelayed(() -> runAttempt(label, attempt, callback, count + 1),
                            RETRY_DELAY_MS * count);
                } else {
                    Log.e(TAG, label + "失敗", e);
                    callback.onError(e);
                }
            }
        };
        try {
            attempt.run(onResult);
        } catch (Exception e) {
            onResult.onError(e);
        }
    }

    /** 通信が途中で切れたエラーか（原因をたどって IOException・接続切断を探す） */
    private static boolean isConnectionError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof IOException) return true;
            String msg = t.getMessage();
            if (msg != null && (msg.contains("CONNECTION_CLOSED") || msg.contains("unexpected end of stream")
                    || msg.contains("IOException"))) {
                return true;
            }
        }
        return false;
    }

    /** 写真とテキストを「フォルダ/名前」ごとにまとめる */
    private static List<Capture> group(List<StorageItem> items) {
        Map<String, Capture> map = new LinkedHashMap<>();
        for (StorageItem item : items) {
            // S3 上の実際のパス（写真の表示・ダウンロードにもこのパスを使う）
            String key = item.getPath();
            int idx = key.indexOf(CAPTURES_DIR);
            if (idx < 0) continue;
            String rest = key.substring(idx + CAPTURES_DIR.length());
            int slash = rest.indexOf('/');
            int dot = rest.lastIndexOf('.');
            if (slash <= 0 || dot <= slash) continue;

            String folder = rest.substring(0, slash);
            String name = rest.substring(slash + 1, dot);
            String ext = rest.substring(dot + 1).toLowerCase();
            String fullKey = key;

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
        withRetry("URLの取得: " + key, (Callback<String> cb) ->
                Amplify.Storage.getUrl(StoragePath.fromString(key),
                        result -> cb.onSuccess(result.getUrl().toString()), cb::onError),
                callback);
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
        withRetry("テキストのダウンロード: " + key, (Callback<String> cb) ->
                Amplify.Storage.downloadFile(StoragePath.fromString(key), file,
                        result -> {
                            try {
                                cb.onSuccess(readText(result.getFile()));
                            } catch (IOException e) {
                                cb.onError(e);
                            }
                        },
                        error -> {
                            // 途中まで書き込まれたファイルをキャッシュとして使わないように削除する
                            file.delete();
                            cb.onError(error);
                        }),
                callback);
    }

    /** エラーの内容を、原因・対処方法まで含めて画面に表示できる文字列にする */
    public static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        for (int depth = 0; t != null && depth < 4; depth++, t = t.getCause()) {
            if (sb.length() > 0) sb.append("\n原因: ");
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
            if (t instanceof AmplifyException) {
                String suggestion = ((AmplifyException) t).getRecoverySuggestion();
                if (suggestion != null) sb.append("\n対処: ").append(suggestion);
            }
            if (t.getCause() == t) break;
        }
        return sb.toString();
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
