package com.example.cameraapp.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cameraapp.FieldAdapter;
import com.example.cameraapp.FieldCell;
import com.example.cameraapp.FieldStatus;
import com.example.cameraapp.R;
import com.example.cameraapp.cloud.CloudBrowser;
import com.example.cameraapp.data.CellResult;
import com.example.cameraapp.data.ResultRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * バックナンバーのカレンダーで選んだ日の圃場マップ。
 * その日に撮影した S3 の記録を、端末のマップと同じ判定（色・平均・スコア帯の集計）で表示する。
 * 区画を押すと、その日のその区画の写真を表示する。
 */
public class BackNumberMapActivity extends AppCompatActivity {

    // 確信度を取得するための結果テキストの同時ダウンロード数
    private static final int DOWNLOAD_PARALLEL = 4;

    private TextView statusText;
    private RecyclerView recyclerView;
    private int rows = 3;
    private int cols = 5;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_back_number_map);

        if (BackNumberDay.date == null) {
            finish();
            return;
        }

        ((TextView) findViewById(R.id.titleText)).setText(BackNumberDay.date + " のマップ");
        statusText = findViewById(R.id.statusText);
        recyclerView = findViewById(R.id.recyclerView);

        SharedPreferences settingPrefs = getSharedPreferences("field_setting", MODE_PRIVATE);
        rows = settingPrefs.getInt("rows", 3);
        cols = settingPrefs.getInt("cols", 5);
        if (cols <= 0) cols = 5;

        loadConfidences(BackNumberDay.captures);
    }

    /**
     * 端末のマップと同じ判定にするため、各記録の確信度を結果テキストから取得してから集計する
     * （一度ダウンロードしたテキストは端末のキャッシュから読む）
     */
    private void loadConfidences(List<CloudBrowser.Capture> captures) {
        List<Float> confidences = new ArrayList<>();
        for (int i = 0; i < captures.size(); i++) confidences.add(null);
        if (captures.isEmpty()) {
            showMap(captures, confidences);
            return;
        }

        int[] next = {0};
        int[] done = {0};
        statusText.setText("判定結果を読み込み中 0/" + captures.size());
        Runnable[] startNext = new Runnable[1];
        startNext[0] = () -> {
            if (next[0] >= captures.size()) return;
            int i = next[0]++;
            CloudBrowser.Capture c = captures.get(i);
            if (c.textKey == null) {
                onLoaded(captures, confidences, done, startNext[0]);
                return;
            }
            CloudBrowser.downloadText(this, c.textKey, new CloudBrowser.Callback<String>() {
                @Override
                public void onSuccess(String text) {
                    Float conf = CloudBrowser.parseConfidence(text);
                    runOnUiThread(() -> {
                        confidences.set(i, conf);
                        onLoaded(captures, confidences, done, startNext[0]);
                    });
                }

                @Override
                public void onError(Exception e) {
                    runOnUiThread(() -> onLoaded(captures, confidences, done, startNext[0]));
                }
            });
        };
        for (int k = 0; k < Math.min(DOWNLOAD_PARALLEL, captures.size()); k++) startNext[0].run();
    }

    private void onLoaded(List<CloudBrowser.Capture> captures, List<Float> confidences, int[] done, Runnable startNext) {
        if (isFinishing() || isDestroyed()) return;
        done[0]++;
        if (done[0] < captures.size()) {
            statusText.setText("判定結果を読み込み中 " + done[0] + "/" + captures.size());
            startNext.run();
        } else {
            showMap(captures, confidences);
        }
    }

    private void showMap(List<CloudBrowser.Capture> captures, List<Float> confidences) {
        // S3 の記録を端末の記録と同じ形にして、一時的なリポジトリで集計する
        ResultRepository repository = ResultRepository.newTemporary();
        int maxCell = 0;
        for (int i = 0; i < captures.size(); i++) {
            CloudBrowser.Capture c = captures.get(i);
            if (c.cellIndex <= 0 || c.shotAt == null) continue;
            int zero = c.cellIndex - 1;
            CellResult r = new CellResult(c.cellIndex, zero / cols, zero % cols, c.result,
                    c.shotAt.getTime(), c.imageKey, 0.0, 0.0, 0f);
            Float conf = confidences.get(i);
            if (conf != null) r.setConfidence(conf);
            repository.add(r);
            maxCell = Math.max(maxCell, c.cellIndex);
        }
        BackNumberDay.repository = repository;

        // 現在の圃場設定より大きい区画番号の記録がある場合は、その区画まで表示する
        int total = Math.max(rows * cols, ((maxCell + cols - 1) / cols) * cols);
        recyclerView.setLayoutManager(new GridLayoutManager(this, cols));
        List<FieldCell> cells = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            FieldStatus status = repository.getStatus(i);
            cells.add(new FieldCell(i, "ピーマン", status));
        }
        recyclerView.setAdapter(new FieldAdapter(cells, repository, cell -> {
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra("cell_id", cell.getIndex());
            intent.putExtra(DetailActivity.EXTRA_BACK_NUMBER, true);
            startActivity(intent);
        }));
        statusText.setText(captures.size() + "件の記録（区画を押すとその日の写真を表示）");
    }
}
