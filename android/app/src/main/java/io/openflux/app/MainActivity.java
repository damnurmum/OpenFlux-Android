package io.openflux.app;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

import android.util.Base64;

import io.openflux.bridge.mobile.Mobile;

public final class MainActivity extends Activity {
    // Named explicitly (instead of the implicit per-Activity-class file from
    // getPreferences()) so OpenFluxTileService can read the connection mode
    // and network settings without depending on Activity.getPreferences()'s
    // undocumented file-naming behavior.
    static final String SETTINGS_PREFS_NAME = "openflux_settings";
    private static final int TUNNEL_PERMISSION_REQUEST = 42;
    private static final int NODE_WIZARD_REQUEST = 7302;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 43;
    private static final int DEFAULT_MTU = 1400;
    private static final int PAGE_HOME = 0;
    private static final int PAGE_PROFILES = 1;
    private static final int PAGE_LOGS = 2;
    private static final int PAGE_SETTINGS = 3;
    private static final int SETTINGS_MODE = 0;
    private static final int SETTINGS_NETWORK = 1;
    private static final int SETTINGS_APPS = 2;
    private static final int SETTINGS_INTERFACE = 3;
    private static final int SETTINGS_ABOUT = 4;
    private static final int SETTINGS_ROUTING = 5;
    private static final String[] PROFILE_ICON_KEYS = {
            "ic_public", "ic_link", "ic_lock", "ic_key", "ic_power",
            "ic_person", "ic_swap", "ic_terminal", "ic_apps", "ic_settings",
    };
    private static final String MODE_TUNNEL = "tunnel";
    private static final String MODE_PROXY = "proxy";
    // The phone serves as an l4 exit node for other clients.
    private static final String MODE_EXIT = "exit";
    private static final int DEFAULT_PROXY_PORT = 1080;
    private static final String MAIN_REPO_URL = "https://github.com/p1neappleXpress/OpenFlux";
    private static final String FORK_REPO_URL = "https://github.com/damnurmum/OpenFlux-Android";
    private static final String FORK_REPO_SLUG = "damnurmum/OpenFlux-Android";
    private static final int VERSION_CHECK_PENDING = 0;
    private static final int VERSION_CHECK_LATEST = 1;
    private static final int VERSION_CHECK_OUTDATED = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean darkMode;
    private boolean urlVisible;
    private boolean autoScroll = true;
    private boolean showSensitiveLogs = true;
    private boolean joinCelebration;
    private int currentPage = PAGE_HOME;
    private int settingsSubTab = SETTINGS_MODE;
    private boolean settingsDetailOpen;
    private int background;
    private int surface;
    private int text;
    private int secondary;
    private int border;
    private int accent;
    private int hint;
    private int logColor;

    private FrameLayout root;
    private View navScrim;
    private View navDeadZone;
    private FrameLayout content;
    private EditText urlInput;
    private EditText encryptionInput;
    private EditText maxTokenInput;
    private EditText maxUidInput;
    private View maxFieldsContainer;
    private EditText dnsInput;
    private EditText mtuInput;
    private EditText proxyPortInput;
    private EditText proxyUsernameInput;
    private EditText proxyPasswordInput;
    private ImageButton visibilityButton;
    private ImageButton encryptionVisibilityButton;
    private ImageButton proxyPasswordVisibilityButton;
    private TextView logView;
    private ScrollView logScroll;
    private LinearLayout tunnelButton;
    private ImageView tunnelPowerIcon;
    private TextView tunnelButtonText;
    private TextView uptimeView;
    private TextView speedView;
    private View ringWave;
    private String documentUrl;
    private String encryptionSecret;
    private String transportType = "yandex";
    private String codec = "batched";
    private String maxToken = "";
    private String maxUid = "";
    private ProfileStore profileStore;
    private List<Profile> profiles = new ArrayList<>();
    private long selectedProfileId = -1;
    // Material 3 "emphasized decelerate": quick start, long soft landing.
    private static final android.view.animation.Interpolator EMPHASIZED =
            new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private ImageView proxyShareQr;
    // The pinned save button of the current page, if any; it stays down
    // behind the keyboard instead of riding up with it.
    private View pinnedSave;
    private boolean keyboardOpen;
    // Exit mode: the home card with the QR clients scan to join this phone;
    // exitShareShown is the link (or error) it currently renders.
    private LinearLayout exitShareCard;
    private String exitShareShown;
    private boolean profileEditorOpen;
    private Long editingProfileId;
    private String editorIcon = "ic_public";
    private String editorTransportType = "yandex";
    private String editorCodec = "batched";
    private String editorMaxToken = "";
    private String editorMaxUid = "";
    private boolean editorSession;
    private int editorPriority = 50;
    private final List<Profile.Transport> editorExtras = new ArrayList<>();
    private View sessionFieldsContainer;
    private View codecSection;
    private View urlField;
    private TextView encryptionHintView;
    private EditText priorityInput;
    private LinearLayout extrasList;
    private EditText profileNameInput;
    private PopupWindow profileDropdown;
    private String dnsServer;
    private int mtu;
    private boolean killSwitchEnabled = true;
    private String connectionMode = MODE_TUNNEL;
    private int proxyPort = DEFAULT_PROXY_PORT;
    private boolean proxyLanAccess;
    private boolean proxyAuthEnabled;
    private String proxyUsername = "";
    private String proxyPassword = "";
    // Draft state for the Network/Mode settings sub-pages: edits only take
    // effect when "Применить" is pressed, not just by navigating away (see
    // applyNetworkSettings/applyModeSettings). Seeded from the live fields
    // each time the corresponding sub-page opens (openSettingsDetail).
    private String editorDnsServer = "";
    private boolean editorDnsAuto = true;
    private int editorMtu;
    private boolean editorKillSwitchEnabled = true;
    private String editorConnectionMode = MODE_TUNNEL;
    private int editorProxyPort = DEFAULT_PROXY_PORT;
    private boolean editorProxyLanAccess;
    private boolean editorProxyAuthEnabled;
    private boolean editorDarkMode;
    private boolean editorAutoScroll;
    private boolean editorShowSensitiveLogs;
    private boolean editorJoinCelebration;
    private String editorAppFilterMode = AppFilter.MODE_OFF;
    private final LinkedHashSet<String> editorSelectedApps = new LinkedHashSet<>();
    private String logs = "";
    private String lastShownError = "";
    private boolean encryptionVisible;
    private boolean proxyPasswordVisible;
    private SecureSettings secureSettings;
    private boolean shellAnimated;
    private int navBottomInset;
    private int gestureInset = -1;
    private ObjectAnimator ringPulse;
    private int lastTunnelButtonFill = -1;
    private Vibrator vibrator;
    private String lastAnnouncedState = "";
    private TextView versionBadge;
    private String appVersion = "";
    private String latestVersion;
    private int versionCheckState = VERSION_CHECK_PENDING;
    private final WeakHashMap<View, AnimatorSet> bounceAnimators = new WeakHashMap<>();

    private SharedPreferences appFilterPrefs;
    private String appFilterMode = AppFilter.MODE_OFF;
    private final LinkedHashSet<String> selectedApps = new LinkedHashSet<>();
    private SharedPreferences domainFilterPrefs;
    private final LinkedHashSet<String> enabledDomainPresets = new LinkedHashSet<>();
    private final LinkedHashSet<String> customDomains = new LinkedHashSet<>();
    private final LinkedHashSet<String> editorEnabledDomainPresets = new LinkedHashSet<>();
    private EditText customDomainsInput;
    private List<AppEntry> installedAppsCache;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            String pending = Mobile.readLogs();
            if (pending != null && !pending.isEmpty()) appendLog(pending);
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        vibrator = getSystemService(Vibrator.class);
        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS_NAME, MODE_PRIVATE);
        secureSettings = new SecureSettings(this);
        // Older prototype builds used plain preferences. Remove those values:
        // connection credentials now live only in the Keystore-backed store.
        prefs.edit().remove("document_url").remove("connection_document_url").apply();
        documentUrl = secureSettings.getString("document_url", "");
        encryptionSecret = secureSettings.getString("encryption_secret", "");
        profileStore = new ProfileStore(secureSettings);
        profiles = profileStore.load();
        selectedProfileId = profileStore.getSelectedId();
        migrateLegacyProfileIfNeeded();
        applySelectedProfileToFields();
        dnsServer = prefs.getString("dns_server", "");
        mtu = prefs.getInt("mtu", DEFAULT_MTU);
        killSwitchEnabled = prefs.getBoolean("kill_switch", true);
        String savedMode = prefs.getString("connection_mode", MODE_TUNNEL);
        connectionMode = MODE_PROXY.equals(savedMode) || MODE_EXIT.equals(savedMode) ? savedMode : MODE_TUNNEL;
        proxyPort = prefs.getInt("proxy_port", DEFAULT_PROXY_PORT);
        proxyLanAccess = prefs.getBoolean("proxy_lan_access", false);
        proxyAuthEnabled = prefs.getBoolean("proxy_auth_enabled", false);
        proxyUsername = prefs.getString("proxy_username", "");
        proxyPassword = secureSettings.getString("proxy_password", "");
        autoScroll = prefs.getBoolean("auto_scroll", true);
        showSensitiveLogs = prefs.getBoolean("show_sensitive_logs", true);
        joinCelebration = prefs.getBoolean("join_celebration", false);
        darkMode = prefs.contains("dark_mode")
                ? prefs.getBoolean("dark_mode", isSystemDark())
                : isSystemDark();
        appFilterPrefs = getSharedPreferences(AppFilter.PREFS_NAME, MODE_PRIVATE);
        appFilterMode = appFilterPrefs.getString(AppFilter.KEY_MODE, AppFilter.MODE_OFF);
        selectedApps.addAll(appFilterPrefs.getStringSet(AppFilter.KEY_PACKAGES, Collections.emptySet()));
        domainFilterPrefs = getSharedPreferences(DomainFilter.PREFS_NAME, MODE_PRIVATE);
        enabledDomainPresets.addAll(DomainFilter.loadEnabledPresets(domainFilterPrefs));
        customDomains.addAll(DomainFilter.loadCustomDomains(domainFilterPrefs));
        appVersion = readAppVersion();
        applyPalette();
        configureSystemBars();
        buildShell();
        showPage(PAGE_HOME);
        appendLog("Готово. При первом запуске Android запросит разрешение на туннель.");
        checkForUpdates();
        requestNotificationPermissionIfNeeded();
        handleShareIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        importShareLink(intent.getData().toString());
        // Not again on a configuration change.
        intent.setAction(Intent.ACTION_MAIN);
    }

    private void scanShareQr() {
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Наведите камеру на QR-код OpenFlux")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    // Turns an openflux:// link into a profile after the user confirms: the
    // link carries the exit's key, so nothing is saved silently.
    private void importShareLink(String link) {
        Profile p;
        try {
            p = Profile.fromShare(new JSONObject(Mobile.parseShareLink(link.trim())));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось прочитать QR: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this, darkMode
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Добавить профиль?")
                .setMessage(p.name + "\n" + profileTransportSummary(p)
                        + "\n\nВ коде ключ шифрования ноды: добавляйте QR только от тех, кому доверяете.")
                .setPositiveButton("Добавить", (dialog, which) -> {
                    profiles.add(p);
                    profileStore.save(profiles);
                    if (isConnectionRunning()) showPage(PAGE_PROFILES);
                    else selectProfile(p.id);
                    appendLog("Профиль «" + p.name + "» добавлен по QR");
                    Toast.makeText(this, "Профиль добавлен", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // Android 13+ requires this runtime permission to actually display any
    // notification, including a foreground service's - without it the tunnel
    // and proxy services still run fine, they just show no ongoing
    // notification (no status, no speed indicator) for the user to see.
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST
                && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            appendLog("Без разрешения на уведомления статус подключения не будет показан в шторке.");
        }
    }

    @Override public void onBackPressed() {
        if (currentPage == PAGE_SETTINGS && settingsDetailOpen) {
            closeSettingsDetail();
            return;
        }
        if (currentPage == PAGE_PROFILES && profileEditorOpen) {
            closeProfileEditor();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onStart() {
        super.onStart();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        captureLogs();
        super.onStop();
    }

    private boolean isSystemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyPalette() {
        if (darkMode) {
            // Deep navy dark theme matching upstream's OpenFluxAndroid palette
            // (bg_deep/bg_card/bg_card_stroke/accent_blue) rather than a
            // neutral grey dark mode.
            background = Color.rgb(8, 11, 18);
            surface = Color.rgb(19, 23, 34);
            text = Color.WHITE;
            secondary = Color.rgb(138, 146, 166);
            border = Color.rgb(42, 52, 70);
            accent = Color.rgb(79, 124, 255);
            hint = Color.rgb(90, 98, 114);
            logColor = Color.rgb(218, 220, 224);
        } else {
            background = Color.rgb(248, 249, 250);
            surface = Color.WHITE;
            text = Color.rgb(32, 33, 36);
            secondary = Color.rgb(95, 99, 104);
            border = Color.rgb(218, 220, 224);
            accent = Color.rgb(79, 124, 255);
            hint = Color.rgb(128, 134, 139);
            logColor = Color.rgb(60, 64, 67);
        }
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        // API 29+ draws its own translucent scrim behind the gesture handle
        // for contrast by default, regardless of setNavigationBarColor - it
        // shows up as a mismatched grey strip under our own dark nav bar.
        // targetSdk 35 also enforces edge-to-edge (setNavigationBarColor is
        // ignored there), so our own root background showing through behind
        // the handle is what actually determines the color on those devices.
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
        window.getDecorView().setSystemUiVisibility(darkMode ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void buildShell() {
        int side = dp(20);
        int top = dp(16);

        // Nav is a floating, fully-rounded pill (Telegram-style) with side
        // margins - its own row here, not overlapping content, header+content
        // get the usual side margins via body.
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(side, top, side, 0);
        body.addView(buildCompactHeader(), new LinearLayout.LayoutParams(-1, dp(54)));

        content = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        contentParams.topMargin = dp(16);
        body.addView(content, contentParams);

        // root is a FrameLayout so content can scroll behind the floating nav
        // pill: body fills the whole screen, a gradient scrim fades content
        // out just above the pill, and the pill itself draws on top of that.
        root = new FrameLayout(this);
        root.setBackgroundColor(background);
        root.addView(body, new FrameLayout.LayoutParams(-1, -1));
        // Fade is purely decorative and taller than the pill (it tapers out
        // above it) - it must NOT intercept clicks, or it blocks buttons that
        // are still visible (just fading a little) higher up in that taper.
        navScrim = new View(this);
        navScrim.setBackground(navScrimDrawable());
        navScrim.setClickable(false);
        root.addView(navScrim, navScrimLayoutParams());
        // Dead zone is a separate element sized to exactly the pill's own
        // footprint (not the taller fade) - only that band is blocked from
        // clicks reaching whatever content scrolled behind it.
        navDeadZone = new View(this);
        navDeadZone.setClickable(true);
        root.addView(navDeadZone, navDeadZoneLayoutParams());
        root.addView(buildBottomNav(), navLayoutParams());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            // The first call happens before any keyboard can be open, so that
            // bottom inset is purely the gesture-nav bar - remember it as the
            // baseline. With windowSoftInputMode=adjustResize, opening the
            // keyboard later inflates this same bottom inset by far more than
            // any gesture bar ever is; treat that as "keyboard open" and hide
            // the pill instead of letting it get dragged up with it.
            if (gestureInset < 0) gestureInset = bottom;
            keyboardOpen = bottom > gestureInset + dp(50);
            navBottomInset = gestureInset;
            // Edge-to-edge (targetSdk 35) means the window is not resized for
            // the keyboard: lift the page above it ourselves. The shrinking
            // ScrollView then brings the focused field back into view.
            body.setPadding(side, top + insets.getSystemWindowInsetTop(), side, keyboardOpen ? bottom : 0);
            if (pinnedSave != null) pinnedSave.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
            View nav = root.getChildAt(root.getChildCount() - 1);
            navScrim.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
            navDeadZone.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
            nav.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
            navScrim.setLayoutParams(navScrimLayoutParams());
            navDeadZone.setLayoutParams(navDeadZoneLayoutParams());
            nav.setLayoutParams(navLayoutParams());
            return insets;
        });

        setContentView(root);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(shellAnimated ? 200 : 340).start();
        shellAnimated = true;
        disableFocusHighlight(root);
    }

    // API 26+ draws its own grey "default focus highlight" rectangle behind
    // any focusable view on top of whatever background/ripple it already has
    // - visible here as a washed-out grey box around icons. Kill it tree-wide
    // since every page gets rebuilt from scratch on each tab/page switch.
    private void disableFocusHighlight(View view) {
        view.setDefaultFocusHighlightEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableFocusHighlight(group.getChildAt(i));
            }
        }
    }

    private View buildCompactHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setContentDescription("Логотип OpenFlux");
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setBackground(rounded(Color.rgb(79, 124, 255), Color.TRANSPARENT, 0, 10));
        logo.setImageResource(R.drawable.ic_openflux_foreground);
        logo.setClipToOutline(true);
        header.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titlesParams.leftMargin = dp(12);
        TextView title = text("OpenFlux", 21, text, true);
        TextView subtitle = text("Зашифрованный туннель", 12, secondary, false);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, titlesParams);

        versionBadge = text("", 10, accent, true);
        versionBadge.setGravity(Gravity.CENTER);
        versionBadge.setPadding(dp(9), dp(5), dp(9), dp(5));
        header.addView(versionBadge);
        updateVersionBadge();
        return header;
    }

    private void updateVersionBadge() {
        if (versionBadge == null) return;
        String base = appVersion.isEmpty() ? "-" : appVersion;
        int fg;
        int bg;
        String label;
        switch (versionCheckState) {
            case VERSION_CHECK_LATEST:
                fg = darkMode ? Color.rgb(129, 201, 149) : Color.rgb(24, 128, 56);
                bg = darkMode ? Color.rgb(30, 46, 36) : Color.rgb(230, 245, 234);
                label = base + " | Последняя версия";
                break;
            case VERSION_CHECK_OUTDATED:
                fg = darkMode ? Color.rgb(253, 214, 99) : Color.rgb(249, 171, 0);
                bg = darkMode ? Color.rgb(56, 46, 20) : Color.rgb(255, 243, 224);
                label = base + " | Доступно обновление";
                break;
            case VERSION_CHECK_PENDING:
            default:
                fg = accent;
                bg = darkMode ? Color.rgb(38, 50, 68) : Color.rgb(232, 240, 254);
                label = base;
        }
        versionBadge.setText(label);
        versionBadge.setTextColor(fg);
        versionBadge.setBackground(rounded(bg, Color.TRANSPARENT, 0, 12));
    }

    private String readAppVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName != null ? info.versionName : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void checkForUpdates() {
        new Thread(() -> {
            String latest = fetchLatestGithubVersion();
            if (latest == null || latest.isEmpty()) return;
            handler.post(() -> {
                latestVersion = latest;
                versionCheckState = compareVersions(appVersion, latest) >= 0
                        ? VERSION_CHECK_LATEST : VERSION_CHECK_OUTDATED;
                updateVersionBadge();
            });
        }).start();
    }

    private String fetchLatestGithubVersion() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://api.github.com/repos/" + FORK_REPO_SLUG + "/releases/latest");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "OpenFlux-Android");
            if (connection.getResponseCode() != 200) return null;
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            String tag = new JSONObject(body.toString()).optString("tag_name", "");
            return tag.startsWith("v") ? tag.substring(1) : tag;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int length = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < length; i++) {
            int valueA = versionPart(partsA, i);
            int valueB = versionPart(partsB, i);
            if (valueA != valueB) return Integer.compare(valueA, valueB);
        }
        return 0;
    }

    private int versionPart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // Floating rounded pill, not a full-width bar - side + bottom margins,
    // with the bottom one padded out by whatever gesture-nav inset is
    // currently known (see buildShell()'s insets listener).
    private FrameLayout.LayoutParams navLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, navHeight(), Gravity.BOTTOM);
        params.leftMargin = dp(12);
        params.rightMargin = dp(12);
        params.bottomMargin = dp(10) + navBottomInset;
        return params;
    }

    private int navHeight() {
        return dp(68);
    }

    // Gradient scrim behind the pill: transparent at the top, fading down to
    // the app background, so content scrolling behind the pill fades out
    // instead of being clipped by a flat rectangle. Purely decorative -
    // taller than the pill itself, so it must stay non-clickable.
    private FrameLayout.LayoutParams navScrimLayoutParams() {
        int height = navHeight() + dp(10) + navBottomInset + dp(48);
        return new FrameLayout.LayoutParams(-1, height, Gravity.BOTTOM);
    }

    // Dead zone: full width (unlike the floating pill, which has side
    // margins), but only as tall as the pill's own footprint - it stops
    // exactly where the pill starts, so it never blocks clicks on content
    // still visible higher up in the fade above it.
    private FrameLayout.LayoutParams navDeadZoneLayoutParams() {
        int height = navHeight() + dp(10) + navBottomInset;
        return new FrameLayout.LayoutParams(-1, height, Gravity.BOTTOM);
    }

    private Drawable navScrimDrawable() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, background});
        return drawable;
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(4), dp(6), dp(4), dp(6));
        nav.setBackground(rounded(surface, border, 1, 34));
        nav.setElevation(dp(4));
        nav.addView(navItem(R.drawable.ic_home, "Главная", PAGE_HOME), weighted());
        nav.addView(navItem(R.drawable.ic_public, "Профили", PAGE_PROFILES), weighted());
        nav.addView(navItem(R.drawable.ic_terminal, "Логи", PAGE_LOGS), weighted());
        nav.addView(navItem(R.drawable.ic_settings, "Настройки", PAGE_SETTINGS), weighted());
        return nav;
    }

    private View navItem(int icon, String label, int page) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setBackground(ripple(Color.TRANSPARENT, 14));
        item.setContentDescription(label);
        boolean active = page == currentPage;

        // Active tab's icon sits on its own colored pill, not just a tinted
        // glyph on the bar's background.
        FrameLayout iconWrap = new FrameLayout(this);
        if (active) iconWrap.setBackground(rounded(accent, Color.TRANSPARENT, 0, 18));
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(active ? Color.WHITE : secondary));
        iconWrap.addView(image, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        item.addView(iconWrap, new LinearLayout.LayoutParams(dp(48), dp(40)));
        if (active) {
            iconWrap.setScaleX(0.6f);
            iconWrap.setScaleY(0.6f);
            iconWrap.animate().scaleX(1f).scaleY(1f).setDuration(280)
                    .setInterpolator(new OvershootInterpolator(4f)).start();
        }
        item.setOnClickListener(v -> {
            if (page == currentPage) return;
            tap(v);
            showPage(page);
        });
        return item;
    }

    private void showPage(int page) {
        captureSettings();
        if (page != PAGE_SETTINGS) settingsDetailOpen = false;
        if (page != PAGE_PROFILES) profileEditorOpen = false;
        currentPage = page;
        pinnedSave = null;
        View pageView = page == PAGE_HOME ? buildHomePage()
                : page == PAGE_PROFILES ? buildProfilesPage()
                : page == PAGE_LOGS ? buildLogsPage() : buildSettingsPage();
        crossfadeContent(pageView);
        View oldNav = root.getChildAt(root.getChildCount() - 1);
        ViewGroup.LayoutParams navParams = oldNav.getLayoutParams();
        root.removeView(oldNav);
        root.addView(buildBottomNav(), navParams);
        updateStatus();
        disableFocusHighlight(root);
    }

    // crossfadeContent swaps the FrameLayout's page content with a short fade
    // + rise instead of an instant cut, used for both outer tab switches and
    // Settings sub-tab switches.
    private void crossfadeContent(View newView) {
        int staleCount = content.getChildCount();
        View[] stale = new View[staleCount];
        for (int i = 0; i < staleCount; i++) stale[i] = content.getChildAt(i);

        newView.setAlpha(0f);
        newView.setTranslationY(dp(8));
        content.addView(newView, new FrameLayout.LayoutParams(-1, -1));
        newView.animate().alpha(1f).translationY(0f).setDuration(280).setStartDelay(40)
                .setInterpolator(EMPHASIZED).withLayer().start();

        for (View old : stale) {
            old.animate().cancel();
            old.animate().alpha(0f).setDuration(140).withLayer()
                    .withEndAction(() -> content.removeView(old)).start();
        }
    }

    private void openSettingsDetail(int tab) {
        if (settingsDetailOpen && settingsSubTab == tab) return;
        settingsDetailOpen = true;
        settingsSubTab = tab;
        if (tab == SETTINGS_NETWORK) {
            editorDnsServer = dnsServer;
            editorDnsAuto = dnsServer.isEmpty();
            editorMtu = mtu;
            editorKillSwitchEnabled = killSwitchEnabled;
        } else if (tab == SETTINGS_MODE) {
            editorConnectionMode = connectionMode;
            editorProxyPort = proxyPort;
            editorProxyLanAccess = proxyLanAccess;
            editorProxyAuthEnabled = proxyAuthEnabled;
        } else if (tab == SETTINGS_INTERFACE) {
            editorDarkMode = darkMode;
            editorAutoScroll = autoScroll;
            editorShowSensitiveLogs = showSensitiveLogs;
            editorJoinCelebration = joinCelebration;
        } else if (tab == SETTINGS_APPS) {
            editorAppFilterMode = appFilterMode;
            editorSelectedApps.clear();
            editorSelectedApps.addAll(selectedApps);
        } else if (tab == SETTINGS_ROUTING) {
            editorEnabledDomainPresets.clear();
            editorEnabledDomainPresets.addAll(enabledDomainPresets);
        }
        showPage(PAGE_SETTINGS);
    }

    private void closeSettingsDetail() {
        if (!settingsDetailOpen) return;
        settingsDetailOpen = false;
        showPage(PAGE_SETTINGS);
    }

    // migrateLegacyProfileIfNeeded turns a pre-0.6.0 install's single global
    // document URL / secret into the first profile, so an existing user's
    // connection keeps working without having to re-enter anything.
    private void migrateLegacyProfileIfNeeded() {
        if (!profiles.isEmpty()) return;
        if (!isValidDocumentUrl(documentUrl)) return;
        Profile migrated = new Profile();
        migrated.id = System.currentTimeMillis();
        migrated.name = "Профиль 1";
        migrated.icon = "ic_public";
        migrated.transportType = "yandex";
        migrated.documentUrl = documentUrl;
        migrated.encryptionSecret = encryptionSecret;
        profiles.add(migrated);
        selectedProfileId = migrated.id;
        profileStore.save(profiles);
        profileStore.setSelectedId(selectedProfileId);
    }

    private Profile selectedProfile() {
        for (Profile p : profiles) if (p.id == selectedProfileId) return p;
        return null;
    }

    // applySelectedProfileToFields refreshes the plain documentUrl/
    // encryptionSecret/transportType fields that toggleConnection/startTunnel/
    // startProxy already read, from whichever profile is currently selected.
    private void applySelectedProfileToFields() {
        Profile p = selectedProfile();
        if (p != null) {
            documentUrl = p.documentUrl;
            encryptionSecret = p.encryptionSecret;
            transportType = p.transportType;
            codec = p.codec;
            maxToken = p.maxToken;
            maxUid = p.maxUid;
        } else {
            documentUrl = "";
            encryptionSecret = "";
            transportType = "yandex";
            codec = "batched";
            maxToken = "";
            maxUid = "";
        }
    }

    private int profileIconRes(String key) {
        if (key == null) return R.drawable.ic_public;
        switch (key) {
            case "ic_link": return R.drawable.ic_link;
            case "ic_lock": return R.drawable.ic_lock;
            case "ic_key": return R.drawable.ic_key;
            case "ic_power": return R.drawable.ic_power;
            case "ic_person": return R.drawable.ic_person;
            case "ic_swap": return R.drawable.ic_swap;
            case "ic_terminal": return R.drawable.ic_terminal;
            case "ic_apps": return R.drawable.ic_apps;
            case "ic_settings": return R.drawable.ic_settings;
            case "ic_public":
            default: return R.drawable.ic_public;
        }
    }

    private String transportLabel(String type) {
        return Profile.transportLabel(type);
    }

    private String profileTransportSummary(Profile p) {
        if (!p.session) return transportLabel(p.transportType);
        StringBuilder summary = new StringBuilder("Session: ").append(transportLabel(p.transportType));
        for (Profile.Transport t : p.extraTransports) summary.append(" + ").append(transportLabel(t.type));
        return summary.toString();
    }

    private void selectProfile(long id) {
        if (id != selectedProfileId && isConnectionRunning()) {
            Toast.makeText(this, "Сначала отключитесь, затем меняйте профиль", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedProfileId = id;
        profileStore.setSelectedId(id);
        applySelectedProfileToFields();
        showPage(PAGE_HOME);
    }

    private void openProfileEditor(Profile existing) {
        editingProfileId = existing != null ? existing.id : null;
        editorIcon = existing != null ? existing.icon : "ic_public";
        editorTransportType = existing != null ? existing.transportType : "yandex";
        editorCodec = existing != null ? existing.codec : "batched";
        editorMaxToken = existing != null ? existing.maxToken : "";
        editorMaxUid = existing != null ? existing.maxUid : "";
        editorSession = existing != null && existing.session;
        editorPriority = existing != null ? existing.priority : 50;
        editorExtras.clear();
        if (existing != null) for (Profile.Transport t : existing.extraTransports) editorExtras.add(t.copy());
        profileEditorOpen = true;
        showPage(PAGE_PROFILES);
    }

    private void closeProfileEditor() {
        profileEditorOpen = false;
        showPage(PAGE_PROFILES);
    }

    private void saveProfileFromEditor(String name, String docUrl, String secret, String token, String uid) {
        if (name.isEmpty()) {
            Toast.makeText(this, "Укажите название профиля", Toast.LENGTH_SHORT).show();
            return;
        }
        String valueProblem = transportValueProblem(editorTransportType,
                "oneme".equals(editorTransportType) ? token : docUrl, true);
        if (valueProblem != null) {
            Toast.makeText(this, valueProblem, Toast.LENGTH_LONG).show();
            return;
        }
        if (!secret.isEmpty() && secret.length() < 16) {
            Toast.makeText(this, "Ключ шифрования должен быть не короче 16 символов, либо оставьте поле пустым",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int mainPriority = parsePriority(priorityInput != null ? priorityInput.getText().toString() : "", 50);
        if (editorSession) {
            if (secret.length() < 16) {
                Toast.makeText(this, "Для режима Session нужен ключ шифрования не короче 16 символов",
                        Toast.LENGTH_LONG).show();
                return;
            }
            for (Profile.Transport t : editorExtras) {
                String problem = extraTransportProblem(t);
                if (problem != null) {
                    Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
        Profile target = null;
        if (editingProfileId != null) {
            for (Profile p : profiles) if (p.id == editingProfileId) { target = p; break; }
        }
        boolean isNew = target == null;
        if (isNew) {
            target = new Profile();
            target.id = System.currentTimeMillis();
            profiles.add(target);
        }
        target.name = name;
        target.icon = editorIcon;
        target.transportType = editorTransportType;
        target.documentUrl = docUrl;
        target.encryptionSecret = secret;
        target.codec = editorCodec;
        target.maxToken = token;
        target.maxUid = uid;
        target.session = editorSession;
        target.priority = mainPriority;
        target.extraTransports.clear();
        for (Profile.Transport t : editorExtras) target.extraTransports.add(t.copy());
        profileStore.save(profiles);
        if (isNew && selectedProfile() == null) selectProfile(target.id);
        if (target.id == selectedProfileId) applySelectedProfileToFields();
        Toast.makeText(this, "Профиль сохранён", Toast.LENGTH_SHORT).show();
        closeProfileEditor();
    }

    private void deleteProfile(Profile profile) {
        if (isConnectionRunning() && profile.id == selectedProfileId) {
            Toast.makeText(this, "Нельзя удалить активный профиль во время подключения", Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Удалить профиль?")
                .setMessage("«" + profile.name + "» будет удалён без возможности восстановления.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    profiles.remove(profile);
                    profileStore.save(profiles);
                    if (profile.id == selectedProfileId) {
                        selectedProfileId = profiles.isEmpty() ? -1 : profiles.get(0).id;
                        profileStore.setSelectedId(selectedProfileId);
                        applySelectedProfileToFields();
                    }
                    Toast.makeText(this, "Профиль удалён", Toast.LENGTH_SHORT).show();
                    profileEditorOpen = false;
                    showPage(PAGE_PROFILES);
                })
                .show();
    }

    private void showProfileDropdown(View anchor) {
        if (profiles.isEmpty()) {
            showPage(PAGE_PROFILES);
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackground(rounded(surface, border, 1, 12));
        content.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (Profile p : profiles) {
            boolean selected = p.id == selectedProfileId;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(ripple(Color.TRANSPARENT, 8));
            row.setClickable(true);
            row.addView(iconBubble(profileIconRes(p.icon)), new LinearLayout.LayoutParams(dp(34), dp(34)));
            TextView nameView = text(p.name, 14, text, selected);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
            nameParams.leftMargin = dp(12);
            nameParams.rightMargin = dp(8);
            row.addView(nameView, nameParams);
            if (selected) row.addView(icon(R.drawable.ic_check, accent), new LinearLayout.LayoutParams(dp(18), dp(18)));
            row.setOnClickListener(v -> {
                tap(v);
                selectProfile(p.id);
                if (profileDropdown != null) profileDropdown.dismiss();
            });
            content.addView(row);
        }
        PopupWindow popup = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(8));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        profileDropdown = popup;
        popup.setOnDismissListener(() -> profileDropdown = null);
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    private View buildProfileSelectorRow() {
        Profile p = selectedProfile();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(rounded(surface, border, 1, 11));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(iconBubble(p != null ? profileIconRes(p.icon) : R.drawable.ic_public),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = dp(14);
        copy.addView(text(p != null ? p.name : "Профиль не выбран", 15, text, true));
        copy.addView(text(p != null ? profileTransportSummary(p) : "Нажмите, чтобы создать профиль",
                12, secondary, false));
        row.addView(copy, copyParams);
        row.addView(icon(R.drawable.ic_chevron_right, hint), new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.setOnClickListener(v -> {
            bounce(v);
            if (profiles.isEmpty()) showPage(PAGE_PROFILES);
            else if (isConnectionRunning()) {
                Toast.makeText(this, "Сначала отключитесь, затем меняйте профиль", Toast.LENGTH_SHORT).show();
            } else showProfileDropdown(v);
        });
        return row;
    }

    private View buildHomePage() {
        if (ringPulse != null) {
            ringPulse.cancel();
            ringPulse = null;
        }
        lastTunnelButtonFill = -1;

        LinearLayout page = page();

        // Big circular connect button: two static concentric rings (in the
        // spirit of the original OpenFluxAndroid app's main toggle) plus a
        // slow expanding/fading "wave" ring behind them, tinted to match the
        // button's own current color (see animateTunnelButtonFill). Status
        // and connection time live inside the button itself, Happ-style,
        // instead of a separate status card.
        FrameLayout buttonStack = new FrameLayout(this);
        buttonStack.setClipChildren(false);
        ringWave = new View(this);
        ringWave.setBackground(rounded(Color.TRANSPARENT, accent, 2, 70));
        buttonStack.addView(ringWave, new FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER));
        View ringOuter = new View(this);
        ringOuter.setBackground(ringOutline());
        buttonStack.addView(ringOuter, new FrameLayout.LayoutParams(dp(200), dp(200), Gravity.CENTER));
        View ringMid = new View(this);
        ringMid.setBackground(ringOutline());
        buttonStack.addView(ringMid, new FrameLayout.LayoutParams(dp(170), dp(170), Gravity.CENTER));

        tunnelButton = new LinearLayout(this);
        tunnelButton.setOrientation(LinearLayout.VERTICAL);
        tunnelButton.setGravity(Gravity.CENTER);
        tunnelButton.setClickable(true);
        tunnelButton.setFocusable(true);
        tunnelButton.setElevation(dp(2));
        tunnelPowerIcon = icon(R.drawable.ic_power, Color.WHITE);
        tunnelButton.addView(tunnelPowerIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        tunnelButtonText = text("Остановлено", 13, Color.WHITE, true);
        tunnelButtonText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusTextParams = matchWrap();
        statusTextParams.topMargin = dp(8);
        tunnelButton.addView(tunnelButtonText, statusTextParams);
        uptimeView = text("", 11, Color.WHITE, false);
        uptimeView.setAlpha(0.85f);
        uptimeView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams uptimeParams = matchWrap();
        uptimeParams.topMargin = dp(2);
        tunnelButton.addView(uptimeView, uptimeParams);
        speedView = text("", 10, Color.WHITE, false);
        speedView.setAlpha(0.7f);
        speedView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams speedParams = matchWrap();
        speedParams.topMargin = dp(2);
        tunnelButton.addView(speedView, speedParams);
        tunnelButton.setOnClickListener(v -> {
            bounce(v);
            toggleConnection();
        });
        buttonStack.addView(tunnelButton, new FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER));

        LinearLayout.LayoutParams stackParams = new LinearLayout.LayoutParams(dp(200), dp(200));
        stackParams.gravity = Gravity.CENTER_HORIZONTAL;
        stackParams.topMargin = dp(16);
        page.addView(buttonStack, stackParams);
        staggerIn(buttonStack, 30);

        ringWave.setAlpha(0f);
        ringPulse = ObjectAnimator.ofPropertyValuesHolder(ringWave,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.5f, 0f));
        ringPulse.setDuration(2600);
        ringPulse.setRepeatCount(ValueAnimator.INFINITE);
        ringPulse.setInterpolator(new LinearInterpolator());
        // Started/stopped from updateStatus() via setRingPulsing() based on
        // connection state, not unconditionally here.

        LinearLayout.LayoutParams selectorParams = matchWrap();
        selectorParams.topMargin = dp(20);
        View profileSelector = buildProfileSelectorRow();
        page.addView(profileSelector, selectorParams);
        staggerIn(profileSelector, 80);

        TextView summaryTitle = label("АКТИВНЫЕ ПАРАМЕТРЫ");
        LinearLayout.LayoutParams summaryTitleParams = matchWrap();
        summaryTitleParams.topMargin = dp(24);
        summaryTitleParams.bottomMargin = dp(8);
        page.addView(summaryTitle, summaryTitleParams);
        View activeParams = paramsCard(activeParamRows());
        LinearLayout.LayoutParams activeParamsParams = matchWrap();
        activeParamsParams.bottomMargin = dp(8);
        page.addView(activeParams, activeParamsParams);
        staggerIn(activeParams, 130);

        exitShareCard = null;
        exitShareShown = null;
        if (MODE_EXIT.equals(connectionMode)) {
            exitShareCard = new LinearLayout(this);
            exitShareCard.setOrientation(LinearLayout.VERTICAL);
            exitShareCard.setGravity(Gravity.CENTER_HORIZONTAL);
            exitShareCard.setPadding(dp(16), dp(14), dp(16), dp(14));
            exitShareCard.setBackground(rounded(surface, border, 1, 11));
            exitShareCard.setVisibility(View.GONE);
            LinearLayout.LayoutParams shareParams = matchWrap();
            shareParams.topMargin = dp(16);
            shareParams.bottomMargin = dp(8);
            page.addView(exitShareCard, shareParams);
            refreshExitShareCard();
        }
        return wrapScroll(page);
    }

    // Shows the QR (link and image both come from the core) while the exit
    // runs; rebuilt only when the link changes, e.g. a new Wi-Fi address.
    private void refreshExitShareCard() {
        if (exitShareCard == null) return;
        String link = null;
        String error = null;
        if (OpenFluxExitService.isRunning()) {
            String ip = getLocalIpAddress();
            try {
                link = Mobile.exitShareLink(ip != null ? ip : "", "OpenFlux " + Build.MODEL);
            } catch (Exception e) {
                error = e.getMessage();
            }
        }
        String shown = link != null ? link : error;
        if (java.util.Objects.equals(shown, exitShareShown)) return;
        exitShareShown = shown;
        exitShareCard.removeAllViews();
        if (shown == null) {
            exitShareCard.setVisibility(View.GONE);
            return;
        }
        exitShareCard.addView(text("ПОДКЛЮЧЕНИЕ ПО QR", 11, secondary, true));
        if (link == null) {
            TextView problem = text("QR недоступен: " + error, 13, text, false);
            LinearLayout.LayoutParams problemParams = matchWrap();
            problemParams.topMargin = dp(6);
            exitShareCard.addView(problem, problemParams);
        } else {
            try {
                byte[] png = Mobile.shareQRPNG(link, dp(220));
                ImageView qrView = new ImageView(this);
                qrView.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length));
                qrView.setBackground(rounded(Color.WHITE, Color.WHITE, 0, 12));
                qrView.setPadding(dp(6), dp(6), dp(6), dp(6));
                LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(232), dp(232));
                qrParams.topMargin = dp(10);
                exitShareCard.addView(qrView, qrParams);
            } catch (Exception e) {
                exitShareCard.addView(text("QR недоступен: " + e.getMessage(), 13, text, false), matchWrap());
            }
            TextView hint = text("Отсканируйте в OpenFlux на другом телефоне: Профили, кнопка QR. "
                    + "В коде ключ шифрования, показывайте только своим.", 12, secondary, false);
            hint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hintParams = matchWrap();
            hintParams.topMargin = dp(10);
            exitShareCard.addView(hint, hintParams);
            Button copyButton = new Button(this);
            copyButton.setText("Копировать ссылку");
            copyButton.setAllCaps(false);
            copyButton.setTextColor(accent);
            copyButton.setTextSize(13);
            copyButton.setStateListAnimator(null);
            copyButton.setBackground(ripple(Color.TRANSPARENT, 9));
            String copied = link;
            copyButton.setOnClickListener(v -> {
                bounce(v);
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlux", copied));
                Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show();
            });
            exitShareCard.addView(copyButton, new LinearLayout.LayoutParams(-1, dp(40)));
        }
        if (exitShareCard.getVisibility() != View.VISIBLE) {
            exitShareCard.setVisibility(View.VISIBLE);
            exitShareCard.setScaleX(0.9f);
            exitShareCard.setScaleY(0.9f);
            exitShareCard.setAlpha(0f);
            exitShareCard.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320)
                    .setInterpolator(new OvershootInterpolator()).start();
        }
    }

    private String[][] activeParamRows() {
        if (MODE_EXIT.equals(connectionMode)) {
            return new String[][]{
                    {"Режим", "Выходная нода (L4)"},
                    {"Выход в интернет", "С IP этого телефона"},
                    {"IP в локальной сети", getLocalIpAddress() != null ? getLocalIpAddress() : "не определён"},
            };
        }
        if (MODE_PROXY.equals(connectionMode)) {
            return new String[][]{
                    {"Режим", "Прокси (SOCKS5)"},
                    {"DNS-сервер", dnsServer.isEmpty() ? "Авто" : dnsServer},
                    {"Локальный порт", String.valueOf(proxyPort)},
                    {"Доступ", proxyAccessSummary()},
            };
        }
        return new String[][]{
                {"Режим", "Туннель (весь трафик)"},
                {"DNS-сервер", dnsServer.isEmpty() ? "Авто" : dnsServer},
                {"MTU пакета", String.valueOf(mtu)},
                {"Приложения", appFilterSummary()},
        };
    }

    private String proxyAccessSummary() {
        if (!proxyLanAccess) return "Только это устройство";
        String localIp = getLocalIpAddress();
        String address = localIp != null ? localIp + ":" + proxyPort : "IP не определён";
        return address + (proxyAuthEnabled ? " (с паролем)" : " (без пароля)");
    }

    private String appFilterSummary() {
        if (AppFilter.MODE_WHITELIST.equals(appFilterMode)) return "Белый список (" + selectedApps.size() + ")";
        if (AppFilter.MODE_BLACKLIST.equals(appFilterMode)) return "Чёрный список (" + selectedApps.size() + ")";
        return "Все";
    }

    private View paramsCard(String[][] rows) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(surface, border, 1, 11));
        for (int i = 0; i < rows.length; i++) {
            card.addView(paramRow(rows[i][0], rows[i][1]));
            if (i < rows.length - 1) addDivider(card, 0);
        }
        return card;
    }

    private View paramRow(String labelValue, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.addView(text(labelValue, 13, secondary, false), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView valueView = text(value, 13, text, true);
        valueView.setGravity(Gravity.END);
        row.addView(valueView, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private void staggerIn(View view, int delayMs) {
        view.setAlpha(0f);
        view.setTranslationY(dp(14));
        view.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(320)
                .setInterpolator(EMPHASIZED).withLayer().start();
    }

    private View buildLogsPage() {
        LinearLayout page = page();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("Журнал событий", 25, text, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageButton clear = iconButton(R.drawable.ic_delete, "Очистить журнал");
        clear.setOnClickListener(v -> {
            tap(v);
            logs = "";
            logView.setText("");
        });
        header.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(header);

        TextView note = text("Логи хранятся только до закрытия приложения.", 11, secondary, false);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(4);
        page.addView(note, noteParams);

        logView = text(colorizeLogs(logs), 12, logColor, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(14), dp(12), dp(14), dp(12));
        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setVerticalScrollBarEnabled(false);
        logScroll.setBackground(rounded(surface, border, 1, 10));
        logScroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        logParams.topMargin = dp(12);
        // Unlike pages that are fine fading behind the pill, log text must
        // stay fully readable - the card's own bottom edge needs to end
        // above the pill, not extend behind it with scroll-padding tricks.
        logParams.bottomMargin = navClearance();
        page.addView(logScroll, logParams);
        return page;
    }

    private View buildSettingsPage() {
        LinearLayout page = page();
        page.addView(buildSettingsHeader());

        if (!settingsDetailOpen) {
            LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            listParams.topMargin = dp(16);
            listParams.bottomMargin = dp(10);
            page.addView(buildSettingsList(), listParams);
            return page;
        }

        boolean showSave = settingsSubTab != SETTINGS_ABOUT;
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        contentParams.topMargin = dp(16);
        if (!showSave) contentParams.bottomMargin = dp(10);
        View subTab = buildSettingsSubTabContent();
        // The pinned save button already clears the nav pill; the scroll
        // needs no room of its own for it.
        if (showSave && subTab instanceof ScrollView) subTab.setPadding(0, 0, 0, dp(8));
        page.addView(subTab, contentParams);

        if (showSave) {
            Button save = primaryButton("Сохранить настройки", () -> {
                if (settingsSubTab == SETTINGS_NETWORK) applyNetworkSettings();
                else if (settingsSubTab == SETTINGS_MODE) applyModeSettings();
                else if (settingsSubTab == SETTINGS_INTERFACE) applyInterfaceSettings();
                else if (settingsSubTab == SETTINGS_APPS) applyAppsSettings();
                else if (settingsSubTab == SETTINGS_ROUTING) applyRoutingSettings();
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(52));
            saveParams.topMargin = dp(14);
            // This button sits outside the scrollable section (as a fixed
            // footer), so it needs its own clearance from the nav pill -
            // the scroll section's internal padding doesn't cover it.
            saveParams.bottomMargin = navClearance();
            page.addView(save, saveParams);
            pinnedSave = save;
            if (keyboardOpen) save.setVisibility(View.GONE);
        }
        return page;
    }

    private View buildSettingsHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (settingsDetailOpen) {
            ImageButton back = iconButton(R.drawable.ic_arrow_back, "Назад к настройкам");
            back.setOnClickListener(v -> {
                bounce(v);
                closeSettingsDetail();
            });
            LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(44), dp(44));
            backParams.rightMargin = dp(6);
            header.addView(back, backParams);
            header.addView(text(settingsSectionTitle(settingsSubTab), 22, text, true),
                    new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            LinearLayout titles = new LinearLayout(this);
            titles.setOrientation(LinearLayout.VERTICAL);
            titles.addView(text("Настройки", 25, text, true));
            TextView hint = text("Параметры сети применяются при следующем подключении.", 12, secondary, false);
            LinearLayout.LayoutParams hintParams = matchWrap();
            hintParams.topMargin = dp(4);
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
            case SETTINGS_MODE:
            default: return "Режим работы";
        }
    }

    private View buildSettingsList() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, navClearance());
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(settingsListRow(R.drawable.ic_swap, "Режим работы",
                modeLabel(connectionMode), SETTINGS_MODE));
        list.addView(settingsListRow(R.drawable.ic_public, "Сеть",
                "DNS-сервер и MTU", SETTINGS_NETWORK));
        list.addView(settingsListRow(R.drawable.ic_apps, "Приложения",
                "Какие приложения используют туннель", SETTINGS_APPS));
        list.addView(settingsListRow(R.drawable.ic_routing, "Маршрутизация",
                "Сайты и сервисы в обход туннеля", SETTINGS_ROUTING));
        list.addView(settingsListRow(R.drawable.ic_dark_mode, "Вид",
                "Тема, логи и анимации", SETTINGS_INTERFACE));
        list.addView(settingsListRow(R.drawable.ic_info, "О проекте",
                "Репозитории проекта", SETTINGS_ABOUT));
        groupTiles(list);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private void addDivider(LinearLayout parent) {
        addDivider(parent, dp(56));
    }

    private void addDivider(LinearLayout parent, int leftMargin) {
        View line = new View(this);
        line.setBackgroundColor(border);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.leftMargin = leftMargin;
        parent.addView(line, params);
    }

    private View settingsListRow(int iconRes, String titleValue, String detailValue, int tab) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(iconBubble(iconRes), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = dp(16);
        copy.addView(text(titleValue, 15, text, true));
        copy.addView(text(detailValue, 12, secondary, false));
        row.addView(copy, copyParams);
        row.addView(icon(R.drawable.ic_chevron_right, hint), new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.setOnClickListener(v -> {
            bounce(v);
            openSettingsDetail(tab);
        });
        return row;
    }

    private View buildSettingsSubTabContent() {
        switch (settingsSubTab) {
            case SETTINGS_NETWORK:
                return wrapScroll(buildNetworkSettings());
            case SETTINGS_APPS:
                return buildAppsSettings();
            case SETTINGS_ROUTING:
                return wrapScroll(buildRoutingSettings());
            case SETTINGS_INTERFACE:
                return wrapScroll(buildInterfaceSettings());
            case SETTINGS_ABOUT:
                return wrapScroll(buildAboutSettings());
            case SETTINGS_MODE:
            default:
                return wrapScroll(buildModeSettings());
        }
    }

    private View buildModeSettings() {
        LinearLayout section = page();
        TextView hint = text(
                "Туннель направляет через систему весь трафик устройства. "
                        + "Прокси поднимает локальный SOCKS5-сервер без запроса разрешения на туннель - "
                        + "адрес нужно указать вручную в приложениях, которые поддерживают прокси.",
                12, secondary, false);
        section.addView(hint, matchWrap());

        String[] modes = {MODE_TUNNEL, MODE_PROXY, MODE_EXIT};
        Runnable[] onModeChanged = new Runnable[1];
        View modeChoice = choiceList(new String[][]{
                {"Туннель", "Весь трафик устройства"},
                {"Прокси (SOCKS5)", "Без системного туннеля"},
                {"Выходная нода (L4)", "Телефон выпускает клиентов в интернет"},
        }, new int[]{R.drawable.ic_lock, R.drawable.ic_swap, R.drawable.ic_power}, java.util.Arrays.asList(modes).indexOf(editorConnectionMode), i -> {
            editorConnectionMode = modes[i];
            onModeChanged[0].run();
        });
        LinearLayout.LayoutParams modeGroupParams = matchWrap();
        modeGroupParams.topMargin = dp(14);
        section.addView(modeChoice, modeGroupParams);

        TextView exitHint = text(
                "Телефон станет выходной нодой: клиенты подключаются к нему через транспорт "
                        + "выбранного профиля, их трафик выходит в интернет с IP телефона. Профиль "
                        + "должен совпадать с профилем клиента (тот же документ, ключ и режим). Для "
                        + "Direct укажите адрес прослушивания, например 0.0.0.0:8445. Экран можно "
                        + "выключать, нода продолжит работу.",
                12, secondary, false);
        setInitialVisibility(exitHint, MODE_EXIT.equals(editorConnectionMode));
        LinearLayout.LayoutParams exitHintParams = matchWrap();
        exitHintParams.topMargin = dp(10);
        exitHintParams.leftMargin = dp(4);
        exitHintParams.rightMargin = dp(4);
        section.addView(exitHint, exitHintParams);

        boolean proxySelected = MODE_PROXY.equals(editorConnectionMode);

        proxyPortInput = settingInput("Порт", String.valueOf(editorProxyPort), InputType.TYPE_CLASS_NUMBER);
        View portRow = settingRow(R.drawable.ic_swap, "Локальный порт SOCKS5", proxyPortInput);
        setInitialVisibility(portRow, proxySelected);
        LinearLayout.LayoutParams portParams = matchWrap();
        portParams.topMargin = dp(14);
        section.addView(portRow, portParams);

        Switch lanSwitch = settingSwitch(R.drawable.ic_public, "Доступ из локальной сети",
                "Прокси станет виден другим устройствам в этой же Wi-Fi/LAN", editorProxyLanAccess);
        View lanRow = (View) lanSwitch.getTag();
        setInitialVisibility(lanRow, proxySelected);
        LinearLayout.LayoutParams lanParams = matchWrap();
        lanParams.topMargin = dp(8);
        section.addView(lanRow, lanParams);

        String localIp = getLocalIpAddress();
        TextView lanAddressHint = text(
                localIp != null
                        ? "Адрес в сети: " + localIp + ":" + editorProxyPort
                        : "Не удалось определить IP - проверьте подключение к Wi-Fi",
                13, accent, true);
        lanAddressHint.setPadding(dp(14), dp(12), dp(14), dp(12));
        lanAddressHint.setBackground(rounded(darkMode ? Color.rgb(38, 50, 68) : Color.rgb(232, 240, 254),
                Color.TRANSPARENT, 0, 10));
        setInitialVisibility(lanAddressHint, proxySelected && editorProxyLanAccess);
        LinearLayout.LayoutParams lanAddressParams = matchWrap();
        lanAddressParams.topMargin = dp(8);
        section.addView(lanAddressHint, lanAddressParams);

        Switch authSwitch = settingSwitch(R.drawable.ic_lock, "Логин и пароль",
                "Требовать авторизацию для подключения к прокси", editorProxyAuthEnabled);
        View authRow = (View) authSwitch.getTag();
        setInitialVisibility(authRow, proxySelected && editorProxyLanAccess);
        LinearLayout.LayoutParams authParams = matchWrap();
        authParams.topMargin = dp(8);
        section.addView(authRow, authParams);
        View lanWarningHint = fieldHint(
                "Без пароля прокси в локальной сети открыт для всех: любой в этой Wi-Fi сможет "
                        + "ходить в интернет через ваш туннель.");
        setInitialVisibility(lanWarningHint, proxySelected && editorProxyLanAccess);
        section.addView(lanWarningHint);

        LinearLayout credentialsBlock = new LinearLayout(this);
        credentialsBlock.setOrientation(LinearLayout.VERTICAL);
        setInitialVisibility(credentialsBlock, proxySelected && editorProxyLanAccess && editorProxyAuthEnabled);
        LinearLayout.LayoutParams credentialsParams = matchWrap();
        credentialsParams.topMargin = dp(10);
        section.addView(credentialsBlock, credentialsParams);

        proxyUsernameInput = settingInput("Логин", proxyUsername, InputType.TYPE_CLASS_TEXT);
        credentialsBlock.addView(iconTextField(R.drawable.ic_person, proxyUsernameInput, null),
                new LinearLayout.LayoutParams(-1, dp(FIELD_HEIGHT)));
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(-1, dp(FIELD_HEIGHT));
        passwordParams.topMargin = dp(4);
        credentialsBlock.addView(buildProxyPasswordField(), passwordParams);

        Button generateCreds = new Button(this);
        generateCreds.setText("Сгенерировать логин и пароль");
        generateCreds.setAllCaps(false);
        generateCreds.setTextColor(accent);
        generateCreds.setTextSize(13);
        generateCreds.setStateListAnimator(null);
        generateCreds.setBackground(ripple(Color.TRANSPARENT, 9));
        generateCreds.setOnClickListener(v -> {
            bounce(v);
            generateProxyCredentials();
        });
        LinearLayout.LayoutParams generateParams = new LinearLayout.LayoutParams(-1, dp(44));
        generateParams.topMargin = dp(2);
        credentialsBlock.addView(generateCreds, generateParams);

        View shareCard = buildProxyShareCard();
        setInitialVisibility(shareCard, proxySelected);
        LinearLayout.LayoutParams shareParams = matchWrap();
        shareParams.topMargin = dp(14);
        section.addView(shareCard, shareParams);

        TextView reliabilityTitle = label("НАДЁЖНОСТЬ");
        LinearLayout.LayoutParams reliabilityTitleParams = matchWrap();
        reliabilityTitleParams.topMargin = dp(26);
        reliabilityTitleParams.bottomMargin = dp(8);
        section.addView(reliabilityTitle, reliabilityTitleParams);

        LinearLayout batteryRow = cardRow(R.drawable.ic_power, "Отключить оптимизацию батареи",
                "Чтобы система не убивала соединение в фоне");
        batteryRow.setClickable(true);
        batteryRow.setFocusable(true);
        batteryRow.setOnClickListener(v -> {
            bounce(v);
            requestIgnoreBatteryOptimizations();
        });
        section.addView(batteryRow, matchWrap());

        LinearLayout alwaysOnRow = cardRow(R.drawable.ic_lock, "Настройки Always-on Tunnel",
                "Включите \"Блокировать соединения без туннеля\" для защиты от утечек при обрыве");
        alwaysOnRow.setClickable(true);
        alwaysOnRow.setFocusable(true);
        alwaysOnRow.setOnClickListener(v -> {
            bounce(v);
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        });
        setInitialVisibility(alwaysOnRow, MODE_TUNNEL.equals(editorConnectionMode));
        LinearLayout.LayoutParams alwaysOnParams = matchWrap();
        alwaysOnParams.topMargin = dp(8);
        section.addView(alwaysOnRow, alwaysOnParams);

        onModeChanged[0] = () -> {
            boolean nowProxy = MODE_PROXY.equals(editorConnectionMode);
            setViewVisibleAnimated(exitHint, MODE_EXIT.equals(editorConnectionMode));
            setViewVisibleAnimated(portRow, nowProxy);
            setViewVisibleAnimated(lanRow, nowProxy);
            setViewVisibleAnimated(lanAddressHint, nowProxy && editorProxyLanAccess);
            setViewVisibleAnimated(authRow, nowProxy && editorProxyLanAccess);
            setViewVisibleAnimated(lanWarningHint, nowProxy && editorProxyLanAccess);
            setViewVisibleAnimated(credentialsBlock, nowProxy && editorProxyLanAccess && editorProxyAuthEnabled);
            setViewVisibleAnimated(shareCard, nowProxy);
            setViewVisibleAnimated(alwaysOnRow, MODE_TUNNEL.equals(editorConnectionMode));
        };

        lanSwitch.setOnCheckedChangeListener((button, checked) -> {
            tap(button);
            editorProxyLanAccess = checked;
            if (proxyShareQr != null) setViewVisibleAnimated(proxyShareQr, checked);
            setViewVisibleAnimated(lanAddressHint, checked);
            setViewVisibleAnimated(authRow, checked);
            setViewVisibleAnimated(lanWarningHint, checked);
            setViewVisibleAnimated(credentialsBlock, checked && editorProxyAuthEnabled);
        });

        authSwitch.setOnCheckedChangeListener((button, checked) -> {
            tap(button);
            editorProxyAuthEnabled = checked;
            setViewVisibleAnimated(credentialsBlock, checked);
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
        boolean modeChanging = !editorConnectionMode.equals(connectionMode);
        String oldMode = connectionMode;
        boolean oldWasRunning = isConnectionRunning();

        connectionMode = editorConnectionMode;
        proxyLanAccess = editorProxyLanAccess;
        proxyAuthEnabled = editorProxyAuthEnabled;
        int newPort;
        try {
            newPort = Integer.parseInt(proxyPortInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            newPort = DEFAULT_PROXY_PORT;
        }
        proxyPort = Math.max(1024, Math.min(65535, newPort));
        proxyUsername = proxyUsernameInput.getText().toString().trim();
        proxyPassword = proxyPasswordInput.getText().toString().trim();
        persistSettings();

        if (modeChanging && oldWasRunning) {
            stopConnection(oldMode);
            appendLog("Режим изменён - предыдущее соединение (" + modeLabel(oldMode) + ") остановлено");
        }
    }

    // buildProxyShareCard renders the socks:// link (and a QR encoding it)
    // that another device can use to add this proxy in an app like Happ or
    // Telegram, using whatever host/port/credentials are currently saved.
    // It reflects state as of when this settings screen was built, not live
    // as the user edits fields above - consistent with how other settings
    // here only take effect after being saved/reopened.
    private View buildProxyShareCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(surface, border, 1, 11));
        card.addView(text("Ссылка для подключения с другого устройства", 12, secondary, false));

        String link = proxyShareLink();
        TextView linkView = text(link, 14, text, true);
        linkView.setTextIsSelectable(true);
        LinearLayout.LayoutParams linkParams = matchWrap();
        linkParams.topMargin = dp(6);
        card.addView(linkView, linkParams);

        Button copyButton = new Button(this);
        copyButton.setText("Копировать ссылку");
        copyButton.setAllCaps(false);
        copyButton.setTextColor(accent);
        copyButton.setTextSize(13);
        copyButton.setStateListAnimator(null);
        copyButton.setBackground(ripple(Color.TRANSPARENT, 9));
        copyButton.setOnClickListener(v -> {
            bounce(v);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlux SOCKS5", link));
            }
            Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(-1, dp(40));
        copyParams.topMargin = dp(2);
        card.addView(copyButton, copyParams);

        Bitmap qr = generateQrBitmap(link, dp(180));
        proxyShareQr = null;
        if (qr != null) {
            // Only another device can scan it, which needs LAN access.
            ImageView qrView = new ImageView(this);
            qrView.setImageBitmap(qr);
            setInitialVisibility(qrView, editorProxyLanAccess);
            proxyShareQr = qrView;
            LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(180), dp(180));
            qrParams.topMargin = dp(10);
            qrParams.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(qrView, qrParams);
        }

        return card;
    }

    private String proxyShareLink() {
        String host = proxyLanAccess ? getLocalIpAddress() : null;
        if (host == null) host = "127.0.0.1";
        String auth = "";
        if (proxyLanAccess && proxyAuthEnabled && !proxyUsername.isEmpty()) {
            auth = proxyUsername + ":" + proxyPassword + "@";
        }
        return "socks://" + auth + host + ":" + proxyPort;
    }

    private Bitmap generateQrBitmap(String content, int sizePx) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx);
            int foreground = darkMode ? Color.WHITE : Color.BLACK;
            int background = darkMode ? Color.BLACK : Color.WHITE;
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
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Оптимизация батареи уже отключена для приложения", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception exception) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // setInitialVisibility sets a view's starting visibility/alpha without
    // animating, for use when building a page (as opposed to
    // setViewVisibleAnimated, which is for reacting to a toggle afterwards).
    private void setInitialVisibility(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
        view.setAlpha(visible ? 1f : 0f);
    }

    private View buildProxyPasswordField() {
        proxyPasswordInput = settingInput("Пароль", proxyPassword,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        proxyPasswordInput.setTransformationMethod(
                proxyPasswordVisible ? null : PasswordTransformationMethod.getInstance());
        proxyPasswordVisibilityButton = iconButton(
                proxyPasswordVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility,
                proxyPasswordVisible ? "Скрыть пароль" : "Показать пароль");
        proxyPasswordVisibilityButton.setOnClickListener(v -> {
            tap(v);
            toggleProxyPasswordVisibility();
        });
        return iconTextField(R.drawable.ic_key, proxyPasswordInput, proxyPasswordVisibilityButton);
    }

    // iconTextField lays out a leading icon and an EditText that fills the
    // rest of the row (with a reasonable gap between them, rather than
    // settingRow's separate caption + far-right fixed-width value box, which
    // doesn't read well when the "value" is itself the thing being typed),
    // plus an optional trailing action button (e.g. a show/hide toggle).
    private View iconTextField(int iconRes, EditText input, ImageButton trailingButton) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, trailingButton != null ? dp(2) : dp(14), 0);
        row.setBackground(rounded(surface, border, 1, 10));
        row.addView(icon(iconRes, secondary), new LinearLayout.LayoutParams(dp(20), dp(20)));
        input.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, -1, 1f);
        inputParams.leftMargin = dp(12);
        row.addView(input, inputParams);
        if (trailingButton != null) {
            row.addView(trailingButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        return floating(row, input);
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
        Toast.makeText(this, "Логин и пароль созданы - нажмите «Применить»", Toast.LENGTH_SHORT).show();
    }

    private View buildAboutSettings() {
        LinearLayout section = page();
        TextView intro = text(
                "OpenFlux - экспериментальный туннель-клиент поверх документ-транспорта. "
                        + "Это доработанный форк общедоступного проекта под Android.",
                13, secondary, false);
        section.addView(intro, matchWrap());

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.VERTICAL);
        links.addView(aboutLinkRow(R.drawable.ic_github, "Основной репозиторий",
                "p1neappleXpress/OpenFlux", MAIN_REPO_URL), matchWrap());
        links.addView(aboutLinkRow(R.drawable.ic_github, "Наш форк",
                "damnurmum/OpenFlux-Android", FORK_REPO_URL), matchWrap());
        links.addView(aboutLinkRow(R.drawable.ic_telegram, "Telegram чат",
                "@openflux_chat", "https://t.me/openflux_chat"), matchWrap());
        links.addView(aboutLinkRow(R.drawable.ic_discord, "Discord",
                "discord.gg/openfluxx", "https://discord.gg/8a4S3QAh62"), matchWrap());
        groupTiles(links);
        LinearLayout.LayoutParams linksParams = matchWrap();
        linksParams.topMargin = dp(20);
        section.addView(links, linksParams);
        return section;
    }

    private View aboutLinkRow(int iconRes, String titleValue, String detailValue, String url) {
        LinearLayout row = cardRow(iconRes, titleValue, detailValue);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            bounce(v);
            openUrl(url);
        });
        return row;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    private View wrapScroll(View sectionContent) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, navClearance());
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(sectionContent, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    // Content now scrolls behind the floating nav pill (for the fade effect
    // behind it), so every scrollable page needs this much extra bottom room
    // or its last item - often a Save button - ends up stuck under the pill.
    private int navClearance() {
        return navHeight() + dp(10) + navBottomInset + dp(16);
    }

    private View buildProfilesPage() {
        LinearLayout page = page();
        if (profileEditorOpen) {
            page.addView(buildProfileEditorHeader());
            LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            editorParams.topMargin = dp(16);
            View editor = wrapScroll(buildProfileEditor());
            // The save button is pinned below instead, above the nav pill.
            editor.setPadding(0, 0, 0, dp(8));
            page.addView(editor, editorParams);
            Button save = primaryButton("Сохранить профиль", () -> saveProfileFromEditor(
                    profileNameInput.getText().toString().trim(),
                    urlInput.getText().toString().trim(),
                    encryptionInput.getText().toString().trim(),
                    maxTokenInput.getText().toString().trim(),
                    maxUidInput.getText().toString().trim()));
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(52));
            saveParams.topMargin = dp(10);
            saveParams.bottomMargin = navClearance();
            page.addView(save, saveParams);
            pinnedSave = save;
            if (keyboardOpen) save.setVisibility(View.GONE);
            return page;
        }

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Профили", 25, text, true));
        titles.addView(text("Наборы параметров для разных серверов", 12, secondary, false), matchWrap());
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageButton nodeButton = iconButton(R.drawable.ic_terminal, "Создать свою ноду");
        nodeButton.setOnClickListener(v -> {
            tap(v);
            openNodeWizard();
        });
        LinearLayout.LayoutParams nodeParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        nodeParams.rightMargin = dp(8);
        header.addView(nodeButton, nodeParams);
        ImageButton scanButton = iconButton(R.drawable.ic_qr_scan, "Сканировать QR");
        scanButton.setOnClickListener(v -> {
            tap(v);
            scanShareQr();
        });
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        scanParams.rightMargin = dp(8);
        header.addView(scanButton, scanParams);
        ImageButton addButton = iconButton(R.drawable.ic_add, "Добавить профиль");
        addButton.setOnClickListener(v -> {
            tap(v);
            openProfileEditor(null);
        });
        header.addView(addButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        page.addView(header);

        if (profiles.isEmpty()) {
            TextView empty = text("Пока нет ни одного профиля. Нажмите + и добавьте первый.", 13, secondary, false);
            LinearLayout.LayoutParams emptyParams = matchWrap();
            emptyParams.topMargin = dp(24);
            page.addView(empty, emptyParams);
            Button createNode = primaryButton("Создать свою ноду на VDS", this::openNodeWizard);
            LinearLayout.LayoutParams nodeButtonParams = new LinearLayout.LayoutParams(-1, dp(52));
            nodeButtonParams.topMargin = dp(16);
            page.addView(createNode, nodeButtonParams);
            return wrapScroll(page);
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(rounded(surface, border, 1, 12));
        for (int i = 0; i < profiles.size(); i++) {
            list.addView(buildProfileListRow(profiles.get(i)));
            if (i < profiles.size() - 1) addDivider(list, 0);
        }
        LinearLayout.LayoutParams listParams = matchWrap();
        listParams.topMargin = dp(20);
        page.addView(list, listParams);
        return wrapScroll(page);
    }

    private View buildProfileListRow(Profile p) {
        boolean selected = p.id == selectedProfileId;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(6), dp(10));
        row.setBackground(ripple(Color.TRANSPARENT, 0));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(iconBubble(profileIconRes(p.icon)), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = dp(16);
        copy.addView(text(p.name, 15, text, selected));
        copy.addView(text(profileTransportSummary(p) + (selected ? " · активен" : ""), 12, secondary, false));
        row.addView(copy, copyParams);
        ImageButton editButton = iconButton(R.drawable.ic_settings, "Изменить профиль «" + p.name + "»");
        editButton.setOnClickListener(v -> {
            tap(v);
            openProfileEditor(p);
        });
        row.addView(editButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        ImageButton deleteButton = iconButton(R.drawable.ic_delete, "Удалить профиль «" + p.name + "»");
        deleteButton.setOnClickListener(v -> {
            tap(v);
            deleteProfile(p);
        });
        row.addView(deleteButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.setOnClickListener(v -> {
            bounce(v);
            selectProfile(p.id);
        });
        return row;
    }

    private View buildProfileEditorHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_arrow_back, "Назад к профилям");
        back.setOnClickListener(v -> {
            bounce(v);
            closeProfileEditor();
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        backParams.rightMargin = dp(6);
        header.addView(back, backParams);
        header.addView(text(editingProfileId != null ? "Изменить профиль" : "Новый профиль", 22, text, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        return header;
    }

    private View buildProfileEditor() {
        Profile existing = null;
        if (editingProfileId != null) {
            for (Profile p : profiles) if (p.id == editingProfileId) { existing = p; break; }
        }
        String initialUrl = existing != null ? existing.documentUrl : "";
        String initialSecret = existing != null ? existing.encryptionSecret : "";

        LinearLayout section = page();

        FrameLayout nameField = new FrameLayout(this);
        nameField.setBackground(rounded(surface, border, 1, 10));
        profileNameInput = settingInput("Название профиля", existing != null ? existing.name : "",
                InputType.TYPE_CLASS_TEXT);
        profileNameInput.setPadding(dp(16), 0, dp(16), 0);
        nameField.addView(profileNameInput, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(FIELD_HEIGHT));
        section.addView(floating(nameField, profileNameInput), nameParams);

        TextView iconLabel = label("ЗНАЧОК");
        LinearLayout.LayoutParams iconLabelParams = matchWrap();
        iconLabelParams.topMargin = dp(18);
        iconLabelParams.bottomMargin = dp(8);
        section.addView(iconLabel, iconLabelParams);
        section.addView(buildIconPicker(), matchWrap());

        TextView modeLabel = label("РЕЖИМ");
        LinearLayout.LayoutParams modeLabelParams = matchWrap();
        modeLabelParams.topMargin = dp(18);
        modeLabelParams.bottomMargin = dp(8);
        section.addView(modeLabel, modeLabelParams);
        section.addView(buildSessionModeSelector(), matchWrap());

        TextView transportTypeLabel = label("ТРАНСПОРТ");
        LinearLayout.LayoutParams transportTypeLabelParams = matchWrap();
        transportTypeLabelParams.topMargin = dp(18);
        transportTypeLabelParams.bottomMargin = dp(8);
        section.addView(transportTypeLabel, transportTypeLabelParams);
        section.addView(buildTransportTypeSelector(), matchWrap());

        LinearLayout.LayoutParams maxFieldsParams = matchWrap();
        maxFieldsParams.topMargin = dp(8);
        maxFieldsContainer = buildMaxFields(existing);
        maxFieldsContainer.setVisibility("oneme".equals(editorTransportType) ? View.VISIBLE : View.GONE);
        section.addView(maxFieldsContainer, maxFieldsParams);

        // Session always uses the batched codec, so the choice only exists in
        // classic mode.
        LinearLayout codecBox = new LinearLayout(this);
        codecBox.setOrientation(LinearLayout.VERTICAL);
        TextView codecLabel = label("КОДЕК");
        LinearLayout.LayoutParams codecLabelParams = matchWrap();
        codecLabelParams.topMargin = dp(18);
        codecLabelParams.bottomMargin = dp(8);
        codecBox.addView(codecLabel, codecLabelParams);
        codecBox.addView(buildCodecSelector(), matchWrap());
        codecSection = codecBox;
        section.addView(codecBox, matchWrap());

        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(-1, dp(FIELD_HEIGHT));
        urlParams.topMargin = dp(10);
        urlField = buildUrlField(initialUrl);
        // MAX has its own token field above; the link field would duplicate it.
        urlField.setVisibility("oneme".equals(editorTransportType) ? View.GONE : View.VISIBLE);
        section.addView(urlField, urlParams);

        LinearLayout.LayoutParams encryptionParams = new LinearLayout.LayoutParams(-1, dp(FIELD_HEIGHT));
        encryptionParams.topMargin = dp(8);
        section.addView(buildEncryptionField(initialSecret), encryptionParams);
        TextView encryptionHint = text("", 11, secondary, false);
        encryptionHintView = encryptionHint;
        LinearLayout.LayoutParams encryptionHintParams = matchWrap();
        encryptionHintParams.topMargin = dp(5);
        encryptionHintParams.leftMargin = dp(4);
        encryptionHintParams.rightMargin = dp(4);
        section.addView(encryptionHint, encryptionHintParams);
        Button generateKey = new Button(this);
        generateKey.setText("Сгенерировать безопасный ключ");
        generateKey.setAllCaps(false);
        generateKey.setTextColor(accent);
        generateKey.setTextSize(13);
        generateKey.setStateListAnimator(null);
        generateKey.setBackground(ripple(Color.TRANSPARENT, 9));
        generateKey.setOnClickListener(v -> {
            tap(v);
            generateEncryptionSecret();
        });
        LinearLayout.LayoutParams generateParams = new LinearLayout.LayoutParams(-1, dp(44));
        generateParams.topMargin = dp(4);
        section.addView(generateKey, generateParams);

        sessionFieldsContainer = buildSessionFields();
        section.addView(sessionFieldsContainer, matchWrap());
        applySessionVisibility();

        if (existing != null) {
            Profile toDelete = existing;
            Button delete = new Button(this);
            delete.setText("Удалить профиль");
            delete.setAllCaps(false);
            delete.setTextColor(darkMode ? Color.rgb(242, 139, 130) : Color.rgb(217, 48, 37));
            delete.setTextSize(14);
            delete.setStateListAnimator(null);
            delete.setBackground(ripple(Color.TRANSPARENT, 9));
            delete.setOnClickListener(v -> {
                tap(v);
                deleteProfile(toDelete);
            });
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-1, dp(44));
            deleteParams.topMargin = dp(6);
            deleteParams.bottomMargin = dp(12);
            section.addView(delete, deleteParams);
        }
        return section;
    }

    private View buildSessionModeSelector() {
        return segmented(new String[]{"Классический", "Session"}, editorSession ? 1 : 0, i -> {
            editorSession = i == 1;
            applySessionVisibility();
        });
    }

    private void applySessionVisibility() {
        if (sessionFieldsContainer != null) {
            sessionFieldsContainer.setVisibility(editorSession ? View.VISIBLE : View.GONE);
        }
        if (codecSection != null) codecSection.setVisibility(editorSession ? View.GONE : View.VISIBLE);
        // Session cannot run without the key; classic mode can.
        setFloatingLabel(encryptionInput, editorSession
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
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView sessionLabel = label("SESSION");
        LinearLayout.LayoutParams sessionLabelParams = matchWrap();
        sessionLabelParams.topMargin = dp(18);
        sessionLabelParams.bottomMargin = dp(6);
        box.addView(sessionLabel, sessionLabelParams);
        TextView hintView = text(
                "Все транспорты работают одновременно, трафик идёт по самому приоритетному "
                        + "из работающих, при его отказе - по следующему. Нода должна быть запущена "
                        + "с --negotiate и теми же транспортами (--transports), в --url - ссылка "
                        + "самого приоритетного транспорта с документом. Ключ шифрования обязателен.",
                11, secondary, false);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.leftMargin = dp(4);
        hintParams.rightMargin = dp(4);
        box.addView(hintView, hintParams);

        priorityInput = settingInput("Приоритет основного транспорта (больше - важнее)",
                Integer.toString(editorPriority), InputType.TYPE_CLASS_NUMBER);
        boxedInput(box, priorityInput, dp(16));

        TextView extrasLabel = label("ДОПОЛНИТЕЛЬНЫЕ ТРАНСПОРТЫ");
        LinearLayout.LayoutParams extrasLabelParams = matchWrap();
        extrasLabelParams.topMargin = dp(18);
        box.addView(extrasLabel, extrasLabelParams);

        extrasList = new LinearLayout(this);
        extrasList.setOrientation(LinearLayout.VERTICAL);
        box.addView(extrasList, matchWrap());
        refreshExtras();

        Button add = new Button(this);
        add.setText("Добавить транспорт");
        add.setAllCaps(false);
        add.setTextColor(accent);
        add.setTextSize(13);
        add.setStateListAnimator(null);
        add.setBackground(ripple(Color.TRANSPARENT, 9));
        add.setOnClickListener(v -> {
            tap(v);
            editorExtras.add(new Profile.Transport());
            refreshExtras();
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(44));
        addParams.topMargin = dp(4);
        box.addView(add, addParams);
        return box;
    }

    private void refreshExtras() {
        if (extrasList == null) return;
        extrasList.removeAllViews();
        for (Profile.Transport t : editorExtras) {
            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.topMargin = dp(8);
            extrasList.addView(buildExtraTransportRow(t), rowParams);
        }
    }

    private View buildExtraTransportRow(Profile.Transport t) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(surface, border, 1, 20));
        card.setPadding(dp(14), dp(4), dp(14), dp(14));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text(transportLabel(t.type), 15, text, true), new LinearLayout.LayoutParams(0, -2, 1f));
        Button remove = new Button(this);
        remove.setText("Удалить");
        remove.setAllCaps(false);
        remove.setTextColor(darkMode ? Color.rgb(242, 139, 130) : Color.rgb(217, 48, 37));
        remove.setTextSize(13);
        remove.setStateListAnimator(null);
        remove.setBackground(ripple(Color.TRANSPARENT, 9));
        remove.setOnClickListener(v -> {
            tap(v);
            editorExtras.remove(t);
            refreshExtras();
        });
        header.addView(remove, new LinearLayout.LayoutParams(-2, dp(44)));
        card.addView(header, matchWrap());

        String[] types = {"direct", "yandex", "vyandex", "boards", "mailru", "cupsonline", "oneme"};
        String[] typeLabels = {"Direct", "Yandex", "Volga", "Board", "Mail.ru", "Cups", "MAX"};
        card.addView(chips(typeLabels, java.util.Arrays.asList(types).indexOf(t.type), i -> {
            t.type = types[i];
            t.value = "";
            t.uid = "";
            refreshExtras();
        }), matchWrap());

        boolean max = "oneme".equals(t.type);
        boolean direct = "direct".equals(t.type);
        EditText value = settingInput(transportValueLabel(t.type), t.value,
                max ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        bindText(value, v -> t.value = v);
        boxedInput(card, value, dp(12));
        if (max) {
            EditText uid = settingInput("MAX call user id", t.uid, InputType.TYPE_CLASS_NUMBER);
            bindText(uid, v -> t.uid = v);
            boxedInput(card, uid, dp(12));
        }
        EditText priority = settingInput("Приоритет (больше - важнее)", Integer.toString(t.priority),
                InputType.TYPE_CLASS_NUMBER);
        bindText(priority, v -> t.priority = parsePriority(v, t.priority));
        boxedInput(card, priority, dp(12));
        return card;
    }

    // Returns why an extra Session transport can't be saved, or null.
    private String extraTransportProblem(Profile.Transport t) {
        return transportValueProblem(t.type, t.value, true);
    }

    private static final Pattern CUPS_ROOMS_CODE = Pattern.compile("[A-Za-z0-9_-]+");

    // Returns why value can't serve as the transport's main field (document
    // link, board link, room code, node address, token), or null. Cups.online
    // takes the base64 room code the exit prints, or a link carrying it; the
    // exit itself creates the rooms and needs nothing, so an empty code is
    // allowed when saving (allowEmptyCups) and checked again on connect.
    private String transportValueProblem(String type, String value, boolean allowEmptyCups) {
        String label = transportLabel(type);
        switch (type) {
            case "direct": {
                int colon = value.lastIndexOf(':');
                int port = colon > 0 ? parsePriority(value.substring(colon + 1), -1) : -1;
                return colon <= 0 || port < 1 || port > 65535 ? label + ": укажите адрес ноды в виде host:port" : null;
            }
            case "oneme":
                return value.isEmpty() ? "MAX: укажите Web token" : null;
            case "cupsonline":
                if (value.isEmpty()) return allowEmptyCups ? null : "Cups.online: укажите код комнат с ноды";
                return isValidDocumentUrl(value) || CUPS_ROOMS_CODE.matcher(value).matches()
                        ? null : "Cups.online: код комнат - строка base64 из лога ноды";
            case "boards":
                return isValidDocumentUrl(value) ? null : label + ": укажите HTTPS-ссылку на доску";
            default:
                return isValidDocumentUrl(value) ? null : label + ": укажите HTTPS-ссылку на документ";
        }
    }

    // The main field's label for a transport type.
    private static String transportValueLabel(String type) {
        switch (type) {
            case "direct": return "Адрес ноды (host:port)";
            case "oneme": return "MAX Web token";
            case "boards": return "Ссылка на доску Yandex Board";
            case "mailru": return "Ссылка на документ Mail.ru";
            case "cupsonline": return "Код комнат Cups.online (base64)";
            case "vyandex": return "Ссылка на документ Yandex (Volga)";
            default: return "Ссылка на документ Yandex";
        }
    }

    private static int parsePriority(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void boxedInput(LinearLayout parent, EditText input, int topMargin) {
        FrameLayout field = new FrameLayout(this);
        field.setBackground(rounded(surface, border, 1, 10));
        input.setPadding(dp(16), 0, dp(16), 0);
        field.addView(input, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(FIELD_HEIGHT));
        params.topMargin = Math.max(0, topMargin - dp(FLOAT_ROOM));
        parent.addView(floating(field, input), params);
    }

    private static void bindText(EditText input, Consumer<String> sink) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { sink.accept(s.toString().trim()); }
        });
    }

    private View buildIconPicker() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View[] cells = new View[PROFILE_ICON_KEYS.length];
        ImageView[] iconViews = new ImageView[PROFILE_ICON_KEYS.length];
        for (int i = 0; i < PROFILE_ICON_KEYS.length; i++) {
            FrameLayout cell = new FrameLayout(this);
            ImageView iconView = icon(profileIconRes(PROFILE_ICON_KEYS[i]), secondary);
            cells[i] = cell;
            iconViews[i] = iconView;
            cell.addView(iconView, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            cell.setClickable(true);
            LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(dp(42), dp(42));
            cellParams.rightMargin = dp(8);
            row.addView(cell, cellParams);
        }
        Runnable refreshCells = () -> {
            for (int i = 0; i < PROFILE_ICON_KEYS.length; i++) {
                boolean selected = PROFILE_ICON_KEYS[i].equals(editorIcon);
                cells[i].setBackground(rounded(selected ? accent : surface, border, 1, 10));
                iconViews[i].setImageTintList(ColorStateList.valueOf(selected ? Color.WHITE : secondary));
            }
        };
        refreshCells.run();
        for (int i = 0; i < PROFILE_ICON_KEYS.length; i++) {
            String key = PROFILE_ICON_KEYS[i];
            cells[i].setOnClickListener(v -> {
                tap(v);
                editorIcon = key;
                refreshCells.run();
            });
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        return scroll;
    }

    private View buildTransportTypeSelector() {
        String[] types = {"yandex", "vyandex", "boards", "mailru", "cupsonline", "oneme"};
        int current = Math.max(0, java.util.Arrays.asList(types).indexOf(editorTransportType));
        return choiceList(new String[][]{
                {"Yandex Docs", "Документ Яндекса"},
                {"Yandex Docs (Volga)", "Экспериментальный"},
                {"Yandex Board", "Доска Яндекса, экспериментальный"},
                {"Mail.ru Docs", "Документ в Облаке Mail.ru"},
                {"Cups.online", "Комнаты live-coding, код комнат с ноды"},
                {"MAX (OneMe)", "Звонок MAX, нужен Web token"},
        }, new int[]{R.drawable.ic_yandex, R.drawable.ic_yandex, R.drawable.ic_yandex,
                R.drawable.ic_mailru, R.drawable.ic_code, R.drawable.ic_max}, current, i -> {
            editorTransportType = types[i];
            if (maxFieldsContainer != null) {
                maxFieldsContainer.setVisibility("oneme".equals(editorTransportType) ? View.VISIBLE : View.GONE);
            }
            setFloatingLabel(urlInput, transportValueLabel(editorTransportType));
            if (urlField != null) urlField.setVisibility("oneme".equals(editorTransportType) ? View.GONE : View.VISIBLE);
        });
    }

    // MAX (OneMe) authenticates via a web token + numeric user id instead of
    // a document URL - only shown/required when that transport is selected.
    private View buildMaxFields(Profile existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        FrameLayout tokenField = new FrameLayout(this);
        tokenField.setBackground(rounded(surface, border, 1, 10));
        maxTokenInput = settingInput("MAX Web token", existing != null ? existing.maxToken : "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        maxTokenInput.setPadding(dp(16), 0, dp(16), 0);
        tokenField.addView(maxTokenInput, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams tokenParams = new LinearLayout.LayoutParams(-1, dp(FIELD_HEIGHT));
        box.addView(floating(tokenField, maxTokenInput), tokenParams);

        FrameLayout uidField = new FrameLayout(this);
        uidField.setBackground(rounded(surface, border, 1, 10));
        maxUidInput = settingInput("MAX call user id", existing != null ? existing.maxUid : "",
                InputType.TYPE_CLASS_NUMBER);
        maxUidInput.setPadding(dp(16), 0, dp(16), 0);
        uidField.addView(maxUidInput, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams uidParams = new LinearLayout.LayoutParams(-1, dp(FIELD_HEIGHT));
        uidParams.topMargin = dp(8);
        box.addView(floating(uidField, maxUidInput), uidParams);

        return box;
    }

    private View buildCodecSelector() {
        return choiceList(new String[][]{
                {"Batched + zstd", "По умолчанию"},
                {"Legacy (LZ4)", "Для совместимости со старым exit-node"},
        }, new int[]{R.drawable.ic_codec_fast, R.drawable.ic_codec_legacy}, "legacy".equals(editorCodec) ? 1 : 0, i -> editorCodec = i == 1 ? "legacy" : "batched");
    }

    private View buildNetworkSettings() {
        LinearLayout section = page();

        Switch dnsAutoSwitch = settingSwitch(R.drawable.ic_public, "DNS-сервер: Авто",
                "Тот же DNS, что использовала сеть до подключения туннеля - как у desktop-клиента",
                editorDnsAuto);
        View dnsAutoRow = (View) dnsAutoSwitch.getTag();
        section.addView(dnsAutoRow, matchWrap());

        dnsInput = settingInput("DNS-сервер", editorDnsServer,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        View dnsInputRow = settingRow(R.drawable.ic_public, "Свой DNS", dnsInput);
        setInitialVisibility(dnsInputRow, !editorDnsAuto);
        LinearLayout.LayoutParams dnsInputParams = matchWrap();
        dnsInputParams.topMargin = dp(8);
        section.addView(dnsInputRow, dnsInputParams);
        View dnsHint = fieldHint(
                "Сюда уходят запросы «какой IP у сайта», резолвится локально на устройстве. "
                        + "Можно указать IP (1.1.1.1) или домен (dns.google).");
        setInitialVisibility(dnsHint, !editorDnsAuto);
        section.addView(dnsHint);

        dnsAutoSwitch.setOnCheckedChangeListener((button, checked) -> {
            tap(button);
            editorDnsAuto = checked;
            setViewVisibleAnimated(dnsInputRow, !checked);
            setViewVisibleAnimated(dnsHint, !checked);
        });

        mtuInput = settingInput("MTU", String.valueOf(editorMtu), InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams mtuParams = matchWrap();
        mtuParams.topMargin = dp(16);
        section.addView(settingRow(R.drawable.ic_settings, "MTU пакета", mtuInput), mtuParams);
        section.addView(fieldHint(
                "Для чего: максимальный размер пакета в туннеле. Трогать не обязательно - "
                        + "уменьшите (например, до 1280), если сайты грузятся не полностью "
                        + "или соединение обрывается."));

        Switch killSwitchSwitch = settingSwitch(R.drawable.ic_lock, "Kill Switch",
                "Блокировать трафик, если туннель отключился, вместо пропуска мимо него",
                editorKillSwitchEnabled);
        killSwitchSwitch.setOnCheckedChangeListener((button, checked) -> {
            tap(button);
            editorKillSwitchEnabled = checked;
        });
        LinearLayout.LayoutParams killSwitchParams = matchWrap();
        killSwitchParams.topMargin = dp(16);
        section.addView((View) killSwitchSwitch.getTag(), killSwitchParams);
        section.addView(fieldHint(
                "Включено: при обрыве соединения приложения теряют доступ в сеть, а не "
                        + "продолжают работать в обход туннеля. Выключено: примерно через 30 "
                        + "секунд без связи туннель отключится сам и сеть заработает как обычно."));

        return section;
    }

    // Commits the Network draft fields to the live settings. Called by the
    // generic "Сохранить настройки" button in buildSettingsPage() - editing
    // these fields and pressing back without it discards the draft.
    private void applyNetworkSettings() {
        dnsServer = editorDnsAuto ? "" : dnsInput.getText().toString().trim();
        int newMtu;
        try {
            newMtu = Integer.parseInt(mtuInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            newMtu = DEFAULT_MTU;
        }
        mtu = Math.max(576, Math.min(1500, newMtu));
        killSwitchEnabled = editorKillSwitchEnabled;
        persistSettings();
    }

    private void applyInterfaceSettings() {
        autoScroll = editorAutoScroll;
        showSensitiveLogs = editorShowSensitiveLogs;
        joinCelebration = editorJoinCelebration;
        getSharedPreferences(SETTINGS_PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean("auto_scroll", autoScroll)
                .putBoolean("show_sensitive_logs", showSensitiveLogs)
                .putBoolean("join_celebration", joinCelebration)
                .apply();
        // Rebuilds the whole shell/page when the theme actually changed, so
        // it must run last - everything above needs to be committed first.
        switchTheme(editorDarkMode);
    }

    private void applyAppsSettings() {
        appFilterMode = editorAppFilterMode;
        selectedApps.clear();
        selectedApps.addAll(editorSelectedApps);
        persistAppFilter();
    }

    private View buildRoutingSettings() {
        LinearLayout section = page();
        TextView routingHint = text(
                "Домены и сервисы ниже подключаются напрямую, в обход туннеля - полезно для "
                        + "локальных сервисов и всего, что чувствительно к задержке.",
                12, secondary, false);
        section.addView(routingHint, matchWrap());

        if (!isProxyMode()) {
            TextView tunnelNote = text(
                    "Пока работает только в режиме Прокси (SOCKS5). В режиме Туннель настройки "
                            + "сохранятся, но не применяются - переключите режим работы, чтобы им пользоваться.",
                    12, accent, false);
            LinearLayout.LayoutParams tunnelNoteParams = matchWrap();
            tunnelNoteParams.topMargin = dp(8);
            section.addView(tunnelNote, tunnelNoteParams);
        }

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.VERTICAL);
        for (DomainFilter.Preset preset : DomainFilter.PRESETS) {
            Switch presetSwitch = settingSwitch(presetIcon(preset.id), preset.title, preset.description,
                    editorEnabledDomainPresets.contains(preset.id));
            presetSwitch.setOnCheckedChangeListener((button, checked) -> {
                tap(button);
                if (checked) editorEnabledDomainPresets.add(preset.id);
                else editorEnabledDomainPresets.remove(preset.id);
            });
            presets.addView((View) presetSwitch.getTag(), matchWrap());
        }
        groupTiles(presets);
        LinearLayout.LayoutParams presetsParams = matchWrap();
        presetsParams.topMargin = dp(12);
        section.addView(presets, presetsParams);

        LinearLayout.LayoutParams customLabelParams = matchWrap();
        customLabelParams.topMargin = dp(16);
        section.addView(text("Свои домены", 13, secondary, false), customLabelParams);

        customDomainsInput = new EditText(this);
        customDomainsInput.setHint("youtube.com\nexample.org");
        customDomainsInput.setHintTextColor(hint);
        customDomainsInput.setText(String.join("\n", customDomains));
        customDomainsInput.setTextSize(14);
        customDomainsInput.setTextColor(text);
        customDomainsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        customDomainsInput.setMinLines(3);
        customDomainsInput.setGravity(Gravity.TOP | Gravity.START);
        customDomainsInput.setBackground(rounded(surface, border, 1, 10));
        customDomainsInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams customInputParams = matchWrap();
        customInputParams.topMargin = dp(6);
        section.addView(customDomainsInput, customInputParams);
        section.addView(fieldHint("По одному домену на строку, без http:// и путей - например youtube.com."));

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
        enabledDomainPresets.clear();
        enabledDomainPresets.addAll(editorEnabledDomainPresets);
        customDomains.clear();
        for (String line : customDomainsInput.getText().toString().split("\n")) {
            String trimmed = line.trim().toLowerCase(java.util.Locale.ROOT);
            if (!trimmed.isEmpty()) customDomains.add(trimmed);
        }
        persistDomainFilter();
    }

    private void persistDomainFilter() {
        domainFilterPrefs.edit()
                .putStringSet(DomainFilter.KEY_ENABLED_PRESETS, new HashSet<>(enabledDomainPresets))
                .putStringSet(DomainFilter.KEY_CUSTOM_DOMAINS, new HashSet<>(customDomains))
                .apply();
    }

    private TextView fieldHint(String value) {
        TextView hint = text(value, 11, secondary, false);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(5);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        hint.setLayoutParams(params);
        return hint;
    }

    private View buildInterfaceSettings() {
        LinearLayout section = page();
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        section.addView(group, matchWrap());
        Switch themeSwitch = settingSwitch(R.drawable.ic_dark_mode, "Тёмная тема",
                "До первого выбора используется тема телефона", editorDarkMode);
        themeSwitch.setOnCheckedChangeListener((button, checked) -> {
            tap(button);
            editorDarkMode = checked;
        });
        group.addView((View) themeSwitch.getTag(), matchWrap());
        Switch scrollSwitch = settingSwitch(R.drawable.ic_terminal, "Автопрокрутка логов",
                "Показывать последние события", editorAutoScroll);
        scrollSwitch.setOnCheckedChangeListener((button, checked) -> {
            tap(button);
            editorAutoScroll = checked;
        });
        group.addView((View) scrollSwitch.getTag(), matchWrap());

        Switch showSensitiveSwitch = settingSwitch(R.drawable.ic_lock, "Данные в логах",
                "Показывать ссылки, IP и WSS адреса. При выключении скрываются под HIDDEN-URL",
                editorShowSensitiveLogs);
        showSensitiveSwitch.setOnCheckedChangeListener((button, checked) -> {
            tap(button);
            editorShowSensitiveLogs = checked;
        });
        group.addView((View) showSensitiveSwitch.getTag(), matchWrap());

        Switch celebrationSwitch = settingSwitch(R.drawable.ic_check, "Салют при подключении клиента",
                "Режим выходной ноды: вспышка, конфетти и вибрация, когда к телефону подключается клиент",
                editorJoinCelebration);
        celebrationSwitch.setOnCheckedChangeListener((button, checked) -> {
            tap(button);
            editorJoinCelebration = checked;
        });
        group.addView((View) celebrationSwitch.getTag(), matchWrap());
        groupTiles(group);
        return section;
    }

    private View buildAppsSettings() {
        LinearLayout section = page();
        TextView hint = text(
                "Выберите, какие приложения используют туннель. По умолчанию - все приложения, кроме OpenFlux. "
                        + "Действует только в режиме туннеля - в режиме прокси приложения подключаются к SOCKS5 сами.",
                12, secondary, false);
        section.addView(hint, matchWrap());

        String[] filterModes = {AppFilter.MODE_OFF, AppFilter.MODE_WHITELIST, AppFilter.MODE_BLACKLIST};
        Runnable[] onFilterChanged = new Runnable[1];
        View filterChoice = choiceList(new String[][]{
                {"Все приложения"},
                {"Только выбранные", "Белый список"},
                {"Все, кроме выбранных", "Чёрный список"},
        }, null, Math.max(0, java.util.Arrays.asList(filterModes).indexOf(editorAppFilterMode)), i -> {
            editorAppFilterMode = filterModes[i];
            onFilterChanged[0].run();
        });
        LinearLayout.LayoutParams modeGroupParams = matchWrap();
        modeGroupParams.topMargin = dp(12);
        section.addView(filterChoice, modeGroupParams);

        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setVisibility(AppFilter.MODE_OFF.equals(editorAppFilterMode) ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams listContainerParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        listContainerParams.topMargin = dp(14);

        ListView appListView = new ListView(this);
        appListView.setDivider(null);
        appListView.setClipToPadding(false);
        appListView.setPadding(0, 0, 0, dp(8));
        appListView.setVerticalScrollBarEnabled(false);
        appListView.setAdapter(new AppListAdapter(loadInstalledAppsCached()));
        listContainer.addView(appListView, new LinearLayout.LayoutParams(-1, -1));
        section.addView(listContainer, listContainerParams);

        onFilterChanged[0] = () ->
                setViewVisibleAnimated(listContainer, !AppFilter.MODE_OFF.equals(editorAppFilterMode));

        return section;
    }

    // Shows or hides view with a fade while everything below it slides to
    // its new place, instead of the neighbors jumping when it goes GONE.
    private void setViewVisibleAnimated(View view, boolean visible) {
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationY(0f);
        if ((view.getVisibility() == View.VISIBLE) == visible) return;
        ViewGroup sceneRoot = transitionRoot(view);
        if (sceneRoot != null) {
            android.transition.TransitionSet transition = new android.transition.TransitionSet()
                    .setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER)
                    .addTransition(new android.transition.Fade())
                    .addTransition(new android.transition.ChangeBounds());
            transition.setDuration(280);
            transition.setInterpolator(EMPHASIZED);
            // One transition per frame: later calls in the same toggle are
            // captured by the first.
            android.transition.TransitionManager.beginDelayedTransition(sceneRoot, transition);
        }
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // The scrolling page content that holds view, so the whole page
    // reflows together.
    private ViewGroup transitionRoot(View view) {
        View child = view;
        for (android.view.ViewParent p = view.getParent(); p instanceof ViewGroup; p = p.getParent()) {
            if (p instanceof ScrollView || p == content) return child instanceof ViewGroup ? (ViewGroup) child : null;
            child = (View) p;
        }
        return null;
    }

    // accent laid over surface at the given strength: the tonal fill of a
    // selected choice.
    private int tonal(float strength) {
        return Color.rgb(
                (int) (Color.red(surface) + (Color.red(accent) - Color.red(surface)) * strength),
                (int) (Color.green(surface) + (Color.green(accent) - Color.green(surface)) * strength),
                (int) (Color.blue(surface) + (Color.blue(accent) - Color.blue(surface)) * strength));
    }

    private android.graphics.drawable.Drawable tile(int fill, int strokeColor, float[] radii) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadii(radii);
        shape.setStroke(dp(1), strokeColor);
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))),
                shape, null);
    }

    private float[] radii(int radius) {
        float r = dp(radius);
        return new float[]{r, r, r, r, r, r, r, r};
    }

    // Icon on a tonal circle, as in the Android 15-16 settings lists.
    private View iconBubble(int iconRes) {
        FrameLayout bubble = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(tonal(0.2f));
        bubble.setBackground(circle);
        bubble.addView(icon(iconRes, accent), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        return bubble;
    }

    // Stacks the children of list as one group: 2dp gaps, large outer
    // corners, small inner ones, each tile with its own ripple.
    private void groupTiles(LinearLayout list) {
        int n = list.getChildCount();
        for (int i = 0; i < n; i++) {
            float top = dp(i == 0 ? 24 : 6), bottom = dp(i == n - 1 ? 24 : 6);
            View child = list.getChildAt(i);
            child.setBackground(tile(surface, border,
                    new float[]{top, top, top, top, bottom, bottom, bottom, bottom}));
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            params.topMargin = i == 0 ? 0 : dp(2);
            child.setLayoutParams(params);
        }
    }

    // Material 3 switch: a 56x32 pill track; off, an outlined track with a
    // small thumb; on, a filled track with a large thumb carrying a check.
    private void styleSwitch(Switch toggle) {
        // Off state leans toward the accent so the light theme is not a flat grey.
        int off = darkMode ? Color.rgb(140, 147, 160) : tonal(0.55f);
        GradientDrawable trackOn = new GradientDrawable();
        trackOn.setCornerRadius(dp(16));
        trackOn.setColor(accent);
        trackOn.setSize(dp(56), dp(32));
        GradientDrawable trackOff = new GradientDrawable();
        trackOff.setCornerRadius(dp(16));
        trackOff.setColor(darkMode ? background : tonal(0.07f));
        trackOff.setStroke(dp(2), off);
        trackOff.setSize(dp(56), dp(32));
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, trackOn);
        track.addState(new int[]{}, trackOff);
        track.setEnterFadeDuration(150);
        track.setExitFadeDuration(150);

        // Both thumbs are 28x32 (the track is twice as wide), so the track
        // keeps its shape while the visible circle grows.
        android.graphics.drawable.Drawable thumbOn = switchThumb(dp(12), Color.WHITE, accent);
        android.graphics.drawable.Drawable thumbOff = switchThumb(dp(8), off, 0);
        android.graphics.drawable.StateListDrawable thumb = new android.graphics.drawable.StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked}, thumbOn);
        thumb.addState(new int[]{}, thumbOff);
        thumb.setEnterFadeDuration(150);
        thumb.setExitFadeDuration(150);

        toggle.setTrackDrawable(track);
        toggle.setThumbDrawable(thumb);
        toggle.setTrackTintList(null);
        toggle.setThumbTintList(null);
        toggle.setSwitchMinWidth(dp(56));
        toggle.setShowText(false);
        toggle.setBackground(null);
    }

    // A switch thumb: a circle of the given radius centered in 28x32, with
    // a check drawn in checkColor when that is not 0.
    private android.graphics.drawable.Drawable switchThumb(float radius, int fill, int checkColor) {
        return new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path path = new android.graphics.Path();
            @Override public void draw(android.graphics.Canvas canvas) {
                float cx = getBounds().exactCenterX(), cy = getBounds().exactCenterY();
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setColor(fill);
                canvas.drawCircle(cx, cy, radius, paint);
                if (checkColor == 0) return;
                float u = radius / 12f;
                path.reset();
                path.moveTo(cx - 5 * u, cy);
                path.lineTo(cx - 1.5f * u, cy + 3.5f * u);
                path.lineTo(cx + 5.5f * u, cy - 3.5f * u);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(2.2f * u);
                paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                paint.setColor(checkColor);
                canvas.drawPath(path, paint);
            }
            @Override public int getIntrinsicWidth() { return dp(28); }
            @Override public int getIntrinsicHeight() { return dp(32); }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private ImageView choiceCheck() {
        ImageView check = icon(R.drawable.ic_check, Color.WHITE);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(accent);
        check.setBackground(dot);
        check.setPadding(dp(3), dp(3), dp(3), dp(3));
        return check;
    }

    // Single choice as stacked tiles, Android 15-16 style: the group has
    // large outer corners and small inner ones, the picked tile gets a tonal
    // fill and a check. options[i] is {title} or {title, subtitle}.
    private View choiceList(String[][] options, int[] icons, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int n = options.length;
        LinearLayout[] rows = new LinearLayout[n];
        TextView[] titles = new TextView[n];
        ImageView[] checks = new ImageView[n];
        int[] current = {selected};
        Runnable paint = () -> {
            for (int i = 0; i < n; i++) {
                boolean on = i == current[0];
                float top = dp(i == 0 ? 20 : 6), bottom = dp(i == n - 1 ? 20 : 6);
                rows[i].setBackground(tile(on ? tonal(0.18f) : surface, on ? tonal(0.45f) : border,
                        new float[]{top, top, top, top, bottom, bottom, bottom, bottom}));
                titles[i].setTextColor(on ? accent : text);
                ImageView check = checks[i];
                check.animate().cancel();
                if (on && check.getVisibility() != View.VISIBLE) {
                    check.setVisibility(View.VISIBLE);
                    check.setScaleX(0f);
                    check.setScaleY(0f);
                    check.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(new OvershootInterpolator(1.6f)).start();
                } else if (!on) {
                    check.setVisibility(View.INVISIBLE);
                }
            }
        };
        for (int i = 0; i < n; i++) {
            int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(60));
            row.setPadding(dp(18), dp(12), dp(16), dp(12));
            if (icons != null && icons[i] != 0) {
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
                iconParams.rightMargin = dp(14);
                row.addView(iconBubble(icons[i]), iconParams);
                row.setPadding(dp(14), dp(12), dp(16), dp(12));
            }
            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            titles[i] = text(options[i][0], 15, text, true);
            copy.addView(titles[i]);
            if (options[i].length > 1) copy.addView(text(options[i][1], 12, secondary, false));
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
            checks[i] = choiceCheck();
            checks[i].setVisibility(i == selected ? View.VISIBLE : View.INVISIBLE);
            row.addView(checks[i], new LinearLayout.LayoutParams(dp(24), dp(24)));
            row.setOnClickListener(v -> {
                if (current[0] == index) return;
                tap(v);
                current[0] = index;
                paint.run();
                onPick.accept(index);
            });
            rows[i] = row;
            LinearLayout.LayoutParams params = matchWrap();
            if (i > 0) params.topMargin = dp(2);
            list.addView(row, params);
        }
        paint.run();
        return list;
    }

    // Material 3 segmented button: one pill split into equal segments, the
    // picked one tonal with a leading check.
    private View segmented(String[] labels, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout bar = new LinearLayout(this);
        int n = labels.length;
        LinearLayout[] segments = new LinearLayout[n];
        TextView[] titles = new TextView[n];
        ImageView[] checks = new ImageView[n];
        int[] current = {selected};
        Runnable paint = () -> {
            for (int i = 0; i < n; i++) {
                boolean on = i == current[0];
                float left = i == 0 ? dp(24) : 0, right = i == n - 1 ? dp(24) : 0;
                segments[i].setBackground(tile(on ? tonal(0.18f) : surface, on ? tonal(0.45f) : border,
                        new float[]{left, left, right, right, right, right, left, left}));
                titles[i].setTextColor(on ? accent : text);
                checks[i].setVisibility(on ? View.VISIBLE : View.GONE);
            }
        };
        for (int i = 0; i < n; i++) {
            int index = i;
            LinearLayout segment = new LinearLayout(this);
            segment.setGravity(Gravity.CENTER);
            checks[i] = icon(R.drawable.ic_check, accent);
            LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(18), dp(18));
            checkParams.rightMargin = dp(6);
            segment.addView(checks[i], checkParams);
            titles[i] = text(labels[i], 14, text, true);
            segment.addView(titles[i]);
            segment.setOnClickListener(v -> {
                if (current[0] == index) return;
                tap(v);
                current[0] = index;
                android.transition.TransitionManager.beginDelayedTransition(bar);
                paint.run();
                onPick.accept(index);
            });
            segments[i] = segment;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
            if (i > 0) params.leftMargin = -dp(1);
            bar.addView(segment, params);
        }
        paint.run();
        return bar;
    }

    // A horizontal row of filter chips, one picked.
    private View chips(String[] labels, int selected, java.util.function.IntConsumer onPick) {
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        scroll.addView(row);
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            boolean on = i == selected;
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(on ? 10 : 14), 0, dp(14), 0);
            float r = dp(10);
            chip.setBackground(tile(on ? tonal(0.18f) : surface, on ? tonal(0.45f) : border,
                    new float[]{r, r, r, r, r, r, r, r}));
            if (on) {
                LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(16), dp(16));
                checkParams.rightMargin = dp(6);
                chip.addView(icon(R.drawable.ic_check, accent), checkParams);
            }
            chip.addView(text(labels[i], 13, on ? accent : text, on));
            chip.setOnClickListener(v -> {
                if (index == selected) return;
                tap(v);
                onPick.accept(index);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(34));
            if (i > 0) params.leftMargin = dp(8);
            row.addView(chip, params);
        }
        return scroll;
    }

    private Button primaryButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setStateListAnimator(null);
        button.setBackground(buttonBackground(Color.rgb(79, 124, 255), Color.rgb(59, 93, 191)));
        button.setOnClickListener(v -> {
            bounce(v);
            action.run();
        });
        return button;
    }

    private List<AppEntry> loadInstalledAppsCached() {
        if (installedAppsCache == null) installedAppsCache = loadInstalledApps();
        return installedAppsCache;
    }

    private List<AppEntry> loadInstalledApps() {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launcherIntent, 0);
        LinkedHashMap<String, AppEntry> byPackage = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            String packageName = info.activityInfo.packageName;
            if (packageName.equals(getPackageName()) || byPackage.containsKey(packageName)) continue;
            String label = info.loadLabel(getPackageManager()).toString();
            Drawable icon = info.loadIcon(getPackageManager());
            byPackage.put(packageName, new AppEntry(packageName, label, icon));
        }
        List<AppEntry> apps = new ArrayList<>(byPackage.values());
        Collections.sort(apps, Comparator.comparing(entry -> entry.label.toLowerCase()));
        return apps;
    }

    private void persistAppFilter() {
        appFilterPrefs.edit()
                .putString(AppFilter.KEY_MODE, appFilterMode)
                .putStringSet(AppFilter.KEY_PACKAGES, new HashSet<>(selectedApps))
                .apply();
    }

    private View buildAppRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(ripple(Color.TRANSPARENT, 8));
        ImageView icon = new ImageView(this);
        row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView labelView = text("", 14, text, false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1f);
        labelParams.leftMargin = dp(12);
        row.addView(labelView, labelParams);
        CheckBox checkBox = new CheckBox(this);
        checkBox.setButtonTintList(ColorStateList.valueOf(accent));
        row.addView(checkBox, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;

        AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final class AppRowHolder {
        final ImageView icon;
        final TextView label;
        final CheckBox checkBox;

        AppRowHolder(View row) {
            LinearLayout layout = (LinearLayout) row;
            icon = (ImageView) layout.getChildAt(0);
            label = (TextView) layout.getChildAt(1);
            checkBox = (CheckBox) layout.getChildAt(2);
        }
    }

    private final class AppListAdapter extends BaseAdapter {
        private final List<AppEntry> apps;

        AppListAdapter(List<AppEntry> apps) {
            this.apps = apps;
        }

        @Override public int getCount() {
            return apps.size();
        }

        @Override public Object getItem(int position) {
            return apps.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row;
            AppRowHolder holder;
            if (convertView != null && convertView.getTag() instanceof AppRowHolder) {
                row = convertView;
                holder = (AppRowHolder) row.getTag();
            } else {
                row = buildAppRow();
                holder = new AppRowHolder(row);
                row.setTag(holder);
            }
            AppEntry entry = apps.get(position);
            holder.icon.setImageDrawable(entry.icon);
            holder.label.setText(entry.label);
            holder.checkBox.setOnCheckedChangeListener(null);
            holder.checkBox.setChecked(editorSelectedApps.contains(entry.packageName));
            holder.checkBox.setOnCheckedChangeListener((button, checked) -> {
                tap(button);
                if (checked) editorSelectedApps.add(entry.packageName);
                else editorSelectedApps.remove(entry.packageName);
            });
            row.setOnClickListener(v -> holder.checkBox.setChecked(!holder.checkBox.isChecked()));
            return row;
        }
    }

    private View buildUrlField(String initialValue) {
        FrameLayout field = new FrameLayout(this);
        field.setBackground(rounded(surface, border, 1, 10));
        urlInput = settingInput(transportValueLabel(editorTransportType), initialValue,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setTransformationMethod(urlVisible ? null : PasswordTransformationMethod.getInstance());
        urlInput.setPadding(dp(16), 0, dp(56), 0);
        field.addView(urlInput, new FrameLayout.LayoutParams(-1, -1));
        visibilityButton = iconButton(urlVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility,
                urlVisible ? "Скрыть ссылку" : "Показать ссылку");
        visibilityButton.setOnClickListener(v -> {
            tap(v);
            toggleUrlVisibility();
        });
        FrameLayout.LayoutParams eye = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        eye.rightMargin = dp(4);
        field.addView(visibilityButton, eye);
        return floating(field, urlInput);
    }

    private View buildEncryptionField(String initialValue) {
        FrameLayout field = new FrameLayout(this);
        field.setBackground(rounded(surface, border, 1, 10));
        encryptionInput = settingInput("Ключ сквозного шифрования (необязательно)", initialValue,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        encryptionInput.setTransformationMethod(encryptionVisible ? null : PasswordTransformationMethod.getInstance());
        encryptionInput.setPadding(dp(16), 0, dp(56), 0);
        field.addView(encryptionInput, new FrameLayout.LayoutParams(-1, -1));
        encryptionVisibilityButton = iconButton(
                encryptionVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility,
                encryptionVisible ? "Скрыть ключ" : "Показать ключ");
        encryptionVisibilityButton.setOnClickListener(v -> {
            tap(v);
            toggleEncryptionVisibility();
        });
        FrameLayout.LayoutParams eye = new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        eye.rightMargin = dp(4);
        field.addView(encryptionVisibilityButton, eye);
        return floating(field, encryptionInput);
    }

    private LinearLayout cardRow(int iconRes, String titleValue, String detailValue) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(16), dp(14));
        row.setBackground(tile(surface, border, radii(20)));
        row.addView(iconBubble(iconRes), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = dp(14);
        copy.addView(text(titleValue, 15, text, true));
        copy.addView(text(detailValue, 12, secondary, false));
        row.addView(copy, copyParams);
        return row;
    }

    private View settingRow(int iconRes, String labelValue, EditText input) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(10), dp(10));
        row.setBackground(tile(surface, border, radii(20)));
        row.addView(iconBubble(iconRes), new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(labelValue, 14, text, false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.leftMargin = dp(12);
        row.addView(title, titleParams);
        row.addView(input, new LinearLayout.LayoutParams(dp(120), dp(46)));
        return row;
    }

    private Switch settingSwitch(int iconRes, String titleValue, String detailValue, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setBackground(tile(surface, border, radii(20)));
        row.addView(iconBubble(iconRes), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = dp(12);
        copy.addView(text(titleValue, 14, text, false));
        copy.addView(text(detailValue, 11, secondary, false));
        row.addView(copy, copyParams);
        Switch toggle = new Switch(this);
        styleSwitch(toggle);
        toggle.setChecked(checked);
        toggle.setContentDescription(titleValue);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(42)));
        toggle.setTag(row);
        return toggle;
    }

    // Wraps an outlined field (box holding input) in a Google-style floating
    // label: the label rests inside the box like a hint while the field is
    // empty and slides up onto the top border once it has text or focus.
    // The input's hint becomes the label; setFloatingLabel changes it later.
    private View floating(View box, EditText input) {
        FrameLayout wrapper = new FrameLayout(this);
        // FLOAT_ROOM above the box holds the upper half of the floated label,
        // so it never pokes out under a neighbor.
        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(-1, -1);
        boxParams.topMargin = dp(FLOAT_ROOM);
        wrapper.addView(box, boxParams);
        TextView label = text(String.valueOf(input.getHint()), 14, hint, false);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(4), 0, dp(4), 0);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        wrapper.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        input.setContentDescription(input.getHint());
        input.setHint(null);
        input.setTag(label);
        // Top half over whatever is behind the field, bottom half over the
        // box: hides the border line behind the floated label.
        android.graphics.drawable.Drawable patch = new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint();
            @Override public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect b = getBounds();
                paint.setColor(colorBehind(wrapper));
                canvas.drawRect(b.left, b.top, b.right, b.exactCenterY(), paint);
                paint.setColor(surface);
                canvas.drawRect(b.left, b.exactCenterY(), b.right, b.bottom, paint);
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        };
        boolean[] floated = {false};
        boolean[] sliding = {false};
        Consumer<Boolean> place = animate -> {
            if (wrapper.getHeight() == 0) return;
            boolean up = input.hasFocus() || input.length() > 0;
            label.setTextColor(up && input.hasFocus() ? accent : up ? secondary : hint);
            // A layout pass (the keyboard opening) must not cut a slide short.
            if (sliding[0] && (!animate || up == floated[0])) return;
            // Text start of the input, relative to the wrapper.
            float x = input.getPaddingLeft() - dp(4);
            for (View v = input; v != wrapper && v != null; v = (View) v.getParent()) x += v.getLeft();
            label.setX(x);
            // Resting, the label must stop where the text does (before an eye
            // button); floated, it is scaled down and fits anyway.
            int textRight = (int) (x + dp(4) + input.getWidth() - input.getPaddingLeft() - input.getPaddingRight());
            label.setMaxWidth(Math.max(dp(40), textRight - (int) x + dp(4)));
            label.setPivotX(0);
            label.setPivotY(label.getHeight() / 2f);
            float room = dp(FLOAT_ROOM);
            float y = up ? room - label.getHeight() / 2f
                    : room + (wrapper.getHeight() - room - label.getHeight()) / 2f;
            float scale = up ? 0.8f : 1f;
            if (animate && up != floated[0]) {
                sliding[0] = true;
                // The patch only belongs on the border: drop it before sliding
                // down, add it once the label has landed up there.
                if (!up) label.setBackground(null);
                label.animate().y(y).scaleX(scale).scaleY(scale).setDuration(220)
                        .setInterpolator(EMPHASIZED)
                        .withEndAction(() -> {
                            sliding[0] = false;
                            label.setBackground(up ? patch : null);
                        }).start();
            } else {
                label.setY(y);
                label.setScaleX(scale);
                label.setScaleY(scale);
                label.setBackground(up ? patch : null);
            }
            floated[0] = up;
        };
        wrapper.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(() -> place.accept(false)));
        input.setOnFocusChangeListener((v, focus) -> place.accept(true));
        bindText(input, v -> place.accept(true));
        return wrapper;
    }

    // The fill of the nearest ancestor that paints one, else the page's.
    private int colorBehind(View view) {
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            android.graphics.drawable.Drawable d = ((View) p).getBackground();
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                return ((android.graphics.drawable.ColorDrawable) d).getColor();
            }
            if (d instanceof android.graphics.drawable.GradientDrawable
                    && ((android.graphics.drawable.GradientDrawable) d).getColor() != null) {
                return ((android.graphics.drawable.GradientDrawable) d).getColor().getDefaultColor();
            }
        }
        return background;
    }

    private static final int FLOAT_ROOM = 8;
    // Height of a field wrapped by floating(): the 56dp box plus FLOAT_ROOM.
    private static final int FIELD_HEIGHT = 56 + FLOAT_ROOM;

    private static void setFloatingLabel(EditText input, String value) {
        if (input == null || !(input.getTag() instanceof TextView)) return;
        ((TextView) input.getTag()).setText(value);
        input.setContentDescription(value);
    }

    private EditText settingInput(String fieldHint, String value, int inputType) {
        EditText input = new EditText(this);
        input.setHint(fieldHint);
        input.setHintTextColor(hint);
        input.setText(value);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(text);
        input.setInputType(inputType);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(8), 0, dp(8), 0);
        return input;
    }

    private void switchTheme(boolean checked) {
        if (darkMode == checked) return;
        captureSettings();
        View oldRoot = root;
        oldRoot.animate().cancel();
        oldRoot.animate().alpha(0.15f).setDuration(110).withEndAction(() -> {
            darkMode = checked;
            getSharedPreferences(SETTINGS_PREFS_NAME, MODE_PRIVATE).edit().putBoolean("dark_mode", darkMode).apply();
            applyPalette();
            configureSystemBars();
            buildShell();
            showPage(currentPage);
        }).start();
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
        Toast.makeText(this, "Создан ключ на 256 бит. Передайте его на VDS.", Toast.LENGTH_LONG).show();
    }

    // Called on every navigation (showPage), not just when leaving a settings
    // page with unsaved edits - so it must NOT commit the Network/Mode draft
    // fields (applyNetworkSettings/applyModeSettings do that, only when the
    // save button is pressed). Just preserves the log scrollback text across
    // the page rebuild and drops view references before they're rebuilt.
    private void captureSettings() {
        captureLogs();
        urlInput = null;
        encryptionInput = null;
        profileNameInput = null;
        dnsInput = null;
        mtuInput = null;
        proxyPortInput = null;
        proxyUsernameInput = null;
        proxyPasswordInput = null;
        logView = null;
        logScroll = null;
    }

    private void captureLogs() {
        if (logView != null) logs = logView.getText().toString();
    }

    private void persistSettings() {
        secureSettings.putString("document_url", documentUrl);
        secureSettings.putString("encryption_secret", encryptionSecret);
        secureSettings.putString("proxy_password", proxyPassword);
        getSharedPreferences(SETTINGS_PREFS_NAME, MODE_PRIVATE).edit()
                .remove("connection_document_url")
                .putString("dns_server", dnsServer)
                .putInt("mtu", mtu)
                .putBoolean("kill_switch", killSwitchEnabled)
                .putString("connection_mode", connectionMode)
                .putInt("proxy_port", proxyPort)
                .putBoolean("proxy_lan_access", proxyLanAccess)
                .putBoolean("proxy_auth_enabled", proxyAuthEnabled)
                .putString("proxy_username", proxyUsername)
                .putBoolean("auto_scroll", autoScroll)
                .putBoolean("dark_mode", darkMode)
                .putBoolean("show_sensitive_logs", showSensitiveLogs)
                .commit();
    }

    private boolean isProxyMode() {
        return MODE_PROXY.equals(connectionMode);
    }

    private boolean isExitMode() {
        return MODE_EXIT.equals(connectionMode);
    }

    private static String modeLabel(String mode) {
        if (MODE_PROXY.equals(mode)) return "Прокси (SOCKS5)";
        if (MODE_EXIT.equals(mode)) return "Выходная нода (L4)";
        return "Туннель (весь трафик)";
    }

    private boolean isConnectionRunning() {
        if (isExitMode()) return OpenFluxExitService.isRunning();
        return isProxyMode() ? OpenFluxProxyService.isRunning() : OpenFluxTunnelService.isRunning();
    }

    private String connectionStatus() {
        if (isExitMode()) return OpenFluxExitService.getStatus();
        return isProxyMode() ? OpenFluxProxyService.getStatus() : OpenFluxTunnelService.getStatus();
    }

    private String connectionLastError() {
        if (isExitMode()) return OpenFluxExitService.getLastError();
        return isProxyMode() ? OpenFluxProxyService.getLastError() : OpenFluxTunnelService.getLastError();
    }

    private long connectionStartedAt() {
        if (isExitMode()) return OpenFluxExitService.getConnectedAtMillis();
        return isProxyMode() ? OpenFluxProxyService.getConnectedAtMillis() : OpenFluxTunnelService.getConnectedAtMillis();
    }

    private void stopConnection(String mode) {
        Intent stop;
        if (MODE_EXIT.equals(mode)) {
            stop = new Intent(this, OpenFluxExitService.class).setAction(OpenFluxExitService.ACTION_STOP);
        } else if (MODE_PROXY.equals(mode)) {
            stop = new Intent(this, OpenFluxProxyService.class).setAction(OpenFluxProxyService.ACTION_STOP);
        } else {
            stop = new Intent(this, OpenFluxTunnelService.class).setAction(OpenFluxTunnelService.ACTION_STOP);
        }
        startService(stop);
    }

    private void toggleConnection() {
        if (isConnectionRunning()) {
            stopConnection(connectionMode);
            appendLog("Запрошена остановка: " + modeLabel(connectionMode));
            return;
        }
        String valueProblem = transportValueProblem(transportType,
                "oneme".equals(transportType) ? (maxToken == null ? "" : maxToken) : documentUrl, isExitMode());
        if (valueProblem != null) {
            Toast.makeText(this, valueProblem, Toast.LENGTH_LONG).show();
            showPage(PAGE_PROFILES);
            return;
        }
        if (encryptionSecret != null && !encryptionSecret.isEmpty() && encryptionSecret.length() < 16) {
            Toast.makeText(this, "Ключ шифрования профиля должен быть не короче 16 символов, либо пустым", Toast.LENGTH_LONG).show();
            showPage(PAGE_PROFILES);
            return;
        }
        if (isProxyMode() && proxyLanAccess && proxyAuthEnabled
                && (proxyUsername.isEmpty() || proxyPassword.isEmpty())) {
            Toast.makeText(this, "Укажите логин и пароль для авторизации прокси", Toast.LENGTH_LONG).show();
            openSettingsDetail(SETTINGS_MODE);
            return;
        }
        persistSettings();
        if (isExitMode()) {
            Intent intent = new Intent(this, OpenFluxExitService.class).setAction(OpenFluxExitService.ACTION_START);
            putProfileExtras(intent);
            startForegroundService(intent);
            appendLog("Запуск выходной ноды…");
            return;
        }
        if (isProxyMode()) {
            startProxy();
            return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, TUNNEL_PERMISSION_REQUEST);
        else startTunnel();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            if (scan.getContents() != null) importShareLink(scan.getContents());
            return;
        }
        if (requestCode == NODE_WIZARD_REQUEST) {
            // The wizard saved its profile straight to the store.
            profiles = profileStore.load();
            long id = data != null ? data.getLongExtra(NodeWizardActivity.EXTRA_PROFILE_ID, -1) : -1;
            if (id >= 0 && !isConnectionRunning()) selectProfile(id);
            else showPage(PAGE_PROFILES);
            if (id >= 0) appendLog("Профиль своей ноды добавлен");
            return;
        }
        if (requestCode == TUNNEL_PERMISSION_REQUEST && resultCode == RESULT_OK) startTunnel();
        else if (requestCode == TUNNEL_PERMISSION_REQUEST) appendLog("[ERROR] Разрешение на создание туннеля не выдано");
    }

    // Installs a new channel (a new node, or another user on a server that
    // already has some) on the user's VDS; see NodeWizardActivity.
    private void openNodeWizard() {
        if (isConnectionRunning()) {
            Toast.makeText(this, "Сначала отключите OpenFlux: мастер сам проверит новую ноду", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(new Intent(this, NodeWizardActivity.class), NODE_WIZARD_REQUEST);
    }

    private void putProfileExtras(Intent intent) {
        Profile p = selectedProfile();
        if (p != null) {
            p.putConnectionExtras(intent);
            return;
        }
        intent.putExtra(OpenFluxTunnelService.EXTRA_DOCUMENT_URL, documentUrl);
        intent.putExtra(OpenFluxTunnelService.EXTRA_ENCRYPTION_SECRET, encryptionSecret);
        intent.putExtra(OpenFluxTunnelService.EXTRA_TRANSPORT_TYPE, transportType);
        intent.putExtra(OpenFluxTunnelService.EXTRA_CODEC, codec);
        intent.putExtra(OpenFluxTunnelService.EXTRA_MAX_TOKEN, maxToken);
        intent.putExtra(OpenFluxTunnelService.EXTRA_MAX_UID, maxUid);
    }

    private void startTunnel() {
        Intent intent = new Intent(this, OpenFluxTunnelService.class);
        intent.setAction(OpenFluxTunnelService.ACTION_START);
        putProfileExtras(intent);
        intent.putExtra(OpenFluxTunnelService.EXTRA_DNS_SERVER, dnsServer);
        intent.putExtra(OpenFluxTunnelService.EXTRA_MTU, mtu);
        startForegroundService(intent);
        appendLog("Запуск туннеля…");
    }

    private void startProxy() {
        Intent intent = new Intent(this, OpenFluxProxyService.class);
        intent.setAction(OpenFluxProxyService.ACTION_START);
        putProfileExtras(intent);
        intent.putExtra(OpenFluxProxyService.EXTRA_PORT, proxyPort);
        intent.putExtra(OpenFluxProxyService.EXTRA_LAN_ACCESS, proxyLanAccess);
        if (proxyLanAccess && proxyAuthEnabled) {
            intent.putExtra(OpenFluxProxyService.EXTRA_USERNAME, proxyUsername);
            intent.putExtra(OpenFluxProxyService.EXTRA_PASSWORD, proxyPassword);
        }
        startForegroundService(intent);
        appendLog("Запуск прокси…");
    }

    private void updateStatus() {
        if (tunnelButtonText == null || tunnelButton == null) return;
        String state = connectionStatus();
        // Text sits on the button's own fill color, not the page background,
        // so it stays a fixed white for contrast rather than status-colored.
        tunnelButtonText.setText(state);

        long connectedAt = connectionStartedAt();
        if (connectedAt == 0L) {
            // GONE, not just empty text: an empty-but-present line still
            // reserves its height, which pushes the icon+status above dead
            // center in the button while there's no time to show yet.
            uptimeView.setVisibility(View.GONE);
            speedView.setVisibility(View.GONE);
        } else {
            uptimeView.setVisibility(View.VISIBLE);
            uptimeView.setText(formatUptime(System.currentTimeMillis() - connectedAt));
            long sentPerSec = isExitMode() ? OpenFluxExitService.getSentPerSec()
                    : isProxyMode() ? OpenFluxProxyService.getSentPerSec() : OpenFluxTunnelService.getSentPerSec();
            long receivedPerSec = isExitMode() ? OpenFluxExitService.getReceivedPerSec()
                    : isProxyMode() ? OpenFluxProxyService.getReceivedPerSec() : OpenFluxTunnelService.getReceivedPerSec();
            speedView.setVisibility(View.VISIBLE);
            speedView.setText("↑ " + OpenFluxTunnelService.formatSpeed(sentPerSec)
                    + "   ↓ " + OpenFluxTunnelService.formatSpeed(receivedPerSec));
        }

        if (state != null && !state.equals(lastAnnouncedState)) {
            if ("Подключено".equals(state) && isExitMode() && lastAnnouncedState != null
                    && lastAnnouncedState.startsWith("Ожидание")) {
                if (joinCelebration) JoinCelebrationView.play(root);
                else vibrateSuccess();
                appendLog("[SUCCESS] Клиент подключился к выходной ноде");
            } else if ("Подключено".equals(state)) {
                vibrateSuccess();
                appendLog("[SUCCESS] Подключено (" + modeLabel(connectionMode) + ")");
            } else if ("Ошибка".equals(state)) {
                vibrateError();
            }
            lastAnnouncedState = state;
        }

        int tunnelFill;
        int tunnelPressed;
        boolean pulsing;
        // Idle matches the "Активные параметры" card background instead of a
        // fixed dark grey, so it also needs to pick readable content color -
        // that card's own background is near-white in light mode.
        boolean idle;
        if ("Подключено".equals(state)) {
            tunnelFill = Color.rgb(79, 124, 255);
            tunnelPressed = Color.rgb(59, 93, 191);
            pulsing = true;
            idle = false;
        } else if ("Ошибка".equals(state)) {
            tunnelFill = Color.rgb(239, 68, 68);
            tunnelPressed = Color.rgb(185, 28, 28);
            pulsing = false;
            idle = false;
        } else if (state != null && (state.contains("Подключ") || state.contains("Останав")
                || state.contains("Ожидание") || state.contains("Нужна"))) {
            tunnelFill = Color.rgb(251, 191, 36);
            tunnelPressed = Color.rgb(217, 119, 6);
            pulsing = true;
            idle = false;
        } else {
            tunnelFill = surface;
            tunnelPressed = border;
            pulsing = false;
            idle = true;
        }
        animateTunnelButtonFill(tunnelFill, tunnelPressed);
        setRingPulsing(pulsing);

        int contentColor = idle ? text : Color.WHITE;
        tunnelButtonText.setTextColor(contentColor);
        uptimeView.setTextColor(contentColor);
        speedView.setTextColor(contentColor);
        if (tunnelPowerIcon != null) {
            tunnelPowerIcon.setImageTintList(ColorStateList.valueOf(contentColor));
        }

        refreshExitShareCard();

        String error = connectionLastError();
        if (error != null && !error.isEmpty() && !error.equals(lastShownError)) {
            lastShownError = error;
            appendLog("[ERROR] " + error);
        }
    }

    private void setRingPulsing(boolean pulsing) {
        if (ringPulse == null || ringWave == null) return;
        if (pulsing) {
            if (!ringPulse.isRunning()) ringPulse.start();
        } else if (ringPulse.isRunning()) {
            ringPulse.cancel();
            ringWave.setScaleX(1f);
            ringWave.setScaleY(1f);
            ringWave.setAlpha(0f);
        }
    }

    private String formatUptime(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void animateTunnelButtonFill(int fill, int pressed) {
        if (tunnelButton == null) return;
        if (ringWave != null) ringWave.setBackground(rounded(Color.TRANSPARENT, fill, 2, 70));
        if (lastTunnelButtonFill == fill) return;
        int from = lastTunnelButtonFill == -1 ? fill : lastTunnelButtonFill;
        lastTunnelButtonFill = fill;
        ValueAnimator animator = ValueAnimator.ofArgb(from, fill);
        animator.setDuration(260);
        animator.addUpdateListener(a -> tunnelButton.setBackground(circleBackground((int) a.getAnimatedValue(), pressed)));
        animator.start();
    }

    // Static decorative ring around the big connect button - a thin stroke
    // circle, no fill. A single large corner radius renders as a perfect
    // circle regardless of the view's own size (GradientDrawable clips
    // excess radius), so the same drawable works for both ring sizes.
    private GradientDrawable ringOutline() {
        return rounded(Color.TRANSPARENT, border, 1, 100);
    }

    private RippleDrawable circleBackground(int fill, int pressed) {
        return new RippleDrawable(ColorStateList.valueOf(pressed), rounded(fill, Color.TRANSPARENT, 0, 70),
                rounded(Color.WHITE, Color.TRANSPARENT, 0, 70));
    }

    // getLocalIpAddress finds this device's IPv4 address on whatever network
    // it's currently attached to (Wi-Fi, a hotspot it joined, Ethernet, ...)
    // by scanning network interfaces directly, so it works the same way
    // regardless of connection type and needs no extra permission.
    private String getLocalIpAddress() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                for (InetAddress address : Collections.list(intf.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return null;
    }

    private boolean isValidDocumentUrl(String value) {
        if (value == null || !value.startsWith("https://")) return false;
        try {
            android.net.Uri uri = android.net.Uri.parse(value);
            return uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final String HIDDEN_URL_LABEL = "HIDDEN-URL";
    private static final Pattern SENSITIVE_URL_PATTERN = Pattern.compile("(?i)\\b(?:https?|wss?)://\\S+");
    private static final Pattern SENSITIVE_IP_PATTERN = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b");
    // Catches bare hostnames without a scheme (e.g. the "WebSocket connected
    // to <host>" debug line, which logs transport.YandexDocsInfo.Host on its
    // own, never as a full wss:// URL). Yandex's own internal hostnames use
    // underscores in a label (e.g. "ota5..._vla_808_....sas.yp-c.yandex.net"),
    // which isn't valid DNS but does show up in these logs, so labels allow
    // '_' too - otherwise the match breaks there and only the tail after the
    // last underscore gets hidden.
    private static final Pattern SENSITIVE_HOST_PATTERN =
            Pattern.compile("\\b(?:[a-zA-Z0-9_](?:[a-zA-Z0-9_-]*[a-zA-Z0-9_])?\\.)+[a-zA-Z]{2,}\\b");

    private void appendLog(String value) {
        value = redactSensitive(value);
        String timestamp = "[" + new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date()) + "]";
        String[] incoming = value.split("\n", -1);
        StringBuilder stamped = new StringBuilder();
        for (int i = 0; i < incoming.length; i++) {
            if (i > 0) stamped.append('\n');
            stamped.append(timestamp).append(' ').append(incoming[i]);
        }
        value = stamped.toString();
        if (!logs.isEmpty()) logs += "\n";
        logs += value;
        if (logs.length() > 60000) logs = logs.substring(logs.length() - 40000);
        if (logView != null) {
            logView.setText(colorizeLogs(logs));
            if (autoScroll && logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    // Document URLs are effectively passwords (docs/GUIDE*.md: "this link is
    // equivalent to your tunnel password"), and the transport's own debug lines
    // ([YDOCS]/[VOLGA]) print full URLs, WebSocket endpoints and resolved IPs
    // verbatim for diagnostics. Opt-in (off by default, Settings -> "Вид")
    // since it makes the log noisier and less useful for real debugging -
    // strips anything URL- or IP-shaped before the line ever reaches the
    // stored/displayed log text, so a screenshot or copy-paste can't leak it.
    private String redactSensitive(String value) {
        if (showSensitiveLogs) return value;
        value = SENSITIVE_URL_PATTERN.matcher(value).replaceAll(HIDDEN_URL_LABEL);
        value = SENSITIVE_IP_PATTERN.matcher(value).replaceAll(HIDDEN_URL_LABEL);
        value = SENSITIVE_HOST_PATTERN.matcher(value).replaceAll(HIDDEN_URL_LABEL);
        return value;
    }

    private static final Pattern LOG_TAG_PATTERN =
            Pattern.compile("^(?:\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\] )?(\\[[A-Z0-9_]+\\])");

    // Colors the leading [TAG] of each log line so errors/successes/the
    // Yandex Docs transport's own debug tag stand out at a glance instead of
    // blending into a wall of monospace text.
    private int logTagColor(String tag) {
        switch (tag) {
            case "[ERROR]":
            case "[PANIC]":
                return darkMode ? Color.rgb(242, 139, 130) : Color.rgb(217, 48, 37);
            case "[SUCCESS]":
                return darkMode ? Color.rgb(129, 201, 149) : Color.rgb(24, 128, 56);
            case "[YDOCS]":
                return darkMode ? Color.rgb(253, 214, 99) : Color.rgb(249, 171, 0);
            case "[ANDROID]":
            case "[VOLGA]":
            case "[MAX]":
            case "[CUPS]":
            case "[M-DOCS]":
                return accent;
            default:
                return logColor;
        }
    }

    private CharSequence colorizeLogs(String rawLogs) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        String[] lines = rawLogs.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int start = builder.length();
            builder.append(line);
            Matcher matcher = LOG_TAG_PATTERN.matcher(line);
            if (matcher.find()) {
                builder.setSpan(new ForegroundColorSpan(logTagColor(matcher.group(1))),
                        start + matcher.start(1), start + matcher.end(1), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int hiddenColor = darkMode ? Color.rgb(242, 139, 130) : Color.rgb(217, 48, 37);
            int searchFrom = 0;
            int idx;
            while ((idx = line.indexOf(HIDDEN_URL_LABEL, searchFrom)) >= 0) {
                builder.setSpan(new ForegroundColorSpan(hiddenColor),
                        start + idx, start + idx + HIDDEN_URL_LABEL.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                searchFrom = idx + HIDDEN_URL_LABEL.length();
            }
            if (i < lines.length - 1) builder.append("\n");
        }
        return builder;
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        return page;
    }

    private TextView text(CharSequence value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 11, secondary, true);
        view.setLetterSpacing(0.08f);
        return view;
    }

    private ImageView icon(int resource, int color) {
        ImageView view = new ImageView(this);
        view.setImageResource(resource);
        view.setImageTintList(ColorStateList.valueOf(color));
        return view;
    }

    private ImageButton iconButton(int resource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setImageTintList(ColorStateList.valueOf(secondary));
        button.setContentDescription(description);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setBackground(ripple(Color.TRANSPARENT, 24));
        return button;
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeWidth, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), stroke);
        return drawable;
    }

    private RippleDrawable ripple(int fill, int radius) {
        // No ripple highlight color - it showed as a flat grey/tinted box
        // over the whole rounded touch target instead of a subtle effect.
        // Feedback on tap comes from tap()/bounce() (haptics + scale) instead.
        return new RippleDrawable(ColorStateList.valueOf(Color.TRANSPARENT),
                rounded(fill, Color.TRANSPARENT, 0, radius), rounded(Color.WHITE, Color.TRANSPARENT, 0, radius));
    }

    private RippleDrawable buttonBackground(int fill, int pressed) {
        return new RippleDrawable(ColorStateList.valueOf(pressed), rounded(fill, Color.TRANSPARENT, 0, 9),
                rounded(Color.WHITE, Color.TRANSPARENT, 0, 9));
    }

    // tap gives a light click haptic for a direct user interaction (button
    // press, toggle, list selection). Respects the system's haptic feedback
    // setting automatically and needs no permission.
    private void tap(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // bounce gives a tap haptic plus a scale-down/scale-up feedback animation.
    // Repeated or rapid taps on the same view would otherwise stack multiple
    // overlapping ViewPropertyAnimator sequences (cancel() still runs a
    // pending withEndAction on API 23+), so any animation already running for
    // this exact view is fully cancelled and replaced before starting a new
    // one, and the scale is reset synchronously rather than relying on the
    // cancelled animation to leave it in a known state.
    private void bounce(View view) {
        tap(view);
        AnimatorSet running = bounceAnimators.remove(view);
        if (running != null) running.cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        ObjectAnimator shrink = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.97f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.97f));
        shrink.setDuration(90);
        shrink.setInterpolator(new DecelerateInterpolator());
        ObjectAnimator grow = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f));
        // No overshoot: growing past 1 pushed views under their neighbors
        // and past the edges of clipping parents.
        grow.setDuration(220);
        grow.setInterpolator(EMPHASIZED);
        AnimatorSet set = new AnimatorSet();
        set.playSequentially(shrink, grow);
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                bounceAnimators.remove(view);
            }
        });
        bounceAnimators.put(view, set);
        set.start();
    }

    // vibrateSuccess/vibrateError are for state changes that aren't a direct
    // touch response (e.g. the tunnel finishing connecting a second later),
    // so they go through the Vibrator instead of View.performHapticFeedback.
    private void vibrateSuccess() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 29) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void vibrateError() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 45, 60, 45}, -1));
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -1, 1f); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
