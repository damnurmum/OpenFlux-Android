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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private LinearLayout encryptionStatus;
    private View addExtraButton;
    private View encryptionSection;
    private View streamHintView;
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
        int mainPriority = editorPriority;
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
        nameField.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
        profileNameInput = app.settingInput("Название профиля", existing != null ? existing.name : "",
                InputType.TYPE_CLASS_TEXT);
        profileNameInput.setPadding(app.dp(16), 0, app.dp(16), 0);
        nameField.addView(profileNameInput, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, app.dp(ThemedActivity.FIELD_HEIGHT));
        section.addView(app.floating(nameField, profileNameInput), nameParams);

        section.addView(app.sectionTitle(section, "РЕЖИМ"));
        section.addView(buildSessionModeSelector(), app.tileParams());

        section.addView(app.sectionTitle(section, "ТРАНСПОРТ"));
        section.addView(buildTransportTypeSelector(), app.tileParams());

        // What the picked transport connects with: the document link, or
        // MAX's token and user id, and a JS transport's own settings.
        section.addView(app.sectionTitle(section, "НАСТРОЙКА ТРАНСПОРТА"));
        maxFieldsContainer = buildMaxFields(existing);
        maxFieldsContainer.setVisibility("oneme".equals(editorTransportType) ? View.VISIBLE : View.GONE);
        section.addView(maxFieldsContainer, app.matchWrap());

        LinearLayout.LayoutParams urlParams = app.fieldParams();
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

        TextView streamHint = app.fieldHint("Выход - PHP-нода на обычном хостинге (deploy/phpbox), связь с ней через "
                + "одну комнату Cups.online или документ Mail.ru. Ключа и Session нет: содержимое защищает "
                + "TLS самих приложений. Через PHP-ноду идёт только TCP на портах 80 и 443 (сайты и большинство "
                + "приложений); в режиме туннеля остальной UDP (звонки, игры, QUIC) и IPv6 отбрасываются.");
        streamHintView = streamHint;
        section.addView(streamHint);

        // Sections that come and go with the mode carry their title inside,
        // so it hides with them.
        // Session always uses the batched codec, so the choice only exists in
        // classic mode.
        LinearLayout codecBox = new LinearLayout(app);
        codecBox.setOrientation(LinearLayout.VERTICAL);
        codecBox.addView(app.sectionTitle(codecBox, "КОДЕК"));
        codecBox.addView(buildCodecSelector(), app.tileParams());
        codecSection = codecBox;
        section.addView(codecBox, sectionBoxParams());

        // The key, its hint and the generator; the stream mode has no key.
        LinearLayout encryptionBox = new LinearLayout(app);
        encryptionBox.setOrientation(LinearLayout.VERTICAL);
        encryptionSection = encryptionBox;
        section.addView(encryptionBox, sectionBoxParams());
        encryptionBox.addView(app.sectionTitle(encryptionBox, "ШИФРОВАНИЕ"));
        encryptionBox.addView(buildEncryptionField(initialSecret), app.fieldParams());
        // Whether the key will do, as it is typed; see updateKeyStatus.
        encryptionStatus = app.callout();
        encryptionBox.addView(encryptionStatus, app.tileParams());
        ThemedActivity.bindText(encryptionInput, value -> updateKeyStatus());
        LinearLayout.LayoutParams generateParams = new LinearLayout.LayoutParams(-1, app.dp(48));
        generateParams.topMargin = app.dp(12);
        encryptionBox.addView(app.tonalButton(R.drawable.ic_key, "Сгенерировать ключ",
                this::generateEncryptionSecret), generateParams);

        sessionFieldsContainer = buildSessionFields();
        section.addView(sessionFieldsContainer, sectionBoxParams());
        applySessionVisibility();

        if (existing != null) {
            Profile toDelete = existing;
            // Apart from the settings above: it removes the whole profile.
            section.addView(app.pageDivider());
            section.addView(app.dangerButton(R.drawable.ic_delete, "Удалить профиль",
                    () -> app.deleteProfile(toDelete)), app.buttonParams());
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
        app.setViewVisibleAnimated(scriptSettingsButton, script != null && script.hasSettings());
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

    // Shows the sections the mode uses; sliding them, like other pages do,
    // once the editor is on screen (while it is being built, nothing moves).
    private void applySessionVisibility() {
        if (sessionFieldsContainer != null) app.setViewVisibleAnimated(sessionFieldsContainer, editorSession);
        if (codecSection != null) app.setViewVisibleAnimated(codecSection, !editorSession && !editorStream);
        if (encryptionSection != null) app.setViewVisibleAnimated(encryptionSection, !editorStream);
        if (streamHintView != null) app.setViewVisibleAnimated(streamHintView, editorStream);
        ThemedActivity.setFloatingLabel(urlInput, editorValueLabel());
        updateKeyStatus();
    }

    // The line under the key: Session cannot run without one, classic can
    // but then nothing is encrypted end to end (old upstream nodes only).
    private void updateKeyStatus() {
        if (encryptionStatus == null || encryptionInput == null) return;
        int length = encryptionInput.getText().toString().trim().length();
        if (length >= 16) {
            app.setCallout(encryptionStatus, true, "Ключ подходит. На ноде должен быть такой же.");
        } else if (length > 0) {
            app.setCallout(encryptionStatus, false, "Нужно не меньше 16 символов, ещё " + (16 - length) + ".");
        } else if (editorSession) {
            app.setCallout(encryptionStatus, false,
                    "Обязателен в режиме Session: тот же ключ, что на ноде, не меньше 16 символов.");
        } else {
            app.setCallout(encryptionStatus, false,
                    "Без ключа трафик не шифруется: так можно подключаться только к старым нодам без ключа.");
        }
    }

    // A block that shows and hides with the mode: a section gap above it.
    private LinearLayout.LayoutParams sectionBoxParams() {
        LinearLayout.LayoutParams params = app.matchWrap();
        params.topMargin = app.dp(ThemedActivity.SECTION_GAP);
        return params;
    }

    private View buildSessionFields() {
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(app.sectionTitle(box, "SESSION"));
        box.addView(app.fieldHint(
                "Все транспорты работают сразу: трафик идёт через самый верхний из работающих, "
                        + "при его отказе - через следующий. На ноде должны быть те же транспорты."));

        box.addView(app.sectionTitle(box, "ТРАНСПОРТЫ"));
        extrasList = new LinearLayout(app);
        extrasList.setOrientation(LinearLayout.VERTICAL);
        box.addView(extrasList, app.matchWrap());

        addExtraButton = app.tonalButton(R.drawable.ic_add, "Добавить транспорт", () -> {
            List<Object> order = orderedTransports();
            Profile.Transport added = new Profile.Transport();
            editorExtras.add(added);
            // A new one goes to the bottom: the least important.
            order.add(added);
            applyOrder(order);
            refreshExtras();
        });
        box.addView(addExtraButton, app.tileParams());
        ((LinearLayout.LayoutParams) addExtraButton.getLayoutParams()).height = app.dp(48);
        refreshExtras();
        return box;
    }

    // The main transport's place in the Session list.
    private static final Object MAIN = new Object();

    // The main transport and the extras, the most important first: the order
    // is the priority. Ties keep the main one first, then the list's order.
    private List<Object> orderedTransports() {
        List<Object> order = new java.util.ArrayList<>();
        order.add(MAIN);
        order.addAll(editorExtras);
        java.util.Collections.sort(order, (a, b) -> Integer.compare(priorityOf(b), priorityOf(a)));
        return order;
    }

    private int priorityOf(Object item) {
        return item == MAIN ? editorPriority : ((Profile.Transport) item).priority;
    }

    // Priorities from the order: 10 apart, the top one highest.
    private void applyOrder(List<Object> order) {
        for (int i = 0; i < order.size(); i++) {
            int priority = (order.size() - i) * 10;
            if (order.get(i) == MAIN) editorPriority = priority;
            else ((Profile.Transport) order.get(i)).priority = priority;
        }
    }

    // The Session list: nothing until there is an extra transport, then a
    // card for the main one and one for each extra, in priority order.
    private void refreshExtras() {
        if (extrasList == null) return;
        extrasList.removeAllViews();
        if (editorExtras.isEmpty()) return;
        List<Object> order = orderedTransports();
        Map<Object, View> cards = new HashMap<>();
        Map<Object, View[]> arrows = new HashMap<>();
        for (Object item : order) {
            View[] upDown = new View[2];
            cards.put(item, item == MAIN ? buildMainTransportCard(upDown, order, cards, arrows)
                    : buildExtraTransportRow((Profile.Transport) item, upDown, order, cards, arrows));
            arrows.put(item, upDown);
        }
        layoutCards(order, cards, arrows);
    }

    // Puts the cards in order (the same views, so a move slides them) and
    // dims the arrow that has nowhere to go.
    private void layoutCards(List<Object> order, Map<Object, View> cards, Map<Object, View[]> arrows) {
        extrasList.removeAllViews();
        for (int i = 0; i < order.size(); i++) {
            Object item = order.get(i);
            // TILE_GAP above each, the first one too (under the section title).
            extrasList.addView(cards.get(item), app.tileParams());
            View[] upDown = arrows.get(item);
            upDown[0].setEnabled(i > 0);
            upDown[0].setAlpha(i > 0 ? 1f : 0.3f);
            upDown[1].setEnabled(i < order.size() - 1);
            upDown[1].setAlpha(i < order.size() - 1 ? 1f : 0.3f);
        }
    }

    // ↑ and ↓ for a card; they swap it with its neighbour and renumber.
    private void addMoveArrows(LinearLayout row, Object item, View[] upDown, List<Object> order,
            Map<Object, View> cards, Map<Object, View[]> arrows) {
        for (int k = 0; k < 2; k++) {
            int step = k == 0 ? -1 : 1;
            ImageButton arrow = app.iconButton(R.drawable.ic_chevron_right, k == 0 ? "Выше" : "Ниже");
            arrow.setRotation(k == 0 ? -90 : 90);
            arrow.setOnClickListener(v -> {
                int from = order.indexOf(item), to = from + step;
                if (to < 0 || to >= order.size()) return;
                app.tap(v);
                java.util.Collections.swap(order, from, to);
                applyOrder(order);
                android.transition.TransitionManager.beginDelayedTransition(extrasList,
                        new android.transition.ChangeBounds().setDuration(260).setInterpolator(ThemedActivity.EMPHASIZED));
                layoutCards(order, cards, arrows);
            });
            row.addView(arrow, new LinearLayout.LayoutParams(app.dp(40), app.dp(40)));
            upDown[k] = arrow;
        }
    }

    // A transport card: a rounded box, its header row first.
    private LinearLayout transportCard() {
        LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.VERTICAL);
        // The icon sits as far from the top edge as from the left one.
        card.setPadding(app.dp(12), app.dp(12), app.dp(12), app.dp(12));
        card.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
        return card;
    }

    private LinearLayout cardHeader(int icon, String title, String detail) {
        LinearLayout row = new LinearLayout(app);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(app.iconBubble(icon), new LinearLayout.LayoutParams(app.dp(40), app.dp(40)));
        LinearLayout copy = new LinearLayout(app);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = app.text(title, 15, app.text, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(name);
        TextView sub = app.text(detail, 12, app.secondary, false);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(sub);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = app.dp(12);
        row.addView(copy, copyParams);
        return row;
    }

    // The main transport in the list: its link is set above, so here only
    // its place.
    private View buildMainTransportCard(View[] upDown, List<Object> order, Map<Object, View> cards,
            Map<Object, View[]> arrows) {
        LinearLayout card = transportCard();
        Profile.Transport main = new Profile.Transport();
        main.type = editorTransportType;
        main.scriptId = editorScriptId;
        LinearLayout header = cardHeader(app.profileTransportIcon(singleTransport(editorTransportType)),
                shortTransportName(main), "Основной транспорт");
        addMoveArrows(header, MAIN, upDown, order, cards, arrows);
        card.addView(header, app.matchWrap());
        return card;
    }

    // One extra transport's card: its transport (tap to pick another), the
    // arrows and the bin, then the field it connects with (and MAX's user
    // id, a JS transport's settings).
    private View buildExtraTransportRow(Profile.Transport t, View[] upDown, List<Object> order,
            Map<Object, View> cards, Map<Object, View[]> arrows) {
        LinearLayout card = transportCard();
        List<String[]> options = new java.util.ArrayList<>();
        List<Integer> icons = new java.util.ArrayList<>();
        List<String[]> keys = new java.util.ArrayList<>();
        extraTransportOptions(options, icons, keys);
        int current = -1;
        for (int i = 0; i < keys.size(); i++) {
            String[] key = keys.get(i);
            if (key[0].equals(t.type) && (!"script".equals(t.type) || key[1].equals(t.scriptId))) current = i;
        }

        LinearLayout header = cardHeader(current >= 0 ? icons.get(current) : R.drawable.ic_public,
                shortTransportName(t), "Нажмите, чтобы сменить");
        header.setBackground(app.ripple(Color.TRANSPARENT, 12));
        header.setClickable(true);
        header.setContentDescription("Транспорт: " + shortTransportName(t) + ". Нажмите, чтобы выбрать другой");
        int picked = current;
        header.setOnClickListener(v -> {
            app.tap(v);
            pickExtraTransport(t, options, icons, keys, picked);
        });
        addMoveArrows(header, t, upDown, order, cards, arrows);
        card.addView(header, app.matchWrap());

        LinearLayout fields = new LinearLayout(app);
        fields.setOrientation(LinearLayout.VERTICAL);
        card.addView(fields, app.matchWrap());
        boolean max = "oneme".equals(t.type);
        boolean script = "script".equals(t.type);
        EditText value = app.settingInput(script ? scriptValueLabel(t.scriptId) : transportValueLabel(t.type), t.value,
                max ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ThemedActivity.bindText(value, v -> t.value = v);
        app.boxedInput(fields, value, app.dp(ThemedActivity.TILE_GAP));
        if (max) {
            EditText uid = app.settingInput("MAX call user id", t.uid, InputType.TYPE_CLASS_NUMBER);
            ThemedActivity.bindText(uid, v -> t.uid = v);
            app.boxedInput(fields, uid, app.dp(ThemedActivity.TILE_GAP));
        }
        ScriptStore.Script installed = script ? new ScriptStore(app).byId(t.scriptId) : null;
        if (installed != null && installed.hasSettings()) {
            fields.addView(app.tonalButton(R.drawable.ic_settings, "Настройки транспорта", () ->
                    openScriptSettings(t.scriptId, t.value, t.settings, (newValue, rest) -> {
                        if (newValue != null) t.value = newValue;
                        t.settings = rest;
                        refreshExtras();
                    })), app.buttonParams());
        }
        // Last in the card, so a tap on it is not a slip near the arrows.
        fields.addView(app.dangerButton(R.drawable.ic_delete, "Удалить транспорт", () -> {
            editorExtras.remove(t);
            refreshExtras();
        }), app.buttonParams());
        return card;
    }

    // Every transport an extra can be: Direct, the built-in ones, then each
    // enabled JS transport as its own entry. keys hold {type, scriptId}.
    private void extraTransportOptions(List<String[]> options, List<Integer> icons, List<String[]> keys) {
        options.add(new String[]{"Direct (TCP до ноды)", "Прямое подключение к порту ноды"});
        icons.add(R.drawable.ic_link);
        keys.add(new String[]{"direct", ""});
        String[][] builtIn = {
                {"yandex", "Yandex Docs", "Документ Яндекса"},
                {"vyandex", "Yandex Docs (Volga)", "Экспериментальный"},
                {"boards", "Yandex Board", "Доска Яндекса, экспериментальный"},
                {"telemost", "Yandex Telemost", "Конференция Телемоста, экспериментальный"},
                {"mailru", "Mail.ru Docs", "Документ в Облаке Mail.ru"},
                {"cupsonline", "Cups.online", "Комнаты live-coding, код комнат с ноды"},
                {"avito", "Avito Code", "Комнаты редактора кода Авито, экспериментальный"},
                {"oneme", "MAX (OneMe)", "Звонок MAX, нужен Web token"},
        };
        for (String[] b : builtIn) {
            options.add(new String[]{b[1], b[2]});
            icons.add(app.profileTransportIcon(singleTransport(b[0])));
            keys.add(new String[]{b[0], ""});
        }
        for (ScriptStore.Script script : new ScriptStore(app).usable()) {
            options.add(new String[]{script.name, "JS-транспорт · версия " + script.version});
            icons.add(R.drawable.ic_code);
            keys.add(new String[]{"script", script.id});
        }
    }

    // The list of transports in a dialog, the current one checked.
    private void pickExtraTransport(Profile.Transport t, List<String[]> options, List<Integer> icons,
            List<String[]> keys, int current) {
        int[] iconArray = new int[icons.size()];
        for (int i = 0; i < iconArray.length; i++) iconArray[i] = icons.get(i);
        android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        View list = app.choiceList(options.toArray(new String[0][]), iconArray, current, i -> {
            t.type = keys.get(i)[0];
            t.scriptId = keys.get(i)[1];
            t.value = "";
            t.uid = "";
            t.settings = new JSONObject();
            if (dialog[0] != null) dialog[0].dismiss();
            refreshExtras();
        });
        android.widget.ScrollView scroll = new android.widget.ScrollView(app);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(app.dp(16), app.dp(8), app.dp(16), app.dp(8));
        scroll.setClipToPadding(false);
        scroll.addView(list);
        dialog[0] = new FlowStyle(app).dialog()
                .setTitle("Транспорт")
                .setView(scroll)
                .setNegativeButton("Отмена", null)
                .create();
        dialog[0].show();
        dialog[0].getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setAllCaps(false);
    }

    private static String shortTransportName(Profile.Transport t) {
        switch (t.type) {
            case "direct": return "Direct";
            case "yandex": return "Yandex Docs";
            case "vyandex": return "Volga";
            case "boards": return "Yandex Board";
            case "telemost": return "Telemost";
            case "mailru": return "Mail.ru";
            case "cupsonline": return "Cups.online";
            case "avito": return "Avito";
            case "oneme": return "MAX";
            case "script": return t.scriptId.isEmpty() ? "JS" : t.scriptId;
            default: return t.type;
        }
    }

    // A one-transport profile, to ask MainActivity for that transport's icon.
    private static Profile singleTransport(String type) {
        Profile p = new Profile();
        p.transportType = type;
        return p;
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
            js.addView(app.fieldHint("Работают только в режиме Session. Установить свои: «Настройки» → «JS-транспорты»."));
            list.addView(js);
        };
        boolean scriptTab = "script".equals(editorTransportType);
        // The tabs only once there is a JS transport to offer.
        if (!scripts.isEmpty()) {
            LinearLayout.LayoutParams tabsParams = app.matchWrap();
            tabsParams.bottomMargin = app.dp(ThemedActivity.TILE_GAP);
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
        boolean max = "oneme".equals(editorTransportType);
        if (maxFieldsContainer != null) app.setViewVisibleAnimated(maxFieldsContainer, max);
        // The link field fades out for MAX: it keeps its old label meanwhile.
        if (!max) ThemedActivity.setFloatingLabel(urlInput, editorValueLabel());
        if (urlField != null) app.setViewVisibleAnimated(urlField, !max);
        // The main transport's card in the Session list shows its new name.
        refreshExtras();
    }

    // MAX (OneMe) authenticates via a web token + numeric user id instead of
    // a document URL - only shown/required when that transport is selected.
    private View buildMaxFields(Profile existing) {
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);

        FrameLayout tokenField = new FrameLayout(app);
        tokenField.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
        maxTokenInput = app.settingInput("MAX Web token", existing != null ? existing.maxToken : "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        maxTokenInput.setPadding(app.dp(16), 0, app.dp(16), 0);
        tokenField.addView(maxTokenInput, new FrameLayout.LayoutParams(-1, -1));
        box.addView(app.floating(tokenField, maxTokenInput), app.fieldParams());

        FrameLayout uidField = new FrameLayout(app);
        uidField.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
        maxUidInput = app.settingInput("MAX call user id", existing != null ? existing.maxUid : "",
                InputType.TYPE_CLASS_NUMBER);
        maxUidInput.setPadding(app.dp(16), 0, app.dp(16), 0);
        uidField.addView(maxUidInput, new FrameLayout.LayoutParams(-1, -1));
        box.addView(app.floating(uidField, maxUidInput), app.fieldParams());

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
        field.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
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
        field.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
        encryptionInput = app.settingInput("Ключ шифрования", initialValue,
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
        Toast.makeText(app, "Создан ключ на 256 бит. Укажите такой же на ноде.", Toast.LENGTH_LONG).show();
    }
}
