package com.nothing.mobilecore.shared.domain

import com.nothing.mobilecore.shared.infrastructure.GitHubReleaseParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdatePolicyTest {
    @Test
    fun newerRelease_isOfferedUntilDismissed() {
        assertTrue(AppUpdatePolicy.isNewer("1.1.0", "1.0.0"))
        assertTrue(AppUpdatePolicy.isNewer("v1.0.1", "1.0.0"))
        assertFalse(AppUpdatePolicy.isNewer("1.0.0", "1.0.0"))
        assertFalse(AppUpdatePolicy.isNewer("0.9.0", "1.0.0"))
        assertFalse(AppUpdatePolicy.isNewer("not-a-version", "1.0.0"))
        assertTrue(AppUpdatePolicy.shouldOffer("", "v1.1.0"))
        assertFalse(AppUpdatePolicy.shouldOffer("v1.1.0", "v1.1.0"))
    }

    @Test
    fun checkIsDue_afterQuietPeriod() {
        assertTrue(AppUpdatePolicy.isCheckDue(0, 1))
        assertFalse(AppUpdatePolicy.isCheckDue(1_000, 1_000 + AppUpdatePolicy.CHECK_INTERVAL_MS - 1))
        assertTrue(AppUpdatePolicy.isCheckDue(1_000, 1_000 + AppUpdatePolicy.CHECK_INTERVAL_MS))
    }

    @Test
    fun downloadUrl_allowsGitHubAssetAndRedirectHost() {
        val asset = "https://github.com/EXTRBXY/smartVPN/releases/download/v1.0.0/NothingMobileVpn.apk"
        assertNull(AppUpdatePolicy.downloadUrlProblem(asset, requireFileName = true))
        assertNull(
            AppUpdatePolicy.downloadUrlProblem(
                "https://release-assets.githubusercontent.com/github-production-release-asset/1",
                requireFileName = false,
            ),
        )
        assertEquals(DownloadUrlProblem.NOT_HTTPS, AppUpdatePolicy.downloadUrlProblem("http://github.com/NothingMobileVpn.apk", true))
        assertEquals(DownloadUrlProblem.HOST, AppUpdatePolicy.downloadUrlProblem("https://evil.example/NothingMobileVpn.apk", true))
        assertEquals(DownloadUrlProblem.HOST, AppUpdatePolicy.downloadUrlProblem("https://user@github.com/NothingMobileVpn.apk", true))
        assertEquals(
            DownloadUrlProblem.FILE_NAME,
            AppUpdatePolicy.downloadUrlProblem("https://github.com/EXTRBXY/smartVPN/releases/download/v1.0.0/other.apk", true),
        )
    }

    @Test
    fun parser_readsMatchingAsset() {
        val body = """
            {"tag_name":"v1.1.0","body":"Исправления","assets":[
              {"name":"other.txt","browser_download_url":"https://github.com/EXTRBXY/smartVPN/releases/download/v1.1.0/other.txt"},
              {"name":"NothingMobileVpn.apk","browser_download_url":"https://github.com/EXTRBXY/smartVPN/releases/download/v1.1.0/NothingMobileVpn.apk"}
            ]}
        """.trimIndent()

        val release = GitHubReleaseParser.parse(body)

        assertEquals("v1.1.0", release?.tag)
        assertEquals("1.1.0", release?.semver)
        assertEquals("Исправления", release?.notes)
        assertTrue(release!!.downloadUrl.endsWith("/NothingMobileVpn.apk"))
    }

    @Test
    fun parser_rejectsReleaseWithoutApk() {
        val body = """{"tag_name":"v1.1.0","assets":[{"name":"notes.txt","browser_download_url":"https://github.com/a/b"}]}"""
        assertNull(GitHubReleaseParser.parse(body))
    }
}
