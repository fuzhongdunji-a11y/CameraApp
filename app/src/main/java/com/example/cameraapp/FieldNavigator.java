package com.example.cameraapp;

public class FieldNavigator {
    public static final FieldNavigator INSTANCE = new FieldNavigator();

    private FieldNavigator() {}

    /**
     * 行と列から1始まりのインデックスを計算します
     */
    public static int getIndex(int row, int col, int cols) {
        if (cols <= 0) return 1;
        return (row * cols + col) + 1;
    }
}
