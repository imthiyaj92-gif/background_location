package com.almoullim.background_location

/** Main-thread, monotonic health state. Silence can mean stationary, not a broken provider.
 * Operations must be cancellable and may complete synchronously. No radius/audio decisions here.
 */
internal class LocationHealthMonitor(
    private val now: () -> Long,
    private val problem: () -> String?,
    private val probe: (done: () -> Unit) -> (() -> Unit),
    private val renew: (done: (Boolean) -> Unit) -> (() -> Unit),
) {
    companion object {
        const val STALE_MS = 30_000L
        const val CHECK_MS = 30_000L
        private val BACKOFF_MS = longArrayOf(60_000, 120_000, 300_000)
    }
    private var active = true
    private var recoveryEnabled = true
    private var lastAccepted: Long? = null
    private var lastCallback: Long? = null
    private var nextAttempt = now() + BACKOFF_MS[0]
    private var operation = ""
    private var operationStarted = 0L
    private var token = 0
    private var cancel: (() -> Unit)? = null
    var attempts = 0
        private set
    private var subscription = "PENDING"

    fun callbackReceived() { if (active) lastCallback = now() }

    fun accepted(fixElapsedMs: Long) {
        if (!active || fixElapsedMs > now() || fixElapsedMs < 0) return
        if (lastAccepted != null && fixElapsedMs < lastAccepted!!) return
        lastAccepted = fixElapsedMs
        // An accepted historical fix is still old; it must not reset the outage budget.
        if (now() - fixElapsedMs < STALE_MS && subscription != "ERROR") {
            cancelOperation()
            attempts = 0
            nextAttempt = now() + BACKOFF_MS[0]
        }
    }

    fun subscriptionResult(success: Boolean) {
        if (active) subscription = if (success) "ACTIVE" else "ERROR"
    }

    fun snapshot(): Map<String, Any?> {
        val age = lastAccepted?.let { (now() - it).coerceAtLeast(0) }
        val blocked = if (active) problem() else null
        val state = when {
            !active -> "STOPPED"
            blocked != null -> blocked
            operation.isNotEmpty() -> "CHECKING"
            subscription == "ERROR" -> "SUBSCRIPTION_ERROR"
            age == null -> "WAITING"
            age >= STALE_MS -> "STALE"
            else -> "FRESH"
        }
        return mapOf("state" to state, "fixAgeMs" to age,
            "callbackAgeMs" to lastCallback?.let { (now() - it).coerceAtLeast(0) },
            "subscription" to subscription, "recoveryAttempts" to attempts,
            "recoveryPending" to operation.isNotEmpty())
    }

    /** Called at most once per 30 seconds; no wakeup alarm or continuous wake lock. */
    fun tick() {
        if (!active || !recoveryEnabled) return
        if (problem() != null) { cancelOperation(); return }
        if (operation.isNotEmpty()) {
            if (now() - operationStarted >= CHECK_MS) {
                if (operation == "PROBE") finishProbe(token)
                else { cancelOperation(); subscription = "ERROR" }
            }
            return
        }
        if (attempts >= BACKOFF_MS.size || now() < nextAttempt) return
        if (subscription != "ERROR" && lastAccepted?.let { now() - it < STALE_MS } == true) return
        attempts++
        nextAttempt = now() + BACKOFF_MS[minOf(attempts, BACKOFF_MS.lastIndex)]
        launch("PROBE") { id -> probe { finishProbe(id) } }
    }

    private fun finishProbe(id: Int) {
        if (!active || token != id || operation != "PROBE") return
        val started = operationStarted
        cancelOperation()
        // Received data (even poor accuracy) is evidence the provider is delivering.
        // Do not churn its subscription during a tunnel/weak signal or while stationary.
        if (problem() != null ||
            (subscription != "ERROR" && lastCallback?.let { it >= started } == true)) return
        launch("RENEW") { renewalId ->
            renew { success ->
                if (active && token == renewalId && operation == "RENEW") {
                    cancelOperation()
                    subscriptionResult(success)
                }
            }
        }
    }

    private fun launch(kind: String, start: (Int) -> (() -> Unit)) {
        operation = kind
        operationStarted = now()
        val id = ++token
        val cancellation = start(id)
        if (active && token == id && operation == kind) cancel = cancellation
        else cancellation()
    }

    private fun cancelOperation() {
        token++
        operation = ""
        val pending = cancel
        cancel = null
        pending?.invoke()
    }

    fun stop() { active = false; cancelOperation() }
    fun disableRecovery() { recoveryEnabled = false; cancelOperation() }
}
