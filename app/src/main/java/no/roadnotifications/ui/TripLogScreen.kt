package no.roadnotifications.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.roadnotifications.log.TripLog

@Composable
fun TripLogScreen() {
    val context = LocalContext.current
    val lines by TripLog.lines.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Turlogg",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Logger GPS, veinett og varsler mens sporing kjører. Del filen etter turen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    val shareIntent = TripLog.shareIntent(context)
                    if (shareIntent == null) {
                        Toast.makeText(context, "Ingen logg å dele ennå.", Toast.LENGTH_SHORT).show()
                    } else {
                        context.startActivity(shareIntent)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Del")
            }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Turlogg", TripLog.copyText()))
                    Toast.makeText(context, "Kopiert.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Kopier")
            }
            OutlinedButton(
                onClick = { TripLog.clear() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Tøm")
            }
        }
        Text(
            text = if (lines.isEmpty()) {
                "Ingen logglinjer ennå. Start sporing og kjør."
            } else {
                "${lines.size} siste linjer"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(
                items = lines,
                key = { index, line -> "$index:$line" },
            ) { _, line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}
