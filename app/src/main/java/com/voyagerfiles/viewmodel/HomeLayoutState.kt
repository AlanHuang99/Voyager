package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.model.HomeLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

internal fun Flow<HomeLayout>.stateInWithLoading(scope: CoroutineScope): StateFlow<HomeLayout?> {
    val nullableLayouts: Flow<HomeLayout?> = this
    return nullableLayouts.stateIn(scope, SharingStarted.Eagerly, null)
}
