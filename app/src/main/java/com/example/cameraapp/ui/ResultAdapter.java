package com.example.cameraapp.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cameraapp.R;
import com.example.cameraapp.data.CellResult;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(CellResult item);
    }

    private final List<CellResult> records;
    private final OnItemClickListener onClick;

    public ResultAdapter(List<CellResult> records, OnItemClickListener onClick) {
        this.records = records;
        this.onClick = onClick;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final ImageView image;
        public final TextView plant;
        public final TextView time;

        public ViewHolder(@NonNull View view) {
            super(view);
            image = view.findViewById(R.id.imageView);
            plant = view.findViewById(R.id.plantText);
            time = view.findViewById(R.id.timeText);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_abnormal, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CellResult record = records.get(position);

        // 結果とスコアを表示
        holder.plant.setText(String.format(Locale.getDefault(), "結果：%s (%.1f%%)", record.getResult(), record.getConfidence()));

        String formattedTime = new SimpleDateFormat(
                "HH:mm:ss",
                Locale.getDefault()
        ).format(record.getTimestamp());
        holder.time.setText(formattedTime);

        if (record.getImagePath() != null) {
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(record.getImagePath());
                holder.image.setImageBitmap(bitmap);
            } catch (Exception e) {
                holder.image.setImageResource(R.drawable.ic_launcher_background);
            }
        }

        holder.itemView.setOnClickListener(v -> {
            if (onClick != null) {
                onClick.onItemClick(record);
            }
        });
    }

    @Override
    public int getItemCount() {
        return records.size();
    }
}
