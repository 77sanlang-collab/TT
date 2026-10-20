package com.github.bookkeeping.automation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opt-in, in-memory, ten-minute session; nothing is persisted or uploaded. */
object Diagnostics {
    data class Snapshot(val pkg: String = "", val event: String = "", val text: String = "", val reason: String = "", val at: Long = 0)
    @Volatile var until: Long = 0
    private val state = MutableStateFlow(Snapshot())
    val snapshots = state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timer: Job? = null
    fun start() {
        timer?.cancel(); val deadline = System.currentTimeMillis() + 600_000L
        until = deadline; state.value = Snapshot()
        timer = scope.launch { delay(600_000L); if (until == deadline) clear() }
    }
    fun clear() { timer?.cancel(); timer = null; until = 0; state.value = Snapshot() }
    fun record(pkg: String, event: String, text: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now >= until) { if (state.value.text.isNotEmpty()) clear(); return }
        val redacted = text.take(12000)
            .replace(Regex("(?<![0-9])1[3-9][0-9]{9}(?![0-9])"), "[手机号]")
            .replace(Regex("[0-9]{16,}"), "[长号码]")
        // Addresses cannot be reliably redacted; user explicitly opts in and can clear immediately.
        state.value = Snapshot(pkg, event.take(200), redacted, reason, now)
    }
}
