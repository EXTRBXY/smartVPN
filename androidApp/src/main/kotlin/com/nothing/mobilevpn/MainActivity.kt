package com.nothing.mobilecore

import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.nothing.mobilecore.ui.AppViewModelFactory
import com.nothing.mobilecore.ui.NothingVpnTheme
import com.nothing.mobilecore.ui.navigation.AppNavGraph
import com.nothing.mobilecore.ui.profiles.ImportViewModel

/**
 * Единственная Activity: тема, навигация и то, что требует Activity — QR-сканер, запрос
 * разрешения VPN, FLAG_SECURE, разрешение на уведомления. Экраны — в `ui/<экран>/`.
 */
class MainActivity : ComponentActivity() {

    private val factory by lazy { AppViewModelFactory((application as NothingVpnApp).graph) }
    private val importViewModel: ImportViewModel by viewModels { factory }

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        val link = result.contents ?: return@registerForActivityResult
        importViewModel.importAndActivate(link) { imported ->
            val text = if (imported.isSuccess) R.string.qr_import_success else R.string.qr_import_failed
            Toast.makeText(this, text, if (imported.isSuccess) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingConnect?.invoke()
        }
        pendingConnect = null
    }

    private var pendingConnect: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            NothingVpnTheme {
                AppNavGraph(
                    factory = factory,
                    onScanQr = ::launchQrScanner,
                    onPrepareVpnAndConnect = ::prepareVpnAndConnect,
                )
            }
        }
    }

    private fun launchQrScanner() {
        qrScannerLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.qr_scan_prompt))
                .setBeepEnabled(false),
        )
    }

    private fun prepareVpnAndConnect(connect: () -> Unit) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConnect = connect
            vpnPermissionLauncher.launch(intent)
        } else {
            connect()
        }
    }
}
