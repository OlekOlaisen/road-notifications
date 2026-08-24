package no.roadnotifications.settings

import no.roadnotifications.R
import no.roadnotifications.data.VegObjektType

data class AlertCategory(
    val type: String,
    val verdi: String?,
    val label: String,
    val signDrawableRes: Int,
) {
    val preferenceKey: String
        get() = AlertPreferences.preferenceKey(type, verdi)
}

object AlertCategories {
    val toggleable: List<AlertCategory> = listOf(
        speed("30"),
        speed("40"),
        speed("50"),
        speed("60"),
        speed("70"),
        speed("80"),
        speed("90"),
        speed("100"),
        speed("110"),
        category(
            type = VegObjektType.FORKJOERSVEI.name,
            label = "Forkjørsvei",
            signDrawableRes = R.drawable.sign_206,
        ),
        category(
            type = VegObjektType.FOTOBOKS.name,
            label = "Fotoboks",
            signDrawableRes = R.drawable.sign_556,
        ),
        category(
            type = VegObjektType.BOM.name,
            label = "Bomstasjon",
            signDrawableRes = R.drawable.sign_792_30,
        ),
        category(
            type = VegObjektType.VILTFARE.name,
            verdi = "ELG",
            label = "Viltfare — elg",
            signDrawableRes = R.drawable.sign_146_1,
        ),
        category(
            type = VegObjektType.VILTFARE.name,
            verdi = "HJORT",
            label = "Viltfare — hjort",
            signDrawableRes = R.drawable.sign_146_2,
        ),
        category(
            type = VegObjektType.VILTFARE.name,
            verdi = "REIN",
            label = "Viltfare — rein",
            signDrawableRes = R.drawable.sign_146_3,
        ),
        category(
            type = VegObjektType.VILTFARE.name,
            verdi = "RADYR",
            label = "Viltfare — rådyr",
            signDrawableRes = R.drawable.sign_146_4,
        ),
        category(
            type = VegObjektType.VILTFARE.name,
            verdi = "ANNET",
            label = "Viltfare — annet",
            signDrawableRes = R.drawable.sign_146_5,
        ),
        category(
            type = VegObjektType.JERNBANE.name,
            label = "Jernbaneovergang",
            signDrawableRes = R.drawable.sign_134,
        ),
        category(
            type = VegObjektType.FERJEKAI.name,
            label = "Ferjekai",
            signDrawableRes = R.drawable.sign_120,
        ),
        category(
            type = VegObjektType.STOPP.name,
            verdi = "204",
            label = "Stopp",
            signDrawableRes = R.drawable.sign_204,
        ),
        category(
            type = VegObjektType.FARLIG_SVING.name,
            verdi = "100.1",
            label = "Farlig sving — høyre",
            signDrawableRes = R.drawable.sign_100_1,
        ),
        category(
            type = VegObjektType.FARLIG_SVING.name,
            verdi = "100.2",
            label = "Farlig sving — venstre",
            signDrawableRes = R.drawable.sign_100_2,
        ),
        category(
            type = VegObjektType.FARLIG_SVING.name,
            verdi = "102.1",
            label = "Farlige svinger — høyre",
            signDrawableRes = R.drawable.sign_102_1,
        ),
        category(
            type = VegObjektType.FARLIG_SVING.name,
            verdi = "102.2",
            label = "Farlige svinger — venstre",
            signDrawableRes = R.drawable.sign_102_2,
        ),
        category(
            type = VegObjektType.SMALERE_VEG.name,
            verdi = "106.1",
            label = "Smalere veg — begge sider",
            signDrawableRes = R.drawable.sign_106_1,
        ),
        category(
            type = VegObjektType.SMALERE_VEG.name,
            verdi = "106.2",
            label = "Smalere veg — høyre",
            signDrawableRes = R.drawable.sign_106_2,
        ),
        category(
            type = VegObjektType.SMALERE_VEG.name,
            verdi = "106.3",
            label = "Smalere veg — venstre",
            signDrawableRes = R.drawable.sign_106_3,
        ),
        category(
            type = VegObjektType.TUNNEL.name,
            verdi = "122",
            label = "Tunnel",
            signDrawableRes = R.drawable.sign_122,
        ),
        category(
            type = VegObjektType.SLUTT_FORKJOERSVEI.name,
            verdi = "208",
            label = "Slutt på forkjørsvei",
            signDrawableRes = R.drawable.sign_208,
        ),
    )

    private fun speed(speedLimit: String): AlertCategory {
        val drawableRes = when (speedLimit) {
            "30" -> R.drawable.sign_362_30
            "40" -> R.drawable.sign_362_40
            "50" -> R.drawable.sign_362_50
            "60" -> R.drawable.sign_362_60
            "70" -> R.drawable.sign_362_70
            "80" -> R.drawable.sign_362_80
            "90" -> R.drawable.sign_362_90
            "100" -> R.drawable.sign_362_100
            "110" -> R.drawable.sign_362_110
            else -> R.drawable.ic_notification_fart
        }
        return category(
            type = VegObjektType.FART.name,
            verdi = speedLimit,
            label = "Fartsgrense $speedLimit",
            signDrawableRes = drawableRes,
        )
    }

    private fun category(
        type: String,
        label: String,
        signDrawableRes: Int,
        verdi: String? = null,
    ): AlertCategory {
        return AlertCategory(
            type = type,
            verdi = verdi,
            label = label,
            signDrawableRes = signDrawableRes,
        )
    }
}
