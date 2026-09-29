package io.openflux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.openflux.bridge.mobile.Mobile;

public final class OpenFluxTunnelService extends VpnService {
    public static final String ACTION_START = "io.openflux.app.START";
    public static final String ACTION_STOP = "io.openflux.app.STOP";
    public static final String EXTRA_DOCUMENT_URL = "document_url";
    public static final String EXTRA_ENCRYPTION_SECRET = "encryption_secret";
    public static final String EXTRA_TRANSPORT_TYPE = "transport_type";
    public static final String EXTRA_DNS_SERVER = "dns_server";
    public static final String EXTRA_MTU = "mtu";
    public static final String EXTRA_CODEC = "codec";
    public static final String EXTRA_MAX_TOKEN = "max_token";
    public static final String EXTRA_MAX_UID = "max_uid";
    public static final String EXTRA_SESSION_TRANSPORTS = "session_transports";

    private static final String CHANNEL_ID = "openflux_tunnel";
    private static final int NOTIFICATION_ID = 7;
    private static volatile boolean running;
    private static volatile String status = "Остановлено";
    private static volatile String lastError = "";
    // Lives on the service, not the Activity: MainActivity can be destroyed
    // and recreated (low memory, long time away) while this foreground
    // service keeps running, and the uptime shown on Home must survive that.
    private static volatile long connectedAtMillis;

    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final Object outputLock = new Object();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean awaitingCaptcha = new AtomicBoolean();
    // Session mode (Mobile.startSession): the profile's transport list as
    // JSON, or empty for the classic single-transport mode.
    private volatile String sessionTransports = "";
    private volatile boolean active;
    private ParcelFileDescriptor tunnel;
    private FileInputStream tunnelInput;
    private FileOutputStream tunnelOutput;

    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    // 0 means "currently connected" - set the first time healthChecker
    // observes a drop, used to measure how long we've been down for the
    // Kill Switch off opt-out watchdog below.
    private volatile long disconnectedSinceMs;
    private static final long KILL_SWITCH_GRACE_MS = 30_000;
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
            long sent = bytesSent.get();
            long received = bytesReceived.get();
            sentPerSec = (sent - lastSampledSent) * 1000 / elapsedMs;
            receivedPerSec = (received - lastSampledReceived) * 1000 / elapsedMs;
            lastSampledSent = sent;
            lastSampledReceived = received;
            lastSampledAt = now;
            String speeds = "↑ " + formatSpeed(sentPerSec) + "   ↓ " + formatSpeed(receivedPerSec);
            // The carrier traffic goes through right now; follows failover.
            String carrier = Mobile.currentTransport();
            updateNotification(carrier.isEmpty() ? speeds : speeds + " · " + Profile.transportLabel(carrier));
            notificationHandler.postDelayed(this, 1000);
        }
    };

    // healthChecker keeps "Подключено" honest: without it, status is set
    // once on initial connect and never revisited, so if the underlying
    // Yandex Docs transport drops and silently retries (it keeps retrying
    // on its own - see transport.DefaultConfig's MaxReconnectAttempts) the
    // UI would keep showing a green "connected" while every packet is
    // actually being dropped (Mobile.send fails silently, so nothing leaks
    // unencrypted - the TUN just stops passing data). This surfaces that
    // state honestly instead of just failing silently.
    //
    // It also implements the Kill Switch opt-out: with it on (default) the
    // above drop-not-leak behavior is simply left alone. With it off, a
    // watchdog here tears the tunnel down after KILL_SWITCH_GRACE_MS of
    // continuous disconnection, restoring normal device routing instead of
    // blocking network access indefinitely.
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
                boolean connected = Mobile.isConnected();
                if (!connected) {
                    long now = SystemClock.elapsedRealtime();
                    if (disconnectedSinceMs == 0L) disconnectedSinceMs = now;
                    boolean killSwitch = getSharedPreferences(MainActivity.SETTINGS_PREFS_NAME, MODE_PRIVATE)
                            .getBoolean("kill_switch", true);
                    if (!killSwitch && now - disconnectedSinceMs >= KILL_SWITCH_GRACE_MS) {
                        lastError = "Не удалось переподключиться - туннель отключён, Kill Switch выключен";
                        stopTunnel();
                        return;
                    }
                    if ("Подключено".equals(status)) {
                        status = "Подключение…";
                    }
                    lastError = killSwitch
                            ? "Kill Switch: трафик заблокирован, переподключение…"
                            : "Транспорт отключился, переподключение…";
                } else {
                    disconnectedSinceMs = 0L;
                    if ("Подключение…".equals(status) && active) {
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
    public static long getConnectedAtMillis() { return connectedAtMillis; }
    public static long getSentPerSec() { return sentPerSec; }
    public static long getReceivedPerSec() { return receivedPerSec; }

    // Package-private so MainActivity's Home screen can format the same
    // numbers this service already tracks (getSentPerSec/getReceivedPerSec).
    static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " Б/с";
        if (bytesPerSecond < 1024 * 1024) return String.format(Locale.US, "%.0f КБ/с", bytesPerSecond / 1024.0);
        return String.format(Locale.US, "%.1f МБ/с", bytesPerSecond / (1024.0 * 1024.0));
    }

    private void startSpeedUpdates() {
        bytesSent.set(0);
        bytesReceived.set(0);
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

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;

        // A foreground service started via startForegroundService() must call
        // startForeground() right away - any early stopSelf() before that
        // (e.g. on a validation error below) would otherwise crash the app
        // with ForegroundServiceDidNotStartInTimeException.
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("Подключение…"));

        String transportTypeExtra = intent == null ? null : intent.getStringExtra(EXTRA_TRANSPORT_TYPE);
        final String transportType = transportTypeExtra == null || transportTypeExtra.isEmpty()
                ? "yandex" : transportTypeExtra;
        String sessionExtra = intent == null ? null : intent.getStringExtra(EXTRA_SESSION_TRANSPORTS);
        sessionTransports = sessionExtra == null ? "" : sessionExtra;
        // MAX takes a token; Cups.online a base64 room code, checked by the core.
        boolean needsUrl = !"oneme".equals(transportType) && !"cupsonline".equals(transportType)
                && sessionTransports.isEmpty();

        String url = intent == null ? null : intent.getStringExtra(EXTRA_DOCUMENT_URL);
        if (needsUrl && (url == null || !url.startsWith("https://"))) {
            lastError = "Некорректная ссылка на документ";
            status = "Ошибка";
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (url == null) url = "";
        String dnsServer = intent.getStringExtra(EXTRA_DNS_SERVER);
        String encryptionSecretExtra = intent.getStringExtra(EXTRA_ENCRYPTION_SECRET);
        final String encryptionSecret = encryptionSecretExtra == null ? "" : encryptionSecretExtra;
        if (!encryptionSecret.isEmpty() && encryptionSecret.length() < 16) {
            lastError = "Ключ шифрования должен быть не короче 16 символов, либо оставьте поле пустым";
            status = "Ошибка";
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        String codecExtra = intent.getStringExtra(EXTRA_CODEC);
        final String codec = codecExtra == null || codecExtra.isEmpty() ? "batched" : codecExtra;
        String maxTokenExtra = intent.getStringExtra(EXTRA_MAX_TOKEN);
        final String maxToken = maxTokenExtra == null ? "" : maxTokenExtra;
        String maxUidExtra = intent.getStringExtra(EXTRA_MAX_UID);
        final String maxUid = maxUidExtra == null ? "" : maxUidExtra;
        String selectedDns = DnsServer.effective(dnsServer);
        if (selectedDns == null) {
            lastError = "DNS-сервер должен быть IPv4-адресом";
            status = "Ошибка";
            stopSelf();
            return START_NOT_STICKY;
        }
        int mtu = Math.max(576, Math.min(1500, intent.getIntExtra(EXTRA_MTU, 1400)));

        active = true;
        running = true;
        status = "Подключение…";
        lastError = "";
        int session = generation.incrementAndGet();
        int selectedMtu = mtu;
        String finalUrl = url;
        workers.execute(() -> startTunnel(transportType, finalUrl, encryptionSecret, codec, maxToken, maxUid, selectedDns, selectedMtu, session));
        return START_STICKY;
    }

    private String startCarrier(String transportType, String url, String encryptionSecret, String codec, String maxToken, String maxUid) {
        String specs = sessionTransports;
        return specs.isEmpty()
                ? Mobile.start(transportType, url, encryptionSecret, codec, maxToken, maxUid)
                : Mobile.startSession(specs, encryptionSecret);
    }

    private void startTunnel(String transportType, String url, String encryptionSecret, String codec, String maxToken, String maxUid, String dnsServerParam, int mtu, int session) {
        if (!isCurrent(session)) return;
        CaptchaActivity.initCookieStore(this);
        String error = startCarrier(transportType, url, encryptionSecret, codec, maxToken, maxUid);
        // Some transports (Volga) fail Start outright on a captcha; retry
        // with the solved cookies, which the next Start replays.
        while (error != null && !error.isEmpty() && awaitCaptcha(session)) {
            error = startCarrier(transportType, url, encryptionSecret, codec, maxToken, maxUid);
        }
        if (error != null && !error.isEmpty()) {
            fail(session, error);
            return;
        }

        for (int attempt = 0; isCurrent(session) && !Mobile.isConnected() && attempt < 120; attempt++) {
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
        if (!Mobile.isConnected()) {
            fail(session, "Транспорт не подключился за 30 секунд");
            return;
        }

        try {
            Builder builder = new Builder()
                    .setSession("OpenFlux")
                    .setMtu(mtu)
                    .addAddress("10.10.10.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(dnsServerParam);
            applyAppFilter(builder);
            ParcelFileDescriptor established = builder.establish();
            if (established == null) throw new IOException("Android не создал TUN-интерфейс");
            synchronized (outputLock) {
                if (!isCurrent(session)) {
                    established.close();
                    return;
                }
                tunnel = established;
                tunnelInput = new FileInputStream(established.getFileDescriptor());
                tunnelOutput = new FileOutputStream(established.getFileDescriptor());
            }
        } catch (IOException | IllegalArgumentException exception) {
            fail(session, exception.getMessage());
            return;
        }

        if (!isCurrent(session)) return;
        status = "Подключено";
        if (connectedAtMillis == 0L) connectedAtMillis = System.currentTimeMillis();
        startSpeedUpdates();
        FileInputStream input = tunnelInput;
        FileOutputStream output = tunnelOutput;
        workers.execute(() -> readOutgoingPackets(session, input));
        workers.execute(() -> writeIncomingPackets(session, output));
    }

    // applyAppFilter routes traffic per the user's "Приложения" settings tab:
    // whitelist mode tunnels only the selected apps, blacklist mode tunnels
    // everything except the selected apps, and "off" tunnels everything.
    // Android forbids calling both addAllowedApplication and
    // addDisallowedApplication on the same Builder, so the two modes are
    // mutually exclusive branches below. Our own package must never enter
    // the tunnel: Go opens the Yandex connection inside this process, so
    // routing our own traffic through TUN would loop it back on itself.
    private void applyAppFilter(Builder builder) {
        SharedPreferences prefs = getSharedPreferences(AppFilter.PREFS_NAME, MODE_PRIVATE);
        String mode = prefs.getString(AppFilter.KEY_MODE, AppFilter.MODE_OFF);
        Set<String> packages = prefs.getStringSet(AppFilter.KEY_PACKAGES, Collections.emptySet());

        if (AppFilter.MODE_WHITELIST.equals(mode) && !packages.isEmpty()) {
            for (String packageName : packages) {
                if (packageName.equals(getPackageName())) continue;
                try {
                    builder.addAllowedApplication(packageName);
                } catch (PackageManager.NameNotFoundException ignored) {
                    // App was uninstalled since it was selected; skip it.
                }
            }
            return;
        }

        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException neverThrown) {
            // We are this package; it always exists.
            throw new AssertionError(neverThrown);
        }
        if (AppFilter.MODE_BLACKLIST.equals(mode)) {
            for (String packageName : packages) {
                if (packageName.equals(getPackageName())) continue;
                try {
                    builder.addDisallowedApplication(packageName);
                } catch (PackageManager.NameNotFoundException ignored) {
                    // App was uninstalled since it was selected; skip it.
                }
            }
        }
    }

    private void readOutgoingPackets(int session, FileInputStream input) {
        byte[] buffer = new byte[32767];
        try {
            while (isCurrent(session)) {
                int length = input.read(buffer);
                if (length <= 0) continue;
                byte[] packet = Arrays.copyOf(buffer, length);
                if (isIpv4Tcp(packet) || isIpv4Udp(packet)) {
                    String error = Mobile.send(packet);
                    if (error != null && !error.isEmpty() && isCurrent(session)) {
                        lastError = "Отправка пакета: " + error;
                    } else {
                        bytesSent.addAndGet(packet.length);
                    }
                }
            }
        } catch (IOException exception) {
            if (isCurrent(session)) fail(session, "Чтение TUN: " + exception.getMessage());
        }
    }

    private void writeIncomingPackets(int session, FileOutputStream output) {
        try {
            while (isCurrent(session)) {
                byte[] packet = Mobile.read();
                if (packet == null || packet.length == 0) {
                    Thread.sleep(2);
                    continue;
                }
                inject(session, output, packet);
                bytesReceived.addAndGet(packet.length);
            }
        } catch (IOException exception) {
            if (isCurrent(session)) fail(session, "Запись TUN: " + exception.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void inject(int session, FileOutputStream output, byte[] packet) throws IOException {
        if (!isCurrent(session) || output == null || packet == null) return;
        synchronized (outputLock) {
            if (isCurrent(session)) output.write(packet);
        }
    }

    private boolean isCurrent(int session) {
        return active && generation.get() == session;
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

    private static boolean isIpv4Tcp(byte[] packet) {
        return packet.length >= 20 && (packet[0] >>> 4) == 4 && (packet[9] & 0xff) == 6;
    }

    // DNS and other IPv4 UDP take the same transport path to the exit node.
    private static boolean isIpv4Udp(byte[] packet) {
        return packet.length >= 20 && (packet[0] >>> 4) == 4 && (packet[9] & 0xff) == 17;
    }

    private synchronized void fail(int session, String message) {
        if (!isCurrent(session)) return;
        lastError = message == null ? "Неизвестная ошибка" : message;
        status = "Ошибка";
        connectedAtMillis = 0L;
        generation.incrementAndGet();
        active = false;
        stopSpeedUpdates();
        closeTunnel();
        Mobile.stop();
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private synchronized void stopTunnel() {
        status = "Останавливается…";
        generation.incrementAndGet();
        active = false;
        stopSpeedUpdates();
        closeTunnel();
        Mobile.stop();
        running = false;
        status = "Остановлено";
        lastError = "";
        connectedAtMillis = 0L;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        generation.incrementAndGet();
        active = false;
        stopSpeedUpdates();
        closeTunnel();
        Mobile.stop();
        running = false;
        if (!"Ошибка".equals(status)) status = "Остановлено";
        connectedAtMillis = 0L;
        workers.shutdownNow();
        super.onDestroy();
    }

    private void closeTunnel() {
        synchronized (outputLock) {
            if (tunnel != null) {
                try { tunnel.close(); } catch (IOException ignored) { }
                tunnel = null;
            }
            tunnelInput = null;
            tunnelOutput = null;
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "OpenFlux Tunnel", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, OpenFluxTunnelService.class).setAction(ACTION_STOP);
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

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }
}
