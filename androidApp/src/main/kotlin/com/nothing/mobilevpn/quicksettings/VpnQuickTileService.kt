package com.nothing.mobilecore.quicksettings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.nothing.mobilecore.NothingVpnApp
import com.nothing.mobilecore.MainActivity
import com.nothing.mobilecore.R
import com.nothing.mobilecore.ui.UserErrorText
import com.nothing.mobilecore.vpn.VpnInternalBroadcastSecurity
import com.nothing.mobilecore.vpn.VpnRunningState
import com.nothing.mobilecore.vpn.VpnRunningStateContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Плитка в быстрых настройках (шторка): вкл/выкл VPN по активному профилю.
 * Плитку нужно один раз перетащить из «Изменить» в панель быстрых настроек.
 */
class VpnQuickTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as NothingVpnApp).graph }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VpnRunningStateContract.ACTION) return
            val running = intent.getBooleanExtra(VpnRunningStateContract.EXTRA_RUNNING, false)
            scope.launch(Dispatchers.Default) {
                VpnRunningState.syncFromDisk(this@VpnQuickTileService)
                val hasProfile = graph.profileRepository.getActiveProfileId() != null
                withContext(Dispatchers.Main) {
                    if (!hasProfile) {
                        setUnavailableTile()
                    } else {
                        refreshTileUi(running)
                    }
                }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        VpnRunningState.syncFromDisk(this)
        val filter = IntentFilter(VpnRunningStateContract.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                stateReceiver,
                filter,
                VpnInternalBroadcastSecurity.PERMISSION,
                null,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                stateReceiver,
                filter,
                VpnInternalBroadcastSecurity.PERMISSION,
                null,
            )
        }
        scope.launch(Dispatchers.Default) {
            val hasProfile = graph.profileRepository.getActiveProfileId() != null
            withContext(Dispatchers.Main) {
                if (!hasProfile) {
                    setUnavailableTile()
                } else {
                    refreshTileUi(VpnRunningState.isRunning)
                }
            }
        }
    }

    override fun onStopListening() {
        runCatching { unregisterReceiver(stateReceiver) }
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
        if (VpnRunningState.isRunning) {
            scope.launch(Dispatchers.Default) {
                graph.sessionController.disconnectSuspend()
            }
            return
        }
        if (VpnService.prepare(this) != null) {
            Toast.makeText(this, R.string.qs_tile_need_vpn_permission, Toast.LENGTH_LONG).show()
            startActivityAndCollapse(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
            return
        }
        scope.launch(Dispatchers.Default) {
            val activeId = graph.profileRepository.getActiveProfileId()
            if (activeId == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@VpnQuickTileService,
                        R.string.qs_tile_no_profile_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            val profile = graph.profileRepository.listProfiles().find { it.id == activeId }
            if (profile == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@VpnQuickTileService,
                        R.string.qs_tile_no_profile_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            val result = graph.sessionController.connectSuspend()
            if (result.isFailure) {
                val err = result.exceptionOrNull()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@VpnQuickTileService,
                        UserErrorText.from(this@VpnQuickTileService, err?.message),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun setUnavailableTile() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_UNAVAILABLE
        tile.label = getString(R.string.qs_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.qs_tile_no_profile)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_vpn)
        tile.updateTile()
    }

    private fun refreshTileUi(running: Boolean) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.qs_tile_label)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (running) {
                    R.string.qs_tile_subtitle_on
                } else {
                    R.string.qs_tile_subtitle_off
                },
            )
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_vpn)
        tile.updateTile()
    }
}
