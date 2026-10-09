package com.example.cameraapp.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cameraapp.R;
import com.example.cameraapp.cloud.CloudBrowser;
import com.example.cameraapp.data.CellResult;
import com.example.cameraapp.data.ResultRepository;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DetailActivity extends AppCompatActivity {

    /** true の場合、バックナンバーで選んだ日の S3 の記録を表示する */
    public static final String EXTRA_BACK_NUMBER = "back_number";

    public static class DateSummary {
        public String date;
        public String imagePath;
        public float averageConfidence;
        public int count;

        public DateSummary(String date) {
            this.date = date;
        }
    }

    private RecyclerView recyclerView;
    private TextView txtCellInfo;
    private int cellId;
    private List<CellResult> allRecords;
    private boolean backNumber;
    private ResultRepository repository = ResultRepository.INSTANCE;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        cellId = getIntent().getIntExtra("cell_id", -1);
        if (cellId == -1) {
            finish();
            return;
        }

        backNumber = getIntent().getBooleanExtra(EXTRA_BACK_NUMBER, false);
        if (backNumber) {
            repository = BackNumberDay.repository;
            if (repository == null || BackNumberDay.date == null) {
                finish();
                return;
            }
        }

        txtCellInfo = findViewById(R.id.txtCellInfo);
        ImageView imgPreview = findViewById(R.id.imgPreview);
        recyclerView = findViewById(R.id.detailRecyclerView);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        imgPreview.setVisibility(View.GONE);

        if (backNumber) {
            // バックナンバーでは選んだ日の記録しかないため、その日の写真をすぐに表示する
            allRecords = repository.getRecordsForCell(cellId);
            showDailyDetails(BackNumberDay.date);
        } else {
            showDateSummary();
        }
    }

    private void showDateSummary() {
        txtCellInfo.setText("区画 " + cellId + " (日付一覧)");
        allRecords = repository.getRecordsForCell(cellId);
        
        Map<String, DateSummary> groupedMap = new LinkedHashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault());

        for (CellResult record : allRecords) {
            String dateStr = sdf.format(new Date(record.getTimestamp()));
            DateSummary summary = groupedMap.get(dateStr);
            if (summary == null) {
                summary = new DateSummary(dateStr);
                summary.imagePath = record.getImagePath();
                groupedMap.put(dateStr, summary);
            }
            summary.averageConfidence += record.getConfidence();
            summary.count++;
            summary.imagePath = record.getImagePath(); 
        }

        List<DateSummary> summaries = new ArrayList<>();
        for (DateSummary s : groupedMap.values()) {
            if (s.count > 0) {
                s.averageConfidence /= s.count;
            }
            summaries.add(s);
        }

        recyclerView.setAdapter(new GroupedResultAdapter(summaries, date -> {
            showDailyDetails(date);
        }));
    }

    private void showDailyDetails(String targetDate) {
        txtCellInfo.setText("区画 " + cellId + " [" + targetDate + "]");
        
        List<CellResult> filteredRecords = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault());
        
        for (CellResult record : allRecords) {
            String dateStr = sdf.format(new Date(record.getTimestamp()));
            if (dateStr.equals(targetDate)) {
                filteredRecords.add(record);
            }
        }

        // ResultAdapterを使用してその日の全写真を表示
        recyclerView.setAdapter(new ResultAdapter(filteredRecords, item -> {
            // 必要なら写真拡大などの処理
            if (CloudBrowser.isCloudKey(item.getImagePath())) {
                // S3 の記録は写真の拡大と判定結果テキストを表示する
                RecordImages.showCloudDetail(this, "区画 " + item.getIndex() + "  " + item.getResult(), item.getImagePath());
            }
        }));
        if (backNumber) {
            if (filteredRecords.isEmpty()) txtCellInfo.setText("区画 " + cellId + " [" + targetDate + "] 写真なし");
            return;
        }
        
        // 戻るボタンの代わりに、もう一度クリックで戻るか、ヘッダーに「戻る」を付けるなどの考慮が必要だが、
        // 今回はシンプルにタイトルをタップすると日付一覧に戻るようにする
        txtCellInfo.setOnClickListener(v -> {
            txtCellInfo.setOnClickListener(null);
            showDateSummary();
        });
    }

    @Override
    public void onBackPressed() {
        // 詳細表示中の場合は日付一覧に戻る
        if (!backNumber && txtCellInfo.getText().toString().contains("[")) {
            showDateSummary();
        } else {
            super.onBackPressed();
        }
    }
}
