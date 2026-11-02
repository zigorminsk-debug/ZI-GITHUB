package com.zigit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Модели данных GitHub Actions. */
final class Models {

    private Models() {
    }

    static class RunItem {
        long id;
        String name = "";
        String displayTitle = "";
        String headBranch = "";
        String event = "";
        String status = "";
        String conclusion = "";
        String createdAt = "";
        String updatedAt = "";
        int runNumber;
        String headSha = "";

        boolean artifactsLoaded;
        boolean artifactsError;
        List<ArtifactInfo> artifacts = new ArrayList<>();

        static RunItem from(JSONObject o) {
            RunItem r = new RunItem();
            r.id = o.optLong("id");
            r.name = o.optString("name", "");
            r.displayTitle = o.optString("display_title", r.name);
            r.headBranch = o.optString("head_branch", "");
            r.event = o.optString("event", "");
            r.status = o.optString("status", "");
            r.conclusion = o.optString("conclusion", "");
            r.createdAt = o.optString("created_at", "");
            r.updatedAt = o.optString("updated_at", "");
            r.runNumber = o.optInt("run_number", 0);
            JSONObject commit = o.optJSONObject("head_commit");
            String sha = "";
            if (commit != null) sha = commit.optString("id", "");
            if (sha.isEmpty()) sha = o.optString("head_sha", "");
            if (sha.length() >= 7) r.headSha = sha.substring(0, 7);
            return r;
        }

        String title() {
            return displayTitle == null || displayTitle.isEmpty() ? name : displayTitle;
        }

        int artifactCount() {
            return artifacts == null ? 0 : artifacts.size();
        }
    }

    static class ArtifactInfo {
        long id;
        String name = "";
        long size;
        String createdAt = "";
        String updatedAt = "";
        String expiresAt = "";
        boolean expired;
        int downloadCount;
        String url = "";

        /** Временное состояние загрузки (текст в строке списка). */
        String uiState;

        static List<ArtifactInfo> list(JSONArray arr) {
            List<ArtifactInfo> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                ArtifactInfo a = new ArtifactInfo();
                a.id = o.optLong("id");
                a.name = o.optString("name", "");
                a.size = o.optLong("size_in_bytes", 0);
                a.createdAt = o.optString("created_at", "");
                a.updatedAt = o.optString("updated_at", "");
                a.expiresAt = o.optString("expires_at", "");
                a.expired = o.optBoolean("expired", false);
                a.downloadCount = o.optInt("download_count", 0);
                // Скачивать нужно archive_download_url (.../artifacts/{id}/zip),
                // а не metadata url — иначе GitHub отдаёт JSON вместо архива.
                a.url = o.optString("archive_download_url", "");
                if (a.url.isEmpty()) {
                    String meta = o.optString("url", "");
                    if (!meta.isEmpty()) {
                        a.url = meta.endsWith("/zip") ? meta : meta + "/zip";
                    }
                }
                out.add(a);
            }
            return out;
        }
    }

    /** Статус запуска: цвет + подпись. */
    static String statusLabel(String status, String conclusion) {
        if ("completed".equals(status)) {
            if (conclusion == null || conclusion.isEmpty() || "null".equals(conclusion)) return "completed";
            return conclusion;
        }
        if (status == null || status.isEmpty()) return "unknown";
        return status;
    }

    static int statusColorRes(String status, String conclusion) {
        if ("completed".equals(status)) {
            if ("success".equals(conclusion)) return R.color.green;
            if ("failure".equals(conclusion) || "timed_out".equals(conclusion)
                    || "startup_failure".equals(conclusion)) return R.color.red;
            return R.color.gray_badge;
        }
        return R.color.gray_badge;
    }
}
