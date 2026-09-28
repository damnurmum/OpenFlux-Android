<div align="center">
  <img src="design/logo/icon.svg" width="112" alt="OpenFlux logo">
  <h1>OpenFlux Android</h1>
  <p>Encrypted document-transport tunnel for Android, desktop clients and Linux exit nodes.</p>
  <p>
    <a href="https://github.com/damnurmum/OpenFlux-Android/releases/latest"><img src="https://img.shields.io/github/v/release/damnurmum/OpenFlux-Android?display_name=tag&amp;sort=semver&amp;style=flat-square&amp;color=EA1A1A" alt="Latest release"></a>
    <a href="https://github.com/damnurmum/OpenFlux-Android/actions/workflows/ci.yml"><img src="https://github.com/damnurmum/OpenFlux-Android/actions/workflows/ci.yml/badge.svg" alt="CI status"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/damnurmum/OpenFlux-Android?style=flat-square" alt="GPL-3.0 license"></a>
    <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 8 or newer">
    <a href="https://discord.gg/openfluxx"><img src="https://img.shields.io/discord/1550202172304785479?style=flat-square&amp;logo=discord&amp;logoColor=white&amp;label=Discord&amp;color=5865F2" alt="Join our Discord"></a>
  </p>
  <p>
    <img src="https://img.shields.io/badge/Go-1.26.4%2B-00ADD8?style=flat-square&amp;logo=go&amp;logoColor=white" alt="Go 1.26.4 or newer">
    <img src="https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&amp;logo=openjdk&amp;logoColor=white" alt="Java 17">
    <img src="https://img.shields.io/badge/ABI-ARM64%20%7C%20ARMv7%20%7C%20x86__64%20%7C%20x86-455a64?style=flat-square" alt="Supported Android architectures">
    <img src="https://img.shields.io/badge/IPv4%20%2F%20TCP-experimental-f59e0b?style=flat-square" alt="Experimental IPv4 and TCP support">
  </p>
  <p><strong>English</strong> · <a href="README.ru.md">Русский</a></p>
</div>

> This repository is an experimental, independently maintained fork of
> [p1neappleXpress/OpenFlux](https://github.com/p1neappleXpress/OpenFlux),
> adding a native Android tunnel client on top of upstream's exit-node/transport
> core. See [docs/en/FORK.md](docs/en/FORK.md) for what this fork changes. `main`
> stays wire-compatible with current upstream exit-node/client binaries; a
> separate `experimental` branch carries additional features (ping graph,
> exit-node country, server-side DNS relay) that need this fork's own exit
> node - see [docs/en/UPSTREAM_DIFF.md](docs/en/UPSTREAM_DIFF.md).

OpenFlux is a research TCP tunnel that disguises traffic as a collaborative
document-editing session (Yandex Docs, Mail.ru Docs, Cups.online, MAX) instead
of a conventional VPN protocol. This fork adds an Android client for that
tunnel and optional end-to-end AES-256-GCM encryption on top of it.

**[Download the latest Android release](https://github.com/damnurmum/OpenFlux-Android/releases/latest)**

New to this? [docs/en/GUIDE.md](docs/en/GUIDE.md) is a beginner-friendly, step-by-step
walkthrough for deploying an exit node on a VPS and connecting from Android.

```text
Android tunnel or SOCKS5 client -> encrypted document transport -> Linux exit node -> Internet
```

## Features

- Android 8+ client using the system `VpnService` API, with ARM, ARM64, x86 and
  x86_64 builds;
- a second Android connection mode, local SOCKS5 Proxy, for when a full
  system-wide tunnel isn't wanted - optionally exposed to the local network with
  SOCKS5 authentication, plus a `socks://` share link and QR code;
- profiles: save several exit-node configurations (transport, document URL,
  secret) and switch between them without re-typing anything;
- five pluggable transports - Yandex.Docs, Yandex Volga, Mail.ru Docs,
  Cups.online and MAX/OneMe - plus a choice of wire codec (batched+zstd, the
  default, or legacy per-packet LZ4 for compatibility with older exit nodes);
- optional AES-256-GCM authenticated encryption with a key derived using
  scrypt, wire-compatible with upstream's exit-node and client binaries;
  leave the key empty to connect unencrypted to a plain exit node;
- Android Keystore-backed storage for the document URL and shared secret;
- DNS-server and MTU fields, applied only when explicitly saved - navigating
  away without saving discards the edit;
- per-app tunnel routing (whitelist or blacklist which apps use it);
- a pinned notification with live upload/download speed and a disconnect
  action, for both connection modes;
- desktop SOCKS5/utun client and Linux exit-node modes (`l3` raw SNAT/DNAT or
  `l4` gVisor proxy) for the same tunnel, see [Desktop CLI](#desktop-cli-exit-node-and-client) below.

> **MAX transport warning:** the MAX backend sends packets via WebRTC
> DataChannel on your MAX account. Do not use a primary or important account;
> running it from an external VPS may lead to account restrictions that
> persist after OpenFlux stops. Treat MAX transport as experimental until its
> detection and blocking behavior is better understood.

## Important limitations

OpenFlux is experimental research software, not an audited replacement for
WireGuard or another mature VPN. The Android tunnel currently supports IPv4 and
TCP; arbitrary UDP and IPv6 are not tunneled (an IPv6 target or a UDP-only
request is rejected with a proper protocol error, not tunneled silently). The
document provider can still observe metadata such as connection times, traffic
sizes and encrypted payloads. Anyone with document edit access can disrupt the
connection.

Use the software only on systems and networks you own or are authorized to
test.

## Requirements

- Go 1.26.4 or newer for the desktop client and exit node;
- a Linux VPS/VDS with root access for the exit node's `l3` mode (`l4` needs
  no root, on any OS);
- for Android builds: Java 17, Android SDK/API 35, Build Tools 35.0.0,
  NDK 27.0.12077973, Gradle 8.14.3 and `gomobile`;
- an editable document opened with the legacy editor when using a document
  transport (Yandex Docs, Yandex Volga, Mail.ru Docs).

## Prepare the private configuration

Create the following files locally and copy the same values to the exit node.
They are excluded by `.gitignore` and must never be committed:

```bash
printf '%s\n' 'https://your-own-document-url' > document-url
openssl rand -base64 32 > encryption-key
chmod 600 document-url encryption-key
```

The encryption key is optional: omit `--encryption-key-file` on both ends
(and leave the Android app's key field empty) to talk to a plain, unmodified
exit node with no transport encryption. If you do set a key, it must contain
at least 16 characters, be a unique random value rather than a reused
password, and match on both ends. Rotate the document URL and the key if
either is exposed.

## Desktop CLI (exit node and client)

The exit node and desktop client are the same binary; only the flags differ.
Prebuilt Linux `amd64`/`arm64` binaries (plus macOS and Windows builds) are
attached to every [GitHub Release](https://github.com/damnurmum/OpenFlux-Android/releases/latest)
alongside the Android APKs. The core is the upstream
[OpenFlux](https://github.com/p1neappleXpress/OpenFlux) repository, checked
out here as the `core/` submodule. To build it yourself instead:

```bash
git clone --recursive https://github.com/damnurmum/OpenFlux-Android.git
cd OpenFlux-Android/core
go build -o openflux .
```

### Exit node - l3 (Linux, root)

```bash
sudo ./openflux --role=exit --mode=l3 --transport=yandex \
    --url="$(cat document-url)" --encryption-key-file=encryption-key
```

`l3` forwards raw IP packets with SNAT/DNAT (conntrack + egress-IP filter) -
one TCP connection end-to-end, no double termination, but Linux + root only.
The exit node's TCP connections live in a userspace stack, so the kernel has
no socket for them and sends an RST on every reply, tearing the tunnel down.
That RST must be suppressed - scoped, not host-wide:

```bash
# Scoped (recommended): give the box a second/alias IP dedicated to the
# tunnel, run with --local-ip, then:
sudo iptables -A OUTPUT -p tcp --tcp-flags RST RST -s 203.0.113.10 -j DROP
sudo ./openflux --role=exit --mode=l3 --local-ip=203.0.113.10 ...

# Host-wide fallback (drops ALL outbound RST; makes closed ports look
# filtered, and stops the host from resetting unrelated connections; only
# on a single-purpose box):
sudo iptables -C OUTPUT -p tcp --tcp-flags RST RST -j DROP 2>/dev/null || \
  sudo iptables -I OUTPUT 1 -p tcp --tcp-flags RST RST -j DROP
```

The sample [systemd unit](deploy/openflux.service) expects the binary and
private files in `/root/openflux`. Review its paths before installing it:

```bash
sudo install -d -m 700 /root/openflux
sudo install -m 755 ./openflux /root/openflux/openflux
sudo install -m 600 ./document-url ./encryption-key /root/openflux/
sudo install -m 644 deploy/openflux.service /etc/systemd/system/openflux.service
sudo systemctl daemon-reload
sudo systemctl enable --now openflux
sudo systemctl status openflux
```

### Exit node - l4 (any OS, no root)

```bash
./openflux --role=exit --mode=l4 --transport=yandex \
    --url="$(cat document-url)" --encryption-key-file=encryption-key
```

Terminates TCP in a userspace gVisor stack and re-dials the real server with
`net.Dial` - works anywhere, at the cost of terminating TCP twice. Use this on
Windows, macOS, or a non-root Linux host.

### Client - SOCKS5 (all platforms)

```bash
./openflux --role=client --inbound=socks5 --transport=yandex \
    --url="$(cat document-url)" --encryption-key-file=encryption-key \
    --socks5=127.0.0.1:1080
```

Point your browser / app at `127.0.0.1:1080` as a SOCKS5 proxy. Default
inbound on non-macOS platforms.

### Client - macOS utun (default on macOS)

```bash
sudo ./openflux --role=client --transport=yandex \
    --url="$(cat document-url)" --encryption-key-file=encryption-key
```

Creates a utun interface, routes the transport's own connection directly
(bypassing the tunnel so it doesn't loop through itself), then takes the
default route. No SOCKS5; all other traffic goes through the tunnel.

### Other transports

```bash
# Yandex Volga (HTTP relay + WS)
./openflux --role=exit --mode=l3 --transport=vyandex --url="..." --debug

# MAX / OneMe (WebRTC DataChannel)
./openflux --role=exit --mode=l3 --transport=oneme \
    --maxToken="..." --maxUid="..." --debug

# Cups.online (Centrifugo rooms)
./openflux --role=exit --mode=l3 --transport=cupsonline --url="..." --debug

# Mail.ru Docs (WS)
./openflux --role=exit --mode=l3 --transport=mailru \
    --url="https://cloud.mail.ru/public/AbCdEfGh1/IjKlMnOp2" --debug
```

Add `--debug` only when diagnosing a problem, and inspect logs before sharing
them - they can include the document URL.

### Flags

| Flag | Short | Default | Description |
|------|-------|---------|-------------|
| `--role` | `-r` | `client` | `client` \| `exit` \| `bench-send` \| `bench-sink` |
| `--inbound` | `-i` | (platform) | Client only: `tun` (macOS) \| `socks5` (default elsewhere) |
| `--transport` | `-t` | `yandex` | `yandex` \| `vyandex` \| `oneme` \| `cupsonline` \| `mailru` |
| `--mode` | `-m` | `l3` | Exit-node only: `l3` \| `l4` |
| `--codec` | `-c` | `batched` | `batched` (zstd+coalescing) \| `legacy` (per-packet LZ4) |
| `--url` | `-u` | empty | Document URL (or a weblink for `mailru`) |
| `--socks5` | `-s` | `:1080` | SOCKS5 listen address (client, `--inbound=socks5`) |
| `--local-ip` | `-l` | (auto) | Exit-node egress IP, for scoping the RST-drop rule (`l3` only) |
| `--encryption-key-file` | | empty | Optional AES-256-GCM shared-secret file; unset means unencrypted |
| `--maxToken` / `--maxUid` | | empty | MAX auth token / user id (`--transport=oneme`) |
| `--bench-bytes` / `--bench-compressible` | | `0` / `false` | Benchmark payload size / compressibility (`--role=bench-send`) |
| `--debug` | `-d` | `false` | Verbose logging |

Deprecated (kept for one release, mapped automatically): `--client`,
`--exit-node`, `--tun`, `--socks5-mode`, `--legacy`, `--bench-send`,
`--bench-sink`.

The batched and legacy wire formats are not compatible with each other -
client and exit node must use the same `--codec`.

## Build and install the Android app

Set `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) and ensure `gomobile` and Gradle are
available. Clone with `--recursive` (or run `git submodule update --init` in an
existing clone) so the `core/` submodule is there, then run:

```bash
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260908204917-8b95e45f8d3e
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260908204917-8b95e45f8d3e
gomobile init
./build_android_app.sh
```

The build creates separate APKs for `arm64-v8a`, `armeabi-v7a`, `x86_64` and
`x86`, plus `OpenFlux-android-universal-debug.apk` for devices whose architecture
is unknown. Transfer the appropriate APK to an Android 8+ device, install it,
create a profile with your own document URL and shared secret, then approve
Android's tunnel consent prompt.

Configuration survives a normal in-place app update when the application ID
and signing certificate stay the same. Clearing app data or uninstalling the
app removes it. APKs signed with a different certificate cannot update the
existing installation. CI artifacts are debug builds; APKs attached to GitHub
Releases use the project's persistent release certificate. Moving from a debug
build to the release channel requires one uninstall and therefore clears saved
settings.

See [android/README.md](android/README.md) for Android-specific details.

## Structure

```
OpenFlux-Android/
  core/                            # Upstream OpenFlux (submodule): CLI, transports, tunnel, mobile/ bridge
  android/                         # Android tunnel client (this fork's addition)
  build_android_app.sh             # Build the Android APKs against core/mobile
  deploy/openflux.service          # Sample systemd unit for the exit node
  docs/                            # Fork documentation
```

The fork's own core, as it was before switching to the upstream submodule,
is kept on the [`fork-core`](https://github.com/damnurmum/OpenFlux-Android/tree/fork-core) branch.

## Development and security

Core changes go to the upstream repository; its CI runs the Go tests. To
move this repository to a newer core, update the submodule and rebuild:

```bash
git -C core fetch origin && git -C core checkout origin/main
./build_android_app.sh
git add core && git commit -m "core: update to upstream <commit>"
```

Contributions are described in [docs/en/CONTRIBUTING.md](docs/en/CONTRIBUTING.md).
Please read [docs/en/SECURITY.md](docs/en/SECURITY.md) before reporting a
vulnerability. Changes are listed in [docs/en/CHANGELOG.md](docs/en/CHANGELOG.md).

## License

OpenFlux is licensed under the GNU General Public License v3.0 or later. See
[LICENSE](LICENSE), [COPYRIGHT](COPYRIGHT) and [NOTICE](NOTICE). This fork is
not endorsed by or affiliated with Yandex or Mail.ru.
