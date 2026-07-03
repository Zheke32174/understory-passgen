package com.understory.passgen

import com.understory.security.BaseCapabilityProvider

/**
 * passgen's capability beacon. Consumers translate
 * `(com.understory.passgen, version=1)` into [SuiteCapability.IDENTITY_VAULT]
 * via their KNOWN_PEERS table — passgen does not advertise the meaning
 * itself. Bump [providedVersion] when this app gains a new capability
 * other apps should learn about.
 */
class SuiteCapsProvider : BaseCapabilityProvider() {
    override val providedVersion: Int = 1
}
