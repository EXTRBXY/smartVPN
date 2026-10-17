package com.nothing.mobilecore.quicksettings

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.nothing.mobilecore.MainActivity
import com.nothing.mobilecore.NothingVpnApp
import com.nothing.mobilecore.R
import com.nothing.mobilecore.shared.domain.TunnelStatus
import com.nothing.mobilecore.shared.domain.VpnSessionState
import com.nothing.mobilecore.ui.UserErrorText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Плитка в быстрых настройках (шторка): вкл/выкл VPN по активному профилю.
 * Плитку нужно один раз перетащить из «Изменить» в панель быстрых настроек.
 *
 * Отображение строится только из общего состояния процесса ([VpnSessionState] контроллера и
 * [TunnelStatus] сервиса), на которое плитка подписана, пока видна. Своего «кэша» статуса нет,
 * поэтому плитка не может разойтись с приложением и уведомлением.
 */
class VpnQuickTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as NothingVpnApp
    private val graph get() = app.graph

    private var listeningJob: Job? = null

    /** Нажатие обрабатывается: плитка показывает «занято», пока команда не завершится. */
    private val actionInProgress = MutableStateFlow(false)

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = scope.launch {
            val hasProfile = withContext(Dispatchers.Default) {
                graph.profileRepository.getActiveProfileId() != null
            }
            combine(
                graph.sessionController.state,
                graph.sessionController.tunnelStatus,
                actionInProgress,
            ) { state, tunnel, busy -> TileModel.from(hasProfile, state, tunnel, busy) }
                .collect { render(it) }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        unlockAndRun { handleClick() }
    }

    private fun handleClick() {
        if (actionInProgress.value) return
        when (graph.sessionController.state.value) {
            VpnSessionState.Connected -> disconnect()
            VpnSessionState.Disconnected, is VpnSessionState.Error -> connect()
            // Подключение/отключение уже идёт: повторное нажатие игнорируем.
            VpnSessionState.Connecting, is VpnSessionState.Recovering, VpnSessionState.Disconnecting -> Unit
        }
    }

    private fun disconnect() {
        app.automation.requestDisconnect("quick settings")
        runAction { graph.sessionController.disconnectSuspend() }
    }

    private fun connect() {
        if (VpnService.prepare(this) != null) {
            Toast.makeText(this, R.string.qs_tile_need_vpn_permission, Toast.LENGTH_LONG).show()
            openApp()
            return
        }
        app.automation.requestConnect("quick settings")
        runAction {
            val result = graph.sessionController.connectSuspend()
            result.exceptionOrNull()?.let { err ->
                withContext(Dispatchers.Main) {
                    val text = if (err.message == "no_active_profile" || err.message == "profile_not_found") {
                        getString(R.string.qs_tile_no_profile_toast)
                    } else {
                        UserErrorText.from(app, err.message)
                    }
                    Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Команда выполняется в scope приложения: система может отвязать плитку сразу после нажатия,
     * и scope плитки отменил бы подключение на полпути.
     */
    private fun runAction(block: suspend () -> Unit) {
        actionInProgress.value = true
        app.applicationScope.launch {
            try {
                block()
            } finally {
                actionInProgress.value = false
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val activityIntent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                2,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(activityIntent)
        }
    }

    private fun render(model: TileModel) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.qs_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_vpn)
        tile.state = when (model) {
            TileModel.NoProfile, TileModel.Connecting, TileModel.Disconnecting -> Tile.STATE_UNAVAILABLE
            is TileModel.On -> Tile.STATE_ACTIVE
            TileModel.Off -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (model) {
                TileModel.NoProfile -> getString(R.string.qs_tile_no_profile)
                TileModel.Connecting -> getString(R.string.qs_tile_subtitle_connecting)
                TileModel.Disconnecting -> getString(R.string.qs_tile_subtitle_disconnecting)
                TileModel.Off -> getString(R.string.qs_tile_subtitle_off)
                is TileModel.On -> {
                    val minutes = ((System.currentTimeMillis() - model.startedAtMillis) / 60_000).coerceAtLeast(0)
                    getString(R.string.qs_tile_subtitle_on_minutes, minutes)
                }
            }
        }
        tile.updateTile()
    }
}

/** Что показывает плитка; чистая функция от общего состояния, см. [from]. */
internal sealed interface TileModel {
    data object NoProfile : TileModel
    data object Off : TileModel
    data object Connecting : TileModel
    data object Disconnecting : TileModel
    data class On(val startedAtMillis: Long) : TileModel

    companion object {
        fun from(
            hasProfile: Boolean,
            state: VpnSessionState,
            tunnel: TunnelStatus,
            actionInProgress: Boolean,
        ): TileModel {
            // Туннель реально поднят — показываем это, даже если профиль успели удалить.
            if (tunnel is TunnelStatus.Running && state is VpnSessionState.Connected) {
                return On(tunnel.startedAtMillis)
            }
            if (tunnel is TunnelStatus.Stopping || state is VpnSessionState.Disconnecting) return Disconnecting
            if (state is VpnSessionState.Connecting || state is VpnSessionState.Recovering) return Connecting
            if (tunnel is TunnelStatus.Starting) return Connecting
            if (actionInProgress) {
                return if (state is VpnSessionState.Connected) Disconnecting else Connecting
            }
            if (!hasProfile) return NoProfile
            return Off
        }
    }
}
