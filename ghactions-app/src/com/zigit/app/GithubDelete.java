package com.zigit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Удаление на GitHub: репозиториев и артефактов сборок (Actions).
 *
 * Оба действия необратимы, поэтому:
 *  • репозиторий удаляется только после ввода его полного имени (как на сайте GitHub);
 *  • артефакты — после обычного подтверждения со списком и размером;
 *  • при нехватке прав показывается разбор, какой scope/разрешение добавить токену.
 */
final class GithubDelete {

    private GithubDelete() {
    }

    /**
     * Итог операции: changed == true, если что-то действительно удалено —
     * экран в этом случае обновляет свой список.
     */
    interface Done {
        void onChanged(boolean changed);
    }

    // ------------------------------------------------------------ репозиторий

    /** Подтверждение (с вводом имени) и удаление репозитория. */
    static void confirmRepo(final Activity a, final String fullName, final Done onDeleted) {
        if (fullName == null || fullName.trim().isEmpty()) return;
        if (Store.token(a).isEmpty()) {
            TokenDialog.askForToken(a,
                    "Чтобы удалить репозиторий, нужен токен GitHub с правами администратора:\n"
                            + "• classic: scope delete_repo;\n"
                            + "• fine-grained: Administration → Read and write.\n\n"
                            + "Репозиторий " + fullName + " останется на GitHub, пока токен не добавлен.",
                    null);
            return;
        }

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Util.dp(a, 20);
        box.setPadding(pad, pad / 4, pad, 0);

        TextView warn = new TextView(a);
        warn.setTextSize(13);
        warn.setText("Репозиторий " + fullName + " будет удалён вместе со всеми ветками, "
                + "запусками Actions, артефактами, релизами и настройками.\n\n"
                + "GitHub не восстанавливает удалённые репозитории.\n\n"
                + "Для подтверждения введите полное имя репозитория:");
        box.addView(warn);

        final EditText input = new EditText(a);
        input.setHint(fullName);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(input);

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Удалить репозиторий?")
                .setView(box)
                .setPositiveButton("Удалить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String typed = input.getText().toString().trim();
                    if (!typed.equalsIgnoreCase(fullName)) {
                        toast(a, "Введите имя полностью: " + fullName);
                        return;
                    }
                    dialog.dismiss();
                    deleteRepo(a, fullName, onDeleted);
                }));
        dialog.show();
    }

    private static void deleteRepo(final Activity a, final String fullName, final Done onDeleted) {
        final AlertDialog waitDialog = showProgress(a, "Удаляю репозиторий " + fullName + "…");
        new Thread(() -> {
            try {
                Api.delete(Api.API + "/repos/" + fullName, Store.token(a));
                a.runOnUiThread(() -> {
                    dismiss(waitDialog);
                    toast(a, "Репозиторий удалён: " + fullName);
                    if (onDeleted != null) onDeleted.onChanged(true);
                });
            } catch (Exception e) {
                final Exception err = e;
                a.runOnUiThread(() -> {
                    dismiss(waitDialog);
                    failure(a, "Не удалось удалить репозиторий", err, true);
                    if (onDeleted != null) onDeleted.onChanged(false);
                });
            }
        }).start();
    }

    // -------------------------------------------------------------- артефакты

    /** Подтверждение и удаление одного или нескольких артефактов сборки. */
    static void confirmArtifacts(final Activity a, final String repo,
                                 final List<Models.ArtifactInfo> artifacts, final Done onDeleted) {
        if (artifacts == null || artifacts.isEmpty()) return;

        // истёкшие артефакты GitHub уже удалил сам — удалять нечего
        final List<Models.ArtifactInfo> live = new ArrayList<>();
        for (Models.ArtifactInfo art : artifacts) {
            if (!art.expired) live.add(art);
        }
        if (live.isEmpty()) {
            toast(a, "Эти артефакты уже истекли — GitHub удалил их сам");
            return;
        }
        if (Store.token(a).isEmpty()) {
            TokenDialog.askForToken(a,
                    "Чтобы удалить артефакты сборок, нужен токен GitHub с правом записи в Actions:\n"
                            + "• classic: scope repo (нужен и для публичных репозиториев);\n"
                            + "• fine-grained: Actions → Read and write + Metadata: Read-only.",
                    null);
            return;
        }

        long total = 0;
        for (Models.ArtifactInfo art : live) total += art.size;

        String what = live.size() == 1
                ? "Артефакт «" + live.get(0).name + "» (" + Util.humanSize(total) + ")"
                : "Артефактов: " + live.size() + " (" + Util.humanSize(total) + ")";

        new AlertDialog.Builder(a)
                .setTitle(live.size() == 1 ? "Удалить артефакт?" : "Удалить артефакты?")
                .setMessage(what + "\n\nЗапуск и его логи останутся, но готовые сборки будут "
                        + "удалены с GitHub без возможности восстановления.")
                .setPositiveButton("Удалить", (d, w) -> deleteArtifacts(a, repo, live, onDeleted))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static void deleteArtifacts(final Activity a, final String repo,
                                        final List<Models.ArtifactInfo> artifacts, final Done onDeleted) {
        final AlertDialog waitDialog = showProgress(a, artifacts.size() == 1
                ? "Удаляю артефакт…"
                : "Удаляю артефакты: 1 из " + artifacts.size() + "…");
        new Thread(() -> {
            int deleted = 0;
            Exception firstError = null;
            for (int i = 0; i < artifacts.size(); i++) {
                final int no = i + 1;
                if (artifacts.size() > 1 && waitDialog != null) {
                    a.runOnUiThread(() -> {
                        try {
                            waitDialog.setMessage("Удаляю артефакты: " + no + " из "
                                    + artifacts.size() + "…");
                        } catch (Exception ignored) {
                        }
                    });
                }
                try {
                    Api.delete(artifactUrl(repo, artifacts.get(i).id), Store.token(a));
                    deleted++;
                } catch (Exception e) {
                    if (firstError == null) firstError = e;
                }
            }
            final int ok = deleted;
            final Exception err = firstError;
            a.runOnUiThread(() -> {
                dismiss(waitDialog);
                if (onDeleted != null) onDeleted.onChanged(ok > 0);
                if (err != null) {
                    failure(a, ok == 0
                                    ? "Не удалось удалить артефакт"
                                    : "Удалено " + ok + " из " + artifacts.size(),
                            err, false);
                } else {
                    toast(a, artifacts.size() == 1
                            ? "Артефакт удалён"
                            : "Удалено артефактов: " + ok);
                }
            });
        }).start();
    }

    /** Артефакт однозначно определяется id, запуск указывать не нужно. */
    private static String artifactUrl(String repo, long artifactId) {
        return Api.API + "/repos/" + repo + "/actions/artifacts/" + artifactId;
    }

    // ------------------------------------------------------------------- утилиты

    private static AlertDialog showProgress(Activity a, String message) {
        try {
            AlertDialog d = new AlertDialog.Builder(a)
                    .setMessage(message)
                    .setCancelable(false)
                    .create();
            d.show();
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static void dismiss(AlertDialog d) {
        if (d == null) return;
        try {
            d.dismiss();
        } catch (Exception ignored) {
        }
    }

    /** Объясняет, чего не хватило токену, и предлагает это исправить. */
    private static void failure(Activity a, String title, Exception e, final boolean isRepo) {
        int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();

        StringBuilder sb = new StringBuilder();
        sb.append(msg);
        if (code == 401 || code == 403 || code == 404) {
            sb.append("\n\n");
            sb.append(isRepo
                    ? "Для удаления репозитория токену нужны права администратора."
                    : "Для удаления артефактов токену нужно право записи в Actions.");
            sb.append("\n\nclassic-токен: ")
                    .append(isRepo ? "scope delete_repo" : "scope repo")
                    .append("\nfine-grained токен: ")
                    .append(isRepo ? "Administration → Read and write"
                            : "Actions → Read and write")
                    .append(" (+ Metadata: Read-only)");
        }

        try {
            new AlertDialog.Builder(a)
                    .setTitle(title)
                    .setMessage(sb.toString())
                    .setPositiveButton("Токен GitHub", (d, w) -> TokenDialog.show(a, null))
                    .setNeutralButton("Настройки на GitHub", (d, w) -> openTokenSettings(a))
                    .setNegativeButton("Закрыть", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    private static void openTokenSettings(Activity a) {
        String token = Store.token(a);
        String url = token.startsWith("github_pat_")
                ? "https://github.com/settings/personal-access-tokens"
                : "https://github.com/settings/tokens";
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast(a, "Нет браузера. Откройте " + url);
        }
    }

    private static void toast(Activity a, String s) {
        try {
            Toast.makeText(a, s, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }
}
