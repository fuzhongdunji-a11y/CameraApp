package com.example.cameraapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.example.cameraapp.data.CellResult;
import com.example.cameraapp.data.ResultRepository;
import com.example.cameraapp.inference.InferencePipeline;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.cameraapp.model.AbnormalRepository;
import com.example.cameraapp.yolo.YoloDetector;

public class OfflineImportActivity extends AppCompatActivity {

    private YoloDetector yoloDetector;
    private PlantClassifier plantClassifier;
    private InferencePipeline pipeline;
    private String plantType;

    private TextView txtStatus;
    private TextView txtProgress;
    private ProgressBar progressBar;
    private Button btnSelectFolder;
    private Spinner spinnerPlant;

    private final ActivityResultLauncher<Intent> folderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri folderUri = result.getData().getData();
                    if (folderUri != null) {
                        getContentResolver().takePersistableUriPermission(
                                folderUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                        Object selected = spinnerPlant.getSelectedItem();
                        plantType = selected != null ? selected.toString() : "ピーマン";
                        startImport(folderUri);
                    }
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_import);

        txtStatus = findViewById(R.id.txtStatus);
        txtProgress = findViewById(R.id.txtProgress);
        progressBar = findViewById(R.id.progressBar);
        btnSelectFolder = findViewById(R.id.btnSelectFolder);
        spinnerPlant = findViewById(R.id.spinnerPlant);

        try {
            yoloDetector = new YoloDetector(this);
            // Pipelineの初期化 (後でモデルをロード)
            pipeline = new InferencePipeline(yoloDetector, bmp -> 
                plantClassifier != null ? plantClassifier.classifyImage(bmp) : new ClassificationResult("Unknown", 0f));
            pipeline.setContext(this);
        } catch (Exception e) {
            Log.e("OfflineImport", "Failed to init", e);
        }

        btnSelectFolder.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            folderPickerLauncher.launch(intent);
        });

        List<String> plants = Arrays.asList("ピーマン", "トマト", "イチゴ", "ブドウ", "りんご", "ポテト", "コーン", "チェリー", "もも");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, plants);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPlant.setAdapter(adapter);
    }

    private void startImport(Uri folderUri) {
        try {
            plantClassifier = new PlantClassifier(this, plantType);
        } catch (Exception e) {
            Toast.makeText(this, "モデルのロードに失敗しました", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences resultPrefs = getSharedPreferences("cell_results", MODE_PRIVATE);
        ResultRepository.INSTANCE.clearAll(resultPrefs);
        AbnormalRepository.INSTANCE.clear();

        btnSelectFolder.setEnabled(false);
        progressBar.setProgress(0);
        txtStatus.setText("インポート中...");

        DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
        if (dir == null) return;

        DocumentFile[] files = dir.listFiles();
        Map<String, DocumentFile> fileCache = new HashMap<>();
        for (DocumentFile f : files) {
            if (f.getName() != null) fileCache.put(f.getName().toLowerCase(), f);
        }

        DocumentFile csvFile = null;
        for (DocumentFile f : files) {
            if (f.getName() != null && f.getName().toLowerCase().endsWith(".csv")) {
                csvFile = f; break;
            }
        }

        if (csvFile == null) {
            Toast.makeText(this, "CSVが見つかりません", Toast.LENGTH_SHORT).show();
            btnSelectFolder.setEnabled(true);
            return;
        }

        List<CsvRecord> records = CsvParser.parse(this, csvFile);
        progressBar.setMax(records.size());
        int cols = getSharedPreferences("field_setting", MODE_PRIVATE).getInt("cols", 5);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            for (int i = 0; i < records.size(); i++) {
                CsvRecord record = records.get(i);
                String imageName = record.getImageName().toLowerCase();
                DocumentFile imageFile = fileCache.get(imageName);
                if (imageFile == null) imageFile = fileCache.get(imageName + ".jpg");

                if (imageFile != null) {
                    try (InputStream is = getContentResolver().openInputStream(imageFile.getUri())) {
                        Bitmap bitmap = BitmapFactory.decodeStream(is);
                        if (bitmap != null) {
                            int cellId = FieldNavigator.getIndex(record.getRow(), record.getCol(), cols);
                            // 第3引数(true)を追加して、メソッドシグネチャの変更に対応
                            InferencePipeline.Result result = pipeline.process(bitmap, cellId, true);

                            if (result != null) {
                                CellResult cellResult = new CellResult(
                                        cellId, record.getRow(), record.getCol(),
                                        result.getLabel(), System.currentTimeMillis(),
                                        result.getSavedImagePath(),
                                        record.getLatitude() != null ? record.getLatitude() : 0.0,
                                        record.getLongitude() != null ? record.getLongitude() : 0.0,
                                        record.getAccuracy() != null ? record.getAccuracy() : 0f
                                );
                                cellResult.setConfidence(result.getConfidence());
                                ResultRepository.INSTANCE.add(cellResult);
                            }
                        }
                    } catch (Exception e) {
                        Log.e("OfflineImport", "Error", e);
                    }
                }

                final int currentIndex = i;
                runOnUiThread(() -> {
                    progressBar.setProgress(currentIndex + 1);
                    txtProgress.setText(((currentIndex + 1) * 100 / records.size()) + "%");
                });
            }

            ResultRepository.INSTANCE.saveToDisk(resultPrefs);
            runOnUiThread(this::showFinishDialog);
        });
    }

    private void showFinishDialog() {
        new AlertDialog.Builder(this)
                .setTitle("完了")
                .setMessage("解析が完了しました。\n保存されたデータ数: " + ResultRepository.INSTANCE.getAll().size())
                .setPositiveButton("マップへ", (dialog, which) -> {
                    startActivity(new Intent(this, MapActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    finish();
                })
                .setCancelable(false)
                .show();
    }
}
