# Changelog

All notable changes to this fork are documented here.

## 0.7.0 - 2026-09-16

### Added

- a `mailru` transport (Mail.ru Docs shared-cursor channel), same shape as
  `yandex`; merged upstream and wired into both the CLI and the Android app.
- exit node core resynced 1:1 with upstream's `flx-kernel`: new
  `--role=client|exit|bench-send|bench-sink`, `--inbound=tun|socks5`,
  `--mode=l3|l4`, `--codec=batched|legacy`, and a single `--url` flag
  (replaces `--exit-node`/`--client`/`--tun`/`--socks5-mode`/`--mode
  raw|proxy`/`--url-file`, all still accepted for one release as deprecated
  aliases). `--url-file` has no direct replacement - read the file yourself
  with shell command substitution, e.g. `--url "$(cat document-url)"`.
- Android app: added the Cups.online and MAX (oneme/WebRTC) transports,
  a codec choice (batched/legacy), and MAX token/UID fields to Settings -
  previously CLI-only. Missing `[M-DOCS]` (mailru) log tag color added.
- new cross-platform release builds (darwin/linux/windows, amd64/arm64,
  plus linux/arm and windows/386) via CI, matching upstream's release
  asset list.

### Fixed

- Android: changing a Network or Mode of operation setting and pressing
  back without an explicit save no longer applies it - these pages now use
  the same draft/apply pattern as the profile editor.
- Android: the mobile bridge (`mobile.Start`/`mobile.StartProxy`) derived
  the encryption KDF context differently from the desktop CLI when no
  document URL is set (the `oneme` transport), which would silently break
  decryption between an Android client and a CLI exit node on that
  transport. Both now fall back to the transport name identically.
- desktop client: `SocketWatcher` installed direct/bypass routes for the
  transport's own control-channel IP but never removed them on shutdown,
  which could leave stale routes behind after disconnecting (also missed
  cleanup on `SIGHUP`, e.g. closing the terminal window).
- CI: Android builds failed because `sdkmanager` wasn't on `PATH` on the
  `ubuntu-latest` runner; the redundant, already-broken
  `android-actions/setup-android` step was removed instead.

### Changed

- all "bypass" wording (code, logs, docs) renamed to "direct" - this is a
  VPN client, not a bypass tool.
- rebrand: accent color changed to `#EA1A1A`, new app icon/logo.
- `main.go`, `tun_darwin.go`, `tun_watch.go`, `tun_learn.go`, `tun_other.go`,
  `signals_unix.go`, `signals_windows.go` moved into a `tunclient/` package;
  `bench.go` moved into a `bench/` package. No behavior change.

## 0.6.0 - 2026-09-13

### Added

- **connection profiles**: save several named connection configurations
  (transport type, document URL, encryption key, an icon) and switch
  between them - a new "Профили" tab (second in the bottom navigation),
  a dropdown selector with icons above the Home connect button, and an
  add/edit screen with an icon picker and a Yandex Docs / Yandex Docs
  (Volga) transport choice. Existing installs migrate their single saved
  URL/key into a first profile automatically. `mobile.Start`/
  `mobile.StartProxy` now take a `transportType` parameter so the Android
  bridge can actually select `vyandex`, not just `yandex`.
- **Quick Settings tile**: toggles the connection mode currently selected
  in the app for the active profile, without opening it. Opens the app
  instead of failing silently when VPN consent hasn't been granted yet or
  no profile is configured.
- **colorized, timestamped logs**: each line gets a `[HH:mm:ss.SSS]`
  prefix; known tags are colored (`[ERROR]`/`[PANIC]` red, `[SUCCESS]`
  green, `[YDOCS]` yellow, `[ANDROID]`/`[VOLGA]`/`[MAX]` accent).
- exit node: a `--mode proxy|raw` flag selects the internet-facing path.
  `proxy` (new default) dials out with a plain `net.Dial`, needs no root
  and no RST-drop iptables rule, and works on any OS. `raw` keeps the
  previous hand-rolled raw-socket path (Linux only, root required),
  falling back to `proxy` automatically if it can't get a raw socket.
  Synced from upstream.
- an experimental `cupsonline` transport riding cups.online's live-coding
  "shared cursor position" broadcast as a covert channel. Not wired into
  the Android app: testing found it does not reliably deliver traffic once
  transport encryption is enabled, so treat it as unsupported for now (see
  the flags table in README).

### Changed

- the exit-node core (`transport/yandex`, `tunnel`, gVisor TCP buffer
  sizing) is realigned to match upstream `p1neappleXpress/OpenFlux`'s
  `main` byte-for-byte, so this fork's Android app is guaranteed to work
  against either this fork's exit-node binary or an unmodified upstream
  one. Client-only code the exit node never executes (SOCKS5 auth/
  telemetry, `--url-file`, the cleaner `DialTCP` address parsing) is kept
  as this fork's own addition.

### Fixed

- the connection uptime counter reset to zero if the app was reopened
  after Android killed the backgrounded Activity (common after being away
  for a while) even though the VPN/Proxy service itself never stopped.
  The "connected since" timestamp now lives on the service (which
  survives Activity recreation), not on the Activity.
- tapping the already-active bottom navigation tab replayed the page
  crossfade/icon-pop animation for no reason; it's now a no-op.
- the divider between rows in the Profiles list was indented under the
  icon (matching the Settings list); it now runs edge to edge.
- the VPN's DNS-server field rejected a hostname (e.g. `dns.google`):
  `VpnService.Builder.addDnsServer()` only accepts a literal IP address
  and threw on anything else. The app now resolves a hostname to an IP
  before handing it to the builder; the local DNS relay itself already
  supported hostnames fine.
- log lines from the `cupsonline` transport's `[CUPS]` tag were not
  colorized like the other component tags in the Logs tab.

## 0.5.3 - 2026-09-12

### Fixed

- crash on connect introduced in 0.5.2: `OpenFluxVpnService` and
  `OpenFluxProxyService` still rejected an empty encryption key with
  `stopSelf()` before ever calling `startForeground()`. Since encryption is
  now optional, leaving the key field empty (as the UI itself now suggests)
  hit that path on every connect attempt, and Android kills a foreground
  service that doesn't call `startForeground()` in time with
  `ForegroundServiceDidNotStartInTimeException` - crashing the whole app.
  Both services now call `startForeground()` immediately in
  `onStartCommand`, before any validation that could stop the service early,
  and their encryption-key check now matches the optional behavior (only
  rejects a key that's set but shorter than 16 characters).
- Android application version is now 0.5.3 (version code 8).

## 0.5.2 - 2026-09-12

### Changed

- synced `main` with upstream `p1neappleXpress/OpenFlux` through commit
  `4f1bdb5`: a Docker image and compose file for running the client/exit-node
  locally, and - most importantly - upstream's own rework of transport
  encryption (originally contributed from this fork via PR #38) that makes
  `--encryption-key-file` **optional** instead of mandatory, and applies it
  generically to every transport (`yandex`, `vyandex`, `oneme`) rather than
  only `yandex`.
- following that, this fork's own mandatory-encryption stance for the
  `yandex` transport is dropped: the Android app's encryption key field is
  now optional (labeled and documented as such), and `mobile.Start`/
  `mobile.StartProxy` skip wrapping the transport in `EncryptedTransport`
  when the key is left empty, matching the CLI. Leaving the key unset lets
  the app talk to a plain, unmodified upstream exit node with no transport
  encryption; setting one (16+ characters, matching on both ends) enables
  AES-256-GCM as before.
- the `vyandex` transport is now wired into `--transport` on the CLI, since
  the previous reason for excluding it (no encryption wrapper of its own)
  no longer applies now that encryption is a generic, optional layer over
  any transport.
- Android application version is now 0.5.2 (version code 7).

## 0.5.1 - 2026-09-12

### Changed

- `main` is realigned to be wire-compatible with the current upstream
  `p1neappleXpress/OpenFlux` exit-node and client binaries again. See
  [docs/UPSTREAM_DIFF.md](UPSTREAM_DIFF.md) for the full comparison that led
  to this.

The previous `EncryptedTransport` added a 1-byte frame-type tag ahead of
every packet to multiplex ping and DNS-relay traffic into the same
encrypted channel. Upstream's transport (including the version merged from
this fork's own earlier PR) has no such tag, so a client on one side could
not talk to an exit node on the other without every packet's IP header
getting corrupted by the stray byte - not just the newer features, any data
at all. That divergence dates back to when ping support was first added, not
just this week's DNS-relay work.

To fix this, `main` drops everything that depended on the frame protocol:

- **ping graph and exit-node country display** (Home screen) - relied on
  `framePingRequest`/`framePingResponse`;
- **"resolve DNS on the server" option** - relied on the new
  `frameDNSRequest`/`frameDNSResponse` frames added this week. DNS
  resolution on Android is local-only again, exactly as it was before this
  week's tunnel-relay work, for both VPN and Proxy mode.

Everything else added since 0.4.0 is untouched and stays fully functional
with an unmodified upstream binary, since none of it touches the client<->
exit-node wire protocol: Proxy (SOCKS5) mode, local-network access and SOCKS5
authentication with a share link and QR code, the vertical Settings
navigation and About section, the GitHub update-check badge, live
upload/download speed and a Disconnect action in the notification, honest
connection-health tracking, the battery-optimization and Always-on VPN
shortcuts, the fuller active-parameters list, the `POST_NOTIFICATIONS`
request, and the animation-duplication fix.

The full previous feature set (frame protocol, ping graph, country display,
server-side DNS relay) is preserved on the `experimental` branch for anyone
running both ends with this fork's own exit-node binary.

- Android application version is now 0.5.1 (version code 6).

## 0.5.0 - 2026-09-12

### Added

- a second connection mode for Android: **Proxy (SOCKS5)**, alongside the
  existing system-wide VPN mode. It reuses the same encrypted document
  transport and the same userspace TCP/IP stack (gVisor) the desktop CLI's
  SOCKS5 client already used, wired through `gomobile` - no protocol or
  exit-node changes were needed for this mode by itself. Unlike VPN mode it
  needs no VPN permission prompt and runs as a plain foreground service.
- **DNS resolution via the exit node**: both VPN and Proxy mode can relay DNS
  queries through the encrypted tunnel to the exit node, which asks the
  configured upstream over plain UDP from its own network - the query never
  touches the client's local network. New symmetric `frameDNSRequest`/
  `frameDNSResponse` frames on the existing encrypted channel. A
  per-connection setting lets you choose server-side or local resolution
  instead (useful with an exit node that hasn't been updated yet, or when
  local resolution is simply faster).
- the DNS-server field now accepts a hostname as well as an IP (e.g.
  `dns.google`, not just `1.1.1.1`) for both resolution modes.
- **local-network access and authentication for Proxy mode**: an opt-in
  toggle exposes the SOCKS5 listener on `0.0.0.0` instead of loopback only,
  so another device on the same Wi-Fi/LAN can use it, plus optional SOCKS5
  username/password authentication (RFC 1929, constant-time credential
  check) with a one-tap credential generator. A `socks://` share link and QR
  code are shown for pairing another device.
- the SOCKS5 server now replies with a proper protocol-level error (RFC 1928
  address-not-supported / command-not-supported) instead of silently closing
  the connection on an IPv6 address or a non-CONNECT command, so a client
  fails fast instead of hanging. Full IPv6/UDP-ASSOCIATE delivery is not yet
  implemented - the exit node's raw-socket internet path is currently
  IPv4/TCP-only.
- Settings navigation was redone as a vertical, phone-Settings-style list
  (icon + label, tap to drill in, back via the top-left arrow or the system
  back button/gesture) instead of a horizontal tab strip, with a new
  **About** section linking to the upstream repository and this fork.
- the app-version badge (previously a static "BETA" pill) now shows the
  installed version and checks GitHub for a newer release at startup,
  turning green ("up to date") or amber ("update available").
- the pinned connection notification now shows live upload/download speed
  (updated every second) with a **Disconnect** action button, for both
  modes.
- connection status now tracks the transport honestly after the initial
  handshake: if the encrypted transport drops and silently retries mid
  session, the UI switches back to "Connecting..." instead of continuing to
  show a stale "Connected", and back again once it recovers.
- shortcuts to two OS-level reliability settings: a battery-optimization
  exemption prompt (foreground services survive OEM battery managers much
  better with it granted), and a link to Android's Always-on VPN /
  "Block connections without VPN" settings screen for VPN mode.
- the Home screen's active-parameters card now lists every relevant setting
  for the current mode (mode, DNS server and resolution location, MTU or
  local port, app filter, LAN/auth status), not just two of them.
- runtime `POST_NOTIFICATIONS` permission is now requested on Android 13+;
  without it the app worked fine but silently showed no notification at all.
- animated UI buttons no longer stack/duplicate their scale animation when
  tapped rapidly or repeatedly (a shared `bounce()` helper cancels and
  replaces any in-flight animation on the same view instead of layering a
  new one on top).

### Changed

- Android application version is now 0.5.0 (version code 5).
- the keyboard no longer resizes the whole window (shrinking the bottom
  navigation and the Save button up into view); it now pans instead, so
  those stay in place and are simply covered while a field is focused.
- the per-app VPN filter's hint now notes it only applies to VPN mode, since
  Proxy mode has no system-wide capture to filter.

## 0.4.0 - 2026-09-12

### Added

- Android Settings is now organized into tabs (Транспорт / Сеть /
  Приложения / Вид) instead of one long scrolling page.
- per-app VPN routing: a whitelist ("only these apps use the tunnel") or
  blacklist ("all apps except these") mode, with an in-app picker over
  installed apps. Backed by `VpnService.Builder.addAllowedApplication`/
  `addDisallowedApplication`.
- the exit node's country now shows up next to the ping once it arrives,
  piggybacked on the existing encrypted ping/pong frames (no new protocol
  message); the exit node determines it once at startup via `ip-api.com`
  and publishes it through `EncryptedTransport.SetCountry`.
- animations throughout the Android UI: crossfaded tab/page transitions,
  a staggered card entrance on Home, a pulsing status dot while
  connecting/disconnecting, an animated VPN-button color transition, and
  small pop/bounce feedback on tab and toggle interactions.
- haptic feedback: light ticks on button/toggle/tab interactions via
  `View.performHapticFeedback`, plus distinct vibration patterns for a
  successful connection and for errors via `Vibrator`/`VibrationEffect`
  (new `VIBRATE` permission).
- a beginner-friendly, step-by-step VPS deployment guide in English and
  Russian ([docs/en/GUIDE.md](GUIDE.md), [docs/ru/GUIDE.md](../ru/GUIDE.md)),
  linked from both READMEs.
- CI now cross-compiles Linux `amd64`/`arm64` server/client binaries and
  uploads them as a build artifact; GitHub Releases now attach the same
  binaries (with checksums) alongside the Android APKs.
- synced with upstream p1neappleXpress/OpenFlux through commit `3249724`:
  fixed swapped `maxToken`/`maxUid` flag descriptions, `yandex` transport now
  returns errors instead of panicking on unexpected document config (with new
  tests), and an experimental Yandex.Docs Volga transport (`vyandex.go`) is
  now in the tree.
- synced with upstream through commit `9ef5ab3`: exponential reconnect
  backoff and bounded WebSocket dial for the `yandex` transport; SOCKS5
  `Bind`/`Close` lifecycle plus a bounds-check fix for malformed domain
  requests; the MAX transport no longer kills the whole process
  (`os.Exit(1)`) when its connection drops - fatal when embedded as an
  Android library; panic recovery (`utils.SafeGo`) around background
  goroutines in the transport, tunnel and SOCKS5 layers; a `--local-ip` flag
  to scope the exit node's RST-drop iptables rule to a dedicated egress IP
  instead of dropping RSTs host-wide; aggressive GC on the exit node for
  small VPS instances.

### Changed

- Android application version is now 0.4.0 (version code 4).

### Notes

- the new `vyandex` transport is intentionally not wired into the
  `--transport` CLI switch: it has no mandatory encryption wrapper yet, so
  exposing it would contradict this fork's encrypted-by-default security
  model for Yandex Docs transports.
- upstream re-added a full iOS app (`ios-app/`, `export_ios*.go`,
  `tunnel/packettunnel.go`, `build_ios*.sh`) and several Network Extension
  hardening commits on top of it; none of that was carried over. This fork
  does not support iOS (see [FORK.md](FORK.md)).

## 0.3.0 - 2026-09-10

### Added

- Android APKs for `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` and universal
  devices;
- automated GitHub Release publishing for tags matching `v*`;
- SHA-256 checksums for every release APK.

## 0.2.0 - 2026-09-10

### Added

- native Android `VpnService` client with connection, logs and settings tabs;
- Android Keystore-backed protection for the saved document URL and secret;
- mandatory AES-256-GCM encryption for the Yandex document transport;
- encrypted ping frames and an animated latency graph;
- DNS-over-HTTPS support for the Android VPN;
- a hardened sample systemd service for the Linux exit node;
- CI checks for Go and Android debug builds.

### Changed

- secret values can be loaded from root-only files instead of command-line
  arguments;
- Android application version is now 0.2.0 (version code 2).

### Removed

- the incomplete iOS prototype and generated IDE/build artifacts.
