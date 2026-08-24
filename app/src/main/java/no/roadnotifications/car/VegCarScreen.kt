package no.roadnotifications.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import no.roadnotifications.R

class VegCarScreen(carContext: CarContext) : Screen(carContext) {
    init {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                VegCarAlertStore.lastAlert.collect {
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val lastAlertText = VegCarAlertStore.lastAlert.value
        val bodyText = if (lastAlertText.isNullOrBlank()) {
            carContext.getString(R.string.car_screen_tracking_active)
        } else {
            carContext.getString(R.string.car_screen_last_alert_prefix, lastAlertText)
        }
        val header = Header.Builder()
            .setStartHeaderAction(Action.APP_ICON)
            .setTitle(carContext.getString(R.string.app_name))
            .build()
        return MessageTemplate.Builder(bodyText)
            .setHeader(header)
            .build()
    }
}
