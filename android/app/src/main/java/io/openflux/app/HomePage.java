package io.openflux.app;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import io.openflux.bridge.mobile.Mobile;

// The Home tab: the connect button (its state, uptime, speed and carriers),
// the selected profile, the active parameters, and in exit mode the QR
// card clients scan to use this phone.
final class HomePage {
    private final MainActivity app;
    private LinearLayout tunnelButton;
    private ImageView tunnelPowerIcon;
    private TextView tunnelButtonText;
    private TextView uptimeView;
    private TextView speedView;
    private View ringWave;

    // Exit mode: the home card with the QR clients scan to join this phone;
    // exitShareShown is the link (or error) it currently renders.
    private LinearLayout exitShareCard;

    private String exitShareShown;
    private ObjectAnimator ringPulse;
    private int lastTunnelButtonFill = -1;
    private String lastAnnouncedState = "";

    HomePage(MainActivity app) {
        this.app = app;
    }

    private View buildProfileSelectorRow() {
        Profile p = app.selectedProfile();
        LinearLayout row = new LinearLayout(app);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(app.dp(16), app.dp(14), app.dp(16), app.dp(14));
        row.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(app.iconBubble(app.profileTransportIcon(p)),
                new LinearLayout.LayoutParams(app.dp(40), app.dp(40)));
        LinearLayout copy = new LinearLayout(app);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = app.dp(14);
        copy.addView(app.text(p != null ? p.name : "Профиль не выбран", 15, app.text, true));
        copy.addView(app.text(p != null ? app.profileTransportSummary(p) : "Нажмите, чтобы создать профиль",
                12, app.secondary, false));
        row.addView(copy, copyParams);
        row.addView(app.icon(R.drawable.ic_chevron_right, app.hint), new LinearLayout.LayoutParams(app.dp(20), app.dp(20)));
        row.setOnClickListener(v -> {
            app.tap(v);
            if (app.profiles.isEmpty()) app.showPage(MainActivity.PAGE_PROFILES);
            else if (app.isConnectionRunning()) {
                Toast.makeText(app, "Сначала отключитесь, затем меняйте профиль", Toast.LENGTH_SHORT).show();
            } else app.profilesPage.showProfileDropdown(v);
        });
        return row;
    }

    View buildHomePage() {
        if (ringPulse != null) {
            ringPulse.cancel();
            ringPulse = null;
        }
        lastTunnelButtonFill = -1;

        LinearLayout page = app.page();

        // Big circular connect button: two static concentric rings (in the
        // spirit of the original OpenFluxAndroid app's main toggle) plus a
        // slow expanding/fading "wave" ring behind them, tinted to match the
        // button's own current color (see animateTunnelButtonFill). Status
        // and connection time live inside the button itself, Happ-style,
        // instead of a separate status card.
        FrameLayout buttonStack = new FrameLayout(app);
        buttonStack.setClipChildren(false);
        ringWave = new View(app);
        ringWave.setBackground(app.rounded(Color.TRANSPARENT, app.accent, 2, 70));
        buttonStack.addView(ringWave, new FrameLayout.LayoutParams(app.dp(140), app.dp(140), Gravity.CENTER));
        View ringOuter = new View(app);
        ringOuter.setBackground(ringOutline());
        buttonStack.addView(ringOuter, new FrameLayout.LayoutParams(app.dp(200), app.dp(200), Gravity.CENTER));
        View ringMid = new View(app);
        ringMid.setBackground(ringOutline());
        buttonStack.addView(ringMid, new FrameLayout.LayoutParams(app.dp(170), app.dp(170), Gravity.CENTER));

        tunnelButton = new LinearLayout(app);
        tunnelButton.setOrientation(LinearLayout.VERTICAL);
        tunnelButton.setGravity(Gravity.CENTER);
        tunnelButton.setClickable(true);
        tunnelButton.setFocusable(true);
        tunnelButton.setElevation(app.dp(2));
        tunnelPowerIcon = app.icon(R.drawable.ic_power, Color.WHITE);
        tunnelButton.addView(tunnelPowerIcon, new LinearLayout.LayoutParams(app.dp(30), app.dp(30)));
        tunnelButtonText = app.text("Остановлено", 13, Color.WHITE, true);
        tunnelButtonText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusTextParams = app.matchWrap();
        statusTextParams.topMargin = app.dp(8);
        tunnelButton.addView(tunnelButtonText, statusTextParams);
        uptimeView = app.text("", 11, Color.WHITE, false);
        uptimeView.setAlpha(0.85f);
        uptimeView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams uptimeParams = app.matchWrap();
        uptimeParams.topMargin = app.dp(2);
        tunnelButton.addView(uptimeView, uptimeParams);
        speedView = app.text("", 10, Color.WHITE, false);
        speedView.setAlpha(0.7f);
        speedView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams speedParams = app.matchWrap();
        speedParams.topMargin = app.dp(2);
        tunnelButton.addView(speedView, speedParams);
        tunnelButton.setOnClickListener(v -> {
            app.bounce(v);
            app.toggleConnection();
        });
        buttonStack.addView(tunnelButton, new FrameLayout.LayoutParams(app.dp(140), app.dp(140), Gravity.CENTER));

        LinearLayout.LayoutParams stackParams = new LinearLayout.LayoutParams(app.dp(200), app.dp(200));
        stackParams.gravity = Gravity.CENTER_HORIZONTAL;
        stackParams.topMargin = app.dp(16);
        page.addView(buttonStack, stackParams);
        app.staggerIn(buttonStack, 30);

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

        LinearLayout.LayoutParams selectorParams = app.matchWrap();
        selectorParams.topMargin = app.dp(20);
        View profileSelector = buildProfileSelectorRow();
        page.addView(profileSelector, selectorParams);
        app.staggerIn(profileSelector, 80);

        TextView summaryTitle = app.label("АКТИВНЫЕ ПАРАМЕТРЫ");
        LinearLayout.LayoutParams summaryTitleParams = app.matchWrap();
        summaryTitleParams.topMargin = app.dp(24);
        summaryTitleParams.bottomMargin = app.dp(8);
        page.addView(summaryTitle, summaryTitleParams);
        View activeParams = app.paramsCard(activeParamRows());
        LinearLayout.LayoutParams activeParamsParams = app.matchWrap();
        activeParamsParams.bottomMargin = app.dp(8);
        page.addView(activeParams, activeParamsParams);
        app.staggerIn(activeParams, 130);

        exitShareCard = null;
        exitShareShown = null;
        if (MainActivity.MODE_EXIT.equals(app.connectionMode)) {
            exitShareCard = new LinearLayout(app);
            exitShareCard.setOrientation(LinearLayout.VERTICAL);
            exitShareCard.setGravity(Gravity.CENTER_HORIZONTAL);
            exitShareCard.setPadding(app.dp(16), app.dp(14), app.dp(16), app.dp(14));
            exitShareCard.setBackground(app.rounded(app.surface, app.border, 1, ThemedActivity.TILE_RADIUS));
            exitShareCard.setVisibility(View.GONE);
            LinearLayout.LayoutParams shareParams = app.matchWrap();
            shareParams.topMargin = app.dp(16);
            shareParams.bottomMargin = app.dp(8);
            page.addView(exitShareCard, shareParams);
            refreshExitShareCard();
        }
        return app.wrapScroll(page);
    }

    // Shows the QR (link and image both come from the core) while the exit
    // runs; rebuilt only when the link changes, e.g. a new Wi-Fi address.
    private void refreshExitShareCard() {
        if (exitShareCard == null) return;
        String link = null;
        String error = null;
        if (OpenFluxExitService.isRunning()) {
            String ip = app.getLocalIpAddress();
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
        exitShareCard.addView(app.text("ПОДКЛЮЧЕНИЕ ПО QR", 11, app.secondary, true));
        if (link == null) {
            TextView problem = app.text("QR недоступен: " + error, 13, app.text, false);
            LinearLayout.LayoutParams problemParams = app.matchWrap();
            problemParams.topMargin = app.dp(6);
            exitShareCard.addView(problem, problemParams);
        } else {
            try {
                byte[] png = Mobile.shareQRPNG(link, app.dp(220));
                ImageView qrView = new ImageView(app);
                qrView.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length));
                qrView.setBackground(app.rounded(Color.WHITE, Color.WHITE, 0, 12));
                qrView.setPadding(app.dp(6), app.dp(6), app.dp(6), app.dp(6));
                LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(app.dp(232), app.dp(232));
                qrParams.topMargin = app.dp(10);
                exitShareCard.addView(qrView, qrParams);
            } catch (Exception e) {
                exitShareCard.addView(app.text("QR недоступен: " + e.getMessage(), 13, app.text, false), app.matchWrap());
            }
            TextView hint = app.text("Отсканируйте в OpenFlux на другом телефоне: Профили, кнопка QR. "
                    + "В коде ключ шифрования, показывайте только своим.", 12, app.secondary, false);
            hint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hintParams = app.matchWrap();
            hintParams.topMargin = app.dp(10);
            exitShareCard.addView(hint, hintParams);
            Button copyButton = new Button(app);
            copyButton.setText("Копировать ссылку");
            copyButton.setAllCaps(false);
            copyButton.setTextColor(app.accent);
            copyButton.setTextSize(13);
            copyButton.setStateListAnimator(null);
            copyButton.setBackground(app.ripple(Color.TRANSPARENT, 9));
            String copied = link;
            copyButton.setOnClickListener(v -> {
                app.bounce(v);
                ClipboardManager clipboard = (ClipboardManager) app.getSystemService(app.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlux", copied));
                Toast.makeText(app, "Ссылка скопирована", Toast.LENGTH_SHORT).show();
            });
            exitShareCard.addView(copyButton, new LinearLayout.LayoutParams(-1, app.dp(40)));
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
        if (MainActivity.MODE_EXIT.equals(app.connectionMode)) {
            return new String[][]{
                    {"Режим", "Выходная нода (L4)"},
                    {"Выход в интернет", "С IP этого телефона"},
                    {"IP в локальной сети", app.getLocalIpAddress() != null ? app.getLocalIpAddress() : "не определён"},
            };
        }
        if (MainActivity.MODE_PROXY.equals(app.connectionMode)) {
            return new String[][]{
                    {"Режим", "Прокси (SOCKS5)"},
                    {"Локальный порт", String.valueOf(app.proxyPort)},
                    {"Доступ", proxyAccessSummary()},
            };
        }
        Profile p = app.selectedProfile();
        if (p != null && p.stream) {
            return new String[][]{
                    {"Режим", "Туннель без сервера (PHP-нода)"},
                    {"Через ноду", "Только TCP 80 и 443"},
                    {"Приложения", appFilterSummary()},
            };
        }
        return new String[][]{
                {"Режим", "Туннель (весь трафик)"},
                {"DNS-сервер", app.dnsServer.isEmpty() ? "По умолчанию (" + DnsServer.DEFAULT + ")" : app.dnsServer},
                {"MTU пакета", String.valueOf(app.mtu)},
                {"Приложения", appFilterSummary()},
        };
    }

    private String proxyAccessSummary() {
        if (!app.proxyLanAccess) return "Только это устройство";
        String localIp = app.getLocalIpAddress();
        String address = localIp != null ? localIp + ":" + app.proxyPort : "IP не определён";
        return address + (app.proxyAuthEnabled ? " (с паролем)" : " (без пароля)");
    }

    private String appFilterSummary() {
        if (AppFilter.MODE_WHITELIST.equals(app.appFilterMode)) return "Белый список (" + app.selectedApps.size() + ")";
        if (AppFilter.MODE_BLACKLIST.equals(app.appFilterMode)) return "Чёрный список (" + app.selectedApps.size() + ")";
        return "Все";
    }

    void updateStatus() {
        if (tunnelButtonText == null || tunnelButton == null) return;
        String state = app.connectionStatus();
        // Text sits on the button's own fill color, not the page background,
        // so it stays a fixed white for contrast rather than status-colored.
        tunnelButtonText.setText(state);

        long connectedAt = app.connectionStartedAt();
        if (connectedAt == 0L) {
            // GONE, not just empty text: an empty-but-present line still
            // reserves its height, which pushes the icon+status above dead
            // center in the button while there's no time to show yet.
            uptimeView.setVisibility(View.GONE);
            speedView.setVisibility(View.GONE);
        } else {
            uptimeView.setVisibility(View.VISIBLE);
            uptimeView.setText(formatUptime(System.currentTimeMillis() - connectedAt));
            long sentPerSec = app.isExitMode() ? OpenFluxExitService.getSentPerSec()
                    : app.isProxyMode() ? OpenFluxProxyService.getSentPerSec() : OpenFluxTunnelService.getSentPerSec();
            long receivedPerSec = app.isExitMode() ? OpenFluxExitService.getReceivedPerSec()
                    : app.isProxyMode() ? OpenFluxProxyService.getReceivedPerSec() : OpenFluxTunnelService.getReceivedPerSec();
            speedView.setVisibility(View.VISIBLE);
            Profile p = app.selectedProfile();
            String carriers = Profile.carriersLabel(Mobile.currentTransports(),
                    p != null && p.stream ? p.transportType : "");
            speedView.setText("↑ " + OpenFluxTunnelService.formatSpeed(sentPerSec)
                    + "   ↓ " + OpenFluxTunnelService.formatSpeed(receivedPerSec)
                    + (carriers.isEmpty() ? "" : "\n" + carriers));
        }

        if (state != null && !state.equals(lastAnnouncedState)) {
            if ("Подключено".equals(state) && app.isExitMode() && lastAnnouncedState != null
                    && lastAnnouncedState.startsWith("Ожидание")) {
                app.vibrateSuccess();
                app.appendLog("[SUCCESS] Клиент подключился к выходной ноде");
            } else if ("Подключено".equals(state)) {
                app.vibrateSuccess();
                app.appendLog("[SUCCESS] Подключено (" + MainActivity.modeLabel(app.connectionMode) + ")");
            } else if ("Ошибка".equals(state)) {
                app.vibrateError();
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
            tunnelFill = app.surface;
            tunnelPressed = app.border;
            pulsing = false;
            idle = true;
        }
        animateTunnelButtonFill(tunnelFill, tunnelPressed);
        setRingPulsing(pulsing);

        int contentColor = idle ? app.text : Color.WHITE;
        tunnelButtonText.setTextColor(contentColor);
        uptimeView.setTextColor(contentColor);
        speedView.setTextColor(contentColor);
        if (tunnelPowerIcon != null) {
            tunnelPowerIcon.setImageTintList(ColorStateList.valueOf(contentColor));
        }

        refreshExitShareCard();

        String error = app.connectionLastError();
        if (error != null && !error.isEmpty() && !error.equals(app.lastShownError)) {
            app.lastShownError = error;
            app.appendLog("[ERROR] " + error);
        }
    }

    void setRingPulsing(boolean pulsing) {
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
        if (ringWave != null) ringWave.setBackground(app.rounded(Color.TRANSPARENT, fill, 2, 70));
        if (lastTunnelButtonFill == fill) return;
        int from = lastTunnelButtonFill == -1 ? fill : lastTunnelButtonFill;
        lastTunnelButtonFill = fill;
        ValueAnimator animator = ValueAnimator.ofArgb(from, fill);
        animator.setDuration(260);
        animator.addUpdateListener(a -> tunnelButton.setBackground(app.circleBackground((int) a.getAnimatedValue(), pressed)));
        animator.start();
    }

    // Static decorative ring around the big connect button - a thin stroke
    // circle, no fill. A single large corner radius renders as a perfect
    // circle regardless of the view's own size (GradientDrawable clips
    // excess radius), so the same drawable works for both ring sizes.
    private GradientDrawable ringOutline() {
        return app.rounded(Color.TRANSPARENT, app.border, 1, 100);
    }
}
