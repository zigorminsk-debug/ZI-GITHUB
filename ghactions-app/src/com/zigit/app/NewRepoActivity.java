package com.zigit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Новый репозиторий на GitHub: владелец (аккаунт или организация), имя, описание,
 * видимость и первый коммит (README + .gitignore + лицензия).
 *
 * Главная особенность экрана — диагностика прав. GitHub не отдаёт список разрешений
 * fine-grained токена через API, поэтому права проверяются «на живом» запросе:
 * POST /user/repos (или /orgs/{org}/repos). Если GitHub отвечает 403
 * «Resource not accessible by personal access token», экран объясняет, что именно
 * включить в токене (Administration + Contents: Read and write) или что не так
 * с classic-токеном (нужен scope «repo»).
 */
public class NewRepoActivity extends Activity {

    static final String EXTRA_REPO = "repo";
    static final String EXTRA_OPEN = "open";

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> orgs = new ArrayList<>();

    private EditText ownerInput, nameInput, descInput;
    private CheckBox privateBox, initBox;
    private Button createBtn;
    private TextView info, ownerPick;

    /** Аккаунт, которому принадлежит токен (из /user). */
    private String login = "";
    /** Скоупы classic-токена (заголовок X-OAuth-Scopes); у fine-grained пусто. */
    private String scopes = "";

    private boolean creating;
    private boolean created;
    private String createdFullName = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_new_repo);

        ownerInput = findViewById(R.id.ownerInput);
        nameInput = findViewById(R.id.nameInput);
        descInput = findViewById(R.id.descInput);
        privateBox = findViewById(R.id.privateBox);
        initBox = findViewById(R.id.initBox);
        createBtn = findViewById(R.id.createBtn);
        info = findViewById(R.id.info);
        ownerPick = findViewById(R.id.ownerPick);

        findViewById(R.id.backBtn).setOnClickListener(v -> onBackPressed());
        ownerPick.setOnClickListener(v -> pickOwner());
        createBtn.setOnClickListener(v -> create());
        initBox.setChecked(true);

        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        ownerInput.setInputType(InputType.TYPE_CLASS_TEXT);

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateCreateEnabled();
            }
        };
        ownerInput.addTextChangedListener(watcher);
        nameInput.addTextChangedListener(watcher);

        updateCreateEnabled();
        checkToken();
    }

    // ---------------------------------------------------------- токен/аккаунт

    /** Узнаём аккаунт токена, его тип, скоупы и организации — и показываем, чего не хватает. */
    private void checkToken() {
        final String token = Store.token(this);
        if (token.isEmpty()) {
            setInfo("Нужен токен GitHub: без него репозиторий не создать.\n"
                    + "Нажмите, чтобы ввести токен →");
            info.setOnClickListener(v -> TokenDialog.show(this, this::checkToken));
            return;
        }
        setInfo("Проверяю токен…");
        pool.execute(() -> {
            String lg = "";
            String sc = null;
            String error = null;
            final List<String> orgList = new ArrayList<>();
            try {
                Api.Response me = Api.get(Api.API + "/user", token);
                lg = new JSONObject(me.body).optString("login", "");
                sc = me.header("X-OAuth-Scopes");
                try {
                    JSONArray arr = new JSONArray(Api.get(Api.API + "/user/orgs?per_page=100", token).body);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) {
                            String org = o.optString("login", "");
                            if (!org.isEmpty()) orgList.add(org);
                        }
                    }
                } catch (Exception ignored) {
                    // организации видны не всем токенам — это не ошибка
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String flg = lg;
            final String fsc = sc;
            final String ferr = error;
            ui.post(() -> {
                if (isFinishing()) return;
                if (ferr != null) {
                    setInfo("Не удалось проверить токен: " + ferr
                            + "\nНажмите, чтобы открыть настройки токена →");
                    info.setOnClickListener(v -> TokenDialog.show(this, this::checkToken));
                    return;
                }
                applyAccount(flg, fsc, orgList);
            });
        });
    }

    private void applyAccount(String account, String scopesHeader, List<String> orgList) {
        login = account == null ? "" : account;
        scopes = scopesHeader == null ? "" : scopesHeader;
        orgs.clear();
        orgs.addAll(orgList);

        if (ownerInput.getText().toString().trim().isEmpty() && !login.isEmpty()) {
            ownerInput.setText(login);
        }
        ownerPick.setVisibility(orgs.isEmpty() ? View.GONE : View.VISIBLE);

        boolean fine = TokenInfo.isFineGrained(Store.token(this));
        StringBuilder sb = new StringBuilder();
        sb.append("Токен: ").append(login.isEmpty() ? "?" : login)
                .append("  ·  ").append(fine ? "fine-grained" : "classic");
        if (!fine) sb.append("  ·  скоупы: ").append(TokenInfo.scopesLabel(scopes));
        sb.append("\n");
        if (fine) {
            sb.append("Для создания репозитория нужны права Administration: Read and write ")
                    .append("и Contents: Read and write. GitHub не показывает их заранее — ")
                    .append("если чего-то не хватает, приложение объяснит это после попытки. ")
                    .append("Нажмите, чтобы посмотреть инструкцию →");
        } else if (!TokenInfo.hasScope(scopes, "repo")) {
            sb.append("⚠ В токене нет scope «repo» — GitHub откажет в записи. ")
                    .append("Нажмите, чтобы включить права →");
        } else {
            sb.append("Права на запись есть (scope «repo»). Если владелец — организация, ")
                    .append("может понадобиться SSO-авторизация токена. Нажмите, чтобы посмотреть инструкцию →");
        }
        setInfo(sb.toString());
        info.setOnClickListener(v -> showPermsHelp());
        updateCreateEnabled();
    }

    /** Выбор владельца: свой аккаунт или одна из организаций токена. */
    private void pickOwner() {
        if (orgs.isEmpty()) return;
        final List<String> all = new ArrayList<>();
        if (!login.isEmpty()) all.add(login + "  (мой аккаунт)");
        for (String o : orgs) all.add(o);
        new AlertDialog.Builder(this)
                .setTitle("Владелец репозитория")
                .setItems(all.toArray(new String[0]), (d, which) -> {
                    String name = all.get(which);
                    if (which == 0 && !login.isEmpty()) name = login;
                    else name = name.replace("  (мой аккаунт)", "").trim();
                    ownerInput.setText(name);
                    updateCreateEnabled();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ------------------------------------------------------------- создание

    private void create() {
        final String token = Store.token(this);
        if (token.isEmpty()) {
            TokenDialog.askForToken(this,
                    "Чтобы создать репозиторий, нужен токен GitHub с правами на запись.",
                    this::checkToken);
            return;
        }

        final String typedOwner = ownerInput.getText().toString().trim();
        final String name = nameInput.getText().toString().trim();
        final String desc = descInput.getText().toString().trim();

        if (typedOwner.isEmpty()) {
            ownerInput.setError("Укажите владельца репозитория");
            ownerInput.requestFocus();
            return;
        }
        String nameError = validateName(name);
        if (nameError != null) {
            nameInput.setError(nameError);
            nameInput.requestFocus();
            return;
        }

        final boolean priv = privateBox.isChecked();
        final boolean init = initBox.isChecked();

        creating = true;
        updateCreateEnabled();
        createBtn.setText("Создаю…");
        info.setOnClickListener(null);
        setInfo("Отправляю запрос на GitHub…");

        pool.execute(() -> {
            String fullName = null;
            String htmlUrl = null;
            int code = 0;
            String message = null;
            String body = null;
            try {
                // Владелец: свой аккаунт (/user/repos) или организация (/orgs/{org}/repos).
                // Если токен ещё не проверили — уточняем аккаунт и тип владельца, чтобы
                // случайно не создать репозиторий в своём аккаунте вместо организации.
                String owner = typedOwner;
                String account = login;
                if (account.isEmpty()) {
                    account = new JSONObject(Api.get(Api.API + "/user", token).body)
                            .optString("login", "");
                }
                String url;
                if (!account.isEmpty() && owner.equalsIgnoreCase(account)) {
                    owner = account;
                    url = Api.API + "/user/repos";
                } else if (isOrganization(owner, token)) {
                    url = Api.API + "/orgs/" + Uri.encode(owner) + "/repos";
                } else {
                    throw new Api.ApiException(0, "«" + owner + "» — не ваш аккаунт и не организация, "
                            + "где у вас есть права. Репозиторий можно создать только в своём аккаунте"
                            + (account.isEmpty() ? "" : " («" + account + "»)") + " или в организации.", null);
                }

                JSONObject payload = new JSONObject();
                payload.put("name", name);
                if (!desc.isEmpty()) payload.put("description", desc);
                payload.put("private", priv);
                payload.put("has_issues", true);
                payload.put("has_wiki", false);
                if (init) {
                    payload.put("auto_init", true);
                    payload.put("gitignore_template", "Android");
                    payload.put("license_template", "mit");
                }
                Api.Response r = Api.post(url, token, payload.toString());
                JSONObject o = new JSONObject(r.body);
                fullName = o.optString("full_name", owner + "/" + name);
                htmlUrl = o.optString("html_url", "https://github.com/" + fullName);
            } catch (Api.ApiException e) {
                code = e.code;
                message = e.getMessage();
                body = e.body;
            } catch (Exception e) {
                message = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String fFull = fullName;
            final String fHtml = htmlUrl;
            final String fMsg = message;
            final String fBody = body;
            final int fCode = code;
            ui.post(() -> {
                if (isFinishing()) return;
                creating = false;
                createBtn.setText("Создать репозиторий");
                if (fFull != null) {
                    created = true;
                    createdFullName = fFull;
                    Store.setRepo(this, fFull);
                    showCreated(fFull, fHtml);
                } else {
                    updateCreateEnabled();
                    applyAccount(login, scopes, orgs);
                    explainFailure(fCode, fMsg, fBody, typedOwner, name);
                }
            });
        });
    }

    /** Организация ли это (для выбора /orgs/{org}/repos вместо /user/repos). */
    private static boolean isOrganization(String owner, String token) {
        try {
            JSONObject o = new JSONObject(Api.get(Api.API + "/users/" + Uri.encode(owner), token).body);
            return "Organization".equalsIgnoreCase(o.optString("type", ""));
        } catch (Exception e) {
            return false;
        }
    }

    /** Правила GitHub для имени репозитория (проверяем заранее, чтобы не ждать 422). */
    private static String validateName(String name) {
        if (name == null || name.isEmpty()) return "Введите имя репозитория";
        if (name.length() > 100) return "Имя не длиннее 100 символов";
        if (name.startsWith(".")) return "Имя не может начинаться с точки";
        if (name.equals(".") || name.equals("..")) return "Такое имя GitHub не принимает";
        if (!name.matches("[A-Za-z0-9._-]+")) return "Разрешены латинские буквы, цифры, «.», «_», «-»";
        return null;
    }

    private void showCreated(final String fullName, final String htmlUrl) {
        boolean init = initBox.isChecked();
        new AlertDialog.Builder(this)
                .setTitle("Репозиторий создан")
                .setMessage(fullName + "\n\n" + (init
                        ? "Первый коммит уже сделан: README, .gitignore (Android) и лицензия MIT."
                        : "Репозиторий пустой: без первого коммита GitHub не запустит Actions."))
                .setPositiveButton("К запускам", (d, w) -> finishWith(true))
                .setNeutralButton("Открыть на GitHub", (d, w) -> {
                    openUrl(htmlUrl);
                    finishWith(false);
                })
                .setNegativeButton("Закрыть", (d, w) -> finishWith(false))
                .setCancelable(false)
                .show();
    }

    /** Дочитываем скоупы classic-токена (если ещё не знаем) и показываем точную причину. */
    private void explainFailure(final int code, final String apiMessage, final String body,
                               final String owner, final String name) {
        if (code == 0) {
            // не ответ GitHub, а наша проверка или сеть — советы про права тут не нужны
            new AlertDialog.Builder(this)
                    .setTitle("Не получилось создать репозиторий")
                    .setMessage(apiMessage == null ? "Неизвестная ошибка" : apiMessage)
                    .setPositiveButton("Понятно", null)
                    .show();
            return;
        }
        final String token = Store.token(this);
        final boolean fine = TokenInfo.isFineGrained(token);
        if (!fine && scopes.isEmpty()) {
            pool.execute(() -> {
                String sc = scopes;
                try {
                    sc = Api.get(Api.API + "/user", token).header("X-OAuth-Scopes");
                } catch (Exception ignored) {
                }
                final String fsc = sc == null ? "" : sc;
                ui.post(() -> {
                    if (isFinishing()) return;
                    scopes = fsc;
                    presentFailure(code, apiMessage, body, owner, name);
                });
            });
        } else {
            presentFailure(code, apiMessage, body, owner, name);
        }
    }

    private void presentFailure(int code, String apiMessage, String body, String owner, String name) {
        final boolean fine = TokenInfo.isFineGrained(Store.token(this));
        String text = TokenInfo.createFailureText(code, apiMessage, body, fine, login, scopes, owner, name);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Нужен токен GitHub")
                .setMessage(text)
                .setPositiveButton("Ввести токен", (d, w) -> TokenDialog.show(this, this::checkToken))
                .setNegativeButton("Позже", null);
        b.setNeutralButton(fine ? "Как выдать права" : "Создать classic токен", (d, w) -> {
            if (fine) {
                openUrl(TokenInfo.URL_FINE_TOKENS);
            } else {
                openUrl(TokenInfo.URL_NEW_CLASSIC);
            }
        });
        b.show();
    }

    // ---------------------------------------------------------------- прочее

    private void showPermsHelp() {
        final String token = Store.token(this);
        final boolean fine = TokenInfo.isFineGrained(token);
        String text = TokenInfo.writePermsText(fine, login, scopes, ownerInput.getText().toString().trim());
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Права токена на запись")
                .setMessage(text)
                .setPositiveButton(fine ? "Настроить токен" : "Открыть токены", (d, w) -> openUrl(
                        fine ? TokenInfo.URL_FINE_TOKENS : TokenInfo.URL_CLASSIC_TOKENS))
                .setNeutralButton("Ввести токен", (d, w) -> TokenDialog.show(this, this::checkToken))
                .setNegativeButton("Закрыть", null);
        b.show();
    }

    private void setInfo(String text) {
        if (text == null || text.isEmpty()) {
            info.setVisibility(View.GONE);
        } else {
            info.setText(text);
            info.setVisibility(View.VISIBLE);
        }
    }

    private void updateCreateEnabled() {
        boolean ready = !creating
                && !ownerInput.getText().toString().trim().isEmpty()
                && !nameInput.getText().toString().trim().isEmpty();
        createBtn.setEnabled(ready);
    }

    private void finishWith(boolean open) {
        if (created) {
            Intent data = new Intent();
            data.putExtra(EXTRA_REPO, createdFullName);
            data.putExtra(EXTRA_OPEN, open);
            setResult(RESULT_OK, data);
        }
        finish();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Нет браузера");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        finishWith(false);
    }
}
