package ai.neuroshield.app.core

import kotlin.random.Random

/**
 * Challenge–response for a video call: the app tells the USER what to ask the other person to do
 * ("turn your head", "pass a hand in front of your face", "close your eyes for a second"), in a random order,
 * and checks on the screen picture whether it happened within the time window.
 *
 * A photo cannot do it, and a pre-recorded video does not react to a random request at the right moment.
 * It is NOT a deepfake detector: a live deepfake that follows the requests can pass.
 */
enum class ChallengeKind(val ask: String, val done: String) {
    TURN("Попросите собеседника повернуть голову в сторону", "повернул голову"),
    HAND("Попросите собеседника провести рукой перед лицом", "провёл рукой перед лицом"),
    EYES("Попросите собеседника закрыть глаза на секунду", "закрыл глаза"),
}

data class Challenge(val kind: ChallengeKind, val startMs: Long, val endMs: Long)
data class ChallengeResult(val challenge: Challenge, val passed: Boolean, val detail: String)

object VideoChallenges {
    const val FIRST_AT_MS = 5_000L
    const val WINDOW_MS = 9_000L
    const val TURN_DEG = 18f
    private const val MIN_FACE_AREA = 0.02f

    /** Two different challenges in random order, one after another. */
    fun plan(random: Random = Random.Default, count: Int = 2): List<Challenge> =
        ChallengeKind.entries.shuffled(random).take(count).mapIndexed { i, k ->
            val s = FIRST_AT_MS + i * WINDOW_MS
            Challenge(k, s, s + WINDOW_MS)
        }

    /** The challenge to show at time [tMs] from the start, or null between / after them. */
    fun current(plan: List<Challenge>, tMs: Long): Challenge? = plan.firstOrNull { tMs >= it.startMs && tMs < it.endMs }

    private fun hasFace(f: ScreenFace) = f.faces > 0 && f.areaFraction >= MIN_FACE_AREA

    fun check(frames: List<ScreenFace>, c: Challenge): ChallengeResult {
        val inWin = frames.filter { it.tMs >= c.startMs && it.tMs < c.endMs }
        val before = frames.filter { it.tMs < c.startMs && hasFace(it) }
        if (inWin.none(::hasFace) && before.isEmpty()) return ChallengeResult(c, false, "лица в кадре не было")
        return when (c.kind) {
            ChallengeKind.TURN -> {
                val base = (before.takeLast(9).ifEmpty { inWin.filter(::hasFace).take(3) }).map { it.yawDeg }.sorted()
                val baseline = if (base.isEmpty()) 0f else base[base.size / 2]
                val maxTurn = inWin.filter(::hasFace).maxOfOrNull { kotlin.math.abs(it.yawDeg - baseline) } ?: 0f
                if (maxTurn >= TURN_DEG) ChallengeResult(c, true, "поворот головы ${maxTurn.toInt()}°")
                else ChallengeResult(c, false, "голова почти не поворачивалась (${maxTurn.toInt()}°)")
            }
            ChallengeKind.HAND -> {
                // the face is covered (lost, or its box shrinks a lot) and then shows again
                val refArea = (before.takeLast(9).ifEmpty { inWin.filter(::hasFace).take(3) }).map { it.areaFraction }.average().toFloat()
                var seenFace = before.isNotEmpty(); var covered = false; var recovered = false
                for (f in inWin) {
                    val visible = hasFace(f) && (refArea.isNaN() || f.areaFraction >= refArea * 0.6f)
                    if (visible) { if (covered) { recovered = true; break }; seenFace = true }
                    else if (seenFace) covered = true
                }
                if (covered && recovered) ChallengeResult(c, true, "лицо закрылось и снова появилось")
                else if (covered) ChallengeResult(c, false, "лицо пропало и больше не появилось")
                else ChallengeResult(c, false, "лицо ни разу не закрывалось")
            }
            ChallengeKind.EYES -> {
                // eyes closed for at least two frames in a row (~0.7 s): longer than an ordinary blink
                var run = 0; var best = 0
                for (f in inWin) {
                    val l = f.leftEye; val r = f.rightEye
                    if (hasFace(f) && l != null && r != null && (l + r) / 2 < 0.3f) { run++; best = maxOf(best, run) } else run = 0
                }
                if (best >= 2) ChallengeResult(c, true, "глаза были закрыты")
                else if (inWin.none { hasFace(it) && it.leftEye != null }) ChallengeResult(c, false, "глаза не удалось рассмотреть")
                else ChallengeResult(c, false, "глаза не закрывались дольше обычного моргания")
            }
        }
    }

    fun checkAll(frames: List<ScreenFace>, plan: List<Challenge>): List<ChallengeResult> = plan.map { check(frames, it) }
}
