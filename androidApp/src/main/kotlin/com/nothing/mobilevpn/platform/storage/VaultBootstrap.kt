package com.nothing.mobilecore.platform.storage

import com.nothing.mobilecore.platform.AppSettings

/** Данные из старого хранилища (EncryptedSharedPreferences), если оно есть. */
internal interface LegacySource {
    /** null — старых данных нет. Исключение — данные есть, но прочитать их не удалось. */
    fun read(): StoredVault?
    fun delete()
}

/** Настройки из старого открытого SharedPreferences, если он есть. */
internal interface LegacySettingsSource {
    /** null — старых настроек нет. */
    fun read(): AppSettings?
    fun delete()
}

internal object NoLegacySettings : LegacySettingsSource {
    override fun read(): AppSettings? = null
    override fun delete() = Unit
}

/** Итог загрузки — для журнала событий. */
internal enum class BootstrapOutcome {
    LOADED,
    LOADED_AND_LEGACY_DELETED,
    MIGRATED_FROM_LEGACY,
    MERGED_LATE_LEGACY,
    FRESH,
    VAULT_UNREADABLE_QUARANTINED,
    LEGACY_UNREADABLE,
    WRITE_FAILED,
}

/**
 * Загрузка хранилища при старте процесса с двухфазной миграцией:
 *
 * 1. Хранилища нет, старые данные есть → перенести, записать, проверить обратным чтением.
 *    Старые данные **не удаляются**, ставится `legacyCleanupPending`.
 * 2. При следующем запуске хранилище успешно расшифровалось и `legacyCleanupPending` →
 *    теперь старые данные можно удалить.
 *
 * Если старые данные не прочитались, в памяти ставится `legacyMigrationIncomplete`: даже если
 * пользователь успеет что-то сохранить, при следующих запусках недостающие профили будут
 * подмешаны. [load] не бросает исключений — сбой выражается в [BootstrapOutcome].
 */
internal class VaultBootstrap(
    private val file: VaultFile,
    private val legacy: LegacySource,
    private val legacySettings: LegacySettingsSource = NoLegacySettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun load(): Pair<StoredVault, BootstrapOutcome> {
        val (vault, outcome) = loadVault()
        return importLegacySettings(vault) to outcome
    }

    /**
     * Однократный перенос настроек из открытого SharedPreferences. Старый файл удаляется только
     * после успешной записи; если запись не удалась — повторим при следующем запуске.
     */
    private fun importLegacySettings(vault: StoredVault): StoredVault {
        if (vault.legacySettingsImported) return vault
        val imported = runCatching { legacySettings.read() }.getOrElse { return vault }
        val updated = vault.copy(settings = imported ?: vault.settings, legacySettingsImported = true)
        return runCatching { persist(updated) }.fold(
            onSuccess = {
                if (imported != null) runCatching { legacySettings.delete() }
                it
            },
            onFailure = { vault },
        )
    }

    private fun loadVault(): Pair<StoredVault, BootstrapOutcome> {
        if (!file.exists()) return migrateOrFresh(unreadableVault = false)
        val vault = runCatching { file.read()?.let(StoredVault::decode) }.getOrNull()
            ?: run {
                // Файл есть, но не расшифровался (ключ Keystore потерян, файл повреждён).
                file.quarantine(clock())
                return migrateOrFresh(unreadableVault = true)
            }
        val current = upgradeSchema(vault)
        return when {
            current.legacyMigrationIncomplete -> mergeLateLegacy(current)
            current.legacyCleanupPending -> cleanupLegacy(current)
            else -> current to BootstrapOutcome.LOADED
        }
    }

    /** Миграция схемы; не записалось — не страшно, в памяти уже новая схема, запишется при изменении. */
    private fun upgradeSchema(vault: StoredVault): StoredVault {
        val upgraded = vault.upgraded()
        if (upgraded != vault) runCatching { persist(upgraded) }
        return upgraded
    }

    private fun cleanupLegacy(vault: StoredVault): Pair<StoredVault, BootstrapOutcome> {
        if (runCatching { legacy.delete() }.isFailure) return vault to BootstrapOutcome.LOADED
        // Не записали снятый флаг — не страшно: при следующем запуске повторное удаление ничего не сделает.
        val cleaned = vault.copy(legacyCleanupPending = false)
        return runCatching { persist(cleaned) }.fold(
            onSuccess = { it to BootstrapOutcome.LOADED_AND_LEGACY_DELETED },
            onFailure = { vault to BootstrapOutcome.LOADED },
        )
    }

    /** Старые данные наконец прочитались: добавляем профили, которых в хранилище ещё нет. */
    private fun mergeLateLegacy(vault: StoredVault): Pair<StoredVault, BootstrapOutcome> {
        val legacyVault = runCatching { legacy.read() }.getOrElse { return vault to BootstrapOutcome.LEGACY_UNREADABLE }
            ?: return runCatching { persist(vault.copy(legacyMigrationIncomplete = false)) }
                .getOrDefault(vault) to BootstrapOutcome.LOADED
        val known = vault.profiles.map { it.id }.toSet()
        val merged = vault.copy(
            profiles = vault.profiles + legacyVault.profiles.filterNot { it.id in known },
            activeProfileId = vault.activeProfileId ?: legacyVault.activeProfileId,
            policy = if (vault.policy == StoredVault().policy) legacyVault.policy else vault.policy,
            legacyMigrationIncomplete = false,
            legacyCleanupPending = true,
        )
        // Не удалось записать — остаёмся на загруженном хранилище с флагом, повторим позже.
        return runCatching { persistVerified(merged) }.fold(
            onSuccess = { it to BootstrapOutcome.MERGED_LATE_LEGACY },
            onFailure = { vault to BootstrapOutcome.WRITE_FAILED },
        )
    }

    private fun migrateOrFresh(unreadableVault: Boolean): Pair<StoredVault, BootstrapOutcome> {
        val legacyVault = try {
            legacy.read()
        } catch (_: Exception) {
            return StoredVault(legacyMigrationIncomplete = true) to BootstrapOutcome.LEGACY_UNREADABLE
        }
        if (legacyVault == null) {
            return StoredVault() to
                if (unreadableVault) BootstrapOutcome.VAULT_UNREADABLE_QUARANTINED else BootstrapOutcome.FRESH
        }
        return runCatching { persistVerified(legacyVault.copy(legacyCleanupPending = true)) }.fold(
            onSuccess = { it to BootstrapOutcome.MIGRATED_FROM_LEGACY },
            onFailure = {
                // Не доверяем частично записанному файлу: убираем его, старые данные не тронуты.
                file.quarantine(clock())
                // В памяти — перенесённые данные, чтобы приложение работало; флаг заставит
                // повторить слияние со старыми данными при следующем запуске.
                legacyVault.copy(legacyMigrationIncomplete = true) to BootstrapOutcome.WRITE_FAILED
            },
        )
    }

    private fun persist(vault: StoredVault): StoredVault {
        file.write(StoredVault.encode(vault))
        return vault
    }

    private fun persistVerified(vault: StoredVault): StoredVault {
        persist(vault)
        check(file.read()?.let(StoredVault::decode) == vault) { "vault read-back mismatch" }
        return vault
    }
}
