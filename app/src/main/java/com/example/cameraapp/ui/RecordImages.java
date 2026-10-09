package com.example.cameraapp.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.bumptech.glide.Glide;
import com.example.cameraapp.R;
import com.example.cameraapp.cloud.CloudBrowser;

import java.util.HashMap;
import java.util.Map;

/**
 * 記録の写真を表示する。端末内のファイルパスはそのまま読み込み、
 * S3 のキー（バックナンバーの記録）は一時 URL を取得して表示する。
 */
public final class RecordImages {

    // S3 の一時 URL は期限があるため、10分で取り直す
    private static final long URL_TTL_MS = 10 * 60 * 1000;
    private static final Map<String, String> urlCache = new HashMap<>();
    private static final Map<String, Long> urlTime = new HashMap<>();

    private RecordImages() {}

    /** 写真を ImageView に表示する。表示までに別の記録へ再利用された場合は表示しない */
    public static void load(ImageView imageView, String path) {
        imageView.setTag(R.id.imageView, path);
        if (path == null) return;

        if (!CloudBrowser.isCloudKey(path)) {
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(path);
                imageView.setImageBitmap(bitmap);
            } catch (Exception e) {
                imageView.setImageResource(R.drawable.ic_launcher_background);
            }
            return;
        }

        imageView.setImageDrawable(null);
        String cached = cachedUrl(path);
        if (cached != null) {
            show(imageView, path, cached);
            return;
        }
        CloudBrowser.getUrl(path, new CloudBrowser.Callback<String>() {
            @Override
            public void onSuccess(String url) {
                imageView.post(() -> {
                    synchronized (urlCache) {
                        urlCache.put(path, url);
                        urlTime.put(path, SystemClock.elapsedRealtime());
                    }
                    show(imageView, path, url);
                });
            }

            @Override
            public void onError(Exception e) {
                imageView.post(() -> {
                    if (path.equals(imageView.getTag(R.id.imageView))) {
                        imageView.setImageResource(R.drawable.ic_launcher_background);
                    }
                });
            }
        });
    }

    private static String cachedUrl(String key) {
        synchronized (urlCache) {
            Long t = urlTime.get(key);
            if (t == null || SystemClock.elapsedRealtime() - t > URL_TTL_MS) return null;
            return urlCache.get(key);
        }
    }

    private static void show(ImageView imageView, String path, String url) {
        if (!path.equals(imageView.getTag(R.id.imageView))) return;
        Activity activity = findActivity(imageView.getContext());
        if (activity != null && (activity.isFinishing() || activity.isDestroyed())) return;
        Glide.with(imageView).load(url).into(imageView);
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** S3 の記録の写真を拡大し、同じ名前の判定結果テキストと一緒にダイアログで表示する */
    public static void showCloudDetail(Activity activity, String title, String imageKey) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_back_number, null);
        ImageView imageView = view.findViewById(R.id.imageView);
        TextView textView = view.findViewById(R.id.textView);

        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(view)
                .setPositiveButton("閉じる", null)
                .show();

        load(imageView, imageKey);
        String textKey = CloudBrowser.textKeyFor(imageKey);
        if (textKey == null) {
            textView.setText("判定結果テキストがありません");
            return;
        }
        textView.setText("判定結果を読み込み中...");
        CloudBrowser.downloadText(activity, textKey, new CloudBrowser.Callback<String>() {
            @Override
            public void onSuccess(String text) {
                textView.post(() -> textView.setText(text));
            }

            @Override
            public void onError(Exception e) {
                textView.post(() -> textView.setText("判定結果の読み込みに失敗しました\n" + CloudBrowser.describe(e)));
            }
        });
    }
}
