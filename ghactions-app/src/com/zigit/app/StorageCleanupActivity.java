package com.zigit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Управление артефактами и кэшами GitHub Actions выбранного репозитория. */
public class StorageCleanupActivity extends Activity {

    private static final int PAGE_SIZE = 100;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String repo;
    private TextView subtitle;
    private TextView summary;
    private TextView status;
    private TextView tokenBanner;
    private View refreshButton;
    private View progressRow;
    private ProgressBar progress;
    private Button keepTwoButton;
    private Button keepOneButton;
    private Button keepThreeButton;
    private Button deleteAllArtifactsButton;
    private Button deleteAllCachesButton;
    private Button deleteOlderButton;
    private Button deleteExpiredButton;

    private List<Models.ArtifactInfo> artifacts = new ArrayList<>();
    private List<ActionCache> caches = new ArrayList<>();
    private boolean snapshotLoaded;
    private boolean busy;
    private int loadSequence;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_storage_cleanup);

        repo = getIntent().getStringExtra("repo");
        ((TextView) findViewById(R.id.title)).setText("Очистка хранилища");
        subtitle = findViewById(R.id.subtitle);
        subtitle.setText(repo == null ? "" : repo);
        summary = findViewById(R.id.summary);
        status = findViewById(R.id.status);
        tokenBanner = findViewById(R.id.tokenBanner);
        progressRow = findViewById(R.id.progressRow);
        progress = findViewById(R.id.progress);
        refreshButton = findViewById(R.id.refreshBtn);

        keepTwoButton = findViewById(R.id.keepTwoButton);
        keepOneButton = findViewById(R.id.keepOneButton);
        keepThreeButton = findViewById(R.id.keepThreeButton);
        deleteAllArtifactsButton = findViewById(R.id.deleteAllArtifactsButton);
        deleteAllCachesButton = findViewById(R.id.deleteAllCachesButton);
        deleteOlderButton = findViewById(R.id.deleteOlderButton);
        deleteExpiredButton = findViewById(R.id.deleteExpiredButton);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        refreshButton.setOnClickListener(v -> loadSummary());
        tokenBanner.setOnClickListener(v -> TokenDialog.show(this, () -> {
            updateTokenBanner();
            loadSummary();
        }));

        keepTwoButton.setOnClickListener(v -> prepareKeepLastSuccessful(2));
        keepOneButton.setOnClickListener(v -> prepareKeepLastSuccessful(1));
        keepThreeButton.setOnClickListener(v -> prepareKeepLastSuccessful(3));
        deleteAllArtifactsButton.setOnClickListener(v -> deleteAllArtifacts());
        deleteAllCachesButton.setOnClickListener(v -> deleteAllCaches());
        deleteOlderButton.setOnClickListener(v -> deleteOlderArtifacts());
        deleteExpiredButton.setOnClickListener(v -> deleteExpiredArtifacts());

        updateTokenBanner();
        updateButtonState();
        if (!validRepo(repo)) {
            subtitle.setText("Сначала выберите репозиторий");
            summary.setText("Откройте очистку хранилища из меню главного экрана после выбора owner/repo.");
            return;
        }
        loadSummary();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTokenBanner();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private static boolean validRepo(String value) {
        if (value == null) return false;
        String[] parts = value.split("/", -1);
        return parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()
                && !value.contains(" ");
    }

    private void updateTokenBanner() {
        if (tokenBanner == null) return;
        tokenBanner.setVisibility(Store.token(this).isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateButtonState() {
        boolean enabled = snapshotLoaded && !busy;
        if (keepTwoButton != null) keepTwoButton.setEnabled(enabled);
        if (keepOneButton != null) keepOneButton.setEnabled(enabled);
        if (keepThreeButton != null) keepThreeButton.setEnabled(enabled);
        if (deleteAllArtifactsButton != null) deleteAllArtifactsButton.setEnabled(enabled);
        if (deleteAllCachesButton != null) deleteAllCachesButton.setEnabled(enabled);
        if (deleteOlderButton != null) deleteOlderButton.setEnabled(enabled);
        if (deleteExpiredButton != null) deleteExpiredButton.setEnabled(enabled);
        if (refreshButton != null) refreshButton.setEnabled(!busy);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        if (message == null || message.isEmpty()) {
            status.setVisibility(View.GONE);
            progressRow.setVisibility(value ? View.VISIBLE : View.GONE);
        } else {
            status.setText(message);
            status.setVisibility(View.VISIBLE);
            progressRow.setVisibility(View.VISIBLE);
        }
        updateButtonState();
    }

    /** Загружает все страницы с сервера, чтобы сводка и очистка охватывали весь репозиторий. */
    private void loadSummary() {
        if (busy || !validRepo(repo)) return;
        final int sequence = ++loadSequence;
        final String token = Store.token(this);
        setBusy(true, "Загружаю данные GitHub Actions…");
        if (!snapshotLoaded) summary.setText("Загружаю хранилище…");

        pool.execute(() -> {
            StorageSnapshot result = null;
            Exception error = null;
            try {
                result = fetchStorage(repo, token);
            } catch (Exception e) {
                error = e;
            }
            final StorageSnapshot loaded = result;
            final Exception failure = error;
            ui.post(() -> {
                if (sequence != loadSequence || isFinishing() || isDestroyed()) return;
                if (loaded != null) {
                    applySnapshot(loaded);
                    setBusy(false, "");
                } else {
                    snapshotLoaded = false;
                    summary.setText("Не удалось загрузить хранилище.\n\n" + friendlyError(failure, false));
                    setBusy(false, "Проверьте репозиторий, подключение и права токена, затем обновите экран.");
                }
            });
        });
    }

    private StorageSnapshot fetchStorage(String repository, String token) throws Exception {
        StorageSnapshot result = new StorageSnapshot();
        String artifactsBase = Api.API + "/repos/" + repository + "/actions/artifacts?per_page=" + PAGE_SIZE + "&page=";
        for (int page = 1; page <= 1000; page++) {
            Api.Response response = Api.get(artifactsBase + page, token);
            JSONObject body = new JSONObject(response.body);
            JSONArray rows = body.optJSONArray("artifacts");
            if (rows == null || rows.length() == 0) break;
            result.artifacts.addAll(Models.ArtifactInfo.list(rows));
            if (rows.length() < PAGE_SIZE) break;
        }

        String cachesBase = Api.API + "/repos/" + repository + "/actions/caches?per_page=" + PAGE_SIZE + "&page=";
        for (int page = 1; page <= 1000; page++) {
            Api.Response response = Api.get(cachesBase + page, token);
            JSONObject body = new JSONObject(response.body);
            JSONArray rows = body.optJSONArray("actions_caches");
            if (rows == null || rows.length() == 0) break;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                ActionCache cache = new ActionCache();
                cache.id = row.optLong("id", 0);
                cache.size = row.optLong("size_in_bytes", 0);
                result.caches.add(cache);
            }
            if (rows.length() < PAGE_SIZE) break;
        }
        return result;
    }

    private void applySnapshot(StorageSnapshot snapshot) {
        artifacts = snapshot.artifacts;
        caches = snapshot.caches;
        snapshotLoaded = true;

        long artifactBytes = 0;
        for (Models.ArtifactInfo artifact : artifacts) artifactBytes += Math.max(0, artifact.size);
        long cacheBytes = 0;
        for (ActionCache cache : caches) cacheBytes += Math.max(0, cache.size);

        summary.setText("Артефакты сборок: " + artifacts.size() + " шт. · " + Util.humanSize(artifactBytes)
                + "\nКэши Actions: " + caches.size() + " шт. · " + Util.humanSize(cacheBytes)
                + "\nВсего можно освободить: " + Util.humanSize(artifactBytes + cacheBytes));
        updateButtonState();
    }

    private boolean readyForAction() {
        if (busy) {
            toast("Дождитесь завершения текущей операции");
            return false;
        }
        if (!snapshotLoaded) {
            toast("Сначала загрузите данные репозитория");
            return false;
        }
        return true;
    }

    private boolean ensureWriteToken(Runnable retry) {
        if (!Store.token(this).trim().isEmpty()) return true;
        TokenDialog.askForToken(this,
                "Для удаления нужны права GitHub: fine-grained токен — Actions: Read and write; "
                        + "classic токен — scope repo.", () -> {
                    updateTokenBanner();
                    if (retry != null) retry.run();
                });
        return false;
    }

    private void deleteAllArtifacts() {
        if (!readyForAction()) return;
        if (!ensureWriteToken(this::deleteAllArtifacts)) return;
        requestArtifactDeletion("Удалить все артефакты",
                new ArrayList<>(artifacts), "Будут удалены все артефакты сборок этого репозитория.");
    }

    private void deleteOlderArtifacts() {
        if (!readyForAction()) return;
        if (!ensureWriteToken(this::deleteOlderArtifacts)) return;
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        List<Models.ArtifactInfo> selected = new ArrayList<>();
        for (Models.ArtifactInfo artifact : artifacts) {
            Instant created = parseInstant(artifact.createdAt);
            if (created != null && created.isBefore(cutoff)) selected.add(artifact);
        }
        requestArtifactDeletion("Артефакты старше 30 дней", selected,
                "Возраст рассчитан по дате создания артефакта. Более новые артефакты останутся.");
    }

    private void deleteExpiredArtifacts() {
        if (!readyForAction()) return;
        if (!ensureWriteToken(this::deleteExpiredArtifacts)) return;
        Instant now = Instant.now();
        List<Models.ArtifactInfo> selected = new ArrayList<>();
        for (Models.ArtifactInfo artifact : artifacts) {
            Instant expires = parseInstant(artifact.expiresAt);
            if (artifact.expired || (expires != null && !expires.isAfter(now))) selected.add(artifact);
        }
        requestArtifactDeletion("Истёкшие артефакты", selected,
                "Будут удалены артефакты, срок хранения которых истёк по данным GitHub.");
    }

    private void requestArtifactDeletion(String title, List<Models.ArtifactInfo> selected, String detail) {
        List<Models.ArtifactInfo> targets = new ArrayList<>();
        long bytes = 0;
        for (Models.ArtifactInfo artifact : selected) {
            if (artifact.id <= 0) continue;
            targets.add(artifact);
            bytes += Math.max(0, artifact.size);
        }
        if (targets.isEmpty()) {
            showInfo("Нечего удалять", "Под выбранное условие артефактов не найдено.");
            return;
        }
        String message = detail + "\n\nК удалению: " + targets.size() + " шт. · " + Util.humanSize(bytes)
                + "\n\nУдалённые данные нельзя восстановить. Новые запуски могут создать артефакты снова.";
        new AlertDialog.Builder(this)
                .setTitle(title + "?")
                .setMessage(message)
                .setPositiveButton("Удалить", (dialog, which) -> performArtifactDeletion(title, targets))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void deleteAllCaches() {
        if (!readyForAction()) return;
        if (!ensureWriteToken(this::deleteAllCaches)) return;
        List<ActionCache> targets = new ArrayList<>(caches);
        long bytes = 0;
        for (ActionCache cache : targets) bytes += Math.max(0, cache.size);
        if (targets.isEmpty()) {
            showInfo("Кэшей нет", "Для этого репозитория GitHub Actions кэши не найдены.");
            return;
        }
        String message = "Будут удалены все кэши GitHub Actions этого репозитория.\n\nК удалению: "
                + targets.size() + " шт. · " + Util.humanSize(bytes)
                + "\n\nНовые запуски могут создать кэши снова.";
        new AlertDialog.Builder(this)
                .setTitle("Удалить все кэши?")
                .setMessage(message)
                .setPositiveButton("Удалить", (dialog, which) -> performCacheDeletion(targets))
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Находит ID последних успешных запусков до показа подтверждения удаления. */
    private void prepareKeepLastSuccessful(int keepCount) {
        if (!readyForAction()) return;
        if (!ensureWriteToken(() -> prepareKeepLastSuccessful(keepCount))) return;

        final List<Models.ArtifactInfo> currentArtifacts = new ArrayList<>(artifacts);
        final String token = Store.token(this);
        setBusy(true, "Ищу последние успешные запуски…");
        pool.execute(() -> {
            Set<Long> successfulRunIds = null;
            Exception error = null;
            try {
                successfulRunIds = fetchLatestSuccessfulRunIds(repo, token, keepCount);
            } catch (Exception e) {
                error = e;
            }
            final Set<Long> keepIds = successfulRunIds;
            final Exception failure = error;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false, "");
                if (failure != null) {
                    showInfo("Не удалось проверить запуски", friendlyError(failure, false));
                    return;
                }
                if (keepIds == null || keepIds.isEmpty()) {
                    showInfo("Успешных запусков нет",
                            "Не найдено ни одного завершённого успешного запуска. Ничего не удалено.");
                    return;
                }

                List<Models.ArtifactInfo> selected = new ArrayList<>();
                int unlinked = 0;
                for (Models.ArtifactInfo artifact : currentArtifacts) {
                    if (artifact.workflowRunId <= 0) {
                        unlinked++;
                    } else if (!keepIds.contains(artifact.workflowRunId)) {
                        selected.add(artifact);
                    }
                }
                String detail = "Будут оставлены артефакты " + keepIds.size()
                        + " последних успешных запусков; остальные связанные с запусками артефакты будут удалены.";
                if (unlinked > 0) {
                    detail += "\nАртефакты без ID запуска: " + unlinked + " шт. — оставлены для безопасности.";
                }
                if (selected.isEmpty()) {
                    String message = "Артефактов для удаления не найдено.";
                    if (unlinked > 0) {
                        message += "\n\nАртефакты без ID запуска (" + unlinked
                                + " шт.) оставлены для безопасности.";
                    }
                    showInfo("Очистка не требуется", message);
                } else {
                    requestArtifactDeletion("Оставить " + keepCount + " последних успешных",
                            selected, detail);
                }
            });
        });
    }

    private Set<Long> fetchLatestSuccessfulRunIds(String repository, String token, int keepCount) throws Exception {
        Set<Long> ids = new HashSet<>();
        String base = Api.API + "/repos/" + repository + "/actions/runs"
                + "?status=completed&sort=created&direction=desc&per_page=" + PAGE_SIZE + "&page=";
        for (int page = 1; page <= 1000 && ids.size() < keepCount; page++) {
            Api.Response response = Api.get(base + page, token);
            JSONObject body = new JSONObject(response.body);
            JSONArray rows = body.optJSONArray("workflow_runs");
            if (rows == null || rows.length() == 0) break;
            for (int i = 0; i < rows.length() && ids.size() < keepCount; i++) {
                JSONObject run = rows.optJSONObject(i);
                if (run != null && "success".equals(run.optString("conclusion", ""))) {
                    long id = run.optLong("id", 0);
                    if (id > 0) ids.add(id);
                }
            }
            if (rows.length() < PAGE_SIZE) break;
        }
        return ids;
    }

    private void performArtifactDeletion(String label, List<Models.ArtifactInfo> targets) {
        final String token = Store.token(this);
        final List<Models.ArtifactInfo> selected = new ArrayList<>(targets);
        setBusy(true, "Удаляю артефакты GitHub…");
        pool.execute(() -> {
            CleanupResult result = new CleanupResult();
            for (int i = 0; i < selected.size(); i++) {
                Models.ArtifactInfo artifact = selected.get(i);
                result.attemptedIds.add(artifact.id);
                try {
                    Api.delete(Api.API + "/repos/" + repo + "/actions/artifacts/" + artifact.id, token);
                    result.deleted++;
                    result.bytesFreed += Math.max(0, artifact.size);
                } catch (Exception e) {
                    if (recordDeleteFailure(result, e)) break;
                }
                postProgress("Удаляю артефакты… " + (i + 1) + " / " + selected.size());
            }
            finishCleanup(label, result, token);
        });
    }

    private void performCacheDeletion(List<ActionCache> targets) {
        final String token = Store.token(this);
        final List<ActionCache> selected = new ArrayList<>(targets);
        setBusy(true, "Удаляю кэши GitHub Actions…");
        pool.execute(() -> {
            CleanupResult result = new CleanupResult();
            result.cachesOperation = true;
            for (int i = 0; i < selected.size(); i++) {
                ActionCache cache = selected.get(i);
                result.attemptedIds.add(cache.id);
                try {
                    Api.delete(Api.API + "/repos/" + repo + "/actions/caches/" + cache.id, token);
                    result.deleted++;
                    result.bytesFreed += Math.max(0, cache.size);
                } catch (Exception e) {
                    if (recordDeleteFailure(result, e)) break;
                }
                postProgress("Удаляю кэши… " + (i + 1) + " / " + selected.size());
            }
            finishCleanup("Кэши GitHub Actions", result, token);
        });
    }

    /** Возвращает true, если из-за отсутствующего ресурса или прав нужно остановить очередь. */
    private boolean recordDeleteFailure(CleanupResult result, Exception error) {
        if (error instanceof Api.ApiException) {
            int code = ((Api.ApiException) error).code;
            if (code == 404 || code == 410) {
                result.alreadyGone++;
                return false;
            }
        }
        result.failed++;
        if (result.firstError == null) result.firstError = error;
        if (error instanceof Api.ApiException) {
            int code = ((Api.ApiException) error).code;
            return code == 401 || code == 403;
        }
        return false;
    }

    private void postProgress(String message) {
        ui.post(() -> {
            if (!isFinishing() && !isDestroyed() && busy) status.setText(message);
        });
    }

    /** После удаления обязательно читаем состояние заново с GitHub — не полагаемся на локальный список. */
    private void finishCleanup(String label, CleanupResult result, String token) {
        StorageSnapshot refreshed = null;
        Exception refreshError = null;
        try {
            refreshed = fetchStorage(repo, token);
        } catch (Exception e) {
            refreshError = e;
        }
        final StorageSnapshot fresh = refreshed;
        final Exception refreshFailure = refreshError;
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (fresh != null) {
                applySnapshot(fresh);
                Set<Long> remainingIds = new HashSet<>();
                if (result.cachesOperation) {
                    for (ActionCache cache : fresh.caches) remainingIds.add(cache.id);
                } else {
                    for (Models.ArtifactInfo artifact : fresh.artifacts) remainingIds.add(artifact.id);
                }
                for (Long id : result.attemptedIds) {
                    if (remainingIds.contains(id)) result.stillPresent++;
                }
            } else {
                snapshotLoaded = false;
                summary.setText("Удаление выполнено, но не удалось проверить остаток.\n\n"
                        + friendlyError(refreshFailure, false));
            }
            setBusy(false, "");

            StringBuilder message = new StringBuilder();
            message.append(label).append("\n\n")
                    .append("Удалено с GitHub: ").append(result.deleted).append(" шт.");
            if (result.alreadyGone > 0) {
                message.append("\nУже отсутствовало: ").append(result.alreadyGone).append(" шт.");
            }
            if (result.failed > 0) {
                message.append("\nНе удалось удалить: ").append(result.failed).append(" шт.");
                message.append("\n\n").append(friendlyError(result.firstError, true));
            }
            if (result.deleted > 0) {
                message.append("\nОсвобождено по размеру артефактов/кэшей: ")
                        .append(Util.humanSize(result.bytesFreed));
            }
            if (fresh != null) {
                message.append("\n\nПроверено повторным запросом GitHub.")
                        .append("\nОсталось: ").append(fresh.artifacts.size()).append(" артефактов и ")
                        .append(fresh.caches.size()).append(" кэшей.");
                if (result.stillPresent > 0) {
                    message.append("\n\nВ списке GitHub всё ещё видны ").append(result.stillPresent)
                            .append(" из обработанных записей. Обновите экран через несколько секунд "
                                    + "или проверьте права токена.");
                }
            } else {
                message.append("\n\nНажмите кнопку обновления, чтобы проверить состояние ещё раз.");
            }

            AlertDialog.Builder report = new AlertDialog.Builder(this)
                    .setTitle(result.failed == 0 && result.stillPresent == 0
                            ? "Очистка завершена" : "Очистка выполнена частично")
                    .setMessage(message.toString())
                    .setPositiveButton("ОК", null);
            if (result.firstError instanceof Api.ApiException) {
                int code = ((Api.ApiException) result.firstError).code;
                if (code == 401 || code == 403) {
                    report.setNeutralButton("Настроить токен", (dialog, which) ->
                            TokenDialog.show(this, () -> {
                                updateTokenBanner();
                                loadSummary();
                            }));
                }
            }
            report.show();
        });
    }

    private static String friendlyError(Exception error, boolean deleting) {
        if (error == null) return "Неизвестная ошибка.";
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        if (error instanceof Api.ApiException) {
            int code = ((Api.ApiException) error).code;
            if (code == 401) {
                return "GitHub отклонил токен (401). Проверьте токен и повторите попытку.\n" + message;
            }
            if (code == 403) {
                String help = deleting
                        ? "Для удаления нужен fine-grained токен с Actions: Read and write "
                                + "или classic-токен со scope repo. Также проверьте, не исчерпан ли лимит API."
                        : "Проверьте, что токену выданы права Actions: Read (fine-grained) или scope repo "
                                + "для приватного репозитория.";
                return message + "\n\n" + help;
            }
            if (code == 404) {
                return "Репозиторий или данные не найдены (404). Проверьте owner/repo и доступ токена.\n" + message;
            }
        }
        return message;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private void showInfo(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("ОК", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static final class StorageSnapshot {
        final List<Models.ArtifactInfo> artifacts = new ArrayList<>();
        final List<ActionCache> caches = new ArrayList<>();
    }

    private static final class ActionCache {
        long id;
        long size;
    }

    private static final class CleanupResult {
        int deleted;
        int alreadyGone;
        int failed;
        int stillPresent;
        long bytesFreed;
        boolean cachesOperation;
        final Set<Long> attemptedIds = new HashSet<>();
        Exception firstError;
    }
}
