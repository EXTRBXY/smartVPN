package com.nothing.mobilecore.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nothing.mobilecore.ui.AppViewModelFactory
import com.nothing.mobilecore.ui.apps.AppsRoute
import com.nothing.mobilecore.ui.diagnostics.DiagnosticsRoute
import com.nothing.mobilecore.ui.dns.DnsAppsRoute
import com.nothing.mobilecore.ui.dns.DnsRoute
import com.nothing.mobilecore.ui.home.HomeRoute
import com.nothing.mobilecore.ui.profiles.ImportRoute
import com.nothing.mobilecore.ui.profiles.ProfileEditRoute
import com.nothing.mobilecore.ui.settings.SettingsRoute

private object Routes {
    const val HOME = "home"
    const val IMPORT = "import"
    const val APPS = "apps"
    const val DNS = "dns"
    const val DNS_APPS = "dns/apps"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val PROFILE_ID_ARG = "profileId"
    const val PROFILE_EDIT = "profile/edit/{$PROFILE_ID_ARG}"

    fun profileEdit(profileId: String) = "profile/edit/$profileId"
}

/**
 * Навигация приложения. Каждый экран получает свою ViewModel (область — запись back stack).
 * Действия, которым нужна Activity (QR-сканер, запрос разрешения VPN), приходят колбэками.
 */
@Composable
fun AppNavGraph(
    factory: AppViewModelFactory,
    onScanQr: () -> Unit,
    onPrepareVpnAndConnect: (connect: () -> Unit) -> Unit,
) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeRoute(
                vm = screenViewModel(factory),
                onImport = { nav.navigate(Routes.IMPORT) },
                onScanQr = onScanQr,
                onApps = { nav.navigate(Routes.APPS) },
                onDns = { nav.navigate(Routes.DNS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                onEditProfile = { profileId -> nav.navigate(Routes.profileEdit(profileId)) },
                onPrepareVpnAndConnect = onPrepareVpnAndConnect,
            )
        }
        composable(Routes.IMPORT) { ImportRoute(vm = screenViewModel(factory), onBack = back) }
        composable(Routes.APPS) { AppsRoute(vm = screenViewModel(factory), onBack = back) }
        composable(Routes.DNS) {
            DnsRoute(vm = screenViewModel(factory), onBack = back, onDnsApps = { nav.navigate(Routes.DNS_APPS) })
        }
        composable(Routes.DNS_APPS) { DnsAppsRoute(vm = screenViewModel(factory), onBack = back) }
        composable(Routes.SETTINGS) { SettingsRoute(vm = screenViewModel(factory), onBack = back) }
        composable(Routes.DIAGNOSTICS) { DiagnosticsRoute(vm = screenViewModel(factory), onBack = back) }
        composable(
            route = Routes.PROFILE_EDIT,
            arguments = listOf(navArgument(Routes.PROFILE_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            ProfileEditRoute(
                vm = screenViewModel(factory),
                profileId = entry.arguments?.getString(Routes.PROFILE_ID_ARG).orEmpty(),
                onBack = back,
            )
        }
    }
}

@Composable
private inline fun <reified T : ViewModel> screenViewModel(factory: AppViewModelFactory): T =
    viewModel(factory = factory)
