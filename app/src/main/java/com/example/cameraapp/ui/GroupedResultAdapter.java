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
import java.util.List;
import java.util.Locale;

public class GroupedResultAdapter extends RecyclerView.Adapter<GroupedResultAdapter.ViewHolder> {

    public interface OnDateClickListener {
        void onDateClick(String date);
    }

    private final List<DetailActivity.DateSummary> summaries;
    private final OnDateClickListener listener;

    public GroupedResultAdapter(List<DetailActivity.DateSummary> summaries, OnDateClickListener listener) {
        this.summaries = summaries;
        this.listener = listener;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final ImageView image;
        public final TextView avgText;
        public final TextView dateText;

        public ViewHolder(@NonNull View view) {
            super(view);
            image = view.findViewById(R.id.imageView);
            avgText = view.findViewById(R.id.plantText);
            dateText = view.findViewById(R.id.timeText);
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
        DetailActivity.DateSummary summary = summaries.get(position);

        holder.avgText.setText(String.format(Locale.getDefault(), "平均スコア: %.1f%%", summary.averageConfidence));
        holder.dateText.setText("-" + summary.date + "- (詳細を表示)");

        if (summary.imagePath != null) {
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(summary.imagePath);
                holder.image.setImageBitmap(bitmap);
            } catch (Exception e) {
                holder.image.setImageResource(R.drawable.ic_launcher_background);
            }
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDateClick(summary.date);
            }
        });
    }

    @Override
    public int getItemCount() {
        return summaries.size();
    }
}
