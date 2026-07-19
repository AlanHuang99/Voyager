package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.model.HomeLayout
import com.voyagerfiles.data.model.HomeSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLayoutStateTest {

    @Test
    fun remainsLoadingUntilPersistedLayoutArrives() = runBlocking {
        val releasePreferences = CompletableDeferred<Unit>()
        val persisted = HomeLayout.DEFAULT.withVisibility(HomeSection.QUICK_ACCESS, visible = false)
        val state = flow {
            releasePreferences.await()
            emit(persisted)
        }.stateInWithLoading(this)

        assertNull(state.value)

        releasePreferences.complete(Unit)
        yield()

        assertEquals(persisted, state.value)
    }
}
