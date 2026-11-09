package ai.neuroshield.app.alert

import ai.neuroshield.app.I18n
import ai.neuroshield.app.app
import ai.neuroshield.app.core.FamilyAlert
import ai.neuroshield.app.tr
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens the SMS app (to the trusted person set in Settings) or the share sheet with the message filled in. */
object TellFamily {
    fun label(ctx: Context): String {
        val p = ctx.app.prefs
        return if (p.trustedNumber.isNotBlank()) tr("Написать: ", "Жазу: ") + p.trustedName.ifBlank { tr("близкому", "жақын адамға") }
        else tr("Сообщить близкому", "Жақын адамға хабарлау")
    }

    fun open(ctx: Context, source: String, number: String?, titleRu: String) {
        val p = ctx.app.prefs
        val text = FamilyAlert.text(I18n.kk, source, number, I18n.core(titleRu))
        val i = if (p.trustedNumber.isNotBlank())
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.trustedNumber))).putExtra("sms_body", text)
        else Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            tr("Сообщить близкому", "Жақын адамға хабарлау"))
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}
