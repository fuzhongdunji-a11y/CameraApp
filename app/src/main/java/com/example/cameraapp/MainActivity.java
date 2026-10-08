package com.example.cameraapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin;
import com.amplifyframework.core.Amplify;
import com.amplifyframework.core.configuration.AmplifyOutputs;
import com.amplifyframework.storage.s3.AWSS3StoragePlugin;
import com.example.cameraapp.R;
import com.example.cameraapp.data.CellResult;
import com.example.cameraapp.data.ResultRepository;
import com.example.cameraapp.inference.InferencePipeline;
import com.example.cameraapp.PlantClassifier;
import com.example.cameraapp.ui.AbnormalListActivity;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.cameraapp.model.AbnormalRecord;
import com.example.cameraapp.model.AbnormalRepository;
import com.example.cameraapp.yolo.YoloDetector;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 100;
    private PreviewView previewView;
    private TextView resultTextView;
    private ExecutorService cameraExecutor;
    private FusedLocationProviderClient fusedLocationClient;

    private YoloDetector yoloDetector;
    private PlantClassifier plantClassifier;
    private InferencePipeline pipeline;

    private Bitmap latestBitmap;
    private String selectedPlant = "ピーマン";
    private int currentCellIndex = 1;
    private int gridCols = 5;
    private int totalCells = 15;

    private volatile boolean isProcessingFrame = false;

    private long lastAutoSaveTime = 0;
    private static final long AUTO_SAVE_INTERVAL = 500; // 0.5秒ごとに自動保存

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- ★ここから追加：Amplifyの初期化 ---
        try {
            Amplify.addPlugin(new AWSCognitoAuthPlugin());
            Amplify.addPlugin(new AWSS3StoragePlugin());
            Amplify.configure(AmplifyOutputs.fromResource(R.raw.amplify_outputs), getApplicationContext());
            Log.i("AmplifyGen2", "Amplifyの初期化に成功しました！");
            uploadTestFile();
        } catch (Exception error) {
            Log.e("AmplifyGen2", "初期化エラー", error);
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        previewView = findViewById(R.id.previewView);
        resultTextView = findViewById(R.id.resultTextView);
        cameraExecutor = Executors.newSingleThreadExecutor();

        selectedPlant = getIntent().getStringExtra("plant_type");
        if (selectedPlant == null) selectedPlant = "ピーマン";
        currentCellIndex = getIntent().getIntExtra("cell_index", 1);

        try {
            yoloDetector = new YoloDetector(this);
            plantClassifier = new PlantClassifier(this, selectedPlant);
            // デフォルトで閾値 0.25f の Config が使用されるように InferencePipeline を修正済み
            pipeline = new InferencePipeline(yoloDetector, bmp -> plantClassifier.classifyImage(bmp));
            pipeline.setContext(this);
        } catch (Exception e) {
            Log.e("MainActivity", "Init error", e);
        }

        loadFieldSettings();
        setupButtons();
        checkPermissionsAndStart();
    }

    private void uploadTestFile() {
        try {
            String dateTimeStr = new SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = dateTimeStr + "_test.txt";
            File testFile = new File(getApplicationContext().getFilesDir(), fileName);

            String content = "======== Amplify Storage テスト ========\n" +
                    "初期化日時: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n" +
                    "西暦_日付ID: " + dateTimeStr + "\n" +
                    "ステータス: 初期化＆接続テスト成功\n" +
                    "========================================\n";

            FileOutputStream fos = new FileOutputStream(testFile);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();

            String s3Key = "public/captures/" + fileName;
            uploadFileToS3(testFile, s3Key);
        } catch (Exception e) {
            Log.e("AmplifyGen2", "テストファイル作成・アップロード失敗", e);
        }
    }

    private void setupButtons() {
        findViewById(R.id.btnDetect).setOnClickListener(v -> {
            Bitmap bmp = latestBitmap;
            if (bmp != null) processIncomingBitmap(bmp, true);
        });

        findViewById(R.id.btnReport).setOnClickListener(v -> {
            saveManualRecord("Report");
            finishWork();
        });

        findViewById(R.id.btnAbnormal).setOnClickListener(v -> saveManualRecord("Manual_Capture"));

        findViewById(R.id.btnFinish).setOnClickListener(v -> {
            startActivity(new Intent(this, AbnormalListActivity.class));
        });

        findViewById(R.id.btnNextCell).setOnClickListener(v -> goToNextCell());
    }

    private void processIncomingBitmap(Bitmap bitmap, boolean manual) {
        if (isProcessingFrame) return;
        isProcessingFrame = true;

        cameraExecutor.execute(() -> {
            try {
                if (pipeline != null) {
                    long now = System.currentTimeMillis();
                    // 手動保存、または前回の自動保存から0.5秒以上経過している場合に保存フラグを立てる
                    boolean shouldTrySave = manual || (now - lastAutoSaveTime >= AUTO_SAVE_INTERVAL);

                    InferencePipeline.Result result = pipeline.process(bitmap, currentCellIndex, shouldTrySave);

                    runOnUiThread(() -> {
                        if (result != null) {
                            float threshold = 0.25f;
                            // 保存された場合（閾値を超え、かつタイミングが合った場合）
                            if (result.getSavedImagePath() != null) {
                                if (!manual) {
                                    lastAutoSaveTime = now;
                                }
                                saveResultToMap(result.getLabel(), result.getSavedImagePath(), result.getConfidence());

                                // 西暦_日付形式（例: 2026_10_07_123847）のファイル名でS3に保存
                                String dateTimeStr = new SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault()).format(new Date());
                                String s3ImageKey = "public/captures/" + dateTimeStr + ".jpg";

                                File imageFile = new File(result.getSavedImagePath());
                                uploadFileToS3(imageFile, s3ImageKey);

                                // 判定結果（テキスト）ファイルを生成してS3にアップロード
                                uploadResultTextToS3(result.getLabel(), result.getConfidence(), result.getGreenRatio(), currentCellIndex, dateTimeStr + ".jpg", dateTimeStr);

                                if (manual) Toast.makeText(this, "手動保存しました", Toast.LENGTH_SHORT).show();
                            }

                            String status = result.getSavedImagePath() != null ? "【保存完了】" : "【待機中】";
                            if (result.getGreenRatio() < threshold) {
                                status = "【緑不足】";
                            }

                            // 精度を上げるため、判定(Confidence)を小数第3位まで表示するように変更
                            String info = String.format(Locale.getDefault(),
                                    "%s\n判定: %s (%.3f%%)\n緑比率: %.3f (しきい値: %.2f)",
                                    status, result.getLabel(), result.getConfidence(), result.getGreenRatio(), threshold);
                            resultTextView.setText(info);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e("Process", "Error", e);
            } finally {
                isProcessingFrame = false;
            }
        });
    }

    private void saveResultToMap(String label, String path, float confidence) {
        int zeroIndex = currentCellIndex - 1;
        CellResult res = new CellResult(currentCellIndex, zeroIndex / gridCols, zeroIndex % gridCols,
                label, System.currentTimeMillis(), path, 0.0, 0.0, 0f);
        res.setConfidence(confidence);
        ResultRepository.INSTANCE.add(res);
    }

    private void saveManualRecord(String trigger) {
        Bitmap bmp = latestBitmap;
        if (bmp == null || pipeline == null) return;

        String dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String nestedPath = "Cell_" + currentCellIndex + "/Manual/" + trigger + "/" + dateStr;
        String path = pipeline.saveToNestedFolder(bmp, nestedPath, "manual_" + System.currentTimeMillis() + ".jpg");

        if (path != null) {
            AbnormalRecord record = new AbnormalRecord(currentCellIndex, selectedPlant, path, 1.0f, System.currentTimeMillis(), trigger);
            AbnormalRepository.INSTANCE.add(record);
            saveResultToMap(trigger, path, 100.0f);

            // 西暦_日付形式（例: 2026_10_07_123847）のファイル名でS3に保存
            String dateTimeStr = new SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault()).format(new Date());
            String s3ImageKey = "public/captures/" + dateTimeStr + ".jpg";

            File imageFile = new File(path);
            uploadFileToS3(imageFile, s3ImageKey);

            // 判定結果（テキスト）ファイルを生成してS3にアップロード
            uploadResultTextToS3("【手動記録】" + trigger, 100.0f, 1.0f, currentCellIndex, dateTimeStr + ".jpg", dateTimeStr);
        }
    }

    // 判定結果（テキストデータ）をファイルとして生成し、S3へアップロードする関数
    private void uploadResultTextToS3(String label, float confidence, float greenRatio, int cellIndex, String imageFileName, String dateTimeStr) {
        String textFileName = dateTimeStr + "_result.txt";

        String content = "======== 判定結果レポート ========\n" +
                "日時: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n" +
                "西暦_日付ID: " + dateTimeStr + "\n" +
                "対象作物: " + selectedPlant + "\n" +
                "区画(Cell): " + cellIndex + "\n" +
                "判定結果(異常/病気/状態): " + label + "\n" +
                "確信度(Confidence): " + String.format(Locale.getDefault(), "%.2f%%", confidence) + "\n" +
                "緑色比率: " + String.format(Locale.getDefault(), "%.3f", greenRatio) + "\n" +
                "関連画像ファイル: " + (imageFileName != null ? imageFileName : "なし") + "\n" +
                "==================================\n";

        try {
            File txtFile = new File(getApplicationContext().getFilesDir(), textFileName);
            FileOutputStream fos = new FileOutputStream(txtFile);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();

            uploadFileToS3(txtFile, "public/captures/" + textFileName);
        } catch (IOException e) {
            Log.e("AmplifyGen2", "結果テキストファイルの生成失敗", e);
        }
    }

    // ファイルを指定してS3にアップロードする共通関数
    private void uploadFileToS3(File file, String s3Key) {
        if (file == null || !file.exists()) {
            Log.e("AmplifyGen2", "アップロード失敗: ファイルが存在しません");
            return;
        }

        Log.i("AmplifyGen2", "ファイルアップロード開始: " + s3Key);

        Amplify.Storage.uploadFile(
                s3Key,
                file,
                result -> Log.i("AmplifyGen2", "S3アップロード成功！キー: " + result.getKey()),
                error -> Log.e("AmplifyGen2", "S3アップロード失敗", error)
        );
    }

    // カメラで撮影したBitmap画像などをS3にアップロードする関数
    private void uploadCameraImageToS3(Bitmap bitmap) {
        if (bitmap == null) {
            Log.e("AmplifyGen2", "アップロード失敗: Bitmapがnullです");
            return;
        }

        try {
            String dateTimeStr = new SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault()).format(new Date());
            File imageFile = new File(getApplicationContext().getFilesDir(), dateTimeStr + ".jpg");
            FileOutputStream fos = new FileOutputStream(imageFile);

            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            fos.flush();
            fos.close();

            uploadFileToS3(imageFile, "public/captures/" + dateTimeStr + ".jpg");

        } catch (IOException e) {
            Log.e("AmplifyGen2", "一時ファイルの作成に失敗しました", e);
        }
    }



    private void finishWork() {
        saveData();
        Toast.makeText(this, "保存して戻ります", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, com.example.cameraapp.MapActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    private void saveData() {
        new Thread(() -> ResultRepository.INSTANCE.saveToDisk(getSharedPreferences("cell_results", MODE_PRIVATE))).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveData(); // 画面を離れる時に常に保存
    }

    private void loadFieldSettings() {
        SharedPreferences prefs = getSharedPreferences("field_setting", MODE_PRIVATE);
        gridCols = prefs.getInt("cols", 5);
        totalCells = prefs.getInt("rows", 3) * gridCols;
    }

    private void checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> provider = ProcessCameraProvider.getInstance(this);
        provider.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = provider.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) { Log.e("Camera", "Error", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(ImageProxy image) {
        try {
            Bitmap bmp = imageProxyToBitmap(image);
            if (bmp != null) {
                latestBitmap = bmp;
                if (!isProcessingFrame) {
                    processIncomingBitmap(bmp, false);
                }
            }
        } finally { image.close(); }
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) return null;
        int width = image.getWidth();
        int height = image.getHeight();
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        byte[] nv21 = new byte[width * height * 3 / 2];

        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();

        int yStride = planes[0].getRowStride();
        int uvStride = planes[1].getRowStride();
        int uvPixStride = planes[1].getPixelStride();

        for (int r = 0; r < height; r++) {
            yBuf.position(r * yStride);
            yBuf.get(nv21, r * width, width);
        }
        int pos = width * height;
        for (int r = 0; r < height / 2; r++) {
            for (int c = 0; c < width / 2; c++) {
                nv21[pos++] = vBuf.get(r * uvStride + c * uvPixStride);
                nv21[pos++] = uBuf.get(r * uvStride + c * uvPixStride);
            }
        }
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, width, height), 100, out);
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size());
    }

    private void goToNextCell() {
        if (currentCellIndex < totalCells) {
            currentCellIndex++;
            Toast.makeText(this, "区画: " + currentCellIndex, Toast.LENGTH_SHORT).show();
        } else {
            finishWork();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (plantClassifier != null) plantClassifier.close();
    }
}
