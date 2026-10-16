package ai.neuroshield.app.data

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
    var storeExcerpts: Boolean
        get() = p.getBoolean("store_excerpts", false)
        set(v) = p.edit().putBoolean("store_excerpts", v).apply()
    var speechLang: String
        get() = p.getString("speech_lang", "ru") ?: "ru"
        set(v) = p.edit().putString("speech_lang", v).apply()
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

    /** Registers a call from [number] and returns how many calls came from it within the last hour. */
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
    val title: String, val reasons: List<String>, val excerpt: String?,
) {
    val sourceRu: String get() = when (source) {
        "incoming_call" -> "Входящий звонок"; "sms_code" -> "SMS с кодом во время звонка"; "sms_text" -> "Подозрительное SMS"
        "live_speech" -> "Речь во время звонка"; "manual_text" -> "Проверка текста"; "face" -> "Проверка лица"; else -> source
    }
}

/** Local history of warnings (SQLite). No audio, no SMS bodies; transcript excerpts only if the user enabled it. */
class AlertStore(ctx: Context) : SQLiteOpenHelper(ctx, "alerts.db", null, 1) {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, source TEXT NOT NULL,
            number TEXT, level TEXT NOT NULL, score REAL NOT NULL, title TEXT NOT NULL, reasons TEXT NOT NULL, excerpt TEXT)""")
        db.execSQL("CREATE INDEX ix_alerts_ts ON alerts(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun add(source: String, number: String?, level: RiskLevel, score: Double, title: String, reasons: List<String>, excerpt: String?): Long {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("source", source); put("number", number); put("level", level.name)
            put("score", score); put("title", title); put("reasons", JSONArray(reasons).toString()); put("excerpt", excerpt)
        }
        val id = writableDatabase.insert("alerts", null, v)
        _version.value++
        return id
    }

    fun list(limit: Int = 200): List<Alert> {
        val out = mutableListOf<Alert>()
        readableDatabase.rawQuery("SELECT id, ts, source, number, level, score, title, reasons, excerpt FROM alerts ORDER BY ts DESC LIMIT ?",
            arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) {
                val arr = JSONArray(c.getString(7))
                out += Alert(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), RiskLevel.valueOf(c.getString(4)), c.getDouble(5),
                    c.getString(6), (0 until arr.length()).map { arr.getString(it) }, c.getString(8))
            }
        }
        return out
    }

    fun delete(id: Long) { writableDatabase.delete("alerts", "id = ?", arrayOf(id.toString())); _version.value++ }
    fun clear() { writableDatabase.delete("alerts", null, null); _version.value++ }
}
