package ai.neuroshield.app.data

import ai.neuroshield.app.tr
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ai.neuroshield.app.core.CallRisk
import ai.neuroshield.app.core.RiskLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** User settings and the state of the current call. Everything stays on the phone. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("neuroshield", Context.MODE_PRIVATE)

    var homeCallingCode: String
        get() = p.getString("home_code", "+7") ?: "+7"
        set(v) = p.edit().putString("home_code", v).apply()
    var warnUnknownCallers: Boolean
        get() = p.getBoolean("warn_unknown", true)
        set(v) = p.edit().putBoolean("warn_unknown", v).apply()
    var offerListening: Boolean
        get() = p.getBoolean("offer_listen", true)
        set(v) = p.edit().putBoolean("offer_listen", v).apply()
    /** Record calls (microphone, while Saqtan listens to a call). Off by default; turned on only after the user agrees. */
    var recordCalls: Boolean
        get() = p.getBoolean("record_calls", false)
        set(v) = p.edit().putBoolean("record_calls", v).apply()
    var autoListen: Boolean
        get() = p.getBoolean("auto_listen", false)   // opt-in: the microphone never starts by itself unless the user turned this on
        set(v) = p.edit().putBoolean("auto_listen", v).apply()
    var storeExcerpts: Boolean
        get() = p.getBoolean("store_excerpts", false)
        set(v) = p.edit().putBoolean("store_excerpts", v).apply()
    var speechLang: String
        get() = p.getString("speech_lang", "ru") ?: "ru"
        set(v) = p.edit().putString("speech_lang", v).apply()
    /** Interface language: "ru" (default) or "kk". */
    var uiLang: String
        get() = p.getString("ui_lang", "ru") ?: "ru"
        set(v) = p.edit().putString("ui_lang", v).apply()
    var trustedName: String
        get() = p.getString("trusted_name", "") ?: ""
        set(v) = p.edit().putString("trusted_name", v).apply()
    var trustedNumber: String
        get() = p.getString("trusted_number", "") ?: ""
        set(v) = p.edit().putString("trusted_number", v).apply()
    var bankNumber: String
        get() = p.getString("bank_number", "") ?: ""
        set(v) = p.edit().putString("bank_number", v).apply()
    var blockMarked: Boolean
        get() = p.getBoolean("block_marked", false)
        set(v) = p.edit().putBoolean("block_marked", v).apply()
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    // ---- numbers the user marked ----
    fun scamNumbers(): Set<String> = p.getStringSet("scam_numbers", emptySet())!!.toSet()
    fun safeNumbers(): Set<String> = p.getStringSet("safe_numbers", emptySet())!!.toSet()
    fun mark(number: String, scam: Boolean) {
        val n = CallRisk.normalizeNumber(number) ?: return
        val s = scamNumbers().toMutableSet(); val ok = safeNumbers().toMutableSet()
        if (scam) { s += n; ok -= n } else { ok += n; s -= n }
        p.edit().putStringSet("scam_numbers", s).putStringSet("safe_numbers", ok).apply()
    }
    fun unmark(number: String) {
        p.edit().putStringSet("scam_numbers", scamNumbers() - number).putStringSet("safe_numbers", safeNumbers() - number).apply()
    }

    // ---- call session (survives process restarts between broadcasts) ----
    var callState: String
        get() = p.getString("call_state", "IDLE") ?: "IDLE"
        set(v) = p.edit().putString("call_state", v).apply()
    var callNumber: String?
        get() = p.getString("call_number", null)
        set(v) = p.edit().putString("call_number", v).apply()
    var callIsContact: Boolean
        get() = p.getBoolean("call_contact", false)
        set(v) = p.edit().putBoolean("call_contact", v).apply()
    var callEndedAt: Long
        get() = p.getLong("call_ended", 0L)
        set(v) = p.edit().putLong("call_ended", v).apply()
    var callStartedAt: Long
        get() = p.getLong("call_started", 0L)
        set(v) = p.edit().putLong("call_started", v).apply()

    // ---- internet call in a messenger (seen through its notification) ----
    var voipActive: Boolean
        get() = p.getBoolean("voip_active", false)
        set(v) = p.edit().putBoolean("voip_active", v).apply()
    var voipNumber: String?
        get() = p.getString("voip_number", null)
        set(v) = p.edit().putString("voip_number", v).apply()
    var voipApp: String?
        get() = p.getString("voip_app", null)
        set(v) = p.edit().putString("voip_app", v).apply()
    var voipIsContact: Boolean
        get() = p.getBoolean("voip_contact", false)
        set(v) = p.edit().putBoolean("voip_contact", v).apply()
    var voipEndedAt: Long
        get() = p.getLong("voip_ended", 0L)
        set(v) = p.edit().putLong("voip_ended", v).apply()

    /** Remember (24 h) that a number sent a suspicious message, so that a later call from it is rated higher. */
    fun rememberSenderRisk(number: String, level: RiskLevel, tactics: List<String>, now: Long = System.currentTimeMillis()) {
        val o = JSONObject(p.getString("sender_risk", "{}") ?: "{}")
        val prev = o.optJSONObject(number)
        if (prev != null && RiskLevel.valueOf(prev.getString("level")).ordinal > level.ordinal && now - prev.getLong("ts") < 86_400_000) return
        o.put(number, JSONObject().put("level", level.name).put("ts", now).put("tactics", JSONArray(tactics)))
        o.keys().asSequence().toList().filter { now - o.getJSONObject(it).getLong("ts") > 86_400_000 }.forEach { o.remove(it) }
        p.edit().putString("sender_risk", o.toString()).apply()
    }

    fun senderRisk(number: String?, now: Long = System.currentTimeMillis()): Pair<RiskLevel, List<String>>? {
        val n = CallRisk.normalizeNumber(number) ?: return null
        val e = JSONObject(p.getString("sender_risk", "{}") ?: "{}").optJSONObject(n) ?: return null
        if (now - e.getLong("ts") > 86_400_000) return null
        val arr = e.getJSONArray("tactics")
        return RiskLevel.valueOf(e.getString("level")) to (0 until arr.length()).map { arr.getString(it) }
    }

    /** Registers a call from [number] and returns how many calls came from it within the last hour. */
    /** Removes every setting, mark and cached call state (used by "delete all data"). */
    fun wipe() { p.edit().clear().commit() }

    fun countRecentCall(number: String?, now: Long = System.currentTimeMillis()): Int {
        val n = CallRisk.normalizeNumber(number) ?: return 1
        val o = JSONObject(p.getString("recent_calls", "{}") ?: "{}")
        val arr = o.optJSONArray(n) ?: JSONArray()
        val keep = JSONArray()
        for (i in 0 until arr.length()) if (now - arr.getLong(i) < 3_600_000) keep.put(arr.getLong(i))
        keep.put(now)
        o.put(n, keep)
        // forget numbers not seen for an hour
        val stale = o.keys().asSequence().toList().filter { k ->
            val a = o.getJSONArray(k); a.length() == 0 || now - a.getLong(a.length() - 1) > 3_600_000
        }
        stale.forEach { o.remove(it) }
        p.edit().putString("recent_calls", o.toString()).apply()
        return keep.length()
    }
}

data class Alert(
    val id: Long, val ts: Long, val source: String, val number: String?, val level: RiskLevel, val score: Double,
    val title: String, val reasons: List<String>, val excerpt: String?, val audio: String? = null,
) {
    val sourceRu: String get() = when (source) {
        "incoming_call" -> tr("Входящий звонок", "Кіріс қоңырау"); "sms_code" -> tr("SMS с кодом во время звонка", "Қоңырау кезіндегі кодты SMS")
        "sms_text" -> tr("Подозрительное SMS", "Күдікті SMS"); "live_speech" -> tr("Речь во время звонка", "Қоңырау кезіндегі сөйлеу")
        "manual_text" -> tr("Проверка текста", "Мәтінді тексеру"); "face" -> tr("Проверка лица", "Бетті тексеру")
        "blocked_call" -> tr("Заблокированный звонок", "Бұғатталған қоңырау"); "messenger_call" -> tr("Звонок в мессенджере", "Мессенджердегі қоңырау")
        "video_call" -> tr("Проверка видеозвонка", "Бейнеқоңырауды тексеру"); "messenger_text" -> tr("Сообщение в мессенджере", "Мессенджердегі хабарлама")
        "demo" -> tr("Демо-сценарий (учебные данные)", "Демо-сценарий (оқу деректері)"); "recording" -> tr("Запись разговора", "Әңгіме жазбасы"); "synthetic_voice" -> tr("Синтетический голос", "Синтетикалық дауыс"); "call_report" -> tr("Отчёт о звонке", "Қоңырау туралы есептеме"); else -> source
    }
}

/**
 * Local history of warnings (SQLite, app-private storage). No audio, no SMS bodies.
 * Transcript excerpts only if the user enabled it, and then encrypted with a Keystore key ([LocalCrypto]).
 * Records older than [RETENTION_DAYS] are deleted automatically; deleted rows are overwritten (secure_delete).
 */
class AlertStore(private val ctx: Context) : SQLiteOpenHelper(ctx, "alerts.db", null, 2) {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    companion object { const val RETENTION_DAYS = 90 }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // a PRAGMA returns a row, so it must go through rawQuery (execSQL throws on Android)
        runCatching { db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() } }
    }

    /** Deletes records older than the retention period. Called on start and after each new record. */
    fun purgeOld(now: Long = System.currentTimeMillis()): Int {
        CallRecordings.purge(ctx, now)
        val n = writableDatabase.delete("alerts", "ts < ?", arrayOf((now - RETENTION_DAYS * 86_400_000L).toString()))
        if (n > 0) _version.value++
        return n
    }

    /** Encrypts excerpts saved in plain text by versions before 1.0.53. */
    fun encryptLegacyExcerpts() {
        val rows = mutableListOf<Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT id, excerpt FROM alerts WHERE excerpt IS NOT NULL AND excerpt NOT LIKE 'enc1:%'", null).use { c ->
            while (c.moveToNext()) rows += c.getLong(0) to c.getString(1)
        }
        for ((id, plain) in rows) {
            val v = ContentValues().apply { put("excerpt", LocalCrypto.encrypt(plain)) }   // null if Keystore fails: dropped, never kept in plain
            writableDatabase.update("alerts", v, "id = ?", arrayOf(id.toString()))
        }
    }

    /** Removes the whole database file (history, local reputation) — used by "delete all data". */
    fun wipe() {
        close()
        ctx.deleteDatabase("alerts.db")
        _version.value++
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, source TEXT NOT NULL,
            number TEXT, level TEXT NOT NULL, score REAL NOT NULL, title TEXT NOT NULL, reasons TEXT NOT NULL, excerpt TEXT, audio TEXT)""")
        db.execSQL("CREATE INDEX ix_alerts_ts ON alerts(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE alerts ADD COLUMN audio TEXT")   // id of a call recording, if any
    }

    fun add(source: String, number: String?, level: RiskLevel, score: Double, title: String, reasons: List<String>, excerpt: String?, audio: String? = null): Long {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("source", source); put("number", number); put("level", level.name)
            put("score", score); put("title", title); put("reasons", JSONArray(reasons).toString()); put("excerpt", LocalCrypto.encrypt(excerpt)); put("audio", audio)
        }
        val id = writableDatabase.insert("alerts", null, v)
        purgeOld()
        _version.value++
        return id
    }

    fun list(limit: Int = 200): List<Alert> {
        val out = mutableListOf<Alert>()
        readableDatabase.rawQuery("SELECT id, ts, source, number, level, score, title, reasons, excerpt, audio FROM alerts ORDER BY ts DESC LIMIT ?",
            arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) {
                val arr = JSONArray(c.getString(7))
                out += Alert(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), RiskLevel.valueOf(c.getString(4)), c.getDouble(5),
                    c.getString(6), (0 until arr.length()).map { arr.getString(it) }, LocalCrypto.decrypt(c.getString(8)), c.getString(9))
            }
        }
        return out
    }

    fun delete(id: Long) {
        readableDatabase.rawQuery("SELECT audio FROM alerts WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) CallRecordings.delete(ctx, c.getString(0)) }
        writableDatabase.delete("alerts", "id = ?", arrayOf(id.toString())); _version.value++
    }
    fun clear() { writableDatabase.delete("alerts", null, null); CallRecordings.deleteAll(ctx); _version.value++ }
    /** Removes only the audio of a record (the report stays). */
    fun deleteAudio(id: Long, audio: String?) {
        CallRecordings.delete(ctx, audio)
        writableDatabase.update("alerts", ContentValues().apply { putNull("audio") }, "id = ?", arrayOf(id.toString())); _version.value++
    }
    fun deleteSource(source: String): Int { val n = writableDatabase.delete("alerts", "source = ?", arrayOf(source)); _version.value++; return n }
    /** Local reputation: how many earlier call reports for [number] ended HIGH and MEDIUM. */
    fun reportCounts(number: String?): Pair<Int, Int> {
        if (number.isNullOrBlank()) return 0 to 0
        var high = 0; var medium = 0
        readableDatabase.rawQuery("SELECT level, COUNT(*) FROM alerts WHERE source = 'call_report' AND number = ? GROUP BY level", arrayOf(number)).use { c ->
            while (c.moveToNext()) when (c.getString(0)) { "HIGH" -> high = c.getInt(1); "MEDIUM" -> medium = c.getInt(1) }
        }
        return high to medium
    }
    fun countSource(source: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM alerts WHERE source = ?", arrayOf(source)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}
