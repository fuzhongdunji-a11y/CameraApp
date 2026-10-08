package com.example.cameraapp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AbnormalRepository {
    public static final AbnormalRepository INSTANCE = new AbnormalRepository();

    private final List<AbnormalRecord> records = new ArrayList<>();

    private AbnormalRepository() {}

    public List<AbnormalRecord> getAll() {
        return new ArrayList<>(records);
    }

    public void add(AbnormalRecord record) {
        records.add(record);
    }

    public List<AbnormalRecord> getRecordsForCell(int cellId) {
        List<AbnormalRecord> filtered = new ArrayList<>();
        for (AbnormalRecord record : records) {
            if (record.getCellId() == cellId) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    /**
     * データを全消去します
     */
    public void clear() {
        records.clear();
    }
}
