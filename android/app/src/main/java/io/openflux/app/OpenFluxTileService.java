package io.openflux.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.List;

// Quick Settings tile that toggles whichever connection mode (Tunnel or
// Proxy) is currently selected in the app, using whichever profile is
// currently selected there. Tunnel mode needs Android's one-time system
// consent dialog, which a TileService cannot show itself, so if that
// permission hasn't been
// granted yet (or no profile is configured) the tile opens the app instead
// of failing silently.
public final class OpenFluxTileService extends TileService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateTile();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onStartListening() {
        super.onStartListening();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override public void onStopListening() {
        handler.removeCallbacks(refresh);
        super.onStopListening();
    }

    @Override public void onClick() {
        super.onClick();
        boolean proxyMode = isProxyMode();
        boolean exitMode = isExitMode();
        boolean running = exitMode ? OpenFluxExitService.isRunning()
                : proxyMode ? OpenFluxProxyService.isRunning() : OpenFluxTunnelService.isRunning();
        if (running) {
            Intent stop = exitMode
                    ? new Intent(this, OpenFluxExitService.class).setAction(OpenFluxExitService.ACTION_STOP)
                    : new Intent(this, proxyMode ? OpenFluxProxyService.class : OpenFluxTunnelService.class)
                            .setAction(proxyMode ? OpenFluxProxyService.ACTION_STOP : OpenFluxTunnelService.ACTION_STOP);
            startService(stop);
            updateTile();
            return;
        }
        if (exitMode) {
            if (!startExit()) openApp();
            updateTile();
            return;
        }
        if (!proxyMode && VpnService.prepare(this) != null) {
            // First-time tunnel consent can only be granted through an activity.
            openApp();
            return;
        }
        if (!startConnection(proxyMode)) {
            openApp();
            return;
        }
        updateTile();
    }

    private boolean isProxyMode() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.SETTINGS_PREFS_NAME, MODE_PRIVATE);
        return "proxy".equals(prefs.getString("connection_mode", "tunnel"));
    }

    private boolean isExitMode() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.SETTINGS_PREFS_NAME, MODE_PRIVATE);
        return "exit".equals(prefs.getString("connection_mode", "tunnel"));
    }

    private Profile selectedProfile() {
        ProfileStore store = new ProfileStore(new SecureSettings(this));
        long selectedId = store.getSelectedId();
        for (Profile p : store.load()) if (p.id == selectedId) return p;
        return null;
    }

    private boolean startExit() {
        Profile selected = selectedProfile();
        if (selected == null) return false;
        Intent intent = new Intent(this, OpenFluxExitService.class).setAction(OpenFluxExitService.ACTION_START);
        selected.putConnectionExtras(intent, new ScriptStore(this));
        startForegroundService(intent);
        return true;
    }

    private boolean startConnection(boolean proxyMode) {
        SecureSettings secureSettings = new SecureSettings(this);
        ProfileStore profileStore = new ProfileStore(secureSettings);
        List<Profile> profiles = profileStore.load();
        long selectedId = profileStore.getSelectedId();
        Profile selected = null;
        for (Profile p : profiles) {
            if (p.id == selectedId) { selected = p; break; }
        }
        if (selected == null) return false;
        // The app says what is wrong with a JS carrier; the tile cannot.
        if (selected.scriptProblem(new ScriptStore(this)) != null) return false;
        if (!"oneme".equals(selected.transportType) && !"script".equals(selected.transportType)
                && (selected.documentUrl == null || selected.documentUrl.isEmpty())) return false;

        SharedPreferences prefs = getSharedPreferences(MainActivity.SETTINGS_PREFS_NAME, MODE_PRIVATE);
        if (proxyMode) {
            boolean lanAccess = prefs.getBoolean("proxy_lan_access", false);
            boolean authEnabled = prefs.getBoolean("proxy_auth_enabled", false);
            Intent intent = new Intent(this, OpenFluxProxyService.class);
            intent.setAction(OpenFluxProxyService.ACTION_START);
            selected.putConnectionExtras(intent, new ScriptStore(this));
            intent.putExtra(OpenFluxProxyService.EXTRA_PORT, prefs.getInt("proxy_port", 1080));
            intent.putExtra(OpenFluxProxyService.EXTRA_LAN_ACCESS, lanAccess);
            if (lanAccess && authEnabled) {
                intent.putExtra(OpenFluxProxyService.EXTRA_USERNAME, prefs.getString("proxy_username", ""));
                intent.putExtra(OpenFluxProxyService.EXTRA_PASSWORD, secureSettings.getString("proxy_password", ""));
            }
            startForegroundService(intent);
        } else {
            Intent intent = new Intent(this, OpenFluxTunnelService.class);
            intent.setAction(OpenFluxTunnelService.ACTION_START);
            selected.putConnectionExtras(intent, new ScriptStore(this));
            intent.putExtra(OpenFluxTunnelService.EXTRA_DNS_SERVER, prefs.getString("dns_server", ""));
            intent.putExtra(OpenFluxTunnelService.EXTRA_MTU, prefs.getInt("mtu", 1400));
            startForegroundService(intent);
        }
        return true;
    }

    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean proxyMode = isProxyMode();
        boolean exitMode = isExitMode();
        boolean running = exitMode ? OpenFluxExitService.isRunning()
                : proxyMode ? OpenFluxProxyService.isRunning() : OpenFluxTunnelService.isRunning();
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_openflux_notification));
        tile.setLabel("OpenFlux");
        tile.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(exitMode ? "Выходная нода" : proxyMode ? "Прокси" : "Туннель");
        }
        tile.updateTile();
    }
}
