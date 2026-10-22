package com.zigit.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Создание нового репозитория на GitHub.
 *
 * API: POST /user/repos — свой аккаунт, POST /orgs/{org}/repos — организация.
 * Токену нужны права на запись:
 *   • classic — галочка scope «repo»;
 *   • fine-grained — Repository permissions: Administration: Read and write,
 *     Contents: Read and write, Metadata: Read-only.
 *
 * Особенность GitHub: gitignore_template и license_template применяются только
 * вместе с auto_init, поэтому при их выборе первый коммит создаётся автоматически.
 */
public class NewRepoActivity extends Activity {

    /** Возвращаемый результат: полное имя репозитория owner/name. */
    static final String EXTRA_REPO = "repo";

    private static final String[] GITIGNORE = {
            "нет", "Android", "Java", "Kotlin", "Python", "Node", "C", "C++", "Go", "Rust",
            "Swift", "PHP", "Ruby", "Dart", "Unity", "VisualStudioCode"
    };

    private static final String[] LICENSE_LABELS = {
            "нет", "MIT", "Apache-2.0", "GPL-3.0", "GPL-2.0", "AGPL-3.0", "BSD-3-Clause",
            "BSD-2-Clause", "MPL-2.0", "Unlicense", "CC0-1.0"
    };
    private static final String[] LICENSE_KEYS = {
            "", "mit", "apache-2.0", "gpl-3.0", "gpl-2.0", "agpl-3.0", "bsd-3-clause",
            "bsd-2-clause", "mpl-2.0", "unlicense", "cc0-1.0"
    };

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Логины, куда можно создать репозиторий: свой аккаунт + организации. */
    private final List<String> owners = new ArrayList<>();
    private ArrayAdapter<String> ownerAdapter;

    private Spinner ownerSpinner, gitignoreSpinner, licenseSpinner;
    private EditText nameInput, descInput;
    private CheckBox privateCheck, readmeCheck;
    private Button createBtn, openAppBtn, openWebBtn, copyBtn;
    private TextView hint, status, resultText;
    private View progressRow, resultBox;

    private String createdRepo;
    private String createdUrl;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_new_repo);

        ownerSpinner = findViewById(R.id.ownerSpinner);
        gitignoreSpinner = findViewById(R.id.gitignoreSpinner);
        licenseSpinner = findViewById(R.id.licenseSpinner);
        nameInput = findViewById(R.id.nameInput);
        descInput = findViewById(R.id.descInput);
        privateCheck = findViewById(R.id.privateCheck);
        readmeCheck = findViewById(R.id.readmeCheck);
        createBtn = findViewById(R.id.createBtn);
        openAppBtn = findViewById(R.id.openAppBtn);
        openWebBtn = findViewById(R.id.openWebBtn);
        copyBtn = findViewById(R.id.copyBtn);
        hint = findViewById(R.id.hint);
        status = findViewById(R.id.status);
        resultText = findViewById(R.id.resultText);
        progressRow = findViewById(R.id.progressRow);
        resultBox = findViewById(R.id.resultBox);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());

        ownerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, owners);
        ownerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ownerSpinner.setAdapter(ownerAdapter);

        gitignoreSpinner.setAdapter(spinnerAdapter(GITIGNORE));
        licenseSpinner.setAdapter(spinnerAdapter(LICENSE_LABELS));

        createBtn.setOnClickListener(v -> create());
        openAppBtn.setOnClickListener(v -> openInApp());
        openWebBtn.setOnClickListener(v -> openInBrowser());
        copyBtn.setOnClickListener(v -> copyLink());

        if (Store.token(this).isEmpty()) {
            setHint("Для создания репозитория нужен токен GitHub с правами на запись.\n\n"
                    + "Classic-токен — галочка «repo». Fine-grained — Repository permissions: "
                    + "Administration: Read and write, Contents: Read and write.\n\n"
                    + "Нажмите здесь, чтобы ввести токен.");
            hint.setOnClickListener(v -> TokenDialog.show(this, () -> {
                setHint(null);
                loadOwners();
            }));
            createBtn.setEnabled(false);
        } else {
            loadOwners();
        }
    }

    private ArrayAdapter<String> spinnerAdapter(String[] values) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>(Arrays.asList(values)));
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    // ------------------------------------------------------------ владельцы

    /** Загружает аккаунт токена и организации, куда можно положить новый репозиторий. */
    private void loadOwners() {
        if (Store.token(this).isEmpty()) return;
        setBusy(true);
        status.setText("Загружаю список аккаунтов…");
        pool.execute(() -> {
            try {
                final String token = Store.token(this);
                JSONObject me = new JSONObject(Api.get(Api.API + "/user", token).body);
                final String login = me.optString("login", "");
                final List<String> orgs = new ArrayList<>();
                orgs.add(login);
                try {
                    JSONArray arr = new JSONArray(
                            Api.get(Api.API + "/user/orgs?per_page=100", token).body);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) {
                            String l = o.optString("login", "");
                            if (!l.isEmpty() && !l.equals(login)) orgs.add(l);
                        }
                    }
                } catch (Exception ignored) {
                }
                ui.post(() -> {
                    if (isFinishing()) return;
                    owners.clear();
                    owners.addAll(orgs);
                    ownerAdapter.notifyDataSetChanged();
                    setBusy(false);
                    setHint(null);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing()) return;
                    setBusy(false);
                    setHint("Не удалось получить аккаунт: " + msg);
                    if (code == 401 || code == 403) {
                        TokenDialog.askForToken(this, "GitHub не принял токен:\n" + msg,
                                () -> loadOwners());
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------ создание

    private void create() {
        if (busy) return;
        String name = nameInput.getText().toString().trim().replace(' ', '-');
        String err = nameError(name);
        if (err != null) {
            nameInput.setError(err);
            nameInput.requestFocus();
            return;
        }
        nameInput.setError(null);

        int pos = ownerSpinner.getSelectedItemPosition();
        final String owner = (pos >= 0 && pos < owners.size()) ? owners.get(pos) : null;
        if (owner == null || owner.isEmpty()) {
            toast("Подождите: загружаю список аккаунтов");
            loadOwners();
            return;
        }

        final String description = descInput.getText().toString().trim();
        final boolean isPrivate = privateCheck.isChecked();
        final String gitignore = gitignoreSpinner.getSelectedItemPosition() > 0
                ? GITIGNORE[gitignoreSpinner.getSelectedItemPosition()] : "";
        final String license = licenseSpinner.getSelectedItemPosition() > 0
                ? LICENSE_KEYS[licenseSpinner.getSelectedItemPosition()] : "";
        final boolean autoInit = readmeCheck.isChecked()
                || !gitignore.isEmpty() || !license.isEmpty();

        hideKeyboard();
        nameInput.setText(name);
        setBusy(true);
        resultBox.setVisibility(View.GONE);
        status.setText("Создаю репозиторий " + owner + "/" + name + "…");

        pool.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("name", name);
                if (!description.isEmpty()) body.put("description", description);
                body.put("private", isPrivate);
                body.put("auto_init", autoInit);
                if (!gitignore.isEmpty()) body.put("gitignore_template", gitignore);
                if (!license.isEmpty()) body.put("license_template", license);

                final String url = owner.equals(owners.get(0))
                        ? Api.API + "/user/repos"
                        : Api.API + "/orgs/" + owner + "/repos";
                Api.Response r = Api.post(url, Store.token(this), body);
                JSONObject o = new JSONObject(r.body);
                final String fullName = o.optString("full_name", owner + "/" + name);
                final String htmlUrl = o.optString("html_url", "https://github.com/" + fullName);
                final boolean priv = o.optBoolean("private", isPrivate);
                final String branch = o.optString("default_branch", "");

                ui.post(() -> {
                    if (isFinishing()) return;
                    setBusy(false);
                    createdRepo = fullName;
                    createdUrl = htmlUrl;
                    StringBuilder sb = new StringBuilder();
                    sb.append(fullName).append("  ·  ").append(priv ? "private" : "public");
                    if (!branch.isEmpty()) sb.append("\nВетка по умолчанию: ").append(branch);
                    sb.append("\n\n").append(htmlUrl);
                    resultText.setText(sb.toString());
                    resultBox.setVisibility(View.VISIBLE);
                    toast("Репозиторий " + fullName + " создан");
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing()) return;
                    setBusy(false);
                    status.setText("");
                    if (code == 401 || code == 403) {
                        TokenDialog.askForToken(this,
                                "GitHub не дал прав на создание репозитория:\n" + msg + "\n\n"
                                        + "Нужны права на запись: у classic-токена — scope «repo»; "
                                        + "у fine-grained — Administration и Contents: Read and write.",
                                () -> loadOwners());
                    } else if (code == 422) {
                        setHint("GitHub отклонил запрос:\n" + msg + "\n\n"
                                + "Чаще всего имя уже занято на аккаунте — измените его.");
                    } else {
                        toast("Ошибка: " + msg);
                    }
                });
            }
        });
    }

    /** Проверка имени по правилам GitHub: буквы, цифры, -, _ и точка. */
    private String nameError(String n) {
        if (n.isEmpty()) return "Введите имя репозитория";
        if (n.length() > 100) return "Имя длиннее 100 символов";
        if (".".equals(n) || "..".equals(n)) return "Такое имя использовать нельзя";
        if (!n.matches("[A-Za-z0-9._-]+")) return "Допустимы только буквы, цифры, -, _ и .";
        if (n.toLowerCase().endsWith(".git")) return "Имя не должно заканчиваться на .git";
        return null;
    }

    // ------------------------------------------------------------- результат

    private void openInApp() {
        if (createdRepo == null) return;
        Intent data = new Intent();
        data.putExtra(EXTRA_REPO, createdRepo);
        setResult(RESULT_OK, data);
        finish();
    }

    private void openInBrowser() {
        if (createdUrl == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(createdUrl)));
        } catch (Exception e) {
            toast("Нет браузера");
        }
    }

    private void copyLink() {
        if (createdUrl == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("repo", createdUrl));
            toast("Ссылка скопирована");
        } catch (Exception e) {
            toast(createdUrl);
        }
    }

    // ------------------------------------------------------------------- UI

    private void setBusy(boolean b) {
        busy = b;
        createBtn.setEnabled(!b);
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
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(nameInput.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
