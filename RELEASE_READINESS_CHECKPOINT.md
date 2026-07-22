# Release-readiness checkpoint

## Identity

- Repository: `Zheke32174/understory-passgen`
- Checkpoint branch: `security/public-signing-containment-v1`
- Reviewed default head: `cd9ca27ddfa8c5a45bb68c5583f43b1612d44a89`
- Validated complete branch head: `70190369407f5b8295d8e40683b030f0e7a8d044`
- Coordination: `Zheke32174/understory-common#3`

## Last completed scope

Public signing identity, APK publication authority, current-tree key exposure,
install-verification claims, vendored trust primitives, debug assembly, complete
unit tests, security reporting, and licensing presence.

## Resolved on this draft

- Removed the shared public debug private key from the current tree.
- Removed committed debug-signing configuration.
- Revoked debug signatures for authorship, sibling identity, and capabilities.
- Replaced automatic latest-release publication with read-only validation.
- Removed tag force-update and release-asset overwrite authority.
- Corrected install-verification and public-distribution claims.
- Added security guidance, incident provenance, key ignore rules, and a
  deterministic signing-boundary validator that also rejects stale README trust
  claims.
- Replaced an app diagnostic reference to the retired ambiguous certificate-pin
  API with the release-only identity.
- Corrected the password wipe test to require the implementation's actual NUL
  overwrite rather than an unrelated space character.

## Validation receipts

GitHub Actions run `29920374645` passed at exact complete branch head
`70190369407f5b8295d8e40683b030f0e7a8d044`:

- immutable read-only checkout;
- public signing and presentation boundary validation;
- Android SDK provisioning;
- debug APK assembly without a committed suite signing key;
- all unit tests, including the corrected wipe contract;
- durable unit-test receipt upload.

## Changed conclusion

The current source, presentation, build, and test boundary is green. The
repository is not publishable because historical artifacts, release governance,
licensing, and an authorized signed candidate remain unresolved.

## Open blockers

- The key remains reachable in public history and prior artifacts/releases.
- Existing movable tags and release assets need an explicit steward disposition.
- No independently verified signed release candidate exists.
- No immutable versioned publication workflow is approved.
- The repository has no explicit license; no license was invented.
- Offline release-key custody remains unverified.
- Branch rules, secret scanning, push protection, private vulnerability
  reporting, and immutable-release settings need administrative verification.
- All sibling repositories must complete the same exact-head boundary before the
  suite can claim coordinated release identity.

## Reconsideration triggers

New commit, changed CI, newly discovered key material, changed release asset,
license decision, signing rotation, changed public claim, changed repository
visibility, or explicit steward request.

## Next action

Review the remaining sibling receipts, select a source license, and decide the
disposition of prior public debug releases before designing any authenticated
release candidate.
