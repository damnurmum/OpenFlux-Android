package io.openflux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.openflux.bridge.mobile.Mobile;

// "Создать свою ноду": installs a new, independent OpenFlux channel on the
// user's VDS and hands back a verified profile. Steps: SSH to the server,
// create the channel's Yandex document, show what will change on the
// server, install, prove the channel end to end, then save the profile here
// or share it with another device as a QR. Running it again on the same
// server adds another channel (a new user) next to the existing ones.
//
// The SSH password, private key and sudo password live only in this
// activity's fields for the length of the wizard; they are never saved or
// logged. The Yandex login stays inside YandexDocActivity's WebView and is
// wiped when it closes.
public final class NodeWizardActivity extends Activity {
    static final String EXTRA_PROFILE_ID = "profile_id";

    private static final int REQUEST_DOC = 1;
    private static final int REQUEST_KEY_FILE = 2;
    private static final String PREFS = "node_wizard";
    private static final String KEY_SERVERS = "servers";
    private static final int VERIFY_TIMEOUT_SEC = 150;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private int background, surface, text, secondary, border, accent, danger;
    private boolean dark;

    private LinearLayout content;
    private ProgressBar progress;
    private TextView statusView;
    private boolean busy;

    // Step 1: server.
    private EditText hostInput, portInput, userInput, passwordInput, keyInput, passphraseInput;
    private boolean useKey;
    private JSONObject probe;
    // Step 2: document.
    private String channelId, channelKey, documentUrl;
    private EditText nameInput, docInput;
    private String docWarning = "";
    // The Yandex sign-in from YandexDocActivity (a Cookie header), handed to
    // the node at install and then dropped. Never saved.
    private String yandexCookies = "";
    // Step 3: plan.
    private JSONObject plan;
    private EditText sudoInput;
    // Step 4/5: result.
    private boolean installed;
    private String shareLink;
    private String verifiedIp = "";
    private boolean primaryUp;
    private long savedProfileId = -1;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences settings = getSharedPreferences(MainActivity.SETTINGS_PREFS_NAME, MODE_PRIVATE);
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        dark = settings.getBoolean("dark_mode", systemDark);
        if (dark) {
            background = Color.rgb(8, 11, 18);
            surface = Color.rgb(19, 23, 34);
            text = Color.WHITE;
            secondary = Color.rgb(138, 146, 166);
            border = Color.rgb(42, 52, 70);
        } else {
            background = Color.rgb(248, 249, 250);
            surface = Color.WHITE;
            text = Color.rgb(32, 33, 36);
            secondary = Color.rgb(95, 99, 104);
            border = Color.rgb(218, 220, 224);
        }
        accent = Color.rgb(79, 124, 255);
        danger = Color.rgb(217, 48, 37);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        if (!dark) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        setContentView(root);
        showServerStep();
    }

    @Override protected void onDestroy() {
        worker.execute(Mobile::nodeDisconnect);
        worker.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (busy) return;
        if (installed && savedProfileId < 0) {
            dialog().setTitle("Выйти без сохранения?")
                    .setMessage("Канал уже установлен на сервере, но профиль не сохранён.")
                    .setPositiveButton("Выйти", (d, w) -> finish())
                    .setNegativeButton("Остаться", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    // ---- step 1: server -----------------------------------------------------

    private void showServerStep() {
        reset("Своя нода", "Шаг 1 из 4. Сервер (VDS) с Linux и systemd: Debian, Ubuntu и похожие.");
        JSONArray recent = recentServers();
        if (recent.length() > 0) {
            content.addView(label("Недавние серверы: новый канал встанет рядом с уже установленными"));
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < recent.length(); i++) {
                JSONObject s = recent.optJSONObject(i);
                if (s == null) continue;
                Button chip = secondaryButton(s.optString("user") + "@" + s.optString("host") + ":" + s.optInt("port", 22), () -> {
                    hostInput.setText(s.optString("host"));
                    portInput.setText(String.valueOf(s.optInt("port", 22)));
                    userInput.setText(s.optString("user"));
                    passwordInput.requestFocus();
                });
                chips.addView(chip, spaced(-1, -2, 6));
            }
            content.addView(chips);
        }
        hostInput = field("Адрес сервера (IP или домен)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        portInput = field("SSH-порт", InputType.TYPE_CLASS_NUMBER);
        portInput.setText("22");
        userInput = field("Логин", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        userInput.setText("root");
        passwordInput = field("Пароль", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput = field("Приватный ключ (OpenSSH/PEM)", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keyInput.setMinLines(3);
        keyInput.setTypeface(Typeface.MONOSPACE);
        keyInput.setTextSize(11);
        passphraseInput = field("Пароль ключа (если есть)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button pickKey = secondaryButton("Выбрать файл ключа", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(pick, REQUEST_KEY_FILE);
        });

        LinearLayout keyGroup = new LinearLayout(this);
        keyGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout segmented = new LinearLayout(this);
        Button byPassword = secondaryButton("Пароль", null);
        Button byKey = secondaryButton("Ключ", null);
        Runnable applyMode = () -> {
            passwordInput.setVisibility(useKey ? View.GONE : View.VISIBLE);
            keyGroup.setVisibility(useKey ? View.VISIBLE : View.GONE);
            styleToggle(byPassword, !useKey);
            styleToggle(byKey, useKey);
        };
        byPassword.setOnClickListener(v -> { useKey = false; applyMode.run(); });
        byKey.setOnClickListener(v -> { useKey = true; applyMode.run(); });
        segmented.addView(byPassword, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(44), 1f);
        right.leftMargin = dp(8);
        segmented.addView(byKey, right);

        content.addView(hostInput, spaced(-1, -2, 12));
        content.addView(portInput, spaced(-1, -2, 8));
        content.addView(userInput, spaced(-1, -2, 8));
        content.addView(label("Вход"));
        content.addView(segmented, spaced(-1, -2, 4));
        content.addView(passwordInput, spaced(-1, -2, 8));
        keyGroup.addView(keyInput, spaced(-1, -2, 0));
        keyGroup.addView(pickKey, spaced(-1, -2, 6));
        keyGroup.addView(passphraseInput, spaced(-1, -2, 6));
        content.addView(keyGroup, spaced(-1, -2, 8));
        applyMode.run();
        content.addView(note("Пароль и ключ нужны только на время установки: приложение их не сохраняет."));
        content.addView(primaryButton("Подключиться", () -> connect(null)), spaced(-1, dp(52), 16));
        addStatus();
    }

    private void connect(String trustedKey) {
        String host = hostInput.getText().toString().trim();
        String user = userInput.getText().toString().trim();
        int port = parsePort(portInput.getText().toString().trim());
        String password = useKey ? "" : passwordInput.getText().toString();
        String key = useKey ? keyInput.getText().toString().trim() : "";
        String passphrase = useKey ? passphraseInput.getText().toString() : "";
        if (host.isEmpty() || user.isEmpty() || port <= 0) {
            setStatus("Укажите адрес, порт и логин", true);
            return;
        }
        if (useKey ? key.isEmpty() : password.isEmpty()) {
            setStatus(useKey ? "Вставьте приватный ключ или выберите файл" : "Введите пароль", true);
            return;
        }
        String hostKey = trustedKey != null ? trustedKey : knownHostKey(host, port);
        run("Подключаюсь к серверу и проверяю его…",
                () -> Mobile.nodeConnect(host, port, user, password, key, passphrase, hostKey),
                r -> {
                    if (r.optBoolean("ok")) {
                        probe = r.optJSONObject("probe");
                        rememberServer(host, port, user);
                        showDocumentStep();
                        return;
                    }
                    String fp = r.optString("hostKey");
                    if (r.optBoolean("trust") && !fp.isEmpty()) {
                        dialog().setTitle("Новый сервер")
                                .setMessage("Отпечаток ключа сервера:\n\n" + fp
                                        + "\n\nЕсли панель провайдера показывает отпечаток, сверьте его. Доверять этому серверу?")
                                .setPositiveButton("Доверять", (d, w) -> {
                                    saveHostKey(host, port, fp);
                                    connect(fp);
                                })
                                .setNegativeButton("Отмена", null)
                                .show();
                        return;
                    }
                    if (r.optBoolean("mismatch") && !fp.isEmpty()) {
                        dialog().setTitle("Ключ сервера изменился")
                                .setMessage("Сервер представился другим ключом:\n\n" + fp
                                        + "\n\nТак бывает после переустановки системы, но так же выглядит и подмена сервера. "
                                        + "Продолжайте, только если вы сами переустанавливали сервер.")
                                .setPositiveButton("Сервер переустановлен", (d, w) -> {
                                    saveHostKey(host, port, fp);
                                    connect(fp);
                                })
                                .setNegativeButton("Отмена", null)
                                .show();
                        return;
                    }
                    setStatus(r.optString("error", "Не удалось подключиться"), true);
                });
    }

    // ---- step 2: document ---------------------------------------------------

    private void showDocumentStep() {
        if (channelId == null) {
            try {
                JSONObject c = new JSONObject(Mobile.nodeNewChannel());
                channelId = c.getString("id");
                channelKey = c.getString("key");
            } catch (Exception e) {
                setStatus("Не удалось создать ключ канала", true);
                return;
            }
        }
        reset("Документ канала", "Шаг 2 из 4. Через этот документ Яндекса телефон и нода обмениваются зашифрованным трафиком.");
        if (probe != null) {
            content.addView(note("Сервер: " + probe.optString("os") + ", " + probe.optString("arch")
                    + channelsSummary(probe.optJSONArray("channels"))));
        }
        nameInput = field("Название профиля", InputType.TYPE_CLASS_TEXT);
        nameInput.setText("Нода " + hostInput.getText().toString().trim());
        content.addView(nameInput, spaced(-1, -2, 12));
        content.addView(note("Канал: " + channelId + ". Для него создан отдельный ключ шифрования, "
                + "его знают только этот телефон и нода."));
        content.addView(primaryButton("Войти в Яндекс и создать документ", () -> {
            Intent intent = new Intent(this, YandexDocActivity.class)
                    .putExtra(YandexDocActivity.EXTRA_FILENAME, "openflux-" + channelId);
            startActivityForResult(intent, REQUEST_DOC);
        }), spaced(-1, dp(52), 16));
        content.addView(note("Документ появится в папке openflux на вашем Яндекс Диске с доступом «Редактирование» по ссылке. "
                + "Вход в Яндекс нужен только для этого: после создания документа приложение забывает сессию."));
        content.addView(label("Или вставьте ссылку на свой пустой документ"));
        docInput = field("https://docs.yandex.ru/edit/d/…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (documentUrl != null) docInput.setText(documentUrl);
        content.addView(docInput, spaced(-1, -2, 4));
        content.addView(secondaryButton("Проверить ссылку", () -> checkDocument(docInput.getText().toString().trim())),
                spaced(-1, dp(48), 8));
        addStatus();
    }

    private void checkDocument(String url) {
        String clean = url.replaceAll("[?#].*$", "").trim();
        if (!clean.matches("^https://(docs|disk)\\.yandex\\.[a-z]{2,3}/edit/d/[A-Za-z0-9_-]{16,200}$")) {
            setStatus("Нужна ссылка вида https://docs.yandex.ru/edit/d/…", true);
            return;
        }
        documentUrl = clean;
        if (docInput != null) docInput.setText(clean);
        run("Проверяю документ так, как его увидит нода…", () -> Mobile.nodeCheckDocument(clean), r -> {
            if (r.optBoolean("ok")) {
                docWarning = "";
                showPlanStep();
            } else if (r.optBoolean("captcha")) {
                // Yandex challenges this phone's address; the node checks
                // from its own address, and the final verification decides.
                docWarning = "Яндекс попросил проверку у телефона, поэтому документ проверит сама нода при запуске.";
                showPlanStep();
            } else {
                setStatus(r.optString("error"), true);
            }
        });
    }

    // ---- step 3: plan ---------------------------------------------------------

    private void showPlanStep() {
        boolean withCookies = !yandexCookies.isEmpty();
        run("Спрашиваю сервер, что изменится…", () -> Mobile.nodePlan(channelId, 0, withCookies), r -> {
            if (!r.optBoolean("ok")) {
                setStatus(r.optString("error"), true);
                return;
            }
            plan = r.optJSONObject("plan");
            renderPlan();
        });
    }

    private void renderPlan() {
        reset("Будут изменения", "Шаг 3 из 4. На сервере будет сделано только это:");
        JSONArray actions = plan.optJSONArray("actions");
        LinearLayout list = card();
        for (int i = 0; actions != null && i < actions.length(); i++) {
            TextView item = new TextView(this);
            item.setText("•  " + actions.optString(i));
            item.setTextColor(text);
            item.setTextSize(14);
            item.setPadding(0, dp(4), 0, dp(4));
            list.addView(item);
        }
        content.addView(list, spaced(-1, -2, 12));
        JSONArray untouched = plan.optJSONArray("untouched");
        String keep = untouched != null && untouched.length() > 0
                ? "Каналы, которые уже есть на сервере, не изменятся: " + join(untouched) + ". "
                : "";
        content.addView(note(keep + "Остальные программы на сервере (Docker, VPN, панели) мастер не трогает."));
        if (!yandexCookies.isEmpty()) {
            content.addView(note("Нода будет открывать документ под вашим аккаунтом Яндекса: так Яндекс не требует "
                    + "от сервера капчу. Кто получит root на сервере, получит и доступ к этому аккаунту, поэтому "
                    + "лучше входить отдельным аккаунтом для документов."));
        }
        if (!docWarning.isEmpty()) content.addView(note(docWarning));
        if ("password".equals(probe.optString("sudo"))) {
            sudoInput = field("Пароль sudo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            if (!useKey) sudoInput.setText(passwordInput.getText().toString());
            content.addView(label("Для установки нужны права root: пароль sudo пользователя " + userInput.getText().toString().trim()));
            content.addView(sudoInput, spaced(-1, -2, 4));
        } else {
            sudoInput = null;
        }
        content.addView(primaryButton("Установить ноду", this::install), spaced(-1, dp(52), 16));
        content.addView(secondaryButton("Назад", this::showDocumentStep), spaced(-1, dp(48), 8));
        addStatus();
    }

    private void install() {
        String sudo = sudoInput != null ? sudoInput.getText().toString() : "";
        int port = plan.optInt("port");
        run("Устанавливаю ноду: скачиваю ядро, пишу конфигурацию, запускаю…",
                () -> Mobile.nodeApply(channelId, documentUrl, channelKey, port, sudo, yandexCookies), r -> {
                    if (!r.optBoolean("ok")) {
                        setStatus(r.optBoolean("sudo") ? "sudo не принял пароль" : r.optString("error"), true);
                        return;
                    }
                    installed = true;
                    yandexCookies = "";
                    try {
                        shareLink = Mobile.nodeShareLink(nameInput.getText().toString().trim(), documentUrl,
                                channelKey, hostInput.getText().toString().trim(), port);
                    } catch (Exception e) {
                        setStatus("Не удалось собрать профиль: " + e.getMessage(), true);
                        return;
                    }
                    verify();
                });
    }

    // ---- step 4: verification -----------------------------------------------

    private void verify() {
        reset("Проверка канала", "Шаг 4 из 4. Подключаюсь к новой ноде и открываю сайт через неё.");
        content.addView(note("Если Яндекс попросит ноду пройти проверку, откроется окно с капчей: "
                + "она показывается с адреса сервера, пройдите её как обычно."));
        addStatus();
        String specs;
        try {
            specs = profileFromLink().sessionTransportsJson();
        } catch (Exception e) {
            setStatus("Не удалось собрать профиль: " + e.getMessage(), true);
            return;
        }
        CaptchaActivity.initCookieStore(this);
        String host = hostInput.getText().toString().trim();
        final boolean[] done = {false};
        Runnable watchCaptcha = new Runnable() {
            boolean shown;
            @Override public void run() {
                if (done[0]) return;
                String pending = Mobile.pendingCaptchaURL();
                if (!pending.isEmpty() && !shown) {
                    shown = true;
                    // A proxy means the check is the node's, shown from its
                    // address; otherwise Yandex is checking this phone.
                    setStatus(Mobile.pendingCaptchaProxy().isEmpty()
                            ? "Яндекс просит проверку у этого телефона"
                            : "Нода просит пройти проверку Яндекса", false);
                    startActivity(new Intent(NodeWizardActivity.this, CaptchaActivity.class));
                } else if (pending.isEmpty()) {
                    shown = false;
                }
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(watchCaptcha, 1000);
        run("Проверяю соединение через новую ноду (до " + (VERIFY_TIMEOUT_SEC / 60 + 1) + " мин)…",
                () -> Mobile.nodeVerify(specs, channelKey, host, VERIFY_TIMEOUT_SEC), r -> {
                    done[0] = true;
                    if (r.optBoolean("ok")) {
                        verifiedIp = r.optString("ip");
                        primaryUp = r.optBoolean("primary");
                        showDone();
                    } else {
                        showVerifyFailed(r.optString("error"));
                    }
                });
        // Works while the check runs (busy), unlike the other buttons: stops
        // waiting for the Yandex carrier and keeps what was proven so far.
        Button skip = secondaryButton("Хватит ждать Яндекс", null);
        skip.setOnClickListener(v -> new Thread(Mobile::nodeCancelVerify).start());
        content.addView(skip, spaced(-1, dp(48), 12));
    }

    private void showVerifyFailed(String error) {
        reset("Проверка не прошла", error);
        content.addView(note("Нода установлена на сервере как openflux-node@" + channelId
                + ". Можно повторить проверку или удалить канал с сервера."));
        content.addView(primaryButton("Повторить проверку", this::verify), spaced(-1, dp(52), 16));
        content.addView(secondaryButton("Удалить канал с сервера", () -> {
            String sudo = sudoInput != null ? sudoInput.getText().toString() : "";
            run("Удаляю канал…", () -> Mobile.nodeRemove(channelId, sudo), r -> {
                if (r.optBoolean("ok")) {
                    installed = false;
                    setStatus("Канал удалён с сервера", false);
                } else {
                    setStatus(r.optString("error"), true);
                }
            });
        }), spaced(-1, dp(48), 8));
        content.addView(secondaryButton("Сохранить профиль всё равно", this::showDone), spaced(-1, dp(48), 8));
        addStatus();
    }

    // ---- done -----------------------------------------------------------------

    private void showDone() {
        reset("Нода готова", verifiedIp.isEmpty()
                ? "Канал установлен, но проверка не завершена."
                : "Трафик выходит в интернет с адреса " + verifiedIp + ".");
        if (!verifiedIp.isEmpty() && !primaryUp) {
            content.addView(note("Сейчас работает резервный канал (прямое подключение к серверу). Канал через Яндекс "
                    + "ещё не поднялся: когда нода попросит проверку, приложение покажет её, пройдите её."));
        }
        content.addView(note("Канал " + channelId + " на порту " + plan.optInt("port")
                + ". Чтобы добавить ещё одного пользователя, запустите мастер снова: появится отдельный канал со своим документом и ключом."));
        content.addView(primaryButton("Сохранить на этом телефоне", this::saveProfile), spaced(-1, dp(52), 16));
        content.addView(secondaryButton("Показать QR для другого устройства", this::showQr), spaced(-1, dp(48), 8));
        content.addView(secondaryButton("Поделиться ссылкой", () -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareLink);
            startActivity(Intent.createChooser(send, "Ссылка на профиль OpenFlux"));
        }), spaced(-1, dp(48), 8));
        content.addView(note("В QR и ссылке лежит ключ канала: передавайте их только тому, кто будет им пользоваться."));
        content.addView(secondaryButton("Готово", this::finishWizard), spaced(-1, dp(48), 16));
        addStatus();
    }

    private Profile profileFromLink() throws Exception {
        return Profile.fromShare(new JSONObject(Mobile.parseShareLink(shareLink)));
    }

    private void saveProfile() {
        if (savedProfileId >= 0) {
            setStatus("Профиль уже сохранён", false);
            return;
        }
        try {
            Profile p = profileFromLink();
            ProfileStore store = new ProfileStore(new SecureSettings(this));
            List<Profile> profiles = store.load();
            profiles.add(p);
            store.save(profiles);
            savedProfileId = p.id;
            setStatus("Профиль «" + p.name + "» сохранён", false);
        } catch (Exception e) {
            setStatus("Не удалось сохранить профиль: " + e.getMessage(), true);
        }
    }

    private void showQr() {
        try {
            byte[] png = Mobile.shareQRPNG(shareLink, 720);
            Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setBackgroundColor(Color.WHITE);
            image.setPadding(dp(12), dp(12), dp(12), dp(12));
            image.setAdjustViewBounds(true);
            dialog().setTitle("Отсканируйте в OpenFlux")
                    .setView(image)
                    .setPositiveButton("Закрыть", null)
                    .show();
        } catch (Exception e) {
            setStatus("Не удалось показать QR: " + e.getMessage(), true);
        }
    }

    private void finishWizard() {
        if (savedProfileId < 0 && installed) {
            dialog().setTitle("Профиль не сохранён")
                    .setMessage("Сохранить профиль на этом телефоне перед выходом?")
                    .setPositiveButton("Сохранить", (d, w) -> {
                        saveProfile();
                        finishWizard();
                    })
                    .setNegativeButton("Не сохранять", (d, w) -> {
                        installed = false;
                        finishWizard();
                    })
                    .show();
            return;
        }
        if (savedProfileId >= 0) setResult(RESULT_OK, new Intent().putExtra(EXTRA_PROFILE_ID, savedProfileId));
        finish();
    }

    // ---- results from other activities -------------------------------------

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DOC) {
            if (resultCode == RESULT_OK && data != null) {
                String cookies = data.getStringExtra(YandexDocActivity.EXTRA_COOKIES);
                yandexCookies = cookies != null && Mobile.nodeSignedIn(cookies) ? cookies : "";
                checkDocument(data.getStringExtra(YandexDocActivity.EXTRA_URL));
            } else {
                setStatus("Документ не создан. Можно вставить ссылку вручную.", true);
            }
        } else if (requestCode == REQUEST_KEY_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while (in != null && (n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 64 * 1024) throw new IllegalStateException("слишком большой файл");
                }
                keyInput.setText(out.toString("UTF-8").trim());
            } catch (Exception e) {
                setStatus("Не удалось прочитать ключ: " + e.getMessage(), true);
            }
        }
    }

    // ---- saved servers (no secrets: address, login and trusted host key) ---

    private JSONArray recentServers() {
        try {
            return new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SERVERS, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void rememberServer(String host, int port, String user) {
        JSONArray old = recentServers();
        JSONArray out = new JSONArray();
        try {
            out.put(new JSONObject().put("host", host).put("port", port).put("user", user));
            for (int i = 0; i < old.length() && out.length() < 5; i++) {
                JSONObject s = old.optJSONObject(i);
                if (s == null || (host.equals(s.optString("host")) && port == s.optInt("port"))) continue;
                out.put(s);
            }
        } catch (Exception ignored) {
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SERVERS, out.toString()).apply();
    }

    private String knownHostKey(String host, int port) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("hostkey:" + host + ":" + port, "");
    }

    private void saveHostKey(String host, int port, String fp) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("hostkey:" + host + ":" + port, fp).apply();
    }

    // ---- plumbing ---------------------------------------------------------------

    interface Call { String run() throws Exception; }
    interface Done { void accept(JSONObject result); }

    // Runs a blocking bridge call off the UI thread, then hands its JSON back.
    private void run(String message, Call call, Done done) {
        if (busy) return;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        setStatus(message, false);
        worker.execute(() -> {
            JSONObject result;
            try {
                result = new JSONObject(call.run());
            } catch (Exception e) {
                result = new JSONObject();
                try {
                    result.put("ok", false).put("error", String.valueOf(e.getMessage()));
                } catch (Exception ignored) {
                }
            }
            JSONObject r = result;
            ui.post(() -> {
                busy = false;
                progress.setVisibility(View.INVISIBLE);
                if (isFinishing() || isDestroyed()) return;
                setStatus("", false);
                done.accept(r);
            });
        });
    }

    private void reset(String title, String subtitle) {
        content.removeAllViews();
        statusView = null;
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(text);
        content.addView(t);
        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextSize(14);
        s.setTextColor(secondary);
        content.addView(s, spaced(-1, -2, 4));
    }

    private void addStatus() {
        statusView = new TextView(this);
        statusView.setTextSize(14);
        content.addView(statusView, spaced(-1, -2, 14));
    }

    private void setStatus(String message, boolean error) {
        if (statusView == null) addStatus();
        statusView.setText(message);
        statusView.setTextColor(error ? danger : secondary);
    }

    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextColor(text);
        e.setHintTextColor(secondary);
        e.setTextSize(15);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackground(rounded(surface, border));
        return e;
    }

    private TextView label(String value) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(13);
        t.setTextColor(secondary);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private TextView note(String value) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(13);
        t.setTextColor(secondary);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(10), dp(14), dp(10));
        l.setBackground(rounded(surface, border));
        return l;
    }

    private Button primaryButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(accent);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setOnClickListener(v -> { if (!busy) action.run(); });
        return b;
    }

    private Button secondaryButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(text);
        b.setTextSize(14);
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(surface, border));
        if (action != null) b.setOnClickListener(v -> { if (!busy) action.run(); });
        return b;
    }

    private void styleToggle(Button b, boolean selected) {
        GradientDrawable bg = rounded(selected ? accent : surface, selected ? accent : border);
        b.setBackground(bg);
        b.setTextColor(selected ? Color.WHITE : text);
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(12));
        return d;
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, dark
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    private LinearLayout.LayoutParams spaced(int width, int height, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.topMargin = dp(topDp);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s);
            return p > 0 && p < 65536 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String channelsSummary(JSONArray channels) {
        if (channels == null || channels.length() == 0) return "";
        return ". Уже есть каналов: " + channels.length();
    }

    private static String join(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(a.optString(i));
        }
        return sb.toString();
    }
}
