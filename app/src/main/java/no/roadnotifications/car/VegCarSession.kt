package no.roadnotifications.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class VegCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return VegCarScreen(carContext)
    }
}
