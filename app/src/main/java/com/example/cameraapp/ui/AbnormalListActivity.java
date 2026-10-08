package com.example.cameraapp.ui;

import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.cameraapp.R;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AbnormalListActivity extends AppCompatActivity {

    private ListView listView;
    private TextView txtPath;
    private ImageView imgPreview;
    private File currentDir;
    private List<File> filesInDir = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_abnormal_list);

        listView = findViewById(R.id.listView);
        txtPath = findViewById(R.id.txtPath);
        imgPreview = findViewById(R.id.imgPreview);

        // ルートディレクトリ: categories (ここに Label/Confidence/Date 構造で保存されている)
        File baseDir = new File(getExternalFilesDir(null), "categories");
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
        
        currentDir = baseDir;
        updateList();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            File selected = filesInDir.get(position);
            if (selected.isDirectory()) {
                currentDir = selected;
                updateList();
            } else {
                // 画像を表示
                try {
                    imgPreview.setImageBitmap(BitmapFactory.decodeFile(selected.getAbsolutePath()));
                    imgPreview.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "表示: " + selected.getName(), Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "画像読み込み失敗", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void updateList() {
        // 表示パスの調整
        File baseDir = new File(getExternalFilesDir(null), "categories");
        String relativePath = currentDir.getAbsolutePath().replace(baseDir.getAbsolutePath(), "");
        txtPath.setText("場所: categories" + (relativePath.isEmpty() ? "/" : relativePath));
        
        imgPreview.setVisibility(View.GONE);
        
        File[] files = currentDir.listFiles();
        filesInDir.clear();
        List<String> names = new ArrayList<>();
        
        if (files != null) {
            for (File f : files) {
                filesInDir.add(f);
                String prefix = f.isDirectory() ? "📁 " : "🖼️ ";
                names.add(prefix + f.getName());
            }
        }
        
        if (filesInDir.isEmpty()) {
            names.add("(空のフォルダ)");
        }
        
        Collections.sort(names);
        listView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
    }

    @Override
    public void onBackPressed() {
        File baseDir = new File(getExternalFilesDir(null), "categories");
        // ルートより上には戻らない
        if (currentDir == null || currentDir.equals(baseDir)) {
            super.onBackPressed();
        } else {
            currentDir = currentDir.getParentFile();
            updateList();
        }
    }
}
