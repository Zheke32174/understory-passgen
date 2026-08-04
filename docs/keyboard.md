# Kotoba Keyboard (`:keyboard`)

A brand-new, from-scratch Android IME in the Understory mold, born from a
study of FlorisBoard and the AOSP LatinIME lineage (HeliBoard — the open
ancestor line Gboard grew from; Gboard itself is closed-source). Neither
reference ships an out-of-process plugin mechanism — FlorisBoard's
".flex" extensions are in-process archives, HeliBoard is monolithic — so
Kotoba's differentiator is exactly that: **a real plugin system** (see
[keyboard-plugin-api.md](keyboard-plugin-api.md)).

## What's implemented (v0.1)

- `KeyboardService` — `InputMethodService` with composing-text handling,
  auto-capitalization (via `getCursorCapsMode`), password-field detection,
  suggestion strip, and the plugin bridge.
- `KotobaKeyboardView` — custom Canvas-drawn keyboard (NOT the deprecated
  `android.inputmethodservice.KeyboardView`): QWERTY + two symbol pages,
  shift with double-tap caps-lock, delete auto-repeat, long-press space
  for the system IME picker.
- `SuggestionEngine` — pure-Kotlin local frequency dictionary: seeded with
  a small English list, learns committed words (optional), bounded at
  4000 entries, persisted only in private prefs.
- `MainActivity` — Compose settings + the plugin manager (suite design
  system, `UnderstoryTheme`).

## Privacy / hardening posture

| Surface | Stance |
| --- | --- |
| Network | The keyboard process holds **no network permission**; the suite-wide comms-permission strip (`tools:node="remove"`) makes it impossible for a transitive library to add one. |
| Password fields | Locked-down mode: no suggestions, no learning, no plugins, `FLAG_SECURE` on the IME window. |
| Learned words | Private prefs only; excluded from cloud backup **and** device transfer by `data_extraction_rules.xml`. |
| Plugins | Off by default per-plugin; user opt-in pins the plugin's signing cert (TOFU); tamper hard-fail (`common-security` `Tamper`) disables the whole plugin subsystem while typing keeps working. |
| Package visibility | `QUERY_ALL_PACKAGES` stripped; a `<queries>` intent exposes exactly the set of installed plugin services. |

## Modules

| Module | Type | Purpose |
| --- | --- | --- |
| `:keyboard` | app | The IME itself (`com.understory.keyboard`). |
| `:keyboard-plugin-api` | library | AIDL contract + `KotobaPluginService` base class plugins build against. |
| `:keyboard-plugin-sample` | app | Reference plugin (case transforms, shrug, demo completions); doubles as the end-to-end test target for the plugin path. |

## Build & try

```bash
gradle :keyboard:assembleDebug :keyboard-plugin-sample:assembleDebug
adb install keyboard/build/outputs/apk/debug/kotoba-keyboard-debug.apk
adb install keyboard-plugin-sample/build/outputs/apk/debug/kotoba-plugin-sample-debug.apk
```

Then: system Settings → enable "Kotoba Keyboard" → open the Kotoba app →
Plugins → enable "Kotoba Sample Tools" → switch to Kotoba in any text
field. Type `kot` to see plugin completions; select text and tap the
`AA` chip to uppercase it; tap the shrug chip to insert it.

## Deliberate non-goals for v0.1

Multilingual layouts/subtypes, glide typing, emoji panel, clipboard
history, themes-as-extensions. The layout tables (`KeyLayouts`) and the
strip renderer are the seams where those grow later.
