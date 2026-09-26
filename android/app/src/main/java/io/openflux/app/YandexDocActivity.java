package io.openflux.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.regex.Pattern;

// Signs in to Yandex in a WebView and, as that user, creates the channel's
// document: the /openflux folder on Disk, a new Volga document in it, and
// edit access for anyone with the link, which both the node and the client
// need. It drives the same internal endpoints the Disk web client calls
// (there is no public API for edit-by-link on personal accounts), from the
// Disk page itself. On success the Yandex cookies for the document go back
// to the wizard (only in memory), which hands them to the node over SSH so
// it opens the document signed in. On the way out every cookie is wiped:
// the login must not stay in the WebView, where CaptchaActivity would pass
// it along with a captcha's cookies.
public final class YandexDocActivity extends Activity {
    static final String EXTRA_FILENAME = "filename";
    static final String EXTRA_URL = "url";
    // The Cookie header the WebView holds for the document: the sign-in.
    static final String EXTRA_COOKIES = "cookies";

    private static final String START_URL = "https://disk.yandex.ru/client/disk";
    // Desktop UA: the Disk web client (and the page data the script reads)
    // is the desktop one.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:153.0) Gecko/20100101 Firefox/153.0";
    private static final Pattern DOC_URL =
            Pattern.compile("^https://(docs|disk)\\.yandex\\.[a-z]{2,3}/edit/d/[A-Za-z0-9_-]{16,200}$");

    private WebView web;
    private TextView status;
    private String filename;
    private boolean running;
    private boolean finished;
    private int waits;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filename = getIntent().getStringExtra(EXTRA_FILENAME);
        if (filename == null || filename.isEmpty()) {
            finish();
            return;
        }

        status = new TextView(this);
        status.setText("Войдите в аккаунт Яндекса. Документ будет создан в папке openflux на вашем Диске.");
        status.setTextSize(14);
        Button cancel = new Button(this);
        cancel.setText("Отмена");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> close(null));

        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(pad * 2, pad, pad, pad);
        bar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(cancel);

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(USER_AGENT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "OpenFluxDisk");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith("https://disk.yandex.ru/client")) {
                    automate();
                } else if (url != null && url.contains("passport.yandex")) {
                    status.setText("Войдите в аккаунт Яндекса. Документ будет создан в папке openflux на вашем Диске.");
                }
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(bar);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
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
        web.loadUrl(START_URL);
    }

    private void automate() {
        if (running || finished) return;
        running = true;
        status.setText("Создаю документ…");
        web.evaluateJavascript(script(filename), null);
    }

    // The same calls the Disk web client makes: page data holds the CSRF
    // keys (sk for /models-v2, skExternal for /editnew).
    private static String script(String name) {
        return "(async () => {\n"
                + "const B = window.OpenFluxDisk;\n"
                + "try {\n"
                + "  const el = document.getElementById('preloaded-data');\n"
                + "  const cfg = el ? (JSON.parse(el.textContent).config || {}) : {};\n"
                + "  if (!cfg.sk || !cfg.skExternal) { B.waiting(); return; }\n"
                + "  const call = async (m, p) => {\n"
                + "    const r = await fetch('/models-v2?m=' + m, {method: 'POST', credentials: 'include',\n"
                + "      headers: {'Content-Type': 'application/json'},\n"
                + "      body: JSON.stringify({sk: cfg.sk, connection_id: cfg.idClient, apiMethod: m, requestParams: p})});\n"
                + "    const t = await r.text(); let j = null; try { j = JSON.parse(t); } catch (e) {}\n"
                + "    if (!r.ok || (j && j.error)) throw new Error(m + ': ' + ((j && j.error && (j.error.title || j.error.code)) || r.status));\n"
                + "    return j;\n"
                + "  };\n"
                + "  const info = async (path) => { const r = await call('mpfs/bulk-resource-info', {ids: [path]});\n"
                + "    return Array.isArray(r) && r.length ? r[0] : null; };\n"
                + "  B.step('Создаю папку openflux…');\n"
                + "  const dir = await info('/disk/openflux');\n"
                + "  if (!dir) await call('mpfs/mkdir', {path: '/disk/openflux'});\n"
                + "  else if (dir.type !== 'dir') throw new Error('на Диске уже есть файл openflux, а нужна папка');\n"
                + "  B.step('Создаю документ…');\n"
                + "  const name = " + JSONObject.quote(name) + ";\n"
                + "  const path = '/disk/openflux/' + name + '.docx';\n"
                + "  let file = await info(path);\n"
                + "  if (!file) {\n"
                + "    const r = await fetch('/editnew/docx/disk/openflux?sk=' + encodeURIComponent(cfg.skExternal)\n"
                + "      + '&filename=' + encodeURIComponent(name), {credentials: 'include'});\n"
                + "    if (!r.ok) throw new Error('создание документа: ' + r.status);\n"
                + "    for (let i = 0; i < 30 && !file; i++) { file = await info(path); if (!file) await new Promise(r => setTimeout(r, 500)); }\n"
                + "  }\n"
                + "  if (!file || !file.meta) throw new Error('документ не появился на Диске');\n"
                + "  B.step('Открываю редактирование по ссылке…');\n"
                + "  await call('mpfs/set-public', {path: path, type: 'file', allowDefaultSettingsAvailable: true});\n"
                + "  await call('mpfs/office-set-access-state', {resourceId: file.meta.resource_id, accessState: 'all'});\n"
                + "  file = await info(path);\n"
                + "  const url = file && file.meta && file.meta.office_online_sharing_url;\n"
                + "  if (!url || file.meta.office_access_state !== 'all') throw new Error('не удалось открыть редактирование по ссылке');\n"
                + "  B.done(url);\n"
                + "} catch (e) { B.fail(String((e && e.message) || e)); }\n"
                + "})();";
    }

    private final class Bridge {
        @JavascriptInterface public void step(String text) {
            runOnUiThread(() -> { if (!finished) status.setText(text); });
        }

        // The page is still loading or signed out: try again shortly.
        @JavascriptInterface public void waiting() {
            runOnUiThread(() -> {
                running = false;
                if (finished || ++waits > 20) return;
                web.postDelayed(() -> {
                    String url = web.getUrl();
                    if (url != null && url.startsWith("https://disk.yandex.ru/client")) automate();
                }, 1000);
            });
        }

        @JavascriptInterface public void done(String url) {
            runOnUiThread(() -> {
                if (url == null || !DOC_URL.matcher(url).matches()) {
                    fail("Яндекс вернул неожиданную ссылку на документ");
                    return;
                }
                close(url);
            });
        }

        @JavascriptInterface public void fail(String message) {
            runOnUiThread(() -> YandexDocActivity.this.fail(message));
        }
    }

    private void fail(String message) {
        running = false;
        if (finished) return;
        status.setText("Не получилось: " + message + ". Нажмите «Отмена» и вставьте ссылку на документ вручную.");
    }

    // Leaves with the document URL (or none), wiping the Yandex login.
    private void close(String url) {
        if (finished) return;
        finished = true;
        String cookies = url != null ? CookieManager.getInstance().getCookie(url) : null;
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        if (web != null) {
            web.clearCache(true);
            web.clearHistory();
        }
        if (url != null) {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_COOKIES, cookies != null ? cookies : ""));
        }
        else setResult(RESULT_CANCELED);
        finish();
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else close(null);
    }

    @Override protected void onDestroy() {
        if (!finished && isFinishing()) close(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
