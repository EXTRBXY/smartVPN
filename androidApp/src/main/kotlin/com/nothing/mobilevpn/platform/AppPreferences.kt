package com.nothing.mobilecore.platform

import com.nothing.mobilecore.platform.storage.VaultStore
import com.nothing.mobilecore.shared.domain.RoutingMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class RoutingRuleSet(val name: String, val mode: RoutingMode, val packages: List<String>)

@Serializable
data class AutomationSettings(
    val autoStartAfterBoot: Boolean = false,
    val autoReconnect: Boolean = false,
    val connectOnUntrustedWifi: Boolean = false,
    val trustedWifiNames: Set<String> = emptySet(),
    val periodicHealthCheck: Boolean = false,
)

/** Пользовательские настройки; хранятся в зашифрованном хранилище вместе с профилями. */
@Serializable
data class AppSettings(
    val automation: AutomationSettings = AutomationSettings(),
    val routingRuleSets: List<RoutingRuleSet> = emptyList(),
    /** Пользователь хочет, чтобы VPN был включён (для автоматизации: переподключение, загрузка). */
    val connectionRequested: Boolean = false,
)

/**
 * Настройки автоматизации, наборы приложений и намерение «VPN нужен». Живут в [VaultStore]
 * (раньше — в открытых SharedPreferences). Чтение синхронное из состояния в памяти; запись —
 * в фоне через очередь с одним обработчиком, чтобы не блокировать UI диском и строго
 * сохранить порядок изменений. Код, которому важны значения сразу после старта процесса (автоматизация
 * при загрузке), сначала ждёт [awaitReady].
 */
class AppPreferences internal constructor(
    private val store: VaultStore,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onWriteFailed: (Throwable) -> Unit = {},
) {
    private val current: AppSettings get() = store.state.value.settings
    private val writes = Channel<(AppSettings) -> AppSettings>(Channel.UNLIMITED)

    init {
        scope.launch(ioDispatcher) {
            for (transform in writes) {
                runCatching { store.update { vault -> vault.copy(settings = transform(vault.settings)) } }
                    .onFailure(onWriteFailed)
            }
        }
    }

    val settings: StateFlow<AppSettings> = store.state
        .map { it.settings }
        .stateIn(scope, SharingStarted.Eagerly, current)

    /** Намерение «VPN нужен» прямо из хранилища (без задержки [settings]); читать после [awaitReady]. */
    val connectionRequestedFlow: Flow<Boolean> = store.state
        .map { it.settings.connectionRequested }
        .distinctUntilChanged()

    suspend fun awaitReady() = store.awaitReady()

    fun automation(): AutomationSettings = current.automation

    fun setAutoStart(enabled: Boolean) = updateAutomation { it.copy(autoStartAfterBoot = enabled) }
    fun setAutoReconnect(enabled: Boolean) = updateAutomation { it.copy(autoReconnect = enabled) }
    fun setConnectOnUntrustedWifi(enabled: Boolean) = updateAutomation { it.copy(connectOnUntrustedWifi = enabled) }
    fun setPeriodicHealthCheck(enabled: Boolean) = updateAutomation { it.copy(periodicHealthCheck = enabled) }

    fun setTrustedWifiNames(names: Set<String>) = updateAutomation { automation ->
        automation.copy(trustedWifiNames = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }

    fun routingRuleSets(): List<RoutingRuleSet> = current.routingRuleSets

    fun saveRoutingRuleSet(ruleSet: RoutingRuleSet) = write { settings ->
        settings.copy(routingRuleSets = settings.routingRuleSets.filterNot { it.name == ruleSet.name } + ruleSet)
    }

    fun deleteRoutingRuleSet(name: String) = write { settings ->
        settings.copy(routingRuleSets = settings.routingRuleSets.filterNot { it.name == name })
    }

    var connectionRequested: Boolean
        get() = current.connectionRequested
        set(value) = write { it.copy(connectionRequested = value) }

    private fun updateAutomation(transform: (AutomationSettings) -> AutomationSettings) =
        write { it.copy(automation = transform(it.automation)) }

    private fun write(transform: (AppSettings) -> AppSettings) {
        writes.trySend(transform)
    }
}
