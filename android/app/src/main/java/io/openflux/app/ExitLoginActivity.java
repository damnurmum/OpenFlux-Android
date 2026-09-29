package io.openflux.app;

import android.app.Activity;
import android.graphics.Insets;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.openflux.bridge.mobile.Mobile;

// Lets a connected Session client offer its Yandex sign-in to the exit node.
// This is separate from CaptchaActivity, which answers a pending core request.
public final class ExitLoginActivity extends Activity {
    private static final String LOGIN_URL = "https://passport.yandex.ru/auth";
    private Button send;
    private WebView browser;
    private FlowStyle ui;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new FlowStyle(this);
        ui.applyWindow();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(20));
        page.setBackgroundColor(ui.background);
        if (Build.VERSION.SDK_INT >= 30) {
            page.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(dp(20) + bars.left, dp(18) + bars.top,
                        dp(20) + bars.right, dp(20) + bars.bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            page.setFitsSystemWindows(true);
        }

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setImageTintList(ColorStateList.valueOf(ui.secondary));
        back.setBackground(ui.rounded(ui.surface, ui.border, 10));
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        backParams.rightMargin = dp(12);
        heading.addView(back, backParams);
        TextView title = new TextView(this);
        title.setText("Вход в Яндекс для ноды");
        title.setTextSize(22);
        ui.heading(title);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(heading);

        TextView help = new TextView(this);
        help.setText("Войдите в аккаунт, затем нажмите «Передать вход». Нужна активная Session с выходной нодой.");
        help.setTextSize(13);
        ui.body(help);
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(-1, -2);
        helpParams.topMargin = dp(12);
        helpParams.bottomMargin = dp(16);
        page.addView(help, helpParams);

        browser = new WebView(this);
        browser.setBackground(ui.rounded(ui.surface, ui.border, 16));
        browser.setClipToOutline(true);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true);
        browser.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                    WebResourceError error) {
                if (request.isForMainFrame()) {
                    help.setText("Не удалось открыть страницу входа: " + error.getDescription());
                }
            }
        });
        page.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));

        send = new Button(this);
        send.setText("Передать вход ноде");
        send.setAllCaps(false);
        ui.primary(send);
        send.setOnClickListener(v -> offerCookies());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(52));
        buttonParams.topMargin = dp(10);
        buttonParams.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(send, buttonParams);
        setContentView(page, new ViewGroup.LayoutParams(-1, -1));
        browser.loadUrl(LOGIN_URL);
    }

    private void offerCookies() {
        boolean connected = (OpenFluxTunnelService.isRunning() && Mobile.isConnected())
                || (OpenFluxProxyService.isRunning() && Mobile.proxyIsConnected());
        if (!connected) {
            Toast.makeText(this, "Сначала подключите Session к ноде", Toast.LENGTH_LONG).show();
            return;
        }
        CookieManager manager = CookieManager.getInstance();
        manager.flush();
        StringBuilder header = new StringBuilder();
        for (String url : new String[] {"https://passport.yandex.ru/", "https://yandex.ru/",
                "https://docs.yandex.ru/", "https://disk.yandex.ru/"}) {
            String cookies = manager.getCookie(url);
            if (cookies == null || cookies.isEmpty()) continue;
            if (header.length() > 0) header.append("; ");
            header.append(cookies);
        }
        if (header.length() == 0 || !Mobile.nodeSignedIn(header.toString())) {
            Toast.makeText(this, "Сначала войдите в аккаунт Яндекса", Toast.LENGTH_LONG).show();
            return;
        }
        send.setEnabled(false);
        String cookies = header.toString();
        new Thread(() -> {
            try {
                long count = Mobile.offerExitCookies("vyandex,yandex,boards", cookies);
                runOnUiThread(() -> {
                    send.setEnabled(true);
                    if (count == 0) {
                        Toast.makeText(this, "В Session нет транспорта Яндекса", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Вход передан ноде", Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    send.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "offer-exit-cookies").start();
    }

    @Override protected void onDestroy() {
        if (browser != null) {
            browser.stopLoading();
            browser.destroy();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
