package com.example.cameraapp.ui;

import android.graphics.Color;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.cameraapp.R;
import com.example.cameraapp.cloud.CloudBrowser;
import com.example.cameraapp.cloud.CloudUploader;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * バックナンバー画面。
 * クラウド(S3)にアップロードしたこれまでの判定結果を、上半分に項目ごとの撮影日別件数グラフ、
 * 下半分に撮影日のカレンダーで表示する。カレンダーの日付を押すとその日のマップを開く。
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
    private MonthCalendarView calendarView;
    // カレンダーに最新の撮影日の月を表示済みか
    private boolean calendarMonthChosen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_back_number);

        statusText = findViewById(R.id.statusText);
        btnReload = findViewById(R.id.btnReload);
        calendarView = findViewById(R.id.calendarView);
        calendarView.setOnDateClickListener(this::openDay);

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
                    BackNumberActivity.this.captures = captures;
                    chartView.setEmptyText("データがありません");
                    updateChart();
                    updateCalendar();
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
                    statusText.setText("読み込みに失敗しました\n" + CloudBrowser.describe(e));
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
                updateCalendar();
                if (done[0] < queue.size()) {
                    statusText.setText(list.size() + "件の結果（撮影日を取得中 " + done[0] + "/" + queue.size() + "）");
                    resolveNext(generation, list, queue, next, done);
                } else {
                    statusText.setText(list.size() + "件の結果");
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

    /** 撮影日ごとの件数をカレンダーに表示する（全項目の合計。病気・異常ありを含む日は赤） */
    private void updateCalendar() {
        SimpleDateFormat key = new SimpleDateFormat("yyyy_MM_dd", Locale.getDefault());
        Map<String, MonthCalendarView.DayMark> marks = new HashMap<>();
        Date latest = null;
        for (CloudBrowser.Capture c : captures) {
            if (c.shotAt == null) continue;
            String day = key.format(c.shotAt);
            MonthCalendarView.DayMark mark = marks.get(day);
            if (mark == null) {
                mark = new MonthCalendarView.DayMark();
                marks.put(day, mark);
            }
            mark.count++;
            if (categoryOf(c.result) != CAT_HEALTHY) mark.problem = true;
            if (latest == null || c.shotAt.after(latest)) latest = c.shotAt;
        }
        calendarView.setMarks(marks);

        // 最初に記録が見つかったときだけ、最新の撮影日の月を表示する（その後はユーザーの操作を優先）
        if (!calendarMonthChosen && latest != null) {
            calendarMonthChosen = true;
            Calendar cal = Calendar.getInstance();
            cal.setTime(latest);
            calendarView.showMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH));
        }
    }

    /** 選んだ撮影日のマップを開く */
    private void openDay(String day) {
        SimpleDateFormat key = new SimpleDateFormat("yyyy_MM_dd", Locale.getDefault());
        List<CloudBrowser.Capture> list = new ArrayList<>();
        for (CloudBrowser.Capture c : captures) {
            if (c.shotAt != null && key.format(c.shotAt).equals(day)) list.add(c);
        }
        if (list.isEmpty()) {
            Toast.makeText(this, "この日の記録はありません", Toast.LENGTH_SHORT).show();
            return;
        }
        // 写真は撮影順に並べる
        Collections.sort(list, (a, b) -> a.shotAt.compareTo(b.shotAt));
        BackNumberDay.select(day.replace('_', '/'), list);
        startActivity(new Intent(this, BackNumberMapActivity.class));
    }
}
