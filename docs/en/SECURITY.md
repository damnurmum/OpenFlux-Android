# Security policy

## Reporting a vulnerability

Do not open a public issue for a vulnerability that exposes secrets, permits
traffic decryption, or allows remote compromise. Use GitHub's **Security →
Report a vulnerability** private reporting feature in this repository. If
private vulnerability reporting is disabled, contact the repository owner
privately and include a minimal reproducer.

Do not include real document URLs, encryption keys, server addresses, logs
containing credentials, or private APK signing keys in reports.

## Security model and limitations

- Traffic carried by the document transport is encrypted and authenticated
  with AES-256-GCM. Keys are derived from a shared secret with scrypt.
- The document provider still observes connection metadata, timing, packet
  sizes and encrypted payloads. End-to-end encryption does not hide metadata.
- Anyone with edit access to the document may disrupt availability even when
  they cannot decrypt or forge traffic.
- Android protects the saved URL and shared secret with Android Keystore. They
  are removed when the app data is cleared or the app is uninstalled.
- The current Android client is an experimental IPv4 TCP/UDP implementation. It
  must not be treated as an audited replacement for WireGuard or another
  mature VPN.

Use a unique randomly generated secret, protect VDS files with mode `0600`, do
not commit secrets, and rotate both the document link and key after disclosure.
