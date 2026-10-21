package com.zigit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Экран «Очистка хранилища»: массовое удаление артефактов сборок и кэшей Actions.
 *
 * Нужен, когда GitHub отвечает «Artifact storage quota has been hit»:
 * у бесплатного плана общее хранилище Actions — 500 МБ на аккаунт, и старые
 * артефакты в любом репозитории занимают эту квоту.
 *
 * Два режима:
 *  • весь аккаунт — обходит доступные токену репозитории;
 *  • один репозиторий — открывается с extra "repo", тогда можно оставить
 *    артефакты N последних успешных запусков, а остальные удалить.
 */
public class CleanupActivity extends Activity {

    /** Ключ: чистим только этот репозиторий. */
    static final String EXTRA_REPO = "repo";
    /** Ключ: сколько последних успешных запусков оставить (по умолчанию 2). */
    static final String EXTRA_KEEP = "keep";

    /** Максимум репозиториев и страниц, чтобы не упереться в лимит запросов. */
    private static final int MAX_REPOS = 60;
    private static final int MAX_PAGES = 20;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Артефакт или кэш, привязанный к репозиторию. */
    private static class Unit {
        String repo;
        long id;
        long runId;          // для артефакта — запуск, который его создал
        String name;
        long size;
        String createdAt;
        boolean cache;
        boolean expired;
    }

    /** Что оставляем: id запусков и их подписи для диалога. */
    private static class Keep {
        final Set<Long> runIds = new HashSet<>();
        final List<String> labels = new ArrayList<>();
    }

    private final List<Unit> artifacts = new ArrayList<>();
    private final List<Unit> caches = new ArrayList<>();
    private final Map<String, String> repoErrors = new LinkedHashMap<>();

    private LinearLayout content;
    private TextView status;
    private LinearLayout progressRow;
    private TextView progressText;
    private ProgressBar progress;
    private boolean busy;
    private boolean scanned;

    /** null — чистим весь аккаунт; иначе только этот репозиторий. */
    private String onlyRepo;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_cleanup);

        String repo = getIntent().getStringExtra(EXTRA_REPO);
        onlyRepo = (repo == null || repo.trim().isEmpty()) ? null : repo.trim();

        content = findViewById(R.id.content);
        status = findViewById(R.id.status);
        progressRow = findViewById(R.id.progressRow);
        progressText = findViewById(R.id.progressText);
        progress = findViewById(R.id.progress);

        TextView sub = findViewById(R.id.subtitle);
        if (sub != null) {
            sub.setText(onlyRepo == null
                    ? "освобождаем квоту хранилища Actions"
                    : onlyRepo + " · можно оставить последние успешные сборки");
        }

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.refreshBtn).setOnClickListener(v -> {
            if (!busy) scan();
        });

        scan();
    }

    // ------------------------------------------------------------------ скан

    private void scan() {
        if (Store.token(this).isEmpty()) {
            TokenDialog.askForToken(this,
                    "Для очистки хранилища нужен токен: без него GitHub не покажет артефакты "
                            + "и не даст их удалить.\n\nПрава: classic — scope repo; "
                            + "fine-grained — Actions: Read and write.", () -> {
                        if (!isFinishing()) scan();
                    });
            return;
        }
        busy = true;
        scanned = false;
        artifacts.clear();
        caches.clear();
        repoErrors.clear();
        content.removeAllViews();
        setStatus("Считаю артефакты и кэши…");
        showProgress(true, onlyRepo == null ? "Готовлю список репозиториев…"
                : "Читаю " + onlyRepo + "…", 0);

        final String token = Store.token(this);
        pool.execute(() -> {
            final List<String> repos = new ArrayList<>();
            String fatal = null;

            if (onlyRepo != null) {
                repos.add(onlyRepo);
            } else {
                try {
                    String url = Api.API + "/user/repos?per_page=100"
                            + "&affiliation=owner,collaborator,organization_member&sort=pushed";
                    int pages = 0;
                    while (url != null && pages < MAX_PAGES && repos.size() < MAX_REPOS) {
                        Api.Response r = Api.get(url, token);
                        JSONArray arr = new JSONArray(r.body);
                        for (int i = 0; i < arr.length() && repos.size() < MAX_REPOS; i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o != null) repos.add(o.optString("full_name", ""));
                        }
                        url = arr.length() < 100 ? null : Api.nextPage(r);
                        pages++;
                    }
                } catch (Exception e) {
                    fatal = e.getMessage();
                }
            }

            if (fatal != null) {
                final String msg = fatal;
                ui.post(() -> {
                    busy = false;
                    showProgress(false, null, 0);
                    setStatus("Не удалось получить репозитории: " + msg);
                    render();
                });
                return;
            }

            for (int i = 0; i < repos.size(); i++) {
                final String repo = repos.get(i);
                final int no = i + 1;
                ui.post(() -> {
                    if (isFinishing()) return;
                    progressText.setText("Проверяю " + no + " из " + repos.size() + ": " + repo);
                    progress.setProgress(no * 100 / Math.max(1, repos.size()));
                });
                try {
                    loadArtifacts(repo, token);
                    loadCaches(repo, token);
                } catch (Exception e) {
                    repoErrors.put(repo, String.valueOf(e.getMessage()));
                }
            }

            ui.post(() -> {
                if (isFinishing()) return;
                busy = false;
                scanned = true;
                showProgress(false, null, 0);
                setStatus("");
                render();
            });
        });
    }

    private void loadArtifacts(String repo, String token) throws Exception {
        String url = Api.API + "/repos/" + repo + "/actions/artifacts?per_page=100";
        int pages = 0;
        while (url != null && pages < MAX_PAGES) {
            Api.Response r = Api.get(url, token);
            JSONObject o = new JSONObject(r.body);
            JSONArray arr = o.optJSONArray("artifacts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject a = arr.optJSONObject(i);
                    if (a == null) continue;
                    Unit u = new Unit();
                    u.repo = repo;
                    u.id = a.optLong("id");
                    u.name = a.optString("name", "artifact");
                    u.size = a.optLong("size_in_bytes", 0);
                    u.createdAt = a.optString("created_at", "");
                    u.expired = a.optBoolean("expired", false);
                    JSONObject run = a.optJSONObject("workflow_run");
                    if (run != null) u.runId = run.optLong("id", 0);
                    artifacts.add(u);
                }
            }
            url = Api.nextPage(r);
            pages++;
        }
    }

    private void loadCaches(String repo, String token) throws Exception {
        String url = Api.API + "/repos/" + repo + "/actions/caches?per_page=100";
        int pages = 0;
        while (url != null && pages < MAX_PAGES) {
            Api.Response r = Api.get(url, token);
            JSONObject o = new JSONObject(r.body);
            JSONArray arr = o.optJSONArray("actions_caches");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    Unit u = new Unit();
                    u.repo = repo;
                    u.id = c.optLong("id");
                    u.name = c.optString("key", "cache");
                    u.size = c.optLong("size_in_bytes", 0);
                    u.createdAt = c.optString("created_at", "");
                    u.cache = true;
                    caches.add(u);
                }
            }
            url = Api.nextPage(r);
            pages++;
        }
    }

    /**
     * Какие запуски оставляем: N последних успешных плюс все незавершённые
     * (чтобы не удалить артефакты сборки, которая идёт прямо сейчас).
     */
    private Keep keepLastSuccessful(String repo, String token, int keep) throws Exception {
        Keep k = new Keep();
        int successes = 0;
        int pages = 0;
        String url = Api.API + "/repos/" + repo + "/actions/runs?per_page=50";
        while (url != null && pages < 5 && successes < keep) {
            Api.Response r = Api.get(url, token);
            JSONObject o = new JSONObject(r.body);
            JSONArray arr = o.optJSONArray("workflow_runs");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject run = arr.optJSONObject(i);
                    if (run == null) continue;
                    long id = run.optLong("id");
                    String st = run.optString("status", "");
                    String conclusion = run.optString("conclusion", "");
                    if (!"completed".equals(st)) {
                        k.runIds.add(id);          // сборка ещё идёт — не трогаем
                        continue;
                    }
                    if ("success".equals(conclusion) && successes < keep) {
                        k.runIds.add(id);
                        successes++;
                        int no = run.optInt("run_number", 0);
                        String br = run.optString("head_branch", "");
                        k.labels.add((no > 0 ? "#" + no : "run " + id)
                                + (br.isEmpty() ? "" : " · " + br));
                    }
                }
            }
            url = Api.nextPage(r);
            pages++;
        }
        return k;
    }

    // ------------------------------------------------------------- отрисовка

    private void render() {
        content.removeAllViews();
        if (!scanned) return;

        long artBytes = 0, expiredBytes = 0;
        for (Unit u : artifacts) {
            artBytes += u.size;
            if (u.expired) expiredBytes += u.size;
        }
        long cacheBytes = 0;
        for (Unit u : caches) cacheBytes += u.size;

        String summary = "Артефакты сборок: " + artifacts.size() + " шт. · " + Util.humanSize(artBytes)
                + "\nКэши Actions: " + caches.size() + " шт. · " + Util.humanSize(cacheBytes)
                + "\nВсего можно освободить: " + Util.humanSize(artBytes + cacheBytes);
        if (expiredBytes > 0) {
            summary += "\nИз них истёкшие (GitHub их уже удалил): " + Util.humanSize(expiredBytes);
        }
        if (!repoErrors.isEmpty()) {
            summary += "\n\nНе удалось проверить репозиториев: " + repoErrors.size()
                    + " (нет прав у токена — это нормально).";
        }
        if (onlyRepo == null) {
            summary += "\n\nБесплатный план: 500 МБ общего хранилища Actions на аккаунт, "
                    + "артефакты и кэши любых репозиториев считаются вместе. Когда квота "
                    + "закончилась, GitHub отклоняет загрузку артефактов с ошибкой "
                    + "«Artifact storage quota has been hit» — очистка возвращает место.";
        }
        card("Сейчас занято", summary);

        if (onlyRepo != null) {
            // --- режим одного репозитория
            addAction("Оставить 2 последних успешных", () -> keepDialog(onlyRepo, 2));
            addAction("Оставить 1 последний успешный", () -> keepDialog(onlyRepo, 1));
            addAction("Оставить 3 последних успешных", () -> keepDialog(onlyRepo, 3));
            addSeparator("или удалить без сохранения:");
            addAction("Все артефакты этого репозитория", () ->
                    confirmDelete("Все артефакты " + onlyRepo,
                            allArtifactsOf(onlyRepo), false));
            addAction("Все кэши этого репозитория", () ->
                    confirmDelete("Все кэши " + onlyRepo, allCachesOf(onlyRepo), true));
            addAction("Артефакты старше 30 дней", () ->
                    confirmDelete("Артефакты старше 30 дней", filter(artifacts, 30, false), false));
            addAction("Истёкшие артефакты", () ->
                    confirmDelete("Истёкшие артефакты", filter(artifacts, 0, true), false));
        } else {
            // --- режим всего аккаунта
            addAction("Оставить по 2 последних успешных в каждом репозитории",
                    this::keepEverywhere);
            addSeparator("");
            addAction("Артефакты старше 7 дней", () ->
                    confirmDelete("Артефакты старше 7 дней", filter(artifacts, 7, false), false));
            addAction("Артефакты старше 30 дней", () ->
                    confirmDelete("Артефакты старше 30 дней", filter(artifacts, 30, false), false));
            addAction("Истёкшие артефакты (GitHub их всё равно удалит)", () ->
                    confirmDelete("Истёкшие артефакты", filter(artifacts, 0, true), false));
            addAction("Все артефакты сборок", () ->
                    confirmDelete("Все артефакты сборок", new ArrayList<>(artifacts), false));
            addAction("Все кэши Actions", () ->
                    confirmDelete("Все кэши Actions", new ArrayList<>(caches), true));
        }

        if (!repoErrors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Map.Entry<String, String> e : repoErrors.entrySet()) {
                if (shown++ >= 10) {
                    sb.append("…\n");
                    break;
                }
                sb.append("• ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            card("Репозитории без доступа", sb.toString().trim());
        }
    }

    private List<Unit> allArtifactsOf(String repo) {
        List<Unit> out = new ArrayList<>();
        for (Unit u : artifacts) if (repo.equals(u.repo)) out.add(u);
        return out;
    }

    private List<Unit> allCachesOf(String repo) {
        List<Unit> out = new ArrayList<>();
        for (Unit u : caches) if (repo.equals(u.repo)) out.add(u);
        return out;
    }

    /** Артефакты старше N дней (days > 0) либо истёкшие (expiredOnly). */
    private List<Unit> filter(List<Unit> from, int days, boolean expiredOnly) {
        List<Unit> out = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Unit u : from) {
            if (expiredOnly) {
                if (u.expired) out.add(u);
                continue;
            }
            if (days <= 0) {
                out.add(u);
                continue;
            }
            try {
                OffsetDateTime t = OffsetDateTime.parse(u.createdAt);
                if (t.plusDays(days).isBefore(now)) out.add(u);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // --------------------------------------------- «оставить последние успешные

    /** Диалог выбора количества сохраняемых успешных сборок для одного репозитория. */
    private void keepDialog(String repo, int keep) {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }
        busy = true;
        showProgress(true, "Смотрю запуски " + repo + "…", 30);
        final String token = Store.token(this);
        pool.execute(() -> {
            Keep keepSet;
            String error = null;
            try {
                keepSet = keepLastSuccessful(repo, token, keep);
            } catch (Exception e) {
                keepSet = new Keep();
                error = e.getMessage();
            }
            final Keep fKeep = keepSet;
            final String fError = error;

            List<Unit> remove = new ArrayList<>();
            long bytes = 0;
            for (Unit u : artifacts) {
                if (!repo.equals(u.repo)) continue;
                if (u.runId == 0) continue;              // без привязки к запуску не трогаем
                if (fKeep.runIds.contains(u.runId)) continue;
                remove.add(u);
                bytes += u.size;
            }
            final List<Unit> fRemove = remove;
            final long fBytes = bytes;

            ui.post(() -> {
                if (isFinishing()) return;
                busy = false;
                showProgress(false, null, 0);
                if (fError != null && fKeep.labels.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle("Не удалось прочитать запуски")
                            .setMessage(fError + "\n\n" + hintFor(errorCode(fError)))
                            .setPositiveButton("ОК", null)
                            .show();
                    return;
                }
                if (fRemove.isEmpty()) {
                    toast("Нечего удалять: в " + repo + " осталось только " + keep
                            + " успешн. запуск(ов) с артефактами");
                    return;
                }
                String kept = fKeep.labels.isEmpty() ? "—"
                        : android.text.TextUtils.join(", ", fKeep.labels);
                new AlertDialog.Builder(this)
                        .setTitle("Оставить " + keep + " последних успешных?")
                        .setMessage(repo
                                + "\n\nОстанутся артефакты запусков: " + kept
                                + "\n(сборки, которые идут сейчас, тоже не трогаем)"
                                + "\n\nБудет удалено: " + fRemove.size() + " артефакт(ов) · "
                                + Util.humanSize(fBytes)
                                + "\n\nУдаление необратимо.")
                        .setPositiveButton("Удалить остальные", (d, w) -> deleteAll(fRemove, false))
                        .setNegativeButton("Отмена", null)
                        .show();
            });
        });
    }

    /** То же, но по всем репозиториям аккаунта: в каждом оставляем 2 последних успешных. */
    private void keepEverywhere() {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }
        final Set<String> repos = new HashSet<>();
        for (Unit u : artifacts) repos.add(u.repo);
        if (repos.isEmpty()) {
            toast("Артефактов нет");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Оставить по 2 последних успешных?")
                .setMessage("В каждом репозитории (их " + repos.size() + ") останутся артефакты "
                        + "двух последних успешных сборок, остальные будут удалены.\n\n"
                        + "Идущие сборки не трогаем. Удаление необратимо.")
                .setPositiveButton("Продолжить", (d, w) -> doKeepEverywhere(repos))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void doKeepEverywhere(final Set<String> repos) {
        busy = true;
        content.setAlpha(0.5f);
        showProgress(true, "Считаю, что оставить…", 0);

        final String token = Store.token(this);
        pool.execute(() -> {
            final List<Unit> remove = new ArrayList<>();
            long bytes = 0;
            int no = 0;
            for (String repo : repos) {
                no++;
                final int step = no;
                final int total = repos.size();
                ui.post(() -> {
                    if (isFinishing()) return;
                    progressText.setText("Смотрю запуски " + step + " из " + total + ": " + repo);
                    progress.setProgress(step * 100 / Math.max(1, total));
                });
                Keep k;
                try {
                    k = keepLastSuccessful(repo, token, 2);
                } catch (Exception e) {
                    continue;                        // нет доступа — этот репозиторий пропускаем
                }
                for (Unit u : artifacts) {
                    if (!repo.equals(u.repo)) continue;
                    if (u.runId == 0) continue;
                    if (k.runIds.contains(u.runId)) continue;
                    remove.add(u);
                    bytes += u.size;
                }
            }
            final List<Unit> fRemove = remove;
            final long fBytes = bytes;
            ui.post(() -> {
                if (isFinishing()) return;
                busy = false;
                content.setAlpha(1f);
                showProgress(false, null, 0);
                if (fRemove.isEmpty()) {
                    toast("Нечего удалять: везде осталось по 2 успешных сборки");
                    render();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Удалить " + fRemove.size() + " артефакт(ов)?")
                        .setMessage("Будет освобождено " + Util.humanSize(fBytes)
                                + ".\nОстанутся артефакты двух последних успешных сборок "
                                + "в каждом репозитории.\n\nУдаление необратимо.")
                        .setPositiveButton("Удалить", (d, w) -> deleteAll(fRemove, false))
                        .setNegativeButton("Отмена", () -> render())
                        .show();
            });
        });
    }

    private int errorCode(String message) {
        if (message == null) return 0;
        if (message.contains("403")) return 403;
        if (message.contains("404")) return 404;
        return 0;
    }

    private String hintFor(int code) {
        if (code == 403 || code == 404) {
            return "Чаще всего у токена нет прав на этот репозиторий. Нужны: classic — scope "
                    + "repo; fine-grained — Actions: Read and write и доступ к репозиторию.";
        }
        return "Проверьте соединение и токен: ⋮ → «Токен GitHub».";
    }

    // ------------------------------------------------------------- действия

    private void addSeparator(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(getResources().getColor(R.color.muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(this, 14);
        t.setLayoutParams(lp);
        content.addView(t);
    }

    private void addAction(String title, final Runnable onClick) {
        TextView btn = new TextView(this);
        btn.setText(title);
        btn.setTextSize(13);
        btn.setTextColor(getResources().getColor(R.color.text));
        btn.setBackgroundResource(R.drawable.bg_button_secondary);
        int pad = Util.dp(this, 12);
        btn.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(this, 8);
        btn.setLayoutParams(lp);
        btn.setEnabled(!busy);
        btn.setOnClickListener(v -> onClick.run());
        content.addView(btn);
    }

    /** Кнопка с количеством и объёмом — для «удалить N артефактов». */
    private void addAction(String title, final List<Unit> items, final boolean asCache) {
        long bytes = 0;
        for (Unit u : items) bytes += u.size;
        addAction(title + "  ·  " + items.size() + " шт. · " + Util.humanSize(bytes),
                () -> confirmDelete(title, items, asCache));
    }

    private void confirmDelete(String title, final List<Unit> items, final boolean asCache) {
        if (items.isEmpty()) {
            toast("Нечего удалять");
            return;
        }
        long bytes = 0;
        for (Unit u : items) bytes += u.size;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Будет удалено с GitHub: " + items.size() + " шт. · "
                        + Util.humanSize(bytes) + "\nРепозиториев задействовано: " + countRepos(items)
                        + "\n\nДействие необратимо: " + (asCache
                        ? "кэши придётся создать заново при следующей сборке."
                        : "сборки из этих артефактов больше не скачать."))
                .setPositiveButton("Удалить", (d, w) -> deleteAll(items, asCache))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private int countRepos(List<Unit> items) {
        Set<String> set = new HashSet<>();
        for (Unit u : items) set.add(u.repo);
        return set.size();
    }

    // ------------------------------------------------------------- удаление

    private void deleteAll(final List<Unit> items, final boolean asCache) {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return;
        }
        busy = true;
        content.setAlpha(0.5f);
        showProgress(true, "Готовлю удаление…", 0);

        final String token = Store.token(this);
        pool.execute(() -> {
            int deleted = 0;
            long freed = 0;
            int denied = 0;
            String firstError = null;

            for (int i = 0; i < items.size(); i++) {
                Unit u = items.get(i);
                final int no = i + 1;
                final String caption = (asCache ? "Удаляю кэши: " : "Удаляю артефакты: ")
                        + no + " из " + items.size() + " · " + u.repo;
                final int pct = no * 100 / Math.max(1, items.size());
                ui.post(() -> {
                    if (isFinishing()) return;
                    progressText.setText(caption);
                    progress.setProgress(pct);
                });
                try {
                    String url = asCache
                            ? Api.API + "/repos/" + u.repo + "/actions/caches/" + u.id
                            : Api.API + "/repos/" + u.repo + "/actions/artifacts/" + u.id;
                    Api.delete(url, token);
                    deleted++;
                    freed += u.size;
                } catch (Exception e) {
                    int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                    if (code == 403 || code == 404) denied++;
                    if (firstError == null) firstError = (u.repo + ": " + e.getMessage());
                }
            }

            final int fDeleted = deleted;
            final long fFreed = freed;
            final int fDenied = denied;
            final String fError = firstError;
            ui.post(() -> {
                if (isFinishing()) return;
                busy = false;
                content.setAlpha(1f);
                showProgress(false, null, 0);
                String msg = "Удалено: " + fDeleted + " шт. · освобождено " + Util.humanSize(fFreed);
                if (fDenied > 0) {
                    msg += "\nОтказано в доступе: " + fDenied
                            + " (нужны права Actions: Read and write)";
                }
                if (fError != null && fDeleted == 0) msg += "\n\nПричина: " + fError;
                new AlertDialog.Builder(this)
                        .setTitle("Очистка завершена")
                        .setMessage(msg)
                        .setPositiveButton("Обновить данные", (d, w) -> scan())
                        .setNegativeButton("Закрыть", null)
                        .show();
            });
        });
    }

    // ------------------------------------------------------------- утилиты UI

    private void card(String title, String body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_card);
        int pad = Util.dp(this, 14);
        box.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Util.dp(this, 10);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(getResources().getColor(R.color.text));
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(t);

        TextView b = new TextView(this);
        b.setText(body);
        b.setTextSize(13);
        b.setTextColor(getResources().getColor(R.color.muted));
        b.setPadding(0, Util.dp(this, 6), 0, 0);
        box.addView(b);

        content.addView(box);
    }

    private void setStatus(String text) {
        if (text == null || text.isEmpty()) {
            status.setVisibility(View.GONE);
        } else {
            status.setText(text);
            status.setVisibility(View.VISIBLE);
        }
    }

    private void showProgress(boolean show, String text, int pct) {
        progressRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        if (text != null) progressText.setText(text);
        progress.setProgress(pct);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /** Открыть очистку конкретного репозитория (используется другими экранами). */
    static void openRepo(Activity a, String repo) {
        Intent i = new Intent(a, CleanupActivity.class);
        i.putExtra(EXTRA_REPO, repo);
        a.startActivity(i);
    }
}
