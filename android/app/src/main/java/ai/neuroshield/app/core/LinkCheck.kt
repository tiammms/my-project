package ai.neuroshield.app.core

/**
 * Phishing-link check for SMS and messenger texts. Rule-based and explainable, works offline.
 * It flags links that imitate known brands, hide their target (shorteners), use raw IP addresses,
 * punycode look-alikes, throw-away domains or credential pages. It never opens the link.
 */
object LinkCheck {
    data class LinkFinding(val url: String, val host: String, val score: Int, val reasons: List<String>)

    private val urlRx = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"'«»]+|(?:[a-z0-9-]+\.)+(?:kz|ru|com|net|org|top|xyz|click|icu|live|online|site|shop|buzz|rest|info|link|io|me)(?:/[^\s<>"'«»]*)?)""")

    /** brand keyword -> its official registrable domains */
    private val BRANDS = mapOf(
        "kaspi" to setOf("kaspi.kz"), "halyk" to setOf("halykbank.kz", "homebank.kz"), "homebank" to setOf("homebank.kz"),
        "forte" to setOf("forte.kz", "fortebank.com"), "jusan" to setOf("jusan.kz"), "bcc" to setOf("bcc.kz"),
        "freedom" to setOf("freedombank.kz", "ffin.kz"), "egov" to setOf("egov.kz"), "olx" to setOf("olx.kz"),
        "kolesa" to setOf("kolesa.kz"), "krisha" to setOf("krisha.kz"), "sber" to setOf("sberbank.ru", "sber.ru", "sberbank.kz"),
        "tinkoff" to setOf("tinkoff.ru", "tbank.ru"), "gosuslugi" to setOf("gosuslugi.ru"), "whatsapp" to setOf("whatsapp.com", "wa.me"),
        "telegram" to setOf("telegram.org", "t.me"), "google" to setOf("google.com", "google.kz"), "apple" to setOf("apple.com"),
    )
    private val SHORTENERS = setOf("bit.ly", "clck.ru", "tinyurl.com", "goo.su", "cutt.ly", "is.gd", "t.co", "rb.gy", "u.to", "vk.cc", "shorturl.at")
    private val RISKY_TLDS = setOf("top", "xyz", "click", "icu", "live", "online", "site", "shop", "buzz", "rest", "link", "info", "cfd", "sbs")
    private val CRED_WORDS = Regex("(?i)(login|signin|verify|verif|secure|account|update|confirm|bonus|prize|vyigrysh|kompensac|vozvrat|parol|password)")

    fun extract(text: String): List<String> = urlRx.findAll(text).map { it.value.trimEnd('.', ',', ')', '!', '?', ';') }.distinct().take(10).toList()

    fun host(url: String): String {
        val noScheme = url.replace(Regex("(?i)^https?://"), "")
        return noScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':').lowercase()
    }

    /** "secure.kaspi.kz" -> "kaspi.kz" (good enough for one-level public suffixes like .kz/.ru/.com) */
    fun registrable(host: String): String = host.split('.').takeLast(2).joinToString(".")

    fun check(url: String): LinkFinding {
        val h = host(url)
        val reg = registrable(h)
        val reasons = mutableListOf<String>()
        var score = 0
        if (h in SHORTENERS || reg in SHORTENERS) { score += 35; reasons += "Короткая ссылка скрывает настоящий адрес" }
        if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(h)) { score += 50; reasons += "Вместо адреса сайта — IP-адрес" }
        if (h.split('.').any { it.startsWith("xn--") }) { score += 45; reasons += "Адрес с похожими буквами (punycode) — так подделывают известные сайты" }
        val tld = h.substringAfterLast('.')
        if (tld in RISKY_TLDS) { score += 25; reasons += "Домен .$tld часто используют для одноразовых мошеннических сайтов" }
        for ((brand, official) in BRANDS) {
            if (h.contains(brand) && reg !in official && official.none { h == it || h.endsWith(".$it") }) {
                score += 55; reasons += "Похоже на «${brand.replaceFirstChar { it.uppercase() }}», но это не официальный сайт (${official.first()})"
                break
            }
        }
        if (h.count { it == '-' } >= 2) { score += 10; reasons += "Много дефисов в адресе" }
        if (CRED_WORDS.containsMatchIn(url) && score > 0) { score += 15; reasons += "Страница просит войти или «подтвердить» данные" }
        if (url.contains('@')) { score += 20; reasons += "Символ @ в ссылке маскирует настоящий адрес" }
        return LinkFinding(url, h, score.coerceAtMost(100), reasons)
    }

    fun scan(text: String): List<LinkFinding> = extract(text).map { check(it) }.filter { it.score > 0 }
}
