package io.openflux.app;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

// The version badge in the header and the app line on «О проекте»: this
// build's version, and whether the app's latest GitHub release is newer
// (checked once per launch).
final class VersionCheck {
    private final MainActivity app;
    private static final String APP_REPO_SLUG = "damnurmum/OpenFlux-Android";
    private static final int VERSION_CHECK_PENDING = 0;
    private static final int VERSION_CHECK_LATEST = 1;
    private static final int VERSION_CHECK_OUTDATED = 2;
    private static final int VERSION_CHECK_FAILED = 3;
    TextView versionBadge;
    String appVersion = "";
    private String latestVersion;
    private int versionCheckState = VERSION_CHECK_PENDING;

    VersionCheck(MainActivity app) {
        this.app = app;
    }

    // The status line on «О проекте», kept in step with the badge.
    TextView appStatus;

    void updateVersionBadge() {
        int fg;
        int bg;
        String status;
        switch (versionCheckState) {
            case VERSION_CHECK_LATEST:
                fg = app.darkMode ? Color.rgb(129, 201, 149) : Color.rgb(24, 128, 56);
                bg = app.darkMode ? Color.rgb(30, 46, 36) : Color.rgb(230, 245, 234);
                status = "Последняя версия, обновлять не нужно";
                break;
            case VERSION_CHECK_OUTDATED:
                fg = app.darkMode ? Color.rgb(253, 214, 99) : Color.rgb(249, 171, 0);
                bg = app.darkMode ? Color.rgb(56, 46, 20) : Color.rgb(255, 243, 224);
                status = "Вышла " + latestVersion + " - обновите приложение";
                break;
            case VERSION_CHECK_PENDING:
            case VERSION_CHECK_FAILED:
            default:
                fg = app.accent;
                bg = app.darkMode ? Color.rgb(38, 50, 68) : Color.rgb(232, 240, 254);
                status = versionCheckState == VERSION_CHECK_FAILED
                        ? "Не удалось проверить обновления" : "Проверяю обновления…";
        }
        if (versionBadge != null) {
            // Just the number: the color tells whether it is the latest.
            versionBadge.setText(appVersion.isEmpty() ? "-" : appVersion);
            versionBadge.setTextColor(fg);
            versionBadge.setBackground(app.rounded(bg, Color.TRANSPARENT, 0, 12));
        }
        if (appStatus != null) {
            appStatus.setText(status);
            boolean known = versionCheckState == VERSION_CHECK_LATEST || versionCheckState == VERSION_CHECK_OUTDATED;
            appStatus.setTextColor(known ? fg : app.secondary);
        }
    }

    String readAppVersion() {
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    void checkForUpdates() {
        new Thread(() -> {
            String latest = fetchLatestGithubVersion();
            if (latest == null || latest.isEmpty()) {
                app.handler.post(() -> {
                    versionCheckState = VERSION_CHECK_FAILED;
                    updateVersionBadge();
                });
                return;
            }
            app.handler.post(() -> {
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
            URL url = new URL("https://api.github.com/repos/" + APP_REPO_SLUG + "/releases/latest");
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

    static int compareVersions(String a, String b) {
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

    private static int versionPart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
