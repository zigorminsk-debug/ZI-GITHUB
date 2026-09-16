package com.zigit.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
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
 * Экран логов запуска Actions: список jobs и их логи.
 * Копирование строки — long-press на текст (системное выделение);
 * весь лог — кнопка «Копировать всё».
 */
public class LogsActivity extends Activity {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<JobItem> jobs = new ArrayList<>();

    private String repo;
    private long runId;
    private String runTitle;

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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_logs);

        repo = getIntent().getStringExtra("repo");
        runId = getIntent().getLongExtra("runId", 0);
        runTitle = getIntent().getStringExtra("title");
        String branch = getIntent().getStringExtra("branch");
        int number = getIntent().getIntExtra("number", 0);
        String conclusion = getIntent().getStringExtra("conclusion");

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
        if (conclusion != null && !conclusion.isEmpty()) sb.append(sb.length() > 0 ? "  ·  " : "").append(conclusion);
        sb.append(sb.length() > 0 ? "  ·  " : "").append(repo);
        subtitle.setText(sb.toString());

        adapter = new JobAdapter();
        jobsList.setAdapter(adapter);

        findViewById(R.id.backBtn).setOnClickListener(v -> {
            if (showingLog) {
                showJobsList();
            } else {
                finish();
            }
        });
        findViewById(R.id.refreshBtn).setOnClickListener(v -> loadJobs());
        copyAllBtn.setOnClickListener(v -> copyAllLog());
        shareBtn.setOnClickListener(v -> shareLog());

        jobsList.setOnItemClickListener((parent, view, position, id) -> {
            JobItem j = jobs.get(position);
            loadJobLog(j);
        });

        updateTokenBanner();
        loadJobs();
    }

    private void updateTokenBanner() {
        TextView tokenBanner = findViewById(R.id.tokenBanner);
        tokenBanner.setVisibility(Store.token(this).isEmpty() ? View.VISIBLE : View.GONE);
        tokenBanner.setOnClickListener(v -> TokenDialog.show(this, () -> {
            updateTokenBanner();
            loadJobs();
        }));
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
            logText.setText("(лог пуст)");
        } else {
            logText.setText(log);
        }

        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
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
        public int getCount() {
            return jobs.size();
        }

        @Override
        public Object getItem(int position) {
            return jobs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return jobs.get(position).id;
        }

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

            name.setText(j.name);

            String label = Models.statusLabel(j.status, j.conclusion);
            badge.setText(label);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(Util.dp(LogsActivity.this, 10));
            bg.setColor(getResources().getColor(
                    Models.statusColorRes(j.status, j.conclusion)));
            int px = Util.dp(LogsActivity.this, 8);
            badge.setPadding(px, px / 3, px, px / 3);
            badge.setBackground(bg);

            StringBuilder sb = new StringBuilder();
            if (j.startedAt != null && !j.startedAt.isEmpty()) {
                sb.append(Util.dateTime(j.startedAt));
            }
            if (j.runnerName != null && !j.runnerName.isEmpty()) {
                sb.append(sb.length() > 0 ? "  ·  " : "").append(j.runnerName);
            }
            sub.setText(sb.toString());

            if (j.stepCount > 0) {
                steps.setVisibility(View.VISIBLE);
                int failed = j.failedSteps;
                steps.setText("Шагов: " + j.stepCount
                        + (failed > 0 ? " (ошибок: " + failed + ")" : "")
                        + "  \u2192");
            } else {
                steps.setVisibility(View.GONE);
            }

            return v;
        }
    }

    // -------------------------------------------------------------- model

    private static class JobItem {
        long id;
        String name = "";
        String status = "";
        String conclusion = "";
        String startedAt = "";
        String completedAt = "";
        String runnerName = "";
        int stepCount;
        int failedSteps;

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
                    if (s != null) {
                        String c = s.optString("conclusion", "");
                        if ("failure".equals(c) || "timed_out".equals(c)) j.failedSteps++;
                    }
                }
            }
            return j;
        }
    }
}
