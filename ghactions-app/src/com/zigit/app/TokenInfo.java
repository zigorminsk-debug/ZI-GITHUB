package com.zigit.app;

import java.util.Locale;

/**
 * Тип токена и права на запись: что именно нужно GitHub-у и как это включить.
 *
 * Зачем отдельный класс: GitHub не отдаёт список разрешений fine-grained токена через API,
 * поэтому узнать, хватает ли прав, можно только попробовав запрос на запись. Зато по ответу
 * (401/403/404/422) и по типу токена можно объяснить причину точно — этим и занимается
 * приложение вместо сухого «Resource not accessible by personal access token».
 */
final class TokenInfo {

    /** Fine-grained токены: список, где правится Repository access и Permissions. */
    static final String URL_FINE_TOKENS = "https://github.com/settings/personal-access-tokens";
    /** Новый fine-grained токен. */
    static final String URL_NEW_FINE = "https://github.com/settings/personal-access-tokens/new";
    /** Classic токены. */
    static final String URL_CLASSIC_TOKENS = "https://github.com/settings/tokens";
    /** Новый classic токен с готовыми галочками (repo + workflow + delete_repo). */
    static final String URL_NEW_CLASSIC =
            "https://github.com/settings/tokens/new?scopes=repo,workflow,delete_repo&description=ZI%20Git";

    private TokenInfo() {
    }

    /** github_pat_… — fine-grained; ghp_/gho_/ghu_/ghs_/ghr_ — classic. */
    static boolean isFineGrained(String token) {
        return token != null && token.startsWith("github_pat_");
    }

    static String typeLabel(String token) {
        return isFineGrained(token) ? "fine-grained" : "classic";
    }

    /** Есть ли скоуп в заголовке X-OAuth-Scopes (значения через запятую). */
    static boolean hasScope(String scopes, String scope) {
        if (scopes == null || scopes.isEmpty() || scope == null) return false;
        for (String s : scopes.split(",")) {
            if (s.trim().equalsIgnoreCase(scope)) return true;
        }
        return false;
    }

    static String scopesLabel(String scopes) {
        return scopes == null || scopes.trim().isEmpty() ? "(скоупов нет)" : scopes.trim();
    }

    /**
     * Пошаговая инструкция «как выдать права на запись».
     *
     * @param owner владелец, в которого пытались создать репозиторий (логин или организация)
     */
    static String writePermsText(boolean fineGrained, String login, String scopes, String owner) {
        StringBuilder sb = new StringBuilder();
        if (fineGrained) {
            sb.append("Fine-grained токену нужны права на запись:\n")
                    .append("  1) GitHub → Settings → Developer settings → Fine-grained tokens → ваш токен;\n")
                    .append("  2) Repository access → «All repositories» ")
                    .append("(если выбрано «Only select repositories», новый репозиторий сам туда не попадёт — ")
                    .append("добавьте его вручную);\n")
                    .append("  3) Permissions → Repository permissions:\n")
                    .append("     • Administration: Read and write — без него GitHub не даёт создать репозиторий;\n")
                    .append("     • Contents: Read and write — первый коммит (README, .gitignore, лицензия) ")
                    .append("и загрузка файлов;\n")
                    .append("     • Metadata: Read-only — подключается автоматически;\n")
                    .append("  4) Save. Значение токена не меняется — вводить его заново не нужно.\n");
            if (login != null && !login.isEmpty()) {
                sb.append("\nТокен принадлежит аккаунту «").append(login).append("».");
            }
            if (owner != null && !owner.isEmpty() && login != null && !login.isEmpty()
                    && !owner.equalsIgnoreCase(login)) {
                sb.append("\nРепозиторий создаётся в организации «").append(owner).append("»: у fine-grained ")
                        .append("токена Resource owner должен быть эта организация, и она должна разрешать ")
                        .append("fine-grained токены.");
            }
        } else if (!hasScope(scopes, "repo")) {
            sb.append("У classic-токена нет scope «repo» (сейчас: ").append(scopesLabel(scopes)).append(").\n")
                    .append("«repo» нужен и для приватных, и для публичных репозиториев ")
                    .append("(«public_repo» даёт только публичные).\n\n")
                    .append("GitHub → Settings → Developer settings → Tokens (classic) → ваш токен → ")
                    .append("галочка «repo» → Update token. Значение токена при этом не меняется.");
        } else {
            sb.append("Scope «repo» у токена есть — значит, дело не в нём. Частые причины:\n")
                    .append("  • организация запрещает создавать репозитории (нужна роль owner ")
                    .append("или разрешение на создание репозиториев);\n")
                    .append("  • у организации включён SAML SSO, и токен для неё не авторизован: ")
                    .append("Settings → Applications → Authorized personal access tokens → SSO authorize;\n")
                    .append("  • исчерпан часовой лимит запросов GitHub (5000/час) — повторите позже;\n")
                    .append("  • токен отозван или истёк — выпустите новый.");
        }
        return sb.toString();
    }

    /**
     * Текст диалога для неудачного запроса на запись: что ответил GitHub, почему
     * и что именно нажать.
     */
    static String createFailureText(int code, String apiMessage, String rawBody, boolean fineGrained,
                                    String login, String scopes, String owner, String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("GitHub не дал прав на создание репозитория");
        if (code > 0) sb.append(" (HTTP ").append(code).append(")");
        sb.append(":\n");
        String detail = apiMessage == null || apiMessage.isEmpty() ? "неизвестная ошибка" : apiMessage;
        String extra = Api.errors(rawBody);
        if (!extra.isEmpty() && !detail.contains(extra)) detail = detail + " — " + extra;
        sb.append(detail).append("\n\n");

        String low = detail.toLowerCase(Locale.US);
        if (code == 404) {
            sb.append("Организация «").append(owner == null ? "" : owner)
                    .append("» не найдена или токену не выдан доступ к ней.\n")
                    .append("Проверьте имя владельца: для fine-grained токена Resource owner должен быть ")
                    .append("эта организация, а Repository access — включать её репозитории.\n\n");
        } else if (code == 422) {
            if (low.contains("already exists")) {
                sb.append("Репозиторий «").append(owner).append("/").append(name)
                        .append("» уже существует — выберите другое имя ")
                        .append("или откройте существующий в «Мои репо».\n\n");
            } else {
                sb.append("GitHub отклонил данные репозитория. Проверьте имя: разрешены латинские буквы, ")
                        .append("цифры, «.», «_», «-»; имя не может начинаться с точки и заканчиваться на .git.\n\n");
            }
        } else if (code == 401) {
            sb.append("Токен недействителен или отозван — введите новый.\n\n");
        } else if (code == 403 && low.contains("rate limit")) {
            sb.append("Исчерпан лимит запросов GitHub (5000 в час с токеном) — попробуйте позже.\n\n");
        } else if (code == 403) {
            sb.append("GitHub не уточняет, какого именно права не хватает: для создания репозитория нужно ")
                    .append("Administration: Read and write, для первого коммита — Contents: Read and write ")
                    .append("(у classic-токена — scope «repo»).\n\n");
        }

        if (code == 401 || code == 403) {
            sb.append("Тип токена: ").append(fineGrained ? "fine-grained" : "classic")
                    .append("  ·  аккаунт: ").append(login == null || login.isEmpty() ? "?" : login);
            if (!fineGrained) sb.append("  ·  скоупы: ").append(scopesLabel(scopes));
            sb.append("\n\n");
        }

        sb.append(writePermsText(fineGrained, login, scopes, owner));
        return sb.toString();
    }
}
