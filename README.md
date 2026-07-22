# passgen


> [!CAUTION]
> **PUBLIC DEBUG SIGNING INCIDENT:** the former shared debug private key is
> public. Existing debug APKs and continuous debug releases cannot prove
> authorship and are untrusted development artifacts. Only a future APK signed
> by the externally held release key can be an authenticated Understory
> distribution. Tracking: `Zheke32174/understory-common#3`.


Store-facing name: **Understory Keys**. A hardened Android password generator +
local encrypted ledger + migration buffer that sits **beside** your existing
password manager (e.g. Bitwarden) — it does not require, and never seizes, the
autofill slot. **The generated password value never appears on screen anywhere**
— not in a label, not in a toast, not in a text field, not in logcat.

Three delivery modes:

1. **Keyboard (the coexistence path)** — enable the passgen keyboard and switch
   to it on a password field to Generate a new password or Type a saved ledger
   entry directly into the field, bypassing both the clipboard and the autofill
   IPC. Works whether or not another app holds the autofill slot.
2. **Autofill** — if passgen holds the autofill slot, tap the
   "passgen — generate" suggestion above the keyboard. The value is generated in
   a hidden activity and handed to the target field over the autofill IPC, never
   touching the clipboard. passgen leads with *who holds the slot* and never
   nags you to replace an incumbent provider.
3. **Clipboard fallback** — for cases where autofill isn't offered (some
   webviews, terminal apps, banking apps that block autofill). Sets
   `EXTRA_IS_SENSITIVE` on the clip; auto-clears after a configurable timeout —
   **only while passgen's process is running**; if you swipe passgen away first,
   clear the clipboard manually. On Samsung devices the keyboard's clipboard
   panel may keep a copy regardless of the sensitive flag, so keyboard or
   autofill mode is preferred there.

Every password passgen generates and delivers is recorded in a device-encrypted
**receipt ledger** (value stored only if you opt in) so a signup done through
passgen can never silently lock you out, and the ledger imports/exports
Bitwarden CSV+JSON so it is a migration buffer, not a roach motel.

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

Shared modules vendored here for a self-contained build: `common-security/` (+ `common-backup/`, `overlay-*/` where used). The `keystore/` directory contains documentation only; signing private keys are forbidden. **Do not edit shared modules in this repo.** Their canonical home is [`understory-common`](https://github.com/Zheke32174/understory-common); propagate changes with its `tools/sync-common.sh`.

Suite-level docs (SUITE_DESIGN, SUITE_ROADMAP, RELEASE_BLOCKERS, SAMSUNG_QUIRKS, BlackArch defense matrix + runbooks) live in `understory-common`.

## Verify your install

Debug APKs cannot be authenticated as Understory distributions. Their signer is
developer-local, and the former shared debug signer is revoked.

For a future authenticated release, verify the APK certificate with `apksigner`
and require the release fingerprint recorded in
`common-security/.../SuitePins.kt`:

```bash
apksigner verify --print-certs the-downloaded.apk | grep -i 'SHA-256'
```

Expected authenticated release certificate:

`59a3dee7feb8262170e4dcabb3dbe7bc323abe8715ab49f5bed5133046a45c4a`

Certificate verification must be combined with an immutable versioned release,
checksum/provenance verification, and the source commit. No such release receipt
is claimed by this draft.
