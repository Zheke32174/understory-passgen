# Kotoba plugin API (v1)

A Kotoba plugin is an **ordinary APK** that exports a bound `Service`
speaking the `IKotobaPlugin` AIDL contract from `:keyboard-plugin-api`.
Plugins run in their own process with their own permissions — the
keyboard never loads plugin code into itself.

## Security model (read this first)

1. **Discovery ≠ execution.** The keyboard lists installed plugins purely
   from `PackageManager`. No plugin code runs until the user flips its
   switch in the plugin manager.
2. **Trust-on-first-use signature pinning.** Enabling records the plugin
   package's signing-cert SHA-256. If a later update is signed with a
   different key the plugin is auto-disabled and flagged; the user must
   explicitly re-trust.
3. **Password fields are off-limits.** The keyboard never invokes any
   plugin while a password-class field is focused.
4. **Minimal data.** A plugin sees only: the current composing word (for
   suggestions), the text being transformed (for a transform action it
   was tapped for), and a [`FieldContext`] carrying the target app's
   package name + input-type class. Never the full text buffer.
5. **INTERNET is surfaced.** The manager warns loudly when a plugin app
   holds the INTERNET permission, since text shared with it could leave
   the device. Permission-free plugins (like the sample) are the norm.
6. **Fault isolation.** Calls run off the IME main thread against a
   short bind timeout; a plugin that throws, hangs, or dies produces
   empty results, never a keyboard crash.

## Writing a plugin

Depend on `:keyboard-plugin-api` (or vendor the AIDL + models), extend
the base class, and declare the service:

```kotlin
class MyPluginService : KotobaPluginService() {
    override fun describe() = PluginManifest(
        apiVersion = PluginContract.API_VERSION,
        pluginId = "my-plugin",
        label = "My Plugin",
        description = "What it does, in one line.",
        capabilities = listOf(PluginContract.CAP_SUGGESTIONS, PluginContract.CAP_ACTIONS),
        actions = listOf(
            QuickAction("emdash", "—", PluginContract.KIND_INSERT),
            QuickAction("rot13", "R13", PluginContract.KIND_TRANSFORM),
        ),
    )

    override fun onGetSuggestions(composing: String, beforeCursor: String, ctx: FieldContext?) =
        listOf("suggestion")

    override fun onPerformAction(actionId: String, selectedText: String, ctx: FieldContext?) =
        when (actionId) {
            "emdash" -> "—"
            "rot13" -> rot13(selectedText)
            else -> null
        }
}
```

```xml
<service
    android:name=".MyPluginService"
    android:exported="true"
    android:label="My Plugin">
    <intent-filter>
        <action android:name="com.understory.keyboard.plugin.SERVICE" />
    </intent-filter>
    <meta-data
        android:name="com.understory.keyboard.plugin.API_VERSION"
        android:value="1" />
</service>
```

### Semantics

- **`describe()`** is called once per session after enable; keep it
  constant and fast.
- **Suggestions** (`CAP_SUGGESTIONS`): called as the user composes a
  word; return up to 3 candidates, or an empty list. Budget: single-digit
  milliseconds.
- **Quick actions** (`CAP_ACTIONS`): rendered as chips on the strip.
  `KIND_INSERT` actions receive empty `selectedText` and their return
  value is committed at the cursor. `KIND_TRANSFORM` actions receive the
  selection (or the word being composed) and the return value replaces
  it. Return `null` to do nothing.
- Callbacks arrive on binder threads — do not touch your UI directly, and
  never block.

### Verifying against the reference implementation

`:keyboard-plugin-sample` is a complete, permission-free plugin
exercising every capability; its pure logic is unit-tested in
`SampleTransformsTest`. If your plugin mirrors its structure, it will
work.

## Versioning

`PluginContract.API_VERSION` is 1. The keyboard skips services whose
`API_VERSION` meta-data or `PluginManifest.apiVersion` doesn't match; a
future v2 will keep v1 callable.
