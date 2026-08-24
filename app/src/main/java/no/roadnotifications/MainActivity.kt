package no.roadnotifications

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import no.roadnotifications.notification.VegNotificationManager
import no.roadnotifications.service.VegTrackingService
import no.roadnotifications.settings.AlertPreferences
import no.roadnotifications.ui.AlertsSettingsScreen
import no.roadnotifications.ui.HomeScreen
import no.roadnotifications.ui.TestAlertsScreen
import no.roadnotifications.ui.theme.RoadNotificationsTheme

private enum class MainTab {
    Home,
    Varsler,
    Test,
}

class MainActivity : ComponentActivity() {
    private val statusMessage = mutableStateOf("Starter sporing…")
    private val isTracking = mutableStateOf(false)
    private var pendingStart = false
    private lateinit var vegNotificationManager: VegNotificationManager
    private lateinit var alertPreferences: AlertPreferences

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!locationGranted) {
            statusMessage.value = "Posisjonstillatelse er nødvendig."
            pendingStart = false
            isTracking.value = false
            return@registerForActivityResult
        }
        requestNextPermissionOrStart()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            statusMessage.value = "Bakgrunnsposisjon ble avslått. Sporing virker bare når appen er synlig."
        }
        requestNextPermissionOrStart()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            // Sideloaded Auto also needs Unknown sources in Android Auto developer settings.
            statusMessage.value = "Varsler er avslått. Android Auto-meldinger vises ikke."
        }
        requestNextPermissionOrStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alertPreferences = AlertPreferences(applicationContext)
        vegNotificationManager = VegNotificationManager(applicationContext)
        VegNotificationManager.createChannels(applicationContext)
        setContent {
            RoadNotificationsTheme {
                var selectedTab by remember { mutableStateOf(MainTab.Home) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == MainTab.Home,
                                onClick = { selectedTab = MainTab.Home },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Filled.Home,
                                        contentDescription = "Hjem",
                                    )
                                },
                                label = { Text("Hjem") },
                            )
                            NavigationBarItem(
                                selected = selectedTab == MainTab.Varsler,
                                onClick = { selectedTab = MainTab.Varsler },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Filled.Notifications,
                                        contentDescription = "Varsler",
                                    )
                                },
                                label = { Text("Varsler") },
                            )
                            NavigationBarItem(
                                selected = selectedTab == MainTab.Test,
                                onClick = { selectedTab = MainTab.Test },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Filled.Science,
                                        contentDescription = "Test",
                                    )
                                },
                                label = { Text("Test") },
                            )
                        }
                    },
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        when (selectedTab) {
                            MainTab.Home -> HomeScreen(
                                statusMessage = statusMessage.value,
                                isTracking = isTracking.value,
                                onToggleTracking = {
                                    if (isTracking.value) {
                                        stopTracking()
                                    } else {
                                        startTrackingClicked()
                                    }
                                },
                            )
                            MainTab.Varsler -> AlertsSettingsScreen(
                                alertPreferences = alertPreferences,
                            )
                            MainTab.Test -> TestAlertsScreen(
                                onTestAlert = { type, verdi ->
                                    vegNotificationManager.postTestAlert(type, verdi)
                                },
                                onTestCombined = {
                                    vegNotificationManager.postTestCombinedAlert()
                                },
                            )
                        }
                    }
                }
            }
        }
        startTrackingClicked()
    }

    private fun startTrackingClicked() {
        pendingStart = true
        requestNextPermissionOrStart()
    }

    private fun requestNextPermissionOrStart() {
        if (!pendingStart) {
            return
        }
        val missingLocation = missingLocationPermissions()
        if (missingLocation.isNotEmpty()) {
            locationPermissionLauncher.launch(missingLocation.toTypedArray())
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        pendingStart = false
        startTrackingService()
    }

    private fun missingLocationPermissions(): List<String> {
        val needed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        return needed.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startTrackingService() {
        val intent = Intent(this, VegTrackingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isTracking.value = true
        statusMessage.value = "Sporing kjører i bakgrunnen."
    }

    private fun stopTracking() {
        pendingStart = false
        stopService(Intent(this, VegTrackingService::class.java))
        isTracking.value = false
        statusMessage.value = "Sporing er stoppet."
    }
}
