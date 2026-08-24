package no.roadnotifications.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto host entry point.
 *
 * Sideloaded builds need Unknown sources in Android Auto developer settings
 * (tap Version about 10 times) after installing the APK locally.
 */
class VegCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return VegCarSession()
    }
}
