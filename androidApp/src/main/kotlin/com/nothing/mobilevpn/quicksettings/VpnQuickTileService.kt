package com.nothing.mobilevpn.quicksettings

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
import androidx.core.content.ContextCompat
import com.nothing.mobilevpn.MainActivity
import com.nothing.mobilevpn.R
import com.nothing.mobilevpn.platform.AndroidSessionConfigWriter
import com.nothing.mobilevpn.platform.ProfileRepositoryAndroid
import com.nothing.mobilevpn.shared.domain.RoutingMode
import com.nothing.mobilevpn.shared.domain.TunnelPolicy
import com.nothing.mobilevpn.shared.infrastructure.SingBoxConfigCompiler
import com.nothing.mobilevpn.vpn.NothingVpnService
import com.nothing.mobilevpn.vpn.VpnRunningState
import com.nothing.mobilevpn.vpn.VpnRunningStateContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Плитка в быстрых настройках (шторка): вкл/выкл VPN по активному профилю.
 * Плитку нужно один раз перетащить из «Изменить» в панель быстрых настроек.
 */
class VpnQuickTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policyJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VpnRunningStateContract.ACTION) return
            val running = intent.getBooleanExtra(VpnRunningStateContract.EXTRA_RUNNING, false)
            scope.launch(Dispatchers.Default) {
                VpnRunningState.syncFromDisk(this@VpnQuickTileService)
                val repo = ProfileRepositoryAndroid(this@VpnQuickTileService)
                val hasProfile = repo.getActiveProfileId() != null
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
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        scope.launch(Dispatchers.Default) {
            val repo = ProfileRepositoryAndroid(this@VpnQuickTileService)
            val hasProfile = repo.getActiveProfileId() != null
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
            startService(
                Intent(this, NothingVpnService::class.java).setAction(NothingVpnService.ACTION_DISCONNECT),
            )
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
            val repo = ProfileRepositoryAndroid(this@VpnQuickTileService)
            val writer = AndroidSessionConfigWriter(this@VpnQuickTileService)
            val compiler = SingBoxConfigCompiler()
            val activeId = repo.getActiveProfileId()
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
            val profile = repo.listProfiles().find { it.id == activeId }
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
            val policy = repo.getTunnelPolicy()
            if (policy.routingMode == RoutingMode.PER_APP_ALLOWED && policy.allowedPackageNames.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@VpnQuickTileService,
                        R.string.qs_tile_per_app_empty,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }
            val path = try {
                val json = compiler.compile(profile, policy)
                writer.writeSessionConfig(json)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@VpnQuickTileService,
                        e.message ?: "config",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            val policyStr = policyJson.encodeToString(TunnelPolicy.serializer(), policy)
            withContext(Dispatchers.Main) {
                val intent = Intent(this@VpnQuickTileService, NothingVpnService::class.java).apply {
                    action = NothingVpnService.ACTION_CONNECT
                    putExtra(NothingVpnService.EXTRA_CONFIG_PATH, path)
                    putExtra(NothingVpnService.EXTRA_POLICY_JSON, policyStr)
                }
                ContextCompat.startForegroundService(this@VpnQuickTileService, intent)
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
