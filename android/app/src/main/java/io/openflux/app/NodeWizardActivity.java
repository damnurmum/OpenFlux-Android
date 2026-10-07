package io.openflux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import io.openflux.bridge.mobile.Mobile;

// Installs a new exit channel on a VPS using the upstream mobile provision API.
// SSH credentials stay in this Activity; only the accepted host fingerprint
// and the finished OpenFlux profile are persisted.
public final class NodeWizardActivity extends Activity {
    static final String EXTRA_NAV_PAGE = "io.openflux.app.NODE_WIZARD_NAV_PAGE";
    private static final int SSH_KEY_REQUEST = 3101;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable captchaPoll = new Runnable() {
        @Override public void run() {
            if (!verifying || destroyed) return;
            boolean pending = CaptchaActivity.pending();
            if (pending && !captchaShown) {
                captchaShown = true;
                startActivity(new android.content.Intent(NodeWizardActivity.this, CaptchaActivity.class));
            } else if (!pending) {
                captchaShown = false;
            }
            handler.postDelayed(this, 500);
        }
    };

    private SecureSettings secureSettings;
    private FlowStyle ui;
    private final Map<CheckBox, View> choiceFields = new HashMap<>();
    private EditText hostInput, sshPortInput, userInput, passwordInput, privateKeyInput, passphraseInput;
    private EditText yandexInput, mailruInput, sudoInput;
    private CheckBox yandexCheck, mailruCheck, cupsCheck, autoUpdateCheck;
    private Button connectButton, changeServerButton, planButton, changeButton, applyButton, verifyButton, saveButton, removeButton;
    private TextView intro, connectedHost, serverInfo, planInfo;
    private TextView keyFileInfo;
    private LinearLayout sshSection, connectedCard, configSection, planCard, planActions;
    private ScrollView scroll;
    private AlertDialog progressDialog;
    private final LinearLayout[] authRows = new LinearLayout[2];
    private final TextView[] authTitles = new TextView[2];
    private final ImageView[] authChecks = new ImageView[2];
    private View passwordRow, passwordDivider, keyGroup;
    private Button chooseKeyButton;
    private boolean usePrivateKey;
    private boolean busy, connected, planned, applied, verified, primaryLive = true;
    private boolean verifying, captchaShown, destroyed;
    private String channelId, channelKey, cupsRooms = "", transportsJson, shareLink;
    private String planSummary;
    private String importedPrivateKey;
    private int channelPort;
    private boolean selectedAutoUpdate;
    private long savedProfileId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        secureSettings = new SecureSettings(this);
        ui = new FlowStyle(this);
        ui.applyWindow();
        buildPage();
    }

    private void buildPage() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ui.background);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(20), dp(16), dp(20), 0);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        shell.addView(buildAppHeader(), new LinearLayout.LayoutParams(-1, dp(54)));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollParams.topMargin = dp(16);
        shell.addView(scroll, scrollParams);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page);

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, ui.background}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, dp(126), Gravity.BOTTOM));
        LinearLayout nav = buildBottomNav();
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(-1, dp(68), Gravity.BOTTOM);
        navParams.leftMargin = dp(12);
        navParams.rightMargin = dp(12);
        navParams.bottomMargin = dp(10);
        root.addView(nav, navParams);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom, keyboard;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
                keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                keyboard = 0;
            }
            boolean keyboardOpen = keyboard > bottom + dp(50);
            shell.setPadding(dp(20), dp(16) + top, dp(20), keyboardOpen ? keyboard : 0);
            nav.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
            scrim.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
            FrameLayout.LayoutParams navLayout = (FrameLayout.LayoutParams) nav.getLayoutParams();
            navLayout.bottomMargin = dp(10) + bottom;
            nav.setLayoutParams(navLayout);
            FrameLayout.LayoutParams scrimLayout = (FrameLayout.LayoutParams) scrim.getLayoutParams();
            scrimLayout.height = dp(126) + bottom;
            scrim.setLayoutParams(scrimLayout);
            // On the page, not the scroll: a ScrollView counts its own padding
            // as visible area, so it would refuse a short drag that starts on
            // a field or a card.
            page.setPadding(0, 0, 0, dp(68 + 10 + 16) + bottom);
            return insets;
        });
        setContentView(root);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setImageTintList(ColorStateList.valueOf(ui.secondary));
        back.setBackground(ui.rounded(ui.background, Color.TRANSPARENT, 10));
        back.setContentDescription("Назад к профилям");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        backParams.rightMargin = dp(12);
        heading.addView(back, backParams);
        TextView title = new TextView(this);
        title.setText("Создать ноду на VPS");
        title.setTextSize(22);
        ui.heading(title);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(heading);
        intro = addText(page, "Сначала подключитесь к серверу по SSH. Приложение покажет план изменений перед установкой.", 13, false);
        spaceAbove(intro, 4);
        connectedCard = buildConnectedCard(page);
        sshSection = new LinearLayout(this);
        sshSection.setOrientation(LinearLayout.VERTICAL);
        page.addView(sshSection, new LinearLayout.LayoutParams(-1, -2));
        sectionLabel(sshSection, "SSH ПОДКЛЮЧЕНИЕ");
        LinearLayout addressGroup = credentialGroup(sshSection);
        hostInput = groupedField(addressGroup, "Адрес сервера", "example.com или IP",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        groupDivider(addressGroup);
        sshPortInput = groupedField(addressGroup, "Порт SSH", "22", InputType.TYPE_CLASS_NUMBER);
        sshPortInput.setText("22");
        sectionLabel(sshSection, "СПОСОБ ВХОДА");
        LinearLayout authChoices = new LinearLayout(this);
        authChoices.setOrientation(LinearLayout.VERTICAL);
        sshSection.addView(authChoices, new LinearLayout.LayoutParams(-1, -2));
        authOption(authChoices, 0, "Пароль", "Войти с паролем пользователя SSH", R.drawable.ic_lock);
        authOption(authChoices, 1, "SSH-ключ", "Выбрать файл или вставить приватный ключ", R.drawable.ic_key);
        LinearLayout loginGroup = credentialGroup(sshSection);
        spaceAbove(loginGroup, 10);
        userInput = groupedField(loginGroup, "Пользователь SSH", "root", InputType.TYPE_CLASS_TEXT);
        userInput.setText("root");
        passwordDivider = groupDivider(loginGroup);
        passwordInput = groupedField(loginGroup, "Пароль SSH", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordRow = loginGroup.getChildAt(loginGroup.getChildCount() - 1);
        LinearLayout keyFields = credentialGroup(sshSection);
        keyGroup = keyFields;
        privateKeyInput = groupedField(keyFields, "Приватный ключ SSH",
                "Вставьте PEM или OpenSSH ключ", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        privateKeyInput.setMinLines(2);
        privateKeyInput.setMaxLines(5);
        groupDivider(keyFields);
        passphraseInput = groupedField(keyFields, "Пароль ключа (если есть)", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        chooseKeyButton = button(sshSection, "Выбрать файл SSH-ключа", v -> pickPrivateKey());
        ui.secondary(chooseKeyButton);
        keyFileInfo = addText(sshSection, "", 12, false);
        spaceAbove(keyFileInfo, 6);
        updateAuthMode();
        connectButton = button(sshSection, "Проверить сервер", v -> connect(null, false));

        configSection = new LinearLayout(this);
        configSection.setOrientation(LinearLayout.VERTICAL);
        page.addView(configSection, new LinearLayout.LayoutParams(-1, -2));
        sectionLabel(configSection, "ТРАНСПОРТЫ КАНАЛА");
        addText(configSection, "Direct (TCP) будет запасным транспортом. Можно выбрать несколько дополнительных.", 13, false);
        yandexCheck = choiceTile(configSection, "Yandex Docs (Volga)",
                "Документ Яндекса", R.drawable.ic_yandex);
        yandexInput = field(configSection, "Ссылка на документ Яндекса", "https://docs.yandex.ru/edit/d/…",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        choiceFields.put(yandexCheck, (View) yandexInput.getTag());
        ((View) yandexInput.getTag()).setVisibility(View.GONE);
        mailruCheck = choiceTile(configSection, "Mail.ru Docs",
                "Документ в Облаке Mail.ru", R.drawable.ic_mailru);
        mailruInput = field(configSection, "Публичная ссылка Mail.ru", "https://cloud.mail.ru/public/…/…",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        choiceFields.put(mailruCheck, (View) mailruInput.getTag());
        ((View) mailruInput.getTag()).setVisibility(View.GONE);
        cupsCheck = choiceTile(configSection, "Cups.online", "Комнаты создаст приложение", R.drawable.ic_code);
        sectionLabel(configSection, "УСТАНОВКА");
        autoUpdateCheck = choiceTile(configSection, "Автоматически обновлять ядро",
                "Настройка общая для всех каналов VPS", R.drawable.ic_swap);
        addText(configSection, "Обновление проверяет node-v* релизы и откатывает неработающую версию.", 12, false);
        sudoInput = field(configSection, "Пароль sudo (если отличается от SSH)", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        planButton = button(configSection, "Показать план установки", v -> plan());
        planCard = new LinearLayout(this);
        planCard.setOrientation(LinearLayout.VERTICAL);
        planCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        planCard.setBackground(ui.rounded(ui.surface, ui.border, 12));
        LinearLayout.LayoutParams planCardParams = new LinearLayout.LayoutParams(-1, -2);
        planCardParams.topMargin = dp(10);
        planCardParams.bottomMargin = dp(8);
        configSection.addView(planCard, planCardParams);
        TextView planTitle = addText(planCard, "План установки", 16, true);
        planTitle.setTextColor(ui.text);
        planInfo = addText(planCard, "", 13, false);
        planActions = new LinearLayout(this);
        planActions.setOrientation(LinearLayout.VERTICAL);
        planCard.addView(planActions);
        changeButton = button(configSection, "Изменить параметры", v -> editPlan());
        applyButton = button(configSection, "Установить канал", v -> confirmApply());
        verifyButton = button(configSection, "Проверить соединение", v -> verify());
        saveButton = button(configSection, "Сохранить профиль", v -> saveProfile());
        removeButton = button(configSection, "Удалить созданный канал", v -> confirmRemove());
        ui.secondary(changeButton);
        ui.secondary(removeButton);
        updateControls();
    }

    private LinearLayout buildConnectedCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(ui.rounded(ui.surface, ui.border, 12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(8);
        cardParams.bottomMargin = dp(8);
        parent.addView(card, cardParams);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout bubble = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.argb(45, 47, 178, 108));
        bubble.setBackground(circle);
        ImageView check = new ImageView(this);
        check.setImageResource(R.drawable.ic_check);
        check.setImageTintList(ColorStateList.valueOf(Color.rgb(56, 190, 115)));
        bubble.addView(check, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        bubbleParams.rightMargin = dp(12);
        heading.addView(bubble, bubbleParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Сервер проверен");
        title.setTextSize(16);
        ui.heading(title);
        labels.addView(title);
        connectedHost = new TextView(this);
        connectedHost.setTextSize(12);
        ui.body(connectedHost);
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(-1, -2);
        hostParams.topMargin = dp(4);
        labels.addView(connectedHost, hostParams);
        heading.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(heading);

        View divider = new View(this);
        divider.setBackgroundColor(ui.border);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.topMargin = dp(14);
        dividerParams.bottomMargin = dp(12);
        card.addView(divider, dividerParams);
        serverInfo = new TextView(this);
        serverInfo.setTextSize(12);
        ui.body(serverInfo);
        card.addView(serverInfo);

        changeServerButton = button(card, "Сменить сервер", v -> changeServer());
        ui.secondary(changeServerButton);
        return card;
    }

    private View buildAppHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setContentDescription("Логотип OpenFlux");
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setImageResource(R.drawable.ic_openflux_foreground);
        logo.setBackground(ui.rounded(ui.accent, Color.TRANSPARENT, 10));
        logo.setClipToOutline(true);
        header.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titlesParams.leftMargin = dp(12);
        TextView name = new TextView(this);
        name.setText("OpenFlux");
        name.setTextSize(21);
        ui.heading(name);
        titles.addView(name);
        TextView subtitle = new TextView(this);
        subtitle.setText("Зашифрованный туннель");
        subtitle.setTextSize(12);
        ui.body(subtitle);
        titles.addView(subtitle);
        header.addView(titles, titlesParams);

        TextView version = new TextView(this);
        try {
            version.setText(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {
            version.setText("—");
        }
        version.setTextSize(10);
        version.setTextColor(ui.accent);
        version.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(9), dp(5), dp(9), dp(5));
        version.setBackground(ui.rounded(ui.selectedSurface, Color.TRANSPARENT, 12));
        header.addView(version);
        return header;
    }

    private LinearLayout buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(4), dp(6), dp(4), dp(6));
        nav.setBackground(ui.rounded(ui.surface, ui.border, 34));
        nav.setElevation(dp(4));
        nav.addView(navItem(R.drawable.ic_home, "Главная", 0),
                new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(navItem(R.drawable.ic_public, "Профили", 1),
                new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(navItem(R.drawable.ic_terminal, "Логи", 2),
                new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(navItem(R.drawable.ic_settings, "Настройки", 3),
                new LinearLayout.LayoutParams(0, -1, 1f));
        return nav;
    }

    private View navItem(int iconRes, String label, int page) {
        FrameLayout item = new FrameLayout(this);
        item.setContentDescription(label);
        item.setClickable(true);
        item.setFocusable(true);
        FrameLayout iconWrap = new FrameLayout(this);
        if (page == 1) iconWrap.setBackground(ui.rounded(ui.accent, Color.TRANSPARENT, 18));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(page == 1 ? Color.WHITE : ui.secondary));
        iconWrap.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        item.addView(iconWrap, new FrameLayout.LayoutParams(dp(48), dp(40), Gravity.CENTER));
        item.setOnClickListener(v -> {
            if (busy) {
                showWarning("Дождитесь завершения операции");
                return;
            }
            setResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_NAV_PAGE, page));
            finish();
        });
        return item;
    }

    private LinearLayout credentialGroup(LinearLayout parent) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(ui.rounded(ui.surface, ui.border, 12));
        group.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        parent.addView(group, params);
        return group;
    }

    private View groupDivider(LinearLayout group) {
        View divider = new View(this);
        divider.setBackgroundColor(ui.border);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.leftMargin = dp(16);
        group.addView(divider, params);
        return divider;
    }

    private void authOption(LinearLayout parent, int index, String title, String detail, int iconRes) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(70));
        row.setClickable(true);
        row.setFocusable(true);
        FrameLayout bubble = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.argb(45, 79, 124, 255));
        bubble.setBackground(circle);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(ui.accent));
        bubble.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        bubbleParams.rightMargin = dp(14);
        row.addView(bubble, bubbleParams);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(15);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        copy.addView(name);
        TextView description = new TextView(this);
        description.setText(detail);
        description.setTextSize(12);
        ui.body(description);
        copy.addView(description);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView marker = new ImageView(this);
        marker.setImageResource(R.drawable.ic_check);
        marker.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        marker.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ui.accent);
        marker.setBackground(dot);
        row.addView(marker, new LinearLayout.LayoutParams(dp(24), dp(24)));
        authRows[index] = row;
        authTitles[index] = name;
        authChecks[index] = marker;
        row.setOnClickListener(v -> {
            if (connected || busy || usePrivateKey == (index == 1)) return;
            usePrivateKey = index == 1;
            updateAuthMode();
            updateControls();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        if (index > 0) params.topMargin = dp(2);
        parent.addView(row, params);
    }

    private void updateAuthMode() {
        for (int i = 0; i < authRows.length; i++) {
            boolean selected = usePrivateKey == (i == 1);
            float top = dp(i == 0 ? 20 : 6), bottom = dp(i == 1 ? 20 : 6);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(selected ? tonal(0.18f) : ui.surface);
            shape.setStroke(dp(1), selected ? tonal(0.45f) : ui.border);
            shape.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
            authRows[i].setBackground(new RippleDrawable(
                    ColorStateList.valueOf(Color.argb(40, 79, 124, 255)), shape, null));
            authRows[i].setContentDescription((i == 0 ? "Пароль" : "SSH-ключ")
                    + (selected ? ", выбрано" : ", не выбрано"));
            authTitles[i].setTextColor(selected ? ui.accent : ui.text);
            authChecks[i].setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
        passwordDivider.setVisibility(usePrivateKey ? View.GONE : View.VISIBLE);
        passwordRow.setVisibility(usePrivateKey ? View.GONE : View.VISIBLE);
        keyGroup.setVisibility(usePrivateKey ? View.VISIBLE : View.GONE);
        chooseKeyButton.setVisibility(usePrivateKey ? View.VISIBLE : View.GONE);
        keyFileInfo.setVisibility(usePrivateKey && importedPrivateKey != null ? View.VISIBLE : View.GONE);
    }

    private int tonal(float strength) {
        return Color.rgb(
                (int) (Color.red(ui.surface) + (Color.red(ui.accent) - Color.red(ui.surface)) * strength),
                (int) (Color.green(ui.surface) + (Color.green(ui.accent) - Color.green(ui.surface)) * strength),
                (int) (Color.blue(ui.surface) + (Color.blue(ui.accent) - Color.blue(ui.surface)) * strength));
    }

    private EditText groupedField(LinearLayout group, String label, String hint, int inputType) {
        boolean multiline = (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setMinimumHeight(dp(multiline ? 104 : 64));
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(11);
        caption.setTextColor(ui.secondary);
        row.addView(caption);
        EditText input = new EditText(this);
        input.setInputType(inputType);
        input.setSingleLine(!multiline);
        input.setHint(hint);
        input.setTextSize(15);
        input.setTextColor(ui.text);
        input.setHintTextColor(ui.hint);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(0, dp(3), 0, 0);
        input.setGravity(multiline ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL | Gravity.START);
        row.addView(input, new LinearLayout.LayoutParams(-1, multiline ? dp(72) : dp(30)));
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        row.setOnClickListener(v -> input.requestFocus());
        return input;
    }

    private void sectionLabel(LinearLayout parent, String title) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(12);
        label.setTextColor(ui.secondary);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(20);
        params.bottomMargin = dp(8);
        parent.addView(label, params);
    }

    private TextView addText(LinearLayout parent, String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(bold ? ui.text : ui.secondary);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        parent.addView(view, params);
        return view;
    }

    private void spaceAbove(View view, int marginDp) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.topMargin = dp(marginDp);
        view.setLayoutParams(params);
    }

    private EditText field(LinearLayout parent, String label, String hint, int inputType) {
        boolean multiline = (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        FrameLayout wrapper = new FrameLayout(this);
        FrameLayout box = new FrameLayout(this);
        box.setBackground(ui.rounded(ui.surface, ui.border, 10));
        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(-1, -1);
        boxParams.topMargin = dp(8);
        wrapper.addView(box, boxParams);
        EditText input = new EditText(this);
        input.setSingleLine(!multiline);
        input.setInputType(inputType);
        input.setHint(hint);
        input.setTextSize(15);
        input.setTextColor(ui.text);
        input.setHintTextColor(ui.hint);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        input.setGravity(multiline ? android.view.Gravity.TOP | android.view.Gravity.START
                : android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        box.addView(input, new FrameLayout.LayoutParams(-1, -1));
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(11);
        caption.setTextColor(ui.secondary);
        caption.setBackground(new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint();
            @Override public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect bounds = getBounds();
                paint.setColor(ui.background);
                canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.exactCenterY(), paint);
                paint.setColor(ui.surface);
                canvas.drawRect(bounds.left, bounds.exactCenterY(), bounds.right, bounds.bottom, paint);
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        });
        caption.setPadding(dp(4), 0, dp(4), 0);
        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(-2, -2,
                android.view.Gravity.TOP | android.view.Gravity.START);
        captionParams.leftMargin = dp(14);
        wrapper.addView(caption, captionParams);
        input.setOnFocusChangeListener((view, focused) -> {
            box.setBackground(ui.rounded(ui.surface, focused ? ui.accent : ui.border, 10));
            caption.setTextColor(focused ? ui.accent : ui.secondary);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(multiline ? 112 : 68));
        params.topMargin = dp(8);
        params.bottomMargin = dp(6);
        parent.addView(wrapper, params);
        input.setTag(wrapper);
        return input;
    }

    private CheckBox choiceTile(LinearLayout parent, String title, String detail, int iconRes) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(70));
        row.setClickable(true);
        row.setFocusable(true);
        FrameLayout bubble = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.argb(45, 79, 124, 255));
        bubble.setBackground(circle);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(ui.accent));
        bubble.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), android.view.Gravity.CENTER));
        row.addView(bubble, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = dp(14);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(15);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        copy.addView(name);
        TextView description = new TextView(this);
        description.setText(detail);
        description.setTextSize(12);
        ui.body(description);
        copy.addView(description);
        row.addView(copy, copyParams);
        ImageView marker = new ImageView(this);
        marker.setImageResource(R.drawable.ic_check);
        marker.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        marker.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ui.accent);
        marker.setBackground(dot);
        row.addView(marker, new LinearLayout.LayoutParams(dp(24), dp(24)));
        CheckBox check = new CheckBox(this);
        check.setVisibility(View.GONE);
        check.setTag(row);
        row.addView(check, new LinearLayout.LayoutParams(0, 0));
        Runnable paint = () -> {
            boolean selected = check.isChecked();
            row.setBackground(ui.rounded(selected ? ui.selectedSurface : ui.surface,
                    selected ? ui.accent : ui.border, 20));
            name.setTextColor(selected ? ui.accent : ui.text);
            marker.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            row.setContentDescription(title + (selected ? ", выбрано" : ", не выбрано"));
            View field = choiceFields.get(check);
            if (field != null) field.setVisibility(selected ? View.VISIBLE : View.GONE);
        };
        check.setOnCheckedChangeListener((button, selected) -> paint.run());
        row.setOnClickListener(v -> { if (check.isEnabled()) check.setChecked(!check.isChecked()); });
        paint.run();
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(4);
        parent.addView(row, rowParams);
        return check;
    }

    private Button button(LinearLayout parent, String title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        ui.primary(button);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.topMargin = dp(8);
        parent.addView(button, params);
        return button;
    }

    private void updateControls() {
        sshSection.setVisibility(connected ? View.GONE : View.VISIBLE);
        connectedCard.setVisibility(connected ? View.VISIBLE : View.GONE);
        changeServerButton.setVisibility(connected && !applied && savedProfileId == 0 ? View.VISIBLE : View.GONE);
        changeServerButton.setEnabled(!busy);
        configSection.setVisibility(connected ? View.VISIBLE : View.GONE);
        connectButton.setEnabled(!busy && !connected);
        planCard.setVisibility(planned ? View.VISIBLE : View.GONE);
        planButton.setVisibility(planned || applied || savedProfileId != 0 ? View.GONE : View.VISIBLE);
        planButton.setEnabled(!busy && connected);
        changeButton.setVisibility(planned && !applied && savedProfileId == 0 ? View.VISIBLE : View.GONE);
        changeButton.setEnabled(!busy);
        applyButton.setVisibility(planned && !applied ? View.VISIBLE : View.GONE);
        applyButton.setEnabled(!busy);
        verifyButton.setVisibility(applied || savedProfileId != 0 ? View.VISIBLE : View.GONE);
        verifyButton.setEnabled(!busy);
        saveButton.setVisibility(applied || savedProfileId != 0 ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy);
        saveButton.setText(verified ? "Сохранить профиль" : "Сохранить без проверки");
        removeButton.setVisibility(applied || savedProfileId != 0 ? View.VISIBLE : View.GONE);
        removeButton.setEnabled(!busy);
        for (EditText input : new EditText[]{hostInput, sshPortInput, userInput})
            input.setEnabled(!connected && !busy);
        passwordInput.setEnabled(!connected && !busy && !usePrivateKey);
        privateKeyInput.setEnabled(!connected && !busy && usePrivateKey);
        passphraseInput.setEnabled(!connected && !busy && usePrivateKey);
        chooseKeyButton.setEnabled(!connected && !busy && usePrivateKey);
        for (LinearLayout row : authRows) row.setEnabled(!connected && !busy);
        boolean editable = connected && !planned && !applied && savedProfileId == 0 && !busy;
        for (EditText input : new EditText[]{yandexInput, mailruInput}) input.setEnabled(editable);
        sudoInput.setEnabled(editable);
        ((View) sudoInput.getTag()).setAlpha(editable ? 1f : 0.55f);
        for (CheckBox check : new CheckBox[]{yandexCheck, mailruCheck, cupsCheck, autoUpdateCheck}) {
            check.setEnabled(editable);
            ((View) check.getTag()).setAlpha(editable ? 1f : 0.55f);
        }
    }

    private interface Job { JSONObject run() throws Exception; }

    private void runJob(String label, Job job, Consumer<JSONObject> done) {
        if (busy) return;
        busy = true;
        updateControls();
        showProgress(label);
        worker.execute(() -> {
            JSONObject result;
            try {
                result = job.run();
            } catch (Exception e) {
                result = new JSONObject();
                try { result.put("ok", false).put("error", e.getMessage()); } catch (Exception ignored) { }
            }
            JSONObject answer = result;
            runOnUiThread(() -> {
                if (destroyed) return;
                dismissProgress();
                busy = false;
                updateControls();
                done.accept(answer);
            });
        });
    }

    private static JSONObject response(String json) throws Exception { return new JSONObject(json); }

    private static boolean ok(JSONObject answer) { return answer.optBoolean("ok", false); }

    private void showError(JSONObject answer) {
        showProblem("Не удалось выполнить", answer.optString("error", "Неизвестная ошибка"));
    }

    private void showWarning(String message) {
        showProblem("Внимание", message);
    }

    private void showProblem(String title, String message) {
        if (message == null || message.trim().isEmpty()) message = "Неизвестная ошибка";
        ui.dialog().setTitle(title)
                .setMessage(message)
                .setPositiveButton("Понятно", null)
                .show();
    }

    private void showSuccess(String title, String message, Runnable next) {
        ui.dialog().setTitle(title)
                .setMessage(message)
                .setPositiveButton("Продолжить", (dialog, which) -> {
                    if (next != null) next.run();
                })
                .show();
    }

    private void showProgress(String label) {
        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(18));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ui.accent));
        content.addView(spinner, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(14);
        text.setTextColor(ui.text);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1f);
        textParams.leftMargin = dp(18);
        content.addView(text, textParams);
        progressDialog = ui.dialog().setTitle("Подождите")
                .setView(content)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private void scrollTo(View target) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        target.getDrawingRect(bounds);
        scroll.offsetDescendantRectToMyCoords(target, bounds);
        scroll.smoothScrollTo(0, Math.max(0, bounds.top - dp(12)));
    }

    private void showPlanActions(JSONArray actions) {
        planActions.removeAllViews();
        for (int i = 0; actions != null && i < actions.length(); i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.topMargin = dp(i == 0 ? 8 : 12);
            planActions.addView(row, rowParams);
            TextView number = new TextView(this);
            number.setText(String.format(Locale.ROOT, "%02d", i + 1));
            number.setTextSize(12);
            number.setTextColor(ui.accent);
            number.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            row.addView(number, new LinearLayout.LayoutParams(dp(32), -2));
            TextView action = new TextView(this);
            action.setText(actions.optString(i));
            action.setTextSize(13);
            action.setTextColor(ui.secondary);
            row.addView(action, new LinearLayout.LayoutParams(0, -2, 1f));
        }
    }

    private int port(EditText input, int fallback) {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) return fallback;
        try {
            int value = Integer.parseInt(raw);
            if (value >= 1 && value <= 65535) return value;
        } catch (NumberFormatException ignored) { }
        return -1;
    }

    private void pickPrivateKey() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, SSH_KEY_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SSH_KEY_REQUEST || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IllegalArgumentException("Не удалось открыть файл");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                if (bytes.size() + count > 65536) throw new IllegalArgumentException("Файл ключа слишком большой");
                bytes.write(chunk, 0, count);
            }
            String key = bytes.toString(StandardCharsets.UTF_8.name()).trim();
            if (!key.startsWith("-----BEGIN ") || !key.contains("PRIVATE KEY-----")) {
                throw new IllegalArgumentException("Выберите приватный ключ в формате PEM или OpenSSH");
            }
            importedPrivateKey = key + "\n";
            privateKeyInput.setText("");
            keyFileInfo.setText("Приватный ключ загружен из файла");
            updateAuthMode();
        } catch (Exception e) {
            showProblem("Не удалось загрузить ключ", e.getMessage());
        }
    }

    private String hostKeyName(String host, int sshPort) {
        return "vps_host_key_" + host.toLowerCase(Locale.ROOT) + ":" + sshPort;
    }

    private void connect(String acceptedKey, boolean rememberKey) {
        String host = hostInput.getText().toString().trim();
        int sshPort = port(sshPortInput, 22);
        String user = userInput.getText().toString().trim();
        if (sshPort < 0) {
            showWarning("Порт SSH должен быть от 1 до 65535");
            return;
        }
        if (host.isEmpty() || user.isEmpty()) {
            showWarning("Укажите адрес сервера и пользователя SSH");
            return;
        }
        String password = usePrivateKey ? "" : passwordInput.getText().toString();
        String typedKey = privateKeyInput.getText().toString().trim();
        String privateKey = !usePrivateKey ? "" : typedKey.isEmpty()
                ? (importedPrivateKey == null ? "" : importedPrivateKey) : typedKey;
        String passphrase = usePrivateKey ? passphraseInput.getText().toString() : "";
        if (usePrivateKey && privateKey.isEmpty()) {
            showWarning("Выберите файл SSH-ключа или вставьте приватный ключ");
            return;
        }
        if (!usePrivateKey && password.isEmpty()) {
            showWarning("Укажите пароль SSH");
            return;
        }
        String keyName = hostKeyName(host, sshPort);
        String trusted = acceptedKey != null ? acceptedKey : secureSettings.getString(keyName, "");
        runJob("Подключение по SSH…", () -> {
            JSONObject answer = response(Mobile.nodeConnect(host, sshPort, user, password, privateKey, passphrase, trusted));
            if (!ok(answer)) return answer;
            JSONObject channel = response(Mobile.nodeNewChannel());
            if (!ok(channel)) return channel;
            answer.put("channelId", channel.getString("id"));
            answer.put("channelKey", channel.getString("key"));
            return answer;
        }, answer -> {
            if (ok(answer)) {
                boolean fingerprintSaved = !rememberKey || secureSettings.putString(keyName, trusted);
                connected = true;
                channelId = answer.optString("channelId");
                channelKey = answer.optString("channelKey");
                connectedHost.setText(host + ":" + sshPort + " · " + user + " · "
                        + (usePrivateKey ? "SSH-ключ" : "пароль"));
                JSONObject probe = answer.optJSONObject("probe");
                if (probe != null) {
                    serverInfo.setText(probe.optString("os") + " / "
                            + probe.optString("arch") + " · каналов: "
                            + (probe.optJSONArray("channels") == null ? 0 : probe.optJSONArray("channels").length()));
                    autoUpdateCheck.setChecked(probe.optBoolean("autoupdate", false));
                } else {
                    serverInfo.setText("Проверка сервера завершена");
                }
                passwordInput.setText("");
                privateKeyInput.setText("");
                passphraseInput.setText("");
                importedPrivateKey = null;
                keyFileInfo.setText("");
                intro.setText("Выберите транспорты канала, затем изучите план установки.");
                updateControls();
                scroll.post(() -> scroll.smoothScrollTo(0, 0));
                if (fingerprintSaved) {
                    showSuccess("Сервер проверен", "SSH-подключение к " + host
                            + " установлено. Теперь выберите транспорты канала.", null);
                } else {
                    showProblem("Сервер подключён", "Отпечаток SSH не сохранился. При следующем входе сверьте его снова.");
                }
                return;
            }
            if (answer.has("hostKey")) {
                String fingerprint = answer.optString("hostKey");
                boolean mismatch = answer.optBoolean("mismatch");
                ui.dialog()
                        .setTitle(mismatch ? "Ключ сервера изменился" : "Новый сервер SSH")
                        .setMessage((mismatch ? "Ранее сохранённый ключ не совпадает. " : "")
                                + "Сверьте отпечаток с сервером через другой доверенный канал:\n\n"
                                + fingerprint)
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton(mismatch ? "Я сверил, обновить" : "Я сверил, доверять",
                                (dialog, which) -> connect(fingerprint, true))
                        .show();
            } else {
                showError(answer);
            }
        });
    }

    private void changeServer() {
        if (planned) {
            ui.dialog().setTitle("Сменить сервер?")
                    .setMessage("Текущий план установки и выбранные транспорты будут сброшены.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Сменить", (dialog, which) -> disconnectAndEdit())
                    .show();
        } else {
            disconnectAndEdit();
        }
    }

    private void disconnectAndEdit() {
        runJob("Закрытие SSH-соединения…", () -> {
            Mobile.nodeDisconnect();
            return new JSONObject().put("ok", true);
        }, answer -> {
            if (!ok(answer)) { showError(answer); return; }
            connected = false;
            planned = false;
            channelId = null;
            channelKey = null;
            channelPort = 0;
            cupsRooms = "";
            transportsJson = null;
            shareLink = null;
            yandexCheck.setChecked(false);
            mailruCheck.setChecked(false);
            cupsCheck.setChecked(false);
            autoUpdateCheck.setChecked(false);
            yandexInput.setText("");
            mailruInput.setText("");
            sudoInput.setText("");
            planInfo.setText("");
            planActions.removeAllViews();
            planSummary = null;
            intro.setText("Сначала подключитесь к серверу по SSH. Приложение покажет план изменений перед установкой.");
            updateAuthMode();
            updateControls();
            scroll.post(() -> scroll.smoothScrollTo(0, 0));
        });
    }

    private void plan() {
        String yandex = yandexInput.getText().toString().trim();
        String mailru = mailruInput.getText().toString().trim();
        boolean withYandex = yandexCheck.isChecked();
        boolean withMailru = mailruCheck.isChecked();
        boolean withCups = cupsCheck.isChecked();
        boolean autoUpdate = autoUpdateCheck.isChecked();
        if (withYandex && yandex.isEmpty()) {
            showWarning("Укажите ссылку на документ Яндекса");
            return;
        }
        if (withMailru && mailru.isEmpty()) {
            showWarning("Укажите публичную ссылку Mail.ru");
            return;
        }
        String oldRooms = cupsRooms;
        runJob("Подготовка плана установки…", () -> {
            String rooms = oldRooms;
            if (withCups && rooms.isEmpty()) {
                JSONObject created = response(Mobile.nodeCreateCupsRooms());
                if (!ok(created)) return created;
                rooms = created.getString("rooms");
            }
            JSONArray transports = new JSONArray();
            if (withYandex) transports.put(new JSONObject().put("type", "vyandex").put("url", yandex));
            if (withMailru) transports.put(new JSONObject().put("type", "mailru").put("url", mailru));
            if (withCups) transports.put(new JSONObject().put("type", "cupsonline").put("url", rooms));
            JSONObject answer = response(Mobile.nodePlan(channelId, 0, transports.toString(), autoUpdate));
            answer.put("cupsRooms", rooms);
            if (ok(answer)) answer.put("transportsJson", transports.toString());
            return answer;
        }, answer -> {
            cupsRooms = answer.optString("cupsRooms", cupsRooms);
            if (!ok(answer)) { showError(answer); return; }
            JSONObject plan = answer.optJSONObject("plan");
            int plannedPort = plan == null ? 0 : plan.optInt("port");
            if (plannedPort < 1 || plannedPort > 65535) {
                showWarning("Сервер не указал порт канала");
                return;
            }
            channelPort = plannedPort;
            transportsJson = answer.optString("transportsJson");
            cupsRooms = answer.optString("cupsRooms", "");
            selectedAutoUpdate = autoUpdate;
            StringBuilder summary = new StringBuilder("Канал " + channelId + " · порт " + channelPort);
            JSONArray actions = plan.optJSONArray("actions");
            for (int i = 0; actions != null && i < actions.length(); i++) summary.append("\n• ").append(actions.optString(i));
            planSummary = summary.toString();
            planInfo.setText("Канал " + channelId + " · порт " + channelPort);
            showPlanActions(actions);
            planned = true;
            updateControls();
            showSuccess("План готов", "Проверьте список изменений перед установкой канала.",
                    () -> scroll.post(() -> scrollTo(planCard)));
        });
    }

    private void editPlan() {
        planned = false;
        planInfo.setText("");
        planActions.removeAllViews();
        planSummary = null;
        updateControls();
        scroll.post(() -> scrollTo(configSection));
    }

    private void confirmApply() {
        ui.dialog()
                .setTitle("Установить канал на VPS?")
                .setMessage(planSummary + "\n\nПриложение изменит настройки сервера согласно этому плану.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Установить", (dialog, which) -> apply())
                .show();
    }

    private void apply() {
        // The server may complete the install even if the app is killed before
        // SSH returns. Save the key and link before making that remote change.
        if (!persistProfile(false)) return;
        String sudo = sudoInput.getText().toString();
        runJob("Установка канала на сервере…", () -> response(Mobile.nodeApply(
                channelId, transportsJson, channelKey, channelPort, selectedAutoUpdate, sudo)), answer -> {
            if (!ok(answer)) {
                showProblem("Установка не подтверждена", answer.optString("error", "Неизвестная ошибка")
                        + "\n\nЧерновик профиля сохранён. Проверьте сервер перед повторной попыткой.");
                return;
            }
            applied = true;
            updateControls();
            showSuccess("Канал установлен", "Профиль сохранён как непроверенный. Проверьте соединение через новую ноду.",
                    () -> scroll.post(() -> scrollTo(verifyButton)));
        });
    }

    private void verify() {
        if (OpenFluxTunnelService.isRunning() || OpenFluxProxyService.isRunning()
                || OpenFluxExitService.isRunning()) {
            showWarning("Отключите OpenFlux перед проверкой нового канала");
            return;
        }
        String host = hostInput.getText().toString().trim();
        verifying = true;
        captchaShown = false;
        handler.post(captchaPoll);
        runJob("Проверка соединения через ноду…", () -> {
            String link = Mobile.nodeShareLink("Нода " + host, transportsJson, channelKey, host, channelPort);
            Profile candidate = Profile.fromShare(new JSONObject(Mobile.parseShareLink(link)));
            JSONObject answer = response(Mobile.nodeVerify(candidate.sessionTransportsJson(null), channelKey, host, 90));
            if (ok(answer)) answer.put("shareLink", link);
            return answer;
        }, answer -> {
            verifying = false;
            handler.removeCallbacks(captchaPoll);
            if (!ok(answer)) { showError(answer); return; }
            shareLink = answer.optString("shareLink");
            applied = true;
            verified = true;
            primaryLive = answer.optBoolean("primary", true);
            if (!persistProfile(primaryLive)) return;
            updateControls();
            if (primaryLive) {
                showSuccess("Соединение проверено", "Трафик вышел с IP сервера. Профиль можно сохранить.",
                        () -> scroll.post(() -> scrollTo(saveButton)));
            } else {
                showProblem("Основной транспорт не подтверждён",
                        "Direct работает, но основной транспорт не подтвердился. Профиль можно сохранить и проверить позже.");
            }
        });
    }

    private void saveProfile() {
        if (!verified || !primaryLive) {
            ui.dialog()
                    .setTitle(verified ? "Основной транспорт не проверен" : "Сохранить без проверки?")
                    .setMessage(verified
                            ? "Direct работает, но основной транспорт не подтвердился. Профиль можно сохранить и проверить позже."
                            : (applied ? "Канал установлен" : "Установка канала не подтверждена")
                                    + ", а соединение не проверено. Профиль может не подключиться.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Сохранить", (dialog, which) -> finishProfile())
                    .show();
        } else {
            finishProfile();
        }
    }

    private void finishProfile() {
        String host = hostInput.getText().toString().trim();
        try {
            String link = shareLink != null ? shareLink
                    : Mobile.nodeShareLink("Нода " + host, transportsJson, channelKey, host, channelPort);
            if (!persistProfile(true)) return;
            ProfileStore store = new ProfileStore(secureSettings);
            store.setSelectedId(savedProfileId);
            if (store.getSelectedId() != savedProfileId) {
                showProblem("Не удалось выбрать профиль", "Сохранённый профиль не удалось сделать активным.");
                return;
            }
            ui.dialog()
                    .setTitle("Нода создана")
                    .setMessage("Профиль сохранён. Ссылку подключения можно скопировать для другого устройства; в ней находится ключ канала.")
                    .setNegativeButton("Готово", (dialog, which) -> complete())
                    .setPositiveButton("Копировать ссылку", (dialog, which) -> {
                        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlux", link));
                        showSuccess("Ссылка скопирована", "Ссылку можно отправить на другое устройство.", this::complete);
                    })
                    .setOnCancelListener(dialog -> complete())
                    .show();
        } catch (Exception e) {
            showProblem("Не удалось создать профиль", e.getMessage());
        }
    }

    private boolean persistProfile(boolean finalName) {
        String host = hostInput.getText().toString().trim();
        try {
            String link = shareLink != null ? shareLink
                    : Mobile.nodeShareLink("Нода " + host, transportsJson, channelKey, host, channelPort);
            Profile profile = Profile.fromShare(new JSONObject(Mobile.parseShareLink(link)));
            profile.id = savedProfileId != 0 ? savedProfileId : System.currentTimeMillis();
            if (!finalName || !verified || !primaryLive) profile.name += " (проверить)";
            ProfileStore store = new ProfileStore(secureSettings);
            List<Profile> profiles = store.load();
            profiles.removeIf(saved -> saved.id == profile.id);
            profiles.add(profile);
            store.save(profiles);
            for (Profile saved : store.load()) {
                if (saved.id == profile.id) {
                    savedProfileId = profile.id;
                    shareLink = link;
                    return true;
                }
            }
        } catch (Exception e) {
            showProblem("Не удалось сохранить профиль", e.getMessage());
            return false;
        }
        showProblem("Не удалось сохранить профиль", "Не удалось сохранить профиль на устройстве.");
        return false;
    }

    private void removeSavedProfile() {
        if (savedProfileId == 0) return;
        ProfileStore store = new ProfileStore(secureSettings);
        List<Profile> profiles = store.load();
        profiles.removeIf(saved -> saved.id == savedProfileId);
        store.save(profiles);
        savedProfileId = 0;
    }

    private void complete() {
        setResult(RESULT_OK);
        finish();
    }

    private void confirmRemove() {
        ui.dialog()
                .setTitle("Удалить созданный канал?")
                .setMessage("Канал " + channelId + " будет удалён с VPS. Другие каналы останутся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    String sudo = sudoInput.getText().toString();
                    runJob("Удаление канала…",
                        () -> response(Mobile.nodeRemove(channelId, sudo)), answer -> {
                            if (!ok(answer)) { showError(answer); return; }
                            removeSavedProfile();
                            showSuccess("Канал удалён", "Созданный канал удалён с VPS.", this::finish);
                        });
                })
                .show();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        dismissProgress();
        verifying = false;
        handler.removeCallbacks(captchaPoll);
        Mobile.nodeCancelVerify();
        worker.execute(Mobile::nodeDisconnect);
        worker.shutdown();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
