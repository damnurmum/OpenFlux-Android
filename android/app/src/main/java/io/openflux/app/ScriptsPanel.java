package io.openflux.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.openflux.bridge.mobile.Mobile;

// Settings → «JS-транспорты»: the experimental-features switch, importing a signed
// script transport (link or file) after the user checks its author, and the
// installed ones with their updates. Every trust decision is the core's
// (InspectTransport, Check/Apply/RollbackScriptUpdate); this screen only asks
// and shows. Ported from upstream's ScriptsScreen.
final class ScriptsPanel {
    static final int PICK_FILE = 4101;
    private static final int MAX_DOWNLOAD = 8 << 20;
    // One job at a time, shared by every panel the settings page builds.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private final MainActivity host;
    private final Map<String, JSONObject> reports = new HashMap<>();
    private final FlowStyle ui;
    private final ScriptStore store;
    private final LinearLayout page;
    private String pendingKey = "";
    private boolean busy;

    ScriptsPanel(MainActivity host) {
        this.host = host;
        ui = new FlowStyle(host);
        store = new ScriptStore(host);
        page = new LinearLayout(host);
        page.setOrientation(LinearLayout.VERTICAL);
        render();
    }

    // The settings tab's content; MainActivity puts it in its own scroll.
    View view() { return page; }

    private void render() {
        page.removeAllViews();
        boolean on = ScriptStore.experimental(host);

        LinearLayout toggle = card();
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(caption("Экспериментальные функции", 15, true), new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = toggle();
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, checked) -> setExperimental(checked));
        row.addView(sw);
        toggle.addView(row);
        toggle.addView(caption("Транспорты на JavaScript: работают только в режиме Session, рядом с обычными. "
                + "Пока выключено, движок не запускается и такие транспорты не подключаются.", 12, false));
        page.addView(toggle, spaced());
        if (!on) return;

        LinearLayout actions = new LinearLayout(host);
        actions.addView(button("Импортировать", true, this::showImport), new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        checkParams.leftMargin = dp(8);
        actions.addView(button("Проверить обновления", false, this::checkAll), checkParams);
        page.addView(actions, spaced());

        if (store.list().isEmpty()) {
            page.addView(caption("Транспортов пока нет: встроенные устанавливаются при включении, "
                    + "свои можно импортировать по ссылке или из файла.", 13, false), spaced());
        }
        for (ScriptStore.Script s : store.list()) page.addView(scriptCard(s), spaced());
        page.addView(caption("Скрипт-транспорт работает без песочницы: у него полный доступ к сети. Подпись автора "
                + "обязательна и проверяется при каждом запуске - импортируйте только из доверенного источника.",
                12, false), spaced());
    }

    private void setExperimental(boolean on) {
        host.getSharedPreferences(MainActivity.SETTINGS_PREFS_NAME, Activity.MODE_PRIVATE).edit()
                .putBoolean(ScriptStore.KEY_EXPERIMENTAL, on).apply();
        if (!on) {
            render();
            return;
        }
        run("Устанавливаю встроенные транспорты…", () -> {
            store.syncBundled();
            Log.i(ScriptStore.TAG, "engine available=" + Mobile.scriptEngineAvailable()
                    + " selftest=" + Mobile.scriptEngineSelfTest(host.getCacheDir().getAbsolutePath()));
            return null;
        });
    }

    private LinearLayout scriptCard(ScriptStore.Script s) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(host);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(host);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(caption(s.name + (s.official ? "  · OpenFlux" : "  · сторонний"), 15, true));
        names.addView(caption("версия " + (s.version.isEmpty() ? "-" : s.version) + " · " + sourceLabel(s.source), 12, false));
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch enabled = toggle();
        enabled.setChecked(s.enabled);
        enabled.setContentDescription("Включён");
        enabled.setOnCheckedChangeListener((b, checked) -> store.setEnabled(s.id, checked));
        top.addView(enabled);
        card.addView(top);

        if (s.hasSettings()) {
            card.addView(caption((s.settingsPage ? "Своя страница настроек" : "Параметров: " + s.params.length())
                    + ". Настраивается в профиле, который использует этот транспорт", 12, false));
        }
        TextView fingerprint = caption("Отпечаток ключа: " + s.shortFingerprint() + "  (нажмите, чтобы скопировать)", 12, false);
        fingerprint.setOnClickListener(v -> {
            host.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("fingerprint", s.fingerprint));
            Toast.makeText(host, "Отпечаток скопирован", Toast.LENGTH_SHORT).show();
        });
        card.addView(fingerprint);

        JSONObject report = reports.get(s.id);
        if (report != null) {
            String status = report.optString("status");
            if ("available".equals(status)) {
                card.addView(button("Обновить до " + report.optString("latest"), true, () -> confirmUpdate(s, report)), buttonRow());
            } else if (!"current".equals(status) && !report.optString("code").isEmpty()) {
                card.addView(caption(updateFailure(report.optString("code")), 12, false));
            }
        }
        LinearLayout row = new LinearLayout(host);
        if (store.hasPrevious(s)) {
            row.addView(button("Вернуть прошлую", false, () -> rollback(s)), new LinearLayout.LayoutParams(0, dp(42), 1f));
        }
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        if (row.getChildCount() > 0) deleteParams.leftMargin = dp(8);
        row.addView(button("Удалить", false, () -> ui.dialog()
                .setTitle("Удалить «" + s.name + "»?")
                .setMessage("Профили с этим транспортом не подключатся, пока его не установят снова.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    store.delete(s.id);
                    render();
                }).show()), deleteParams);
        card.addView(row, buttonRow());
        return card;
    }

    // ---- import ----

    private void showImport() {
        LinearLayout form = new LinearLayout(host);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText url = input("Ссылка на .flux или .js (GitHub raw)", InputType.TYPE_TEXT_VARIATION_URI);
        EditText key = input("Ключ автора (hex), пусто - официальный OpenFlux", InputType.TYPE_CLASS_TEXT);
        form.addView(url);
        form.addView(key);
        ui.dialog()
                .setTitle("Импорт транспорта")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Из файла", (d, w) -> {
                    pendingKey = key.getText().toString().trim();
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    host.startActivityForResult(pick, PICK_FILE);
                })
                .setPositiveButton("Скачать", (d, w) -> {
                    String link = url.getText().toString().trim();
                    String author = key.getText().toString().trim();
                    if (!link.startsWith("https://") && !link.startsWith("http://")) {
                        Toast.makeText(host, "Укажите ссылку http(s)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    run("Скачиваю…", () -> {
                        byte[] data = download(link);
                        byte[] sig = link.endsWith(".js") ? download(link + ".sig") : new byte[0];
                        return new Candidate(data, sig, author, link.contains("github") ? "github" : "link", link);
                    });
                })
                .show();
    }

    // MainActivity hands every result here first; true when it was this panel's.
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_FILE) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        String author = pendingKey;
        run("Читаю файл…", () -> {
            try (InputStream in = host.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("файл не открывается");
                return new Candidate(ScriptStore.readAll(in), new byte[0], author, "file", uri.toString());
            }
        });
        return true;
    }

    // A downloaded or picked transport waiting for the user's trust.
    private static final class Candidate {
        final byte[] data, sig;
        final String key, source, origin;

        Candidate(byte[] data, byte[] sig, String key, String source, String origin) {
            this.data = data;
            this.sig = sig;
            this.key = key.isEmpty() ? Mobile.officialScriptKey() : key;
            this.source = source;
            this.origin = origin;
        }
    }

    private void showTrust(Candidate c) {
        JSONObject r = store.inspect(c.data, c.sig, c.key);
        if (r == null || !r.optBoolean("ok")) {
            Toast.makeText(host, "Не удалось прочитать транспорт: "
                    + (r == null ? "ядро ответило не JSON" : r.optString("error")), Toast.LENGTH_LONG).show();
            return;
        }
        boolean valid = "valid".equals(r.optString("signature"));
        ScriptStore.Script preview = new ScriptStore.Script();
        preview.fingerprint = r.optString("fingerprint");
        String message = "Транспорт: " + r.optString("name") + " · " + r.optString("version", "-")
                + "\nПодпись: " + (valid ? "верна" : "НЕ совпадает с ключом")
                + "\nАвтор: " + (r.optBoolean("official") ? "OpenFlux (официальный)" : "неизвестный")
                + "\nОтпечаток: " + preview.shortFingerprint()
                + "\nПараметров: " + (r.optJSONArray("params") == null ? 0 : r.optJSONArray("params").length())
                + (valid ? "\n\nСкрипт работает без песочницы. Устанавливайте, только если доверяете этому автору и отпечатку."
                        : "\n\nУстановка невозможна: проверьте ключ автора или источник.");
        android.app.AlertDialog.Builder dialog = ui.dialog()
                .setTitle("Проверьте транспорт")
                .setMessage(message)
                .setNegativeButton("Отмена", null);
        if (valid) {
            dialog.setPositiveButton("Доверять и установить", (d, w) -> run("Устанавливаю…", () -> {
                store.install(c.data, c.sig, c.key, c.source, c.origin);
                return "Транспорт установлен";
            }));
        }
        dialog.show();
    }

    private static byte[] download(String link) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(link).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        try {
            if (connection.getResponseCode() / 100 != 2) {
                throw new IOException("сервер ответил " + connection.getResponseCode());
            }
            if (connection.getContentLengthLong() > MAX_DOWNLOAD) throw new IOException("файл слишком большой");
            try (InputStream in = connection.getInputStream()) {
                byte[] data = ScriptStore.readAll(in);
                if (data.length > MAX_DOWNLOAD) throw new IOException("файл слишком большой");
                return data;
            }
        } finally {
            connection.disconnect();
        }
    }

    // ---- updates ----

    private void checkAll() {
        run("Проверяю обновления…", () -> {
            int waiting = 0;
            for (ScriptStore.Script s : store.list()) {
                if (!s.enabled || !s.fileName.endsWith(".flux")) continue;
                JSONObject r = s.updatable()
                        ? report(Mobile.checkScriptUpdate(s.installedJson(), "stable"))
                        : new JSONObject().put("status", "error").put("code", "no_source");
                reports.put(s.id, r);
                if ("available".equals(r.optString("status"))) waiting++;
            }
            return waiting == 0 ? "Все транспорты свежие" : "Есть обновления: " + waiting;
        });
    }

    private void confirmUpdate(ScriptStore.Script s, JSONObject report) {
        boolean wireBreak = report.optBoolean("wireBreak");
        String notes = report.optString("notes");
        ui.dialog()
                .setTitle(s.name + ": " + report.optString("current") + " → " + report.optString("latest"))
                .setMessage((wireBreak ? "Обновление меняет формат обмена: ноду тоже нужно обновить, иначе связь пропадёт.\n\n" : "")
                        + (notes.isEmpty() ? "Установить обновление?" : notes))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Обновить", (d, w) -> run("Обновляю…", () -> {
                    JSONObject r = report(Mobile.applyScriptUpdate(s.installedJson(), "stable", store.dir.getPath(), wireBreak));
                    if (!"installed".equals(r.optString("status"))) return updateFailure(r.optString("code"));
                    if (!r.optString("newKey").isEmpty()) store.repin(s.id, r.optString("newKey"));
                    store.refresh(s.id);
                    reports.remove(s.id);
                    return s.name + ": теперь " + r.optString("latest");
                }))
                .show();
    }

    private void rollback(ScriptStore.Script s) {
        run("Возвращаю прошлую версию…", () -> {
            JSONObject r = report(Mobile.rollbackScript(s.installedJson(), store.dir.getPath()));
            if (!"installed".equals(r.optString("status"))) return updateFailure(r.optString("code"));
            store.refresh(s.id);
            reports.remove(s.id);
            return "Вернули версию " + r.optString("latest");
        });
    }

    private static JSONObject report(String raw) throws JSONException {
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject().put("status", "error").put("code", "bad_answer");
        }
    }

    // Words for the core's script-update codes (upstream's ScriptUpdateMessages).
    private static String updateFailure(String code) {
        switch (code) {
            case "no_source": return "У транспорта не указано, где искать обновления";
            case "fetch_failed": return "Не удалось получить сведения об обновлении: проверьте сеть";
            case "insecure_url": return "Адрес обновления не https, транспорт его не принимает";
            case "bad_index": return "Файл обновлений автора повреждён";
            case "id_mismatch": return "Обновление относится к другому транспорту";
            case "no_channel": return "У автора нет выпуска для этого канала";
            case "needs_newer_app": return "Обновлению нужна более новая версия приложения";
            case "key_changed": return "Автор сменил ключ подписи. Это не обновление: импортируйте транспорт заново и сверьте отпечаток";
            case "wire_break": return "Обновление меняет формат обмена: ноду тоже нужно обновить";
            case "bad_hash": return "Скачанный файл не совпадает с тем, что обещал автор";
            case "bad_package": return "Скачанный файл не читается как транспорт";
            case "bad_signature": return "Подпись не совпадает с ключом автора. Обновление отклонено";
            case "version_mismatch": return "Версия в скачанном файле не та, что объявлена";
            case "not_newer": return "В скачанном файле версия не новее установленной";
            case "install_failed": return "Не удалось записать обновление на диск";
            case "no_previous": return "Предыдущей версии нет";
            default: return "Не получилось: " + code;
        }
    }

    private static String sourceLabel(String source) {
        switch (source) {
            case "bundled": return "в комплекте";
            case "github": return "GitHub";
            case "link": return "ссылка";
            default: return "файл";
        }
    }

    // ---- plumbing ----

    private interface Job { Object run() throws Exception; }

    // One job at a time on the worker; a Candidate opens the trust dialog, a
    // String is shown as a toast, and the list is redrawn either way.
    private void run(String progress, Job job) {
        if (busy) return;
        busy = true;
        Toast.makeText(host, progress, Toast.LENGTH_SHORT).show();
        WORKER.execute(() -> {
            Object result;
            try {
                result = job.run();
            } catch (Exception e) {
                result = "Не получилось: " + e.getMessage();
            }
            Object done = result;
            host.runOnUiThread(() -> {
                busy = false;
                if (host.isFinishing()) return;
                render();
                if (done instanceof Candidate) showTrust((Candidate) done);
                else if (done instanceof String) Toast.makeText(host, (String) done, Toast.LENGTH_LONG).show();
            });
        });
    }

    // The app's own switch, as on the other settings tabs.
    private Switch toggle() {
        Switch toggle = new Switch(host);
        host.styleSwitch(toggle);
        return toggle;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.rounded(ui.surface, ui.border, 12));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        return card;
    }

    private TextView caption(String value, int size, boolean strong) {
        TextView view = new TextView(host);
        view.setText(value);
        view.setTextSize(size);
        if (strong) ui.heading(view);
        else ui.body(view);
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(host);
        button.setText(label);
        button.setAllCaps(false);
        if (primary) ui.primary(button);
        else ui.secondary(button);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private EditText input(String hint, int variation) {
        EditText input = new EditText(host);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        input.setTextColor(ui.text);
        input.setHintTextColor(ui.hint);
        return input;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        return params;
    }

    private LinearLayout.LayoutParams buttonRow() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        return params;
    }

    private int dp(int value) { return ui.dp(value); }
}
