package com.example.cameraapp.data;

import android.content.SharedPreferences;
import android.util.Log;

import com.example.cameraapp.FieldStatus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ResultRepository {
    public static final ResultRepository INSTANCE = new ResultRepository();

    private final Map<Integer, List<CellResult>> results = new HashMap<>();
    private final Map<Integer, FieldStatus> map = new HashMap<>();

    private static final String KEY_STATUS_PREFIX = "cell_status_";
    private static final String KEY_RECORDS_PREFIX = "cell_records_";

    private ResultRepository() {}

    /**
     * 端末の記録とは別に、一時的に集計するためのリポジトリを作る
     * （バックナンバーのその日のマップなど。ステータスの判定は INSTANCE と同じ）
     */
    public static ResultRepository newTemporary() {
        return new ResultRepository();
    }

    public void clearAll(SharedPreferences prefs) {
        results.clear();
        map.clear();
        if (prefs != null) prefs.edit().clear().apply();
    }

    public FieldStatus getStatus(int cellId) {
        FieldStatus status = map.get(cellId);
        return status != null ? status : FieldStatus.UNKNOWN;
    }

    public float getAverageScore(int cellId) {
        List<CellResult> list = results.get(cellId);
        if (list == null || list.isEmpty()) return 0f;
        float sum = 0;
        for (CellResult r : list) {
            sum += r.getConfidence();
        }
        return sum / list.size();
    }

    public String getBracketSummary(int cellId) {
        List<CellResult> list = results.get(cellId);
        if (list == null || list.isEmpty()) return "データなし";
        
        Map<Integer, Integer> brackets = new TreeMap<>();
        for (CellResult r : list) {
            int b = (int)(r.getConfidence() / 10);
            if (b > 9) b = 9;
            if (b < 0) b = 0;
            brackets.put(b, brackets.getOrDefault(b, 0) + 1);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> entry : brackets.entrySet()) {
            sb.append(entry.getKey() * 10).append("%台:").append(entry.getValue()).append("件 ");
        }
        return sb.toString().trim();
    }

    public void loadAll(SharedPreferences prefs) {
        map.clear();
        results.clear();
        Map<String, ?> allEntries = prefs.getAll();
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(KEY_STATUS_PREFIX)) {
                try {
                    int cellId = Integer.parseInt(key.substring(KEY_STATUS_PREFIX.length()));
                    map.put(cellId, FieldStatus.valueOf((String) entry.getValue()));
                } catch (Exception ignored) {}
            } else if (key.startsWith(KEY_RECORDS_PREFIX)) {
                try {
                    int cellId = Integer.parseInt(key.substring(KEY_RECORDS_PREFIX.length()));
                    JSONArray array = new JSONArray((String) entry.getValue());
                    List<CellResult> list = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        CellResult record = new CellResult(
                                obj.getInt("index"), obj.getInt("row"), obj.getInt("col"),
                                obj.getString("result"), obj.getLong("timestamp"),
                                obj.optString("imagePath", null),
                                obj.getDouble("latitude"), obj.getDouble("longitude"),
                                (float) obj.getDouble("accuracy")
                        );
                        record.setConfidence((float) obj.optDouble("confidence", 0.0));
                        record.setUploaded(obj.optBoolean("uploaded", false));
                        list.add(record);
                    }
                    results.put(cellId, list);
                } catch (Exception ignored) {}
            }
        }
    }

    public void saveToDisk(SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<Integer, List<CellResult>> entry : results.entrySet()) {
            JSONArray array = new JSONArray();
            try {
                for (CellResult record : entry.getValue()) {
                    JSONObject obj = new JSONObject();
                    obj.put("index", record.getIndex());
                    obj.put("row", record.getRow());
                    obj.put("col", record.getCol());
                    obj.put("result", record.getResult());
                    obj.put("timestamp", record.getTimestamp());
                    obj.put("imagePath", record.getImagePath());
                    obj.put("latitude", record.getLatitude());
                    obj.put("longitude", record.getLongitude());
                    obj.put("accuracy", record.getAccuracy());
                    obj.put("confidence", record.getConfidence()); // ここで保存
                    obj.put("uploaded", record.isUploaded());
                    array.put(obj);
                }
                editor.putString(KEY_RECORDS_PREFIX + entry.getKey(), array.toString());
            } catch (Exception ignored) {}
        }
        for (Map.Entry<Integer, FieldStatus> entry : map.entrySet()) {
            editor.putString(KEY_STATUS_PREFIX + entry.getKey(), entry.getValue().name());
        }
        editor.apply();
    }

    public void add(CellResult record) {
        List<CellResult> list = results.get(record.getIndex());
        if (list == null) {
            list = new ArrayList<>();
            results.put(record.getIndex(), list);
        }
        list.add(record);
        updateStatus(record);
    }

    /** 記録を削除し、その区画のステータスを残りの記録から再計算する */
    public void remove(CellResult record) {
        List<CellResult> list = results.get(record.getIndex());
        if (list == null || !list.remove(record)) return;

        map.remove(record.getIndex());
        for (CellResult r : list) updateStatus(r);
    }

    private void updateStatus(CellResult record) {
        String r = record.getResult().toLowerCase();

        // 緑不足の場合はステータスを健康(OK)に更新しない
        if (r.contains("low green")) {
            return;
        }

        boolean isAbnormal = r.contains("diseased") || r.contains("異常") || r.contains("ng") ||
                             r.contains("manual") || record.getConfidence() <= 50.0f;

        if (isAbnormal) {
            map.put(record.getIndex(), FieldStatus.NG);
        } else if (getStatus(record.getIndex()) != FieldStatus.NG) {
            map.put(record.getIndex(), FieldStatus.OK);
        }
    }

    public List<CellResult> getAll() {
        List<CellResult> all = new ArrayList<>();
        for (List<CellResult> list : results.values()) all.addAll(list);
        return all;
    }

    public List<CellResult> getRecordsForCell(int cellId) {
        List<CellResult> list = results.get(cellId);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }
}
