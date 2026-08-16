package com.manus.spatialsafety.ar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.manus.spatialsafety.ar.safety.FusedObstacle
import com.manus.spatialsafety.ar.safety.ThreatZone
import com.manus.spatialsafety.ar.util.PerformanceStats
import kotlin.math.roundToInt

@Immutable
data class SafetyUiState(
    val tracking: Boolean = false,
    val paused: Boolean = false,
    val statusText: String = "Preparing AR session",
    val highestZone: ThreatZone = ThreatZone.UNKNOWN,
    val obstacles: List<FusedObstacle> = emptyList(),
    val performance: PerformanceStats = PerformanceStats(),
    val errorMessage: String? = null,
) {
    companion object {
        fun paused() = SafetyUiState(paused = true, statusText = "Scanning paused")
        fun error(message: String) = SafetyUiState(statusText = "Needs attention", errorMessage = message)
    }
}

@Composable
fun UIOverlayScreen(
    state: SafetyUiState,
    voiceEnabled: Boolean,
    onToggleScanning: () -> Unit,
    onToggleVoice: () -> Unit,
) {
    val zoneColor = Color(state.highestZone.color)
    Box(modifier = Modifier.fillMaxSize()) {
        DetectionBoxes(state.obstacles)

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xD9071019)),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(zoneColor))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = state.highestZone.hindiLabel,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(state.statusText, color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(
                        text = if (state.tracking) "TRACKING" else "WAIT",
                        color = zoneColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            PerformanceStrip(state.performance)
        }

        state.errorMessage?.let { message ->
            Card(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xEE071019)),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Safety view unavailable", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = Color(0xFFCBD5E1))
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f).height(52.dp),
                onClick = onToggleScanning,
                colors = ButtonDefaults.buttonColors(containerColor = if (state.paused) Color(0xFF36D399) else Color(0xCC071019)),
            ) {
                Icon(if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.paused) "Resume" else "Pause")
            }
            OutlinedButton(
                modifier = Modifier.height(52.dp),
                onClick = onToggleVoice,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF94A3B8)),
            ) {
                Icon(Icons.Default.RecordVoiceOver, null)
                Spacer(Modifier.width(8.dp))
                Text(if (voiceEnabled) "Voice on" else "Voice off")
            }
        }
    }
}

@Composable
private fun DetectionBoxes(obstacles: List<FusedObstacle>) {
    val density = LocalDensity.current
    obstacles.forEach { obstacle ->
        val box = obstacle.detection.box
        val color = Color(obstacle.zone.color)
        val width = with(density) { box.width().coerceAtLeast(2f).toDp() }
        val height = with(density) { box.height().coerceAtLeast(2f).toDp() }
        Box(
            modifier = Modifier
                .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                .width(width)
                .height(height)
                .border(3.dp, color, RoundedCornerShape(5.dp)),
        ) {
            val distanceText = obstacle.distanceMeters?.let { String.format("%.1f m", it) } ?: "estimating"
            Text(
                text = "${obstacle.detection.label.replaceFirstChar(Char::uppercase)} · $distanceText",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(color, RoundedCornerShape(bottomEnd = 6.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                color = Color(0xFF071019),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun PerformanceStrip(stats: PerformanceStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xB3071019))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Metric("FPS", stats.fps.toString())
        Metric("Battery", stats.batteryPercent?.let { "$it%" } ?: "—")
        Metric("CPU", stats.cpuPercent?.let { "$it%" } ?: "—")
        Metric("GPU", stats.gpuText)
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        Text(label, color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
    }
}
