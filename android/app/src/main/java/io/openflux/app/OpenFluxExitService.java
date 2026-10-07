package io.openflux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.openflux.bridge.mobile.Mobile;

// Runs the phone as an l4 exit node (Mobile.startExit / startSessionExit),
// the CLI's --role=exit --mode=l4: clients reach it through the profile's
// transport and their traffic leaves from this phone's network through a
// userspace gVisor stack, no root needed. A partial wake lock keeps it
// serving while the screen is off.
public final class OpenFluxExitService extends Service {
    public static final String ACTION_START = "io.openflux.app.EXIT_START";
    public static final String ACTION_STOP = "io.openflux.app.EXIT_STOP";

    private static final String CHANNEL_ID = "openflux_exit";
    private static final int NOTIFICATION_ID = 10;
    private static final String WAITING = "Ожидание клиента";
    private static volatile boolean running;
    private static volatile String status = "Остановлено";
    private static volatile String lastError = "";
    private static volatile long connectedAtMillis;

    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean awaitingCaptcha = new AtomicBoolean();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private volatile String sessionTransports = "";

    private long lastSent;
    private long lastReceived;
    private long lastSampledAt;
    // Read by MainActivity's Home screen, like the other services' speeds.
    private static volatile long sentPerSec;
    private static volatile long receivedPerSec;
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!Mobile.pendingCaptchaURL().isEmpty() && awaitingCaptcha.compareAndSet(false, true)) {
                int session = generation.get();
                new Thread(() -> {
                    try { awaitCaptcha(session); }
                    finally { awaitingCaptcha.set(false); }
                }).start();
            }
            if (!"Нужна проверка".equals(status)) {
                status = Mobile.exitIsConnected() ? "Подключено" : WAITING;
            }
            long now = SystemClock.elapsedRealtime();
            long elapsedMs = Math.max(1, now - lastSampledAt);
            long sent = Mobile.exitBytesSent();
            long received = Mobile.exitBytesReceived();
            sentPerSec = (sent - lastSent) * 1000 / elapsedMs;
            receivedPerSec = (received - lastReceived) * 1000 / elapsedMs;
            String speeds = "↑ " + formatSpeed(sentPerSec) + "   ↓ " + formatSpeed(receivedPerSec);
            lastSent = sent;
            lastReceived = received;
            lastSampledAt = now;
            String carrier = Profile.carriersLabel(Mobile.currentTransports(), "");
            String text = WAITING.equals(status) ? WAITING : speeds;
            updateNotification(carrier.isEmpty() ? text : text + " · " + carrier);
            handler.postDelayed(this, 1000);
        }
    };

    public static boolean isRunning() { return running; }
    public static String getStatus() { return status; }
    public static String getLastError() { return lastError; }
    public static long getConnectedAtMillis() { return connectedAtMillis; }
    public static long getSentPerSec() { return sentPerSec; }
    public static long getReceivedPerSec() { return receivedPerSec; }

    private static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " Б/с";
        if (bytesPerSecond < 1024 * 1024) return String.format(Locale.US, "%.0f КБ/с", bytesPerSecond / 1024.0);
        return String.format(Locale.US, "%.1f МБ/с", bytesPerSecond / (1024.0 * 1024.0));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopExit();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;

        // startForeground must come first; an early stopSelf() before it
        // would crash with ForegroundServiceDidNotStartInTimeException.
        createNotificationChannel();
        shownNotification = "Запуск выходной ноды…";
        startForeground(NOTIFICATION_ID, notification("Запуск выходной ноды…"));

        String typeExtra = intent == null ? null : intent.getStringExtra(OpenFluxTunnelService.EXTRA_TRANSPORT_TYPE);
        String transportType = typeExtra == null || typeExtra.isEmpty() ? "yandex" : typeExtra;
        String sessionExtra = intent == null ? null : intent.getStringExtra(OpenFluxTunnelService.EXTRA_SESSION_TRANSPORTS);
        sessionTransports = sessionExtra == null ? "" : sessionExtra;
        if (intent != null && intent.getBooleanExtra(OpenFluxTunnelService.EXTRA_STREAM, false)) {
            failEarly("Профиль без сервера не работает выходной нодой: выход в нём - PHP-нода на хостинге");
            return START_NOT_STICKY;
        }
        String url = intent == null ? null : intent.getStringExtra(OpenFluxTunnelService.EXTRA_DOCUMENT_URL);
        // MAX takes a token; a Cups.online exit creates its rooms itself.
        if (!"oneme".equals(transportType) && !"cupsonline".equals(transportType) && sessionTransports.isEmpty()
                && (url == null || !url.startsWith("https://"))) {
            failEarly("Некорректная ссылка на документ");
            return START_NOT_STICKY;
        }
        String secret = extra(intent, OpenFluxTunnelService.EXTRA_ENCRYPTION_SECRET, "");
        if (!secret.isEmpty() && secret.length() < 16) {
            failEarly("Ключ шифрования должен быть не короче 16 символов, либо оставьте поле пустым");
            return START_NOT_STICKY;
        }
        String codec = extra(intent, OpenFluxTunnelService.EXTRA_CODEC, "batched");
        String maxToken = extra(intent, OpenFluxTunnelService.EXTRA_MAX_TOKEN, "");
        String maxUid = extra(intent, OpenFluxTunnelService.EXTRA_MAX_UID, "");
        String documentUrl = url == null ? "" : url;

        running = true;
        status = "Подключение…";
        lastError = "";
        int session = generation.incrementAndGet();
        workers.execute(() -> startNode(transportType, documentUrl, secret, codec, maxToken, maxUid, session));
        return START_STICKY;
    }

    private static String extra(Intent intent, String key, String fallback) {
        String value = intent == null ? null : intent.getStringExtra(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private String startCarrier(String transportType, String url, String secret, String codec, String maxToken, String maxUid) {
        String specs = sessionTransports;
        return specs.isEmpty()
                ? Mobile.startExit(transportType, url, secret, codec, maxToken, maxUid)
                : Mobile.startSessionExit(specs, secret);
    }

    private void startNode(String transportType, String url, String secret, String codec, String maxToken, String maxUid, int session) {
        if (!isCurrent(session)) return;
        CaptchaActivity.initCookieStore(this);
        String error = startCarrier(transportType, url, secret, codec, maxToken, maxUid);
        // Some transports (Volga) fail Start outright on a captcha; retry
        // with the solved cookies, which the next Start replays.
        while (error != null && !error.isEmpty() && awaitCaptcha(session)) {
            error = startCarrier(transportType, url, secret, codec, maxToken, maxUid);
        }
        if (error != null && !error.isEmpty()) {
            fail(session, error);
            return;
        }
        acquireWakeLock();
        status = WAITING;
        connectedAtMillis = System.currentTimeMillis();
        lastSent = 0;
        lastReceived = 0;
        lastSampledAt = SystemClock.elapsedRealtime();
        handler.post(monitor);
    }

    private boolean awaitCaptcha(int session) {
        if (Mobile.pendingCaptchaURL().isEmpty()) return false;
        String before = status;
        status = "Нужна проверка";
        lastError = "Яндекс запросил проверку - откройте уведомление";
        boolean solved = CaptchaActivity.awaitIfPending(this, () -> isCurrent(session));
        status = before;
        lastError = "";
        return solved;
    }

    private void acquireWakeLock() {
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenFlux:exit");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private boolean isCurrent(int session) {
        return running && generation.get() == session;
    }

    private void failEarly(String message) {
        lastError = message;
        status = "Ошибка";
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private synchronized void fail(int session, String message) {
        if (generation.get() != session) return;
        lastError = message == null ? "Неизвестная ошибка" : message;
        shutdown();
        status = "Ошибка";
    }

    private synchronized void stopExit() {
        status = "Останавливается…";
        shutdown();
        status = "Остановлено";
        lastError = "";
    }

    private void shutdown() {
        generation.incrementAndGet();
        handler.removeCallbacks(monitor);
        sentPerSec = 0;
        receivedPerSec = 0;
        Mobile.stopExit();
        releaseWakeLock();
        running = false;
        connectedAtMillis = 0L;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        generation.incrementAndGet();
        handler.removeCallbacks(monitor);
        Mobile.stopExit();
        releaseWakeLock();
        running = false;
        if (!"Ошибка".equals(status)) status = "Остановлено";
        connectedAtMillis = 0L;
        workers.shutdownNow();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "OpenFlux Выходная нода", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String text) {
        PendingIntent content = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OpenFluxExitService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OpenFlux · выходная нода")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_openflux_notification)
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(R.drawable.ic_power, "Остановить", stop)
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
