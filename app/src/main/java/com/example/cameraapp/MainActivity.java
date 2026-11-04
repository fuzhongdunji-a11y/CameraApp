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
import android.os.SystemClock;
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

import com.example.cameraapp.cloud.CloudUploader;
import com.example.cameraapp.R;
import com.example.cameraapp.data.CellResult;
import com.example.cameraapp.data.ResultRepository;
import com.example.cameraapp.inference.BestFrameSelector;
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

    // 最初のフレームを保存済みの区画番号（区画が変わると再び最初のフレームを保存する）
    private volatile int firstFrameSavedCell = -1;
    // 区画の最初に保存した写真の記録（異常記録・病気判別時に破棄する。UIスレッドでのみ操作）
    private CellResult firstFrameRecord;

    // 「異常あり」ボタンで開始し、もう一度押すまで2秒ごとに1枚保存する
    private volatile boolean abnormalRecording = false;
    private int abnormalSavedCount = 0;

    // 病気のフレーム・異常記録のフレームは、2秒ごとにその間で最もブレの少ない1枚を保存する
    // （候補の追加・保存は cameraExecutor 上でのみ行う）
    private static final long BEST_FRAME_WINDOW_MS = 2000;
    private final BestFrameSelector diseaseSelector = new BestFrameSelector(BEST_FRAME_WINDOW_MS);
    private final BestFrameSelector abnormalSelector = new BestFrameSelector(BEST_FRAME_WINDOW_MS);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- ★ここから追加：Amplifyの初期化 ---
        if (CloudUploader.ensureInitialized(this)) {
            uploadTestFile();
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
            CloudUploader.uploadFile(testFile, s3Key);
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

        findViewById(R.id.btnAbnormal).setOnClickListener(v -> {
            if (abnormalRecording) stopAbnormalRecording();
            else startAbnormalRecording();
        });

        findViewById(R.id.btnFinish).setOnClickListener(v -> {
            startActivity(new Intent(this, AbnormalListActivity.class));
        });

        findViewById(R.id.btnNextCell).setOnClickListener(v -> goToNextCell());
    }

    private void startAbnormalRecording() {
        abnormalRecording = true;
        abnormalSavedCount = 0;
        // 最初の写真は破棄し、以降は最初のフレームとして保存しない
        discardFirstFrame(currentCellIndex);
        firstFrameSavedCell = currentCellIndex;
        ((Button) findViewById(R.id.btnAbnormal)).setText("異常記録停止");
        Toast.makeText(this, "異常記録を開始しました", Toast.LENGTH_SHORT).show();
    }

    private void stopAbnormalRecording() {
        stopAbnormalRecording(null);
    }

    /** 異常記録を停止し、保存待ちのフレームを保存してから onDone を UI スレッドで実行する */
    private void stopAbnormalRecording(@Nullable Runnable onDone) {
        boolean wasRecording = abnormalRecording;
        abnormalRecording = false;
        if (wasRecording) ((Button) findViewById(R.id.btnAbnormal)).setText("異常あり");
        flushPendingFrames(() -> {
            if (wasRecording) {
                Toast.makeText(this, "異常記録を停止しました（" + abnormalSavedCount + "枚保存）", Toast.LENGTH_SHORT).show();
            }
            if (onDone != null) onDone.run();
        });
    }

    /** 2秒経過前の保存待ちフレーム（病気・異常記録）を保存し、完了後に onDone を UI スレッドで実行する */
    private void flushPendingFrames(@Nullable Runnable onDone) {
        cameraExecutor.execute(() -> {
            BestFrameSelector.Candidate abnormal = abnormalSelector.flush();
            String abnormalPath = saveAbnormalCandidate(abnormal);
            BestFrameSelector.Candidate disease = diseaseSelector.flush();
            String diseasePath = saveDiseaseCandidate(disease);
            runOnUiThread(() -> {
                if (abnormalPath != null) onAbnormalFrameSaved(abnormal.cellIndex, abnormalPath, null);
                if (diseasePath != null) onDiseaseFrameSaved(disease, diseasePath);
                if (onDone != null) onDone.run();
            });
        });
    }

    /** 異常記録の候補フレームを保存する（候補がなければ null） */
    private String saveAbnormalCandidate(@Nullable BestFrameSelector.Candidate c) {
        if (c == null || pipeline == null) return null;
        String dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String nestedPath = "Cell_" + c.cellIndex + "/Manual/Manual_Capture/" + dateStr;
        return pipeline.saveToNestedFolder(c.bitmap, nestedPath, "manual_" + System.nanoTime() + ".jpg");
    }

    /** 病気と判別した候補フレームを保存する（候補がなければ null） */
    private String saveDiseaseCandidate(@Nullable BestFrameSelector.Candidate c) {
        if (c == null || pipeline == null) return null;
        return pipeline.saveClassified(c.bitmap, c.cellIndex, c.label, c.confidence);
    }

    /** 病気のフレームを保存した記録を登録する（UIスレッド） */
    private void onDiseaseFrameSaved(BestFrameSelector.Candidate c, String path) {
        // S3 へのアップロードはマップ画面の「クラウドに保存」ボタンで行う
        saveResultToMap(c.cellIndex, c.label, path, c.confidence);
        // 病気と判別したフレームを保存した場合、正常だった最初の写真は破棄
        CellResult first = firstFrameRecord;
        if (first != null && first.getIndex() == c.cellIndex && "healthy".equalsIgnoreCase(first.getResult())) {
            discardFirstFrame(c.cellIndex);
        }
    }

    /** 区画の最初に保存した写真を破棄する（ファイルと記録を削除） */
    private void discardFirstFrame(int cellIndex) {
        CellResult first = firstFrameRecord;
        if (first == null || first.getIndex() != cellIndex) return;
        firstFrameRecord = null;
        if (first.getImagePath() != null) new File(first.getImagePath()).delete();
        ResultRepository.INSTANCE.remove(first);
    }

    private void processIncomingBitmap(Bitmap bitmap, boolean manual) {
        if (isProcessingFrame) return;
        isProcessingFrame = true;

        cameraExecutor.execute(() -> {
            try {
                if (pipeline != null) {
                    int cellIndex = currentCellIndex;

                    long now = SystemClock.elapsedRealtime();

                    // 異常記録中は判別結果に関係なく、2秒ごとに最もブレの少ないフレームを保存する
                    if (abnormalRecording && !manual) {
                        InferencePipeline.Result result = pipeline.process(bitmap, cellIndex, false);
                        abnormalSelector.offer(bitmap, cellIndex, result.getLabel(), result.getConfidence(), now);
                        String path = saveAbnormalCandidate(abnormalSelector.pollIfExpired(now));
                        runOnUiThread(() -> onAbnormalFrameSaved(cellIndex, path, result));
                        return;
                    }

                    // この区画でまだ最初に判別したフレームを保存していなければ、結果に関係なく保存
                    boolean isFirstFrame = firstFrameSavedCell != cellIndex;
                    // 2フレーム目以降の自動保存では、正常(healthy)のフレームは保存せず、
                    // 病気のフレームは2秒ごとにその間で最もブレの少ない1枚を保存する
                    boolean autoDisease = !manual && !isFirstFrame;

                    // 緑不足のフレームは判別・保存されない
                    InferencePipeline.Result result = pipeline.process(bitmap, cellIndex, !autoDisease);
                    if (isFirstFrame && result != null && result.getSavedImagePath() != null) {
                        firstFrameSavedCell = cellIndex;
                    }

                    BestFrameSelector.Candidate diseaseFrame = null;
                    String diseasePath = null;
                    if (autoDisease && result != null) {
                        boolean diseased = result.getGreenRatio() >= pipeline.getConfig().getGreenThreshold()
                                && !"healthy".equalsIgnoreCase(result.getLabel());
                        if (diseased) {
                            diseaseSelector.offer(bitmap, cellIndex, result.getLabel(), result.getConfidence(), now);
                        }
                        diseaseFrame = diseaseSelector.pollIfExpired(now);
                        diseasePath = saveDiseaseCandidate(diseaseFrame);
                    }
                    boolean diseasePending = autoDisease && diseaseSelector.hasCandidate();
                    BestFrameSelector.Candidate savedDiseaseFrame = diseaseFrame;
                    String savedDiseasePath = diseasePath;

                    runOnUiThread(() -> {
                        if (result != null) {
                            float threshold = 0.25f;
                            String savedPath = result.getSavedImagePath();
                            if (savedPath != null) {
                                // S3 へのアップロードはマップ画面の「クラウドに保存」ボタンで行う
                                CellResult saved = saveResultToMap(cellIndex, result.getLabel(), savedPath, result.getConfidence());


                                if (manual) {
                                    Toast.makeText(this, "手動保存しました", Toast.LENGTH_SHORT).show();
                                } else if (isFirstFrame) {
                                    firstFrameRecord = saved;
                                }
                            }
                            if (savedDiseasePath != null) onDiseaseFrameSaved(savedDiseaseFrame, savedDiseasePath);



                            String status;
                            if (savedPath != null) {
                                status = isFirstFrame && !manual ? "【初回フレーム保存】" : "【保存完了】";
                            } else if (savedDiseasePath != null) {
                                status = "【保存完了】";
                            } else if (diseasePending) {
                                status = "【病気検出・保存待ち】";
                            } else if (result.getGreenRatio() < threshold) {
                                status = "【緑不足】";
                            } else {
                                status = "【待機中】";
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

    private void onAbnormalFrameSaved(int cellIndex, String path, InferencePipeline.Result result) {
        if (path != null) {
            if (abnormalSavedCount == 0) {
                // 判定履歴には異常記録1回につき1件（最初のフレーム）のみ登録する
                AbnormalRecord record = new AbnormalRecord(cellIndex, selectedPlant, path, 1.0f, System.currentTimeMillis(), "Manual_Capture");
                AbnormalRepository.INSTANCE.add(record);
            }
            abnormalSavedCount++;
            saveResultToMap(cellIndex, "Manual_Capture", path, 100.0f);
        }
        if (result != null) {
            resultTextView.setText(String.format(Locale.getDefault(),
                    "【異常記録中】%d枚保存\n判定: %s (%.3f%%)\n緑比率: %.3f",
                    abnormalSavedCount, result.getLabel(), result.getConfidence(), result.getGreenRatio()));
        }
    }

    private CellResult saveResultToMap(String label, String path, float confidence) {
        return saveResultToMap(currentCellIndex, label, path, confidence);
    }

    private CellResult saveResultToMap(int cellIndex, String label, String path, float confidence) {
        int zeroIndex = cellIndex - 1;
        CellResult res = new CellResult(cellIndex, zeroIndex / gridCols, zeroIndex % gridCols,
                label, System.currentTimeMillis(), path, 0.0, 0.0, 0f);
        res.setConfidence(confidence);
        ResultRepository.INSTANCE.add(res);
        return res;
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
            // S3 へのアップロードはマップ画面の「クラウドに保存」ボタンで行う
            saveResultToMap(trigger, path, 100.0f);
        }
    }

    private void finishWork() {
        // 保存待ちのフレームを保存し終えてから記録をディスクに保存して戻る
        stopAbnormalRecording(() -> {
            saveData();
            Toast.makeText(this, "保存して戻ります", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, com.example.cameraapp.MapActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
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
        // 区画が変わる場合は異常記録を停止し、保存待ちのフレームを保存する
        stopAbnormalRecording();
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
