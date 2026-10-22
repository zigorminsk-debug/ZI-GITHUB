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
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
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
        findViewById(R.id.logsBtn).setOnClickListener(v -> openLogs());
        findViewById(R.id.menuBtn).setOnClickListener(this::showMenu);

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

        // удержание строки — действия: скачать / удалить с GitHub
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            artifactActions(items.get(position));
            return true;
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

    // ------------------------------------------------------- удаление с GitHub

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Обновить список");
        m.getMenu().add(0, 2, 1, items.isEmpty()
                ? "Удалить все артефакты"
                : "🗑 Удалить все артефакты (" + items.size() + ")");
        m.getMenu().add(0, 3, 2, "💾 Хранилище этого репозитория");
        m.getMenu().add(0, 4, 3, "🧹 Очистить репозиторий, оставив 2 успешных");
        m.getMenu().add(0, 5, 4, "🧹 Очистка хранилища аккаунта");
        m.setOnMenuItemClickListener((MenuItem item) -> {
            switch (item.getItemId()) {
                case 1:
                    load();
                    break;
                case 2:
                    deleteAll();
                    break;
                case 3: {
                    Intent i = new Intent(this, StorageActivity.class);
                    i.putExtra("repo", repo);
                    startActivity(i);
                    break;
                }
                case 4:
                    CleanupActivity.openRepo(this, repo);
                    break;
                case 5:
                    startActivity(new Intent(this, CleanupActivity.class));
                    break;
            }
            return true;
        });
        m.show();
    }

    /** Меню по удержанию строки. */
    private void artifactActions(final Models.ArtifactInfo a) {
        final List<String> actions = new ArrayList<>();
        if (!a.expired) actions.add("Скачать");
        actions.add("🗑 Удалить артефакт");
        actions.add("Удалить все артефакты запуска");

        new AlertDialog.Builder(this)
                .setTitle(a.name)
                .setItems(actions.toArray(new String[0]), (d, which) -> {
                    String action = actions.get(which);
                    if ("Скачать".equals(action)) {
                        startDownload(a, items.indexOf(a));
                    } else if (action.startsWith("Удалить артефакт")) {
                        deleteOne(a);
                    } else {
                        deleteAll();
                    }
                })
                .show();
    }

    private void deleteOne(final Models.ArtifactInfo a) {
        final List<Models.ArtifactInfo> one = new ArrayList<>();
        one.add(a);
        GithubDelete.confirmArtifacts(this, repo, one, changed -> {
            if (!changed || isFinishing()) return;
            items.remove(a);
            adapter.notifyDataSetChanged();
            updateEmptyState();
        });
    }

    private void deleteAll() {
        if (items.isEmpty()) {
            toast("Артефактов нет");
            return;
        }
        GithubDelete.confirmArtifacts(this, repo, new ArrayList<>(items), changed -> {
            if (!changed || isFinishing()) return;
            items.clear();
            adapter.notifyDataSetChanged();
            updateEmptyState();
            load();   // сверяемся с GitHub: что-то могло не удалиться
        });
    }

    private void updateEmptyState() {
        if (items.isEmpty()) {
            empty.setText("Артефактов нет.\n\n"
                    + "Удалённые вручную сборки GitHub не восстанавливает.");
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
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
                    if (isFinishing() || isDestroyed()) return;
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    String t = (total > 0 ? pct + "%  " : "") + Util.humanSize(done)
                            + (total > 0 ? " / " + Util.humanSize(total) : "");
                    a.uiState = t;
                    progress.setProgress(pct);
                    progressText.setText(a.name + ": " + t);
                    adapter.notifyDataSetChanged();
                }));

                ui.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressText.setText(a.name + ": извлекаю APK…");
                    }
                });
                // GitHub отдаёт артефакты только zip-архивом. Достаём из него APK и сохраняем
                // ГОТОВЫЙ .apk (архив в «Загрузки» не кладём). Если APK внутри нет — сохраняем
                // сам архив, чтобы артефакт всё равно можно было скачать.
                File apkBase = getExternalFilesDir(null);
                if (apkBase == null) apkBase = getFilesDir();
                File apkDir = new File(apkBase, FileProviderX.DIR_APK);

                File extractedApk = null;
                String problemText = null;
                try {
                    File extracted = ZipExtract.apkFrom(tmp, apkDir);
                    String problem = Util.checkApk(extracted);
                    if (problem == null) {
                        extractedApk = extracted;
                    } else {
                        problemText = problem;
                    }
                } catch (Exception noApk) {
                    // архива с APK внутри нет — это нормально для артефактов с jar/бинарниками
                    problemText = noApk.getMessage();
                }
                // final-копии: их захватывают лямбды ниже
                final File apk = extractedApk;
                final String apkProblem = problemText;

                ui.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressText.setText(a.name + ": сохраняю "
                                + (apk != null ? "APK…" : "архив…"));
                    }
                });

                final Util.Saved saved;
                final File apkFinal;
                if (apk != null) {
                    // единственный файл в «Загрузках» — готовый APK, ставить можно сразу
                    saved = Util.save(this, apk, Util.apkFileName(a.name),
                            "application/vnd.android.package-archive");
                    // дальше работаем с копией в каталоге приложения, а распакованный
                    // дубликат убираем — в «Файлах» не должно быть двух одинаковых APK
                    apkFinal = saved.appFile;
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                } else {
                    saved = Util.save(this, tmp, Util.archiveFileName(a.name), "application/zip");
                    apkFinal = null;
                }
                // почему не удалось достать APK (если не удалось)
                final String apkNote = apk == null && apkProblem != null ? apkProblem : null;
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    downloading = false;
                    a.uiState = null;
                    progressRow.setVisibility(View.GONE);
                    adapter.notifyDataSetChanged();
                    try {
                        resultDialog(a, saved, apkFinal, apkNote);
                    } catch (Exception ignored) {
                        toast("Сохранено: " + saved.publicPath);
                    }
                });
            } catch (Exception e) {
                String detail = e.getMessage();
                if (detail == null || detail.isEmpty()) detail = e.getClass().getSimpleName();
                final String msg = detail;
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
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
                        try {
                            new AlertDialog.Builder(this)
                                    .setTitle("Не удалось скачать / извлечь")
                                    .setMessage(msg)
                                    .setPositiveButton("ОК", null)
                                    .show();
                        } catch (Exception ignored) {
                            toast(msg);
                        }
                    }
                });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        });
    }

    private void resultDialog(Models.ArtifactInfo a, Util.Saved saved, final File apk,
                              String apkNote) {
        final File file = saved.appFile;
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle("Готово: " + a.name)
                .setMessage("Сохранено: " + saved.publicPath
                        + (apk != null
                        ? "\n\nЭто готовый APK (не архив) — можно ставить сразу."
                        + "\nРазмер: " + Util.humanSize(apk.length())
                        + "  ·  SHA-256: " + Util.sha256Short(apk)
                        + "\n\nЕсли установщик пишет «пакет недействителен», на телефоне "
                        + "стоит сборка с другой подписью или с большим versionCode: "
                        + "удалите старое приложение и установите это."
                        : "\n\nГотового APK внутри артефакта нет"
                        + (apkNote == null || apkNote.isEmpty() ? "" : " (" + firstLine(apkNote) + ")")
                        + ", поэтому сохранён сам архив.")
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

    /** Первая строка сообщения — чтобы не раздувать диалог. */
    private static String firstLine(String text) {
        if (text == null) return "";
        int i = text.indexOf('\n');
        return i < 0 ? text : text.substring(0, i);
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

    private void openLogs() {
        Intent i = new Intent(this, LogsActivity.class);
        i.putExtra("repo", repo);
        i.putExtra("runId", runId);
        i.putExtra("title", runTitle);
        String branch = getIntent().getStringExtra("branch");
        if (branch != null) i.putExtra("branch", branch);
        int number = getIntent().getIntExtra("number", 0);
        i.putExtra("number", number);
        startActivity(i);
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
