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

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Экран одного запуска Actions: список артефактов и их скачивание.
 * Скачанный архив сохраняется в «Загрузки»; если внутри APK — предлагается установка.
 */
public class ArtifactsActivity extends Activity {

    private static final int REQ_INSTALL_PERM = 200;
    private static final int REQ_STORAGE = 201;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Models.ArtifactInfo> items = new ArrayList<>();

    private String repo;
    private long runId;
    private String runTitle;

    private ArtAdapter adapter;
    private TextView subtitle, empty, progressText, tokenBanner;
    private View progressRow, headerProgress;
    private ProgressBar progress;

    private boolean downloading;
    private File pendingApk;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_artifacts);

        repo = getIntent().getStringExtra("repo");
        runId = getIntent().getLongExtra("runId", 0);
        runTitle = getIntent().getStringExtra("title");
        String branch = getIntent().getStringExtra("branch");
        int number = getIntent().getIntExtra("number", 0);
        String created = getIntent().getStringExtra("created");

        ((TextView) findViewById(R.id.title)).setText(runTitle == null ? "Артефакты" : runTitle);
        subtitle = findViewById(R.id.subtitle);
        StringBuilder sb = new StringBuilder();
        if (number > 0) sb.append('#').append(number);
        if (branch != null && !branch.isEmpty()) sb.append(sb.length() > 0 ? "  ·  " : "").append(branch);
        if (created != null && !created.isEmpty()) sb.append(sb.length() > 0 ? "  ·  " : "").append(Util.dateTime(created));
        sb.append(sb.length() > 0 ? "  ·  " : "").append(repo);
        subtitle.setText(sb.toString());

        empty = findViewById(R.id.empty);
        tokenBanner = findViewById(R.id.tokenBanner);
        tokenBanner.setOnClickListener(v -> TokenDialog.show(this, () -> {
            updateTokenBanner();
            load();
        }));
        progressRow = findViewById(R.id.progressRow);
        progressText = findViewById(R.id.progressText);
        progress = findViewById(R.id.progress);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.refreshBtn).setOnClickListener(v -> load());

        ListView list = findViewById(R.id.list);
        adapter = new ArtAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Models.ArtifactInfo a = items.get(position);
            if (a.expired) {
                toast("Артефакт истёк и удалён GitHub");
                return;
            }
            startDownload(a, position);
        });

        updateTokenBanner();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTokenBanner();
    }

    private void updateTokenBanner() {
        tokenBanner.setVisibility(Store.token(this).isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void load() {
        empty.setText("Загружаю артефакты…");
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        pool.execute(() -> {
            try {
                JSONObject o = Api.json(Api.API + "/repos/" + repo + "/actions/runs/" + runId
                        + "/artifacts?per_page=100", Store.token(this));
                final List<Models.ArtifactInfo> list = Models.ArtifactInfo.list(o.optJSONArray("artifacts"));
                ui.post(() -> {
                    if (isFinishing()) return;
                    items.clear();
                    items.addAll(list);
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    if (items.isEmpty()) {
                        empty.setText("У этого запуска нет артефактов.\n\n"
                                + "Артефакты появляются, если в workflow есть шаг\n"
                                + "actions/upload-artifact\n\n"
                                + "Также GitHub удаляет артефакты через 90 дней.");
                    }
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing()) return;
                    empty.setText("Ошибка: " + msg);
                    empty.setVisibility(View.VISIBLE);
                    if (code == 401 || code == 403) {
                        updateTokenBanner();
                        TokenDialog.askForToken(this, "GitHub не отдал список артефактов без авторизации.", () -> load());
                    }
                });
            }
        });
    }

    private void startDownload(final Models.ArtifactInfo a, final int pos) {
        if (downloading) {
            toast("Дождитесь завершения текущей загрузки");
            return;
        }
        if (Build.VERSION.SDK_INT < 29 && !hasStoragePermission()) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            toast("Разрешите доступ к файлам и повторите");
            return;
        }
        downloading = true;
        progressRow.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        progressText.setText(a.name + ": начинаю загрузку…");

        pool.execute(() -> {
            final File tmp = new File(getCacheDir(), "art_" + a.id + ".zip");
            try {
                Api.download(a.url, Store.token(this), tmp, (done, total) -> ui.post(() -> {
                    if (isFinishing()) return;
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    String t = (total > 0 ? pct + "%  " : "") + Util.humanSize(done)
                            + (total > 0 ? " / " + Util.humanSize(total) : "");
                    a.uiState = t;
                    progress.setProgress(pct);
                    progressText.setText(a.name + ": " + t);
                    adapter.notifyDataSetChanged();
                }));

                if (Util.looksLikeJson(tmp)) {
                    throw new IOException("GitHub вернул JSON вместо архива. Проверьте токен.");
                }
                ui.post(() -> progressText.setText(a.name + ": извлекаю APK…"));
                File apkBase = getExternalFilesDir(null);
                if (apkBase == null) apkBase = getFilesDir();
                File apkDir = new File(apkBase, FileProviderX.DIR_APK);
                File apk = Util.extractFirstApk(tmp, apkDir);
                ui.post(() -> progressText.setText(a.name + ": сохраняю в «Загрузки»…"));
                final Util.Saved saved;
                if (apk != null) {
                    saved = Util.save(this, apk, apk.getName(), "application/octet-stream");
                } else if (Util.looksLikeZip(tmp)) {
                    saved = Util.save(this, tmp, Util.safeName(a.name) + ".zip", "application/zip");
                } else {
                    throw new IOException("Скачанный файл не архив и не APK (возможно, ошибка GitHub).");
                }
                final File apkFinal = apk;
                ui.post(() -> {
                    if (isFinishing()) return;
                    downloading = false;
                    a.uiState = null;
                    progressRow.setVisibility(View.GONE);
                    adapter.notifyDataSetChanged();
                    resultDialog(a, saved, apkFinal);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing()) return;
                    downloading = false;
                    a.uiState = null;
                    progressRow.setVisibility(View.GONE);
                    adapter.notifyDataSetChanged();
                    if (code == 401 || code == 403) {
                        updateTokenBanner();
                        TokenDialog.askForToken(this,
                                "Артефакты доступны только с авторизацией.\n" + msg,
                                () -> startDownload(a, pos));
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("Не удалось скачать")
                                .setMessage(msg + "\n\nЕсли артефакт старше 90 дней, GitHub удаляет его безвозвратно.")
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

    private void resultDialog(Models.ArtifactInfo a, Util.Saved saved, final File apk) {
        final File file = saved.appFile;
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle("Готово: " + a.name)
                .setMessage("Сохранено: " + saved.publicPath
                        + (apk != null ? "\n\nЭто готовый APK — можно ставить сразу, распаковывать не нужно."
                        : "\n\nAPK внутри архива не найден.")
                        + "\n\nВсе скачанные файлы: нижняя кнопка «Файлы».");
        if (apk != null) {
            d.setPositiveButton("Установить APK", (dd, w) -> installApk(apk));
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

    private boolean hasStoragePermission() {
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private class ArtAdapter extends BaseAdapter {
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
            return items.get(position).id;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(ArtifactsActivity.this)
                        .inflate(R.layout.row_artifact, parent, false);
            }
            Models.ArtifactInfo a = items.get(position);
            TextView title = v.findViewById(R.id.title);
            TextView sub = v.findViewById(R.id.sub);
            TextView action = v.findViewById(R.id.action);

            title.setText(a.name);
            String info = Util.humanSize(a.size) + "  ·  " + Util.dateTime(a.createdAt);
            if (a.downloadCount > 0) info += "  ·  скачиваний: " + a.downloadCount;
            if (a.expired) info += "\nИстёк: артефакт удалён GitHub";
            sub.setText(info);

            if (a.expired) {
                action.setText("Истёк");
                action.setEnabled(false);
            } else if (a.uiState != null) {
                action.setText(a.uiState);
                action.setEnabled(false);
            } else {
                action.setText("Скачать");
                action.setEnabled(true);
            }
            return v;
        }
    }
}
