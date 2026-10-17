package com.nothing.mobilecore.platform.storage

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileSecretIntegrityTest {
    @Test
    fun resolve_detectsCorruptedStoredSecret() {
        val result = ProfileSecretIntegrity.resolve(
            profileId = "p1",
            rawSecretExists = true,
            stored = null,
            inline = ProfileSecrets(profileId = "p1", uuid = null),
        )
        assertTrue(result.purgeSecureStore)
        assertNull(result.secrets)
        assertFalse(result.migrateInlineToSecureStore)
    }

    @Test
    fun resolve_rejectsMismatchedProfileId() {
        val result = ProfileSecretIntegrity.resolve(
            profileId = "p1",
            rawSecretExists = true,
            stored = ProfileSecrets(profileId = "p2", uuid = "u"),
            inline = ProfileSecrets(profileId = "p1", uuid = "inline"),
        )
        assertTrue(result.purgeSecureStore)
        assertNull(result.secrets)
    }

    @Test
    fun resolve_migratesInlineSecretsWhenStoreEmpty() {
        val inline = ProfileSecrets(profileId = "p1", uuid = "u")
        val result = ProfileSecretIntegrity.resolve(
            profileId = "p1",
            rawSecretExists = false,
            stored = null,
            inline = inline,
        )
        assertFalse(result.purgeSecureStore)
        assertTrue(result.migrateInlineToSecureStore)
        assertTrue(result.secrets?.uuid == "u")
    }
}
