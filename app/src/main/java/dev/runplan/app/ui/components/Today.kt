package dev.runplan.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDate

/** Today's epoch day, kept current: rechecked when the app comes back to the screen and every 30 s while it's shown. */
@Composable
fun rememberToday(): Long {
    var today by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                today = LocalDate.now().toEpochDay()
                delay(30_000)
            }
        }
    }
    return today
}
