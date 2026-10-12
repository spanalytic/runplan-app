package dev.runplan.app.run

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dev.runplan.core.RunState
import dev.runplan.core.WearPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Phone side of the watch link. Pushes [RunState] to every connected node once a second
 * and forwards pause/resume/skip commands from the watch. Silently does nothing when no
 * watch (or no Play services) is present.
 */
class WatchLink(context: Context, private val scope: CoroutineScope, private val onCommand: (String) -> Unit) {
    private val messages = Wearable.getMessageClient(context)
    private val nodes = Wearable.getNodeClient(context)
    @Volatile private var nodeIds: List<String> = emptyList()
    private var refresher: Job? = null

    private val listener = MessageClient.OnMessageReceivedListener { ev ->
        if (ev.path == WearPaths.COMMAND) onCommand(String(ev.data, Charsets.UTF_8))
    }

    fun start() {
        runCatching { messages.addListener(listener) }
        refresher = scope.launch {
            while (isActive) {
                nodeIds = runCatching { nodes.connectedNodes.await().map { it.id } }.getOrDefault(emptyList())
                delay(if (nodeIds.isEmpty()) 5_000 else 30_000)
            }
        }
    }

    fun send(state: RunState) {
        val bytes = state.encode()
        nodeIds.forEach { id -> runCatching { messages.sendMessage(id, WearPaths.STATE, bytes) } }
    }

    fun stop() {
        refresher?.cancel()
        runCatching { messages.removeListener(listener) }
    }
}
