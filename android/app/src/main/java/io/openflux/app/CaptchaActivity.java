package io.openflux.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.util.Collections;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.openflux.bridge.mobile.Mobile;

// Lets the user pass a Yandex SmartCaptcha (or log in) when the transport
// core can't get through on its own, then hands the resulting cookies back
// to it. Android's side of the core's out-of-band cookie flow - desktop and
// iOS do the same over transport/ipc.
//
// A JS transport's own page (its inline html, or its own server on
// 127.0.0.1, PendingCaptchaOwn) is shown as it is and hands its data over
// through window.openfluxSubmit instead of cookies. With EXTRA_SETTINGS_HTML
// the same page host shows a script's settings page for the profile editor
// and returns what it submitted (EXTRA_SUBMITTED) instead of calling the core.
public class CaptchaActivity extends Activity {
    // The settings-page host: the same page code, its own manifest entry.
    public static final class Settings extends CaptchaActivity { }

    static final String EXTRA_SETTINGS_HTML = "io.openflux.app.SETTINGS_HTML";
    static final String EXTRA_TITLE = "io.openflux.app.SETTINGS_TITLE";
    static final String EXTRA_SUBMITTED = "io.openflux.app.SUBMITTED";
    // window.openfluxSubmit for a script's page; fires "openflux-ready" once
    // defined. Idempotent: it goes in before the page's scripts where it can
    // and again when the page has loaded.
    private static final String SUBMIT_BRIDGE = "OpenFluxSubmit";
    private static final String BRIDGE_JS = "(function(){if(window.openfluxSubmit)return;"
            + "window.openfluxSubmit=function(p){try{var j=JSON.stringify(p);window." + SUBMIT_BRIDGE + ".submit(j)}catch(e){}};"
            + "setTimeout(function(){try{window.dispatchEvent(new Event('openflux-ready'))}catch(e){}},0)})();";
    private static final Pattern LOOPBACK = Pattern.compile(
            "(?i)http://(127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|localhost|\\[::1\\]):(\\d{1,5})(?:[/?#].*)?");

    // Same UA the Go transport uses for the document fetch: the captcha pass
    // may be bound to it, so solving under a different one could be useless.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:153.0) Gecko/20100101 Firefox/153.0";
    private static final String CHANNEL_ID = "openflux_captcha";
    private static final int NOTIFICATION_ID = 9;

    private static volatile boolean solved;

    private String startUrl;
    // Set when the check belongs to the exit node: the page must load through
    // this proxy (the tunnel) so it is passed from the node's address.
    private String proxy = "";
    private boolean proxyOverridden;
    private volatile String currentUrl;
    private boolean submitted;
    // A script's own page: its inline html, or the origin of its own server.
    private String ownHtml;
    private String ownOrigin;
    private boolean settingsMode;

    // Whether the core waits on a page: a real site's (URL) or a script's
    // inline one (HTML only, no URL).
    static boolean pending() {
        return !Mobile.pendingCaptchaURL().isEmpty() || !Mobile.pendingCaptchaHTML().isEmpty();
    }

    static void initCookieStore(Context context) {
        Mobile.setCookieStorePath(new File(context.getFilesDir(), "transport-cookies.json").getPath());
    }

    // Blocks the calling worker while the core waits for a solve, surfacing
    // it as a notification. Returns true once the user submitted cookies,
    // false if nothing was pending, the user cancelled, or the session ended.
    static boolean awaitIfPending(Context context, BooleanSupplier stillCurrent) {
        if (!pending()) return false;
        solved = false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Проверка Яндекса", NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(context, CaptchaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(
                context, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean login = "login".equals(Mobile.pendingCaptchaReason());
        boolean remote = !Mobile.pendingCaptchaProxy().isEmpty();
        boolean own = Mobile.pendingCaptchaOwn() || !Mobile.pendingCaptchaHTML().isEmpty();
        String title = own ? "OpenFlux: транспорт просит настройку" : remote
                ? (login ? "OpenFlux: ноде нужен вход в Яндекс" : "OpenFlux: нода просит пройти проверку")
                : (login ? "OpenFlux: нужен вход в Яндекс" : "OpenFlux: нужна проверка");
        manager.notify(NOTIFICATION_ID, new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(remote ? "Нажмите, чтобы открыть проверку для ноды"
                        : "Нажмите, чтобы продолжить подключение")
                .setSmallIcon(R.drawable.ic_openflux_notification)
                .setAutoCancel(true)
                .setContentIntent(content)
                .build());
        try {
            while (stillCurrent.getAsBoolean() && pending()) {
                Thread.sleep(500);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            manager.cancel(NOTIFICATION_ID);
        }
        return solved && stillCurrent.getAsBoolean();
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String reason = Mobile.pendingCaptchaReason();
        settingsMode = getIntent().hasExtra(EXTRA_SETTINGS_HTML);
        if (settingsMode) {
            ownHtml = getIntent().getStringExtra(EXTRA_SETTINGS_HTML);
            startUrl = "";
            reason = getIntent().getStringExtra(EXTRA_TITLE);
        } else {
            startUrl = Mobile.pendingCaptchaURL();
            String html = Mobile.pendingCaptchaHTML();
            if (!html.isEmpty()) ownHtml = html;
            else if (Mobile.pendingCaptchaOwn()) ownOrigin = loopbackOrigin(startUrl);
            if (startUrl.isEmpty() && ownHtml == null) {
                finish();
                return;
            }
        }
        boolean own = ownHtml != null || ownOrigin != null;
        boolean login = "login".equals(reason);
        // A script's page never goes through the exit's proxy: it is inline or loopback.
        proxy = own ? "" : Mobile.pendingCaptchaProxy();
        boolean remote = !proxy.isEmpty();

        TextView title = new TextView(this);
        title.setText(own
                ? (reason == null || reason.isEmpty() ? "Настройка транспорта" : reason)
                : remote
                ? (login ? "Вход в Яндекс для ноды" : "Проверка для ноды")
                : (login ? "Войдите в Яндекс" : "Пройдите проверку"));
        title.setTextSize(16);
        Button done = new Button(this);
        done.setText("Готово");
        done.setOnClickListener(v -> submit());
        Button cancel = new Button(this);
        cancel.setText("Отмена");
        cancel.setOnClickListener(v -> cancel());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad * 2, pad, pad, pad);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(cancel);
        // A script's page submits itself.
        if (!own) bar.addView(done);

        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(USER_AGENT);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        if (own) {
            web.addJavascriptInterface(new Submit(), SUBMIT_BRIDGE);
            if (ownOrigin != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, BRIDGE_JS,
                        Collections.singleton(ownOrigin.substring(0, ownOrigin.length() - 1)));
            }
        }
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentUrl = url;
            }

            @Override public void onPageFinished(WebView view, String url) {
                currentUrl = url;
                if (own) {
                    if (bridgeAllowedAt(url)) view.evaluateJavascript(BRIDGE_JS, null);
                    return;
                }
                // A real browser is often let through without any check (the
                // captcha targets the transport's bot-like client), so any
                // regular page counts as passed. The short delay lets a page
                // that bounces to a check get there before we submit.
                if (!isCheckpoint(url)) {
                    view.postDelayed(() -> {
                        if (!isCheckpoint(currentUrl)) submit();
                    }, 1500);
                }
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(bar);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // Keep the bar and the page clear of the status/navigation bars and
        // the keyboard (edge-to-edge is enforced from Android 15).
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
        if (ownHtml != null) {
            web.loadDataWithBaseURL(null, inject(ownHtml, BRIDGE_JS), "text/html", "utf-8", null);
            return;
        }
        if (!remote) {
            web.loadUrl(startUrl);
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Toast.makeText(this, "WebView не умеет работать через прокси: обновите Android System WebView",
                    Toast.LENGTH_LONG).show();
            cancel();
            return;
        }
        ProxyConfig config = new ProxyConfig.Builder().addProxyRule(proxy).build();
        proxyOverridden = true;
        ProxyController.getInstance().setProxyOverride(config, Runnable::run, () -> web.loadUrl(startUrl));
    }

    @Override protected void onDestroy() {
        // The override applies to every WebView in the process; drop it.
        if (proxyOverridden) ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> { });
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        cancel();
    }

    private static boolean isCheckpoint(String url) {
        return url != null && (url.contains("showcaptcha") || url.contains("passport.yandex"));
    }

    private void submit() {
        if (submitted) return;
        CookieManager manager = CookieManager.getInstance();
        manager.flush();
        StringBuilder header = new StringBuilder();
        for (String url : new String[] {startUrl, currentUrl}) {
            String part = url == null ? null : manager.getCookie(url);
            if (part == null || part.isEmpty()) continue;
            if (header.length() > 0) header.append("; ");
            header.append(part);
        }
        String error = Mobile.submitCaptchaCookies(header.toString());
        if (error != null && !error.isEmpty()) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            return;
        }
        submitted = true;
        solved = true;
        finish();
    }

    private void cancel() {
        if (!submitted && !settingsMode) Mobile.cancelCaptcha();
        finish();
    }

    // What a script's page handed to window.openfluxSubmit.
    private void submitData(String json) {
        if (submitted) return;
        if (settingsMode) {
            submitted = true;
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_SUBMITTED, json));
            finish();
            return;
        }
        String error = Mobile.submitCaptchaData(json);
        if (error != null && !error.isEmpty()) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            return;
        }
        submitted = true;
        solved = true;
        finish();
    }

    // The submit channel belongs to the page's own address: once the WebView
    // has followed a link anywhere else, what it sends is dropped.
    private boolean bridgeAllowedAt(String at) {
        if (ownHtml != null) return at == null || at.isEmpty() || at.startsWith("about:") || at.startsWith("data:");
        return ownOrigin != null && at != null && at.toLowerCase(Locale.ROOT).startsWith(ownOrigin);
    }

    private final class Submit {
        @JavascriptInterface public void submit(String json) {
            // Called on a WebView thread; currentUrl is volatile.
            if (!bridgeAllowedAt(currentUrl)) return;
            runOnUiThread(() -> submitData(json));
        }
    }

    // "http://host:port/" of a page on the script's own loopback server, the
    // prefix its address must keep for the bridge; null for anything else.
    static String loopbackOrigin(String url) {
        Matcher m = LOOPBACK.matcher(url == null ? "" : url.trim());
        if (!m.matches()) return null;
        int port = Integer.parseInt(m.group(2));
        if (port < 1 || port > 65535) return null;
        return "http://" + m.group(1).toLowerCase(Locale.ROOT) + ":" + port + "/";
    }

    // Puts a script into a page where it runs before the page's own and the
    // page stays in standards mode: after <head>, else <html>, else the
    // doctype, else in front (the core's devhost.InjectSnippet does the same).
    static String inject(String page, String snippet) {
        String tag = "<script>" + snippet + "</script>";
        // A-Z only, so every index into lower is an index into page.
        char[] chars = page.toCharArray();
        for (int i = 0; i < chars.length; i++) if (chars[i] >= 'A' && chars[i] <= 'Z') chars[i] += 32;
        String lower = new String(chars);
        for (String open : new String[]{"<head", "<html"}) {
            int i = lower.indexOf(open);
            if (i < 0) continue;
            int end = i + open.length();
            // The tag must really be the tag (<header is not <head).
            if (end < lower.length() && " \t\n\r>/".indexOf(lower.charAt(end)) >= 0) {
                int close = lower.indexOf('>', end);
                if (close >= 0) return page.substring(0, close + 1) + tag + page.substring(close + 1);
            }
        }
        if (lower.trim().startsWith("<!doctype")) {
            int close = lower.indexOf('>');
            if (close >= 0) return page.substring(0, close + 1) + tag + page.substring(close + 1);
        }
        return tag + page;
    }
}
