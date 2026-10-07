package io.openflux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.openflux.bridge.mobile.Mobile;

// Runs the local SOCKS5 proxy (Mobile.startProxy/stopProxy) as a plain
// foreground Service, not a VpnService: unlike OpenFluxTunnelService, this
// mode never requests Android's system-wide tunnel permission, so it needs
// no consent dialog and doesn't show the status-bar key icon. Other apps
// must be pointed at the local SOCKS5 address manually.
public final class OpenFluxProxyService extends Service {
    public static final String ACTION_START = "io.openflux.app.PROXY_START";
    public static final String ACTION_STOP = "io.openflux.app.PROXY_STOP";
    public static final String EXTRA_DOCUMENT_URL = "document_url";
    public static final String EXTRA_ENCRYPTION_SECRET = "encryption_secret";
    public static final String EXTRA_TRANSPORT_TYPE = "transport_type";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_LAN_ACCESS = "lan_access";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_CODEC = "codec";
    public static final String EXTRA_MAX_TOKEN = "max_token";
    public static final String EXTRA_MAX_UID = "max_uid";
    public static final String EXTRA_SESSION_TRANSPORTS = "session_transports";

    private static final String CHANNEL_ID = "openflux_proxy";
    private static final int NOTIFICATION_ID = 8;
    private static volatile boolean running;
    private static volatile String status = "Остановлено";
    private static volatile String lastError = "";
    private static volatile int activePort;
    // Lives on the service, not the Activity: MainActivity can be destroyed
    // and recreated (low memory, long time away) while this foreground
    // service keeps running, and the uptime shown on Home must survive that.
    private static volatile long connectedAtMillis;

    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean awaitingCaptcha = new AtomicBoolean();
    // Session mode (Mobile.startSession): the profile's transport list as
    // JSON, or empty for the classic single-transport mode.
    private volatile String sessionTransports = "";
    // Stream mode (Mobile.startStreamProxy, the PHP exit): its transport
    // type, or empty for the other modes.
    private volatile String streamType = "";

    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    private long lastSampledSent;
    private long lastSampledReceived;
    private long lastSampledAt;
    // Read by MainActivity's Home screen (getSentPerSec/getReceivedPerSec)
    // to show live speed there too, not just in the notification.
    private static volatile long sentPerSec;
    private static volatile long receivedPerSec;
    private final Runnable speedUpdater = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            long elapsedMs = Math.max(1, now - lastSampledAt);
            long sent = Mobile.proxyBytesSent();
            long received = Mobile.proxyBytesReceived();
            sentPerSec = (sent - lastSampledSent) * 1000 / elapsedMs;
            receivedPerSec = (received - lastSampledReceived) * 1000 / elapsedMs;
            lastSampledSent = sent;
            lastSampledReceived = received;
            lastSampledAt = now;
            String speeds = "↑ " + formatSpeed(sentPerSec) + "   ↓ " + formatSpeed(receivedPerSec);
            // The carrier traffic goes through right now; follows failover.
            String carrier = Profile.carriersLabel(Mobile.currentTransports(), streamType);
            updateNotification(carrier.isEmpty() ? speeds : speeds + " · " + carrier);
            notificationHandler.postDelayed(this, 1000);
        }
    };

    // 0 means "currently connected" - see OpenFluxTunnelService's identical
    // field for why, and for the Kill Switch off grace-period watchdog this
    // feeds below.
    private volatile long disconnectedSinceMs;
    private static final long KILL_SWITCH_GRACE_MS = 30_000;

    // healthChecker keeps "Подключено" honest for the same reason as in
    // OpenFluxTunnelService: once set at initial connect, status would never
    // reflect a later drop in the underlying transport without this. Also
    // implements the same Kill Switch off opt-out: stop the local proxy
    // after a prolonged drop instead of leaving it up (and refusing/hanging
    // new connections) indefinitely.
    private final Runnable healthChecker = new Runnable() {
        @Override public void run() {
            if (running && !Mobile.pendingCaptchaURL().isEmpty() && awaitingCaptcha.compareAndSet(false, true)) {
                // Captcha hit on a mid-session reconnect, not during the initial connect.
                int session = generation.get();
                new Thread(() -> {
                    try { awaitCaptcha(session); }
                    finally { awaitingCaptcha.set(false); }
                }).start();
            }
            if (running) {
                boolean connected = Mobile.proxyIsConnected();
                if (!connected) {
                    long now = SystemClock.elapsedRealtime();
                    if (disconnectedSinceMs == 0L) disconnectedSinceMs = now;
                    boolean killSwitch = getSharedPreferences(MainActivity.SETTINGS_PREFS_NAME, MODE_PRIVATE)
                            .getBoolean("kill_switch", true);
                    if (!killSwitch && now - disconnectedSinceMs >= KILL_SWITCH_GRACE_MS) {
                        lastError = "Не удалось переподключиться - прокси остановлен, Kill Switch выключен";
                        stopProxy();
                        return;
                    }
                    if ("Подключено".equals(status)) {
                        status = "Подключение…";
                    }
                    lastError = killSwitch
                            ? "Kill Switch: новые соединения блокируются, переподключение…"
                            : "Транспорт отключился, переподключение…";
                } else {
                    disconnectedSinceMs = 0L;
                    if ("Подключение…".equals(status)) {
                        status = "Подключено";
                        lastError = "Транспорт восстановлен";
                    }
                }
            }
            notificationHandler.postDelayed(this, 2000);
        }
    };

    public static boolean isRunning() { return running; }
    public static String getStatus() { return status; }
    public static String getLastError() { return lastError; }
    public static int getActivePort() { return activePort; }
    public static long getConnectedAtMillis() { return connectedAtMillis; }
    public static long getSentPerSec() { return sentPerSec; }
    public static long getReceivedPerSec() { return receivedPerSec; }

    private static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " Б/с";
        if (bytesPerSecond < 1024 * 1024) return String.format(Locale.US, "%.0f КБ/с", bytesPerSecond / 1024.0);
        return String.format(Locale.US, "%.1f МБ/с", bytesPerSecond / (1024.0 * 1024.0));
    }

    private void startSpeedUpdates() {
        lastSampledSent = 0;
        lastSampledReceived = 0;
        lastSampledAt = SystemClock.elapsedRealtime();
        disconnectedSinceMs = 0L;
        notificationHandler.removeCallbacks(speedUpdater);
        notificationHandler.post(speedUpdater);
        notificationHandler.removeCallbacks(healthChecker);
        notificationHandler.postDelayed(healthChecker, 2000);
    }

    private void stopSpeedUpdates() {
        notificationHandler.removeCallbacks(speedUpdater);
        notificationHandler.removeCallbacks(healthChecker);
        sentPerSec = 0;
        receivedPerSec = 0;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopProxy();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;

        // A foreground service started via startForegroundService() must call
        // startForeground() right away - any early stopSelf() before that
        // (e.g. on a validation error below) would otherwise crash the app
        // with ForegroundServiceDidNotStartInTimeException.
        createNotificationChannel();
        shownNotification = "Подключение…";
        startForeground(NOTIFICATION_ID, notification("Подключение…"));

        String transportTypeExtra = intent == null ? null : intent.getStringExtra(EXTRA_TRANSPORT_TYPE);
        final String transportType = transportTypeExtra == null || transportTypeExtra.isEmpty()
                ? "yandex" : transportTypeExtra;
        String sessionExtra = intent == null ? null : intent.getStringExtra(EXTRA_SESSION_TRANSPORTS);
        sessionTransports = sessionExtra == null ? "" : sessionExtra;
        boolean stream = intent != null && intent.getBooleanExtra(OpenFluxTunnelService.EXTRA_STREAM, false);
        streamType = stream ? transportType : "";
        // MAX takes a token; Cups.online a base64 room code, checked by the core.
        boolean needsUrl = !"oneme".equals(transportType) && !"cupsonline".equals(transportType)
                && sessionTransports.isEmpty();

        String url = intent == null ? null : intent.getStringExtra(EXTRA_DOCUMENT_URL);
        if (needsUrl && (url == null || !url.startsWith("https://"))) {
            lastError = "Некорректная ссылка на документ";
            status = "Ошибка";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (url == null) url = "";
        String encryptionSecretExtra = intent.getStringExtra(EXTRA_ENCRYPTION_SECRET);
        final String encryptionSecret = encryptionSecretExtra == null ? "" : encryptionSecretExtra;
        if (!encryptionSecret.isEmpty() && encryptionSecret.length() < 16) {
            lastError = "Ключ шифрования должен быть не короче 16 символов, либо оставьте поле пустым";
            status = "Ошибка";
            stopSelf();
            return START_NOT_STICKY;
        }
        String codecExtra = intent.getStringExtra(EXTRA_CODEC);
        final String codec = codecExtra == null || codecExtra.isEmpty() ? "batched" : codecExtra;
        String maxTokenExtra = intent.getStringExtra(EXTRA_MAX_TOKEN);
        final String maxToken = maxTokenExtra == null ? "" : maxTokenExtra;
        String maxUidExtra = intent.getStringExtra(EXTRA_MAX_UID);
        final String maxUid = maxUidExtra == null ? "" : maxUidExtra;
        int port = intent.getIntExtra(EXTRA_PORT, 1080);
        boolean lanAccess = intent.getBooleanExtra(EXTRA_LAN_ACCESS, false);
        String username = intent.getStringExtra(EXTRA_USERNAME);
        String password = intent.getStringExtra(EXTRA_PASSWORD);
        if (username == null) username = "";
        if (password == null) password = "";
        String bindHost = lanAccess ? "0.0.0.0" : "127.0.0.1";

        running = true;
        status = "Подключение…";
        lastError = "";
        activePort = port;
        int session = generation.incrementAndGet();
        String selectedUser = username;
        String selectedPassword = password;
        String finalUrl = url;
        workers.execute(() -> startProxyTransport(transportType, finalUrl, encryptionSecret, codec, maxToken, maxUid, bindHost, port,
                selectedUser, selectedPassword, session));
        return START_STICKY;
    }

    // Newline-joined domain list for the enabled "Маршрутизация" presets +
    // custom domains, passed to the bridge's split dialer. Read fresh on
    // every start so a settings change takes effect on the next connect.
    private String resolveBypassDomains() {
        SharedPreferences prefs = getSharedPreferences(DomainFilter.PREFS_NAME, MODE_PRIVATE);
        Set<String> presets = DomainFilter.loadEnabledPresets(prefs);
        Set<String> custom = DomainFilter.loadCustomDomains(prefs);
        return String.join("\n", DomainFilter.resolveDomains(this, presets, custom));
    }

    private String startCarrier(String transportType, String url, String encryptionSecret, String codec, String maxToken, String maxUid,
            String listen, String username, String password, String bypassDomains) {
        if (!streamType.isEmpty()) return Mobile.startStreamProxy(streamType, url, listen, username, password, bypassDomains);
        String specs = sessionTransports;
        return specs.isEmpty()
                ? Mobile.startProxy(transportType, url, encryptionSecret, codec, maxToken, maxUid, listen, username, password, bypassDomains)
                : Mobile.startSessionProxy(specs, encryptionSecret, listen, username, password, bypassDomains);
    }

    private void startProxyTransport(String transportType, String url, String encryptionSecret, String codec, String maxToken, String maxUid, String bindHost, int port,
            String username, String password, int session) {
        if (!isCurrent(session)) return;
        CaptchaActivity.initCookieStore(this);
        String listen = bindHost + ":" + port;
        String bypass = resolveBypassDomains();
        String error = startCarrier(transportType, url, encryptionSecret, codec, maxToken, maxUid, listen, username, password, bypass);
        // Some transports (Volga) fail Start outright on a captcha; retry
        // with the solved cookies, which the next Start replays.
        while (error != null && !error.isEmpty() && awaitCaptcha(session)) {
            error = startCarrier(transportType, url, encryptionSecret, codec, maxToken, maxUid, listen, username, password, bypass);
        }
        if (error != null && !error.isEmpty()) {
            fail(session, error);
            return;
        }

        for (int attempt = 0; isCurrent(session) && !Mobile.proxyIsConnected() && attempt < 120; attempt++) {
            // Only the phone's own checks block connecting; the node's are
            // handled by the health checker once the tunnel is up.
            if (!Mobile.pendingCaptchaURL().isEmpty() && Mobile.pendingCaptchaProxy().isEmpty()) {
                if (!awaitCaptcha(session)) {
                    if (isCurrent(session)) fail(session, "Проверка Яндекса не пройдена");
                    return;
                }
                attempt = 0;
            }
            try { Thread.sleep(250); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!isCurrent(session)) return;
        if (!Mobile.proxyIsConnected()) {
            fail(session, "Транспорт не подключился за 30 секунд");
            return;
        }

        status = "Подключено";
        if (connectedAtMillis == 0L) connectedAtMillis = System.currentTimeMillis();
        startSpeedUpdates();
    }

    private boolean awaitCaptcha(int session) {
        if (Mobile.pendingCaptchaURL().isEmpty()) return false;
        if (!Mobile.pendingCaptchaProxy().isEmpty()) {
            // The node's own document carrier is stuck; the tunnel itself
            // keeps working over another transport, so the status stays.
            lastError = "Нода просит пройти проверку Яндекса - откройте уведомление";
            boolean solved = CaptchaActivity.awaitIfPending(this, () -> isCurrent(session));
            lastError = solved ? "Cookies отправлены на ноду" : "";
            return solved;
        }
        status = "Нужна проверка";
        lastError = "Яндекс запросил проверку - откройте уведомление";
        boolean solved = CaptchaActivity.awaitIfPending(this, () -> isCurrent(session));
        if (solved) {
            status = "Подключение…";
            lastError = "";
        }
        return solved;
    }

    private boolean isCurrent(int session) {
        return running && generation.get() == session;
    }

    private synchronized void fail(int session, String message) {
        if (generation.get() != session) return;
        lastError = message == null ? "Неизвестная ошибка" : message;
        status = "Ошибка";
        connectedAtMillis = 0L;
        generation.incrementAndGet();
        stopSpeedUpdates();
        Mobile.stopProxy();
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private synchronized void stopProxy() {
        status = "Останавливается…";
        generation.incrementAndGet();
        stopSpeedUpdates();
        Mobile.stopProxy();
        running = false;
        status = "Остановлено";
        lastError = "";
        connectedAtMillis = 0L;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        generation.incrementAndGet();
        stopSpeedUpdates();
        Mobile.stopProxy();
        running = false;
        if (!"Ошибка".equals(status)) status = "Остановлено";
        connectedAtMillis = 0L;
        workers.shutdownNow();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "OpenFlux Прокси", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, OpenFluxProxyService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(
                this, 0, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OpenFlux")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_openflux_notification)
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(R.drawable.ic_power, "Отключить", stopIntent)
                .build();
    }

    // Re-posting an unchanged notification every second still wakes
    // SystemUI to redraw it; idle speeds stay the same text.
    private String shownNotification = "";

    private void updateNotification(String text) {
        if (text.equals(shownNotification)) return;
        shownNotification = text;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }
}
