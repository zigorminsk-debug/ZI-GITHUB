package com.zigit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Экран «Скачанные файлы»: всё, что ZI Git загрузил из GitHub.
 * Тап — открыть файл во внешнем приложении, удержание — дополнительно установить / поделиться / удалить.
 */
public class DownloadsActivity extends Activity {

    private final List<File> items = new ArrayList<>();
    private FileAdapter adapter;
    private TextView empty, titleView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_downloads);

        titleView = findViewById(R.id.title);
        empty = findViewById(R.id.empty);
        ListView list = findViewById(R.id.list);
        adapter = new FileAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.refreshBtn).setOnClickListener(v -> load());

        list.setOnItemClickListener((parent, view, position, id) -> open(items.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            actionsDialog(items.get(position));
            return true;
        });

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        items.clear();
        List<File> list = new ArrayList<>();
        addFiles(list, Util.appDownloadDir(this));
        File apkDir = getExternalFilesDir(null);
        if (apkDir != null) addFiles(list, new File(apkDir, FileProviderX.DIR_APK));
        java.util.Map<String, File> uniq = new java.util.LinkedHashMap<>();
        for (File f : list) {
            File prev = uniq.get(f.getName());
            if (prev == null || f.lastModified() > prev.lastModified()) uniq.put(f.getName(), f);
        }
        list = new ArrayList<>(uniq.values());
        list.sort(Comparator.comparingLong(File::lastModified).reversed());
        items.addAll(list);
        adapter.notifyDataSetChanged();
        titleView.setText(getString(R.string.downloads_title) + " (" + items.size() + ")");
        if (items.isEmpty()) {
            empty.setText("Пока ничего не скачано.\n\n"
                    + "Откройте запуск GitHub Actions → артефакт → «Скачать»,\n"
                    + "или скачайте APK со экрана «Релизы».");
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    private void open(File f) {
        if (f.getName().toLowerCase().endsWith(".apk")) {
            actionsDialog(f);
            return;
        }
        String err = Util.openFile(this, f);
        if (err != null) toast(err);
    }

    private void actionsDialog(final File f) {
        final boolean apk = f.getName().toLowerCase().endsWith(".apk");
        final List<String> actions = new ArrayList<>();
        actions.add("Открыть");
        if (apk) actions.add("Установить");
        actions.add("Поделиться");
        actions.add("Удалить");

        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(actions.toArray(new String[0]), (d, which) -> {
                    String action = actions.get(which);
                    switch (action) {
                        case "Открыть": {
                            String err = Util.openFile(this, f);
                            if (err != null) toast(err);
                            break;
                        }
                        case "Установить":
                            install(f);
                            break;
                        case "Поделиться": {
                            String err = Util.shareFile(this, f);
                            if (err != null) toast(err);
                            break;
                        }
                        case "Удалить":
                            new AlertDialog.Builder(this)
                                    .setTitle("Удалить файл?")
                                    .setMessage(f.getName() + "\n\nКопия в папке «Загрузки» останется.")
                                    .setPositiveButton("Удалить", (dd, w) -> {
                                        //noinspection ResultOfMethodCallIgnored
                                        f.delete();
                                        load();
                                    })
                                    .setNegativeButton("Отмена", null)
                                    .show();
                            break;
                    }
                })
                .show();
    }

    private void install(File apk) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            toast("Разрешите установку из этого источника и повторите");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            Util.installApk(this, apk);
        } catch (Exception e) {
            toast("Не удалось запустить установщик: " + e.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private class FileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(DownloadsActivity.this)
                        .inflate(R.layout.row_file, parent, false);
            }
            File f = items.get(position);
            ((TextView) v.findViewById(R.id.title)).setText(f.getName());
            String sub = Util.humanSize(f.length()) + "  ·  " + Util.formatDate(f.lastModified());
            boolean apk = f.getName().toLowerCase().endsWith(".apk");
            ((TextView) v.findViewById(R.id.sub)).setText(sub);
            TextView action = v.findViewById(R.id.action);
            action.setText(apk ? "Установить" : "Открыть");
            return v;
        }
    }
}
