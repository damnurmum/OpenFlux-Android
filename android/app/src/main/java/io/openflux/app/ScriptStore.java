package io.openflux.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import io.openflux.bridge.mobile.Mobile;

// The JS (goja) script transports installed on this device: the signed files
// in filesDir/scripts and registry.json listing them, with the author key
// pinned when the user trusted each one. The core re-verifies the signature
// against that key every time it loads a file, so a swapped file fails
// closed; this record is for the UI and for building the carrier. Ported from
// upstream's FileScriptRepository / AndroidScriptRepository.
//
// A script's saved values (the profile field, the settings page) are not
// here: one script can back any number of carriers, so they live on the
// carrier (Profile.settings, Profile.Transport.settings).
final class ScriptStore {
    // Every JS-engine feature sits behind this switch, as in upstream's apps.
    static final String KEY_EXPERIMENTAL = "experimental";
    static final String TAG = "OpenFluxJS";

    static final class Script {
        String id = "", name = "", version = "", pubkey = "", fingerprint = "", fileName = "";
        String source = "file", origin = "", packageId = "";
        boolean official, enabled = true, settingsPage;
        int wire = 1;
        JSONArray params = new JSONArray();
        JSONArray updateUrls = new JSONArray();
        // The file on disk; set when listed, not stored.
        String path = "";

        // The param the profile's own field edits: the one the core scoped
        // "profile", or the first for a record from before scopes.
        JSONObject primaryParam() {
            boolean scoped = false;
            for (int i = 0; i < params.length(); i++) {
                if (!params.optJSONObject(i).optString("scope").isEmpty()) scoped = true;
            }
            for (int i = 0; i < params.length(); i++) {
                JSONObject p = params.optJSONObject(i);
                if (!scoped || "profile".equals(p.optString("scope"))) return p;
            }
            return null;
        }

        String primaryKey() {
            JSONObject p = primaryParam();
            return p == null ? "" : p.optString("key");
        }

        boolean hasSettings() { return params.length() > 0 || settingsPage; }

        boolean updatable() { return fileName.endsWith(".flux") && updateUrls.length() > 0; }

        // "ab cd ef 12 …", what the user compares out of band.
        String shortFingerprint() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i + 2 <= fingerprint.length() && i < 16; i += 2) {
                if (out.length() > 0) out.append(' ');
                out.append(fingerprint, i, i + 2);
            }
            return fingerprint.length() > 16 ? out + " …" : out.toString();
        }

        // What CheckScriptUpdate / ApplyScriptUpdate / RollbackScript take.
        String installedJson() throws JSONException {
            return new JSONObject()
                    .put("id", packageId.isEmpty() ? id : packageId)
                    .put("file", fileName)
                    .put("version", version)
                    .put("wire", wire)
                    .put("pubkey", pubkey)
                    .put("update", updateUrls)
                    .toString();
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id).put("name", name).put("version", version)
                    .put("pubkey", pubkey).put("fingerprint", fingerprint).put("fileName", fileName)
                    .put("source", source).put("origin", origin).put("packageId", packageId)
                    .put("official", official).put("enabled", enabled).put("settingsPage", settingsPage)
                    .put("wire", wire).put("params", params).put("updateUrls", updateUrls);
        }

        static Script fromJson(JSONObject o) {
            Script s = new Script();
            s.id = o.optString("id");
            s.name = o.optString("name", s.id);
            s.version = o.optString("version");
            s.pubkey = o.optString("pubkey");
            s.fingerprint = o.optString("fingerprint");
            s.fileName = o.optString("fileName");
            s.source = o.optString("source", "file");
            s.origin = o.optString("origin");
            s.packageId = o.optString("packageId");
            s.official = o.optBoolean("official");
            s.enabled = o.optBoolean("enabled", true);
            s.settingsPage = o.optBoolean("settingsPage");
            s.wire = o.optInt("wire", 1);
            JSONArray params = o.optJSONArray("params");
            if (params != null) s.params = params;
            JSONArray urls = o.optJSONArray("updateUrls");
            if (urls != null) s.updateUrls = urls;
            return s;
        }

        // Takes what the core's trust report says about the file on disk; an
        // update may declare new settings, so the record follows the file.
        void apply(JSONObject report) {
            version = report.optString("version", version);
            JSONArray reported = report.optJSONArray("params");
            if (reported != null) params = reported;
            settingsPage = report.optBoolean("settingsPage");
            packageId = report.optString("id");
            wire = report.optInt("wire", 1);
            JSONArray urls = report.optJSONArray("update");
            updateUrls = urls != null ? urls : new JSONArray();
        }
    }

    final File dir;
    private final Context context;
    private final File registry;

    ScriptStore(Context context) {
        this.context = context.getApplicationContext();
        dir = new File(this.context.getFilesDir(), "scripts");
        registry = new File(dir, "registry.json");
    }

    static boolean experimental(Context context) {
        return context.getSharedPreferences(MainActivity.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_EXPERIMENTAL, false);
    }

    // Read from disk every time: the screen, the editor and the services each
    // hold their own store, and the list is a handful of entries.
    synchronized List<Script> list() {
        List<Script> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(new String(Files.readAllBytes(registry.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                Script s = Script.fromJson(arr.getJSONObject(i));
                File file = new File(dir, s.fileName);
                s.path = file.getPath();
                if (file.exists()) out.add(s);
            }
        } catch (IOException | JSONException ignored) {
            // No registry yet, or an unreadable one: nothing installed.
        }
        return out;
    }

    Script byId(String id) {
        for (Script s : list()) if (s.id.equals(id)) return s;
        return null;
    }

    // Enabled scripts while the experimental features are on; nothing otherwise.
    List<Script> usable() {
        List<Script> out = new ArrayList<>();
        if (!experimental(context)) return out;
        for (Script s : list()) if (s.enabled) out.add(s);
        return out;
    }

    // Verifies data (a .flux, or a bare .js with its detached sig) against
    // pubkey through the core and, only if the signature is valid, writes it
    // and records it. Throws with the reason the trust dialog shows.
    synchronized Script install(byte[] data, byte[] sig, String pubkey, String source, String origin) {
        JSONObject report = inspect(data, sig, pubkey.trim());
        if (report == null || !report.optBoolean("ok")) {
            throw new IllegalArgumentException(report != null && !report.optString("error").isEmpty()
                    ? report.optString("error") : "скрипт не читается");
        }
        if (!"valid".equals(report.optString("signature"))) {
            throw new IllegalArgumentException("подпись не совпадает с ключом автора");
        }
        String name = report.optString("name");
        if (name.isEmpty()) name = "script";
        boolean flux = data.length >= 2 && data[0] == 'P' && data[1] == 'K';
        String fileName = flux ? name + ".flux" : name + ".js";
        try {
            dir.mkdirs();
            write(new File(dir, fileName), data);
            if (!flux) write(new File(dir, fileName + ".sig"), sig);
        } catch (IOException e) {
            throw new IllegalArgumentException("не удалось записать файл: " + e.getMessage());
        }
        Script s = new Script();
        s.id = name;
        s.name = name;
        s.pubkey = pubkey.trim();
        s.fingerprint = report.optString("fingerprint");
        s.fileName = fileName;
        s.official = report.optBoolean("official");
        s.source = source;
        s.origin = origin;
        s.apply(report);
        upsert(s);
        return s;
    }

    synchronized void delete(String id) {
        Script s = byId(id);
        if (s != null) {
            for (String suffix : new String[]{"", ".sig", ".prev"}) new File(dir, s.fileName + suffix).delete();
        }
        List<Script> all = list();
        all.removeIf(x -> x.id.equals(id));
        save(all);
    }

    synchronized void setEnabled(String id, boolean enabled) {
        List<Script> all = list();
        for (Script s : all) if (s.id.equals(id)) s.enabled = enabled;
        save(all);
    }

    // An official transport may move to another official key in an update.
    synchronized void repin(String id, String pubkey) {
        List<Script> all = list();
        for (Script s : all) {
            if (!s.id.equals(id)) continue;
            s.pubkey = pubkey;
            s.fingerprint = Mobile.scriptFingerprint(pubkey);
        }
        save(all);
    }

    // Re-reads the installed file's report after an update or a rollback.
    synchronized void refresh(String id) {
        Script s = byId(id);
        byte[][] pkg = packageBytes(s);
        if (pkg == null) return;
        JSONObject report = inspect(pkg[0], pkg[1], s.pubkey);
        if (report == null || !report.optBoolean("ok") || !"valid".equals(report.optString("signature"))) return;
        s.apply(report);
        upsert(s);
    }

    // The file and its detached signature (empty for a .flux), or null.
    byte[][] packageBytes(Script s) {
        if (s == null) return null;
        try {
            byte[] data = Files.readAllBytes(new File(dir, s.fileName).toPath());
            File sig = new File(dir, s.fileName + ".sig");
            return new byte[][]{data, sig.exists() ? Files.readAllBytes(sig.toPath()) : new byte[0]};
        } catch (IOException e) {
            return null;
        }
    }

    boolean hasPrevious(Script s) { return new File(dir, s.fileName + ".prev").exists(); }

    // Installs the scripts shipped in the APK, or replaces the installed
    // copies when this build brings newer ones: a bundled script has no
    // update address, so a new build is how it gets fixed. Only bundled
    // installs are touched; a deleted one stays deleted, a disabled one off.
    // Runs the script engine, so only once the experimental features are on.
    synchronized void syncBundled() {
        boolean fresh = list().isEmpty();
        String official = Mobile.officialScriptKey();
        String[] files;
        try {
            files = context.getAssets().list("scripts");
        } catch (IOException e) {
            return;
        }
        if (files == null) return;
        for (String file : files) {
            if (!file.endsWith(".js")) continue;
            try {
                byte[] js = asset("scripts/" + file);
                byte[] sig = asset("scripts/" + file + ".sig");
                if (fresh) {
                    install(js, sig, official, "bundled", "bundled:" + file);
                    continue;
                }
                JSONObject report = inspect(js, sig, official);
                if (report == null || !"valid".equals(report.optString("signature"))) continue;
                Script installed = byId(report.optString("name"));
                if (installed == null || !"bundled".equals(installed.source)) continue;
                if (Mobile.compareScriptVersions(report.optString("version"), installed.version) <= 0) continue;
                install(js, sig, official, "bundled", "bundled:" + file);
                if (!installed.enabled) setEnabled(installed.id, false);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "bundled " + file + " not installed: " + e.getMessage());
            }
        }
    }

    JSONObject inspect(byte[] data, byte[] sig, String pubkey) {
        try {
            return new JSONObject(Mobile.inspectTransport(data, sig, pubkey));
        } catch (JSONException e) {
            return null;
        }
    }

    private void upsert(Script script) {
        List<Script> all = list();
        all.removeIf(x -> x.id.equals(script.id));
        all.add(script);
        save(all);
    }

    private void save(List<Script> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Script s : all) arr.put(s.toJson());
            dir.mkdirs();
            File tmp = new File(dir, "registry.json.tmp");
            write(tmp, arr.toString(2).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(registry)) throw new IOException("rename failed");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "registry not saved", e);
        }
    }

    private byte[] asset(String path) throws IOException {
        try (InputStream in = context.getAssets().open(path)) {
            return readAll(in);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    // What a settings or setup page handed to window.openfluxSubmit, as the
    // {key: value} map a script receives: the core's FlattenSubmission rules
    // (flat or {client: {...}}; strings, numbers and booleans as strings).
    static JSONObject flatten(String json) {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Страница настройки передала не JSON-объект");
        }
        JSONObject scoped = root.optJSONObject("client");
        if (scoped == null) scoped = root;
        JSONObject out = new JSONObject();
        for (Iterator<String> keys = scoped.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object v = scoped.opt(key);
            if (key.isEmpty() || !(v instanceof String || v instanceof Number || v instanceof Boolean)) continue;
            try {
                out.put(key, String.valueOf(v));
            } catch (JSONException ignored) {
                // A non-empty string key always fits.
            }
        }
        if (out.length() == 0) throw new IllegalArgumentException("Страница настройки не передала данных");
        return out;
    }
}
