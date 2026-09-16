package com.zigit.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Локальное хранилище настроек. */
final class Store {

    private Store() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("zigit", Context.MODE_PRIVATE);
    }

    static String token(Context c) {
        return sp(c).getString("token", "");
    }

    static void setToken(Context c, String t) {
        sp(c).edit().putString("token", t == null ? "" : t.trim()).apply();
    }

    static String repo(Context c) {
        return sp(c).getString("repo", "");
    }

    static void setRepo(Context c, String r) {
        sp(c).edit().putString("repo", r == null ? "" : r).apply();
    }
}
