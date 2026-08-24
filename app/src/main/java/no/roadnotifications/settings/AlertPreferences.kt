package no.roadnotifications.settings

import android.content.Context
import no.roadnotifications.data.VegObjektType

class AlertPreferences(context: Context) {
    private val sharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isEnabled(objektType: String, verdi: String? = null): Boolean {
        val preferenceKey = preferenceKey(objektType, verdi)
        if (sharedPreferences.contains(preferenceKey)) {
            return sharedPreferences.getBoolean(preferenceKey, true)
        }
        if (preferenceKey != objektType && sharedPreferences.contains(objektType)) {
            return sharedPreferences.getBoolean(objektType, true)
        }
        return true
    }

    fun isEnabled(objektType: VegObjektType, verdi: String? = null): Boolean {
        return isEnabled(objektType.name, verdi)
    }

    fun setEnabled(preferenceKey: String, enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(preferenceKey, enabled)
            .apply()
    }

    fun setEnabled(objektType: String, verdi: String?, enabled: Boolean) {
        setEnabled(preferenceKey(objektType, verdi), enabled)
    }

    companion object {
        private const val PREFERENCES_NAME = "alert_preferences"

        fun preferenceKey(objektType: String, verdi: String?): String {
            val normalizedVerdi = verdi?.trim().orEmpty()
            return when (objektType) {
                VegObjektType.FART.name -> {
                    val speedText = normalizedVerdi.takeWhile { character -> character.isDigit() }
                    if (speedText.isEmpty()) objektType else "$objektType:$speedText"
                }
                VegObjektType.FARLIG_SVING.name,
                VegObjektType.SMALERE_VEG.name,
                -> {
                    if (normalizedVerdi.isEmpty()) objektType else "$objektType:$normalizedVerdi"
                }
                VegObjektType.VILTFARE.name -> {
                    if (normalizedVerdi.isEmpty()) {
                        objektType
                    } else {
                        "$objektType:${normalizedVerdi.uppercase()}"
                    }
                }
                else -> objektType
            }
        }
    }
}
