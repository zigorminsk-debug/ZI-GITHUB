package com.zigit.app;

/**
 * Что за токен введён и какие именно права нужны GitHub-у для записи.
 *
 * GitHub не отдаёт список прав fine-grained токена через API: единственный способ
 * узнать, хватает ли их, — попробовать запрос на запись и разобрать ответ.
 * Поэтому здесь собраны тексты-подсказки для двух случаев:
 *   • classic токен — права видны в заголовке X-OAuth-Scopes (нужен scope «repo»);
 *   • fine-grained токен — нужны Administration: Read and write (создание репозитория)
 *     и Contents: Read and write (первый коммит: README, .gitignore, лицензия).
 */
final class TokenInfo {

    /** Список fine-grained токенов (там же правится доступ к репозиториям и права). */
    static final String URL_FINE_TOKENS = "https://github.com/settings/personal-access-tokens";
    /** Новый fine-grained токен. */
    static final String URL_NEW_FINE = "https://github.com/settings/personal-access-tokens/new";
    /** Classic токены. */
    static final String URL_CLASSIC_TOKENS = "https://github.com/settings/tokens";
    /** Новый classic токен с уже проставленными галочками repo + workflow. */
    static final String URL_NEW_CLASSIC =
            "https://github.com/settings/tokens/new?scopes=repo,workflow&description=ZI%20Git";

    private TokenInfo() {
    }

    /** github_pat_… — fine-grained; ghp_/gho_/ghu_/ghs_/ghr_ — classic (и токены GitHub App). */
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

    /** Подпись скоупов для показа пользователю. */
    static String scopesLabel(String scopes) {
        return scopes == null || scopes.trim().isEmpty() ? "(скоупов нет)" : scopes.trim();
    }

    /**
     * Пошаговая инструкция: как выдать токену права на запись.
     *
     * @param owner владелец создаваемого репозитория (логин или организация)
     */
    static String writePermsText(boolean fineGrained, String login, String scopes, String owner) {
        StringBuilder sb = new StringBuilder();
        if (fineGrained) {
            sb.append("Fine-grained токену нужны права на запись:\n")
                    .append("  1) GitHub → Settings → Developer settings → Fine-grained tokens → ваш токен;\n")
                    .append("  2) Repository access → «All repositories» (если выбрано «Only select repositories», ")
                    .append("добавьте туда репозиторий вручную — новый сам не появится);\n")
                    .append("  3) Permissions → Repository permissions:\n")
                    .append("     • Administration: Read and write — без него GitHub не даёт создать репозиторий;\n")
                    .append("     • Contents: Read and write — первый коммит: README, .gitignore, лицензия;\n")
                    .append("     • Metadata: Read-only — подключается автоматически;\n")
                    .append("  4) Save. Значение токена не меняется — вводить его заново не нужно.\n");
            if (login != null && !login.isEmpty()) {
                sb.append("\nТокен принадлежит аккаунту «").append(login).append("».");
            }
            if (owner != null && !owner.isEmpty() && login != null && !login.isEmpty()
                    && !owner.equalsIgnoreCase(login)) {
                sb.append("\nРепозиторий создаётся в организации «").append(owner).append("»: у fine-grained токена ")
                        .append("Resource owner должен быть эта организация (и она должна разрешать ")
                        .append("fine-grained токены).");
            }
        } else {
            if (!hasScope(scopes, "repo")) {
                sb.append("У classic-токена нет scope «repo» (сейчас: ").append(scopesLabel(scopes)).append(").\n")
                        .append("Нужен «repo» — он даёт запись в публичные и приватные репозитории ")
                        .append("(«public_repo» — только в публичные).\n\n")
                        .append("GitHub → Settings → Developer settings → Tokens (classic) → ваш токен → ")
                        .append("галочка «repo» → Update token. Значение токена при этом не меняется.");
            } else {
                sb.append("Scope «repo» у токена есть — значит, дело не в нём.\n")
                        .append("Частые причины:\n")
                        .append("  • организация запрещает создавать репозитории (нужна роль owner или ")
                        .append("разрешение «Repository creation»);\n")
                        .append("  • у организации включён SAML SSO, и токен для неё не авторизован: ")
                        .append("Settings → Applications → Authorized personal access tokens → SSO authorize;\n")
                        .append("  • исчерпан часовой лимит запросов GitHub (5000/час) — попробуйте позже;\n")
                        .append("  • токен отозван или истёк — выпустите новый.");
            }
        }
        return sb.toString();
    }

    /**
     * Текст диалога при неудачном создании репозитория: что сказал GitHub,
     * чем это вызвано и что нажать.
     */
    static String createFailureText(int code, String apiMessage, String body, boolean fineGrained,
                                    String login, String scopes, String owner, String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("GitHub не дал прав на создание репозитория");
        if (code > 0) sb.append(" (HTTP ").append(code).append(")");
        sb.append(":\n");
        String detail = apiMessage == null || apiMessage.isEmpty() ? "неизвестная ошибка" : apiMessage;
        String extra = Api.errors(body);
        if (!extra.isEmpty() && !detail.contains(extra)) detail = detail + " — " + extra;
        sb.append(detail).append("\n\n");

        if (code == 404) {
            sb.append("Организация «").append(owner).append("» не найдена или токену не выдан доступ к ней.\n")
                    .append("Проверьте имя владельца. Для fine-grained токена Resource owner должен быть ")
                    .append("эта организация, а Repository access — включать её репозитории.\n\n");
        } else if (code == 422) {
            if (detail.toLowerCase(java.util.Locale.US).contains("already exists")) {
                sb.append("Репозиторий «").append(owner).append("/").append(name).append("» уже существует — ")
                        .append("выберите другое имя или откройте существующий через «Мои репо».\n\n");
            } else {
                sb.append("GitHub отклонил данные репозитория. Проверьте имя: разрешены латинские буквы, ")
                        .append("цифры, «.», «_», «-»; имя не может начинаться с точки.\n\n");
            }
        } else if (code == 401) {
            sb.append("Токен недействителен или отозван — введите новый.\n\n");
        } else if (code == 403 && detail.toLowerCase(java.util.Locale.US).contains("rate limit")) {
            sb.append("Исчерпан лимит запросов GitHub (5000 в час с токеном) — попробуйте позже.\n\n");
        }

        if (code == 403 || code == 401) {
            sb.append("Нужны права на запись: у classic-токена — scope «repo»; ")
                    .append("у fine-grained — Administration и Contents: Read and write.\n\n");
            sb.append("Тип токена: ").append(fineGrained ? "fine-grained" : "classic")
                    .append(" · аккаунт: ").append(login == null || login.isEmpty() ? "?" : login);
            if (!fineGrained) sb.append(" · скоупы: ").append(scopesLabel(scopes));
            sb.append("\n\n");
        }

        sb.append(writePermsText(fineGrained, login, scopes, owner));
        return sb.toString();
    }
}
