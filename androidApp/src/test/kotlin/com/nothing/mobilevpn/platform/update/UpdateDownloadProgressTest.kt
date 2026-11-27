package com.nothing.mobilecore.platform.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateDownloadProgressTest {
    @Test
    fun reportsPercentFromDownloadedBytes() {
        val partial = UpdateDownloadProgress(29, 100)
        assertEquals(29, partial.percent)
        assertEquals(0.29f, partial.fraction ?: -1f, 0.001f)
        assertEquals(100, UpdateDownloadProgress(100, 100).percent)
    }

    @Test
    fun unknownFileSizeDoesNotShowAPercentage() {
        val partial = UpdateDownloadProgress(1024, null)
        assertEquals(1024L, partial.downloadedBytes)
        assertNull(partial.percent)
        assertNull(partial.fraction)
    }
}
