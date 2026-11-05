package ai.neuroshield.app.core

/**
 * Identities of the PendingIntents that open the app (notifications, the Quick Settings tile, the warning window).
 *
 * Android treats two PendingIntents as THE SAME one when their request code and their Intent (action, data,
 * component, flags — but NOT the extras) are equal; FLAG_UPDATE_CURRENT then replaces the extras of the older one.
 * Before 1.0.84 the call-report notification and the tile both used request code 31 on the same component, so
 * after the tile was used, tapping an old report could start the microphone instead of opening the report.
 *
 * Here every destination gets its own request code AND its own action; destinations that exist many times
 * (one notification per warning or report) also get their own data URI, so each one keeps its own extras.
 * Pure Kotlin: the uniqueness is checked by unit tests.
 */
enum class AppIntent(val requestCode: Int, val action: String) {
    OPEN_ALERT(3, "ai.neuroshield.app.action.OPEN_ALERT"),            // a warning notification -> that warning in history
    START_LISTEN_OFFER(7, "ai.neuroshield.app.action.START_LISTEN"),  // "check the call" offer -> start the speech analysis
    OPEN_FROM_LISTENING(11, "ai.neuroshield.app.action.OPEN_LISTENING"), // the "Saqtan is listening" notification
    OPEN_FROM_VIDEO_CHECK(21, "ai.neuroshield.app.action.OPEN_VIDEO_CHECK"), // the video-check notification
    OPEN_REPORT(31, "ai.neuroshield.app.action.OPEN_REPORT"),          // a call-report notification -> that report
    TILE_START_LISTEN(61, "ai.neuroshield.app.action.TILE_LISTEN");    // Quick Settings tile -> start the speech analysis

    /** A distinct data URI per record, so that notifications for different records never share extras. */
    fun dataFor(recordId: Long): String = "saqtan://" + name.lowercase() + "/" + recordId
}
