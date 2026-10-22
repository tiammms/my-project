package ai.neuroshield.app.alert

import ai.neuroshield.app.tr
import ai.neuroshield.app.I18n

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import ai.neuroshield.app.MainActivity
import ai.neuroshield.app.NeuroShieldApp
import ai.neuroshield.app.LockScreen
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
    /** Demo scenario: scripted input, real analysis. Saved to history with the «Демо» mark, shown with a «ДЕМО» label. */
    val demo: Boolean = false,
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
        RiskLevel.HIGH -> tr("⚠️ ВНИМАНИЕ! ВОЗМОЖНО, МОШЕННИК!", "⚠️ НАЗАР АУДАРЫҢЫЗ! АЛАЯҚ БОЛУЫ МҮМКІН!")
        RiskLevel.MEDIUM -> tr("⚠️ Осторожно: есть признаки обмана", "⚠️ Абайлаңыз: алдау белгілері бар")
        RiskLevel.LOW -> tr("Незнакомый номер", "Бейтаныс нөмір")
    }

    fun show(ctx: Context, w: Warning) {
        val app = ctx.app
        val id = if (!w.isTest) app.alerts.add(w.source, w.number, w.level, w.score, w.title, w.reasons,
            if (app.prefs.storeExcerpts) w.excerpt?.take(600) else null) else -1L
        if (w.level != RiskLevel.LOW) vibrate(ctx, w.level)
        // stored in Russian (history reads the same everywhere); shown in the interface language
        val d = w.copy(title = I18n.core(w.title), reasons = w.reasons.map { I18n.core(it) }, advice = w.advice.map { I18n.core(it) })
        main.post {
            if (Perms.overlay(ctx)) showOverlay(ctx.applicationContext, d, id) else notify(ctx, d, id)
            if (d.level == RiskLevel.HIGH && Perms.overlay(ctx)) notify(ctx, d, id)   // also stays in the shade
        }
    }

    fun hide() = main.post { dismiss(view); view = null }

    private fun removeView(v: View?) {
        if (v == null) return
        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
    }

    /** Slide up + fade out, then remove. */
    private fun dismiss(v: View?) {
        if (v == null) return
        v.animate().alpha(0f).translationY(-dp(v.context, 24f).toFloat()).scaleX(0.97f).scaleY(0.97f).setDuration(170)
            .withEndAction { removeView(v) }.start()
    }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun params(ctx: Context, focusable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.CENTER }   // centre: the system's incoming-call / SMS banners sit at the top

    private const val BG = 0xF20F1115.toInt()
    private const val SURFACE = 0xFF1D2129.toInt()
    private const val TEXT = 0xFFF2F4F7.toInt()
    private const val TEXT2 = 0xFFB4BCC8.toInt()
    private const val MUTED = 0xFF7A8494.toInt()
    private const val CYAN = 0xFF22D3EE.toInt()

    fun levelColor(l: RiskLevel) = when (l) { RiskLevel.HIGH -> 0xFFFF3B5C.toInt(); RiskLevel.MEDIUM -> 0xFFFFB020.toInt(); RiskLevel.LOW -> CYAN }
    private fun alpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a * 255).toInt() shl 24)

    /**
     * Compact threat card, readable in 1-2 seconds: what (level + headline), why (one reason), what to do (one action).
     * Full analysis opens in the app («Подробнее»).
     */
    @SuppressLint("SetTextI18n")
    private fun showOverlay(ctx: Context, w: Warning, alertId: Long) {
        removeView(view)
        val c = levelColor(w.level)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18f), dp(ctx, 18f), dp(ctx, 18f), dp(ctx, 16f))
            background = GradientDrawable().apply { setColor(BG); cornerRadius = dp(ctx, 26f).toFloat(); setStroke(dp(ctx, 1.5f), alpha(c, 0.85f)) }
            elevation = dp(ctx, 18f).toFloat()
        }
        fun tv(s: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 2, spacing: Float = 0f) = TextView(ctx).apply {
            text = s; setTextColor(color); textSize = size; maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END
            letterSpacing = spacing
            typeface = Typeface.create("sans-serif" + if (bold) "-medium" else "", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

        // header: pulsing icon + label + headline
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val iconSize = dp(ctx, 46f)
        val iconBox = FrameLayout(ctx)
        val ring = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(ctx, 2f), c) } }
        val disc = TextView(ctx).apply {
            text = if (w.level == RiskLevel.LOW) "i" else "!"; gravity = Gravity.CENTER; setTextColor(c); textSize = 24f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(alpha(c, 0.16f)); setStroke(dp(ctx, 1f), alpha(c, 0.5f)) }
        }
        iconBox.addView(ring, FrameLayout.LayoutParams(iconSize, iconSize))
        iconBox.addView(disc, FrameLayout.LayoutParams(iconSize, iconSize))
        header.addView(iconBox, LinearLayout.LayoutParams(iconSize, iconSize))
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 14f), 0, 0, 0) }
        val label = when (w.level) { RiskLevel.HIGH -> tr("⚠ ВНИМАНИЕ", "⚠ НАЗАР АУДАРЫҢЫЗ"); RiskLevel.MEDIUM -> tr("ОСТОРОЖНО", "АБАЙЛАҢЫЗ"); RiskLevel.LOW -> "SAQTAN" }
        titles.addView(tv(if (w.isTest) tr("$label · ТЕСТ", "$label · ТЕСТ") else if (w.demo) tr("$label · ДЕМО", "$label · ДЕМО") else label, 11f, c, bold = true, lines = 1, spacing = 0.1f))
        val headlineText = when (w.level) { RiskLevel.HIGH -> tr("Возможно, мошенник!", "Алаяқ болуы мүмкін!"); RiskLevel.MEDIUM -> tr("Есть признаки обмана", "Алдау белгілері бар"); RiskLevel.LOW -> tr("Незнакомый номер", "Бейтаныс нөмір") }
        titles.addView(tv(headlineText, 21f, TEXT, bold = true, lines = 2).apply { setLineSpacing(0f, 0.95f) })
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(header)

        // danger meter: 3 neon segments + score
        val meter = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(ctx, 14f), 0, dp(ctx, 12f)) }
        val segs = (0 until 3).map { i ->
            View(ctx).apply {
                background = GradientDrawable().apply { setColor(if (i <= w.level.ordinal) c else alpha(c, 0.16f)); cornerRadius = dp(ctx, 3f).toFloat() }
                scaleX = 0f; pivotX = 0f
            }
        }
        segs.forEachIndexed { i, v -> meter.addView(v, LinearLayout.LayoutParams(0, dp(ctx, 6f), 1f).apply { if (i > 0) marginStart = dp(ctx, 5f) }) }
        val levelName = when (w.level) { RiskLevel.HIGH -> tr("Высокий", "Жоғары"); RiskLevel.MEDIUM -> tr("Средний", "Орташа"); RiskLevel.LOW -> tr("Низкий", "Төмен") }
        meter.addView(tv("  $levelName" + (if (w.score > 0) " · ${w.score.toInt()}" else ""), 12.5f, c, bold = true, lines = 1))
        card.addView(meter)

        // why (one line) + what to do (one action)
        card.addView(tv(w.title, 15.5f, TEXT, bold = true, lines = 2))
        w.number?.takeIf { it.any(Char::isDigit) }?.let { card.addView(tv(it, 13f, MUTED, lines = 1).apply { setPadding(0, dp(ctx, 2f), 0, 0) }) }
        val action = w.advice.firstOrNull() ?: w.reasons.firstOrNull()
        if (action != null) {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
                background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(ctx, 14f).toFloat() }
            }
            box.addView(tv("→", 16f, CYAN, bold = true, lines = 1).apply { setPadding(0, 0, dp(ctx, 10f), 0) })
            box.addView(tv(action, 14f, TEXT2, lines = 3), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 12f) })
        }

        // one-tap help for HIGH threats: call a trusted person or the bank (opens the dialer, never calls by itself)
        val pr = ctx.app.prefs
        if (w.level == RiskLevel.HIGH || w.isTest) {
            val help = mutableListOf<Pair<String, String>>()
            if (pr.trustedNumber.isNotBlank()) help += (tr("Позвонить: ", "Қоңырау шалу: ") + pr.trustedName.ifBlank { tr("близкому", "жақын адамға") }) to pr.trustedNumber
            if (pr.bankNumber.isNotBlank()) help += tr("Позвонить в банк", "Банкке қоңырау шалу") to pr.bankNumber
            if (help.isNotEmpty()) {
                val hr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 12f), 0, 0) }
                help.forEachIndexed { i, (label, num) ->
                    val b = Button(ctx).apply {
                        text = label; isAllCaps = false; textSize = 13f; maxLines = 1; stateListAnimator = null; setTextColor(TEXT)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        background = GradientDrawable().apply { cornerRadius = dp(ctx, 14f).toFloat(); setColor(SURFACE) }
                        setPadding(dp(ctx, 8f), 0, dp(ctx, 8f), 0); minWidth = 0; minimumWidth = 0
                        setOnClickListener {
                            dismiss(view); view = null
                            try { ctx.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + Uri.encode(num))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                        }
                    }
                    hr.addView(b, LinearLayout.LayoutParams(0, dp(ctx, 42f), 1f).apply { if (i > 0) marginStart = dp(ctx, 10f) })
                }
                card.addView(hr)
            }
        }

        // actions
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 14f), 0, 0) }
        fun button(text: String, primary: Boolean, onClick: () -> Unit) = Button(ctx).apply {
            this.text = text; isAllCaps = false; textSize = 14f; maxLines = 1; stateListAnimator = null
            setTextColor(if (primary) 0xFF07080A.toInt() else TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14f).toFloat()
                if (primary) setColor(c) else { setColor(0x00000000); setStroke(dp(ctx, 1f), 0xFF2F3644.toInt()) }
            }
            setPadding(dp(ctx, 6f), 0, dp(ctx, 6f), 0); minWidth = 0; minimumWidth = 0
            setOnClickListener { onClick() }
        }
        if (w.offerListening) {
            // full-width AI action above the main row so labels never get cut
            val listen = button(tr("◉  Слушать звонок через ИИ", "◉  Қоңырауды ЖИ арқылы тыңдау"), false) { dismiss(view); view = null; openListen(ctx) }.apply {
                setTextColor(CYAN)
                background = GradientDrawable().apply { cornerRadius = dp(ctx, 14f).toFloat(); setColor(alpha(CYAN, 0.08f)); setStroke(dp(ctx, 1f), alpha(CYAN, 0.5f)) }
            }
            card.addView(listen, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44f)).apply { topMargin = dp(ctx, 14f) })
            row.setPadding(0, dp(ctx, 10f), 0, 0)
        }
        val buttons = mutableListOf<Button>()
        buttons += button(tr("Подробнее", "Толығырақ"), false) { dismiss(view); view = null; openApp(ctx, "history", alertId) }
        buttons += button(tr("Понятно", "Түсінікті"), true) { dismiss(view); view = null }
        buttons.forEachIndexed { i, b -> row.addView(b, LinearLayout.LayoutParams(0, dp(ctx, 46f), 1f).apply { if (i > 0) marginStart = dp(ctx, 10f) }) }
        card.addView(row)

        val wrap = FrameLayout(ctx).apply { setPadding(dp(ctx, 14f), 0, dp(ctx, 14f), 0); addView(card) }
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(wrap, params(ctx, false))
            view = wrap
        } catch (e: Exception) { notify(ctx, w, alertId); return }

        // entrance: drop in with a soft overshoot, segments fill one by one, the icon ring keeps pulsing
        card.alpha = 0f; card.translationY = -dp(ctx, 36f).toFloat(); card.scaleX = 0.94f; card.scaleY = 0.94f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        segs.forEachIndexed { i, v -> v.animate().scaleX(1f).setStartDelay(250L + i * 110L).setDuration(260).start() }
        if (w.level != RiskLevel.LOW) {
            android.animation.ObjectAnimator.ofPropertyValuesHolder(ring,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
                android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f)).apply {
                duration = 1300; repeatCount = android.animation.ValueAnimator.INFINITE; start()
            }
        } else ring.alpha = 0f
        if (w.level == RiskLevel.LOW && !w.isTest && !w.demo) main.postDelayed({ if (view === wrap) { dismiss(wrap); view = null } }, 12_000)
    }

    /** Small chip during a call with an unknown number: one tap opens the app and starts speech analysis. */
    fun showListenOffer(ctx: Context) = main.post {
        if (!Perms.overlay(ctx) || offerView != null) return@post
        val c = ctx.applicationContext
        val b = Button(c).apply {
            text = tr("◉  Проверить звонок · Saqtan", "◉  Қоңырауды тексеру · Saqtan"); isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
            background = GradientDrawable().apply { setColor(0xF20F1115.toInt()); cornerRadius = dp(c, 24f).toFloat(); setStroke(dp(c, 1.5f), 0xFF22D3EE.toInt()) }
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

    private var promptView: TextView? = null

    /**
     * Instruction strip during the video-call check («Попросите собеседника повернуть голову…»). It never takes
     * touches, so the call app underneath stays fully usable. Without the overlay permission the same text is in
     * the ongoing notification and in the app.
     */
    fun showPrompt(ctx: Context, text: String) = main.post {
        if (!Perms.overlay(ctx)) return@post
        val c = ctx.applicationContext
        val v = promptView ?: TextView(c).apply {
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(c, 18f), dp(c, 12f), dp(c, 18f), dp(c, 12f))
            background = GradientDrawable().apply { setColor(0xF20F1115.toInt()); cornerRadius = dp(c, 18f).toFloat(); setStroke(dp(c, 1.5f), 0xFF8B7CF6.toInt()) }
        }.also { tv ->
            val p = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; y = dp(c, 56f); horizontalMargin = 0.04f }
            try { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(tv, p); promptView = tv } catch (_: Exception) { return@post }
        }
        v.text = text
    }

    fun hidePrompt() = main.post { removeView(promptView); promptView = null }

    private var videoOfferView: View? = null

    /** Chip during a messenger video call: one tap starts the 30-second check of the caller's face on screen. */
    fun showVideoOffer(ctx: Context) = main.post {
        if (!Perms.overlay(ctx) || videoOfferView != null) return@post
        val c = ctx.applicationContext
        val b = Button(c).apply {
            text = tr("◉  Проверить видео · 30 с", "◉  Бейнені тексеру · 30 с"); isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
            background = GradientDrawable().apply { setColor(0xF20F1115.toInt()); cornerRadius = dp(c, 24f).toFloat(); setStroke(dp(c, 1.5f), 0xFF22D3EE.toInt()) }
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
        ctx.startActivity(MainActivity.internalIntent(ctx).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_START_VIDEO, true)
        })
    }

    fun openListen(ctx: Context, auto: Boolean = false) {
        ctx.startActivity(MainActivity.internalIntent(ctx).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_START_LISTEN, true)
            putExtra(MainActivity.EXTRA_AUTO, auto)
        })
    }

    fun openApp(ctx: Context, route: String, alertId: Long = -1L) {
        ctx.startActivity(MainActivity.internalIntent(ctx).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_ROUTE, route)
            if (alertId > 0) putExtra(MainActivity.EXTRA_ALERT_ID, alertId)
        })
    }

    fun listenIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 7,
        MainActivity.internalIntent(ctx).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP); putExtra(MainActivity.EXTRA_START_LISTEN, true)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notify(ctx: Context, w: Warning, alertId: Long = -1L) {
        if (!Perms.notifications(ctx)) return
        val open = PendingIntent.getActivity(ctx, 3, MainActivity.internalIntent(ctx).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP); putExtra(MainActivity.EXTRA_ROUTE, "history")
            if (alertId > 0) putExtra(MainActivity.EXTRA_ALERT_ID, alertId)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = (listOf("${I18n.core(w.level.ru)} · ${w.title}") + w.reasons.take(4).map { "• $it" } + w.advice.take(2).map { "→ $it" }).joinToString("\n")
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
            .apply { if (w.offerListening) addAction(0, tr("Слушать звонок", "Қоңырауды тыңдау"), listenIntent(ctx)) }
            .let { LockScreen.hideDetails(it, ctx, NeuroShieldApp.CH_ALERTS) }
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n)
    }

    fun notifyListenOffer(ctx: Context, number: String?) {
        if (!Perms.notifications(ctx)) return
        val n = NotificationCompat.Builder(ctx, NeuroShieldApp.CH_CALL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(tr("Звонок с незнакомого номера", "Бейтаныс нөмірден қоңырау"))
            .setContentText(tr("Включите громкую связь и нажмите «Слушать», чтобы проверить разговор", "Әңгімені тексеру үшін дауыс зорайтқышты қосып, «Тыңдау» түймесін басыңыз"))
            .setOngoing(true)
            .addAction(0, tr("Слушать звонок", "Қоңырауды тыңдау"), listenIntent(ctx))
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
