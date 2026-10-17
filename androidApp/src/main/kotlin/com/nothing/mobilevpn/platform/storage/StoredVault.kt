package com.nothing.mobilecore.platform.storage

import com.nothing.mobilecore.shared.domain.TunnelPolicy
import com.nothing.mobilecore.shared.domain.VlessProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Содержимое зашифрованного хранилища. Профили лежат вместе с секретами: весь документ
 * шифруется целиком, отдельное хранилище секретов больше не нужно.
 *
 * Добавляя поле — давай ему значение по умолчанию; меняя смысл — поднимай [schemaVersion]
 * и пиши миграцию в [VaultBootstrap].
 */
@Serializable
internal data class StoredVault(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val profiles: List<VlessProfile> = emptyList(),
    val activeProfileId: String? = null,
    val policy: TunnelPolicy = TunnelPolicy(),
    /** Данные перенесены из старых EncryptedSharedPreferences; удалить их после следующего успешного чтения. */
    val legacyCleanupPending: Boolean = false,
    /**
     * Старые данные были, но прочитать их не удалось: при каждом следующем запуске пробуем
     * снова и подмешиваем недостающие профили (см. [VaultBootstrap]).
     */
    val legacyMigrationIncomplete: Boolean = false,
) {
    companion object {
        const val CURRENT_SCHEMA = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(bytes: ByteArray): StoredVault = json.decodeFromString(serializer(), bytes.decodeToString())
        fun encode(vault: StoredVault): ByteArray = json.encodeToString(serializer(), vault).encodeToByteArray()
    }
}
