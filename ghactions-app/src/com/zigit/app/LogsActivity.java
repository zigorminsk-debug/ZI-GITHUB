package com.zigit.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Экран логов и прогресса сборки Actions.
 * Для завершённых запусков — список jobs с логами.
 * Для in_progress — автообновление каждые 5 сек с показом шагов.
 */
public class LogsActivity extends Activity {

    private static final long REFRESH_MS = 5000;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<JobItem> jobs = new ArrayList<>();

    private String repo;
    private long runId;
    private String runTitle;
    private String runConclusion;

    private ListView jobsList;
    private ScrollView logScroll;
    private TextView logText;
    private TextView title, subtitle;
    private TextView empty, progressText;
    private TextView copyAllBtn, shareBtn, copyHint;
    private View progressRow;

    private JobAdapter adapter;
    private String currentLog;
    private String currentJobName;
    private boolean showingLog;
    private boolean autoRefresh;
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (autoRefresh && !isFinishing() && !showingLog) {
                silentRefresh();
                ui.postDelayed(this, REFRESH_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_logs);

        repo = getIntent().getStringExtra("repo");
        runId = getIntent().getLongExtra("runId", 0);
        runTitle = getIntent().getStringExtra("title");
        String branch = getIntent().getStringExtra("branch");
        int number = getIntent().getIntExtra("number", 0);
        runConclusion = getIntent().getStringExtra("conclusion");

        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        jobsList = findViewById(R.id.jobsList);
        logScroll = findViewById(R.id.logScroll);
        logText = findViewById(R.id.logText);
        empty = findViewById(R.id.empty);
        progressRow = findViewById(R.id.progressRow);
        progressText = findViewById(R.id.progressText);
        copyAllBtn = findViewById(R.id.copyAllBtn);
        shareBtn = findViewById(R.id.shareBtn);
        copyHint = findViewById(R.id.copyHint);

        title.setText(runTitle == null ? getString(R.string.logs_title) : runTitle);
        StringBuilder sb = new StringBuilder();
        if (number > 0) sb.append('#').append(number);
        if (branch != null && !branch.isEmpty()) sb.append(sb.length() > 0 ? "  ·  " : "").append(branch);
        if (runConclusion != null && !runConclusion.isEmpty()) sb.append(sb.length() > 0 ? "  ·  " : "").append(runConclusion);
        sb.append(sb.length() > 0 ? "  ·  " : "").append(repo);
        subtitle.setText(sb.toString());

        adapter = new JobAdapter();
        jobsList.setAdapter(adapter);

        findViewById(R.id.backBtn).setOnClickListener(v -> {
            if (showingLog) showJobsList();
            else finish();
        });
        findViewById(R.id.refreshBtn).setOnClickListener(v -> loadJobs());
        copyAllBtn.setOnClickListener(v -> copyAllLog());
        shareBtn.setOnClickListener(v -> shareLog());

        jobsList.setOnItemClickListener((parent, view, position, id) ->
                loadJobLog(jobs.get(position)));

        updateTokenBanner();
        loadJobs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startAutoRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoRefresh();
    }

    private void updateTokenBanner() {
        TextView tokenBanner = findViewById(R.id.tokenBanner);
        tokenBanner.setVisibility(Store.token(this).isEmpty() ? View.VISIBLE : View.GONE);
        tokenBanner.setOnClickListener(v -> TokenDialog.show(this, () -> {
            updateTokenBanner();
            loadJobs();
        }));
    }

    // -------------------------------------------------------- auto-refresh

    private void startAutoRefresh() {
        if ("in_progress".equals(runConclusion) || "queued".equals(runConclusion)) {
            autoRefresh = true;
            ui.postDelayed(refreshRunnable, REFRESH_MS);
        }
    }

    private void stopAutoRefresh() {
        autoRefresh = false;
        ui.removeCallbacks(refreshRunnable);
    }

    /** Тихое обновление: без прогресс-бара, без сброса списка. */
    private void silentRefresh() {
        pool.execute(() -> {
            try {
                JSONObject o = Api.json(Api.API + "/repos/" + repo
                        + "/actions/runs/" + runId + "/jobs?per_page=100&filter=all",
                        Store.token(this));
                JSONArray arr = o.optJSONArray("jobs");
                final List<JobItem> got = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject jo = arr.optJSONObject(i);
                        if (jo != null) got.add(JobItem.from(jo));
                    }
                }
                // проверяем статус самого run
                final String runStatus = checkRunStatus();

                ui.post(() -> {
                    if (isFinishing()) return;
                    jobs.clear();
                    jobs.addAll(got);
                    adapter.notifyDataSetChanged();

                    // обновляем статус run
                    if (runStatus != null) {
                        runConclusion = runStatus;
                        if (!"in_progress".equals(runStatus) && !"queued".equals(runStatus)) {
                            stopAutoRefresh();
                        }
                    }

                    // если все jobs завершены — остановим автообновление
                    boolean anyRunning = false;
                    for (JobItem j : got) {
                        if ("in_progress".equals(j.status) || "queued".equals(j.status)) {
                            anyRunning = true;
                            break;
                        }
                    }
                    if (!anyRunning && !got.isEmpty()) {
                        stopAutoRefresh();
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    private String checkRunStatus() {
        try {
            JSONObject o = Api.json(Api.API + "/repos/" + repo
                    + "/actions/runs/" + runId, Store.token(this));
            String status = o.optString("status", "");
            String conclusion = o.optString("conclusion", "");
            if ("completed".equals(status) && !conclusion.isEmpty()) return conclusion;
            return status;
        } catch (Exception e) {
            return null;
        }
    }

    // ----------------------------------------------------------- jobs list

    private void loadJobs() {
        showJobsList();
        jobs.clear();
        adapter.notifyDataSetChanged();
        showProgress("Загружаю задания запуска…");
        empty.setVisibility(View.GONE);

        pool.execute(() -> {
            try {
                JSONObject o = Api.json(Api.API + "/repos/" + repo
                        + "/actions/runs/" + runId + "/jobs?per_page=100&filter=all",
                        Store.token(this));
                JSONArray arr = o.optJSONArray("jobs");
                final List<JobItem> got = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject jo = arr.optJSONObject(i);
                        if (jo != null) got.add(JobItem.from(jo));
                    }
                }
                ui.post(() -> {
                    if (isFinishing()) return;
                    hideProgress();
                    jobs.clear();
                    jobs.addAll(got);
                    adapter.notifyDataSetChanged();
                    if (jobs.isEmpty()) {
                        jobsList.setVisibility(View.GONE);
                        empty.setText("У этого запуска нет заданий.\n\n"
                                + "Возможно, запуск ещё не начался, "
                                + "или workflow завершился на уровне планирования.");
                        empty.setVisibility(View.VISIBLE);
                    } else {
                        jobsList.setVisibility(View.VISIBLE);
                        empty.setVisibility(View.GONE);
                    }
                    startAutoRefresh();
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing()) return;
                    hideProgress();
                    jobsList.setVisibility(View.GONE);
                    empty.setText("Ошибка: " + msg);
                    empty.setVisibility(View.VISIBLE);
                    if (code == 401 || code == 403) {
                        TokenDialog.askForToken(this,
                                "GitHub не отдал задания запуска без авторизации.",
                                this::loadJobs);
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------- job log

    private void loadJobLog(JobItem job) {
        currentJobName = job.name;
        currentLog = null;
        showProgress("Загружаю лог: " + job.name + "…");

        pool.execute(() -> {
            try {
                String text = Api.getText(
                        Api.API + "/repos/" + repo + "/actions/jobs/" + job.id + "/logs",
                        Store.token(this));
                final String log = text;
                ui.post(() -> {
                    if (isFinishing()) return;
                    hideProgress();
                    currentLog = log;
                    showLogView(job.name, log);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing()) return;
                    hideProgress();
                    if (code == 401 || code == 403) {
                        TokenDialog.askForToken(this,
                                "Логи доступны только с авторизацией.\n" + msg,
                                () -> loadJobLog(job));
                    } else {
                        toast("Не удалось загрузить лог: " + msg);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------- view switching

    private void showJobsList() {
        showingLog = false;
        jobsList.setVisibility(View.VISIBLE);
        logScroll.setVisibility(View.GONE);
        copyAllBtn.setVisibility(View.GONE);
        shareBtn.setVisibility(View.GONE);
        copyHint.setVisibility(View.GONE);
        findViewById(R.id.refreshBtn).setVisibility(View.VISIBLE);
        title.setText(runTitle == null ? getString(R.string.logs_title) : runTitle);
    }

    private void showLogView(String jobName, String log) {
        showingLog = true;
        jobsList.setVisibility(View.GONE);
        logScroll.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        copyAllBtn.setVisibility(View.VISIBLE);
        shareBtn.setVisibility(View.VISIBLE);
        copyHint.setVisibility(View.VISIBLE);

        title.setText(jobName);

        if (log == null || log.isEmpty()) {
            logText.setText("(лог пуст — задание ещё не завершилось или лог недоступен)");
        } else {
            logText.setText(colorizeLog(log));
        }

        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ------------------------------------------------------ log colorizing

    private SpannableStringBuilder colorizeLog(String log) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = log.split("\n", -1);
        int red = 0xFFFF4444, redBg = 0x30FF0000, yellow = 0xFFFFB300;
        int green = 0xFF66BB6A, cyan = 0xFF4FC3F7, dim = 0xFF8B949E;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int start = sb.length();
            String stripped = line;
            if (stripped.length() > 28 && stripped.charAt(4) == '-' && stripped.charAt(10) == 'T') {
                int sp = stripped.indexOf(' ', 20);
                if (sp > 0 && sp < 35) stripped = stripped.substring(sp + 1);
            }
            sb.append(line);
            if (i < lines.length - 1) sb.append('\n');
            int end = sb.length();

            String low = stripped.toLowerCase();
            boolean isError = false;
            if (low.contains("##[error]") || low.contains("error:") || low.contains("error ")
                    || low.contains("fatal:") || low.contains("failed")
                    || low.contains("exception") || low.contains("❌")
                    || low.contains("failure:") || low.contains("✗")
                    || low.contains("could not") || low.contains("unable to")
                    || low.contains("not found") || low.contains("no such file")
                    || low.contains("permission denied")) {
                isError = true;
                sb.setSpan(new ForegroundColorSpan(red), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (low.contains("##[error]") || low.contains("fatal:")
                        || low.contains("build failed") || low.contains("failure:")) {
                    sb.setSpan(new android.text.style.BackgroundColorSpan(redBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (!isError && (low.contains("##[warning]") || low.contains("warning:")
                    || low.contains("deprecated"))) {
                sb.setSpan(new ForegroundColorSpan(yellow), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!isError && (low.contains("✓") || low.contains("✔") || low.contains("build successful"))) {
                sb.setSpan(new ForegroundColorSpan(green), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!isError && (stripped.startsWith("##[group]") || stripped.startsWith("Run "))) {
                sb.setSpan(new ForegroundColorSpan(cyan), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!isError && (stripped.equals("##[endgroup]") || stripped.startsWith("shell:"))) {
                sb.setSpan(new ForegroundColorSpan(dim), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    // ---------------------------------------------------------- copy/share

    private void copyAllLog() {
        if (currentLog == null || currentLog.isEmpty()) {
            toast("Лог пуст — копировать нечего");
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("build log", currentLog));
            toast("Лог скопирован в буфер (" + currentLog.length() + " симв.)");
        } catch (Exception e) {
            toast("Не удалось скопировать: " + e.getMessage());
        }
    }

    private void shareLog() {
        if (currentLog == null || currentLog.isEmpty()) {
            toast("Лог пуст");
            return;
        }
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "Лог сборки: " + currentJobName);
            share.putExtra(Intent.EXTRA_TEXT, currentLog);
            startActivity(Intent.createChooser(share, "Поделиться логом"));
        } catch (Exception e) {
            toast("Не удалось: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- helpers

    private void showProgress(String text) {
        progressRow.setVisibility(View.VISIBLE);
        progressText.setText(text);
    }

    private void hideProgress() {
        progressRow.setVisibility(View.GONE);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ adapter

    private class JobAdapter extends BaseAdapter {
        @Override
        public int getCount() { return jobs.size(); }
        @Override
        public Object getItem(int p) { return jobs.get(p); }
        @Override
        public long getItemId(int p) { return jobs.get(p).id; }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(LogsActivity.this)
                        .inflate(R.layout.row_job, parent, false);
            }
            JobItem j = jobs.get(position);

            TextView name = v.findViewById(R.id.jobName);
            TextView badge = v.findViewById(R.id.jobBadge);
            TextView sub = v.findViewById(R.id.jobSub);
            TextView steps = v.findViewById(R.id.jobSteps);
            ProgressBar bar = v.findViewById(R.id.jobProgress);

            name.setText(j.name);

            // бейдж статуса
            String label = Models.statusLabel(j.status, j.conclusion);
            if ("in_progress".equals(j.status)) label = "⏳ " + label;
            if ("queued".equals(j.status)) label = "⏸ " + label;
            badge.setText(label);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(Util.dp(LogsActivity.this, 10));
            bg.setColor(getResources().getColor(
                    Models.statusColorRes(j.status, j.conclusion)));
            int px = Util.dp(LogsActivity.this, 8);
            badge.setPadding(px, px / 3, px, px / 3);
            badge.setBackground(bg);

            // подпись: время, runner
            StringBuilder sb = new StringBuilder();
            if (j.startedAt != null && !j.startedAt.isEmpty())
                sb.append(Util.dateTime(j.startedAt));
            if (j.runnerName != null && !j.runnerName.isEmpty())
                sb.append(sb.length() > 0 ? "  ·  " : "").append(j.runnerName);
            if ("in_progress".equals(j.status) && autoRefresh)
                sb.append(sb.length() > 0 ? "  ·  " : "").append("🔄 авто-обновление");
            sub.setText(sb.toString());

            // прогресс-бар для in_progress
            if ("in_progress".equals(j.status) && j.stepCount > 0) {
                int done = j.completedSteps;
                bar.setVisibility(View.VISIBLE);
                bar.setMax(j.stepCount);
                bar.setProgress(done);
            } else {
                bar.setVisibility(View.GONE);
            }

            // шаги: имя + статус
            if (j.stepDetails != null && !j.stepDetails.isEmpty()) {
                steps.setVisibility(View.VISIBLE);
                SpannableStringBuilder ssb = new SpannableStringBuilder();
                int red = 0xFFCF222E, green = 0xFF1F883D, orange = 0xFFBF8700, gray = 0xFF8B949E;
                for (StepInfo s : j.stepDetails) {
                    int start = ssb.length();
                    String icon;
                    int color;
                    if ("completed".equals(s.status) && "success".equals(s.conclusion)) {
                        icon = "✓"; color = green;
                    } else if ("completed".equals(s.status) && "failure".equals(s.conclusion)) {
                        icon = "✗"; color = red;
                    } else if ("completed".equals(s.status) && "skipped".equals(s.conclusion)) {
                        icon = "⊘"; color = gray;
                    } else if ("completed".equals(s.status)) {
                        icon = "·"; color = orange;
                    } else if ("in_progress".equals(s.status)) {
                        icon = "▶"; color = orange;
                    } else if ("queued".equals(s.status)) {
                        icon = "○"; color = gray;
                    } else {
                        icon = "?"; color = gray;
                    }
                    ssb.append(icon).append(" ").append(s.name);
                    ssb.setSpan(new ForegroundColorSpan(color), start, ssb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if ("in_progress".equals(s.status) || "completed".equals(s.status) && "failure".equals(s.conclusion)) {
                        ssb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, ssb.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    ssb.append("\n");
                }
                steps.setText(ssb);
            } else if (j.stepCount > 0) {
                steps.setVisibility(View.VISIBLE);
                steps.setText("Шагов: " + j.stepCount
                        + (j.failedSteps > 0 ? " (ошибок: " + j.failedSteps + ")" : ""));
            } else {
                steps.setVisibility(View.GONE);
            }

            return v;
        }
    }

    // -------------------------------------------------------------- model

    private static class StepInfo {
        int number;
        String name = "";
        String status = "";
        String conclusion = "";
    }

    private static class JobItem {
        long id;
        String name = "";
        String status = "";
        String conclusion = "";
        String startedAt = "";
        String completedAt = "";
        String runnerName = "";
        int stepCount;
        int completedSteps;
        int failedSteps;
        List<StepInfo> stepDetails = new ArrayList<>();

        static JobItem from(JSONObject o) {
            JobItem j = new JobItem();
            j.id = o.optLong("id");
            j.name = o.optString("name", "");
            j.status = o.optString("status", "");
            j.conclusion = o.optString("conclusion", "");
            j.startedAt = o.optString("started_at", "");
            j.completedAt = o.optString("completed_at", "");
            j.runnerName = o.optString("runner_name", "");
            JSONArray steps = o.optJSONArray("steps");
            if (steps != null) {
                j.stepCount = steps.length();
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject s = steps.optJSONObject(i);
                    if (s == null) continue;
                    StepInfo si = new StepInfo();
                    si.number = s.optInt("number", i + 1);
                    si.name = s.optString("name", "шаг " + (i + 1));
                    si.status = s.optString("status", "");
                    si.conclusion = s.optString("conclusion", "");
                    j.stepDetails.add(si);
                    if ("completed".equals(si.status)) j.completedSteps++;
                    if ("failure".equals(si.conclusion) || "timed_out".equals(si.conclusion))
                        j.failedSteps++;
                }
            }
            return j;
        }
    }
}
