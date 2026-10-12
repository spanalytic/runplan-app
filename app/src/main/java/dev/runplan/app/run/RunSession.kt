package dev.runplan.app.run

import dev.runplan.core.RunState
import dev.runplan.core.RunStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RunPhase { IDLE, READY, RUNNING, PAUSED, SAVED }

/** What the UI observes about the run the service is recording. */
data class SessionView(
    val phase: RunPhase = RunPhase.IDLE,
    val workoutId: String? = null,
    val steps: List<RunStep> = emptyList(),
    val state: RunState = RunState.IDLE,
    val savedRunId: String? = null,
)

/** Process-wide bridge between [RunService] and the UI. */
object RunSession {
    private val _view = MutableStateFlow(SessionView())
    val view: StateFlow<SessionView> = _view.asStateFlow()

    internal fun set(v: SessionView) { _view.value = v }
    internal fun update(f: (SessionView) -> SessionView) { _view.value = f(_view.value) }

    /** UI calls this after it has shown the summary of a saved run. */
    fun clear() { if (_view.value.phase == RunPhase.SAVED || _view.value.phase == RunPhase.IDLE) _view.value = SessionView() }
}
