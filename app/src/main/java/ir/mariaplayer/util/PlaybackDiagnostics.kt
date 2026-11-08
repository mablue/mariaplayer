package ir.mariaplayer.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * P5 (SSOT drift detector): atomic counters that track every critical
 * playback lifecycle event. In SelfTest, we read snapshots to see if
 * any subsystem is behaving outside SSOT expectations.
 */
object PlaybackDiagnostics {
    val listenerRegistrations = AtomicInteger()
    val listenerRemovals = AtomicInteger()
    val pollerTicks = AtomicInteger()
    val transitionHandles = AtomicInteger()
    val stateHandles = AtomicInteger()

    val saveCalls = AtomicInteger()
    val savePositionCalls = AtomicInteger()
    val readCalls = AtomicInteger()
    val readPositionCalls = AtomicInteger()
    val clearPositionCalls = AtomicInteger()

    fun reset() {
        listenerRegistrations.set(0)
        listenerRemovals.set(0)
        pollerTicks.set(0)
        transitionHandles.set(0)
        stateHandles.set(0)
        saveCalls.set(0)
        savePositionCalls.set(0)
        readCalls.set(0)
        readPositionCalls.set(0)
        clearPositionCalls.set(0)
    }

    fun snapshot(): String =
        "reg=${listenerRegistrations.get()} rem=${listenerRemovals.get()} " +
        "tick=${pollerTicks.get()} trans=${transitionHandles.get()} " +
        "state=${stateHandles.get()} save=${saveCalls.get()} " +
        "savePos=${savePositionCalls.get()} read=${readCalls.get()} " +
        "readPos=${readPositionCalls.get()} clear=${clearPositionCalls.get()}"
}
