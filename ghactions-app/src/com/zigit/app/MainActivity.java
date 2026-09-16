package com.zigit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Главный экран: вводим owner/repo, получаем запуски GitHub Actions
 * и список артефактов (готовых сборок) к каждому запуску.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_REPO = 100;
    private static final int REQ_STORAGE = 101;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Models.RunItem> all = new ArrayList<>();
    private final List<Models.RunItem> shown = new ArrayList<>();

    private EditText repoInput;
    private Button findBtn;
    private ProgressBar progress;
    private View progressRow;
    private TextView status, empty, tokenBanner;
    private ListView list;

    private RunAdapter adapter;
    private String currentRepo;
    private int seq = 0;
    private boolean onlyWithArtifacts = false;
    private int artifactsPending = 0;

    private static final int TAB_RUNS = 0, TAB_RELEASES = 1, TAB_REPOS = 2, TAB_FILES = 3;
    private TextView[] tabLabels;
    private View[] tabLines;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        repoInput = findViewById(R.id.repoInput);
        findBtn = findViewById(R.id.findBtn);
        progress = findViewById(R.id.progress);
        progressRow = findViewById(R.id.progressRow);
        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        tokenBanner = findViewById(R.id.tokenBanner);
        list = findViewById(R.id.list);
        tokenBanner.setOnClickListener(v -> TokenDialog.show(this, () -> {
            updateTokenBanner();
            if (currentRepo != null) find();
        }));

        adapter = new RunAdapter();
        list.setAdapter(adapter);

        findBtn.setOnClickListener(v -> {
            hideKeyboard();
            find();
        });
        repoInput.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                find();
                return true;
            }
            return false;
        });

        findViewById(R.id.myReposBtn).setOnClickListener(v ->
                startActivityForResult(new Intent(this, RepoPickerActivity.class), REQ_PICK_REPO));

        findViewById(R.id.sourceBtn).setOnClickListener(v -> downloadSourceZip());

        findViewById(R.id.releasesBtn).setOnClickListener(v -> {
            String repo = normalizeRepo(repoInput.getText().toString());
            if (repo == null) {
                toast("Сначала укажите репозиторий");
                return;
            }
            currentRepo = repo;
            Intent i = new Intent(this, ReleasesActivity.class);
            i.putExtra("repo", repo);
            startActivity(i);
        });

        findViewById(R.id.menuBtn).setOnClickListener(v -> showMenu(v));

        list.setOnItemClickListener((parent, view, position, id) -> {
            Models.RunItem r = shown.get(position);
            Intent i = new Intent(this, ArtifactsActivity.class);
            i.putExtra("repo", currentRepo);
            i.putExtra("runId", r.id);
            i.putExtra("title", r.title());
            i.putExtra("branch", r.headBranch);
            i.putExtra("number", r.runNumber);
            i.putExtra("created", r.createdAt);
            startActivity(i);
        });

        setupBottomNav();
        updateTokenBanner();

        String saved = Store.repo(this);
        if (!saved.isEmpty()) {
            repoInput.setText(saved);
            find();
        } else {
            showEmpty(getString(R.string.empty_runs));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTokenBanner();
        if (tabLabels != null) highlightTab(TAB_RUNS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    private void updateTokenBanner() {
        tokenBanner.setVisibility(Store.token(this).isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- поиск

    private String normalizeRepo(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        s = s.replace("https://", "").replace("http://", "");
        if (s.startsWith("github.com/")) s = s.substring("github.com/".length());
        if (s.startsWith("www.github.com/")) s = s.substring("www.github.com/".length());
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        String[] parts = s.split("/");
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null;
        return parts[0] + "/" + parts[1];
    }

    private void find() {
        String repo = normalizeRepo(repoInput.getText().toString());
        if (repo == null) {
            toast("Укажите репозиторий в формате owner/repo");
            return;
        }
        repoInput.setText(repo);
        Store.setRepo(this, repo);
        currentRepo = repo;

        final int mySeq = ++seq;
        all.clear();
        shown.clear();
        adapter.notifyDataSetChanged();
        artifactsPending = 0;
        showProgress("Загружаю запуски Actions…");

        pool.execute(() -> {
            try {
                String token = Store.token(this);
                int page = 1;
                int total = -1;
                while (true) {
                    if (mySeq != seq) return;
                    String url = Api.API + "/repos/" + currentRepo
                            + "/actions/runs?per_page=100&page=" + page;
                    JSONObject o = Api.json(url, token);
                    total = o.optInt("total_count", 0);
                    JSONArray arr = o.optJSONArray("workflow_runs");
                    if (arr == null || arr.length() == 0) break;
                    final List<Models.RunItem> got = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject ro = arr.optJSONObject(i);
                        if (ro != null) got.add(Models.RunItem.from(ro));
                    }
                    final int loaded = page * arr.length();
                    final int totalFinal = total;
                    ui.post(() -> {
                        if (mySeq != seq || isFinishing()) return;
                        all.addAll(got);
                        applyFilter();
                        status.setText("Загружено запусков: " + all.size() + " из " + totalFinal);
                    });
                    if (arr.length() < 100) break;
                    page++;
                }
                final int totalFinal = total;
                ui.post(() -> {
                    if (mySeq != seq || isFinishing()) return;
                    if (all.isEmpty()) {
                        hideProgress();
                        showEmpty("У репозитория " + currentRepo + " нет запусков GitHub Actions");
                        return;
                    }
                    showEmpty(null);
                    status.setText("Загружаю артефакты…");
                    loadArtifacts(mySeq, totalFinal);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (mySeq != seq || isFinishing()) return;
                    hideProgress();
                    if (all.isEmpty()) showEmpty("Ошибка: " + msg);
                    if (code == 401 || code == 403) {
                        updateTokenBanner();
                        TokenDialog.askForToken(this,
                                "GitHub не отдал данные без авторизации:\n" + msg
                                        + "\n\nДля приватных репозиториев и скачивания артефактов нужен токен.",
                                () -> find());
                    } else {
                        toast("Ошибка: " + msg);
                    }
                });
            }
        });
    }

    /** Подтягиваем список артефактов для каждого запуска (по 3 параллельно). */
    private void loadArtifacts(int mySeq, int totalRuns) {
        final List<Models.RunItem> snapshot = new ArrayList<>(all);
        artifactsPending = snapshot.size();
        final AtomicInteger remaining = new AtomicInteger(snapshot.size());
        final int[] doneCount = {0};
        for (final Models.RunItem r : snapshot) {
            pool.execute(() -> {
                try {
                    if (mySeq != seq) return;
                    JSONObject o = Api.json(Api.API + "/repos/" + currentRepo
                            + "/actions/runs/" + r.id + "/artifacts?per_page=100", Store.token(this));
                    final List<Models.ArtifactInfo> arts = Models.ArtifactInfo.list(o.optJSONArray("artifacts"));
                    ui.post(() -> {
                        if (mySeq != seq || isFinishing()) return;
                        r.artifacts = arts;
                        r.artifactsLoaded = true;
                        applyFilter();
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        if (mySeq != seq || isFinishing()) return;
                        r.artifactsLoaded = true;
                        r.artifactsError = true;
                        applyFilter();
                    });
                } finally {
                    final int left = remaining.decrementAndGet();
                    ui.post(() -> {
                        if (mySeq != seq || isFinishing()) return;
                        int done = snapshot.size() - left;
                        if (left > 0) {
                            status.setText("Загружаю артефакты… " + done + " / " + snapshot.size());
                        } else {
                            artifactsPending = 0;
                            hideProgress();
                            applyFilter();
                            int withArt = 0;
                            for (Models.RunItem it : all) if (it.artifactCount() > 0) withArt++;
                            status.setText("Готово: запусков " + all.size()
                                    + " (с артефактами " + withArt + "), " + totalRuns + " всего");
                            if (withArt == 0) {
                                toast("Артефактов не найдено. Возможно, в workflow нет шага upload-artifact");
                            }
                        }
                    });
                }
            });
        }
    }

    private void applyFilter() {
        shown.clear();
        for (Models.RunItem r : all) {
            if (onlyWithArtifacts) {
                if (r.artifactsLoaded && r.artifactCount() > 0) shown.add(r);
            } else {
                shown.add(r);
            }
        }
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------- источник

    private void downloadSourceZip() {
        String repo = normalizeRepo(repoInput.getText().toString());
        if (repo == null) {
            toast("Сначала укажите репозиторий");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < 29 && !hasStoragePermission()) {
            requestStorage();
            toast("Разрешите доступ к файлам и повторите");
            return;
        }
        final String name = Util.safeName(repo.replace('/', '-') + "-source.zip");
        showProgress("Скачиваю ZIP исходников…");
        new Thread(() -> {
            File tmp = new File(getCacheDir(), "src_" + System.currentTimeMillis() + ".zip");
            try {
                Api.download(Api.API + "/repos/" + repo + "/zipball", Store.token(this), tmp, (done, total) -> ui.post(() -> {
                    if (isFinishing()) return;
                    status.setText(total > 0
                            ? "Скачиваю ZIP исходников… " + (done * 100 / total) + "% • " + Util.humanSize(done) + " / " + Util.humanSize(total)
                            : "Скачиваю ZIP исходников… " + Util.humanSize(done));
                }));
                String dest = Util.copyToDownloads(this, tmp, name, "application/zip");
                ui.post(() -> {
                    hideProgress();
                    toast("Сохранено: " + dest);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    hideProgress();
                    toast("Ошибка загрузки: " + e.getMessage());
                });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }).start();
    }

    private boolean hasStoragePermission() {
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorage() {
        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
    }

    // ------------------------------------------------------------- меню/UI

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Обновить");
        m.getMenu().add(0, 2, 1, onlyWithArtifacts
                ? "Показать все запуски" : "Только запуски с артефактами");
        m.getMenu().add(0, 5, 2, "Скачанные файлы");
        m.getMenu().add(0, 3, 3, "Токен GitHub");
        m.getMenu().add(0, 4, 4, "О приложении и разработчике");
        m.setOnMenuItemClickListener((MenuItem item) -> {
            switch (item.getItemId()) {
                case 1:
                    if (currentRepo != null) find();
                    break;
                case 2:
                    onlyWithArtifacts = !onlyWithArtifacts;
                    applyFilter();
                    toast(onlyWithArtifacts ? "Фильтр: только с артефактами" : "Фильтр выключен");
                    break;
                case 3:
                    showTokenDialog();
                    break;
                case 4:
                    showAbout();
                    break;
                case 5:
                    startActivity(new Intent(this, DownloadsActivity.class));
                    break;
            }
            return true;
        });
        m.show();
    }

    private void showTokenDialog() {
        TokenDialog.show(this, () -> {
            updateTokenBanner();
            if (currentRepo != null) find();
        });
    }

    private void showAbout() {
        String ver = "2.2";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("ZI Git " + ver)
                .setMessage("Загрузчик готовых сборок из GitHub Actions.\n\n"
                        + "Разработчик: Захаревич Игорь\n"
                        + "E-mail: ziv@csl.by\n\n"
                        + "Как пользоваться:\n"
                        + "1. Введите owner/repo (например, zigorminsk-debug/PC-Tools).\n"
                        + "2. Приложение покажет запуски Actions и артефакты к ним.\n"
                        + "3. Нажмите запуск → «Скачать» у нужного артефакта.\n"
                        + "4. Файл сохранится в «Загрузки»; если внутри APK — установка в один тап.\n"
                        + "5. Все загруженные файлы всегда под рукой: нижняя кнопка «Файлы».\n\n"
                        + "Артефакты GitHub хранит 90 дней, потом удаляет — приложение помечает такие как «Истёк».")
                .setPositiveButton("Написать разработчику", (d, w) -> emailDeveloper())
                .setNeutralButton("Копировать e-mail", (d, w) -> copyEmail())
                .setNegativeButton("Закрыть", null)
                .show();
    }

    // ------------------------------------------------------------ навигация

    private void setupBottomNav() {
        tabLabels = new TextView[]{
                findViewById(R.id.navRunsLabel), findViewById(R.id.navReleasesLabel),
                findViewById(R.id.navReposLabel), findViewById(R.id.navFilesLabel)};
        tabLines = new View[]{
                findViewById(R.id.navRunsLine), findViewById(R.id.navReleasesLine),
                findViewById(R.id.navReposLine), findViewById(R.id.navFilesLine)};
        int[] ids = {R.id.navRuns, R.id.navReleases, R.id.navRepos, R.id.navFiles};
        for (int i = 0; i < ids.length; i++) {
            final int tab = i;
            findViewById(ids[i]).setOnClickListener(v -> onTab(tab));
        }
        highlightTab(TAB_RUNS);

        // подпись разработчика в шапке — тап открывает письмо
        findViewById(R.id.developerLine).setOnClickListener(v -> emailDeveloper());
    }

    /** Главный экран соответствует вкладке «Запуски», остальные открывают свои экраны. */
    private void onTab(int tab) {
        switch (tab) {
            case TAB_RUNS:
                highlightTab(TAB_RUNS);
                if (currentRepo != null) find();
                break;
            case TAB_RELEASES: {
                String repo = normalizeRepo(repoInput.getText().toString());
                if (repo == null) {
                    toast("Сначала укажите репозиторий");
                    return;
                }
                currentRepo = repo;
                Intent i = new Intent(this, ReleasesActivity.class);
                i.putExtra("repo", repo);
                startActivity(i);
                break;
            }
            case TAB_REPOS:
                startActivityForResult(new Intent(this, RepoPickerActivity.class), REQ_PICK_REPO);
                break;
            case TAB_FILES:
                startActivity(new Intent(this, DownloadsActivity.class));
                break;
        }
    }

    private void highlightTab(int tab) {
        for (int i = 0; i < tabLabels.length; i++) {
            boolean active = i == tab;
            tabLabels[i].setTextColor(getResources().getColor(active ? R.color.green : R.color.muted));
            tabLabels[i].setTypeface(null, active
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tabLines[i].setBackgroundColor(active ? getResources().getColor(R.color.green) : 0x00FFFFFF);
        }
    }

    // ------------------------------------------------------------- разработчик

    private void emailDeveloper() {
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse(
                    "mailto:ziv@csl.by?subject=" + android.net.Uri.encode("ZI Git")));
            startActivity(i);
        } catch (Exception e) {
            copyEmail();
        }
    }

    private void copyEmail() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("e-mail", "ziv@csl.by"));
            toast("Адрес ziv@csl.by скопирован");
        } catch (Exception e) {
            toast("Адрес разработчика: ziv@csl.by");
        }
    }

    private void showProgress(String text) {
        progressRow.setVisibility(View.VISIBLE);
        status.setText(text);
    }

    private void hideProgress() {
        progressRow.setVisibility(View.GONE);
    }

    private void showEmpty(String text) {
        if (text == null) {
            empty.setVisibility(View.GONE);
        } else {
            empty.setText(text);
            empty.setVisibility(View.VISIBLE);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(repoInput.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_REPO && res == RESULT_OK && data != null) {
            String repo = data.getStringExtra("repo");
            if (repo != null) {
                repoInput.setText(repo);
                find();
            }
        }
    }

    // --------------------------------------------------------------- адаптер

    private class RunAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return shown.get(position).id;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.row_run, parent, false);
            }
            Models.RunItem r = shown.get(position);

            TextView title = v.findViewById(R.id.title);
            TextView badge = v.findViewById(R.id.badge);
            TextView sub = v.findViewById(R.id.sub);
            TextView extra = v.findViewById(R.id.extra);

            title.setText(r.title());

            String label = Models.statusLabel(r.status, r.conclusion);
            badge.setText(label);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(Util.dp(MainActivity.this, 10));
            bg.setColor(getResources().getColor(Models.statusColorRes(r.status, r.conclusion)));
            int px = Util.dp(MainActivity.this, 8);
            badge.setPadding(px, px / 3, px, px / 3);
            badge.setBackground(bg);

            StringBuilder sb = new StringBuilder();
            if (r.runNumber > 0) sb.append('#').append(r.runNumber).append("  ·  ");
            sb.append(r.name);
            if (r.headBranch != null && !r.headBranch.isEmpty()) sb.append("  ·  ").append(r.headBranch);
            if (r.headSha != null && !r.headSha.isEmpty()) sb.append("  ·  ").append(r.headSha);
            sb.append("  ·  ").append(Util.timeAgo(r.createdAt));
            sub.setText(sb.toString());

            if (r.artifactsLoaded) {
                if (r.artifactCount() > 0) {
                    long bytes = 0;
                    for (Models.ArtifactInfo a : r.artifacts) bytes += a.size;
                    extra.setText("Артефактов: " + r.artifactCount() + "  (" + Util.humanSize(bytes) + ")  →");
                } else if (r.artifactsError) {
                    extra.setText("Не удалось получить артефакты");
                } else {
                    extra.setText("Артефактов нет");
                }
            } else {
                extra.setText("Загружаю артефакты…");
            }
            return v;
        }
    }
}
