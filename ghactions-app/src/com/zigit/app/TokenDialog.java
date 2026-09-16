package com.zigit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

/** Диалог ввода токена GitHub + встроенная проверка того, что именно видит токен. */
final class TokenDialog {

    private TokenDialog() {
    }

    interface Callback {
        void done(String report);
    }

    static void show(final Activity a, final Runnable onSaved) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Util.dp(a, 20);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView info = new TextView(a);
        info.setTextSize(13);
        info.setText("Зачем токен:\n"
                + "• GitHub отдаёт артефакты сборок только авторизованным запросам;\n"
                + "• доступ к приватным репозиториям;\n"
                + "• лимит 5000 запросов в час вместо 60.\n\n"
                + "Токен хранится только на этом устройстве. Создавайте его для того аккаунта, "
                + "в котором лежат ваши проекты.");
        box.addView(info);

        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("ghp_… или github_pat_…");
        input.setText(Store.token(a));
        box.addView(input);

        final TextView checkResult = new TextView(a);
        checkResult.setTextSize(12);
        checkResult.setPadding(0, Util.dp(a, 8), 0, 0);
        box.addView(checkResult);

        Button check = new Button(a);
        check.setText("Проверить, что видит токен");
        check.setOnClickListener(v -> {
            final String t = input.getText().toString().trim();
            if (t.isEmpty()) {
                Toast.makeText(a, "Введите токен", Toast.LENGTH_SHORT).show();
                return;
            }
            check.setEnabled(false);
            checkResult.setText("Проверяю…");
            describe(a, t, report -> {
                check.setEnabled(true);
                checkResult.setText(report);
            });
        });
        box.addView(check);

        Button settings = new Button(a);
        settings.setText("Настроить доступ существующего токена");
        settings.setOnClickListener(v ->
                open(a, "https://github.com/settings/personal-access-tokens"));
        box.addView(settings);

        Button fineGrained = new Button(a);
        fineGrained.setText("Создать fine-grained токен");
        fineGrained.setOnClickListener(v -> open(a, "https://github.com/settings/personal-access-tokens/new"));
        box.addView(fineGrained);

        Button classic = new Button(a);
        classic.setText("Создать classic токен (repo + workflow)");
        classic.setOnClickListener(v ->
                open(a, "https://github.com/settings/tokens/new?scopes=repo,workflow&description=Actions%20Loader"));
        box.addView(classic);

        new AlertDialog.Builder(a)
                .setTitle("Токен GitHub")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Store.setToken(a, input.getText().toString());
                    Toast.makeText(a, "Токен сохранён", Toast.LENGTH_SHORT).show();
                    if (onSaved != null) onSaved.run();
                })
                .setNeutralButton("Очистить", (d, w) -> {
                    Store.setToken(a, "");
                    Toast.makeText(a, "Токен удалён", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static void open(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(a, "Нет браузера", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Проверяет токен: кто владелец, какой тип, сколько репозиториев токен реально видит,
     * и объясняет, что делать, если их меньше, чем ожидалось.
     */
    static void describe(final Activity a, final String token, final Callback cb) {
        new Thread(() -> {
            final StringBuilder sb = new StringBuilder();
            try {
                Api.Response me = Api.get(Api.API + "/user", token);
                org.json.JSONObject u = new org.json.JSONObject(me.body);
                String login = u.optString("login", "?");
                String scopes = me.header("X-OAuth-Scopes");
                boolean fineGrained = token.startsWith("github_pat_");

                sb.append("Аккаунт токена: ").append(login).append("\n");
                sb.append("Тип токена: ").append(fineGrained ? "fine-grained" : "classic").append("\n");
                if (!fineGrained) {
                    sb.append("Скоупы: ").append(scopes == null || scopes.isEmpty() ? "(нет!)" : scopes).append("\n");
                }
                int publicRepos = u.optInt("public_repos", -1);
                if (publicRepos >= 0) sb.append("Публичных репозиториев на аккаунте: ").append(publicRepos).append("\n");

                // сколько репозиториев реально отдаёт этот токен
                int count = 0;
                String url = Api.API + "/user/repos?per_page=100"
                        + "&affiliation=owner,collaborator,organization_member&sort=pushed";
                for (int page = 0; page < 5 && url != null; page++) {
                    Api.Response r = Api.get(url, token);
                    JSONArray arr = new JSONArray(r.body);
                    count += arr.length();
                    if (arr.length() < 100) url = null;
                    else url = Api.nextPage(r);
                }
                sb.append("Доступно токену репозиториев: ").append(count)
                        .append(count == 100 || count == 200 || count == 300 || count == 400 ? "+" : "").append("\n");

                int orgs = 0;
                try {
                    JSONArray o = new JSONArray(Api.get(Api.API + "/user/orgs?per_page=100", token).body);
                    orgs = o.length();
                } catch (Exception ignored) {
                }
                sb.append("Организаций: ").append(orgs).append("\n\n");

                if (fineGrained) {
                    sb.append("Fine-grained токен видит только те репозитории, что разрешены в его настройках. "
                            + "Особенно это касается ПРИВАТНЫХ репозиториев: в дашборде GitHub они есть, "
                            + "а через API не видны, пока токену не выдан к ним доступ.\n\n"
                            + "Исправление (1 минута): GitHub → Settings → Developer settings → "
                            + "Fine-grained tokens → ваш токен →\n"
                            + "  1) Repository access → «All repositories» (или выбрать нужные вручную);\n"
                            + "  2) Permissions → Repository permissions → Metadata: Read-only "
                            + "(обязательно), Actions: Read-only (для артефактов), Contents: Read-only "
                            + "(для ZIP исходников и релизов);\n"
                            + "  3) Save. Значение токена при этом не меняется.\n\n"
                            + "Альтернатива: создать classic токен с галочкой repo — он сразу видит все ваши "
                            + "приватные репозитории.\n\n"
                            + "Токен принадлежит аккаунту «").append(login).append("».");
                } else if (scopes == null || !scopes.contains("repo")) {
                    sb.append("В classic-токене нет scope «repo», поэтому приватные репозитории не видны. "
                            + "Пересоздайте токен с галочками repo (и workflow).");
                } else if (count <= 3) {
                    sb.append("Токен видит мало репозиториев — проверьте, что он создан на нужном аккаунте "
                            + "(owner/organization). Проекты другого аккаунта можно найти через поиск 🔍.");
                } else {
                    sb.append("Всё в порядке: токен видит ваши репозитории.");
                }
            } catch (Api.ApiException e) {
                sb.append("Ошибка ").append(e.code).append(": ").append(e.getMessage());
            } catch (Exception e) {
                sb.append("Не удалось проверить: ").append(e.getMessage());
            }
            final String report = sb.toString();
            a.runOnUiThread(() -> cb.done(report));
        }).start();
    }

    /** Подсказка «нужен токен» с кнопкой ввода. */
    static void askForToken(final Activity a, String reason, final Runnable onSaved) {
        new AlertDialog.Builder(a)
                .setTitle("Нужен токен GitHub")
                .setMessage(reason)
                .setPositiveButton("Ввести токен", (d, w) -> show(a, onSaved))
                .setNegativeButton("Позже", null)
                .show();
    }
}
