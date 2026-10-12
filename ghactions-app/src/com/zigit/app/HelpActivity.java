package com.zigit.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.TextView;

/**
 * Встроенная инструкция с форматированным HTML,
 * визуальными обозначениями кнопок и схемами экранов.
 */
public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Создаём layout программно — шапка + WebView
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF6F8FA);

        // Шапка
        android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF0D1117);
        int pad = Util.dp(this, 4);
        header.setPadding(pad, 0, Util.dp(this, 14), 0);
        android.widget.LinearLayout.LayoutParams hdrParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        Util.dp(this, 56));
        root.addView(header, hdrParams);

        // Кнопка назад
        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextColor(0xFFFFFFFF);
        back.setTextSize(30);
        back.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams backP =
                new android.widget.LinearLayout.LayoutParams(
                        Util.dp(this, 44), Util.dp(this, 44));
        header.addView(back, backP);
        back.setOnClickListener(v -> finish());

        // Заголовок
        TextView title = new TextView(this);
        title.setText("Инструкция");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        android.widget.LinearLayout.LayoutParams titleP =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleP.setMargins(Util.dp(this, 10), 0, 0, 0);
        header.addView(title, titleP);

        // WebView
        WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(false);
        wv.getSettings().setBuiltInZoomControls(true);
        wv.getSettings().setDisplayZoomControls(false);
        wv.getSettings().setSupportZoom(true);
        wv.setBackgroundColor(0xFFFFFFFF);
        android.widget.LinearLayout.LayoutParams wvParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        0, 1);
        root.addView(wv, wvParams);

        setContentView(root);

        // Загружаем HTML из ресурсов
        wv.loadUrl("file:///android_res/raw/help");
    }
}
