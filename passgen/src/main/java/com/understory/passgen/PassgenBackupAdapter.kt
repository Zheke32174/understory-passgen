package com.understory.passgen

import com.understory.backup.BackupAdapter

/**
 * Passgen's [BackupAdapter] — exposes the unlocked vault's user-saved
 * credentials as an exportable payload, and accepts an importable
 * payload for restore-merge.
 *
 * Unlike [com.understory.aegis.AegisBackupAdapter], this is a CLASS
 * not an object: passgen's [UnlockedVault] is held inside
 * [VaultActivity] rather than a process-singleton, so the adapter
 * gets the vault by constructor injection at the point the export /
 * import Activity actually runs. The orchestrator (eventual backups
 * app, or in-app export UI) instantiates this with the live vault
 * after the user authenticates via BiometricPrompt.
 *
 * Payload format (schemaVersion = 1) reuses [Vault.serialize] /
 * [Vault.parse] — the on-disk plaintext shape — minus the
 * snake-eats-tail master-KEK entry. The master is recovery metadata
 * for *this* specific vault on *this* device; carrying it into a
 * backup that may be restored on a different device leaves a stale
 * "passgen vault master" entry in the new vault containing the OLD
 * KEK that's no longer the actual master. We filter it out at export
 * and refuse to accept any incoming entry with that title at import,
 * so backups never poison the master-entry slot of the destination.
 *
 * Import semantics: **merge with dedup by (title, username), prefer
 * existing**. Reasoning matches aegis:
 *   - The user's current entries are known-good and may be in active
 *     use. An import shouldn't destroy them in favor of a possibly-
 *     stale backup.
 *   - (title, username) tuple is the natural identity for a credential.
 *     Same title with different username = different credential (work
 *     vs personal Gmail). Empty-title-and-empty-username is treated
 *     as always-distinct.
 *   - On collision, existing entry wins. Re-importing a backup never
 *     overwrites a current password.
 *
 * Returns a human-readable summary string for the caller's
 * Toast/snackbar surface.
 */
class PassgenBackupAdapter(private val vault: UnlockedVault) : BackupAdapter {

    override val appId: String = "com.understory.passgen"

    /**
     * Bump only on incompatible JSON shape changes. Backwards-compatible
     * additions (a new optional field with sensible defaults) don't
     * require a bump — readers tolerate unknown fields per existing
     * Vault.parse semantics.
     */
    override val schemaVersion: Int = 1

    override fun export(): ByteArray {
        // Filter the snake-eats-tail master-KEK entry. It's recovery
        // metadata for *this* vault, not user data; including it
        // would leave an orphaned "passgen vault master" entry in any
        // restored vault containing a stale KEK.
        val exportable = vault.contents.entries
            .filter { it.title != Vault.MASTER_ENTRY_TITLE }
        val exportContents = VaultContents(exportable)
        return Vault.serialize(exportContents).toByteArray(Charsets.UTF_8)
    }

    override fun import(payload: ByteArray, schemaVersion: Int): String {
        require(schemaVersion <= this.schemaVersion) {
            "payload schemaVersion=$schemaVersion is newer than this build supports " +
                "(${this.schemaVersion}); upgrade passgen before importing"
        }

        val payloadText = payload.toString(Charsets.UTF_8)
        val incoming = Vault.parse(payloadText)

        val existingKeys = vault.contents.entries
            .filter { it.title.isNotEmpty() || it.username.isNotEmpty() }
            .map { it.title to it.username }
            .toHashSet()

        val toAdd = mutableListOf<VaultEntry>()
        var skippedDuplicates = 0
        var skippedMaster = 0
        for (entry in incoming.entries) {
            // Refuse to import any entry claiming to be a master entry —
            // backups must not poison the destination's master slot.
            if (entry.title == Vault.MASTER_ENTRY_TITLE) {
                skippedMaster++
                continue
            }
            val key = entry.title to entry.username
            val isAnonymous = entry.title.isEmpty() && entry.username.isEmpty()
            if (!isAnonymous && key in existingKeys) {
                skippedDuplicates++
            } else {
                toAdd += entry
            }
        }

        if (toAdd.isNotEmpty()) {
            vault.contents = vault.contents.copy(
                entries = vault.contents.entries + toAdd,
            )
            vault.save()
        }

        return buildString {
            append("Imported ${toAdd.size} new ")
            append(if (toAdd.size == 1) "entry" else "entries")
            if (skippedDuplicates > 0) {
                append("; $skippedDuplicates duplicate")
                if (skippedDuplicates != 1) append("s")
                append(" kept existing")
            }
            if (skippedMaster > 0) {
                append("; $skippedMaster master-entry impostor")
                if (skippedMaster != 1) append("s")
                append(" refused")
            }
            append(". ${vault.contents.entries.size} total.")
        }
    }
}
