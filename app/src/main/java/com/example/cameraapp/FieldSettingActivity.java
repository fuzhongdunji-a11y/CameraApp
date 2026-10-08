package com.example.cameraapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class FieldSettingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_field_setting);

        EditText etRows = findViewById(R.id.etRows);
        EditText etCols = findViewById(R.id.etCols);
        EditText etWidth = findViewById(R.id.etWidth);
        Button btnStart = findViewById(R.id.btnStart);
        Button btnAddData = findViewById(R.id.btnAddData);

        if (etRows == null || etCols == null || etWidth == null) {
            Toast.makeText(this, "Layout Error", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 保存された値を読み込んでセット
        SharedPreferences prefs = getSharedPreferences("field_setting", MODE_PRIVATE);
        int savedRows = prefs.getInt("rows", 3);
        int savedCols = prefs.getInt("cols", 5);
        float savedWidth = prefs.getFloat("width", 10.0f);

        etRows.setText(String.valueOf(savedRows));
        etCols.setText(String.valueOf(savedCols));
        etWidth.setText(String.valueOf(savedWidth));

        if (btnStart != null) {
            btnStart.setOnClickListener(v -> {
                if (saveSettings(etRows, etCols, etWidth)) {
                    startActivity(new Intent(this, MapActivity.class));
                    finish();
                }
            });
        }

        if (btnAddData != null) {
            btnAddData.setOnClickListener(v -> {
                if (saveSettings(etRows, etCols, etWidth)) {
                    Intent intent = new Intent(this, com.example.cameraapp.MainActivity.class);
                    intent.putExtra("plant_type", "ピーマン");
                    intent.putExtra("work_mode", "manual");
                    intent.putExtra("cell_index", 1);
                    startActivity(intent);
                    finish();
                }
            });
        }
    }

    private boolean saveSettings(EditText etRows, EditText etCols, EditText etWidth) {
        String rowsStr = etRows.getText().toString();
        String colsStr = etCols.getText().toString();
        String widthStr = etWidth.getText().toString();

        try {
            int rows = Integer.parseInt(rowsStr);
            int cols = Integer.parseInt(colsStr);
            float width = Float.parseFloat(widthStr);

            if (rows <= 0 || cols <= 0 || width <= 0f) {
                Toast.makeText(this, "正の値を入力してください", Toast.LENGTH_SHORT).show();
                return false;
            }

            SharedPreferences prefs = getSharedPreferences("field_setting", MODE_PRIVATE);
            prefs.edit()
                    .putInt("rows", rows)
                    .putInt("cols", cols)
                    .putFloat("width", width)
                    .apply();
            return true;
        } catch (NumberFormatException e) {
            Toast.makeText(this, "数値を正しく入力してください", Toast.LENGTH_SHORT).show();
            return false;
        }
    }
}
