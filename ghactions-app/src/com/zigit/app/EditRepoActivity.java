package com.zigit.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Редактирование настроек репозитория на GitHub.
 * API: PATCH /repos/{owner}/{repo}
 * Темы: PUT /repos/{owner}/{repo}/topics (отдельный эндпоинт)
 */
public class EditRepoActivity extends Activity {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String repo;
    private String owner, repoName;
    private boolean isPrivate;
    private boolean isArchived;
    private String originalTopics = "";

    private EditText nameInput, descInput, homepageInput, branchInput, topicsInput;
    private Button publicBtn, privateBtn, saveBtn;
    private TextView hint, status, subtitle, visibilityNote;
    private CheckBox archiveCheck;
    private View progressRow;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_edit_repo);

        repo = getIntent().getStringExtra("repo");
        if (repo == null || !repo.contains("/")) {
            toast("Репозиторий не указан");
            finish();
            return;
        }
        String[] parts = repo.split("/");
        owner = parts[0];
        repoName = parts[1];

        subtitle = findViewById(R.id.subtitle);
        subtitle.setText(repo);

        nameInput = findViewById(R.id.nameInput);
        descInput = findViewById(R.id.descInput);
        homepageInput = findViewById(R.id.homepageInput);
        branchInput = findViewById(R.id.branchInput);
        topicsInput = findViewById(R.id.topicsInput);
        publicBtn = findViewById(R.id.publicBtn);
        privateBtn = findViewById(R.id.privateBtn);
        saveBtn = findViewById(R.id.saveBtn);
        hint = findViewById(R.id.hint);
        status = findViewById(R.id.status);
        visibilityNote = findViewById(R.id.visibilityNote);
        archiveCheck = findViewById(R.id.archiveCheck);
        progressRow = findViewById(R.id.progressRow);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        publicBtn.setOnClickListener(v -> setVisibility(false));
        privateBtn.setOnClickListener(v -> setVisibility(true));
        saveBtn.setOnClickListener(v -> save());

        if (Store.token(this).isEmpty()) {
            setHint("Для редактирования репозитория нужен токен с правами на запись.\n\n"
                    + "Classic — scope «repo». Fine-grained — Administration: Read and write.\n\n"
                    + "Нажмите здесь, чтобы ввести токен.");
            hint.setOnClickListener(v -> TokenDialog.show(this, () -> {
                setHint(null);
                load();
            }));
            saveBtn.setEnabled(false);
        } else {
            load();
        }
    }

    // ------------------------------------------------------------ загрузка

    private void load() {
        setBusy(true);
        status.setText("Загружаю настройки…");
        pool.execute(() -> {
            try {
                JSONObject o = Api.json(Api.API + "/repos/" + repo, Store.token(this));
                final String name = o.optString("name", repoName);
                final String desc = o.optString("description", "");
                final String homepage = o.optString("homepage", "");
                final String branch = o.optString("default_branch", "");
                final boolean priv = o.optBoolean("private", false);
                final boolean archived = o.optBoolean("archived", false);

                // темы — отдельный запрос
                String topics = "";
                try {
                    JSONObject t = Api.json(Api.API + "/repos/" + repo + "/topics",
                            Store.token(this));
                    JSONArray arr = t.optJSONArray("names");
                    if (arr != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < arr.length(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(arr.getString(i));
                        }
                        topics = sb.toString();
                    }
                } catch (Exception ignored) {
                }

                final String finalTopics = topics;
                ui.post(() -> {
                    if (isFinishing()) return;
                    setBusy(false);
                    nameInput.setText(name);
                    descInput.setText(desc);
                    homepageInput.setText(homepage);
                    branchInput.setText(branch);
                    topicsInput.setText(finalTopics);
                    originalTopics = finalTopics;
                    isPrivate = priv;
                    isArchived = archived;
                    archiveCheck.setChecked(archived);
                    setVisibility(priv);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing()) return;
                    setBusy(false);
                    setHint("Ошибка загрузки: " + msg);
                    if (code == 401 || code == 403) {
                        TokenDialog.askForToken(this,
                                "GitHub не принял запрос:\n" + msg,
                                this::load);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------- save

    private void save() {
        if (busy) return;
        hideKeyboard();

        final String newName = nameInput.getText().toString().trim();
        if (newName.isEmpty()) {
            nameInput.setError("Имя не может быть пустым");
            return;
        }
        nameInput.setError(null);

        final String desc = descInput.getText().toString().trim();
        final String homepage = homepageInput.getText().toString().trim();
        final String branch = branchInput.getText().toString().trim();
        final String topics = topicsInput.getText().toString().trim();
        final boolean archive = archiveCheck.isChecked();

        setBusy(true);
        status.setText("Сохраняю изменения…");

        pool.execute(() -> {
            try {
                // основной PATCH
                JSONObject body = new JSONObject();
                body.put("name", newName);
                body.put("description", desc.isEmpty() ? JSONObject.NULL : desc);
                body.put("homepage", homepage.isEmpty() ? JSONObject.NULL : homepage);
                body.put("private", isPrivate);
                body.put("default_branch", branch);
                body.put("archived", archive);

                Api.send("PATCH", Api.API + "/repos/" + repo,
                        Store.token(this), body);

                // темы — отдельный PUT (нужен Accept: application/vnd.github.mercy-preview+json)
                if (!topics.equals(originalTopics)) {
                    JSONObject topicBody = new JSONObject();
                    JSONArray arr = new JSONArray();
                    if (!topics.isEmpty()) {
                        for (String t : topics.split(",")) {
                            String trimmed = t.trim().toLowerCase().replaceAll("[^a-z0-9-]", "-");
                            if (!trimmed.isEmpty()) arr.put(trimmed);
                        }
                    }
                    topicBody.put("names", arr);
                    Api.send("PUT", Api.API + "/repos/" + repo + "/topics",
                            Store.token(this), topicBody);
                }

                final String newRepo = owner + "/" + newName;
                ui.post(() -> {
                    if (isFinishing()) return;
                    setBusy(false);
                    toast("Настройки сохранены");
                    // возвращаем новое имя репозитория (могло измениться)
                    Intent data = new Intent();
                    data.putExtra("repo", newRepo);
                    setResult(RESULT_OK, data);
                    finish();
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing()) return;
                    setBusy(false);
                    if (code == 401 || code == 403) {
                        TokenDialog.askForToken(this,
                                "Нет прав на редактирование:\n" + msg + "\n\n"
                                        + "Нужны права: Administration: Read and write.",
                                this::load);
                    } else if (code == 422) {
                        setHint("GitHub отклонил изменения:\n" + msg
                                + "\n\nВозможно, имя уже занято или ветка не существует.");
                    } else {
                        toast("Ошибка: " + msg);
                    }
                });
            }
        });
    }

    // -------------------------------------------------------- visibility

    private void setVisibility(boolean priv) {
        isPrivate = priv;
        int green = getResources().getColor(R.color.green);
        int border = getResources().getColor(R.color.border);
        int text = getResources().getColor(R.color.text);

        if (priv) {
            privateBtn.setTextColor(Color.WHITE);
            privateBtn.getBackground().setTint(green);
            publicBtn.setTextColor(text);
            publicBtn.getBackground().setTint(border);
            visibilityNote.setText("🔒 Приватный: виден только вам и коллабораторам");
        } else {
            publicBtn.setTextColor(Color.WHITE);
            publicBtn.getBackground().setTint(green);
            privateBtn.setTextColor(text);
            privateBtn.getBackground().setTint(border);
            visibilityNote.setText("🌍 Публичный: виден всем");
        }
    }

    // ---------------------------------------------------------------- UI

    private void setBusy(boolean b) {
        busy = b;
        saveBtn.setEnabled(!b);
        progressRow.setVisibility(b ? View.VISIBLE : View.GONE);
        if (!b) status.setText("");
    }

    private void setHint(String text) {
        if (text == null || text.isEmpty()) {
            hint.setVisibility(View.GONE);
        } else {
            hint.setText(text);
            hint.setVisibility(View.VISIBLE);
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            View focus = getCurrentFocus();
            if (im != null && focus != null)
                im.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
