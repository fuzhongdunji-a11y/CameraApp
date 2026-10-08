package com.example.cameraapp;

import android.content.Context;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class CsvParser {
    public static List<CsvRecord> parse(Context context, DocumentFile csvDoc) {
        List<CsvRecord> list = new ArrayList<>();
        try {
            InputStream input = context.getContentResolver().openInputStream(csvDoc.getUri());
            if (input != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                    String line;
                    int lineCount = 0;
                    while ((line = reader.readLine()) != null) {
                        lineCount++;
                        if (lineCount == 1 || line.trim().isEmpty()) continue;

                        String[] t = line.split(",");
                        try {
                            if (t.length >= 3) {
                                int id = Integer.parseInt(t[0].trim());
                                int row = Integer.parseInt(t[1].trim());
                                int col = Integer.parseInt(t[2].trim());

                                list.add(new CsvRecord(id, row, col, id + ".jpg"));
                            }
                        } catch (Exception e) {
                            Log.e("CsvParser", "Error line " + lineCount + ": " + line);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e("CsvParser", "Failed to open CSV stream", e);
        }
        Log.d("CsvParser", "Successfully parsed " + list.size() + " records");
        return list;
    }
}
