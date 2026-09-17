package com.zigit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Выбор репозитория: свои репозитории по токену или поиск по GitHub.
 *
 * Важно: GitHub отдаёт по токену только те репозитории, на которые у токена есть доступ
 * (fine-grained — выбранные при создании токена; classic без scope «repo» — только публичные).
 * Поэтому экран показывает, что именно видит токен, и подсказывает, что менять.
 */
public class RepoPickerActivity extends Activity {

    private static final int MAX_PAGES = 10;
    private static final int REQ_NEW_REPO = 21;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Repo> items = new ArrayList<>();

    private RepoAdapter adapter;
    private TextView empty, info;
    private TextView titleView;
    private boolean loading;

    private static class Repo {
        String fullName;
        boolean priv;
        String description;
        String pushedAt;
        String language;

        static Repo from(JSONObject o) {
            Repo r = new Repo();
            r.fullName = o.optString("full_name", "");
            r.priv = o.optBoolean("private", false);
            r.description = o.optString("description", "");
            r.pushedAt = o.optString("pushed_at", "");
            r.language = o.optString("language", "");
            return r;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_repos);

        titleView = findViewById(R.id.title);
        empty = findViewById(R.id.empty);
        info = findViewById(R.id.info);

        ListView list = findViewById(R.id.list);
        adapter = new RepoAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.searchBtn).setOnClickListener(v -> searchDialog());
        findViewById(R.id.addBtn).setOnClickListener(v -> createRepo());

        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent data = new Intent();
            data.putExtra("repo", items.get(position).fullName);
            setResult(RESULT_OK, data);
            finish();
        });

        loadMyRepos();
    }

    private void setInfo(String text) {
        if (text == null || text.isEmpty()) {
            info.setVisibility(View.GONE);
        } else {
            info.setText(text);
            info.setVisibility(View.VISIBLE);
        }
    }

    private void loadMyRepos() {
        if (Store.token(this).isEmpty()) {
            setInfo(null);
            empty.setText("Нужен токен GitHub, чтобы увидеть список ваших репозиториев.\n\n"
                    + "Приватные репозитории видны только токену с доступом к ним.\n\n"
                    + "Нажмите 🔍 вверху, чтобы найти публичный репозиторий по названию;\n"
                    + "«+» создаёт новый репозиторий (тоже по токену);\n"
                    + "токен добавляется в главном меню (⋮ → Токен GitHub).");
            empty.setVisibility(View.VISIBLE);
            titleView.setText(getString(R.string.my_repos));
            return;
        }
        loading = true;
        empty.setText("Загружаю ваши репозитории…");
        empty.setVisibility(View.VISIBLE);
        setInfo(null);

        pool.execute(() -> {
            try {
                final String token = Store.token(this);
                String url = Api.API + "/user/repos?per_page=100"
                        + "&affiliation=owner,collaborator,organization_member&sort=pushed";
                int page = 0;
                while (url != null && page < MAX_PAGES) {
                    final Api.Response r = Api.get(url, token);
                    final JSONArray arr = new JSONArray(r.body);
                    final List<Repo> got = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) got.add(Repo.from(o));
                    }
                    final int pageNo = page + 1;
                    ui.post(() -> {
                        if (isFinishing()) return;
                        items.addAll(got);
                        adapter.notifyDataSetChanged();
                        empty.setVisibility(View.GONE);
                        titleView.setText(getString(R.string.my_repos) + " (" + items.size()
                                + (got.size() == 100 ? "+" : "") + ")");
                        if (!got.isEmpty()) {
                            setInfo("Загружаю страницу " + (pageNo + 1) + "… всего: " + items.size());
                        }
                    });
                    if (arr.length() < 100) {
                        url = null;
                    } else {
                        url = Api.nextPage(r);
                    }
                    page++;
                }
                ui.post(() -> {
                    if (isFinishing()) return;
                    loading = false;
                    setInfo(null);
                    if (items.isEmpty()) {
                        empty.setText("Токен не дал доступа ни к одному репозиторию.\n\n"
                                + "Проверьте настройки токена: ⋮ → Токен GitHub → «Проверить, что видит токен».");
                        empty.setVisibility(View.VISIBLE);
                    }
                    loadAccountInfo();
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                final int code = e instanceof Api.ApiException ? ((Api.ApiException) e).code : 0;
                ui.post(() -> {
                    if (isFinishing()) return;
                    loading = false;
                    setInfo(null);
                    empty.setText("Ошибка: " + msg);
                    empty.setVisibility(View.VISIBLE);
                    if (code == 401 || code == 403) {
                        TokenDialog.askForToken(this,
                                "GitHub не принял токен:\n" + msg, () -> {
                                    items.clear();
                                    adapter.notifyDataSetChanged();
                                    loadMyRepos();
                                });
                    }
                });
            }
        });
    }

    /** Показывает, какому аккаунту принадлежит токен и сколько репозиториев он видит. */
    private void loadAccountInfo() {
        final String token = Store.token(this);
        if (token.isEmpty()) return;
        pool.execute(() -> {
            String text = null;
            try {
                Api.Response meResp = Api.get(Api.API + "/user", token);
                JSONObject me = new JSONObject(meResp.body);
                String login = me.optString("login", "?");
                boolean fineGrained = token.startsWith("github_pat_");
                String scopes = meResp.header("X-OAuth-Scopes");

                StringBuilder sb = new StringBuilder();
                sb.append("Аккаунт токена: ").append(login)
                        .append("  ·  ").append(fineGrained ? "fine-grained" : "classic")
                        .append("  ·  видно репозиториев: ").append(items.size())
                        .append(items.size() % 100 == 0 && items.size() >= 100 ? "+" : "");

                if (items.size() <= 3) {
                    if (fineGrained) {
                        sb.append("\nМало репозиториев? Приватные репозитории видны только тому токену, "
                                + "которому выдан к ним доступ: Fine-grained tokens → ваш токен → "
                                + "Repository access → «All repositories» + Metadata: Read-only. "
                                + "Либо создайте classic токен со scope repo. Токен принадлежит аккаунту «")
                                .append(login).append("». Нажмите сюда, чтобы настроить доступ.");
                    } else if (scopes == null || !scopes.contains("repo")) {
                        sb.append("\nВ classic-токене нет scope «repo», поэтому видны только публичные "
                                + "репозитории. Создайте токен с галочками repo и workflow.");
                    } else {
                        sb.append("\nНажмите сюда, чтобы проверить токен и найти причину.");
                    }
                    text = sb.toString();
                }
                final String t = text;
                ui.post(() -> {
                    if (isFinishing()) return;
                    setInfo(t);
                    if (t != null) {
                        info.setOnClickListener(v -> TokenDialog.show(this, () -> {
                            items.clear();
                            adapter.notifyDataSetChanged();
                            loadMyRepos();
                        }));
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    /** Создание нового репозитория: без токена с правами на запись GitHub не даст этого сделать. */
    private void createRepo() {
        if (Store.token(this).isEmpty()) {
            TokenDialog.askForToken(this,
                    "Чтобы создавать репозитории, нужен токен с правами на запись:\n"
                            + "classic — scope «repo»; fine-grained — Administration и Contents: "
                            + "Read and write.",
                    () -> {
                        items.clear();
                        adapter.notifyDataSetChanged();
                        loadMyRepos();
                    });
            return;
        }
        startActivityForResult(new Intent(this, NewRepoActivity.class), REQ_NEW_REPO);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_NEW_REPO) return;
        if (res == RESULT_OK && data != null) {
            // новый репозиторий сразу открываем на главном экране
            String repo = data.getStringExtra(NewRepoActivity.EXTRA_REPO);
            if (repo != null) {
                Intent out = new Intent();
                out.putExtra("repo", repo);
                setResult(RESULT_OK, out);
                finish();
                return;
            }
        }
        // вернулись без выбора — просто обновляем список, созданный репозиторий уже в нём
        items.clear();
        adapter.notifyDataSetChanged();
        loadMyRepos();
    }

    private void searchDialog() {
        final EditText input = new EditText(this);
        input.setHint("например: PC-Tools или owner/repo");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int pad = Util.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, 0);

        new AlertDialog.Builder(this)
                .setTitle("Поиск репозиториев на GitHub")
                .setMessage("Поиск работает по всему GitHub — так можно открыть проект любого аккаунта, "
                        + "даже если токен к нему доступа не даёт.")
                .setView(input)
                .setPositiveButton("Найти", (d, w) -> search(input.getText().toString().trim()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void search(final String rawQuery) {
        // "owner/repo" -> ищем по имени репозитория
        String query = rawQuery;
        if (query.contains("/")) {
            String tail = query.substring(query.lastIndexOf('/') + 1).trim();
            if (!tail.isEmpty()) query = tail;
        }
        if (query.isEmpty()) return;
        final String term = query;
        setInfo(null);
        titleView.setText("Поиск: " + term);
        empty.setText("Ищу…");
        empty.setVisibility(View.VISIBLE);
        items.clear();
        adapter.notifyDataSetChanged();
        pool.execute(() -> {
            try {
                String q = android.net.Uri.encode(term);
                JSONArray arr = Api.array(Api.API + "/search/repositories?q=" + q
                        + "&sort=stars&order=desc&per_page=50", Store.token(this));
                final List<Repo> got = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) got.add(Repo.from(o));
                }
                ui.post(() -> {
                    if (isFinishing()) return;
                    items.clear();
                    items.addAll(got);
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    titleView.setText(items.isEmpty() ? "Ничего не найдено" : "Найдено: " + items.size());
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                ui.post(() -> {
                    if (isFinishing()) return;
                    empty.setText("Ошибка: " + msg);
                    empty.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private class RepoAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(RepoPickerActivity.this)
                        .inflate(R.layout.row_repo, parent, false);
            }
            Repo r = items.get(position);
            ((TextView) v.findViewById(R.id.title)).setText(r.fullName);
            TextView badge = v.findViewById(R.id.badge);
            badge.setText(r.priv ? "private" : "public");
            StringBuilder sb = new StringBuilder();
            if (r.language != null && !r.language.isEmpty() && !"null".equals(r.language)) {
                sb.append(r.language);
            }
            if (r.description != null && !r.description.isEmpty() && !"null".equals(r.description)) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(r.description);
            }
            if (r.pushedAt != null && !r.pushedAt.isEmpty()) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append("обновлён ").append(Util.timeAgo(r.pushedAt));
            }
            ((TextView) v.findViewById(R.id.sub)).setText(sb.toString());
            return v;
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
