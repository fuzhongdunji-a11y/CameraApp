package com.example.cameraapp.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.cameraapp.R;
import com.example.cameraapp.cloud.CloudBrowser;
import com.example.cameraapp.cloud.CloudUploader;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * バックナンバー画面。
 * クラウド(S3)にアップロードしたこれまでの写真と判定結果を、アップロード日時ごとに一覧表示する。
 * 項目を押すと写真と判定結果テキストを表示する。
 */
public class BackNumberActivity extends AppCompatActivity {

    // グラフの項目（正常・病気・異常あり）
    private static final int CAT_HEALTHY = 0;
    private static final int CAT_DISEASED = 1;
    private static final int CAT_ABNORMAL = 2;
    private static final String[] CAT_NAMES = {"正常", "病気", "異常あり"};
    private static final int[] CAT_COLORS = {
            Color.parseColor("#2A78D6"), Color.parseColor("#EB6834"), Color.parseColor("#4A3AA7")};

    private TextView statusText;
    private TextView chartTitle;
    private ResultChartView chartView;
    private int selectedCategory = CAT_DISEASED;
    private List<CloudBrowser.Capture> captures = new ArrayList<>();
    // 撮影日時を取得するための結果テキストの同時ダウンロード数
    private static final int DOWNLOAD_PARALLEL = 4;
    // 再読み込みのたびに増やし、前回の読み込みの結果を破棄するために使う
    private int loadGeneration = 0;
    private Button btnReload;
    private final BackNumberAdapter adapter = new BackNumberAdapter();
    // S3 の写真 URL（一時的な署名付き URL）のキャッシュ。UIスレッドでのみ操作
    private final Map<String, String> urlCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_back_number);

        statusText = findViewById(R.id.statusText);
        btnReload = findViewById(R.id.btnReload);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        chartTitle = findViewById(R.id.chartTitle);
        chartView = findViewById(R.id.chartView);
        RadioGroup categoryGroup = findViewById(R.id.categoryGroup);
        categoryGroup.check(R.id.radioDiseased);
        categoryGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioHealthy) selectedCategory = CAT_HEALTHY;
            else if (checkedId == R.id.radioAbnormal) selectedCategory = CAT_ABNORMAL;
            else selectedCategory = CAT_DISEASED;
            updateChart();
        });
        chartView.setEmptyText("読み込み中...");
        updateChart();

        btnReload.setOnClickListener(v -> load());
        load();
    }

    private void load() {
        if (!CloudUploader.ensureInitialized(this)) {
            statusText.setText("クラウドの初期化に失敗しました");
            return;
        }
        btnReload.setEnabled(false);
        statusText.setText("クラウドから読み込み中...");

        CloudBrowser.listCaptures(new CloudBrowser.Callback<List<CloudBrowser.Capture>>() {
            @Override
            public void onSuccess(List<CloudBrowser.Capture> captures) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    btnReload.setEnabled(true);
                    urlCache.clear();
                    BackNumberActivity.this.captures = captures;
                    adapter.setCaptures(captures);
                    chartView.setEmptyText("データがありません");
                    updateChart();
                    statusText.setText(captures.isEmpty()
                            ? "アップロードした写真はまだありません"
                            : captures.size() + "件の結果");
                    resolveShotTimes(captures);
                });
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    btnReload.setEnabled(true);
                    statusText.setText("読み込みに失敗しました: " + e.getMessage());
                });
            }
        });
    }

    /**
     * ファイル名に撮影日時がない以前のアップロード分は、結果テキストの「撮影日時」から取得する。
     * 同時に DOWNLOAD_PARALLEL 件ずつダウンロードし、取得するたびにグラフを更新する。
     */
    private void resolveShotTimes(List<CloudBrowser.Capture> list) {
        int generation = ++loadGeneration;
        List<CloudBrowser.Capture> queue = new ArrayList<>();
        for (CloudBrowser.Capture c : list) {
            if (c.shotAt == null && c.textKey != null) queue.add(c);
        }
        if (queue.isEmpty()) return;

        int[] next = {0};
        int[] done = {0};
        statusText.setText(list.size() + "件の結果（撮影日を取得中 0/" + queue.size() + "）");
        for (int i = 0; i < Math.min(DOWNLOAD_PARALLEL, queue.size()); i++) {
            resolveNext(generation, list, queue, next, done);
        }
    }

    /** キューから次の1件の結果テキストを読み、撮影日時を設定する（UIスレッドで呼ぶ） */
    private void resolveNext(int generation, List<CloudBrowser.Capture> list, List<CloudBrowser.Capture> queue,
                             int[] next, int[] done) {
        if (next[0] >= queue.size()) return;
        CloudBrowser.Capture c = queue.get(next[0]++);
        CloudBrowser.downloadText(this, c.textKey, new CloudBrowser.Callback<String>() {
            @Override
            public void onSuccess(String text) {
                Date shotAt = CloudBrowser.parseShotAt(text);
                runOnUiThread(() -> onResolved(shotAt));
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> onResolved(null));
            }

            private void onResolved(Date shotAt) {
                // 再読み込みで別の一覧になった場合や画面を閉じた場合は破棄する
                if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                c.shotAt = shotAt;
                done[0]++;
                updateChart();
                if (done[0] < queue.size()) {
                    statusText.setText(list.size() + "件の結果（撮影日を取得中 " + done[0] + "/" + queue.size() + "）");
                    resolveNext(generation, list, queue, next, done);
                } else {
                    statusText.setText(list.size() + "件の結果");
                    adapter.notifyDataSetChanged();
                }
            }
        });
    }

    /** 判定結果を項目（正常・病気・異常あり）に分類する */
    private static int categoryOf(String result) {
        if ("healthy".equalsIgnoreCase(result)) return CAT_HEALTHY;
        // 「異常あり」ボタンの記録（Manual_Capture）と「報告」ボタンの記録（Report）
        if (result != null && (result.startsWith("Manual") || result.equals("Report"))) return CAT_ABNORMAL;
        return CAT_DISEASED;
    }

    /**
     * 選択中の項目について、全区画の件数を撮影日ごとに集計してグラフに描画する。
     * 撮影日時が分からない記録（取得中・取得失敗）は集計に含めず、件数を見出しに表示する。
     */
    private void updateChart() {
        SimpleDateFormat key = new SimpleDateFormat("yyyy_MM_dd", Locale.getDefault());
        int total = 0;
        int unknown = 0;
        Map<String, Integer> counts = new HashMap<>();
        String first = null;
        String last = null;
        for (CloudBrowser.Capture c : captures) {
            if (categoryOf(c.result) != selectedCategory) continue;
            if (c.shotAt == null) {
                unknown++;
                continue;
            }
            String day = key.format(c.shotAt);
            Integer n = counts.get(day);
            counts.put(day, n == null ? 1 : n + 1);
            if (first == null || day.compareTo(first) < 0) first = day;
            if (last == null || day.compareTo(last) > 0) last = day;
            total++;
        }

        String title = CAT_NAMES[selectedCategory] + "の件数（合計 " + total + "件）";
        if (unknown > 0) title += "　撮影日不明 " + unknown + "件は除く";
        chartTitle.setText(title);
        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        if (first != null) {
            // 件数のない日も 0 件として並べ、日付の間隔を正しく表す
            SimpleDateFormat label = new SimpleDateFormat("MM/dd", Locale.getDefault());
            try {
                Calendar cal = Calendar.getInstance();
                cal.setTime(key.parse(first));
                Date end = key.parse(last);
                while (!cal.getTime().after(end)) {
                    String day = key.format(cal.getTime());
                    Integer n = counts.get(day);
                    labels.add(label.format(cal.getTime()));
                    values.add(n == null ? 0 : n);
                    cal.add(Calendar.DAY_OF_MONTH, 1);
                }
            } catch (ParseException e) {
                labels.clear();
                values.clear();
            }
        }
        chartView.setData(labels, values, CAT_COLORS[selectedCategory]);
    }

    /** S3 の写真を ImageView に表示する。表示までに別の項目へ再利用された場合は表示しない */
    private void loadImage(ImageView imageView, String key) {
        imageView.setTag(key);
        imageView.setImageDrawable(null);
        String cached = urlCache.get(key);
        if (cached != null) {
            Glide.with(this).load(cached).into(imageView);
            return;
        }
        CloudBrowser.getUrl(key, new CloudBrowser.Callback<String>() {
            @Override
            public void onSuccess(String url) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    urlCache.put(key, url);
                    if (key.equals(imageView.getTag())) Glide.with(BackNumberActivity.this).load(url).into(imageView);
                });
            }

            @Override
            public void onError(Exception e) {
                // 写真なしのまま表示する
            }
        });
    }

    private void showDetail(CloudBrowser.Capture c) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_back_number, null);
        ImageView imageView = view.findViewById(R.id.imageView);
        TextView textView = view.findViewById(R.id.textView);

        new AlertDialog.Builder(this)
                .setTitle(cellLabel(c) + "  " + c.result)
                .setView(view)
                .setPositiveButton("閉じる", null)
                .show();

        loadImage(imageView, c.imageKey);
        if (c.textKey == null) {
            textView.setText("判定結果テキストがありません");
            return;
        }
        textView.setText("判定結果を読み込み中...");
        CloudBrowser.downloadText(this, c.textKey, new CloudBrowser.Callback<String>() {
            @Override
            public void onSuccess(String text) {
                runOnUiThread(() -> textView.setText(text));
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> textView.setText("判定結果の読み込みに失敗しました: " + e.getMessage()));
            }
        });
    }

    private static String cellLabel(CloudBrowser.Capture c) {
        return c.cellIndex > 0 ? "区画 " + c.cellIndex : c.name;
    }

    /** フォルダ名（yyyy_MM_dd_HHmmss）を表示用の日時にする */
    private static String folderLabel(String folder) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault());
            SimpleDateFormat out = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault());
            return out.format(in.parse(folder)) + " アップロード";
        } catch (ParseException e) {
            return folder;
        }
    }

    /** アップロード日時の見出しと結果の項目を並べるアダプター */
    private class BackNumberAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_ITEM = 1;

        // String（見出しのフォルダ名）または CloudBrowser.Capture
        private final List<Object> rows = new ArrayList<>();

        void setCaptures(List<CloudBrowser.Capture> captures) {
            rows.clear();
            String folder = null;
            for (CloudBrowser.Capture c : captures) {
                if (!c.folder.equals(folder)) {
                    folder = c.folder;
                    rows.add(folder);
                }
                rows.add(c);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) {
                TextView tv = new TextView(parent.getContext());
                tv.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                int pad = (int) (8 * parent.getResources().getDisplayMetrics().density);
                tv.setPadding(pad, pad * 2, pad, pad);
                tv.setTextSize(15);
                tv.setTextColor(Color.DKGRAY);
                return new RecyclerView.ViewHolder(tv) {};
            }
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_back_number, parent, false);
            return new ItemHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object row = rows.get(position);
            if (row instanceof String) {
                ((TextView) holder.itemView).setText(folderLabel((String) row));
                return;
            }
            CloudBrowser.Capture c = (CloudBrowser.Capture) row;
            ItemHolder h = (ItemHolder) holder;
            h.cellText.setText(cellLabel(c));
            String detail = "判定: " + c.result;
            if (c.shotAt != null) {
                detail += "\n撮影: " + new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(c.shotAt);
            }
            h.resultText.setText(detail);
            loadImage(h.imageView, c.imageKey);
            h.itemView.setOnClickListener(v -> showDetail(c));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static class ItemHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;
        final TextView cellText;
        final TextView resultText;

        ItemHolder(View v) {
            super(v);
            imageView = v.findViewById(R.id.imageView);
            cellText = v.findViewById(R.id.cellText);
            resultText = v.findViewById(R.id.resultText);
        }
    }
}
