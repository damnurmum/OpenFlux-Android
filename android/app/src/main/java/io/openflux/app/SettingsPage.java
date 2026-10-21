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

    // The settings search: the query stays while a found section is open, so
    // "back" returns to the results; pendingHighlight is the row to light up
    // once that section is shown.
    String searchQuery = "";
    private String pendingHighlight;
    private LinearLayout settingsList;

    SettingsPage(MainActivity app) {
        this.app = app;
    }

    // A row the search finds: its section, icon and title, the text on the
    // page to light up (usually the title itself), and other words people
    // may type for it.
    private static final class SearchItem {
        final int tab, icon;
        final String title, target, words;

        SearchItem(int tab, int icon, String title, String target, String words) {
            this.tab = tab;
            this.icon = icon;
            this.title = title;
            this.target = target;
            this.words = words;
        }
    }

    // Titles here must match the rows built below, or the highlight finds
    // nothing (the section still opens).
    private static java.util.List<SearchItem> searchItems() {
        java.util.List<SearchItem> items = new java.util.ArrayList<>(java.util.Arrays.asList(
                item(SETTINGS_MODE, R.drawable.ic_lock, "Туннель", "vpn весь трафик режим"),
                item(SETTINGS_MODE, R.drawable.ic_swap, "Прокси (SOCKS5)", "socks proxy режим"),
                item(SETTINGS_MODE, R.drawable.ic_power, "Выходная нода (L4)", "exit нода раздать интернет режим"),
                item(SETTINGS_MODE, R.drawable.ic_swap, "Локальный порт SOCKS5", "порт прокси 1080"),
                item(SETTINGS_MODE, R.drawable.ic_public, "Доступ из локальной сети", "lan wi-fi wifi прокси"),
                item(SETTINGS_MODE, R.drawable.ic_lock, "Логин и пароль", "авторизация прокси пароль"),
                item(SETTINGS_MODE, R.drawable.ic_power, "Отключить оптимизацию батареи", "батарея фон энергосбережение"),
                item(SETTINGS_MODE, R.drawable.ic_lock, "Настройки Always-on Tunnel", "постоянный vpn утечки always on"),
                item(SETTINGS_NETWORK, R.drawable.ic_public, "DNS-сервер: по умолчанию", "dns 1.1.1.1"),
                item(SETTINGS_NETWORK, R.drawable.ic_public, "Свой DNS", "dns сервер адрес"),
                item(SETTINGS_NETWORK, R.drawable.ic_settings, "MTU пакета", "mtu размер пакета"),
                item(SETTINGS_NETWORK, R.drawable.ic_lock, "Kill Switch", "килл свитч блокировка обрыв утечки"),
                item(SETTINGS_APPS, R.drawable.ic_apps, "Все приложения", "фильтр приложений"),
                item(SETTINGS_APPS, R.drawable.ic_apps, "Только выбранные", "белый список whitelist"),
                item(SETTINGS_APPS, R.drawable.ic_apps, "Все, кроме выбранных", "черный список blacklist исключения"),
                item(SETTINGS_APPS, R.drawable.ic_apps, "Показать системные приложения", "системные")));
        for (DomainFilter.Preset preset : DomainFilter.PRESETS) {
            items.add(item(SETTINGS_ROUTING, presetIcon(preset.id), preset.title, "обход " + preset.description));
        }
        items.addAll(java.util.Arrays.asList(
                item(SETTINGS_ROUTING, R.drawable.ic_routing, "Свои домены", "домены сайты обход"),
                item(SETTINGS_INTERFACE, R.drawable.ic_dark_mode, "Тёмная тема", "тема светлая ночная оформление"),
                item(SETTINGS_INTERFACE, R.drawable.ic_terminal, "Автопрокрутка логов", "журнал логи прокрутка"),
                item(SETTINGS_SCRIPTS, R.drawable.ic_code, "Экспериментальные функции", "js javascript скрипты включить"),
                new SearchItem(SETTINGS_SCRIPTS, R.drawable.ic_code, "Импорт JS-транспорта", "Импортировать",
                        "js javascript скрипт flux файл ссылка"),
                new SearchItem(SETTINGS_SCRIPTS, R.drawable.ic_code, "Обновления JS-транспортов", "Проверить обновления",
                        "js javascript скрипты"),
                item(SETTINGS_ABOUT, R.drawable.ic_github, "Ядро проекта", "github апстрим исходный код core"),
                item(SETTINGS_ABOUT, R.drawable.ic_github, "Приложение", "github исходный код android"),
                new SearchItem(SETTINGS_ABOUT, R.drawable.ic_telegram, "Telegram-чат", "Telegram",
                        "телеграм чат сообщество помощь поддержка"),
                new SearchItem(SETTINGS_ABOUT, R.drawable.ic_discord, "Discord-сервер", "Discord",
                        "дискорд сервер сообщество помощь поддержка")));
        return items;
    }

    private static SearchItem item(int tab, int icon, String title, String words) {
        return new SearchItem(tab, icon, title, title, words);
    }

    private static String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е').trim();
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
        // When the focused search field goes with the old page, focus lands
        // on the page itself rather than the first field on the new one.
        page.setFocusableInTouchMode(true);
        page.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        page.addView(buildSettingsHeader());

        if (!settingsDetailOpen) {
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, app.dp(48));
            searchParams.topMargin = app.dp(16);
            page.addView(buildSearchField(), searchParams);
            LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            listParams.topMargin = app.dp(12);
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
        if (pendingHighlight != null) {
            String target = pendingHighlight;
            pendingHighlight = null;
            // After the page has laid out and started fading in.
            subTab.postDelayed(() -> highlight(subTab, target), 120);
        }

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
        settingsList = list;
        fillSettingsList();
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    // The sections, or while there is a query the rows that match it.
    private void fillSettingsList() {
        LinearLayout list = settingsList;
        list.removeAllViews();
        String[] words = normalize(searchQuery).split("\\s+");
        if (!words[0].isEmpty()) {
            for (SearchItem item : searchItems()) {
                String haystack = normalize(item.title + " " + item.words + " " + settingsSectionTitle(item.tab));
                boolean match = true;
                for (String word : words) match &= haystack.contains(word);
                if (!match) continue;
                list.addView(settingsListRow(item.icon, item.title, settingsSectionTitle(item.tab), () -> {
                    hideKeyboard(list);
                    pendingHighlight = item.target;
                    openSettingsDetail(item.tab);
                }));
            }
            if (list.getChildCount() == 0) {
                TextView empty = app.text("Ничего не найдено", 13, app.secondary, false);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, app.dp(24), 0, 0);
                list.addView(empty, app.matchWrap());
                return;
            }
            app.groupTiles(list);
            return;
        }
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
                "Помощь, сообщество и исходный код", SETTINGS_ABOUT));
        app.groupTiles(list);
    }

    private View buildSearchField() {
        LinearLayout row = new LinearLayout(app);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(app.rounded(app.surface, app.border, 1, 24));
        EditText input = app.settingInput("Поиск настроек", searchQuery,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setContentDescription("Поиск настроек");
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(app.dp(10), 0, 0, 0);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(app.dp(20), app.dp(20));
        iconParams.leftMargin = app.dp(16);
        row.addView(app.icon(R.drawable.ic_search, app.hint), iconParams);
        row.addView(input, new LinearLayout.LayoutParams(0, -1, 1f));
        ImageButton clear = app.iconButton(R.drawable.ic_close, "Очистить поиск");
        clear.setVisibility(searchQuery.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        clear.setOnClickListener(v -> {
            app.tap(v);
            input.setText("");
        });
        row.addView(clear, new LinearLayout.LayoutParams(app.dp(48), -1));
        input.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard(v);
            return true;
        });
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                boolean wasSearching = !searchQuery.trim().isEmpty();
                searchQuery = s.toString();
                boolean searching = !searchQuery.trim().isEmpty();
                clear.setVisibility(searchQuery.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                fillSettingsList();
                // Sections and results swap with a short fade; typing within
                // the results just refreshes them.
                if (wasSearching != searching) {
                    settingsList.setAlpha(0f);
                    settingsList.animate().alpha(1f).setDuration(180)
                            .setInterpolator(ThemedActivity.EMPHASIZED).start();
                }
            }
        });
        return row;
    }

    // "Back" on the results clears the query before it leaves Settings.
    boolean clearSearch() {
        if (searchQuery.isEmpty()) return false;
        searchQuery = "";
        app.showPage(MainActivity.PAGE_SETTINGS);
        return true;
    }

    private void hideKeyboard(View view) {
        android.view.inputmethod.InputMethodManager keyboard =
                app.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    // Lights up the row a search result named, as Android's settings do: the
    // page scrolls it to the middle and an accent wash pulses over it twice.
    private void highlight(View page, String target) {
        View found = findText(page, target);
        if (found == null || !found.isShown()) return;
        // The text sits in its row's copy column; the row is the first
        // ancestor with a tile, short of the page itself.
        View row = found;
        while (row.getBackground() == null && row.getParent() instanceof View) {
            View parent = (View) row.getParent();
            if (parent == page || parent instanceof ScrollView || parent.getParent() instanceof ScrollView
                    || parent instanceof androidx.recyclerview.widget.RecyclerView) break;
            row = parent;
        }
        for (View v = row; v.getParent() instanceof View; v = (View) v.getParent()) {
            if (!(v.getParent() instanceof ScrollView)) continue;
            ScrollView scroll = (ScrollView) v.getParent();
            int y = 0;
            for (View step = row; step != scroll; step = (View) step.getParent()) y += step.getTop();
            scroll.smoothScrollTo(0, Math.max(0, y - (scroll.getHeight() - row.getHeight()) / 2));
            break;
        }
        android.graphics.drawable.GradientDrawable wash = new android.graphics.drawable.GradientDrawable();
        wash.setColor(app.accent);
        wash.setCornerRadii(cornerRadii(row.getBackground()));
        wash.setAlpha(0);
        View lit = row;
        lit.setForeground(wash);
        android.animation.ValueAnimator pulse = android.animation.ValueAnimator.ofInt(0, 70, 0, 70, 0);
        pulse.setDuration(1600);
        pulse.setStartDelay(250);
        pulse.addUpdateListener(a -> wash.setAlpha((int) a.getAnimatedValue()));
        pulse.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                lit.setForeground(null);
            }
        });
        pulse.start();
    }

    private static View findText(View view, String target) {
        if (view instanceof TextView && target.contentEquals(((TextView) view).getText())) return view;
        if (!(view instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findText(group.getChildAt(i), target);
            if (found != null) return found;
        }
        return null;
    }

    // The tile's own corners, so the wash covers it exactly.
    private float[] cornerRadii(android.graphics.drawable.Drawable background) {
        if (background instanceof android.graphics.drawable.LayerDrawable
                && ((android.graphics.drawable.LayerDrawable) background).getNumberOfLayers() > 0) {
            background = ((android.graphics.drawable.LayerDrawable) background).getDrawable(0);
        }
        if (background instanceof android.graphics.drawable.GradientDrawable) {
            android.graphics.drawable.GradientDrawable shape = (android.graphics.drawable.GradientDrawable) background;
            if (shape.getCornerRadii() != null) return shape.getCornerRadii();
            float r = shape.getCornerRadius();
            return new float[]{r, r, r, r, r, r, r, r};
        }
        return app.radii(10);
    }

    private View settingsListRow(int iconRes, String titleValue, String detailValue, int tab) {
        return settingsListRow(iconRes, titleValue, detailValue, () -> openSettingsDetail(tab));
    }

    private View settingsListRow(int iconRes, String titleValue, String detailValue, Runnable open) {
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
            open.run();
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
                "OpenFlux - зашифрованный туннель, который маскирует трафик под совместное "
                        + "редактирование документов. Приложение работает на открытом ядре OpenFlux.",
                13, app.secondary, false);
        section.addView(intro, app.matchWrap());

        LinearLayout.LayoutParams helpParams = app.matchWrap();
        helpParams.topMargin = app.dp(20);
        section.addView(buildHelpCard(), helpParams);

        TextView codeTitle = app.label("ИСХОДНЫЙ КОД");
        LinearLayout.LayoutParams codeTitleParams = app.matchWrap();
        codeTitleParams.topMargin = app.dp(26);
        codeTitleParams.bottomMargin = app.dp(8);
        section.addView(codeTitle, codeTitleParams);
        LinearLayout links = new LinearLayout(app);
        links.setOrientation(LinearLayout.VERTICAL);
        links.addView(aboutLinkRow(R.drawable.ic_github, "Ядро проекта",
                "p1neappleXpress/OpenFlux", MainActivity.MAIN_REPO_URL), app.matchWrap());
        links.addView(aboutLinkRow(R.drawable.ic_github, "Приложение",
                "damnurmum/OpenFlux-Android", MainActivity.APP_REPO_URL), app.matchWrap());
        app.groupTiles(links);
        section.addView(links, app.matchWrap());
        return section;
    }

    // "Нужна помощь?": where to ask, with the community chats as two buttons.
    private View buildHelpCard() {
        LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(app.dp(18), app.dp(18), app.dp(18), app.dp(18));
        card.setBackground(app.rounded(app.surface, app.border, 1, 24));

        LinearLayout heading = new LinearLayout(app);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(app.iconBubble(R.drawable.ic_help), new LinearLayout.LayoutParams(app.dp(40), app.dp(40)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.leftMargin = app.dp(14);
        heading.addView(app.text("Нужна помощь?", 17, app.text, true), titleParams);
        card.addView(heading, app.matchWrap());

        TextView body = app.text("Не подключается, транспорт не работает или непонятно, как что-то настроить? "
                + "Спросите в нашем сообществе: там помогают разработчики и другие пользователи. "
                + "Чтобы разобраться быстрее, опишите, что делали, и приложите журнал со вкладки «Журнал».",
                13, app.secondary, false);
        body.setLineSpacing(app.dp(2), 1f);
        LinearLayout.LayoutParams bodyParams = app.matchWrap();
        bodyParams.topMargin = app.dp(12);
        card.addView(body, bodyParams);

        LinearLayout buttons = new LinearLayout(app);
        buttons.addView(helpButton(R.drawable.ic_telegram, "Telegram", "https://t.me/openflux_chat"),
                new LinearLayout.LayoutParams(0, app.dp(48), 1f));
        LinearLayout.LayoutParams discordParams = new LinearLayout.LayoutParams(0, app.dp(48), 1f);
        discordParams.leftMargin = app.dp(10);
        buttons.addView(helpButton(R.drawable.ic_discord, "Discord", "https://discord.gg/8a4S3QAh62"), discordParams);
        LinearLayout.LayoutParams buttonsParams = app.matchWrap();
        buttonsParams.topMargin = app.dp(16);
        card.addView(buttons, buttonsParams);
        return card;
    }

    private View helpButton(int iconRes, String label, String url) {
        LinearLayout button = new LinearLayout(app);
        button.setGravity(Gravity.CENTER);
        button.setBackground(app.tile(app.tonal(0.2f), Color.TRANSPARENT, app.radii(16)));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(label);
        button.addView(app.icon(iconRes, app.accent), new LinearLayout.LayoutParams(app.dp(20), app.dp(20)));
        TextView text = app.text(label, 14, app.accent, true);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.leftMargin = app.dp(8);
        button.addView(text, textParams);
        button.setOnClickListener(v -> {
            app.bounce(v);
            app.openUrl(url);
        });
        return button;
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
