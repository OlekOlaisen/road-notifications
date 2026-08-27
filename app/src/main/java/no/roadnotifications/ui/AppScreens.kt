package no.roadnotifications.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import no.roadnotifications.data.VegObjektType
import no.roadnotifications.settings.AlertCategories
import no.roadnotifications.settings.AlertPreferences

@Composable
fun HomeScreen(
    statusMessage: String,
    isTracking: Boolean,
    onToggleTracking: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Vegassistent",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = onToggleTracking,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = if (isTracking) "Stopp sporing" else "Start sporing",
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
fun AlertsSettingsScreen(
    alertPreferences: AlertPreferences,
) {
    val alertCategories = remember { AlertCategories.toggleable }
    val enabledByKey = remember {
        mutableStateMapOf<String, Boolean>().apply {
            alertCategories.forEach { category ->
                put(
                    category.preferenceKey,
                    alertPreferences.isEnabled(category.type, category.verdi),
                )
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Varsler",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Velg hvilke skilt som skal varsles under sporing. Scroll for alle. Valgene huskes.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        alertCategories.forEach { category ->
            val preferenceKey = category.preferenceKey
            val enabled = enabledByKey[preferenceKey] == true
            AlertCategoryRow(
                label = category.label,
                signDrawableRes = category.signDrawableRes,
                enabled = enabled,
                onEnabledChange = { isEnabled ->
                    enabledByKey[preferenceKey] = isEnabled
                    alertPreferences.setEnabled(category.type, category.verdi, isEnabled)
                },
            )
        }
    }
}

@Composable
private fun AlertCategoryRow(
    label: String,
    signDrawableRes: Int,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEnabledChange(!enabled) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(id = signDrawableRes),
            contentDescription = label,
            modifier = Modifier.size(40.dp),
            contentScale = ContentScale.Fit,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
    }
}

@Composable
fun TestAlertsScreen(
    onTestAlert: (type: String, verdi: String?) -> Unit,
    onTestCombined: () -> Unit,
) {
    val testAlerts = remember {
        AlertCategories.toggleable.map { category ->
            val testVerdi = when {
                category.type == VegObjektType.BOM.name -> "42"
                category.type == VegObjektType.JERNBANE.name -> "I plan"
                category.type == VegObjektType.FERJEKAI.name -> "Dokka"
                category.type == VegObjektType.STREKNINGS_ATK.name -> "Lærdalstunnelen"
                category.type == VegObjektType.KOMMUNE.name -> "Oslo"
                else -> category.verdi
            }
            val label = when (category.type) {
                VegObjektType.BOM.name -> "Bomstasjon 42 kr"
                else -> category.label
            }
            TestAlertItem(label) { onTestAlert(category.type, testVerdi) }
        } + TestAlertItem("Forkjørsvei - Fartsgrense 50", onTestCombined)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Test varsler",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item {
            Text(
                text = "Sender ekte varsler på telefonen (og Auto hvis tilkoblet). Scroll for flere.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(
            items = testAlerts,
            key = { item -> item.label },
        ) { item ->
            TestAlertButton(label = item.label, onClick = item.onClick)
        }
    }
}

private data class TestAlertItem(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
private fun TestAlertButton(
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = label)
    }
}
