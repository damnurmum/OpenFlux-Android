package io.openflux.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.LinkedHashSet;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import android.util.Base64;

// The Settings tab: its list, and each section's page (mode and proxy,
// network, apps, routing, look, JS transports, about). A page edits a
// draft that "Сохранить настройки" applies to MainActivity's live settings.
final class SettingsPage {
    private final MainActivity app;
    static final int SETTINGS_MODE = 0;
    private static final int SETTINGS_NETWORK = 1;
    static final int SETTINGS_APPS = 2;
    private static final int SETTINGS_INTERFACE = 3;
    private static final int SETTINGS_ABOUT = 4;
    private static final int SETTINGS_ROUTING = 5;
    private static final int SETTINGS_SCRIPTS = 6;
    int settingsSubTab = SETTINGS_MODE;
    boolean settingsDetailOpen;
    EditText dnsInput;
    EditText mtuInput;
    EditText proxyPortInput;
    EditText proxyUsernameInput;
    EditText proxyPasswordInput;
    private ImageButton proxyPasswordVisibilityButton;
    private ImageView proxyShareQr;
    ScriptsPanel scriptsPanel;

    // Draft state for the Network/Mode settings sub-pages: edits only take
    // effect when "Применить" is pressed, not just by navigating away (see
    // applyNetworkSettings/applyModeSettings). Seeded from the live fields
    // each time the corresponding sub-page opens (openSettingsDetail).
    private String editorDnsServer = "";

    private boolean editorDnsAuto = true;
    private int editorMtu;
    private boolean editorKillSwitchEnabled = true;
    private String editorConnectionMode = MainActivity.MODE_TUNNEL;
    private int editorProxyPort = MainActivity.DEFAULT_PROXY_PORT;
    private boolean editorProxyLanAccess;
    private boolean editorProxyAuthEnabled;
    private boolean editorDarkMode;
    private boolean editorAutoScroll;
    String editorAppFilterMode = AppFilter.MODE_OFF;
    final LinkedHashSet<String> editorSelectedApps = new LinkedHashSet<>();
    private boolean proxyPasswordVisible;
    private final LinkedHashSet<String> editorEnabledDomainPresets = new LinkedHashSet<>();
    private EditText customDomainsInput;

    SettingsPage(MainActivity app) {
        this.app = app;
    }

    void openSettingsDetail(int tab) {
        if (settingsDetailOpen && settingsSubTab == tab) return;
        settingsDetailOpen = true;
        settingsSubTab = tab;
        if (tab == SETTINGS_NETWORK) {
            editorDnsServer = app.dnsServer;
            editorDnsAuto = app.dnsServer.isEmpty();
            editorMtu = app.mtu;
            editorKillSwitchEnabled = app.killSwitchEnabled;
        } else if (tab == SETTINGS_MODE) {
            editorConnectionMode = app.connectionMode;
            editorProxyPort = app.proxyPort;
            editorProxyLanAccess = app.proxyLanAccess;
            editorProxyAuthEnabled = app.proxyAuthEnabled;
        } else if (tab == SETTINGS_INTERFACE) {
            editorDarkMode = app.darkMode;
            editorAutoScroll = app.autoScroll;
        } else if (tab == SETTINGS_APPS) {
            editorAppFilterMode = app.appFilterMode;
            editorSelectedApps.clear();
            editorSelectedApps.addAll(app.selectedApps);
        } else if (tab == SETTINGS_ROUTING) {
            editorEnabledDomainPresets.clear();
            editorEnabledDomainPresets.addAll(app.enabledDomainPresets);
        }
        app.showPage(MainActivity.PAGE_SETTINGS);
    }

    void closeSettingsDetail() {
        if (!settingsDetailOpen) return;
        settingsDetailOpen = false;
        app.showPage(MainActivity.PAGE_SETTINGS);
    }

    View buildSettingsPage() {
        LinearLayout page = app.page();
        page.addView(buildSettingsHeader());

        if (!settingsDetailOpen) {
            LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            listParams.topMargin = app.dp(16);
            listParams.bottomMargin = app.dp(10);
            page.addView(buildSettingsList(), listParams);
            return page;
        }

        // The scripts tab acts at once, like the about tab has nothing to save.
        boolean showSave = settingsSubTab != SETTINGS_ABOUT && settingsSubTab != SETTINGS_SCRIPTS;
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        contentParams.topMargin = app.dp(16);
        if (!showSave) contentParams.bottomMargin = app.dp(10);
        View subTab = buildSettingsSubTabContent();
        // The pinned save button already clears the nav pill; the scroll
        // needs no room of its own for it.
        if (showSave && subTab instanceof ScrollView) ThemedActivity.scrollBottom(subTab, app.dp(8));
        page.addView(subTab, contentParams);

        if (showSave) {
            Button save = app.primaryButton("Сохранить настройки", () -> {
                if (settingsSubTab == SETTINGS_NETWORK) {
                    if (!applyNetworkSettings()) return;
                } else if (settingsSubTab == SETTINGS_MODE) applyModeSettings();
                else if (settingsSubTab == SETTINGS_INTERFACE) applyInterfaceSettings();
                else if (settingsSubTab == SETTINGS_APPS) {
                    if (AppFilter.MODE_WHITELIST.equals(editorAppFilterMode) && editorSelectedApps.isEmpty()) {
                        Toast.makeText(app, "Выберите хотя бы одно приложение для белого списка", Toast.LENGTH_LONG).show();
                        return;
                    }
                    applyAppsSettings();
                } else if (settingsSubTab == SETTINGS_ROUTING) applyRoutingSettings();
                Toast.makeText(app, "Настройки сохранены", Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, app.dp(52));
            saveParams.topMargin = app.dp(14);
            // This button sits outside the scrollable section (as a fixed
            // footer), so it needs its own clearance from the nav pill -
            // the scroll section's internal padding doesn't cover it.
            saveParams.bottomMargin = app.navClearance();
            page.addView(save, saveParams);
            app.pinnedSave = save;
            if (app.keyboardOpen) save.setVisibility(View.GONE);
        }
        return page;
    }

    private View buildSettingsHeader() {
        LinearLayout header = new LinearLayout(app);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (settingsDetailOpen) {
            ImageButton back = app.iconButton(R.drawable.ic_arrow_back, "Назад к настройкам");
            back.setOnClickListener(v -> {
                app.bounce(v);
                closeSettingsDetail();
            });
            LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(app.dp(44), app.dp(44));
            backParams.rightMargin = app.dp(6);
            header.addView(back, backParams);
            header.addView(app.text(settingsSectionTitle(settingsSubTab), 22, app.text, true),
                    new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            LinearLayout titles = new LinearLayout(app);
            titles.setOrientation(LinearLayout.VERTICAL);
            titles.addView(app.text("Настройки", 25, app.text, true));
            TextView hint = app.text("Параметры сети применяются при следующем подключении.", 12, app.secondary, false);
            LinearLayout.LayoutParams hintParams = app.matchWrap();
            hintParams.topMargin = app.dp(4);
            titles.addView(hint, hintParams);
            header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return header;
    }

    private String settingsSectionTitle(int tab) {
        switch (tab) {
            case SETTINGS_NETWORK: return "Сеть";
            case SETTINGS_APPS: return "Приложения";
            case SETTINGS_ROUTING: return "Маршрутизация";
            case SETTINGS_INTERFACE: return "Вид";
            case SETTINGS_ABOUT: return "О проекте";
            case SETTINGS_SCRIPTS: return "JS-транспорты";
            case SETTINGS_MODE:
            default: return "Режим работы";
        }
    }

    private View buildSettingsList() {
        ScrollView scroll = new ScrollView(app);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(app);
        list.setOrientation(LinearLayout.VERTICAL);
        // Inside the list, not on the scroll: see scrollBottom.
        list.setPadding(0, 0, 0, app.navClearance());
        list.addView(settingsListRow(R.drawable.ic_swap, "Режим работы",
                MainActivity.modeLabel(app.connectionMode), SETTINGS_MODE));
        list.addView(settingsListRow(R.drawable.ic_public, "Сеть",
                "DNS-сервер и MTU", SETTINGS_NETWORK));
        list.addView(settingsListRow(R.drawable.ic_apps, "Приложения",
                "Какие приложения используют туннель", SETTINGS_APPS));
        list.addView(settingsListRow(R.drawable.ic_routing, "Маршрутизация",
                "Сайты и сервисы в обход туннеля", SETTINGS_ROUTING));
        list.addView(settingsListRow(R.drawable.ic_dark_mode, "Вид",
                "Тема и автопрокрутка логов", SETTINGS_INTERFACE));
        list.addView(settingsListRow(R.drawable.ic_code, "JS-транспорты",
                ScriptStore.experimental(app) ? "Экспериментально: включены" : "Экспериментально: выключены",
                SETTINGS_SCRIPTS));
        list.addView(settingsListRow(R.drawable.ic_info, "О проекте",
                "Репозитории проекта", SETTINGS_ABOUT));
        app.groupTiles(list);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private View settingsListRow(int iconRes, String titleValue, String detailValue, int tab) {
        LinearLayout row = new LinearLayout(app);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(app.dp(14), app.dp(14), app.dp(14), app.dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(app.iconBubble(iconRes), new LinearLayout.LayoutParams(app.dp(40), app.dp(40)));
        LinearLayout copy = new LinearLayout(app);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = app.dp(16);
        copy.addView(app.text(titleValue, 15, app.text, true));
        copy.addView(app.text(detailValue, 12, app.secondary, false));
        row.addView(copy, copyParams);
        row.addView(app.icon(R.drawable.ic_chevron_right, app.hint), new LinearLayout.LayoutParams(app.dp(20), app.dp(20)));
        row.setOnClickListener(v -> {
            app.bounce(v);
            openSettingsDetail(tab);
        });
        return row;
    }

    private View buildSettingsSubTabContent() {
        switch (settingsSubTab) {
            case SETTINGS_NETWORK:
                return app.wrapScroll(buildNetworkSettings());
            case SETTINGS_APPS:
                return app.appPicker.buildAppsSettings();
            case SETTINGS_ROUTING:
                return app.wrapScroll(buildRoutingSettings());
            case SETTINGS_INTERFACE:
                return app.wrapScroll(buildInterfaceSettings());
            case SETTINGS_ABOUT:
                return app.wrapScroll(buildAboutSettings());
            case SETTINGS_SCRIPTS:
                scriptsPanel = new ScriptsPanel(app);
                return app.wrapScroll(scriptsPanel.view());
            case SETTINGS_MODE:
            default:
                return app.wrapScroll(buildModeSettings());
        }
    }

    private View buildModeSettings() {
        LinearLayout section = app.page();
        TextView hint = app.text(
                "Туннель направляет через систему весь трафик устройства. "
                        + "Прокси поднимает локальный SOCKS5-сервер без запроса разрешения на туннель - "
                        + "адрес нужно указать вручную в приложениях, которые поддерживают прокси.",
                12, app.secondary, false);
        section.addView(hint, app.matchWrap());

        String[] modes = {MainActivity.MODE_TUNNEL, MainActivity.MODE_PROXY, MainActivity.MODE_EXIT};
        Runnable[] onModeChanged = new Runnable[1];
        View modeChoice = app.choiceList(new String[][]{
                {"Туннель", "Весь трафик устройства"},
                {"Прокси (SOCKS5)", "Без системного туннеля"},
                {"Выходная нода (L4)", "Телефон выпускает клиентов в интернет"},
        }, new int[]{R.drawable.ic_lock, R.drawable.ic_swap, R.drawable.ic_power}, java.util.Arrays.asList(modes).indexOf(editorConnectionMode), i -> {
            editorConnectionMode = modes[i];
            onModeChanged[0].run();
        });
        LinearLayout.LayoutParams modeGroupParams = app.matchWrap();
        modeGroupParams.topMargin = app.dp(14);
        section.addView(modeChoice, modeGroupParams);

        TextView exitHint = app.text(
                "Телефон станет выходной нодой: клиенты подключаются к нему через транспорт "
                        + "выбранного профиля, их трафик выходит в интернет с IP телефона. Профиль "
                        + "должен совпадать с профилем клиента (тот же документ, ключ и режим). Для "
                        + "Direct укажите адрес прослушивания, например 0.0.0.0:8445. Экран можно "
                        + "выключать, нода продолжит работу.",
                12, app.secondary, false);
        app.setInitialVisibility(exitHint, MainActivity.MODE_EXIT.equals(editorConnectionMode));
        LinearLayout.LayoutParams exitHintParams = app.matchWrap();
        exitHintParams.topMargin = app.dp(10);
        exitHintParams.leftMargin = app.dp(4);
        exitHintParams.rightMargin = app.dp(4);
        section.addView(exitHint, exitHintParams);

        boolean proxySelected = MainActivity.MODE_PROXY.equals(editorConnectionMode);

        proxyPortInput = app.settingInput("Порт", String.valueOf(editorProxyPort), InputType.TYPE_CLASS_NUMBER);
        View portRow = app.settingRow(R.drawable.ic_swap, "Локальный порт SOCKS5", proxyPortInput);
        app.setInitialVisibility(portRow, proxySelected);
        LinearLayout.LayoutParams portParams = app.matchWrap();
        portParams.topMargin = app.dp(14);
        section.addView(portRow, portParams);

        Switch lanSwitch = app.settingSwitch(R.drawable.ic_public, "Доступ из локальной сети",
                "Прокси станет виден другим устройствам в этой же Wi-Fi/LAN", editorProxyLanAccess);
        View lanRow = (View) lanSwitch.getTag();
        app.setInitialVisibility(lanRow, proxySelected);
        LinearLayout.LayoutParams lanParams = app.matchWrap();
        lanParams.topMargin = app.dp(8);
        section.addView(lanRow, lanParams);

        String localIp = app.getLocalIpAddress();
        TextView lanAddressHint = app.text(
                localIp != null
                        ? "Адрес в сети: " + localIp + ":" + editorProxyPort
                        : "Не удалось определить IP - проверьте подключение к Wi-Fi",
                13, app.accent, true);
        lanAddressHint.setPadding(app.dp(14), app.dp(12), app.dp(14), app.dp(12));
        lanAddressHint.setBackground(app.rounded(app.darkMode ? Color.rgb(38, 50, 68) : Color.rgb(232, 240, 254),
                Color.TRANSPARENT, 0, 10));
        app.setInitialVisibility(lanAddressHint, proxySelected && editorProxyLanAccess);
        LinearLayout.LayoutParams lanAddressParams = app.matchWrap();
        lanAddressParams.topMargin = app.dp(8);
        section.addView(lanAddressHint, lanAddressParams);

        Switch authSwitch = app.settingSwitch(R.drawable.ic_lock, "Логин и пароль",
                "Требовать авторизацию для подключения к прокси", editorProxyAuthEnabled);
        View authRow = (View) authSwitch.getTag();
        app.setInitialVisibility(authRow, proxySelected && editorProxyLanAccess);
        LinearLayout.LayoutParams authParams = app.matchWrap();
        authParams.topMargin = app.dp(8);
        section.addView(authRow, authParams);
        View lanWarningHint = app.fieldHint(
                "Без пароля прокси в локальной сети открыт для всех: любой в этой Wi-Fi сможет "
                        + "ходить в интернет через ваш туннель.");
        app.setInitialVisibility(lanWarningHint, proxySelected && editorProxyLanAccess);
        section.addView(lanWarningHint);

        LinearLayout credentialsBlock = new LinearLayout(app);
        credentialsBlock.setOrientation(LinearLayout.VERTICAL);
        app.setInitialVisibility(credentialsBlock, proxySelected && editorProxyLanAccess && editorProxyAuthEnabled);
        LinearLayout.LayoutParams credentialsParams = app.matchWrap();
        credentialsParams.topMargin = app.dp(10);
        section.addView(credentialsBlock, credentialsParams);

        proxyUsernameInput = app.settingInput("Логин", app.proxyUsername, InputType.TYPE_CLASS_TEXT);
        credentialsBlock.addView(app.iconTextField(R.drawable.ic_person, proxyUsernameInput, null),
                new LinearLayout.LayoutParams(-1, app.dp(ThemedActivity.FIELD_HEIGHT)));
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(-1, app.dp(ThemedActivity.FIELD_HEIGHT));
        passwordParams.topMargin = app.dp(4);
        credentialsBlock.addView(buildProxyPasswordField(), passwordParams);

        Button generateCreds = new Button(app);
        generateCreds.setText("Сгенерировать логин и пароль");
        generateCreds.setAllCaps(false);
        generateCreds.setTextColor(app.accent);
        generateCreds.setTextSize(13);
        generateCreds.setStateListAnimator(null);
        generateCreds.setBackground(app.ripple(Color.TRANSPARENT, 9));
        generateCreds.setOnClickListener(v -> {
            app.bounce(v);
            generateProxyCredentials();
        });
        LinearLayout.LayoutParams generateParams = new LinearLayout.LayoutParams(-1, app.dp(44));
        generateParams.topMargin = app.dp(2);
        credentialsBlock.addView(generateCreds, generateParams);

        View shareCard = buildProxyShareCard();
        app.setInitialVisibility(shareCard, proxySelected);
        LinearLayout.LayoutParams shareParams = app.matchWrap();
        shareParams.topMargin = app.dp(14);
        section.addView(shareCard, shareParams);

        TextView reliabilityTitle = app.label("НАДЁЖНОСТЬ");
        LinearLayout.LayoutParams reliabilityTitleParams = app.matchWrap();
        reliabilityTitleParams.topMargin = app.dp(26);
        reliabilityTitleParams.bottomMargin = app.dp(8);
        section.addView(reliabilityTitle, reliabilityTitleParams);

        LinearLayout batteryRow = app.cardRow(R.drawable.ic_power, "Отключить оптимизацию батареи",
                "Чтобы система не убивала соединение в фоне");
        batteryRow.setClickable(true);
        batteryRow.setFocusable(true);
        batteryRow.setOnClickListener(v -> {
            app.bounce(v);
            requestIgnoreBatteryOptimizations();
        });
        section.addView(batteryRow, app.matchWrap());

        LinearLayout alwaysOnRow = app.cardRow(R.drawable.ic_lock, "Настройки Always-on Tunnel",
                "Включите \"Блокировать соединения без туннеля\" для защиты от утечек при обрыве");
        alwaysOnRow.setClickable(true);
        alwaysOnRow.setFocusable(true);
        alwaysOnRow.setOnClickListener(v -> {
            app.bounce(v);
            app.startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        });
        app.setInitialVisibility(alwaysOnRow, MainActivity.MODE_TUNNEL.equals(editorConnectionMode));
        LinearLayout.LayoutParams alwaysOnParams = app.matchWrap();
        alwaysOnParams.topMargin = app.dp(8);
        section.addView(alwaysOnRow, alwaysOnParams);

        onModeChanged[0] = () -> {
            boolean nowProxy = MainActivity.MODE_PROXY.equals(editorConnectionMode);
            app.setViewVisibleAnimated(exitHint, MainActivity.MODE_EXIT.equals(editorConnectionMode));
            app.setViewVisibleAnimated(portRow, nowProxy);
            app.setViewVisibleAnimated(lanRow, nowProxy);
            app.setViewVisibleAnimated(lanAddressHint, nowProxy && editorProxyLanAccess);
            app.setViewVisibleAnimated(authRow, nowProxy && editorProxyLanAccess);
            app.setViewVisibleAnimated(lanWarningHint, nowProxy && editorProxyLanAccess);
            app.setViewVisibleAnimated(credentialsBlock, nowProxy && editorProxyLanAccess && editorProxyAuthEnabled);
            app.setViewVisibleAnimated(shareCard, nowProxy);
            app.setViewVisibleAnimated(alwaysOnRow, MainActivity.MODE_TUNNEL.equals(editorConnectionMode));
        };

        lanSwitch.setOnCheckedChangeListener((button, checked) -> {
            app.tap(button);
            editorProxyLanAccess = checked;
            if (proxyShareQr != null) app.setViewVisibleAnimated(proxyShareQr, checked);
            app.setViewVisibleAnimated(lanAddressHint, checked);
            app.setViewVisibleAnimated(authRow, checked);
            app.setViewVisibleAnimated(lanWarningHint, checked);
            app.setViewVisibleAnimated(credentialsBlock, checked && editorProxyAuthEnabled);
        });

        authSwitch.setOnCheckedChangeListener((button, checked) -> {
            app.tap(button);
            editorProxyAuthEnabled = checked;
            app.setViewVisibleAnimated(credentialsBlock, checked);
        });

        return section;
    }

    // Commits the Mode/Proxy draft fields to the live settings. Called by the
    // generic "Сохранить настройки" button in buildSettingsPage() - editing
    // these fields and pressing back without it discards the draft.
    private void applyModeSettings() {
        // If the mode is actually changing while the OLD mode's service is
        // still connected, isConnectionRunning() below would start checking
        // the NEW mode's (not yet started) service and show "not connected" -
        // while the old service keeps running unseen in the background,
        // holding the VPN slot or the proxy port. Stop it explicitly instead
        // of orphaning it.
        boolean modeChanging = !editorConnectionMode.equals(app.connectionMode);
        String oldMode = app.connectionMode;
        boolean oldWasRunning = app.isConnectionRunning();

        app.connectionMode = editorConnectionMode;
        app.proxyLanAccess = editorProxyLanAccess;
        app.proxyAuthEnabled = editorProxyAuthEnabled;
        int newPort;
        try {
            newPort = Integer.parseInt(proxyPortInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            newPort = MainActivity.DEFAULT_PROXY_PORT;
        }
        app.proxyPort = Math.max(1024, Math.min(65535, newPort));
        app.proxyUsername = proxyUsernameInput.getText().toString().trim();
        app.proxyPassword = proxyPasswordInput.getText().toString().trim();
        app.persistSettings();

        if (modeChanging && oldWasRunning) {
            app.stopConnection(oldMode);
            app.appendLog("Режим изменён - предыдущее соединение (" + MainActivity.modeLabel(oldMode) + ") остановлено");
        }
    }

    // buildProxyShareCard renders the socks:// link (and a QR encoding it)
    // that another device can use to add this proxy in an app like Happ or
    // Telegram, using whatever host/port/credentials are currently saved.
    // It reflects state as of when this settings screen was built, not live
    // as the user edits fields above - consistent with how other settings
    // here only take effect after being saved/reopened.
    private View buildProxyShareCard() {
        LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(app.dp(16), app.dp(14), app.dp(16), app.dp(14));
        card.setBackground(app.rounded(app.surface, app.border, 1, 11));
        card.addView(app.text("Ссылка для подключения с другого устройства", 12, app.secondary, false));

        String link = proxyShareLink();
        TextView linkView = app.text(link, 14, app.text, true);
        linkView.setTextIsSelectable(true);
        LinearLayout.LayoutParams linkParams = app.matchWrap();
        linkParams.topMargin = app.dp(6);
        card.addView(linkView, linkParams);

        Button copyButton = new Button(app);
        copyButton.setText("Копировать ссылку");
        copyButton.setAllCaps(false);
        copyButton.setTextColor(app.accent);
        copyButton.setTextSize(13);
        copyButton.setStateListAnimator(null);
        copyButton.setBackground(app.ripple(Color.TRANSPARENT, 9));
        copyButton.setOnClickListener(v -> {
            app.bounce(v);
            ClipboardManager clipboard = (ClipboardManager) app.getSystemService(app.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlux SOCKS5", link));
            }
            Toast.makeText(app, "Ссылка скопирована", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(-1, app.dp(40));
        copyParams.topMargin = app.dp(2);
        card.addView(copyButton, copyParams);

        Bitmap qr = generateQrBitmap(link, app.dp(180));
        proxyShareQr = null;
        if (qr != null) {
            // Only another device can scan it, which needs LAN access.
            ImageView qrView = new ImageView(app);
            qrView.setImageBitmap(qr);
            app.setInitialVisibility(qrView, editorProxyLanAccess);
            proxyShareQr = qrView;
            LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(app.dp(180), app.dp(180));
            qrParams.topMargin = app.dp(10);
            qrParams.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(qrView, qrParams);
        }

        return card;
    }

    private String proxyShareLink() {
        String host = app.proxyLanAccess ? app.getLocalIpAddress() : null;
        if (host == null) host = "127.0.0.1";
        String auth = "";
        if (app.proxyLanAccess && app.proxyAuthEnabled && !app.proxyUsername.isEmpty()) {
            auth = app.proxyUsername + ":" + app.proxyPassword + "@";
        }
        return "socks://" + auth + host + ":" + app.proxyPort;
    }

    private Bitmap generateQrBitmap(String content, int sizePx) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx);
            int foreground = app.darkMode ? Color.WHITE : Color.BLACK;
            int background = app.darkMode ? Color.BLACK : Color.WHITE;
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
            for (int x = 0; x < sizePx; x++) {
                for (int y = 0; y < sizePx; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? foreground : background);
                }
            }
            return bitmap;
        } catch (WriterException exception) {
            return null;
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        PowerManager powerManager = (PowerManager) app.getSystemService(app.POWER_SERVICE);
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(app.getPackageName())) {
            Toast.makeText(app, "Оптимизация батареи уже отключена для приложения", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            app.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + app.getPackageName())));
        } catch (Exception exception) {
            app.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private View buildProxyPasswordField() {
        proxyPasswordInput = app.settingInput("Пароль", app.proxyPassword,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        proxyPasswordInput.setTransformationMethod(
                proxyPasswordVisible ? null : PasswordTransformationMethod.getInstance());
        proxyPasswordVisibilityButton = app.iconButton(
                proxyPasswordVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility,
                proxyPasswordVisible ? "Скрыть пароль" : "Показать пароль");
        proxyPasswordVisibilityButton.setOnClickListener(v -> {
            app.tap(v);
            toggleProxyPasswordVisibility();
        });
        return app.iconTextField(R.drawable.ic_key, proxyPasswordInput, proxyPasswordVisibilityButton);
    }

    private void toggleProxyPasswordVisibility() {
        int position = proxyPasswordInput.getSelectionStart();
        proxyPasswordVisible = !proxyPasswordVisible;
        proxyPasswordInput.setTransformationMethod(
                proxyPasswordVisible ? null : PasswordTransformationMethod.getInstance());
        proxyPasswordInput.setTypeface(Typeface.DEFAULT);
        proxyPasswordVisibilityButton.setImageResource(
                proxyPasswordVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
        proxyPasswordVisibilityButton.setContentDescription(
                proxyPasswordVisible ? "Скрыть пароль" : "Показать пароль");
        proxyPasswordInput.setSelection(Math.max(0, Math.min(position, proxyPasswordInput.length())));
    }

    // Only fills the (still-unsaved) input fields - applyModeSettings() is
    // what actually commits them, same as editing the fields by hand would.
    private void generateProxyCredentials() {
        byte[] randomPass = new byte[16];
        new SecureRandom().nextBytes(randomPass);
        String generatedUser = "user" + (100 + new SecureRandom().nextInt(900));
        String generatedPass = Base64.encodeToString(randomPass, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        if (proxyUsernameInput != null) proxyUsernameInput.setText(generatedUser);
        if (proxyPasswordInput != null) {
            proxyPasswordInput.setText(generatedPass);
            proxyPasswordInput.setSelection(proxyPasswordInput.length());
        }
        Toast.makeText(app, "Логин и пароль созданы - нажмите «Применить»", Toast.LENGTH_SHORT).show();
    }

    private View buildAboutSettings() {
        LinearLayout section = app.page();
        TextView intro = app.text(
                "OpenFlux - экспериментальный туннель-клиент поверх документ-транспорта. "
                        + "Это доработанный форк общедоступного проекта под Android.",
                13, app.secondary, false);
        section.addView(intro, app.matchWrap());

        LinearLayout links = new LinearLayout(app);
        links.setOrientation(LinearLayout.VERTICAL);
        links.addView(aboutLinkRow(R.drawable.ic_github, "Основной репозиторий",
                "p1neappleXpress/OpenFlux", MainActivity.MAIN_REPO_URL), app.matchWrap());
        links.addView(aboutLinkRow(R.drawable.ic_github, "Наш форк",
                "damnurmum/OpenFlux-Android", MainActivity.FORK_REPO_URL), app.matchWrap());
        links.addView(aboutLinkRow(R.drawable.ic_telegram, "Telegram чат",
                "@openflux_chat", "https://t.me/openflux_chat"), app.matchWrap());
        links.addView(aboutLinkRow(R.drawable.ic_discord, "Discord",
                "discord.gg/8a4S3QAh62", "https://discord.gg/8a4S3QAh62"), app.matchWrap());
        app.groupTiles(links);
        LinearLayout.LayoutParams linksParams = app.matchWrap();
        linksParams.topMargin = app.dp(20);
        section.addView(links, linksParams);
        return section;
    }

    private View aboutLinkRow(int iconRes, String titleValue, String detailValue, String url) {
        LinearLayout row = app.cardRow(iconRes, titleValue, detailValue);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            app.bounce(v);
            app.openUrl(url);
        });
        return row;
    }

    private View buildNetworkSettings() {
        LinearLayout section = app.page();

        Switch dnsAutoSwitch = app.settingSwitch(R.drawable.ic_public, "DNS-сервер: по умолчанию",
                "1.1.1.1 через выходную ноду; DNS-запросы в VPN идут через туннель",
                editorDnsAuto);
        View dnsAutoRow = (View) dnsAutoSwitch.getTag();
        section.addView(dnsAutoRow, app.matchWrap());

        dnsInput = app.settingInput("DNS-сервер", editorDnsServer,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        View dnsInputRow = app.settingRow(R.drawable.ic_public, "Свой DNS", dnsInput);
        app.setInitialVisibility(dnsInputRow, !editorDnsAuto);
        LinearLayout.LayoutParams dnsInputParams = app.matchWrap();
        dnsInputParams.topMargin = app.dp(8);
        section.addView(dnsInputRow, dnsInputParams);
        View dnsHint = app.fieldHint(
                "Укажите IPv4-адрес DNS-сервера, доступного с выходной ноды. "
                        + "Имена вроде dns.google здесь не поддерживаются: их пришлось бы разрешать до запуска VPN.");
        app.setInitialVisibility(dnsHint, !editorDnsAuto);
        section.addView(dnsHint);

        dnsAutoSwitch.setOnCheckedChangeListener((button, checked) -> {
            app.tap(button);
            editorDnsAuto = checked;
            app.setViewVisibleAnimated(dnsInputRow, !checked);
            app.setViewVisibleAnimated(dnsHint, !checked);
        });

        mtuInput = app.settingInput("MTU", String.valueOf(editorMtu), InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams mtuParams = app.matchWrap();
        mtuParams.topMargin = app.dp(16);
        section.addView(app.settingRow(R.drawable.ic_settings, "MTU пакета", mtuInput), mtuParams);
        section.addView(app.fieldHint(
                "Для чего: максимальный размер пакета в туннеле. Трогать не обязательно - "
                        + "уменьшите (например, до 1280), если сайты грузятся не полностью "
                        + "или соединение обрывается."));

        Switch killSwitchSwitch = app.settingSwitch(R.drawable.ic_lock, "Kill Switch",
                "Блокировать трафик, если туннель отключился, вместо пропуска мимо него",
                editorKillSwitchEnabled);
        killSwitchSwitch.setOnCheckedChangeListener((button, checked) -> {
            app.tap(button);
            editorKillSwitchEnabled = checked;
        });
        LinearLayout.LayoutParams killSwitchParams = app.matchWrap();
        killSwitchParams.topMargin = app.dp(16);
        section.addView((View) killSwitchSwitch.getTag(), killSwitchParams);
        section.addView(app.fieldHint(
                "Включено: при обрыве соединения приложения теряют доступ в сеть, а не "
                        + "продолжают работать в обход туннеля. Выключено: примерно через 30 "
                        + "секунд без связи туннель отключится сам и сеть заработает как обычно."));

        return section;
    }

    // Commits the Network draft fields to the live settings. Called by the
    // generic "Сохранить настройки" button in buildSettingsPage() - editing
    // these fields and pressing back without it discards the draft.
    private boolean applyNetworkSettings() {
        String selectedDns = editorDnsAuto ? "" : DnsServer.normalizeIpv4(dnsInput.getText().toString());
        if (selectedDns == null) {
            Toast.makeText(app, "Укажите IPv4-адрес DNS-сервера, например 1.1.1.1", Toast.LENGTH_LONG).show();
            return false;
        }
        app.dnsServer = selectedDns;
        int newMtu;
        try {
            newMtu = Integer.parseInt(mtuInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            newMtu = MainActivity.DEFAULT_MTU;
        }
        app.mtu = Math.max(576, Math.min(1500, newMtu));
        app.killSwitchEnabled = editorKillSwitchEnabled;
        app.persistSettings();
        return true;
    }

    private void applyInterfaceSettings() {
        app.autoScroll = editorAutoScroll;
        app.getSharedPreferences(MainActivity.SETTINGS_PREFS_NAME, app.MODE_PRIVATE).edit()
                .putBoolean("auto_scroll", app.autoScroll)
                .apply();
        // Rebuilds the whole shell/page when the theme actually changed, so
        // it must run last - everything above needs to be committed first.
        app.switchTheme(editorDarkMode);
    }

    private void applyAppsSettings() {
        app.appFilterMode = editorAppFilterMode;
        app.selectedApps.clear();
        app.selectedApps.addAll(editorSelectedApps);
        persistAppFilter();
    }

    private View buildRoutingSettings() {
        LinearLayout section = app.page();
        TextView routingHint = app.text(
                "Домены и сервисы ниже подключаются напрямую, в обход туннеля - полезно для "
                        + "локальных сервисов и всего, что чувствительно к задержке.",
                12, app.secondary, false);
        section.addView(routingHint, app.matchWrap());

        if (!app.isProxyMode()) {
            TextView tunnelNote = app.text(
                    "Пока работает только в режиме Прокси (SOCKS5). В режиме Туннель настройки "
                            + "сохранятся, но не применяются - переключите режим работы, чтобы им пользоваться.",
                    12, app.accent, false);
            LinearLayout.LayoutParams tunnelNoteParams = app.matchWrap();
            tunnelNoteParams.topMargin = app.dp(8);
            section.addView(tunnelNote, tunnelNoteParams);
        }

        LinearLayout presets = new LinearLayout(app);
        presets.setOrientation(LinearLayout.VERTICAL);
        for (DomainFilter.Preset preset : DomainFilter.PRESETS) {
            Switch presetSwitch = app.settingSwitch(presetIcon(preset.id), preset.title, preset.description,
                    editorEnabledDomainPresets.contains(preset.id));
            presetSwitch.setOnCheckedChangeListener((button, checked) -> {
                app.tap(button);
                if (checked) editorEnabledDomainPresets.add(preset.id);
                else editorEnabledDomainPresets.remove(preset.id);
            });
            presets.addView((View) presetSwitch.getTag(), app.matchWrap());
        }
        app.groupTiles(presets);
        LinearLayout.LayoutParams presetsParams = app.matchWrap();
        presetsParams.topMargin = app.dp(12);
        section.addView(presets, presetsParams);

        LinearLayout.LayoutParams customLabelParams = app.matchWrap();
        customLabelParams.topMargin = app.dp(16);
        section.addView(app.text("Свои домены", 13, app.secondary, false), customLabelParams);

        customDomainsInput = new EditText(app);
        customDomainsInput.setHint("youtube.com\nexample.org");
        customDomainsInput.setHintTextColor(app.hint);
        customDomainsInput.setText(String.join("\n", app.customDomains));
        customDomainsInput.setTextSize(14);
        customDomainsInput.setTextColor(app.text);
        customDomainsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        customDomainsInput.setMinLines(3);
        customDomainsInput.setGravity(Gravity.TOP | Gravity.START);
        customDomainsInput.setBackground(app.rounded(app.surface, app.border, 1, 10));
        customDomainsInput.setPadding(app.dp(14), app.dp(12), app.dp(14), app.dp(12));
        LinearLayout.LayoutParams customInputParams = app.matchWrap();
        customInputParams.topMargin = app.dp(6);
        section.addView(customDomainsInput, customInputParams);
        section.addView(app.fieldHint("По одному домену на строку, без http:// и путей - например youtube.com."));

        return section;
    }

    private static int presetIcon(String id) {
        switch (id) {
            case "ru": return R.drawable.ic_gerb;
            case "youtube": return R.drawable.ic_youtube;
            case "discord": return R.drawable.ic_discord;
            case "ai": return R.drawable.ic_openai;
            default: return R.drawable.ic_public;
        }
    }

    private void applyRoutingSettings() {
        app.enabledDomainPresets.clear();
        app.enabledDomainPresets.addAll(editorEnabledDomainPresets);
        app.customDomains.clear();
        for (String line : customDomainsInput.getText().toString().split("\n")) {
            String trimmed = line.trim().toLowerCase(java.util.Locale.ROOT);
            if (!trimmed.isEmpty()) app.customDomains.add(trimmed);
        }
        persistDomainFilter();
    }

    private void persistDomainFilter() {
        app.domainFilterPrefs.edit()
                .putStringSet(DomainFilter.KEY_ENABLED_PRESETS, new HashSet<>(app.enabledDomainPresets))
                .putStringSet(DomainFilter.KEY_CUSTOM_DOMAINS, new HashSet<>(app.customDomains))
                .apply();
    }

    private View buildInterfaceSettings() {
        LinearLayout section = app.page();
        LinearLayout group = new LinearLayout(app);
        group.setOrientation(LinearLayout.VERTICAL);
        section.addView(group, app.matchWrap());
        Switch themeSwitch = app.settingSwitch(R.drawable.ic_dark_mode, "Тёмная тема",
                "До первого выбора используется тема телефона", editorDarkMode);
        themeSwitch.setOnCheckedChangeListener((button, checked) -> {
            app.tap(button);
            editorDarkMode = checked;
        });
        group.addView((View) themeSwitch.getTag(), app.matchWrap());
        Switch scrollSwitch = app.settingSwitch(R.drawable.ic_terminal, "Автопрокрутка логов",
                "Показывать последние события", editorAutoScroll);
        scrollSwitch.setOnCheckedChangeListener((button, checked) -> {
            app.tap(button);
            editorAutoScroll = checked;
        });
        group.addView((View) scrollSwitch.getTag(), app.matchWrap());
        app.groupTiles(group);
        return section;
    }

    private void persistAppFilter() {
        app.appFilterPrefs.edit()
                .putString(AppFilter.KEY_MODE, app.appFilterMode)
                .putStringSet(AppFilter.KEY_PACKAGES, new HashSet<>(app.selectedApps))
                .apply();
    }
}
