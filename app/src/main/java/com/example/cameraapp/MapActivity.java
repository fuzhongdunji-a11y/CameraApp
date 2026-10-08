package com.example.cameraapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cameraapp.cloud.CloudUploader;
import com.example.cameraapp.data.CellResult;
import com.example.cameraapp.data.ResultRepository;
import com.example.cameraapp.ui.DetailActivity;

import java.util.ArrayList;
import java.util.List;

import com.example.cameraapp.model.AbnormalRepository;

public class MapActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private Button btnFieldSettings;
    private Button btnOfflineImport;
    private Button btnCloudUpload;
    private boolean isUploading = false;

    private int rows = 3;
    private int cols = 5;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        recyclerView = findViewById(R.id.recyclerView);
        btnFieldSettings = findViewById(R.id.btnFieldSettings);
        btnOfflineImport = findViewById(R.id.btnOfflineImport);

        if (btnFieldSettings != null) {
            btnFieldSettings.setOnClickListener(v -> {
                startActivity(new Intent(this, FieldSettingActivity.class));
            });
        }

        if (btnOfflineImport != null) {
            btnOfflineImport.setOnClickListener(v -> {
                startActivity(new Intent(this, OfflineImportActivity.class));
            });
        }

        btnCloudUpload = findViewById(R.id.btnCloudUpload);
        if (btnCloudUpload != null) {
            btnCloudUpload.setOnClickListener(v -> uploadToCloud());
        }

        refreshData();
    }

    // 保存した写真と判定結果テキストを AWS(S3) にアップロードする
    private void uploadToCloud() {
        if (!CloudUploader.ensureInitialized(this)) {
            Toast.makeText(this, "クラウドの初期化に失敗しました", Toast.LENGTH_SHORT).show();
            return;
        }

        List<CellResult> records = ResultRepository.INSTANCE.getAll();
        btnCloudUpload.setEnabled(false);
        Toast.makeText(this, "アップロードを開始します", Toast.LENGTH_SHORT).show();

        isUploading = true;
        CloudUploader.uploadSavedResults(this, records, (success, failure) -> runOnUiThread(() -> {
            isUploading = false;
            btnCloudUpload.setEnabled(true);
            // アップロード済みの状態を保存し、次回のアップロード対象から除外する
            if (success > 0) {
                SharedPreferences prefs = getSharedPreferences("cell_results", MODE_PRIVATE);
                new Thread(() -> ResultRepository.INSTANCE.saveToDisk(prefs)).start();
            }
            String msg;
            if (success == 0 && failure == 0) {
                msg = "新しくアップロードする写真がありません";
            } else if (failure == 0) {
                msg = "クラウドに保存しました（" + success + "件）";
            } else {
                msg = "アップロード完了: 成功 " + success + "件 / 失敗 " + failure + "件";
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshData();
    }

    private void refreshData() {
        // 設定を読み込む
        SharedPreferences settingPrefs = getSharedPreferences("field_setting", MODE_PRIVATE);
        rows = settingPrefs.getInt("rows", 3);
        cols = settingPrefs.getInt("cols", 5);
        Log.d("MapActivity", "Config: rows=" + rows + ", cols=" + cols);

        // 判定結果を読み込む
        // アップロード中に読み込み直すとアップロード済みの状態が失われるため、読み込まない
        if (!isUploading) {
            SharedPreferences resultPrefs = getSharedPreferences("cell_results", MODE_PRIVATE);
            ResultRepository.INSTANCE.loadAll(resultPrefs);
        }
        Log.d("MapActivity", "Results loaded. Abnormal Count: " + AbnormalRepository.INSTANCE.getAll().size());

        setupRecyclerView();
    }

    private void setupRecyclerView() {
        // 列数が0以下の場合はデフォルト5にする
        int spanCount = cols > 0 ? cols : 5;
        recyclerView.setLayoutManager(new GridLayoutManager(this, spanCount));

        int total = rows * cols;
        List<FieldCell> cells = new ArrayList<>();
        // 1始まりのインデックスでセルを生成 (1..total)
        for (int i = 1; i <= total; i++) {
            FieldStatus status = ResultRepository.INSTANCE.getStatus(i);
            cells.add(new FieldCell(i, "ピーマン", status));
        }

        Log.d("MapActivity", "Setting adapter with " + cells.size() + " cells (1-based)");
        recyclerView.setAdapter(new FieldAdapter(cells, cell -> {
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra("cell_id", cell.getIndex());
            startActivity(intent);
        }));
    }
}
