package ai.neuroshield.app.core

import kotlin.math.sqrt

/** One analysed frame of the screen during a video call (largest face = the other person; the small self-view is ignored). */
data class ScreenFace(
    val tMs: Long,
    val faces: Int,
    val areaFraction: Float,        // largest face box area / screen area
    val cx: Float, val cy: Float,   // its centre, 0..1 of the screen
    val yawDeg: Float,
    val leftEye: Float?, val rightEye: Float?,
)

data class VideoVerdict(
    val level: RiskLevel, val score: Double, val title: String, val reasons: List<String>, val advice: List<String>,
    val seconds: Double, val facePresentRatio: Double, val blinks: Int, val movement: Double, val conclusive: Boolean,
)

/**
 * What can honestly be judged from the picture of a video call:
 *  - is a face shown at all (scammers often show only documents, a uniform, a logo);
 *  - does it behave like a live person: people blink every few seconds and their head is never perfectly still.
 *    A photo or a frozen picture shows neither.
 * It cannot detect deepfakes or tell WHO the person is; every result says so.
 */
object VideoCallCheck {
    const val MIN_SECONDS = 10.0
    private const val MIN_FACE_AREA = 0.02f      // ignore the small self-view thumbnail

    fun evaluate(frames: List<ScreenFace>): VideoVerdict {
        val limits = "Проверка не распознаёт дипфейки и не определяет, кто именно на видео."
        if (frames.size < 2) return inconclusive(0.0, "Слишком мало кадров", limits)
        val secs = (frames.last().tMs - frames.first().tMs) / 1000.0
        val withFace = frames.filter { it.faces > 0 && it.areaFraction >= MIN_FACE_AREA }
        val present = withFace.size.toDouble() / frames.size
        if (secs < MIN_SECONDS) return inconclusive(secs, "Проверка шла меньше ${MIN_SECONDS.toInt()} секунд", limits)

        // blinks: eyes open -> closed -> open
        var blinks = 0; var open = false; var closed = false; var eyeFrames = 0
        for (f in withFace) {
            val l = f.leftEye; val r = f.rightEye
            if (l == null || r == null) continue
            eyeFrames++
            val m = (l + r) / 2
            if (m > 0.6f) { if (closed) { blinks++; closed = false }; open = true }
            else if (m < 0.3f && open) { closed = true; open = false }
        }
        val movement = if (withFace.size >= 2) std(withFace.map { it.yawDeg.toDouble() }) +
            100 * (std(withFace.map { it.cx.toDouble() }) + std(withFace.map { it.cy.toDouble() })) else 0.0
        val faceSecs = secs * present

        return when {
            present < 0.3 -> VideoVerdict(RiskLevel.MEDIUM, 55.0, "Собеседник почти не показывает лицо",
                listOf("Лицо было видно только ${pct(present)} времени", "Мошенники часто показывают удостоверение, форму или «документы» вместо лица"),
                listOf("Не доверяйте «удостоверениям» в видеозвонке: их легко подделать", "Положите трубку и перезвоните в организацию по официальному номеру"),
                secs, present, blinks, movement, true)
            faceSecs >= 15 && movement < 1.5 && blinks == 0 -> VideoVerdict(RiskLevel.HIGH, 80.0, "Лицо неподвижно и не моргает — похоже на фото или запись вместо человека",
                listOf("За ${faceSecs.toInt()} с лицо ни разу не моргнуло", "Положение головы почти не меняется (${"%.1f".format(movement)})", "Так выглядит фотография или стоп-кадр, а не живой собеседник"),
                listOf("Попросите собеседника помахать рукой или повернуть голову", "Не выполняйте просьбы о деньгах и кодах", limits),
                secs, present, blinks, movement, true)
            eyeFrames < withFace.size / 3 -> VideoVerdict(RiskLevel.LOW, 10.0, "Лицо видно, но моргание оценить не удалось",
                listOf("Лицо слишком мелкое или размытое для оценки глаз", "Голова двигается (${"%.1f".format(movement)})"),
                listOf(limits), secs, present, blinks, movement, false)
            else -> VideoVerdict(RiskLevel.LOW, 10.0, "Похоже на живого человека в кадре",
                listOf("Морганий: $blinks за ${faceSecs.toInt()} с", "Голова двигается (${"%.1f".format(movement)})"),
                listOf("Живой человек в кадре ещё не значит, что он тот, за кого себя выдаёт", limits),
                secs, present, blinks, movement, true)
        }
    }

    private fun inconclusive(secs: Double, why: String, limits: String) =
        VideoVerdict(RiskLevel.LOW, 0.0, "Недостаточно данных: $why", listOf(why), listOf("Запустите проверку ещё раз во время видеозвонка", limits),
            secs, 0.0, 0, 0.0, false)

    private fun pct(x: Double) = "${(x * 100).toInt()}%"

    private fun std(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }
}
