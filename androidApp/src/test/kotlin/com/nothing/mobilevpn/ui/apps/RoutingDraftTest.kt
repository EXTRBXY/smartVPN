package com.nothing.mobilecore.ui.apps

import com.nothing.mobilecore.platform.InstalledApp
import com.nothing.mobilecore.shared.domain.RoutingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingDraftTest {
    @Test
    fun switchingModes_keepsEachModesSelection() {
        // Баг 0.2.0: «Весь трафик» стирал выбор приложений, а оба per-app режима делили один список.
        var draft = RoutingDraft(mode = RoutingMode.PER_APP_ALLOWED).withCurrent(setOf("a", "b"))
        draft = draft.copy(mode = RoutingMode.FULL_TUN)
        draft = draft.copy(mode = RoutingMode.PER_APP_EXCLUDED).withCurrent(setOf("x"))
        draft = draft.copy(mode = RoutingMode.PER_APP_ALLOWED)

        assertEquals(setOf("a", "b"), draft.current)
        assertEquals(setOf("x"), draft.excluded)
    }

    @Test
    fun fullTunnel_editsNothing() {
        val draft = RoutingDraft(mode = RoutingMode.FULL_TUN, included = setOf("a"))
        assertEquals(emptySet<String>(), draft.current)
        assertEquals(draft, draft.withCurrent(setOf("z")))
    }

    @Test
    fun filterApps_selectedFirstAndSearchByLabelOrPackage() {
        val apps = listOf(
            InstalledApp("com.b", "Browser", isSystem = false),
            InstalledApp("com.a", "Alpha", isSystem = true),
            InstalledApp("com.c", "Camera", isSystem = true),
        )

        assertEquals(listOf("com.c", "com.b", "com.a"), filterApps(apps, setOf("com.c"), "", AppFilter.ALL).map { it.packageName })
        assertEquals(listOf("com.b"), filterApps(apps, emptySet(), "brow", AppFilter.ALL).map { it.packageName })
        assertEquals(listOf("com.a"), filterApps(apps, emptySet(), "com.a", AppFilter.ALL).map { it.packageName })
        assertEquals(listOf("com.b"), filterApps(apps, emptySet(), "", AppFilter.USER).map { it.packageName })
        assertEquals(listOf("com.c"), filterApps(apps, setOf("com.c"), "", AppFilter.SELECTED).map { it.packageName })
    }
}
