package com.zigit.app;

import android.app.Activity;
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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Экран «Хранилище»: сколько места занято на GitHub и сколько осталось.
 *
 * Считается из открытых данных API:
 *  • аккаунт — /user: disk_usage (КБ) и лимит плана plan.space (КБ);
 *  • репозитории — /user/repos: поле size (КБ) по каждому репозиторию;
 *  • текущий репозиторий — его size, суммарный размер артефактов Actions
 *    (/actions/artifacts, поле size_in_bytes) и кэш Actions (/actions/cache/usage);
 *  • платное хранилище — /users/{login}/settings/billing/shared-storage
 *    (доступно не всем токенам: нужен classic со scope user / fine-grained с Plan).
 */
public class StorageActivity extends Activity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private TextView status, empty;
    private ProgressBar progress;

    /** Репозиторий, из которого открыли экран (может быть null). */
    private String repo;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_storage);

        repo = getIntent().getStringExtra("repo");

        content = findViewById(R.id.content);
        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        progress = findViewById(R.id.progress);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.refreshBtn).setOnClickListener(v -> load());

        load();
    }

    private void load() {
        content.removeAllViews();
        empty.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText("Считаю занятое место…");

        final String token = Store.token(this);
        pool.execute(() -> {
            // --- аккаунт
            String login = null;
            long diskUsageKb = -1;      // занято аккаунтом (КБ)
            long planSpaceKb = -1;      // лимит плана (КБ)
            String planName = null;
            String accountError = null;
            try {
                Api.Response r = Api.get(Api.API + "/user", token);
                JSONObject u = new JSONObject(r.body);
                login = u.optString("login", null);
                if (u.has("disk_usage") && !u.isNull("disk_usage")) {
                    diskUsageKb = u.optLong("disk_usage", -1);
                }
                JSONObject plan = u.optJSONObject("plan");
                if (plan != null) {
                    planName = plan.optString("name", null);
                    planSpaceKb = plan.optLong("space", -1);
                }
            } catch (Exception e) {
                accountError = e.getMessage();
            }

            // --- все репозитории: количество и суммарный размер
            long reposTotalKb = 0;
            int reposCount = 0;
            long privateCount = 0;
            String reposError = null;
            final StringBuilder top = new StringBuilder();
            try {
                String url = Api.API + "/user/repos?per_page=100"
                        + "&affiliation=owner,collaborator,organization_member&sort=pushed";
                int pages = 0;
                java.util.List<long[]> sizes = new java.util.ArrayList<>();
                java.util.List<String> names = new java.util.ArrayList<>();
                while (url != null && pages < 10) {
                    Api.Response r = Api.get(url, token);
                    JSONArray arr = new JSONArray(r.body);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        long kb = o.optLong("size", 0);
                        reposCount++;
                        reposTotalKb += kb;
                        if (o.optBoolean("private", false)) privateCount++;
                        sizes.add(new long[]{kb});
                        names.add(o.optString("full_name", "?")
                                + (o.optBoolean("private", false) ? "  ·  private" : ""));
                    }
                    url = arr.length() < 100 ? null : Api.nextPage(r);
                    pages++;
                }
                // топ-10 по размеру
                final List<String> n = names;
                final List<long[]> s = sizes;
                Integer[] idx = new Integer[n.size()];
                for (int i = 0; i < idx.length; i++) idx[i] = i;
                java.util.Arrays.sort(idx, (a, bb) -> Long.compare(s.get(bb)[0], s.get(a)[0]));
                for (int i = 0; i < Math.min(10, idx.length); i++) {
                    int k = idx[i];
                    if (s.get(k)[0] <= 0) continue;
                    top.append("• ").append(n.get(k)).append(" — ")
                            .append(Util.humanSize(s.get(k)[0] * 1024)).append("\n");
                }
            } catch (Exception e) {
                reposError = e.getMessage();
            }

            // --- текущий репозиторий: размер, артефакты, кэш
            long repoSizeKb = -1;
            int artifactsCount = 0;
            long artifactsBytes = 0;
            long artifactsExpiredBytes = 0;
            int artifactsExpired = 0;
            String artifactsError = null;
            long cacheBytes = -1;
            String repoError = null;

            if (repo != null && !repo.isEmpty()) {
                try {
                    JSONObject ro = Api.json(Api.API + "/repos/" + repo, token);
                    repoSizeKb = ro.optLong("size", -1);
                } catch (Exception e) {
                    repoError = e.getMessage();
                }
                try {
                    String url = Api.API + "/repos/" + repo + "/actions/artifacts?per_page=100";
                    int pages = 0;
                    while (url != null && pages < 20) {
                        Api.Response r = Api.get(url, token);
                        JSONObject o = new JSONObject(r.body);
                        JSONArray arr = o.optJSONArray("artifacts");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject a = arr.optJSONObject(i);
                                if (a == null) continue;
                                long size = a.optLong("size_in_bytes", 0);
                                if (a.optBoolean("expired", false)) {
                                    artifactsExpired++;
                                    artifactsExpiredBytes += size;
                                } else {
                                    artifactsCount++;
                                    artifactsBytes += size;
                                }
                            }
                        }
                        url = Api.nextPage(r);
                        pages++;
                    }
                } catch (Exception e) {
                    artifactsError = e.getMessage();
                }
                try {
                    JSONObject c = Api.json(Api.API + "/repos/" + repo
                            + "/actions/cache/usage", token);
                    cacheBytes = c.optLong("active_caches_size_in_bytes", -1);
                } catch (Exception ignored) {
                }
            }

            // --- платное хранилище (может быть недоступно токену)
            long billedGb = -1;
            int daysLeft = -1;
            String billingError = null;
            if (login != null) {
                try {
                    JSONObject bo = Api.json(Api.API + "/users/" + login
                            + "/settings/billing/shared-storage", token);
                    billedGb = bo.optLong("estimated_paid_storage_for_month", -1);
                    daysLeft = bo.optInt("days_left_in_billing_cycle", -1);
                } catch (Exception e) {
                    billingError = e.getMessage();
                }
            }

            final String fLogin = login;
            final long fDisk = diskUsageKb, fPlan = planSpaceKb, fReposKb = reposTotalKb;
            final int fReposCount = reposCount;
            final long fPrivate = privateCount;
            final String fPlanName = planName, fAccountError = accountError, fReposError = reposError;
            final String fTop = top.toString();
            final long fRepoSizeKb = repoSizeKb;
            final int fArtCount = artifactsCount;
            final long fArtBytes = artifactsBytes;
            final int fArtExpired = artifactsExpired;
            final long fArtExpiredBytes = artifactsExpiredBytes;
            final String fArtError = artifactsError, fRepoError = repoError;
            final long fCache = cacheBytes;
            final long fBilled = billedGb;
            final int fDays = daysLeft;
            final String fBillingError = billingError;
            final boolean hasToken = !token.isEmpty();

            ui.post(() -> {
                if (isFinishing()) return;
                progress.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                render(hasToken, fLogin, fDisk, fPlan, fPlanName, fAccountError,
                        fReposCount, fReposKb, fPrivate, fTop, fReposError,
                        fRepoSizeKb, fArtCount, fArtBytes, fArtExpired, fArtExpiredBytes,
                        fArtError, fRepoError, fCache, fBilled, fDays, fBillingError);
            });
        });
    }

    // ------------------------------------------------------------- отрисовка

    private void render(boolean hasToken, String login,
                        long diskKb, long planKb, String planName, String accountError,
                        int reposCount, long reposKb, long privateCount, String top,
                        String reposError,
                        long repoSizeKb, int artCount, long artBytes,
                        int artExpired, long artExpiredBytes,
                        String artError, String repoError, long cacheBytes,
                        long billedGb, int daysLeft, String billingError) {

        if (!hasToken) {
            card("Нужен токен GitHub",
                    "Занятое место GitHub отдаёт только авторизованным запросам.\n"
                            + "Меню ⋮ → «Токен GitHub» на главном экране, затем обновите этот экран.", -1);
            return;
        }

        // 1. Аккаунт
        StringBuilder acc = new StringBuilder();
        if (login != null) acc.append("Аккаунт: ").append(login).append("\n");
        if (diskKb >= 0) {
            acc.append("Занято репозиториями (по данным GitHub): ")
                    .append(Util.humanSize(diskKb * 1024)).append("\n");
        }
        if (planKb > 0) {
            acc.append("Лимит плана").append(planName == null ? "" : " «" + planName + "»")
                    .append(": ").append(Util.humanSize(planKb * 1024)).append("\n");
            if (diskKb >= 0) {
                long free = planKb - diskKb;
                acc.append("Осталось: ").append(free >= 0
                        ? Util.humanSize(free * 1024)
                        : "лимит превышен").append("\n");
                acc.append("Использовано: ").append(pct(diskKb, planKb)).append("%");
            }
        } else if (accountError != null) {
            acc.append("GitHub не отдал данные аккаунта: ").append(accountError);
        } else {
            acc.append("Лимит плана GitHub через API не сообщает — используйте подсчёт по репозиториям ниже.");
        }
        card("Место в аккаунте", acc.toString(), planKb > 0 && diskKb >= 0
                ? (int) Math.min(100, diskKb * 100 / planKb) : -1);

        // 2. Репозитории
        StringBuilder reps = new StringBuilder();
        if (reposError != null) {
            reps.append("Не удалось получить список: ").append(reposError);
        } else {
            reps.append("Репозиториев доступно токену: ").append(reposCount)
                    .append(privateCount > 0 ? " (приватных: " + privateCount + ")" : "")
                    .append("\n");
            reps.append("Суммарный размер репозиториев: ").append(Util.humanSize(reposKb * 1024))
                    .append("\n");
            reps.append("Это git-история без учёта релизов, Packages и артефактов Actions.\n");
            if (top.length() > 0) {
                reps.append("\nСамые большие:\n").append(top);
            }
        }
        card("Репозитории", reps.toString().trim(), -1);

        // 3. Текущий репозиторий
        if (repo != null && !repo.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(repo).append("\n");
            if (repoSizeKb >= 0) {
                sb.append("Размер репозитория: ").append(Util.humanSize(repoSizeKb * 1024)).append("\n");
            } else if (repoError != null) {
                sb.append("Размер не получен: ").append(repoError).append("\n");
            }
            if (artError != null) {
                sb.append("Артефакты не посчитаны: ").append(artError).append("\n");
            } else {
                sb.append("Артефакты Actions: ").append(artCount).append(" шт., ")
                        .append(Util.humanSize(artBytes)).append("\n");
                if (artExpired > 0) {
                    sb.append("Из них истекли (GitHub скоро удалит): ").append(artExpired)
                            .append(" шт., ").append(Util.humanSize(artExpiredBytes)).append("\n");
                }
            }
            if (cacheBytes >= 0) {
                sb.append("Кэш Actions: ").append(Util.humanSize(cacheBytes)).append("\n");
            }
            sb.append("\nАртефакты и кэш хранятся отдельно от репозитория и входят "
                    + "в хранилище Actions (у бесплатного плана — 500 МБ для приватных "
                    + "репозиториев, для публичных бесплатно).\n"
                    + "Освободить место: удалить старые артефакты (удержание строки "
                    + "в списке артефактов).");
            card("Текущий репозиторий", sb.toString().trim(), -1);
        }

        // 4. Платное хранилище
        StringBuilder bill = new StringBuilder();
        if (billedGb >= 0) {
            bill.append("Платное хранилище за месяц: ").append(billedGb).append(" ГБ\n");
            if (daysLeft >= 0) bill.append("До конца расчётного периода: ").append(daysLeft).append(" дн.\n");
            bill.append("\nДанные GitHub Billing.");
        } else {
            bill.append("GitHub не отдаёт биллинг этому токену");
            if (billingError != null) bill.append(" (").append(billingError).append(")");
            bill.append(".\n\nНужен classic-токен со scope user (или fine-grained с "
                    + "Plan: Read). Точные цифры по лимитам — на github.com: "
                    + "Settings → Billing and plans.");
        }
        card("Платное хранилище", bill.toString().trim(), -1);
    }

    private int pct(long used, long limit) {
        if (limit <= 0) return 0;
        return (int) Math.min(100, used * 100 / limit);
    }

    /** Карточка: заголовок, текст и (необязательно) полоса заполнения. */
    private void card(String title, String body, int percent) {
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

        if (percent >= 0) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(percent);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Util.dp(this, 6));
            blp.topMargin = Util.dp(this, 8);
            bar.setLayoutParams(blp);
            box.addView(bar);
        }

        content.addView(box);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
