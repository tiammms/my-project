package ai.neuroshield.app.core

/**
 * One written form for one phone number, so that a number marked from an SMS ("87015550888") is recognised when
 * it calls ("+77015550888").
 *
 * Kazakhstan (and Russia) share the country code +7, the national (significant) number has 10 digits, and the
 * domestic trunk prefix is 8. So for a user whose home code is +7:
 *   8 701 555 08 88  ->  +77015550888   (11 digits, trunk prefix 8)
 *   7 701 555 08 88  ->  +77015550888   (11 digits, country code without "+")
 *     701 555 08 88  ->  +77015550888   (10 digits starting with 7: a Kazakh mobile number written without prefix)
 *   00 44 7911 123456 / 810 44 7911 123456  ->  +447911123456   (international dialling prefixes)
 * Everything else keeps its digits as they are: short service numbers ("7700", "1414"), numbers of other
 * lengths and, for other home codes, national numbers are NOT merged, because there is no reliable rule for them.
 */
object PhoneNumbers {
    /** The user's home calling code (setting "home_code"); set by the app at start and when the setting changes. */
    @Volatile var homeCode: String = "+7"

    fun normalize(raw: String?, home: String = homeCode): String? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        val d = t.filter { it.isDigit() }
        if (d.isEmpty()) return null
        if (t.startsWith("+")) return "+$d"
        if (d.startsWith("00") && d.length > 6) return "+" + d.drop(2)
        if (home == "+7") {
            if (d.startsWith("810") && d.length > 11) return "+" + d.drop(3)
            if (d.length == 11 && (d[0] == '8' || d[0] == '7')) return "+7" + d.drop(1)
            if (d.length == 10 && d[0] == '7') return "+7$d"
        }
        return d
    }

    /**
     * Forms in which earlier versions (before 1.0.84) may have stored the same number, for looking up old records.
     * Only for numbers in the +7 zone; other numbers were stored as they are now.
     */
    fun storedForms(normalized: String): List<String> {
        if (!normalized.startsWith("+7") || normalized.length != 12) return listOf(normalized)
        val nsn = normalized.drop(2)
        return listOf(normalized, "8$nsn", "7$nsn", nsn)
    }
}
