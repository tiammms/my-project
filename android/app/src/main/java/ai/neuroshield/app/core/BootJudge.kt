package ai.neuroshield.app.core

/** Pure decision, unit-tested (CoreLogicTest.bootJudge*). Times in ms; [sinceBootMs] = time since the restart. */
object BootJudge {
    /** the system may deliver the boot broadcast late; before this the answer is "not known yet" */
    const val GRACE_MS = 5 * 60_000L

    fun judge(bootAt: Long, seenAt: Long, installedAt: Long, sinceBootMs: Long): Boolean? = when {
        seenAt >= bootAt - 60_000L -> true          // recorded after this restart (1 min slack for clock adjustments)
        installedAt >= bootAt -> null               // installed after the restart: no restart to judge
        sinceBootMs < GRACE_MS -> null              // just restarted: the broadcast may still come
        else -> false
    }
}
