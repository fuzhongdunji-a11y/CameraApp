package com.example.cameraapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.cameraapp.data.ResultRepository;

public class StartActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // アプリ起動時に保存された解析結果をロードする
        SharedPreferences prefs = getSharedPreferences("cell_results", MODE_PRIVATE);
        ResultRepository.INSTANCE.loadAll(prefs);

        Log.d("StartActivity", "Loaded results: " + ResultRepository.INSTANCE.getAll().size() + " records");

        setContentView(R.layout.activity_start);

        Button btnStart = findViewById(R.id.btnStart);
        Button btnOffline = findViewById(R.id.btnOfflineImport);
        Button btnSettings = findViewById(R.id.btnFieldSettings);

        // ボタンが見つからない場合のフォールバック
        if (btnStart == null && btnOffline == null && btnSettings == null) {
            startWork();
            return;
        }

        if (btnStart != null) {
            btnStart.setOnClickListener(v -> startWork());
        }
        if (btnOffline != null) {
            btnOffline.setOnClickListener(v -> {
                startActivity(new Intent(this, OfflineImportActivity.class));
            });
        }
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> {
                startActivity(new Intent(this, FieldSettingActivity.class));
            });
        }
    }

    private void startWork() {
        startActivity(new Intent(this, MapActivity.class));
        finish();
    }
}
