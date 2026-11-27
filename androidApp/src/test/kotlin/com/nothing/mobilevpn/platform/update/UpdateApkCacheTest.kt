package com.nothing.mobilecore.platform.update

import com.nothing.mobilecore.shared.domain.AppRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateApkCacheTest {
    @get:Rule
    val cache = TemporaryFolder()

    @Test
    fun onlyReusesCompletedFileForRequestedRelease() {
        val release = AppRelease(
            tag = "v1.0.1",
            semver = "1.0.1",
            notes = "",
            downloadUrl = "https://github.com/EXTRBXY/smartVPN/releases/download/v1.0.1/NothingMobileVpn.apk",
        )
        val destination = updateApkFile(cache.root, release)
        cache.root.resolve("${destination.name}.part").writeBytes(byteArrayOf(0x50, 0x4B, 3, 4))
        assertNull(findCachedUpdateApk(cache.root, release))

        destination.writeBytes(byteArrayOf(0x50, 0x4B, 3, 4))
        assertEquals(destination, findCachedUpdateApk(cache.root, release.copy(notes = "Edited notes")))
        assertNull(findCachedUpdateApk(cache.root, release.copy(tag = "v1.0.2")))
        assertNull(findCachedUpdateApk(cache.root, release.copy(downloadUrl = release.downloadUrl + "?new=1")))
    }
}
