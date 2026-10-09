package com.example.cameraapp;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cameraapp.data.ResultRepository;

import java.util.List;
import java.util.Locale;

public class FieldAdapter extends RecyclerView.Adapter<FieldAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(FieldCell item);
    }

    private final List<FieldCell> items;
    private final OnItemClickListener onClick;
    private final ResultRepository repository;

    public FieldAdapter(List<FieldCell> items, OnItemClickListener onClick) {
        this(items, ResultRepository.INSTANCE, onClick);
    }

    /** 平均・集計を表示する記録のリポジトリを指定する（バックナンバーのその日のマップ用） */
    public FieldAdapter(List<FieldCell> items, ResultRepository repository, OnItemClickListener onClick) {
        this.items = items;
        this.repository = repository;
        this.onClick = onClick;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_field_cell, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        FieldCell item = items.get(position);
        holder.bind(item, repository, onClick);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class VH extends RecyclerView.ViewHolder {
        private final TextView txtCellIndex;
        private final TextView txtPlantType;
        private final TextView txtStatus; // 平均健康度表示用

        public VH(@NonNull View itemView) {
            super(itemView);
            txtCellIndex = itemView.findViewById(R.id.txtCellIndex);
            txtPlantType = itemView.findViewById(R.id.txtPlantType);
            // item_field_cell.xml に TextView があると仮定。なければ追加が必要。
            txtStatus = itemView.findViewById(R.id.txtStatus); 
        }

        public void bind(final FieldCell item, final ResultRepository repository, final OnItemClickListener onClick) {
            int cellId = item.getIndex();
            if (txtCellIndex != null) {
                txtCellIndex.setText(String.valueOf(cellId));
            }
            
            // 平均健康度とサマリーを取得して表示
            float avg = repository.getAverageScore(cellId);
            String summary = repository.getBracketSummary(cellId);
            
            if (txtPlantType != null) {
                // 植物名と一緒に平均を表示
                txtPlantType.setText(String.format(Locale.getDefault(), "%s\n平均:%.1f%%", item.getPlantType(), avg));
            }

            if (txtStatus != null) {
                // 集計サマリーを表示
                txtStatus.setText(summary);
                txtStatus.setTextSize(10f); // 小さめに表示
            }

            // ステータスに基づく背景色設定
            FieldStatus status = item.getStatus();
            if (status == FieldStatus.OK || status == FieldStatus.NORMAL) {
                itemView.setBackgroundColor(Color.parseColor("#A5D6A7")); // 緑
            } else if (status == FieldStatus.NG || status == FieldStatus.ABNORMAL) {
                itemView.setBackgroundColor(Color.parseColor("#EF9A9A")); // 赤
            } else if (status == FieldStatus.UNKNOWN || status == FieldStatus.UNCHECKED) {
                itemView.setBackgroundColor(Color.LTGRAY);
            }

            itemView.setOnClickListener(v -> {
                if (onClick != null) onClick.onItemClick(item);
            });
        }
    }
}
