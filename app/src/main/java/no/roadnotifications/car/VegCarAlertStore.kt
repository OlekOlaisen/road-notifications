package no.roadnotifications.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VegCarAlertStore {
    private val lastAlertMessage = MutableStateFlow<String?>(null)

    val lastAlert: StateFlow<String?> = lastAlertMessage.asStateFlow()

    fun recordAlert(messageText: String) {
        lastAlertMessage.value = messageText
    }
}
