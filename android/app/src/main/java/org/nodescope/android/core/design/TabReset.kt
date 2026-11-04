package org.nodescope.android.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Runs [onReset] when reselecting a tab bumps [request] (iOS `resetID`). Only a new request
 * counts, so coming back to the tab, rotating or folding keeps what was open.
 */
@Composable
fun OnTabReset(request: Int, onReset: () -> Unit) {
    var handled by rememberSaveable { mutableIntStateOf(request) }
    val reset by rememberUpdatedState(onReset)
    LaunchedEffect(request) {
        if (request != handled) {
            handled = request
            reset()
        }
    }
}
