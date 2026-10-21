package io.openflux.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import io.openflux.bridge.mobile.Mobile;

public final class MainActivity extends ThemedActivity {
    // ---- Screens and parts ----

    final VersionCheck versionCheck = new VersionCheck(this);
    final ShareLinks shareLinks = new ShareLinks(this);
    final ProfilesPage profilesPage = new ProfilesPage(this);
    final HomePage homePage = new HomePage(this);
    final SettingsPage settingsPage = new SettingsPage(this);
    final ProfileEditor profileEditor = new ProfileEditor(this);
    final AppPicker appPicker = new AppPicker(this);
    final LogsPage logsPage = new LogsPage(this);

    // ---- Constants ----

    // Named explicitly (instead of the implicit per-Activity-class file from
    // getPreferences()) so OpenFluxTileService can read the connection mode
    // and network settings without depending on Activity.getPreferences()'s
    // undocumented file-naming behavior.
    static final String SETTINGS_PREFS_NAME = "openflux_settings";

    private static final int TUNNEL_PERMISSION_REQUEST = 42;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 43;
    private static final int NODE_WIZARD_REQUEST = 44;
    static final int QR_IMAGE_REQUEST = 45;
    static final int SCRIPT_SETTINGS_REQUEST = 47;
    static final int DEFAULT_MTU = 1400;
    private static final int PAGE_HOME = 0;
    static final int PAGE_PROFILES = 1;
    private static final int PAGE_LOGS = 2;
    static final int PAGE_SETTINGS = 3;
    static final String MODE_TUNNEL = "tunnel";
    static final String MODE_PROXY = "proxy";

    // The phone serves as an l4 exit node for other clients.
    static final String MODE_EXIT = "exit";

    static final int DEFAULT_PROXY_PORT = 1080;
    static final String MAIN_REPO_URL = "https://github.com/p1neappleXpress/OpenFlux";
    static final String APP_REPO_URL = "https://github.com/damnurmum/OpenFlux-Android";

    // ---- State ----

    // The shell: pages, the bottom bar and the keyboard.
    final Handler handler = new Handler(Looper.getMainLooper());
    int currentPage = PAGE_HOME;
    private FrameLayout root;
    private View navScrim;
    private View navDeadZone;
    private FrameLayout content;
    private boolean shellAnimated;
    private int navBottomInset;
    private int gestureInset = -1;
    boolean keyboardOpen;
    // The pinned save button of the current page, if any; it stays down
    // behind the keyboard instead of riding up with it.
    View pinnedSave;

    // The selected profile, copied out for connecting (applySelectedProfileToFields).
    private String documentUrl;
    private String encryptionSecret;
    private String transportType = "yandex";
    private String codec = "batched";
    private String maxToken = "";
    private String maxUid = "";
    ProfileStore profileStore;
    List<Profile> profiles = new ArrayList<>();
    long selectedProfileId = -1;
    boolean profileEditorOpen;

    // The live settings; SettingsPage edits drafts of them.
    private SecureSettings secureSettings;
    boolean autoScroll = true;
    String dnsServer;
    int mtu;
    boolean killSwitchEnabled = true;
    String connectionMode = MODE_TUNNEL;
    int proxyPort = DEFAULT_PROXY_PORT;
    boolean proxyLanAccess;
    boolean proxyAuthEnabled;
    String proxyUsername = "";
    String proxyPassword = "";
    SharedPreferences appFilterPrefs;
    String appFilterMode = AppFilter.MODE_OFF;
    final LinkedHashSet<String> selectedApps = new LinkedHashSet<>();
    SharedPreferences domainFilterPrefs;
    final LinkedHashSet<String> enabledDomainPresets = new LinkedHashSet<>();
    final LinkedHashSet<String> customDomains = new LinkedHashSet<>();

    // The log text LogsPage shows, and the last error already shown.
    String logs = "";
    String lastShownError = "";

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            homePage.updateStatus();
            String pending = Mobile.readLogs();
            if (pending != null && !pending.isEmpty()) appendLog(pending);
            handler.postDelayed(this, 500);
        }
    };

    // ---- Lifecycle ----

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        vibrator = getSystemService(Vibrator.class);
        SharedPreferences prefs = getSharedPreferences(SETTINGS_PREFS_NAME, MODE_PRIVATE);
        secureSettings = new SecureSettings(this);
        // Older prototype builds used plain preferences. Remove those values:
        // connection credentials now live only in the Keystore-backed store.
        prefs.edit().remove("document_url").remove("connection_document_url")
                .remove("show_sensitive_logs").remove("join_celebration").apply();
        documentUrl = secureSettings.getString("document_url", "");
        encryptionSecret = secureSettings.getString("encryption_secret", "");
        profileStore = new ProfileStore(secureSettings);
        profiles = profileStore.load();
        selectedProfileId = profileStore.getSelectedId();
        migrateLegacyProfileIfNeeded();
        // A new build may ship newer copies of the bundled JS transports;
        // they replace the installed ones only through this (the switch in
        // Settings runs it too). It runs the script engine, so only when on.
        if (ScriptStore.experimental(this)) {
            new Thread(() -> new ScriptStore(this).syncBundled(), "scripts-sync").start();
        }
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
        darkMode = prefs.contains("dark_mode")
                ? prefs.getBoolean("dark_mode", isSystemDark())
                : isSystemDark();
        appFilterPrefs = getSharedPreferences(AppFilter.PREFS_NAME, MODE_PRIVATE);
        appFilterMode = appFilterPrefs.getString(AppFilter.KEY_MODE, AppFilter.MODE_OFF);
        appPicker.showSystemApps = appFilterPrefs.getBoolean("show_system_apps", false);
        selectedApps.addAll(appFilterPrefs.getStringSet(AppFilter.KEY_PACKAGES, Collections.emptySet()));
        domainFilterPrefs = getSharedPreferences(DomainFilter.PREFS_NAME, MODE_PRIVATE);
        enabledDomainPresets.addAll(DomainFilter.loadEnabledPresets(domainFilterPrefs));
        customDomains.addAll(DomainFilter.loadCustomDomains(domainFilterPrefs));
        versionCheck.appVersion = versionCheck.readAppVersion();
        applyPalette();
        configureSystemBars();
        buildShell();
        showPage(PAGE_HOME);
        appendLog("Готово. При первом запуске Android запросит разрешение на туннель.");
        versionCheck.checkForUpdates();
        requestNotificationPermissionIfNeeded();
        shareLinks.handleShareIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        shareLinks.handleShareIntent(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        // An animator keeps ticking off-screen; onStart's refresh restarts it.
        homePage.setRingPulsing(false);
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (currentPage == PAGE_SETTINGS && settingsPage.settingsDetailOpen) {
            settingsPage.closeSettingsDetail();
            return;
        }
        if (currentPage == PAGE_SETTINGS && settingsPage.clearSearch()) return;
        if (currentPage == PAGE_PROFILES && profileEditorOpen) {
            closeProfileEditor();
            return;
        }
        // Another tab steps back to Home, as bottom-nav apps do; Home asks for
        // a second press so one stray swipe does not leave the app.
        if (currentPage != PAGE_HOME) {
            showPage(PAGE_HOME);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastBackPress > 2000) {
            lastBackPress = now;
            tap(root);
            Toast.makeText(this, "Нажмите «Назад» ещё раз, чтобы выйти", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    private long lastBackPress;

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == QR_IMAGE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                shareLinks.importQrImage(data.getData());
            }
            return;
        }
        if (requestCode == NODE_WIZARD_REQUEST) {
            profiles = profileStore.load();
            selectedProfileId = profileStore.getSelectedId();
            applySelectedProfileToFields();
            int page = data == null ? PAGE_PROFILES
                    : data.getIntExtra(NodeWizardActivity.EXTRA_NAV_PAGE, PAGE_PROFILES);
            showPage(page >= PAGE_HOME && page <= PAGE_SETTINGS ? page : PAGE_PROFILES);
            return;
        }
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            if (scan.getContents() != null) shareLinks.importShareLink(scan.getContents());
            return;
        }
        if (settingsPage.scriptsPanel != null && settingsPage.scriptsPanel.onActivityResult(requestCode, resultCode, data)) return;
        if (requestCode == SCRIPT_SETTINGS_REQUEST) {
            profileEditor.onScriptSettings(resultCode == RESULT_OK && data != null
                    ? data.getStringExtra(CaptchaActivity.EXTRA_SUBMITTED) : null);
        }
        if (requestCode == TUNNEL_PERMISSION_REQUEST && resultCode == RESULT_OK) startTunnel();
        else if (requestCode == TUNNEL_PERMISSION_REQUEST) appendLog("[ERROR] Разрешение на создание туннеля не выдано");
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

    // ---- Shell and navigation ----

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
        body.addView(buildCompactHeader(), new LinearLayout.LayoutParams(-1, -2));

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
        header.setMinimumHeight(dp(54));
        header.setPadding(0, dp(5), 0, dp(5));

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

        versionCheck.versionBadge = text("", 10, accent, true);
        versionCheck.versionBadge.setGravity(Gravity.CENTER);
        versionCheck.versionBadge.setPadding(dp(9), dp(5), dp(9), dp(5));
        header.addView(versionCheck.versionBadge);
        versionCheck.updateVersionBadge();
        return header;
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

    private int navHeight() {
        return dp(68);
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

    // Content now scrolls behind the floating nav pill (for the fade effect
    // behind it), so every scrollable page needs this much extra bottom room
    // or its last item - often a Save button - ends up stuck under the pill.
    @Override int navClearance() {
        return navHeight() + dp(10) + navBottomInset + dp(16);
    }

    // The scrolling page content that holds view, so the whole page
    // reflows together.
    @Override ViewGroup transitionRoot(View view) {
        View child = view;
        for (android.view.ViewParent p = view.getParent(); p instanceof ViewGroup; p = p.getParent()) {
            if (p instanceof ScrollView || p == content) return child instanceof ViewGroup ? (ViewGroup) child : null;
            child = (View) p;
        }
        return null;
    }

    void showPage(int page) {
        captureSettings();
        if (page != PAGE_SETTINGS) {
            settingsPage.settingsDetailOpen = false;
            settingsPage.searchQuery = "";
        }
        if (page != PAGE_PROFILES) profileEditorOpen = false;
        currentPage = page;
        pinnedSave = null;
        View pageView = page == PAGE_HOME ? homePage.buildHomePage()
                : page == PAGE_PROFILES ? profilesPage.buildProfilesPage()
                : page == PAGE_LOGS ? logsPage.buildLogsPage() : settingsPage.buildSettingsPage();
        crossfadeContent(pageView);
        View oldNav = root.getChildAt(root.getChildCount() - 1);
        ViewGroup.LayoutParams navParams = oldNav.getLayoutParams();
        root.removeView(oldNav);
        root.addView(buildBottomNav(), navParams);
        homePage.updateStatus();
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

    void switchTheme(boolean checked) {
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

    void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    void openNodeWizard() {
        if (OpenFluxTunnelService.isRunning() || OpenFluxProxyService.isRunning()
                || OpenFluxExitService.isRunning()) {
            Toast.makeText(this, "Отключите OpenFlux перед настройкой ноды", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(new Intent(this, NodeWizardActivity.class), NODE_WIZARD_REQUEST);
    }

    // ---- Profiles ----

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

    Profile selectedProfile() {
        for (Profile p : profiles) if (p.id == selectedProfileId) return p;
        return null;
    }

    void selectProfile(long id) {
        if (id != selectedProfileId && isConnectionRunning()) {
            Toast.makeText(this, "Сначала отключитесь, затем меняйте профиль", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedProfileId = id;
        profileStore.setSelectedId(id);
        applySelectedProfileToFields();
        showPage(PAGE_HOME);
    }

    // applySelectedProfileToFields refreshes the plain documentUrl/
    // encryptionSecret/transportType fields that toggleConnection/startTunnel/
    // startProxy already read, from whichever profile is currently selected.
    void applySelectedProfileToFields() {
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

    void deleteProfile(Profile profile) {
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

    void closeProfileEditor() {
        profileEditorOpen = false;
        showPage(PAGE_PROFILES);
    }

    int profileTransportIcon(Profile profile) {
        if (profile == null) return R.drawable.ic_public;
        String type = profile.transportType;
        int priority = profile.priority;
        if (profile.session) {
            for (Profile.Transport transport : profile.extraTransports) {
                if (transport.priority > priority) {
                    type = transport.type;
                    priority = transport.priority;
                }
            }
        }
        switch (type) {
            case "yandex":
            case "vyandex":
            case "boards":
            case "telemost": return R.drawable.ic_yandex;
            case "mailru": return R.drawable.ic_mailru;
            case "cupsonline": return R.drawable.ic_cupsonline;
            case "avito": return R.drawable.ic_avito;
            case "script": return R.drawable.ic_code;
            case "oneme": return R.drawable.ic_max;
            case "direct": return R.drawable.ic_link;
            default: return R.drawable.ic_public;
        }
    }

    String transportLabel(String type) {
        return Profile.transportLabel(type);
    }

    String carrierLabel(String type, String scriptId) {
        return "script".equals(type) ? "JS: " + scriptId : transportLabel(type);
    }

    String profileTransportSummary(Profile p) {
        if (p.stream) return "Без сервера: " + transportLabel(p.transportType);
        if (p.session) {
            StringBuilder summary = new StringBuilder("Session: ").append(carrierLabel(p.transportType, p.scriptId));
            for (Profile.Transport t : p.extraTransports) summary.append(" + ").append(carrierLabel(t.type, t.scriptId));
            return summary.toString();
        }
        return transportLabel(p.transportType);
    }

    static int parsePriority(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    boolean isValidDocumentUrl(String value) {
        if (value == null || !value.startsWith("https://")) return false;
        try {
            android.net.Uri uri = android.net.Uri.parse(value);
            return uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    // ---- Settings ----

    // Called on every navigation (showPage), not just when leaving a settings
    // page with unsaved edits - so it must NOT commit the Network/Mode draft
    // fields (applyNetworkSettings/applyModeSettings do that, only when the
    // save button is pressed). The log buffer is kept separately from the
    // filtered view, so switching pages must not copy the visible text back.
    void captureSettings() {
        profileEditor.urlInput = null;
        profileEditor.encryptionInput = null;
        profileEditor.profileNameInput = null;
        settingsPage.dnsInput = null;
        settingsPage.mtuInput = null;
        settingsPage.proxyPortInput = null;
        settingsPage.proxyUsernameInput = null;
        settingsPage.proxyPasswordInput = null;
        logsPage.logView = null;
        logsPage.logScroll = null;
    }

    void persistSettings() {
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
                .commit();
    }

    // ---- Connection ----

    boolean isProxyMode() {
        return MODE_PROXY.equals(connectionMode);
    }

    boolean isExitMode() {
        return MODE_EXIT.equals(connectionMode);
    }

    static String modeLabel(String mode) {
        if (MODE_PROXY.equals(mode)) return "Прокси (SOCKS5)";
        if (MODE_EXIT.equals(mode)) return "Выходная нода (L4)";
        return "Туннель (весь трафик)";
    }

    boolean isConnectionRunning() {
        if (isExitMode()) return OpenFluxExitService.isRunning();
        return isProxyMode() ? OpenFluxProxyService.isRunning() : OpenFluxTunnelService.isRunning();
    }

    String connectionStatus() {
        if (isExitMode()) return OpenFluxExitService.getStatus();
        return isProxyMode() ? OpenFluxProxyService.getStatus() : OpenFluxTunnelService.getStatus();
    }

    String connectionLastError() {
        if (isExitMode()) return OpenFluxExitService.getLastError();
        return isProxyMode() ? OpenFluxProxyService.getLastError() : OpenFluxTunnelService.getLastError();
    }

    long connectionStartedAt() {
        if (isExitMode()) return OpenFluxExitService.getConnectedAtMillis();
        return isProxyMode() ? OpenFluxProxyService.getConnectedAtMillis() : OpenFluxTunnelService.getConnectedAtMillis();
    }

    void toggleConnection() {
        if (isConnectionRunning()) {
            stopConnection(connectionMode);
            appendLog("Запрошена остановка: " + modeLabel(connectionMode));
            return;
        }
        String valueProblem = profileEditor.transportValueProblem(transportType,
                "oneme".equals(transportType) ? (maxToken == null ? "" : maxToken) : documentUrl, isExitMode());
        if (valueProblem != null) {
            Toast.makeText(this, valueProblem, Toast.LENGTH_LONG).show();
            showPage(PAGE_PROFILES);
            return;
        }
        Profile selected = selectedProfile();
        String scriptProblem = selected == null ? null : selected.scriptProblem(new ScriptStore(this));
        if (scriptProblem != null) {
            Toast.makeText(this, scriptProblem, Toast.LENGTH_LONG).show();
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
            settingsPage.openSettingsDetail(SettingsPage.SETTINGS_MODE);
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

    void stopConnection(String mode) {
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

    private void putProfileExtras(Intent intent) {
        Profile p = selectedProfile();
        if (p != null) {
            p.putConnectionExtras(intent, new ScriptStore(this));
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

    // getLocalIpAddress finds this device's IPv4 address on whatever network
    // it's currently attached to (Wi-Fi, a hotspot it joined, Ethernet, ...)
    // by scanning network interfaces directly, so it works the same way
    // regardless of connection type and needs no extra permission.
    String getLocalIpAddress() {
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

    // ---- Log ----

    void appendLog(String value) {
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
        logsPage.renderLogs();
    }
}
