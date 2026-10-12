package com.zigit.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Загрузка файлов и текста в репозиторий через GitHub Contents API.
 *
 * API: PUT /repos/{owner}/{repo}/contents/{path}
 * Тело: { "message": "commit msg", "content": "base64", "branch": "main" }
 * Для обновления: + "sha": "текущий sha файла"
 */
public class UploadActivity extends Activity {

    private static final int REQ_PICK_FILE = 50;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String repo;
    private String defaultBranch;

    private boolean fileMode = true;
    private Uri pickedFileUri;
    private String pickedFileName;
    private byte[] pickedFileData;

    private Button modeFileBtn, modeTextBtn, pickFileBtn, uploadBtn;
    private Button openWebBtn, copyLinkBtn;
    private TextView fileName, hint, status, subtitle;
    private EditText pathInput, branchInput, messageInput, contentInput;
    private View fileSection, textSection, progressRow, resultBox;
    private TextView resultText;

    private boolean busy;
    private String uploadedUrl;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_upload);

        repo = getIntent().getStringExtra("repo");
        defaultBranch = getIntent().getStringExtra("branch");
        if (defaultBranch == null || defaultBranch.isEmpty()) defaultBranch = "main";

        if (repo == null || !repo.contains("/")) {
            toast("Репозиторий не указан");
            finish();
            return;
        }

        subtitle = findViewById(R.id.subtitle);
        subtitle.setText(repo);

        modeFileBtn = findViewById(R.id.modeFileBtn);
        modeTextBtn = findViewById(R.id.modeTextBtn);
        pickFileBtn = findViewById(R.id.pickFileBtn);
        uploadBtn = findViewById(R.id.uploadBtn);
        openWebBtn = findViewById(R.id.openWebBtn);
        copyLinkBtn = findViewById(R.id.copyLinkBtn);
        fileName = findViewById(R.id.fileName);
        hint = findViewById(R.id.hint);
        status = findViewById(R.id.status);
        pathInput = findViewById(R.id.pathInput);
        branchInput = findViewById(R.id.branchInput);
        messageInput = findViewById(R.id.messageInput);
        contentInput = findViewById(R.id.contentInput);
        fileSection = findViewById(R.id.fileSection);
        textSection = findViewById(R.id.textSection);
        progressRow = findViewById(R.id.progressRow);
        resultBox = findViewById(R.id.resultBox);
        resultText = findViewById(R.id.resultText);

        branchInput.setText(defaultBranch);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        modeFileBtn.setOnClickListener(v -> setMode(true));
        modeTextBtn.setOnClickListener(v -> setMode(false));
        pickFileBtn.setOnClickListener(v -> pickFile());
        uploadBtn.setOnClickListener(v -> upload());
        openWebBtn.setOnClickListener(v -> openInBrowser());
        copyLinkBtn.setOnClickListener(v -> copyLink());

        if (Store.token(this).isEmpty()) {
            setHint("Для загрузки файлов нужен токен с правами на запись.\n\n"
                    + "Classic — scope «repo». Fine-grained — Contents: Read and write.\n\n"
                    + "Нажмите здесь, чтобы ввести токен.");
            hint.setOnClickListener(v -> TokenDialog.show(this, () -> {
                setHint(null);
            }));
            uploadBtn.setEnabled(false);
        }
    }

    // ------------------------------------------------------------ mode

    private void setMode(boolean file) {
        fileMode = file;
        int green = getResources().getColor(R.color.green);
        int border = getResources().getColor(R.color.border);
        int text = getResources().getColor(R.color.text);

        if (file) {
            fileSection.setVisibility(View.VISIBLE);
            textSection.setVisibility(View.GONE);
            modeFileBtn.setTextColor(Color.WHITE);
            modeFileBtn.getBackground().setTint(green);
            modeTextBtn.setTextColor(text);
            modeTextBtn.getBackground().setTint(border);
        } else {
            fileSection.setVisibility(View.GONE);
            textSection.setVisibility(View.VISIBLE);
            modeTextBtn.setTextColor(Color.WHITE);
            modeTextBtn.getBackground().setTint(green);
            modeFileBtn.setTextColor(text);
            modeFileBtn.getBackground().setTint(border);
        }
        resultBox.setVisibility(View.GONE);
    }

    // --------------------------------------------------------- pick file

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        try {
            startActivityForResult(i, REQ_PICK_FILE);
        } catch (Exception e) {
            // fallback
            Intent i2 = new Intent(Intent.ACTION_GET_CONTENT);
            i2.addCategory(Intent.CATEGORY_OPENABLE);
            i2.setType("*/*");
            startActivityForResult(i2, REQ_PICK_FILE);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_FILE && res == RESULT_OK && data != null) {
            pickedFileUri = data.getData();
            if (pickedFileUri == null) return;

            // получаем имя файла
            pickedFileName = null;
            try {
                android.database.Cursor c = getContentResolver().query(
                        pickedFileUri, null, null, null, null);
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) pickedFileName = c.getString(idx);
                    c.close();
                }
            } catch (Exception ignored) {
            }
            if (pickedFileName == null) {
                pickedFileName = pickedFileUri.getLastPathSegment();
            }
            if (pickedFileName == null) pickedFileName = "file";

            fileName.setText(pickedFileName);
            fileName.setTextColor(getResources().getColor(R.color.text));

            // автозаполнение пути
            if (pathInput.getText().toString().isEmpty()) {
                pathInput.setText(pickedFileName);
            }

            // читаем содержимое файла
            pool.execute(() -> {
                try {
                    InputStream in = getContentResolver().openInputStream(pickedFileUri);
                    if (in == null) throw new Exception("Не удалось открыть файл");
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    in.close();
                    pickedFileData = bos.toByteArray();
                    final long size = pickedFileData.length;
                    ui.post(() -> {
                        if (isFinishing()) return;
                        fileName.setText(pickedFileName + " (" + Util.humanSize(size) + ")");
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        if (isFinishing()) return;
                        fileName.setText("Ошибка чтения: " + e.getMessage());
                        fileName.setTextColor(getResources().getColor(R.color.red));
                        pickedFileData = null;
                    });
                }
            });
        }
    }

    // ------------------------------------------------------------ upload

    private void upload() {
        if (busy) return;
        hideKeyboard();

        String path = pathInput.getText().toString().trim();
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);

        if (path.isEmpty()) {
            pathInput.setError("Укажите путь в репозитории");
            pathInput.requestFocus();
            return;
        }
        pathInput.setError(null);

        String branch = branchInput.getText().toString().trim();
        if (branch.isEmpty()) branch = defaultBranch;

        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) message = "Upload " + path + " via ZI Git";

        final byte[] data;
        if (fileMode) {
            if (pickedFileData == null) {
                toast("Выберите файл для загрузки");
                return;
            }
            data = pickedFileData;
        } else {
            String text = contentInput.getText().toString();
            if (text.isEmpty()) {
                toast("Введите содержимое");
                return;
            }
            try {
                data = text.getBytes("UTF-8");
            } catch (Exception e) {
                toast("Ошибка кодирования: " + e.getMessage());
                return;
            }
        }

        // Лимит GitHub: 100 МБ через API (но на практике ~50 МБ надёжнее)
        if (data.length > 50 * 1024 * 1024) {
            toast("Файл слишком большой для загрузки через API (макс. 50 МБ).\n"
                    + "Размер: " + Util.humanSize(data.length));
            return;
        }

        final String fPath = path;
        final String fBranch = branch;
        final String fMessage = message;

        setBusy(true);
        status.setText("Загружаю " + fPath + "…");
        resultBox.setVisibility(View.GONE);

        pool.execute(() -> {
            try {
                // сначала проверяем, есть ли файл (для обновления нужен sha)
                String sha = null;
                try {
                    JSONObject existing = Api.json(Api.API + "/repos/" + repo
                            + "/contents/" + Uri.encode(fPath, "/")
                            + "?ref=" + Uri.encode(fBranch), Store.token(this));
                    sha = existing.optString("sha", null);
                } catch (Exception ignored) {
                    // файла нет — создаём новый
                }

                // кодируем в base64
                String content = Base64.encodeToString(data, Base64.NO_WRAP);

                JSONObject body = new JSONObject();
                body.put("message", fMessage);
                body.put("content", content);
                body.put("branch", fBranch);
                if (sha != null) body.put("sha", sha);

                Api.Response r = Api.send("PUT", Api.API + "/repos/" + repo
                        + "/contents/" + Uri.encode(fPath, "/"),
                        Store.token(this), body);

                JSONObject resp = new JSONObject(r.body);
                JSONObject contentObj = resp.optJSONObject("content");
                final String htmlUrl = contentObj != null
                        ? contentObj.optString("html_url", "") : "";
                final String action = sha != null ? "обновлён" : "создан";

                uploadedUrl = htmlUrl;

                ui.post(() -> {
                    if (isFinishing()) return;
                    setBusy(false);
                    resultText.setText("Файл " + action + ": " + fPath
                            + "\nВетка: " + fBranch
                            + "\nКоммит: " + fMessage
                            + (htmlUrl.isEmpty() ? "" : "\n" + htmlUrl));
                    resultBox.setVisibility(View.VISIBLE);
                    toast("Загружено: " + fPath);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing()) return;
                    setBusy(false);
                    if (code == 401 || code == 403) {
                        TokenDialog.askForToken(this,
                                "Нет прав на загрузку файлов:\n" + msg + "\n\n"
                                        + "Нужны права: Contents: Read and write.",
                                () -> {});
                    } else if (code == 409) {
                        setHint("Конфликт: файл был изменён. Попробуйте ещё раз.");
                    } else if (code == 422) {
                        setHint("GitHub отклонил файл:\n" + msg
                                + "\n\nВозможно, файл слишком большой или путь некорректен.");
                    } else {
                        toast("Ошибка: " + msg);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------- result

    private void openInBrowser() {
        if (uploadedUrl == null || uploadedUrl.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uploadedUrl)));
        } catch (Exception e) {
            toast("Нет браузера");
        }
    }

    private void copyLink() {
        if (uploadedUrl == null || uploadedUrl.isEmpty()) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("file", uploadedUrl));
            toast("Ссылка скопирована");
        } catch (Exception e) {
            toast(uploadedUrl);
        }
    }

    // ------------------------------------------------------------------ UI

    private void setBusy(boolean b) {
        busy = b;
        uploadBtn.setEnabled(!b);
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
