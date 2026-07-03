# passgen

A hardened Android password generator. **The generated password value never
appears on screen anywhere** — not in a label, not in a toast, not in a text
field, not in logcat. Two delivery modes:

1. **Autofill (preferred)** — passgen registers as an Android Autofill provider.
   On any password field, tap the "passgen — generate" suggestion above the
   keyboard. The value is generated in a hidden activity and handed to the
   target field over the autofill IPC. Never touches the clipboard. Bypasses
   Samsung Keyboard's clipboard panel entirely.
2. **Clipboard fallback** — for cases where autofill isn't offered (some
   webviews, terminal apps, banking apps that block autofill). Sets
   `EXTRA_IS_SENSITIVE` on the clip; auto-clears after a configurable timeout.
   On Samsung devices the keyboard's clipboard panel may keep a copy regardless
   of the sensitive flag — autofill is preferred.

## Hardening

- `FLAG_SECURE` on every activity (no screenshots, no screen recording, no casting, no external display)
- `SecureRandom`; password held in a `CharArray` and wiped after the single use
- The fill-activity is `windowNoDisplay` + translucent + `noHistory` — never renders a surface
- The autofill suggestion presentation is a fixed neutral string, never the value
- No `INTERNET`, `SMS`, telephony, satellite, Bluetooth, NFC, location, or contacts permissions — every comms permission is explicitly stripped at the manifest level (`tools:node="remove"`) so transitive libraries cannot silently add one
- `network-security-config` denies cleartext for every domain (defense in depth)
- `usesCleartextTraffic="false"`
- `allowBackup="false"`, `data-extraction-rules` exclude every domain
- Proguard removes `Log.*` calls in release builds
- No code path logs, prints, or renders the password value (verifiable via grep over the `chars` symbol)

## Build

Requires JDK 17+ and the Android SDK with platform 35 + build-tools 35.0.0.

```bash
# Set sdk.dir in local.properties (see local.properties.example)
gradle :passgen:assembleDebug
# APK output:
ls passgen/build/outputs/apk/debug/passgen-debug.apk
```

## Install on a device

```bash
adb install -r passgen/build/outputs/apk/debug/passgen-debug.apk
```

## What's persisted

Only the generation **shape** — length, which character classes, auto-clear
seconds. No password value is ever persisted. Stored in plain
`SharedPreferences` (these are not secrets).

## Provenance & suite

Split 2026-07-02 from `Zheke32174/underward` `android/` (commit `f867493`) into per-app repos — one repo per suite app.

Part of the **Understory Suite** — rootless, in-bounds, local-first Android security apps (design constraints: no root, no Shizuku, public APIs only, zero network unless explicitly opted in).

Shared modules vendored here for a self-contained build: `common-security/` (+ `common-backup/`, `overlay-*/` where used) and `keystore/` (pinned suite debug keystore — cert digest is the Tamper/SuiteAttestation pin). **Do not edit shared modules in this repo.** Their canonical home is [`understory-common`](https://github.com/Zheke32174/understory-common); propagate changes with its `tools/sync-common.sh`.

Suite-level docs (SUITE_DESIGN, SUITE_ROADMAP, RELEASE_BLOCKERS, SAMSUNG_QUIRKS, BlackArch defense matrix + runbooks) live in `understory-common`.

## Verify your install

Before trusting the app, confirm the APK you are about to install (or did install) is signed by the suite key. With Android build-tools on any machine:

```bash
apksigner verify --print-certs the-downloaded.apk | grep -i 'SHA-256'
```

The signer certificate SHA-256 digest must be exactly one of the two suite pins (single source of truth: `common-security/.../SuitePins.kt`):

- **Debug** builds (CI artifacts; committed suite debug keystore): `aba68a81a0d63b5549794e586875a4f04e6dba3a6fe25d363e04eb75f46df69e`
- **Release** builds (offline release keystore): `59a3dee7feb8262170e4dcabb3dbe7bc323abe8715ab49f5bed5133046a45c4a`

Any other digest means the APK was not signed by the suite keys — do not install it. The apps also enforce these pins at runtime (Tamper self-check + SuiteAttestation cross-check of installed siblings), but verifying before install is the stronger position. Signing doctrine: `docs/SIGNING.md` in understory-common.
