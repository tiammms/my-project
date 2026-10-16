package ai.neuroshield.app.core

import java.security.SecureRandom

enum class Pose(val instruction: String, val arrow: String) {
    CENTER("Посмотрите прямо в камеру", "●"),
    LEFT("Медленно поверните голову влево", "←"),
    RIGHT("Медленно поверните голову вправо", "→"),
    BLINK("Моргните", "◉"),
}

/** One analysed camera frame (from ML Kit face detection). */
data class FaceFrame(
    val faces: Int,
    val trackingId: Int?,
    val yawDeg: Float,          // ML Kit headEulerAngleY, converted so that positive = the person's LEFT
    val leftEyeOpen: Float?,    // probability 0..1
    val rightEyeOpen: Float?,
)

enum class LivenessState { RUNNING, PASSED, FAILED }

/**
 * Active liveness check: a random sequence of head turns and a blink, verified frame by frame.
 * A printed photo or a phone screen cannot turn its head or blink on request in a random order.
 * Limits: a 3D mask or a real-time deepfake fed into the camera are not detected.
 */
class LivenessSession(
    val steps: List<Pose> = randomSteps(),
    private val stepTimeoutMs: Long = 7000,
) {
    companion object {
        const val TURN_DEG = 18f
        const val CENTER_DEG = 8f
        fun randomSteps(rnd: SecureRandom = SecureRandom()): List<Pose> {
            val turns = if (rnd.nextBoolean()) listOf(Pose.LEFT, Pose.RIGHT) else listOf(Pose.RIGHT, Pose.LEFT)
            val list = mutableListOf(Pose.CENTER, turns[0], Pose.CENTER, turns[1])
            list.add(1 + rnd.nextInt(list.size), Pose.BLINK)   // blink at a random position (never first)
            return list
        }
    }

    var index = 0; private set
    var state = LivenessState.RUNNING; private set
    var failure: String? = null; private set
    val log = mutableListOf<String>()
    private var stepStart = -1L
    private var trackingId: Int? = null
    private var eyesWereOpen = false

    val current: Pose? get() = steps.getOrNull(index)

    fun onFrame(f: FaceFrame, nowMs: Long): LivenessState {
        if (state != LivenessState.RUNNING) return state
        if (stepStart < 0) stepStart = nowMs
        if (f.faces > 1) return fail("В кадре больше одного лица")
        if (f.faces == 0) {
            if (nowMs - stepStart > stepTimeoutMs) return fail("Лицо пропало из кадра")
            return state
        }
        if (f.trackingId != null) {
            if (trackingId == null) trackingId = f.trackingId
            else if (trackingId != f.trackingId) return fail("Лицо сменилось во время проверки")
        }
        val pose = current ?: return state
        val done = when (pose) {
            Pose.CENTER -> kotlin.math.abs(f.yawDeg) < CENTER_DEG
            Pose.LEFT -> f.yawDeg > TURN_DEG
            Pose.RIGHT -> f.yawDeg < -TURN_DEG
            Pose.BLINK -> {
                val l = f.leftEyeOpen; val r = f.rightEyeOpen
                if (l == null || r == null) false
                else {
                    if (l > 0.7f && r > 0.7f) eyesWereOpen = true
                    eyesWereOpen && l < 0.3f && r < 0.3f
                }
            }
        }
        if (done) {
            log += "${pose.name}: выполнено (поворот ${"%.0f".format(f.yawDeg)}°)"
            index++
            stepStart = nowMs
            eyesWereOpen = false
            if (index >= steps.size) state = LivenessState.PASSED
        } else if (nowMs - stepStart > stepTimeoutMs) {
            return fail("Не выполнено: «${pose.instruction.lowercase()}». Последний поворот головы: ${"%.0f".format(f.yawDeg)}°")
        }
        return state
    }

    private fun fail(why: String): LivenessState {
        failure = why
        state = LivenessState.FAILED
        log += "FAILED: $why"
        return state
    }
}
