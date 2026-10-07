package io.openflux.app;

import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// A saved connection configuration: which transport, which document/secret,
// a display name and an icon. Lets a user keep several exit nodes configured
// and switch between them without re-typing anything.
//
// In Session mode (the CLI's --negotiate / --transports) the main transport
// is joined by extra ones, all running at once with failover by priority.
//
// In stream mode (the CLI's --mode=stream) there is no server: the exit is a
// PHP node on ordinary hosting (deploy/phpbox), reached over one Cups.online
// room or Mail.ru document, with no key and no Session.
final class Profile {
    long id;
    String name = "";
    String icon = "ic_public";
    String transportType = "yandex";
    String documentUrl = "";
    String encryptionSecret = "";
    String codec = "batched";
    String maxToken = "";
    String maxUid = "";

    boolean session;
    boolean stream;
    int priority = 50;
    // Session encryption context (the exit's --url), set by an imported
    // openflux:// link; empty means the bridge derives it from the transports.
    String context = "";
    // The main carrier's JS transport (ScriptStore id) when transportType is
    // "script", and what it saved in the script's settings page.
    String scriptId = "";
    JSONObject settings = new JSONObject();
    final List<Transport> extraTransports = new ArrayList<>();

    // One extra carrier of a Session profile.
    static final class Transport {
        String type = "direct";
        // Document URL; host:port for direct; MAX Web token for oneme.
        String value = "";
        String uid = "";
        int priority = 100;
        String scriptId = "";
        JSONObject settings = new JSONObject();

        Transport copy() {
            Transport t = new Transport();
            t.type = type;
            t.value = value;
            t.uid = uid;
            t.priority = priority;
            t.scriptId = scriptId;
            t.settings = copyOf(settings);
            return t;
        }
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("icon", icon);
        o.put("transportType", transportType);
        o.put("documentUrl", documentUrl);
        o.put("encryptionSecret", encryptionSecret);
        o.put("codec", codec);
        o.put("maxToken", maxToken);
        o.put("maxUid", maxUid);
        o.put("session", session);
        o.put("stream", stream);
        o.put("priority", priority);
        o.put("context", context);
        o.put("scriptId", scriptId);
        o.put("settings", settings);
        JSONArray extras = new JSONArray();
        for (Transport t : extraTransports) {
            extras.put(new JSONObject()
                    .put("type", t.type)
                    .put("value", t.value)
                    .put("uid", t.uid)
                    .put("priority", t.priority)
                    .put("scriptId", t.scriptId)
                    .put("settings", t.settings));
        }
        o.put("extraTransports", extras);
        return o;
    }

    static Profile fromJson(JSONObject o) {
        Profile p = new Profile();
        p.id = o.optLong("id");
        p.name = o.optString("name", "");
        p.icon = o.optString("icon", "ic_public");
        p.transportType = o.optString("transportType", "yandex");
        p.documentUrl = o.optString("documentUrl", "");
        p.encryptionSecret = o.optString("encryptionSecret", "");
        p.codec = o.optString("codec", "batched");
        p.maxToken = o.optString("maxToken", "");
        p.maxUid = o.optString("maxUid", "");
        p.session = o.optBoolean("session", false);
        p.stream = o.optBoolean("stream", false);
        p.priority = o.optInt("priority", 50);
        p.context = o.optString("context", "");
        p.scriptId = o.optString("scriptId", "");
        p.settings = copyOf(o.optJSONObject("settings"));
        JSONArray extras = o.optJSONArray("extraTransports");
        for (int i = 0; extras != null && i < extras.length(); i++) {
            JSONObject e = extras.optJSONObject(i);
            if (e == null) continue;
            Transport t = new Transport();
            t.type = e.optString("type", "direct");
            t.value = e.optString("value", "");
            t.uid = e.optString("uid", "");
            t.priority = e.optInt("priority", 100);
            t.scriptId = e.optString("scriptId", "");
            t.settings = copyOf(e.optJSONObject("settings"));
            p.extraTransports.add(t);
        }
        return p;
    }

    static String transportLabel(String type) {
        if ("direct".equals(type)) return "Direct (TCP до ноды)";
        if ("vyandex".equals(type)) return "Yandex Docs (Volga)";
        if ("boards".equals(type)) return "Yandex Board";
        if ("mailru".equals(type)) return "Mail.ru Docs";
        if ("cupsonline".equals(type)) return "Cups.online";
        if ("oneme".equals(type)) return "MAX (OneMe)";
        if ("script".equals(type)) return "JS-транспорт";
        return "Yandex Docs";
    }

    // The carriers traffic goes through right now (Mobile.currentTransports:
    // Session names like "boards" or "boards-2", or a classic type) in the
    // user's words. A stream connection names none, so it passes its type
    // as streamType.
    static String carriersLabel(String names, String streamType) {
        if (names.isEmpty()) names = streamType;
        StringBuilder out = new StringBuilder();
        for (String name : names.split(",")) {
            if (name.isEmpty()) continue;
            String type = name.replaceFirst("-\\d+$", "");
            boolean known = Arrays.asList("direct", "yandex", "vyandex", "boards", "mailru", "cupsonline", "oneme", "script")
                    .contains(type);
            if (out.length() > 0) out.append(", ");
            out.append(known ? transportLabel(type) + name.substring(type.length()).replace('-', ' ') : name);
        }
        return out.toString();
    }

    // Puts what both connection services need to start this profile; they
    // read the same extra keys. scripts resolves JS carriers to their files.
    void putConnectionExtras(Intent intent, ScriptStore scripts) {
        intent.putExtra(OpenFluxTunnelService.EXTRA_DOCUMENT_URL, documentUrl);
        intent.putExtra(OpenFluxTunnelService.EXTRA_ENCRYPTION_SECRET, encryptionSecret);
        intent.putExtra(OpenFluxTunnelService.EXTRA_TRANSPORT_TYPE, transportType);
        intent.putExtra(OpenFluxTunnelService.EXTRA_CODEC, codec);
        intent.putExtra(OpenFluxTunnelService.EXTRA_MAX_TOKEN, maxToken);
        intent.putExtra(OpenFluxTunnelService.EXTRA_MAX_UID, maxUid);
        intent.putExtra(OpenFluxTunnelService.EXTRA_STREAM, stream);
        String specs = "";
        if (session) {
            try {
                specs = sessionTransportsJson(scripts);
            } catch (JSONException ignored) {
                // Unreachable for string/int values; classic mode is the fallback.
            }
        }
        intent.putExtra(OpenFluxTunnelService.EXTRA_SESSION_TRANSPORTS, specs);
    }

    // The transport list Mobile.startSession takes: the main transport plus
    // the extra ones. Names follow the CLI, which names --transports entries
    // after their type (then type-2, type-3 for repeats), so they match the
    // exit's: cookie exchange is addressed by name.
    String sessionTransportsJson(ScriptStore scripts) throws JSONException {
        JSONArray out = new JSONArray();
        Map<String, Integer> seen = new HashMap<>();
        String mainValue = "oneme".equals(transportType) ? maxToken : documentUrl;
        out.put(spec(seen, transportType, mainValue, maxUid, priority, script(scripts, scriptId), settings));
        for (Transport t : extraTransports) {
            out.put(spec(seen, t.type, t.value, t.uid, t.priority, script(scripts, t.scriptId), t.settings));
        }
        if (context.isEmpty()) return out.toString();
        return new JSONObject().put("context", context).put("transports", out).toString();
    }

    // Builds a profile from an openflux:// link's configuration (the JSON
    // Mobile.parseShareLink returns). The highest-priority non-direct
    // transport becomes the main one, the rest become Session extras.
    static Profile fromShare(JSONObject c) throws JSONException {
        JSONArray ts = c.getJSONArray("transports");
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < ts.length(); i++) sorted.add(ts.getJSONObject(i));
        sorted.sort((a, b) -> Integer.compare(b.optInt("priority"), a.optInt("priority")));
        JSONObject main = sorted.get(0);
        for (JSONObject t : sorted) {
            if (!"direct".equals(t.optString("type"))) { main = t; break; }
        }
        Profile p = new Profile();
        p.id = System.currentTimeMillis();
        p.name = c.optString("name", "");
        if (p.name.isEmpty()) p.name = "OpenFlux";
        p.session = c.optBoolean("negotiate", false);
        p.stream = "stream".equals(c.optString("mode"));
        p.encryptionSecret = c.optString("secret", "");
        p.codec = "legacy".equals(c.optString("codec")) ? "legacy" : "batched";
        p.context = p.session ? c.optString("context", "") : "";
        p.transportType = main.getString("type");
        p.documentUrl = shareValue(main);
        p.priority = main.optInt("priority", 50);
        for (JSONObject t : sorted) {
            if (t == main) continue;
            Transport x = new Transport();
            x.type = t.getString("type");
            x.value = shareValue(t);
            x.priority = t.optInt("priority", 0);
            p.extraTransports.add(x);
        }
        return p;
    }

    private static String shareValue(JSONObject t) {
        return "direct".equals(t.optString("type")) ? t.optString("dial") : t.optString("url");
    }

    // Why this profile cannot start with the installed scripts, or null:
    // a JS carrier needs Session and an enabled, installed script.
    String scriptProblem(ScriptStore scripts) {
        List<String> ids = new ArrayList<>();
        if ("script".equals(transportType)) ids.add(scriptId);
        if (session) for (Transport t : extraTransports) if ("script".equals(t.type)) ids.add(t.scriptId);
        if (ids.isEmpty()) return null;
        if (!session) return "JS-транспорт работает только в режиме Session";
        for (String id : ids) {
            if (id.isEmpty()) return "Выберите JS-транспорт";
            if (script(scripts, id) == null) {
                return "JS-транспорт «" + id + "» не установлен, выключен или экспериментальные функции выключены";
            }
        }
        return null;
    }

    private static ScriptStore.Script script(ScriptStore scripts, String id) {
        if (scripts == null || id.isEmpty()) return null;
        for (ScriptStore.Script s : scripts.usable()) if (s.id.equals(id)) return s;
        return null;
    }

    static JSONObject copyOf(JSONObject o) {
        try {
            return o == null ? new JSONObject() : new JSONObject(o.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONObject spec(Map<String, Integer> seen, String type, String value, String uid,
            int priority, ScriptStore.Script script, JSONObject saved) throws JSONException {
        int n = seen.containsKey(type) ? seen.get(type) + 1 : 1;
        seen.put(type, n);
        JSONObject params = new JSONObject();
        String url = "";
        if ("direct".equals(type)) {
            params.put("dial", value);
        } else if ("oneme".equals(type)) {
            params.put("token", value);
            params.put("uid", uid);
        } else if ("script".equals(type)) {
            url = value;
            if (script != null) {
                params.put("path", script.path);
                params.put("pubkey", script.pubkey);
                params.put("name", script.id);
                // The profile field's value rides along under its declared key
                // too, so a script reading cfg.params[key] sees it. Nested: the
                // core merges it without letting it shadow path/pubkey/name.
                JSONObject merged = copyOf(saved);
                if (!script.primaryKey().isEmpty()) merged.put(script.primaryKey(), value);
                if (merged.length() > 0) params.put("settings", merged);
            }
        } else {
            url = value;
        }
        return new JSONObject()
                .put("name", n == 1 ? type : type + "-" + n)
                .put("type", type)
                .put("url", url)
                .put("priority", priority)
                .put("params", params);
    }
}
