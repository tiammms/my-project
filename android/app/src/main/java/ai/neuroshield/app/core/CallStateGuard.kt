package ai.neuroshield.app.core

/**
 * The app learns about cellular calls only from PHONE_STATE broadcasts and keeps the last state on disk, because
 * receivers run in short-lived processes. If the phone restarts during a call, or an "IDLE" broadcast is missed,
 * the saved state would say "a call is going on" forever, and every SMS with a code would raise a false
 * "code during a call with an unknown number" alarm (audit C-03).
 *
 * [effective] decides whether the saved state can still be trusted. Pure Kotlin, covered by unit tests.
 */
object CallStateGuard {
    /** Ringing that lasts longer than this has certainly ended (operators drop unanswered calls within ~1–2 min). */
    const val MAX_RINGING_MS = 3 * 60_000L
    /** Without the system's confirmation, a "call in progress" older than this is treated as stale. */
    const val MAX_CALL_MS = 6 * 3_600_000L
    /** A state saved moments ago is kept even if the system does not report the call yet (call setup, screening). */
    const val GRACE_MS = 30_000L

    /**
     * @param stored        saved state: "IDLE", "RINGING" or "OFFHOOK"
     * @param changedAt     wall-clock time the state was saved (0 = unknown: saved by a version before 1.0.84)
     * @param bootAt        wall-clock time the phone was switched on (now − time since boot)
     * @param systemInCall  what the system says right now (TelecomManager.isInCall), or null if it cannot be asked
     */
    fun effective(stored: String, changedAt: Long, now: Long, bootAt: Long, systemInCall: Boolean?): String {
        if (stored != "RINGING" && stored != "OFFHOOK") return "IDLE"
        if (changedAt <= 0L || changedAt < bootAt) return "IDLE"        // saved before the phone restarted
        val age = now - changedAt
        if (systemInCall == false && age > GRACE_MS) return "IDLE"       // the system knows of no call: IDLE was missed
        if (systemInCall != null) return stored
        if (stored == "RINGING" && age > MAX_RINGING_MS) return "IDLE"
        if (stored == "OFFHOOK" && age > MAX_CALL_MS) return "IDLE"
        return stored
    }
}

/** Whether an SMS code that arrives now belongs to a cellular call with a stranger (during it or shortly after). */
object CellularCallWindow {
    /** @return null = no such call; true = the call is going on; false = it ended within [windowMs]. */
    fun riskyCall(effectiveState: String, endedAt: Long, now: Long, isContact: Boolean, windowMs: Long = 10 * 60_000L): Boolean? {
        if (isContact) return null
        val active = effectiveState == "OFFHOOK" || effectiveState == "RINGING"
        if (active) return effectiveState == "OFFHOOK"
        return if (endedAt > 0 && now - endedAt in 0 until windowMs) false else null
    }
}
