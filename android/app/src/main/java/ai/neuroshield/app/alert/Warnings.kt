package ai.neuroshield.app.alert

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.neuroshield.app.MainActivity
import ai.neuroshield.app.NeuroShieldApp
import ai.neuroshield.app.Perms
import ai.neuroshield.app.R
import ai.neuroshield.app.app
import ai.neuroshield.app.core.RiskLevel
import androidx.core.app.NotificationCompat

data class Warning(
    val level: RiskLevel,
    val title: String,
    val reasons: List<String>,
    val advice: List<String> = emptyList(),
    val source: String,
    val number: String? = null,
    val score: Double = 0.0,
    val excerpt: String? = null,
    val offerListening: Boolean = false,
    val isTest: Boolean = false,
)

/**
 * Shows warnings: a window on top of other apps (also on top of the call screen) when the user allowed it,
 * otherwise a high-priority notification. Every real warning is written to the local history.
 */
object Warnings {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var offerView: View? = null
    private const val NOTIF_ALERT = 1001
    const val NOTIF_OFFER = 1002

    fun headline(level: RiskLevel) = when (level) {
        RiskLevel.HIGH -> "⚠️ ВНИМАНИЕ! ВОЗМОЖНО, МОШЕННИК!"
        RiskLevel.MEDIUM -> "⚠️ Осторожно: есть признаки обмана"
        RiskLevel.LOW -> "Незнакомый номер"
    }

    fun show(ctx: Context, w: Warning) {
        val app = ctx.app
        if (!w.isTest) app.alerts.add(w.source, w.number, w.level, w.score, w.title, w.reasons,
            if (app.prefs.storeExcerpts) w.excerpt?.take(600) else null)
        if (w.level != RiskLevel.LOW) vibrate(ctx, w.level)
        main.post {
            if (Perms.overlay(ctx)) showOverlay(ctx.applicationContext, w) else notify(ctx, w)
            if (w.level == RiskLevel.HIGH && Perms.overlay(ctx)) notify(ctx, w)   // also stays in the shade
        }
    }

    fun hide() = main.post { removeView(view); view = null }

    private fun removeView(v: View?) {
        if (v == null) return
        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
    }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun params(ctx: Context, focusable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.CENTER }   // centre: the system's incoming-call / SMS banners sit at the top

    @SuppressLint("SetTextI18n")
    private fun showOverlay(ctx: Context, w: Warning) {
        removeView(view)
        val bg = when (w.level) { RiskLevel.HIGH -> 0xFFB3261E.toInt(); RiskLevel.MEDIUM -> 0xFFB86E00.toInt(); RiskLevel.LOW -> 0xFF1F2935.toInt() }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18f), dp(ctx, 16f), dp(ctx, 18f), dp(ctx, 12f))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(ctx, 18f).toFloat() }
            elevation = dp(ctx, 12f).toFloat()
        }
        fun text(s: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(ctx).apply {
            text = s; setTextColor(Color.WHITE); textSize = size; this.alpha = alpha
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(ctx, 2f), 0, dp(ctx, 2f))
        }
        if (w.isTest) box.addView(text("ПРИМЕР ПРЕДУПРЕЖДЕНИЯ (тест, не настоящий звонок)", 12f, true, 0.85f))
        box.addView(text(headline(w.level), if (w.level == RiskLevel.HIGH) 22f else 19f, true))
        box.addView(text("${w.level.ru} · ${w.title}", 15f, true, 0.95f))
        w.number?.let { box.addView(text("Номер: $it", 13f, alpha = 0.85f)) }
        w.reasons.take(4).forEach { box.addView(text("• $it", 14f)) }
        w.advice.take(2).forEach { box.addView(text("→ $it", 14f, bold = true)) }
        // Buttons share the width equally so that "Понятно" is always visible, even on narrow screens
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 10f), 0, 0) }
        fun button(label: String, strong: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
            text = label; setTextColor(if (strong) bg else Color.WHITE); isAllCaps = false; textSize = 13f; maxLines = 2
            background = GradientDrawable().apply { setColor(if (strong) Color.WHITE else 0x33FFFFFF); cornerRadius = dp(ctx, 12f).toFloat() }
            setPadding(dp(ctx, 4f), 0, dp(ctx, 4f), 0); minWidth = 0; minimumWidth = 0
            setOnClickListener { onClick() }
        }
        val actions = mutableListOf<Button>()
        if (w.offerListening) actions += button("Слушать звонок") { removeView(view); view = null; openListen(ctx) }
        actions += button("Подробнее") { removeView(view); view = null; openApp(ctx, "history") }
        actions += button("Понятно", strong = true) { removeView(view); view = null }
        actions.forEachIndexed { i, b ->
            row.addView(b, LinearLayout.LayoutParams(0, dp(ctx, 46f), 1f).apply { if (i > 0) marginStart = dp(ctx, 8f) })
        }
        box.addView(row)
        val wrap = LinearLayout(ctx).apply { setPadding(dp(ctx, 10f), 0, dp(ctx, 10f), 0); addView(box) }
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(wrap, params(ctx, false))
            view = wrap
        } catch (e: Exception) { notify(ctx, w) }
        if (w.level == RiskLevel.LOW && !w.isTest) main.postDelayed({ if (view === wrap) { removeView(wrap); view = null } }, 12_000)
    }

    /** Small chip during a call with an unknown number: one tap opens the app and starts speech analysis. */
    fun showListenOffer(ctx: Context) = main.post {
        if (!Perms.overlay(ctx) || offerView != null) return@post
        val c = ctx.applicationContext
        val b = Button(c).apply {
            text = "🛡 NeuroShield: проверить звонок (громкая связь)"; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
            background = GradientDrawable().apply { setColor(0xEE0B1016.toInt()); cornerRadius = dp(c, 24f).toFloat(); setStroke(dp(c, 2f), 0xFF2CC5B0.toInt()) }
            setPadding(dp(c, 16f), 0, dp(c, 16f), 0)
            setOnClickListener { hideListenOffer(); openListen(c) }
        }
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, dp(c, 48f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(c, 150f) }
        try { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(b, p); offerView = b } catch (_: Exception) {}
    }

    fun hideListenOffer() = main.post { removeView(offerView); offerView = null }

    private var videoOfferView: View? = null

    /** Chip during a messenger video call: one tap starts the 30-second check of the caller's face on screen. */
    fun showVideoOffer(ctx: Context) = main.post {
        if (!Perms.overlay(ctx) || videoOfferView != null) return@post
        val c = ctx.applicationContext
        val b = Button(c).apply {
            text = "🛡 NeuroShield: проверить видео (30 с)"; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
            background = GradientDrawable().apply { setColor(0xEE0B1016.toInt()); cornerRadius = dp(c, 24f).toFloat(); setStroke(dp(c, 2f), 0xFF2CC5B0.toInt()) }
            setPadding(dp(c, 16f), 0, dp(c, 16f), 0)
            setOnClickListener { hideVideoOffer(); openVideoCheck(c) }
        }
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, dp(c, 48f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(c, 210f) }
        try { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(b, p); videoOfferView = b } catch (_: Exception) {}
    }

    fun hideVideoOffer() = main.post { removeView(videoOfferView); videoOfferView = null }

    fun openVideoCheck(ctx: Context) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_START_VIDEO, true)
        })
    }

    fun openListen(ctx: Context, auto: Boolean = false) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_START_LISTEN, true)
            putExtra(MainActivity.EXTRA_AUTO, auto)
        })
    }

    fun openApp(ctx: Context, route: String) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_ROUTE, route)
        })
    }

    fun listenIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 7,
        Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP); putExtra(MainActivity.EXTRA_START_LISTEN, true)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notify(ctx: Context, w: Warning) {
        if (!Perms.notifications(ctx)) return
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP); putExtra(MainActivity.EXTRA_ROUTE, "history")
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = (listOf("${w.level.ru} · ${w.title}") + w.reasons.take(4).map { "• $it" } + w.advice.take(2).map { "→ $it" }).joinToString("\n")
        val n = NotificationCompat.Builder(ctx, NeuroShieldApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(headline(w.level))
            .setContentText(w.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(if (w.level == RiskLevel.HIGH) 0xFFB3261E.toInt() else 0xFFB86E00.toInt())
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply { if (w.offerListening) addAction(0, "Слушать звонок", listenIntent(ctx)) }
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n)
    }

    fun notifyListenOffer(ctx: Context, number: String?) {
        if (!Perms.notifications(ctx)) return
        val n = NotificationCompat.Builder(ctx, NeuroShieldApp.CH_CALL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Звонок с незнакомого номера")
            .setContentText("Включите громкую связь и нажмите «Слушать», чтобы проверить разговор")
            .setOngoing(true)
            .addAction(0, "Слушать звонок", listenIntent(ctx))
            .setContentIntent(listenIntent(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_OFFER, n)
    }

    fun cancelListenOffer(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_OFFER)

    private fun vibrate(ctx: Context, level: RiskLevel) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            val pattern = if (level == RiskLevel.HIGH) longArrayOf(0, 400, 150, 400, 150, 600) else longArrayOf(0, 300)
            v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }
}
