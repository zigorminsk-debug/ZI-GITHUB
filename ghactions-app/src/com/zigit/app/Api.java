package com.zigit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Минимальный клиент GitHub REST API на HttpURLConnection (без внешних зависимостей). */
final class Api {

    static final String API = "https://api.github.com";

    interface Progress {
        void onProgress(long done, long total);
    }

    static class ApiException extends Exception {
        final int code;

        ApiException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    private Api() {
    }

    private static HttpURLConnection open(String url, String token) throws IOException {
        return open(url, token, "application/vnd.github+json");
    }

    private static HttpURLConnection open(String url, String token, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(40000);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "ZI-Git/2.3");
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** Ответ с заголовками — нужен для диагностики токена (scopes) и пагинации (Link). */
    static class Response {
        final int code;
        final String body;
        final Map<String, List<String>> headers;

        Response(int code, String body, Map<String, List<String>> headers) {
            this.code = code;
            this.body = body;
            this.headers = headers;
        }

        String header(String name) {
            List<String> v = headers.get(name);
            return (v == null || v.isEmpty()) ? null : v.get(0);
        }
    }

    static Response get(String url, String token) throws Exception {
        HttpURLConnection c = open(url, token);
        int code = c.getResponseCode();
        String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        Map<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null) h.put(e.getKey(), e.getValue());
        }
        c.disconnect();
        if (code >= 400) throw new ApiException(code, message(body, code));
        return new Response(code, body, h);
    }

    /** Ссылка на следующую страницу из заголовка Link (или null). */
    static String nextPage(Response r) {
        String link = r.header("Link");
        if (link == null) return null;
        for (String part : link.split(",")) {
            if (part.contains("rel=\"next\"")) {
                int a = part.indexOf('<');
                int b = part.indexOf('>');
                if (a >= 0 && b > a) return part.substring(a + 1, b);
            }
        }
        return null;
    }

    private static String request(String url, String token) throws Exception {
        HttpURLConnection c = open(url, token);
        int code = c.getResponseCode();
        String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code >= 400) throw new ApiException(code, message(body, code));
        return body;
    }

    static JSONObject json(String url, String token) throws Exception {
        return new JSONObject(request(url, token));
    }

    static JSONArray array(String url, String token) throws Exception {
        return new JSONArray(request(url, token));
    }

    static String message(String body, int code) {
        String gh = null;
        try {
            JSONObject o = new JSONObject(body == null ? "" : body);
            if (o.has("message")) gh = o.getString("message");
        } catch (Exception ignored) {
        }
        if (code == 401) {
            return "GitHub отклонил запрос (401): " + (gh == null ? "нужна авторизация" : gh)
                    + ". Проверьте токен: меню ⋮ → «Токен GitHub».";
        }
        if (gh != null) return gh;
        switch (code) {
            case 401:
                return "Неверный или просроченный токен (401)";
            case 403:
                return "Доступ запрещён или исчерпан лимит запросов (403)";
            case 404:
                return "Не найдено (404): проверьте owner/repo либо добавьте токен для приватных репозиториев";
            case 410:
                return "Артефакт истёк и уже удалён GitHub (410)";
            default:
                return "HTTP " + code;
        }
    }

    /**
     * Скачивание файла с поддержкой редиректа.
     * GitHub отдаёт 302 на подписанный URL (objects.githubusercontent.com) — туда
     * токен отправлять нельзя, поэтому переход выполняется без Authorization.
     */
    static void download(String url, String token, File out, Progress p) throws Exception {
        download(url, token, out, "application/vnd.github+json", p);
    }

    /**
     * @param accept для ссылок на файлы релизов через API нужен application/octet-stream,
     *               иначе GitHub вернёт JSON-описание вместо самого файла
     */
    static void download(String url, String token, File out, String accept, Progress p) throws Exception {
        HttpURLConnection c = null;
        String current = url;
        String authToken = token;
        int code = 0;
        for (int hop = 0; hop < 8; hop++) {
            c = open(current, authToken, accept);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(30000);
            c.setReadTimeout(300000);
            c.setRequestProperty("Accept-Encoding", "identity");
            code = c.getResponseCode();
            if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) break;
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc == null || loc.isEmpty()) throw new IOException("Редирект без заголовка Location");
            if (loc.startsWith("/")) {
                URL base = new URL(current);
                int port = base.getPort();
                loc = base.getProtocol() + "://" + base.getHost()
                        + (port != -1 ? ":" + port : "") + loc;
            }
            try {
                String host = new URL(loc).getHost();
                // токен только на api.github.com; на objects/release-assets.githubusercontent.com — нельзя
                authToken = "api.github.com".equals(host) ? token : null;
            } catch (Exception e) {
                authToken = null;
            }
            current = loc;
        }
        if (c == null) throw new IOException("Не удалось открыть соединение");
        if (code >= 400) {
            String body = readAll(c.getErrorStream());
            c.disconnect();
            throw new ApiException(code, message(body, code));
        }
        long total = c.getContentLengthLong();
        InputStream in = c.getInputStream();
        FileOutputStream fos = new FileOutputStream(out);
        try {
            byte[] buf = new byte[65536];
            long done = 0;
            long last = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n == 0) continue;
                fos.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (p != null && now - last > 250) {
                    last = now;
                    p.onProgress(done, total);
                }
            }
            if (p != null) p.onProgress(done, total);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            fos.close();
            c.disconnect();
        }
    }
}
