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

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

import com.example.cameraapp.model.AbnormalRecord;

public class AbnormalAdapter extends RecyclerView.Adapter<AbnormalAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(AbnormalRecord record);
    }

    private final List<AbnormalRecord> records;
    private final OnItemClickListener onClick;

    public AbnormalAdapter(List<AbnormalRecord> records, OnItemClickListener onClick) {
        this.records = records;
        this.onClick = onClick;
    }

    public static class VH extends RecyclerView.ViewHolder {
        public final ImageView image;
        public final TextView plant;
        public final TextView time;

        public VH(@NonNull View view) {
            super(view);
            image = view.findViewById(R.id.imageView);
            plant = view.findViewById(R.id.plantText);
            time = view.findViewById(R.id.timeText);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_abnormal, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        AbnormalRecord r = records.get(position);

        // 画像の表示
        if (r.getImagePath() != null && !r.getImagePath().isEmpty()) {
            Bitmap bmp = BitmapFactory.decodeFile(r.getImagePath());
            holder.image.setImageBitmap(bmp);
        }

        holder.plant.setText("結果: " + r.getLabel() + " (" + r.getScore() + ")");

        // 日時の表示
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
        holder.time.setText(sdf.format(r.getTimestamp()));

        holder.itemView.setOnClickListener(v -> {
            if (onClick != null) {
                onClick.onItemClick(r);
            }
        });
    }

    @Override
    public int getItemCount() {
        return records.size();
    }
}
