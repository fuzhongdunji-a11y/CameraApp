package com.example.cameraapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cameraapp.data.ResultRepository;
import com.example.cameraapp.ui.DetailActivity;

import java.util.ArrayList;
import java.util.List;

import com.example.cameraapp.model.AbnormalRepository;

public class MapActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private Button btnFieldSettings;
    private Button btnOfflineImport;

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

        refreshData();
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
        SharedPreferences resultPrefs = getSharedPreferences("cell_results", MODE_PRIVATE);
        ResultRepository.INSTANCE.loadAll(resultPrefs);
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
