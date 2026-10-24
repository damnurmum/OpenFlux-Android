package io.openflux.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONObject;
import android.util.Base64;
import io.openflux.bridge.mobile.Mobile;

// The profile editor: name, mode (classic, Session, without a server),
// the transport (built-in or JavaScript) and its value, codec, key, and a
// Session's extra transports. Edits live here until "Сохранить профиль"
// writes them to the profile.
final class ProfileEditor {
    private final MainActivity app;
    private boolean urlVisible;
    EditText urlInput;
    EditText encryptionInput;
    EditText maxTokenInput;
    EditText maxUidInput;
    private View maxFieldsContainer;
    private ImageButton visibilityButton;
    private ImageButton encryptionVisibilityButton;
    private Long editingProfileId;
    private String editorTransportType = "yandex";
    private String editorCodec = "batched";
    private String editorMaxToken = "";
    private String editorMaxUid = "";
    private boolean editorSession;
    private boolean editorStream;

    // The main carrier's JS transport and its saved settings ("script" type).
    private String editorScriptId = "";

    private JSONObject editorSettings = new JSONObject();
    private View scriptSettingsButton;

    // A settings page in flight: what the script declares, where the answer goes.
    private java.util.Set<String> pendingSettingsKeys;

    private String pendingSettingsPrimary = "";
    private java.util.function.BiConsumer<String, JSONObject> pendingSettingsSave;
    private int editorPriority = 50;
    private final List<Profile.Transport> editorExtras = new ArrayList<>();
    private View sessionFieldsContainer;
    private View codecSection;
    private View urlField;
    private TextView encryptionHintView;
    private View encryptionSection;
    private View streamHintView;
    private EditText priorityInput;
    private LinearLayout extrasList;
    EditText profileNameInput;
    private boolean encryptionVisible;
    private static final Pattern CUPS_ROOMS_CODE = Pattern.compile("[A-Za-z0-9_-]+");

    ProfileEditor(MainActivity app) {
        this.app = app;
    }

    void openProfileEditor(Profile existing) {
        editingProfileId = existing != null ? existing.id : null;
        editorTransportType = existing != null ? existing.transportType : "yandex";
        editorCodec = existing != null ? existing.codec : "batched";
        editorMaxToken = existing != null ? existing.maxToken : "";
        editorMaxUid = existing != null ? existing.maxUid : "";
        editorSession = existing != null && existing.session;
        editorStream = existing != null && existing.stream;
        editorScriptId = existing != null ? existing.scriptId : "";
        editorSettings = Profile.copyOf(existing != null ? existing.settings : null);
        editorPriority = existing != null ? existing.priority : 50;
        editorExtras.clear();
        if (existing != null) for (Profile.Transport t : existing.extraTransports) editorExtras.add(t.copy());
        app.profileEditorOpen = true;
        app.showPage(MainActivity.PAGE_PROFILES);
    }

    void saveProfileFromEditor(String name, String docUrl, String secret, String token, String uid) {
        if (name.isEmpty()) {
            Toast.makeText(app, "Укажите название профиля", Toast.LENGTH_SHORT).show();
            return;
        }
        if (editorStream && !"cupsonline".equals(editorTransportType) && !"mailru".equals(editorTransportType)) {
            Toast.makeText(app, "Режим без сервера работает только через Cups.online или Mail.ru", Toast.LENGTH_LONG).show();
            return;
        }
        boolean mainScript = "script".equals(editorTransportType);
        if (mainScript && editorScriptId.isEmpty()) {
            Toast.makeText(app, "Выберите JS-транспорт", Toast.LENGTH_LONG).show();
            return;
        }
        if (mainScript && !editorSession) {
            Toast.makeText(app, "JS-транспорт работает только в режиме Session", Toast.LENGTH_LONG).show();
            return;
        }
        // The stream mode has no key; a hidden leftover one must not be saved.
        if (editorStream) secret = "";
        String valueProblem = transportValueProblem(editorTransportType,
                "oneme".equals(editorTransportType) ? token : docUrl, !editorStream);
        if (valueProblem != null) {
            Toast.makeText(app, valueProblem, Toast.LENGTH_LONG).show();
            return;
        }
        if (!secret.isEmpty() && secret.length() < 16) {
            Toast.makeText(app, "Ключ шифрования должен быть не короче 16 символов, либо оставьте поле пустым",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int mainPriority = MainActivity.parsePriority(priorityInput != null ? priorityInput.getText().toString() : "", 50);
        if (editorSession) {
            if (secret.length() < 16) {
                Toast.makeText(app, "Для режима Session нужен ключ шифрования не короче 16 символов",
                        Toast.LENGTH_LONG).show();
                return;
            }
            for (Profile.Transport t : editorExtras) {
                String problem = extraTransportProblem(t);
                if (problem != null) {
                    Toast.makeText(app, problem, Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
        Profile target = null;
        if (editingProfileId != null) {
            for (Profile p : app.profiles) if (p.id == editingProfileId) { target = p; break; }
        }
        boolean isNew = target == null;
        if (isNew) {
            target = new Profile();
            target.id = System.currentTimeMillis();
            app.profiles.add(target);
        }
        target.name = name;
        target.transportType = editorTransportType;
        target.documentUrl = docUrl;
        target.encryptionSecret = secret;
        target.codec = editorCodec;
        target.maxToken = token;
        target.maxUid = uid;
        target.session = editorSession && !editorStream;
        target.scriptId = mainScript ? editorScriptId : "";
        target.settings = mainScript ? Profile.copyOf(editorSettings) : new JSONObject();
        target.stream = editorStream;
        target.priority = mainPriority;
        target.extraTransports.clear();
        for (Profile.Transport t : editorExtras) target.extraTransports.add(t.copy());
        app.profileStore.save(app.profiles);
        if (isNew && app.selectedProfile() == null) app.selectProfile(target.id);
        if (target.id == app.selectedProfileId) app.applySelectedProfileToFields();
        Toast.makeText(app, "Профиль сохранён", Toast.LENGTH_SHORT).show();
        app.closeProfileEditor();
    }

    View buildProfileEditorHeader() {
        LinearLayout header = new LinearLayout(app);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = app.iconButton(R.drawable.ic_arrow_back, "Назад к профилям");
        back.setOnClickListener(v -> {
            app.bounce(v);
            app.closeProfileEditor();
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(app.dp(44), app.dp(44));
        backParams.rightMargin = app.dp(6);
        header.addView(back, backParams);
        header.addView(app.text(editingProfileId != null ? "Изменить профиль" : "Новый профиль", 22, app.text, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        return header;
    }

    View buildProfileEditor() {
        Profile existing = null;
        if (editingProfileId != null) {
            for (Profile p : app.profiles) if (p.id == editingProfileId) { existing = p; break; }
        }
        String initialUrl = existing != null ? existing.documentUrl : "";
        String initialSecret = existing != null ? existing.encryptionSecret : "";

        LinearLayout section = app.page();

        FrameLayout nameField = new FrameLayout(app);
        nameField.setBackground(app.rounded(app.surface, app.border, 1, 10));
        profileNameInput = app.settingInput("Название профиля", existing != null ? existing.name : "",
                InputType.TYPE_CLASS_TEXT);
        profileNameInput.setPadding(app.dp(16), 0, app.dp(16), 0);
        nameField.addView(profileNameInput, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, app.dp(ThemedActivity.FIELD_HEIGHT));
        section.addView(app.floating(nameField, profileNameInput), nameParams);

        TextView modeLabel = app.label("РЕЖИМ");
        LinearLayout.LayoutParams modeLabelParams = app.matchWrap();
        modeLabelParams.topMargin = app.dp(18);
        modeLabelParams.bottomMargin = app.dp(8);
        section.addView(modeLabel, modeLabelParams);
        section.addView(buildSessionModeSelector(), app.matchWrap());

        TextView transportTypeLabel = app.label("ТРАНСПОРТ");
        LinearLayout.LayoutParams transportTypeLabelParams = app.matchWrap();
        transportTypeLabelParams.topMargin = app.dp(18);
        transportTypeLabelParams.bottomMargin = app.dp(8);
        section.addView(transportTypeLabel, transportTypeLabelParams);
        section.addView(buildTransportTypeSelector(), app.matchWrap());

        LinearLayout.LayoutParams maxFieldsParams = app.matchWrap();
        maxFieldsParams.topMargin = app.dp(8);
        maxFieldsContainer = buildMaxFields(existing);
        maxFieldsContainer.setVisibility("oneme".equals(editorTransportType) ? View.VISIBLE : View.GONE);
        section.addView(maxFieldsContainer, maxFieldsParams);

        // Session always uses the batched codec, so the choice only exists in
        // classic mode.
        LinearLayout codecBox = new LinearLayout(app);
        codecBox.setOrientation(LinearLayout.VERTICAL);
        TextView codecLabel = app.label("КОДЕК");
        LinearLayout.LayoutParams codecLabelParams = app.matchWrap();
        codecLabelParams.topMargin = app.dp(18);
        codecLabelParams.bottomMargin = app.dp(8);
        codecBox.addView(codecLabel, codecLabelParams);
        codecBox.addView(buildCodecSelector(), app.matchWrap());
        codecSection = codecBox;
        section.addView(codecBox, app.matchWrap());

        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(-1, app.dp(ThemedActivity.FIELD_HEIGHT));
        urlParams.topMargin = app.dp(10);
        urlField = buildUrlField(initialUrl);
        // MAX has its own token field above; the link field would duplicate it.
        urlField.setVisibility("oneme".equals(editorTransportType) ? View.GONE : View.VISIBLE);
        section.addView(urlField, urlParams);

        Button scriptSettings = new Button(app);
        scriptSettings.setText("Настройки транспорта");
        scriptSettings.setAllCaps(false);
        scriptSettings.setTextColor(app.accent);
        scriptSettings.setTextSize(13);
        scriptSettings.setStateListAnimator(null);
        scriptSettings.setBackground(app.ripple(Color.TRANSPARENT, 9));
        scriptSettings.setOnClickListener(v -> {
            app.tap(v);
            openScriptSettings(editorScriptId, urlInput.getText().toString().trim(), editorSettings, (value, rest) -> {
                if (value != null) urlInput.setText(value);
                editorSettings = rest;
            });
        });
        scriptSettingsButton = scriptSettings;
        section.addView(scriptSettings, new LinearLayout.LayoutParams(-1, app.dp(44)));
        updateScriptSettingsButton();

        TextView streamHint = app.text("Выход - PHP-нода на обычном хостинге (deploy/phpbox), связь с ней через "
                + "одну комнату Cups.online или документ Mail.ru. Ключа и Session нет: содержимое защищает "
                + "TLS самих приложений. Через PHP-ноду идёт только TCP на портах 80 и 443 (сайты и большинство "
                + "приложений); в режиме туннеля остальной UDP (звонки, игры, QUIC) и IPv6 отбрасываются.",
                11, app.secondary, false);
        streamHintView = streamHint;
        LinearLayout.LayoutParams streamHintParams = app.matchWrap();
        streamHintParams.topMargin = app.dp(8);
        streamHintParams.leftMargin = app.dp(4);
        streamHintParams.rightMargin = app.dp(4);
        section.addView(streamHint, streamHintParams);

        // The key, its hint and the generator; the stream mode has no key.
        LinearLayout encryptionBox = new LinearLayout(app);
        encryptionBox.setOrientation(LinearLayout.VERTICAL);
        encryptionSection = encryptionBox;
        section.addView(encryptionBox, app.matchWrap());
        LinearLayout.LayoutParams encryptionParams = new LinearLayout.LayoutParams(-1, app.dp(ThemedActivity.FIELD_HEIGHT));
        encryptionParams.topMargin = app.dp(8);
        encryptionBox.addView(buildEncryptionField(initialSecret), encryptionParams);
        TextView encryptionHint = app.text("", 11, app.secondary, false);
        encryptionHintView = encryptionHint;
        LinearLayout.LayoutParams encryptionHintParams = app.matchWrap();
        encryptionHintParams.topMargin = app.dp(5);
        encryptionHintParams.leftMargin = app.dp(4);
        encryptionHintParams.rightMargin = app.dp(4);
        encryptionBox.addView(encryptionHint, encryptionHintParams);
        Button generateKey = new Button(app);
        generateKey.setText("Сгенерировать безопасный ключ");
        generateKey.setAllCaps(false);
        generateKey.setTextColor(app.accent);
        generateKey.setTextSize(13);
        generateKey.setStateListAnimator(null);
        generateKey.setBackground(app.ripple(Color.TRANSPARENT, 9));
        generateKey.setOnClickListener(v -> {
            app.tap(v);
            generateEncryptionSecret();
        });
        LinearLayout.LayoutParams generateParams = new LinearLayout.LayoutParams(-1, app.dp(44));
        generateParams.topMargin = app.dp(4);
        encryptionBox.addView(generateKey, generateParams);

        sessionFieldsContainer = buildSessionFields();
        section.addView(sessionFieldsContainer, app.matchWrap());
        applySessionVisibility();

        if (existing != null) {
            Profile toDelete = existing;
            Button delete = new Button(app);
            delete.setText("Удалить профиль");
            delete.setAllCaps(false);
            delete.setTextColor(app.darkMode ? Color.rgb(242, 139, 130) : Color.rgb(217, 48, 37));
            delete.setTextSize(14);
            delete.setStateListAnimator(null);
            delete.setBackground(app.ripple(Color.TRANSPARENT, 9));
            delete.setOnClickListener(v -> {
                app.tap(v);
                app.deleteProfile(toDelete);
            });
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-1, app.dp(44));
            deleteParams.topMargin = app.dp(6);
            deleteParams.bottomMargin = app.dp(12);
            section.addView(delete, deleteParams);
        }
        return section;
    }

    private View buildSessionModeSelector() {
        return app.segmented(new String[]{"Классический", "Session", "Без сервера"},
                editorStream ? 2 : editorSession ? 1 : 0, i -> {
            editorSession = i == 1;
            editorStream = i == 2;
            applySessionVisibility();
        });
    }

    // The main field's label in the editor: in stream mode Cups.online takes
    // the room's link, not the classic exit's room code.
    private String editorValueLabel() {
        if ("script".equals(editorTransportType)) return scriptValueLabel(editorScriptId);
        return editorStream && "cupsonline".equals(editorTransportType)
                ? "Ссылка на комнату Cups.online" : transportValueLabel(editorTransportType);
    }

    // The profile field of a JS transport: its primary param's label.
    private String scriptValueLabel(String scriptId) {
        ScriptStore.Script script = new ScriptStore(app).byId(scriptId);
        org.json.JSONObject param = script == null ? null : script.primaryParam();
        String label = param == null ? "" : param.optString("label", param.optString("key"));
        return label.isEmpty() ? "Значение для JS-транспорта (если нужно)" : label;
    }

    private void updateScriptSettingsButton() {
        if (scriptSettingsButton == null) return;
        ScriptStore.Script script = "script".equals(editorTransportType) ? new ScriptStore(app).byId(editorScriptId) : null;
        scriptSettingsButton.setVisibility(script != null && script.hasSettings() ? View.VISIBLE : View.GONE);
    }

    // Opens a JS transport's settings page (the core builds it from what the
    // script declares, or the script brings its own) prefilled from the
    // carrier's saved values plus its profile field; onSave gets the field's
    // new value and the rest of the settings.
    private void openScriptSettings(String scriptId, String value, JSONObject saved,
            java.util.function.BiConsumer<String, JSONObject> onSave) {
        ScriptStore store = new ScriptStore(app);
        ScriptStore.Script script = store.byId(scriptId);
        if (script == null) return;
        new Thread(() -> {
            String message = null;
            JSONObject answer = null;
            try {
                byte[][] pkg = store.packageBytes(script);
                if (pkg == null) throw new IllegalStateException("файл скрипта не найден");
                JSONObject values = Profile.copyOf(saved);
                if (!script.primaryKey().isEmpty()) values.put(script.primaryKey(), value);
                answer = new JSONObject(Mobile.scriptSettings(pkg[0], pkg[1], script.pubkey, values.toString(), "ru"));
                if (!answer.optBoolean("ok")) message = scriptSettingsProblem(answer);
            } catch (Exception e) {
                message = "Не удалось открыть настройки: " + e.getMessage();
            }
            String problem = message;
            JSONObject page = answer;
            app.runOnUiThread(() -> {
                if (problem != null) {
                    Toast.makeText(app, problem, Toast.LENGTH_LONG).show();
                    return;
                }
                pendingSettingsKeys = new java.util.HashSet<>();
                org.json.JSONArray params = page.optJSONArray("params");
                for (int i = 0; params != null && i < params.length(); i++) {
                    pendingSettingsKeys.add(params.optJSONObject(i).optString("key"));
                }
                pendingSettingsPrimary = script.primaryKey();
                pendingSettingsSave = onSave;
                app.startActivityForResult(new Intent(app, CaptchaActivity.Settings.class)
                        .putExtra(CaptchaActivity.EXTRA_SETTINGS_HTML, page.optString("html"))
                        .putExtra(CaptchaActivity.EXTRA_TITLE, script.name + ": настройки"), MainActivity.SCRIPT_SETTINGS_REQUEST);
            });
        }, "script-settings").start();
    }

    // What the settings page submitted: only what the script declares (a
    // custom page may send more), the profile field's key split back out.
    void onScriptSettings(String json) {
        java.util.function.BiConsumer<String, JSONObject> save = pendingSettingsSave;
        pendingSettingsSave = null;
        if (json == null || save == null) return;
        JSONObject values;
        try {
            values = ScriptStore.flatten(json);
        } catch (IllegalArgumentException e) {
            Toast.makeText(app, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        String primary = null;
        JSONObject rest = new JSONObject();
        for (java.util.Iterator<String> keys = values.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!pendingSettingsKeys.isEmpty() && !pendingSettingsKeys.contains(key)) continue;
            if (key.equals(pendingSettingsPrimary)) primary = values.optString(key);
            else {
                try {
                    rest.put(key, values.optString(key));
                } catch (org.json.JSONException ignored) {
                    // A non-empty string key always fits.
                }
            }
        }
        save.accept(primary, rest);
        Toast.makeText(app, "Настройки перенесены: сохраните профиль", Toast.LENGTH_LONG).show();
    }

    // Why the core could not open a script's settings (upstream's ScriptSettingsMessages).
    private static String scriptSettingsProblem(JSONObject page) {
        switch (page.optString("code")) {
            case "no_key": return "У скрипта нет закреплённого ключа автора: переустановите его";
            case "bad_signature": return "Файл скрипта не совпадает с подписью автора. Переустановите транспорт";
            case "no_settings": return "У этого транспорта нет настроек";
            case "needs_newer_app": return "Транспорту нужна более новая версия приложения";
            default: return "Не удалось открыть настройки: " + page.optString("error", "неизвестная ошибка");
        }
    }

    private void applySessionVisibility() {
        if (sessionFieldsContainer != null) {
            sessionFieldsContainer.setVisibility(editorSession ? View.VISIBLE : View.GONE);
        }
        if (codecSection != null) codecSection.setVisibility(editorSession || editorStream ? View.GONE : View.VISIBLE);
        if (encryptionSection != null) encryptionSection.setVisibility(editorStream ? View.GONE : View.VISIBLE);
        if (streamHintView != null) streamHintView.setVisibility(editorStream ? View.VISIBLE : View.GONE);
        ThemedActivity.setFloatingLabel(urlInput, editorValueLabel());
        // Session cannot run without the key; classic mode can.
        ThemedActivity.setFloatingLabel(encryptionInput, editorSession
                ? "Ключ сквозного шифрования" : "Ключ сквозного шифрования (необязательно)");
        if (encryptionHintView != null) {
            encryptionHintView.setText(editorSession
                    ? "Обязателен для Session: одинаковый секрет (минимум 16 символов) на телефоне и ноде."
                    : "Необязательно: оставьте пустым, чтобы подключаться без сквозного шифрования "
                            + "(например, к обычному exit-node апстрима). Если заполняете - нужен "
                            + "одинаковый секрет (минимум 16 символов) на телефоне и VDS.");
        }
    }

    private View buildSessionFields() {
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView sessionLabel = app.label("SESSION");
        LinearLayout.LayoutParams sessionLabelParams = app.matchWrap();
        sessionLabelParams.topMargin = app.dp(18);
        sessionLabelParams.bottomMargin = app.dp(6);
        box.addView(sessionLabel, sessionLabelParams);
        TextView hintView = app.text(
                "Все транспорты работают одновременно, трафик идёт по самому приоритетному "
                        + "из работающих, при его отказе - по следующему. Нода должна быть запущена "
                        + "с --negotiate и теми же транспортами (--transports), в --url - ссылка "
                        + "самого приоритетного транспорта с документом. Ключ шифрования обязателен.",
                11, app.secondary, false);
        LinearLayout.LayoutParams hintParams = app.matchWrap();
        hintParams.leftMargin = app.dp(4);
        hintParams.rightMargin = app.dp(4);
        box.addView(hintView, hintParams);

        priorityInput = app.settingInput("Приоритет основного транспорта (больше - важнее)",
                Integer.toString(editorPriority), InputType.TYPE_CLASS_NUMBER);
        app.boxedInput(box, priorityInput, app.dp(16));

        TextView extrasLabel = app.label("ДОПОЛНИТЕЛЬНЫЕ ТРАНСПОРТЫ");
        LinearLayout.LayoutParams extrasLabelParams = app.matchWrap();
        extrasLabelParams.topMargin = app.dp(18);
        box.addView(extrasLabel, extrasLabelParams);

        extrasList = new LinearLayout(app);
        extrasList.setOrientation(LinearLayout.VERTICAL);
        box.addView(extrasList, app.matchWrap());
        refreshExtras();

        Button add = new Button(app);
        add.setText("Добавить транспорт");
        add.setAllCaps(false);
        add.setTextColor(app.accent);
        add.setTextSize(13);
        add.setStateListAnimator(null);
        add.setBackground(app.ripple(Color.TRANSPARENT, 9));
        add.setOnClickListener(v -> {
            app.tap(v);
            editorExtras.add(new Profile.Transport());
            refreshExtras();
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, app.dp(44));
        addParams.topMargin = app.dp(4);
        box.addView(add, addParams);
        return box;
    }

    private void refreshExtras() {
        if (extrasList == null) return;
        extrasList.removeAllViews();
        for (Profile.Transport t : editorExtras) {
            LinearLayout.LayoutParams rowParams = app.matchWrap();
            rowParams.topMargin = app.dp(8);
            extrasList.addView(buildExtraTransportRow(t), rowParams);
        }
    }

    private View buildExtraTransportRow(Profile.Transport t) {
        LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(app.rounded(app.surface, app.border, 1, 20));
        card.setPadding(app.dp(14), app.dp(4), app.dp(14), app.dp(14));

        LinearLayout header = new LinearLayout(app);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(app.text(app.carrierLabel(t.type, t.scriptId), 15, app.text, true), new LinearLayout.LayoutParams(0, -2, 1f));
        Button remove = new Button(app);
        remove.setText("Удалить");
        remove.setAllCaps(false);
        remove.setTextColor(app.darkMode ? Color.rgb(242, 139, 130) : Color.rgb(217, 48, 37));
        remove.setTextSize(13);
        remove.setStateListAnimator(null);
        remove.setBackground(app.ripple(Color.TRANSPARENT, 9));
        remove.setOnClickListener(v -> {
            app.tap(v);
            editorExtras.remove(t);
            refreshExtras();
        });
        header.addView(remove, new LinearLayout.LayoutParams(-2, app.dp(44)));
        card.addView(header, app.matchWrap());

        List<ScriptStore.Script> scripts = new ScriptStore(app).usable();
        boolean js = !scripts.isEmpty() || "script".equals(t.type);
        String[] types = js ? new String[]{"direct", "yandex", "vyandex", "boards", "telemost", "mailru", "cupsonline", "avito", "oneme", "script"}
                : new String[]{"direct", "yandex", "vyandex", "boards", "telemost", "mailru", "cupsonline", "avito", "oneme"};
        String[] typeLabels = js ? new String[]{"Direct", "Yandex", "Volga", "Board", "Телемост", "Mail.ru", "Cups", "Avito", "MAX", "JS"}
                : new String[]{"Direct", "Yandex", "Volga", "Board", "Телемост", "Mail.ru", "Cups", "Avito", "MAX"};
        card.addView(app.chips(typeLabels, java.util.Arrays.asList(types).indexOf(t.type), i -> {
            t.type = types[i];
            t.value = "";
            t.uid = "";
            t.scriptId = "";
            t.settings = new JSONObject();
            refreshExtras();
        }), app.matchWrap());

        if ("script".equals(t.type)) {
            String[] names = new String[scripts.size()];
            for (int i = 0; i < names.length; i++) names[i] = scripts.get(i).name;
            int picked = -1;
            for (int i = 0; i < names.length; i++) if (scripts.get(i).id.equals(t.scriptId)) picked = i;
            if (names.length == 0) {
                card.addView(app.text("Нет включённых JS-транспортов: «Настройки» → «JS-транспорты».", 12, app.secondary, false));
            } else {
                card.addView(app.chips(names, picked, i -> {
                    t.scriptId = scripts.get(i).id;
                    t.settings = new JSONObject();
                    refreshExtras();
                }), app.matchWrap());
            }
        }

        boolean max = "oneme".equals(t.type);
        boolean direct = "direct".equals(t.type);
        boolean script = "script".equals(t.type);
        EditText value = app.settingInput(script ? scriptValueLabel(t.scriptId) : transportValueLabel(t.type), t.value,
                max ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ThemedActivity.bindText(value, v -> t.value = v);
        app.boxedInput(card, value, app.dp(12));
        ScriptStore.Script installed = script ? new ScriptStore(app).byId(t.scriptId) : null;
        if (installed != null && installed.hasSettings()) {
            Button settings = new Button(app);
            settings.setText("Настройки транспорта");
            settings.setAllCaps(false);
            settings.setTextColor(app.accent);
            settings.setTextSize(13);
            settings.setStateListAnimator(null);
            settings.setBackground(app.ripple(Color.TRANSPARENT, 9));
            settings.setOnClickListener(v -> {
                app.tap(v);
                openScriptSettings(t.scriptId, t.value, t.settings, (newValue, rest) -> {
                    if (newValue != null) t.value = newValue;
                    t.settings = rest;
                    refreshExtras();
                });
            });
            card.addView(settings, new LinearLayout.LayoutParams(-1, app.dp(44)));
        }
        if (max) {
            EditText uid = app.settingInput("MAX call user id", t.uid, InputType.TYPE_CLASS_NUMBER);
            ThemedActivity.bindText(uid, v -> t.uid = v);
            app.boxedInput(card, uid, app.dp(12));
        }
        EditText priority = app.settingInput("Приоритет (больше - важнее)", Integer.toString(t.priority),
                InputType.TYPE_CLASS_NUMBER);
        ThemedActivity.bindText(priority, v -> t.priority = MainActivity.parsePriority(v, t.priority));
        app.boxedInput(card, priority, app.dp(12));
        return card;
    }

    // Returns why an extra Session transport can't be saved, or null.
    private String extraTransportProblem(Profile.Transport t) {
        if ("script".equals(t.type) && t.scriptId.isEmpty()) return "Выберите JS-транспорт";
        return transportValueProblem(t.type, t.value, true);
    }

    // Returns why value can't serve as the transport's main field (document
    // link, board link, room code, node address, token), or null. Cups.online
    // takes the base64 room code the exit prints, or a link carrying it; the
    // exit itself creates the rooms and needs nothing, so an empty code is
    // allowed when saving (allowEmptyCups) and checked again on connect.
    String transportValueProblem(String type, String value, boolean allowEmptyCups) {
        String label = app.transportLabel(type);
        switch (type) {
            case "direct": {
                int colon = value.lastIndexOf(':');
                int port = colon > 0 ? MainActivity.parsePriority(value.substring(colon + 1), -1) : -1;
                return colon <= 0 || port < 1 || port > 65535 ? label + ": укажите адрес ноды в виде host:port" : null;
            }
            case "oneme":
                return value.isEmpty() ? "MAX: укажите Web token" : null;
            case "script":
                return null; // the script validates its own params in the engine
            case "cupsonline":
            case "avito":
                // Both take the room list a node prints (either format is base64url).
                if (value.isEmpty()) return allowEmptyCups ? null : label + ": укажите код комнат с ноды";
                return app.isValidDocumentUrl(value) || CUPS_ROOMS_CODE.matcher(value).matches()
                        ? null : label + ": код комнат - строка base64 из лога ноды";
            case "boards":
                return app.isValidDocumentUrl(value) ? null : label + ": укажите HTTPS-ссылку на доску";
            case "telemost":
                return app.isValidDocumentUrl(value) ? null : label + ": укажите HTTPS-ссылку на конференцию";
            default:
                return app.isValidDocumentUrl(value) ? null : label + ": укажите HTTPS-ссылку на документ";
        }
    }

    // The main field's label for a transport type.
    private static String transportValueLabel(String type) {
        switch (type) {
            case "direct": return "Адрес ноды (host:port)";
            case "oneme": return "MAX Web token";
            case "boards": return "Ссылка на доску Yandex Board";
            case "telemost": return "Ссылка на конференцию Телемоста";
            case "mailru": return "Ссылка на документ Mail.ru";
            case "cupsonline": return "Код комнат Cups.online (base64)";
            case "avito": return "Код комнат Avito (base64)";
            case "vyandex": return "Ссылка на документ Yandex (Volga)";
            default: return "Ссылка на документ Yandex";
        }
    }

    private View buildTransportTypeSelector() {
        String[] builtIn = {"yandex", "vyandex", "boards", "telemost", "mailru", "cupsonline", "avito", "oneme"};
        String[][] builtInLabels = {
                {"Yandex Docs", "Документ Яндекса"},
                {"Yandex Docs (Volga)", "Экспериментальный"},
                {"Yandex Board", "Доска Яндекса, экспериментальный"},
                {"Yandex Telemost", "Конференция Телемоста, несколько потоков, экспериментальный"},
                {"Mail.ru Docs", "Документ в Облаке Mail.ru"},
                {"Cups.online", "Комнаты live-coding, код комнат с ноды"},
                {"Avito Code", "Комнаты редактора кода Авито, код комнат с ноды, экспериментальный"},
                {"MAX (OneMe)", "Звонок MAX, нужен Web token"},
        };
        int[] builtInIcons = {R.drawable.ic_yandex, R.drawable.ic_yandex, R.drawable.ic_yandex, R.drawable.ic_yandex,
                R.drawable.ic_mailru, R.drawable.ic_cupsonline, R.drawable.ic_avito, R.drawable.ic_max};
        List<ScriptStore.Script> scripts = new ScriptStore(app).usable();

        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);
        FrameLayout list = new FrameLayout(app);
        // Shows one tab's list, its row checked only when it holds the
        // selection; the other tab is rebuilt when switched to.
        java.util.function.IntConsumer show = tab -> {
            list.removeAllViews();
            if (tab == 0) {
                int current = java.util.Arrays.asList(builtIn).indexOf(editorTransportType);
                list.addView(app.choiceList(builtInLabels, builtInIcons, current, i -> pickTransport(builtIn[i], "")));
                return;
            }
            String[][] labels = new String[scripts.size()][];
            int[] icons = new int[scripts.size()];
            int current = -1;
            for (int i = 0; i < labels.length; i++) {
                ScriptStore.Script script = scripts.get(i);
                labels[i] = new String[]{script.name, (script.official ? "Подписан OpenFlux" : "Сторонний автор")
                        + " · версия " + script.version};
                icons[i] = R.drawable.ic_code;
                if ("script".equals(editorTransportType) && script.id.equals(editorScriptId)) current = i;
            }
            LinearLayout js = new LinearLayout(app);
            js.setOrientation(LinearLayout.VERTICAL);
            js.addView(app.choiceList(labels, icons, current, i -> pickTransport("script", scripts.get(i).id)));
            TextView hint = app.text("Работают только в режиме Session. Установить свои: «Настройки» → «JS-транспорты».",
                    11, app.secondary, false);
            LinearLayout.LayoutParams hintParams = app.matchWrap();
            hintParams.topMargin = app.dp(6);
            hintParams.leftMargin = app.dp(4);
            js.addView(hint, hintParams);
            list.addView(js);
        };
        boolean scriptTab = "script".equals(editorTransportType);
        // The tabs only once there is a JS transport to offer.
        if (!scripts.isEmpty()) {
            LinearLayout.LayoutParams tabsParams = app.matchWrap();
            tabsParams.bottomMargin = app.dp(10);
            box.addView(app.segmented(new String[]{"Встроенные", "JavaScript"}, scriptTab ? 1 : 0, show), tabsParams);
        }
        show.accept(scriptTab && !scripts.isEmpty() ? 1 : 0);
        box.addView(list, app.matchWrap());
        return box;
    }

    private void pickTransport(String type, String scriptId) {
        if (!scriptId.equals(editorScriptId)) editorSettings = new JSONObject();
        editorScriptId = scriptId;
        editorTransportType = type;
        updateScriptSettingsButton();
        if (maxFieldsContainer != null) {
            maxFieldsContainer.setVisibility("oneme".equals(editorTransportType) ? View.VISIBLE : View.GONE);
        }
        ThemedActivity.setFloatingLabel(urlInput, editorValueLabel());
        if (urlField != null) urlField.setVisibility("oneme".equals(editorTransportType) ? View.GONE : View.VISIBLE);
    }

    // MAX (OneMe) authenticates via a web token + numeric user id instead of
    // a document URL - only shown/required when that transport is selected.
    private View buildMaxFields(Profile existing) {
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);

        FrameLayout tokenField = new FrameLayout(app);
        tokenField.setBackground(app.rounded(app.surface, app.border, 1, 10));
        maxTokenInput = app.settingInput("MAX Web token", existing != null ? existing.maxToken : "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        maxTokenInput.setPadding(app.dp(16), 0, app.dp(16), 0);
        tokenField.addView(maxTokenInput, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams tokenParams = new LinearLayout.LayoutParams(-1, app.dp(ThemedActivity.FIELD_HEIGHT));
        box.addView(app.floating(tokenField, maxTokenInput), tokenParams);

        FrameLayout uidField = new FrameLayout(app);
        uidField.setBackground(app.rounded(app.surface, app.border, 1, 10));
        maxUidInput = app.settingInput("MAX call user id", existing != null ? existing.maxUid : "",
                InputType.TYPE_CLASS_NUMBER);
        maxUidInput.setPadding(app.dp(16), 0, app.dp(16), 0);
        uidField.addView(maxUidInput, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams uidParams = new LinearLayout.LayoutParams(-1, app.dp(ThemedActivity.FIELD_HEIGHT));
        uidParams.topMargin = app.dp(8);
        box.addView(app.floating(uidField, maxUidInput), uidParams);

        return box;
    }

    private View buildCodecSelector() {
        return app.choiceList(new String[][]{
                {"Batched + zstd", "По умолчанию"},
                {"Legacy (LZ4)", "Для совместимости со старым exit-node"},
        }, new int[]{R.drawable.ic_codec_fast, R.drawable.ic_codec_legacy}, "legacy".equals(editorCodec) ? 1 : 0, i -> editorCodec = i == 1 ? "legacy" : "batched");
    }

    private View buildUrlField(String initialValue) {
        FrameLayout field = new FrameLayout(app);
        field.setBackground(app.rounded(app.surface, app.border, 1, 10));
        urlInput = app.settingInput(editorValueLabel(), initialValue,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setTransformationMethod(urlVisible ? null : PasswordTransformationMethod.getInstance());
        urlInput.setPadding(app.dp(16), 0, app.dp(56), 0);
        field.addView(urlInput, new FrameLayout.LayoutParams(-1, -1));
        visibilityButton = app.iconButton(urlVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility,
                urlVisible ? "Скрыть ссылку" : "Показать ссылку");
        visibilityButton.setOnClickListener(v -> {
            app.tap(v);
            toggleUrlVisibility();
        });
        FrameLayout.LayoutParams eye = new FrameLayout.LayoutParams(app.dp(48), app.dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        eye.rightMargin = app.dp(4);
        field.addView(visibilityButton, eye);
        return app.floating(field, urlInput);
    }

    private View buildEncryptionField(String initialValue) {
        FrameLayout field = new FrameLayout(app);
        field.setBackground(app.rounded(app.surface, app.border, 1, 10));
        encryptionInput = app.settingInput("Ключ сквозного шифрования (необязательно)", initialValue,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        encryptionInput.setTransformationMethod(encryptionVisible ? null : PasswordTransformationMethod.getInstance());
        encryptionInput.setPadding(app.dp(16), 0, app.dp(56), 0);
        field.addView(encryptionInput, new FrameLayout.LayoutParams(-1, -1));
        encryptionVisibilityButton = app.iconButton(
                encryptionVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility,
                encryptionVisible ? "Скрыть ключ" : "Показать ключ");
        encryptionVisibilityButton.setOnClickListener(v -> {
            app.tap(v);
            toggleEncryptionVisibility();
        });
        FrameLayout.LayoutParams eye = new FrameLayout.LayoutParams(
                app.dp(48), app.dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        eye.rightMargin = app.dp(4);
        field.addView(encryptionVisibilityButton, eye);
        return app.floating(field, encryptionInput);
    }

    private void toggleUrlVisibility() {
        int position = urlInput.getSelectionStart();
        urlVisible = !urlVisible;
        urlInput.setTransformationMethod(urlVisible ? null : PasswordTransformationMethod.getInstance());
        urlInput.setTypeface(Typeface.DEFAULT);
        visibilityButton.setImageResource(urlVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
        visibilityButton.setContentDescription(urlVisible ? "Скрыть ссылку" : "Показать ссылку");
        urlInput.setSelection(Math.max(0, Math.min(position, urlInput.length())));
    }

    private void toggleEncryptionVisibility() {
        int position = encryptionInput.getSelectionStart();
        encryptionVisible = !encryptionVisible;
        encryptionInput.setTransformationMethod(
                encryptionVisible ? null : PasswordTransformationMethod.getInstance());
        encryptionInput.setTypeface(Typeface.DEFAULT);
        encryptionVisibilityButton.setImageResource(
                encryptionVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
        encryptionVisibilityButton.setContentDescription(
                encryptionVisible ? "Скрыть ключ" : "Показать ключ");
        encryptionInput.setSelection(Math.max(0, Math.min(position, encryptionInput.length())));
    }

    private void generateEncryptionSecret() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String generated = Base64.encodeToString(
                random, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        if (encryptionInput != null) {
            encryptionInput.setText(generated);
            encryptionInput.setSelection(encryptionInput.length());
        }
        Toast.makeText(app, "Создан ключ на 256 бит. Передайте его на VDS.", Toast.LENGTH_LONG).show();
    }
}
