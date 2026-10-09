package com.example.cameraapp.ui;

import com.example.cameraapp.cloud.CloudBrowser;
import com.example.cameraapp.data.ResultRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * バックナンバーのカレンダーで選んだ日の記録を、その日のマップ・写真画面に受け渡す。
 * （アプリのプロセスが終了した場合は失われるため、各画面は null を確認して閉じる）
 */
final class BackNumberDay {

    /** 選んだ日（yyyy/MM/dd。DetailActivity の日付の形式と同じ） */
    static String date;
    /** 選んだ日に撮影した S3 の記録 */
    static List<CloudBrowser.Capture> captures = new ArrayList<>();
    /** その日のマップで集計した記録（写真画面で使う） */
    static ResultRepository repository;

    private BackNumberDay() {}

    static void select(String date, List<CloudBrowser.Capture> captures) {
        BackNumberDay.date = date;
        BackNumberDay.captures = new ArrayList<>(captures);
        BackNumberDay.repository = null;
    }
}
