package com.example.cameraapp.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 1か月分のカレンダー。記録のある日は件数を表示し、マップと同じ色（緑: 正常のみ、赤: 病気・異常あり）で塗る。
 * 日付を押すとリスナーに yyyy_MM_dd 形式の日付を渡す。
 */
public class MonthCalendarView extends LinearLayout {

    public interface OnDateClickListener {
        void onDateClick(String day);
    }

    /** 1日分の記録の件数と、病気・異常ありを含むか */
    public static class DayMark {
        public int count;
        public boolean problem;
    }

    // マップの区画と同じ色
    private static final int COLOR_OK = Color.parseColor("#A5D6A7");
    private static final int COLOR_NG = Color.parseColor("#EF9A9A");
    private static final int COLOR_TEXT = Color.parseColor("#0B0B0B");
    private static final int COLOR_MUTED = Color.parseColor("#52514E");
    private static final int COLOR_SUNDAY = Color.parseColor("#C62828");
    private static final int COLOR_SATURDAY = Color.parseColor("#1565C0");
    private static final String[] WEEKDAYS = {"日", "月", "火", "水", "木", "金", "土"};

    private final SimpleDateFormat dayKey = new SimpleDateFormat("yyyy_MM_dd", Locale.getDefault());
    private final TextView monthTitle;
    private final TextView[] dayCells = new TextView[42];
    private final Calendar month = Calendar.getInstance();
    private Map<String, DayMark> marks = new HashMap<>();
    private OnDateClickListener listener;
    private final float dp;

    public MonthCalendarView(Context context) {
        this(context, null);
    }

    public MonthCalendarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        dp = getResources().getDisplayMetrics().density;
        month.set(Calendar.DAY_OF_MONTH, 1);

        // 見出し（前の月・年月・次の月）
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = new Button(context);
        prev.setText("＜");
        prev.setOnClickListener(v -> moveMonth(-1));
        Button next = new Button(context);
        next.setText("＞");
        next.setOnClickListener(v -> moveMonth(1));
        monthTitle = new TextView(context);
        monthTitle.setGravity(Gravity.CENTER);
        monthTitle.setTextSize(16);
        monthTitle.setTypeface(Typeface.DEFAULT_BOLD);
        monthTitle.setTextColor(COLOR_TEXT);
        header.addView(prev, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(monthTitle, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(next, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(header, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 曜日
        LinearLayout weekRow = new LinearLayout(context);
        for (int i = 0; i < 7; i++) {
            TextView tv = new TextView(context);
            tv.setText(WEEKDAYS[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(12);
            tv.setTextColor(i == 0 ? COLOR_SUNDAY : i == 6 ? COLOR_SATURDAY : COLOR_MUTED);
            weekRow.addView(tv, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        addView(weekRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 日付（6週 x 7日）。残りの高さを6週で等分する
        int margin = (int) (1 * dp);
        for (int w = 0; w < 6; w++) {
            LinearLayout row = new LinearLayout(context);
            for (int d = 0; d < 7; d++) {
                TextView tv = new TextView(context);
                tv.setGravity(Gravity.CENTER);
                tv.setTextSize(13);
                tv.setIncludeFontPadding(false);
                LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(margin, margin, margin, margin);
                row.addView(tv, lp);
                dayCells[w * 7 + d] = tv;
            }
            addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        render();
    }

    public void setOnDateClickListener(OnDateClickListener listener) {
        this.listener = listener;
    }

    /** 日付（yyyy_MM_dd）ごとの記録を設定する */
    public void setMarks(Map<String, DayMark> marks) {
        this.marks = marks;
        render();
    }

    /** 表示する月を変える */
    public void showMonth(int year, int monthOfYear) {
        month.set(year, monthOfYear, 1);
        render();
    }

    private void moveMonth(int delta) {
        month.add(Calendar.MONTH, delta);
        render();
    }

    private void render() {
        monthTitle.setText(new SimpleDateFormat("yyyy年M月", Locale.getDefault()).format(month.getTime()));

        String today = dayKey.format(Calendar.getInstance().getTime());
        Calendar cal = (Calendar) month.clone();
        int offset = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        for (int i = 0; i < dayCells.length; i++) {
            TextView tv = dayCells[i];
            int day = i - offset + 1;
            if (day < 1 || day > days) {
                tv.setText("");
                tv.setBackground(null);
                tv.setOnClickListener(null);
                tv.setClickable(false);
                continue;
            }
            cal.set(Calendar.DAY_OF_MONTH, day);
            String key = dayKey.format(cal.getTime());
            DayMark mark = marks.get(key);
            int weekday = i % 7;

            tv.setText(mark != null ? day + "\n" + mark.count + "件" : String.valueOf(day));
            tv.setTextColor(weekday == 0 ? COLOR_SUNDAY : weekday == 6 ? COLOR_SATURDAY : COLOR_TEXT);
            tv.setTypeface(key.equals(today) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(4 * dp);
            if (mark != null) bg.setColor(mark.problem ? COLOR_NG : COLOR_OK);
            else bg.setColor(Color.TRANSPARENT);
            if (key.equals(today)) bg.setStroke((int) (1.5f * dp), COLOR_MUTED);
            tv.setBackground(bg);
            tv.setOnClickListener(v -> {
                if (listener != null) listener.onDateClick(key);
            });
        }
    }
}
