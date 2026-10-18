package com.nothing.mobilecore.platform.storage

import com.nothing.mobilecore.platform.AppPreferences
import com.nothing.mobilecore.platform.AppSettings
import com.nothing.mobilecore.platform.AutomationSettings
import com.nothing.mobilecore.platform.ProfileRepositoryAndroid
import com.nothing.mobilecore.platform.RoutingRuleSet
import com.nothing.mobilecore.shared.domain.RoutingMode
import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.VlessProfile
import com.nothing.mobilecore.shared.domain.VlessSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Обратимый «шифр» для тестов; умеет имитировать потерю ключа и сбой записи. */
private class FakeCipher : VaultCipher {
    var keyLost = false
    var failEncrypt = false
    override fun encrypt(plain: ByteArray): ByteArray {
        if (failEncrypt) throw IOException("disk full")
        return byteArrayOf(0x7F) + plain.map { (it.toInt() xor 0x5A).toByte() }
    }
    override fun decrypt(sealed: ByteArray): ByteArray {
        if (keyLost || sealed.firstOrNull() != 0x7F.toByte()) throw IllegalStateException("bad key")
        return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}

private class FakeLegacy(var data: StoredVault?) : LegacySource {
    var unreadable = false
    var deleted = false
    override fun read(): StoredVault? {
        if (unreadable) throw IllegalStateException("keystore")
        return if (deleted) null else data
    }
    override fun delete() {
        deleted = true
    }
}

private class FakeLegacySettings(var data: AppSettings?) : LegacySettingsSource {
    var deleted = false
    var reads = 0
    override fun read(): AppSettings? {
        reads++
        return if (deleted) null else data
    }
    override fun delete() {
        deleted = true
    }
}

class VaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val cipher = FakeCipher()
    private val profile = VlessProfile(
        id = "p1",
        name = "main",
        uuid = "12345678-1234-4123-8123-123456789abc",
        host = "example.com",
        port = 443,
        security = VlessSecurity.REALITY,
        realityPublicKey = "pbk",
        realityShortId = "sid",
    )
    private val legacyVault = StoredVault(
        profiles = listOf(profile),
        activeProfileId = "p1",
        policy = TunnelPolicy(singleConnectAttempt = false),
    )

    private fun vaultFile() = VaultFile(tmp.root.resolve("vault/state.bin"), cipher)

    @Test
    fun file_roundTripsAndLeavesNoTempFile() {
        val file = vaultFile()
        file.write("hello".encodeToByteArray())
        file.write("world".encodeToByteArray())

        assertEquals("world", file.read()!!.decodeToString())
        assertFalse(tmp.root.resolve("vault/state.bin.tmp").exists())
        // На диске — шифротекст, а не открытый текст.
        assertFalse(tmp.root.resolve("vault/state.bin").readText().contains("world"))
    }

    @Test
    fun file_failedWriteKeepsPreviousVersion() {
        val file = vaultFile()
        file.write("old".encodeToByteArray())
        cipher.failEncrypt = true

        runCatching { file.write("new".encodeToByteArray()) }

        cipher.failEncrypt = false
        assertEquals("old", file.read()!!.decodeToString())
    }

    @Test
    fun bootstrap_freshInstall() {
        val (vault, outcome) = VaultBootstrap(vaultFile(), FakeLegacy(null)).load()
        assertEquals(BootstrapOutcome.FRESH, outcome)
        assertEquals(StoredVault(legacySettingsImported = true), vault)
    }

    @Test
    fun bootstrap_migratesWithoutDeletingLegacyThenDeletesOnNextStart() {
        val legacy = FakeLegacy(legacyVault)

        val (first, firstOutcome) = VaultBootstrap(vaultFile(), legacy).load()
        assertEquals(BootstrapOutcome.MIGRATED_FROM_LEGACY, firstOutcome)
        assertEquals(legacyVault.profiles, first.profiles)
        assertEquals("p1", first.activeProfileId)
        assertFalse(first.policy.singleConnectAttempt)
        assertFalse("старые данные остаются до следующего запуска", legacy.deleted)

        val (second, secondOutcome) = VaultBootstrap(vaultFile(), legacy).load()
        assertEquals(BootstrapOutcome.LOADED_AND_LEGACY_DELETED, secondOutcome)
        assertTrue(legacy.deleted)
        assertFalse(second.legacyCleanupPending)
        assertEquals(legacyVault.profiles, second.profiles)

        val (_, thirdOutcome) = VaultBootstrap(vaultFile(), legacy).load()
        assertEquals(BootstrapOutcome.LOADED, thirdOutcome)
    }

    @Test
    fun bootstrap_writeFailureKeepsLegacyAndDataInMemory() {
        val legacy = FakeLegacy(legacyVault)
        cipher.failEncrypt = true

        val (vault, outcome) = VaultBootstrap(vaultFile(), legacy).load()

        assertEquals(BootstrapOutcome.WRITE_FAILED, outcome)
        assertEquals(legacyVault.profiles, vault.profiles)
        assertTrue(vault.legacyMigrationIncomplete)
        assertFalse(legacy.deleted)
    }

    @Test
    fun bootstrap_unreadableLegacyIsMergedLaterWithoutLosingNewData() = runBlocking {
        val legacy = FakeLegacy(legacyVault).apply { unreadable = true }
        val (vault, outcome) = VaultBootstrap(vaultFile(), legacy).load()
        assertEquals(BootstrapOutcome.LEGACY_UNREADABLE, outcome)
        assertTrue(vault.profiles.isEmpty())

        // Пользователь успел импортировать новый профиль, пока старые данные не читались.
        val store = VaultStore(vaultFile()).apply { initialize(vault) }
        val fresh = profile.copy(id = "p2", name = "new")
        ProfileRepositoryAndroid(store).upsert(fresh)

        legacy.unreadable = false
        val (merged, mergedOutcome) = VaultBootstrap(vaultFile(), legacy).load()
        assertEquals(BootstrapOutcome.MERGED_LATE_LEGACY, mergedOutcome)
        assertEquals(listOf("p2", "p1"), merged.profiles.map { it.id })
        assertEquals("p1", merged.activeProfileId)
        assertFalse(merged.legacyMigrationIncomplete)
        assertTrue(merged.legacyCleanupPending)
    }

    @Test
    fun bootstrap_unreadableVaultIsQuarantinedNotDeleted() {
        vaultFile().write(StoredVault.encode(legacyVault))
        cipher.keyLost = true

        val (vault, outcome) = VaultBootstrap(vaultFile(), FakeLegacy(null)).load()

        assertEquals(BootstrapOutcome.VAULT_UNREADABLE_QUARANTINED, outcome)
        assertTrue(vault.profiles.isEmpty())
        assertEquals(1, tmp.root.resolve("vault").listFiles()!!.count { it.name.startsWith("state.bin.corrupt-") })
    }

    @Test
    fun repository_persistsChangesAndReadsDoNotWrite() = runBlocking {
        val store = VaultStore(vaultFile()).apply { initialize(StoredVault()) }
        val repo = ProfileRepositoryAndroid(store)

        repo.upsert(profile)
        repo.setActiveProfileId("p1")
        repo.listProfiles()

        val onDisk = StoredVault.decode(vaultFile().read()!!)
        assertEquals(listOf(profile), onDisk.profiles)
        assertEquals("p1", onDisk.activeProfileId)

        repo.delete("p1")
        val afterDelete = StoredVault.decode(vaultFile().read()!!)
        assertTrue(afterDelete.profiles.isEmpty())
        assertNull(afterDelete.activeProfileId)
    }

    @Test
    fun repository_failedWriteIsReportedAndStateUnchanged() = runBlocking {
        val store = VaultStore(vaultFile()).apply { initialize(StoredVault()) }
        val repo = ProfileRepositoryAndroid(store)
        cipher.failEncrypt = true

        val result = runCatching { repo.upsert(profile) }

        assertTrue(result.isFailure)
        assertTrue(repo.listProfiles().isEmpty())
    }

    @Test
    fun readOnlyStore_rejectsWrites() = runBlocking {
        vaultFile().write(StoredVault.encode(legacyVault))
        val store = VaultStore(vaultFile()).apply { initializeReadOnly() }

        val result = runCatching { ProfileRepositoryAndroid(store).upsert(profile.copy(id = "x")) }

        assertTrue(result.isFailure)
        // Данные на диске не тронуты.
        assertEquals(legacyVault.profiles, StoredVault.decode(vaultFile().read()!!).profiles)
    }

    @Test
    fun legacyDecoder_restoresSecretsFromSecureStoreAndInline() {
        val listJson = """[
            {"id":"a","name":"A","uuid":"","host":"h","port":1,"security":"reality"},
            {"id":"b","name":"B","uuid":"11111111-1111-4111-8111-111111111111","host":"h","port":1},
            {"id":"c","name":"C","uuid":"","host":"h","port":1}
        ]"""
        val secrets = mapOf(
            "profile_secret:a" to """{"profileId":"a","uuid":"22222222-2222-4222-8222-222222222222","realityPublicKey":"k","realityShortId":"s"}""",
            "profile_secret:c" to "not-json",
        )

        val vault = LegacyDecoder.decode(listJson, "a", """{"failClosed":false}""") { secrets[LegacyDecoder.secretKey(it)] }!!

        val byId = vault.profiles.associateBy { it.id }
        assertEquals("22222222-2222-4222-8222-222222222222", byId.getValue("a").uuid)
        assertEquals("k", byId.getValue("a").realityPublicKey)
        assertEquals("11111111-1111-4111-8111-111111111111", byId.getValue("b").uuid)
        assertEquals("", byId.getValue("c").uuid)
        assertEquals("a", vault.activeProfileId)
        assertFalse(vault.policy.singleConnectAttempt)
    }

    @Test
    fun legacyDecoder_returnsNullWhenNothingStored() {
        assertNull(LegacyDecoder.decode(null, null, null) { null })
    }

    @Test
    fun bootstrap_upgradesSchemaV1SplittingExcludedList() {
        // Хранилище v1: в режиме «в обход» общий список означал исключения.
        val v1 = """{"schemaVersion":1,"policy":{"routingMode":"PER_APP_EXCLUDED","allowedPackageNames":["a.b","c.d"]}}"""
        vaultFile().write(v1.encodeToByteArray())

        val (vault, outcome) = VaultBootstrap(vaultFile(), FakeLegacy(null)).load()

        assertEquals(BootstrapOutcome.LOADED, outcome)
        assertEquals(StoredVault.CURRENT_SCHEMA, vault.schemaVersion)
        assertTrue(vault.policy.includedPackageNames.isEmpty())
        assertEquals(listOf("a.b", "c.d"), vault.policy.excludedPackageNames)
        // Новая схема записана на диск.
        assertEquals(vault, StoredVault.decode(vaultFile().read()!!))
    }

    private val legacySettings = AppSettings(
        automation = AutomationSettings(autoReconnect = true, trustedWifiNames = setOf("home")),
        routingRuleSets = listOf(RoutingRuleSet("work", RoutingMode.PER_APP_ALLOWED, listOf("a.b"))),
        connectionRequested = true,
    )

    @Test
    fun bootstrap_importsLegacySettingsOnceAndDeletesOldFile() {
        val source = FakeLegacySettings(legacySettings)

        val (vault, _) = VaultBootstrap(vaultFile(), FakeLegacy(null), source).load()

        assertEquals(legacySettings, vault.settings)
        assertTrue(vault.legacySettingsImported)
        assertTrue(source.deleted)
        assertEquals(vault, StoredVault.decode(vaultFile().read()!!))

        // Повторная загрузка не читает старый источник и не затирает новые значения.
        val (second, _) = VaultBootstrap(vaultFile(), FakeLegacy(null), source).load()
        assertEquals(1, source.reads)
        assertEquals(legacySettings, second.settings)
    }

    @Test
    fun bootstrap_keepsLegacySettingsWhenWriteFails() {
        val source = FakeLegacySettings(legacySettings)
        cipher.failEncrypt = true

        val (vault, _) = VaultBootstrap(vaultFile(), FakeLegacy(null), source).load()

        assertFalse(vault.legacySettingsImported)
        assertFalse(source.deleted)
    }

    @Test
    fun preferences_writesAreAppliedInOrderAndPersisted() = runBlocking {
        val store = VaultStore(vaultFile())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val preferences = AppPreferences(store, scope)
            // Записи до загрузки хранилища ждут её и не теряются.
            preferences.setAutoStart(true)
            preferences.saveRoutingRuleSet(RoutingRuleSet("one", RoutingMode.PER_APP_ALLOWED, listOf("a")))
            preferences.saveRoutingRuleSet(RoutingRuleSet("two", RoutingMode.PER_APP_EXCLUDED, listOf("b")))
            preferences.deleteRoutingRuleSet("one")
            preferences.connectionRequested = true
            store.initialize(StoredVault())

            val settings = withTimeout(5_000) { preferences.settings.first { it.connectionRequested } }

            assertTrue(settings.automation.autoStartAfterBoot)
            assertEquals(listOf("two"), settings.routingRuleSets.map { it.name })
            assertEquals(settings, StoredVault.decode(vaultFile().read()!!).settings)
        } finally {
            scope.cancel()
        }
    }
}
