package com.zigit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Экран «Релизы»: готовые файлы из релизов репозитория.
 * В отличие от артефактов Actions, публичные релизы качаются без токена.
 */
public class ReleasesActivity extends Activity {

    private static final int REQ_INSTALL_PERM = 300;
    private static final int REQ_STORAGE = 301;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Asset> items = new ArrayList<>();

    private String repo;
    private AssetAdapter adapter;
    private TextView empty, progressText, titleView;
    private View progressRow;
    private ProgressBar progress;
    private boolean downloading;
    private boolean onlyApk = true;
    private List<Asset> all = new ArrayList<>();
    private File pendingApk;

    private static class Asset {
        String name;
        long size;
        int downloads;
        String url;
        String browserUrl;
        String releaseTag;
        String releaseName;
        String publishedAt;
        boolean prerelease;

        boolean isApk() {
            return name != null && name.toLowerCase().endsWith(".apk");
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_releases);

        repo = getIntent().getStringExtra("repo");
        titleView = findViewById(R.id.title);
        titleView.setText(repo);
        empty = findViewById(R.id.empty);
        progressRow = findViewById(R.id.progressRow);
        progressText = findViewById(R.id.progressText);
        progress = findViewById(R.id.progress);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.refreshBtn).setOnClickListener(v -> load());
        findViewById(R.id.filterBtn).setOnClickListener(v -> {
            onlyApk = !onlyApk;
            applyFilter();
            toast(onlyApk ? "Показаны только APK" : "Показаны все файлы");
        });

        ListView list = findViewById(R.id.list);
        adapter = new AssetAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> download(items.get(position), adapter));

        load();
    }

    private void load() {
        empty.setText("Загружаю релизы…");
        empty.setVisibility(View.VISIBLE);
        pool.execute(() -> {
            try {
                JSONArray arr = Api.array(Api.API + "/repos/" + repo
                        + "/releases?per_page=30", Store.token(this));
                final List<Asset> got = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject rel = arr.optJSONObject(i);
                    if (rel == null) continue;
                    String tag = rel.optString("tag_name", "");
                    String relName = rel.optString("name", "");
                    String published = rel.optString("published_at", "");
                    boolean pre = rel.optBoolean("prerelease", false);
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets == null) continue;
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject as = assets.optJSONObject(j);
                        if (as == null) continue;
                        Asset a = new Asset();
                        a.name = as.optString("name", "");
                        a.size = as.optLong("size", 0);
                        a.downloads = as.optInt("download_count", 0);
                        a.url = as.optString("url", "");
                        a.browserUrl = as.optString("browser_download_url", "");
                        a.releaseTag = tag;
                        a.releaseName = relName;
                        a.publishedAt = published;
                        a.prerelease = pre;
                        got.add(a);
                    }
                }
                ui.post(() -> {
                    if (isFinishing()) return;
                    all = got;
                    applyFilter();
                    if (all.isEmpty()) {
                        empty.setText("В релизах этого репозитория нет файлов");
                    }
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                ui.post(() -> {
                    if (isFinishing()) return;
                    empty.setText("Ошибка: " + msg);
                    empty.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void applyFilter() {
        items.clear();
        for (Asset a : all) {
            if (!onlyApk || a.isApk()) items.add(a);
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (all.isEmpty()) {
            // список ещё не загружен
        } else if (items.isEmpty()) {
            empty.setText(onlyApk
                    ? "В релизах нет файлов .apk.\n\nНажмите ⟳/фильтр, чтобы показать все файлы."
                    : "Файлов не найдено");
        }
        titleView.setText(repo + (all.isEmpty() ? "" : "  (" + items.size() + ")"));
    }

    private void download(final Asset a, final BaseAdapter ad) {
        if (downloading) {
            toast("Дождитесь завершения текущей загрузки");
            return;
        }
        final String downloadUrl;
        final String accept;
        if (a.browserUrl != null && !a.browserUrl.isEmpty()) {
            // публичная ссылка: работает без токена, редирект на подписанный URL
            downloadUrl = a.browserUrl;
            accept = "application/octet-stream";
        } else if (a.url != null && !a.url.isEmpty()) {
            // через API — обязательно octet-stream, иначе вернётся JSON
            downloadUrl = a.url;
            accept = "application/octet-stream";
        } else {
            toast("Нет ссылки на файл");
            return;
        }
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            toast("Разрешите доступ к файлам и повторите");
            return;
        }
        downloading = true;
        progressRow.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        progressText.setText(a.name + ": начинаю загрузку…");

        pool.execute(() -> {
            final File tmp = new File(getCacheDir(), "rel_" + Math.abs(a.url.hashCode()) + ".bin");
            try {
                Api.download(downloadUrl, Store.token(this), tmp, accept, (done, total) -> ui.post(() -> {
                    if (isFinishing()) return;
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    progress.setProgress(pct);
                    String t = (total > 0 ? pct + "%  " : "") + Util.humanSize(done)
                            + (total > 0 ? " / " + Util.humanSize(total) : "");
                    progressText.setText(a.name + ": " + t);
                }));
                ui.post(() -> progressText.setText(a.name + ": сохраняю в «Загрузки»…"));
                final String displayName = Util.safeName(a.name);
                final Util.Saved saved = Util.save(this, tmp, displayName, Util.mimeFor(displayName));
                File apk = null;
                if (a.isApk()) {
                    File dir = new File(getExternalFilesDir(null), FileProviderX.DIR_APK);
                    if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                    File out = new File(dir, displayName);
                    try (java.io.InputStream in = new java.io.FileInputStream(tmp);
                         java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    apk = out;
                }
                final File apkFinal = apk;
                ui.post(() -> {
                    if (isFinishing()) return;
                    downloading = false;
                    progressRow.setVisibility(View.GONE);
                    AlertDialog.Builder d = new AlertDialog.Builder(this)
                            .setTitle("Готово: " + a.name)
                            .setMessage("Сохранено: " + saved.publicPath
                                    + "\n\nВсе скачанные файлы: нижняя кнопка «Файлы».");
                    final File file = saved.appFile;
                    if (apkFinal != null) {
                        d.setPositiveButton("Установить APK", (dd, w) -> installApk(apkFinal));
                        d.setNeutralButton("Открыть файл", (dd, w) -> {
                            String err = Util.openFile(this, file);
                            if (err != null) toast(err);
                        });
                        d.setNegativeButton("Закрыть", null);
                    } else {
                        d.setPositiveButton("Открыть файл", (dd, w) -> {
                            String err = Util.openFile(this, file);
                            if (err != null) toast(err);
                        });
                        d.setNegativeButton("Закрыть", null);
                    }
                    d.show();
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing()) return;
                    downloading = false;
                    progressRow.setVisibility(View.GONE);
                    if (code == 401 || code == 403) {
                        TokenDialog.askForToken(this, "GitHub не отдал файл без авторизации: " + msg,
                                () -> download(a, ad));
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("Не удалось скачать")
                                .setMessage(msg)
                                .setPositiveButton("ОК", null)
                                .show();
                    }
                });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        });
    }

    private void installApk(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())), REQ_INSTALL_PERM);
            } catch (Exception e) {
                toast("Разрешите установку из этого источника в настройках");
            }
            return;
        }
        try {
            Util.installApk(this, apk);
        } catch (Exception e) {
            toast("Не удалось запустить установщик: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_INSTALL_PERM && pendingApk != null) {
            File f = pendingApk;
            pendingApk = null;
            if (getPackageManager().canRequestPackageInstalls()) installApk(f);
            else toast("Разрешение на установку не выдано");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private class AssetAdapter extends BaseAdapter {
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
                v = LayoutInflater.from(ReleasesActivity.this)
                        .inflate(R.layout.row_artifact, parent, false);
            }
            Asset a = items.get(position);
            TextView title = v.findViewById(R.id.title);
            TextView sub = v.findViewById(R.id.sub);
            TextView action = v.findViewById(R.id.action);

            title.setText(a.name);
            StringBuilder sb = new StringBuilder();
            sb.append(a.releaseTag);
            if (a.prerelease) sb.append(" (pre)");
            sb.append("  ·  ").append(Util.humanSize(a.size));
            if (a.publishedAt != null && !a.publishedAt.isEmpty()) {
                sb.append("  ·  ").append(Util.timeAgo(a.publishedAt));
            }
            if (a.downloads > 0) sb.append("\nскачиваний: ").append(a.downloads);
            sub.setText(sb.toString());

            action.setText(a.isApk() ? "Скачать APK" : "Скачать");
            action.setEnabled(!downloading);
            return v;
        }
    }
}
