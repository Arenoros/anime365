package io.github.arenoros.anime365.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationBackTest {
    @Test
    fun queuedBackEventsKeepTabRootAfterClosingDetailsAndSheet() {
        val stack = mutableListOf("catalog", "series", "translations")
        val queuedBack = { stack.popAboveRoot() }

        repeat(20) { queuedBack() }

        assertEquals(listOf("catalog"), stack)
        stack.add("another series")
        queuedBack()
        assertEquals(listOf("catalog"), stack)
    }

    @Test
    fun delayedBackFromPreviousTabDoesNotRemoveItsRootOrChangeSelectedTab() {
        val home = mutableListOf("home", "series")
        val library = mutableListOf("library", "download")
        val delayedBack = { home.popAboveRoot() }

        repeat(3) { delayedBack() }

        assertEquals(listOf("home"), home)
        assertEquals(listOf("library", "download"), library)
    }
}
