# OpenFlux for Android

The Android app uses the system `VpnService` API and provides connection,
logging and settings tabs. Its document URL and shared encryption secret are
entered by the user; no exit-node address, document URL or secret is embedded in
the source code or APK.

## Runtime behavior

- Android 8 (API 26) or newer, with `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
  and universal APKs;
- two connection modes: system-wide tunnel (`VpnService`), or a local SOCKS5
  proxy that other apps can be pointed at manually, optionally exposed to the
  local network with authentication;
- IPv4/TCP and UDP traffic, including DNS over UDP/53, is forwarded through
  OpenFlux and the exit node;
- the DNS-server field accepts a numeric IPv4 address reachable from the exit
  node; the default is `1.1.1.1`, rather than the phone's local network resolver;
- the foreground service keeps the tunnel alive while the screen is off, and
  shows live upload/download speed in its notification;
- the URL and secret are encrypted using an Android Keystore-backed key;
- settings remain after an in-place update signed by the same certificate;
- clearing app data or uninstalling the app removes the saved settings.

The current app does not tunnel IPv6. UDP, including DNS, needs an exit node
with UDP support; an older exit node may drop it. Custom DNS hostnames are not
accepted because resolving one before the VPN starts would expose that lookup
on the phone's local network. It is experimental and has not received an
independent security audit.

## Build

Install Java 17, Android SDK/API 35, Build Tools 35.0.0,
NDK 27.0.12077973, Gradle 8.14.3, `gomobile` and `gobind`. Set
`ANDROID_SDK_ROOT` or `ANDROID_HOME`, then run from the repository root:

```bash
./build_android_app.sh
```

The script generates `android/app/libs/openflux.aar` and writes per-architecture
and universal debug APKs to `dist/`. Generated files are ignored by Git.

Debug builds are suitable for direct testing only. GitHub Release builds use a
persistent signing key stored outside the repository and supplied to Actions as
encrypted secrets. Android accepts an in-place update only when the package name
and signing certificate match the installed application.
