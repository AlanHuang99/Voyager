package com.voyagerfiles.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    @Test
    fun defaultLayoutMatchesExistingHomeOrder() {
        assertEquals(
            listOf(
                HomeSection.STORAGE,
                HomeSection.ACTIVE_SESSIONS,
                HomeSection.QUICK_ACCESS,
                HomeSection.REMOTE_CONNECTIONS,
                HomeSection.BOOKMARKS,
                HomeSection.FOLDERS,
            ),
            HomeLayout.DEFAULT.sectionOrder,
        )
        assertEquals(HomeSection.entries, HomeLayout.DEFAULT.visibleSections)
    }

    @Test
    fun persistedLayoutDropsUnknownsAndDuplicatesThenAppendsMissingSections() {
        val layout = HomeLayout.fromPersisted(
            order = "FOLDERS,UNKNOWN,FOLDERS,STORAGE",
            hidden = "QUICK_ACCESS,REMOVED",
        )

        assertEquals(
            listOf(
                HomeSection.FOLDERS,
                HomeSection.STORAGE,
                HomeSection.ACTIVE_SESSIONS,
                HomeSection.QUICK_ACCESS,
                HomeSection.REMOTE_CONNECTIONS,
                HomeSection.BOOKMARKS,
            ),
            layout.sectionOrder,
        )
        assertEquals(setOf(HomeSection.QUICK_ACCESS), layout.hiddenSections)
    }

    @Test
    fun blankPersistenceUsesDefaultLayout() {
        assertEquals(HomeLayout.DEFAULT, HomeLayout.fromPersisted(null, null))
        assertEquals(HomeLayout.DEFAULT, HomeLayout.fromPersisted("", ""))
    }

    @Test
    fun visibilityChangeKeepsSectionRecoverableInTheOrder() {
        val hidden = HomeLayout.DEFAULT.withVisibility(HomeSection.QUICK_ACCESS, visible = false)

        assertFalse(HomeSection.QUICK_ACCESS in hidden.visibleSections)
        assertTrue(HomeSection.QUICK_ACCESS in hidden.sectionOrder)
        assertEquals(
            HomeLayout.DEFAULT,
            hidden.withVisibility(HomeSection.QUICK_ACCESS, visible = true),
        )
    }

    @Test
    fun moveClampsAtBoundariesAndChangesOnlyOrder() {
        assertEquals(
            HomeLayout.DEFAULT,
            HomeLayout.DEFAULT.move(HomeSection.STORAGE, offset = -1),
        )
        assertEquals(
            HomeLayout.DEFAULT,
            HomeLayout.DEFAULT.move(HomeSection.FOLDERS, offset = 1),
        )

        val moved = HomeLayout.DEFAULT
            .withVisibility(HomeSection.FOLDERS, visible = false)
            .move(HomeSection.FOLDERS, offset = -1)

        assertEquals(HomeSection.FOLDERS, moved.sectionOrder[moved.sectionOrder.lastIndex - 1])
        assertEquals(setOf(HomeSection.FOLDERS), moved.hiddenSections)
    }

    @Test
    fun persistenceRoundTripIsDeterministic() {
        val layout = HomeLayout.DEFAULT
            .move(HomeSection.FOLDERS, offset = -1)
            .withVisibility(HomeSection.QUICK_ACCESS, visible = false)
            .withVisibility(HomeSection.STORAGE, visible = false)

        assertEquals(
            layout,
            HomeLayout.fromPersisted(layout.persistedOrder, layout.persistedHidden),
        )
        assertEquals("STORAGE,QUICK_ACCESS", layout.persistedHidden)
    }
}
