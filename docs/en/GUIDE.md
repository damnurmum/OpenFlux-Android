# Beginner's guide: deploying OpenFlux on your own VPS

**English** · [Русский](../ru/GUIDE.md)

This is a step-by-step, start-to-finish walkthrough: rent a server, run an
OpenFlux exit node on it, and connect from an Android phone. It assumes you
have never done this before.

If anything here disagrees with the main [README.md](../../README.md), trust the
README - it's the more complete and up-to-date reference. This guide is a
simplified path to get a first connection working.

> This is experimental software, not a full replacement for a mature VPN like
> WireGuard. Only use it on systems and networks you own or are authorized to
> test. See [SECURITY.md](SECURITY.md) and the "Important limitations"
> section of the README for details.

## How this actually works

OpenFlux clients don't connect to your server directly - instead, the client
(Android app) and the server (exit node on your VPS) exchange traffic through
a **shared Yandex Docs document**. The document is just a meeting point; all
traffic inside it is encrypted with a secret only you know.

```
Android app <-> encrypted traffic via a Yandex Docs document <-> your VPS (exit node) <-> Internet
```

So you need three things:
1. A **Yandex Docs document** that both the phone and the server point at.
2. An **encryption secret** - the same string on the phone and the server.
3. A **VPS with root access** to run the exit node on.

## What you'll need

- A VPS (any provider) with root access, preferably running Ubuntu/Debian;
- an SSH client to connect to the server (PowerShell/Terminal's built-in
  `ssh` works fine on Windows, and the regular Terminal on macOS/Linux);
- a Yandex account to create the document;
- an Android phone running 8.0 or newer.

You don't need to build anything from source - we'll use the prebuilt files
from [GitHub Releases](https://github.com/damnurmum/OpenFlux-Android/releases/latest).

## Step 1. Prepare a Yandex Docs document

1. Go to [docs.yandex.ru](https://docs.yandex.ru) signed in to your account
   and create a new text document.
2. OpenFlux only works with the **old (classic/legacy) document editor** -
   the new editor breaks the transport and the app may crash. If the
   document opens in the new editor, look for a toggle to switch to the
   old/classic editor somewhere in the document's own interface (usually a
   banner or a setting in the document's menu - Yandex occasionally moves
   this option around, so you may need to look a bit). If you can't find it,
   try creating the document again, or via Yandex.Disk instead.
3. Enable **edit** access via link sharing (not just view) - both the client
   and the server need write access to exchange data through the document.
4. Copy the document link - you'll need it on both the phone and the server.

> This link is effectively your tunnel password - don't publish it or share it
> with anyone. Anyone with edit access to the document can disrupt the
> connection (without being able to decrypt traffic, but they don't need to
> for that).

## Step 2. Pick an encryption secret (optional, but recommended)

Transport encryption is optional - without it OpenFlux behaves exactly like
a plain upstream exit node. If it's your own exit node, we recommend turning
it on: you need a random string **at least 16 characters long**, identical on
the phone and the server. The easiest way is to generate it right in the
Android app in step 4 using the "Generate secure key" button and then copy it
to the server. Or generate one ahead of time in a terminal on your own
machine:

```bash
openssl rand -base64 32
```

Save the output - that's your secret. Don't reuse an existing password;
generate a fresh random value specifically for this.

## Step 3. Install the exit node on your VPS

From here on, run everything on the server - connect over SSH:

```bash
ssh root@your_server_ip
```

### 3.1. Download the prebuilt binary

Check your server's CPU architecture:

```bash
uname -m
```

- `x86_64` → get `OpenFlux-linux-amd64` (the most common case);
- `aarch64` or `arm64` → get `OpenFlux-linux-arm64` (ARM servers, common on
  some cloud providers' ARM instance types).

Download it and make it executable (example for amd64 - swap the filename
for `OpenFlux-linux-arm64` if you're on ARM):

```bash
mkdir -p /root/openflux && cd /root/openflux
curl -LO https://github.com/damnurmum/OpenFlux-Android/releases/latest/download/OpenFlux-linux-amd64
mv OpenFlux-linux-amd64 openflux
chmod +x openflux
```

### 3.2. Save the document link (and the secret, if you chose one) to files

```bash
printf '%s\n' 'YOUR_DOCUMENT_URL' > /root/openflux/document-url
printf '%s\n' 'YOUR_SECRET_FROM_STEP_2' > /root/openflux/encryption-key
chmod 600 /root/openflux/document-url /root/openflux/encryption-key
```

If you decided to skip encryption, you don't need the `encryption-key` file -
just drop `--encryption-key-file` from the start command in steps 3.4/3.5.

Replace `YOUR_DOCUMENT_URL` and `YOUR_SECRET_FROM_STEP_2` with your own
values.

### 3.3. Allow the RST-drop iptables rule

The full technical explanation of why this is needed is in the README; in
short, without this rule the kernel will tear the tunnel down right after it
connects.

```bash
sudo iptables -C OUTPUT -p tcp --tcp-flags RST RST -j DROP 2>/dev/null || \
  sudo iptables -I OUTPUT 1 -p tcp --tcp-flags RST RST -j DROP
```

If this server also runs other services besides OpenFlux, check the scoped
`--local-ip` variant in the main [README.md](../../README.md#desktop-cli-exit-node-and-client)
instead - it doesn't silence RSTs for the whole host.

### 3.4. Test it manually before setting up auto-start

```bash
sudo /root/openflux/openflux --role=exit --mode=l3 --transport=yandex \
  --encryption-key-file /root/openflux/encryption-key \
  --url "$(cat /root/openflux/document-url)" --debug
```

If you don't see errors in the log and it prints something like "Running as
EXIT NODE", you're good - stop it with `Ctrl+C` and move on. If there are
errors, check the "Common problems" section below.

### 3.5. Set up systemd for auto-start

Create `/etc/systemd/system/openflux.service`:

```bash
sudo tee /etc/systemd/system/openflux.service > /dev/null <<'EOF'
[Unit]
Description=OpenFlux encrypted exit node (l3, Yandex transport)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStartPre=/bin/sh -c '/usr/sbin/iptables -C OUTPUT -p tcp --tcp-flags RST RST -j DROP 2>/dev/null || /usr/sbin/iptables -I OUTPUT 1 -p tcp --tcp-flags RST RST -j DROP'
ExecStart=/bin/sh -c 'exec /root/openflux/openflux --role=exit --mode=l3 --transport=yandex --encryption-key-file /root/openflux/encryption-key --url "$(cat /root/openflux/document-url)"'
ExecStopPost=/bin/sh -c '/usr/sbin/iptables -C OUTPUT -p tcp --tcp-flags RST RST -j DROP 2>/dev/null && /usr/sbin/iptables -D OUTPUT -p tcp --tcp-flags RST RST -j DROP || true'
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=read-only
ProtectSystem=strict
ReadOnlyPaths=/root/openflux

[Install]
WantedBy=multi-user.target
EOF
```

Start it and enable it to survive reboots:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now openflux
sudo systemctl status openflux
```

`Active: active (running)` means the exit node is up. Watch live logs with:

```bash
sudo journalctl -u openflux -f
```

The server side is done. Now let's set up the phone.

## Step 4. Install the Android app

1. Open the [releases page](https://github.com/damnurmum/OpenFlux-Android/releases/latest)
   on your phone and download the right APK:
   - most modern phones (2018 or newer) → `OpenFlux-android-arm64-v8a-release.apk`;
   - older 32-bit ARM devices → `OpenFlux-android-armeabi-v7a-release.apk`;
   - not sure → download `OpenFlux-android-universal-release.apk`, it works
     on any device, just a bit larger.
2. Android may ask you to allow installs "from unknown sources" for whichever
   app you used to download the file (browser, Telegram, etc.) - approve it,
   this is standard for any APK that isn't from Google Play.
3. Open the OpenFlux app and go to the **Settings** tab - it's a vertical
   list, tap an item to open it and the back arrow (top-left) or your phone's
   back gesture to return.
4. Tap **Transport** and fill in:
   - the first field - the document link from Step 1;
   - the second field - the encryption secret from Step 2 (the exact same
     value you saved into `encryption-key` on the server). If you haven't
     picked one yet, tap **"Generate secure key"**, copy the value, and paste
     it into the `encryption-key` file on the server (Step 3.2).
5. You can leave **Network** (DNS server, MTU) alone - the defaults work for
   most setups. The default DNS uses `1.1.1.1` through the exit node, which must
   support UDP. A custom DNS server must be a numeric IPv4 address reachable
   from the exit node.
6. Go back to the **Home** tab and tap **"Start Tunnel"**. Android will show
   its standard system prompt to set up a VPN connection - confirm it (this is a
   generic Android dialog, not something specific to OpenFlux).
7. If everything's set up correctly, the status switches to **"Connected"**.
   If something's off, open the **Logs** tab for the technical details.

Done - your phone's traffic now goes through your own server.

Don't want a full system tunnel? Under **Settings -> Mode of operation**, switch
to **Proxy (SOCKS5)** instead - it uses the same document link and secret, runs
as a local SOCKS5 server on the phone with no tunnel permission prompt, and can
optionally be exposed to your local network (with a login/password) so another
device can use it too.

## Also handy: the desktop client

OpenFlux can also run as a SOCKS5 client on a computer (Linux/macOS/Windows)
- see the main [README.md](../../README.md#desktop-cli-exit-node-and-client)
for details. Short version: it's the same binary you used on the server,
just with `--role=client` instead of `--role=exit`, and you point your browser at
the SOCKS5 proxy `127.0.0.1:1080`.

## Common problems

**"document URL" error when starting the server.**
Check that `document-url` isn't empty and doesn't have stray whitespace or
extra newlines. If you're using encryption, check the same for
`encryption-key`, and that the secret is at least 16 characters.

**Do I need to set the encryption key on the server if it's already in the
app?** Yes, if you chose to enable encryption - the server and client must
agree on the same secret, or the connection won't come up. On the server
it's passed via `--encryption-key-file` (see step 3.2); in the app it's the
"Settings" → "TRANSPORT" field. The key is optional: leave the app's field
empty and skip `--encryption-key-file` on the server to run the tunnel
unencrypted, like a plain upstream exit node.

**The app connects but immediately drops / no ping.** This usually means the
RST-drop rule (step 3.3) isn't active on the server - the iptables rule
resets on server reboot unless it's reapplied automatically (our systemd
unit from step 3.5 reapplies it on every start).

**The app crashes or can't fetch document data.** This almost always means
the document is open in the new Yandex Docs editor - go back to step 1 and
switch it to the old editor.

**I updated the APK and my settings disappeared.** Settings only survive an
update over the same application ID and signing certificate. If you had a
debug build from CI installed and switched to a signed release (or vice
versa), Android requires uninstalling the old app first, which wipes saved
data - you'll need to re-enter the link and key.

**Someone else edited the document and everything broke.** That's expected -
anyone with edit access to the document can disrupt the connection. See
"Important limitations" in the [README.md](../../README.md) and
[SECURITY.md](SECURITY.md) for details.

## Where to go next

- Full CLI flag reference and architecture details - [README.md](../../README.md).
- Security model and what to do if your key/link leaks -
  [SECURITY.md](SECURITY.md).
- Found a bug or have an improvement idea - open an issue in the repository;
  report vulnerabilities the way [SECURITY.md](SECURITY.md) describes, not
  in a public issue.
