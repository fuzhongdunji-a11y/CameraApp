package com.example.cameraapp;

public enum FieldStatus {
    OK,
    NG,
    UNKNOWN,
    NORMAL,
    ABNORMAL,
    UNCHECKED;

    public static final FieldStatus HEALTHY = NORMAL;
    public static final FieldStatus DISEASED = ABNORMAL;
    public static final FieldStatus NO_PLANT = UNCHECKED;
}
