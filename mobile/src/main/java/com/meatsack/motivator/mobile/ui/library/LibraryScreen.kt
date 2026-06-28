package com.meatsack.motivator.mobile.ui.library

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meatsack.motivator.mobile.sync.PhoneSyncSender
import com.meatsack.motivator.mobile.sync.SyncResult
import com.meatsack.shared.model.Message
import kotlinx.coroutines.launch

private const val MAX_LEVEL = 4 // EscalationLevel: AGGRESSIVE(1)..EXISTENTIAL(4)

@Composable
fun LibraryScreen(viewModel: LibraryViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            VitalsHeader(roundCount = messages.size)

            SyncBar(
                onClick = {
                    scope.launch {
                        val msg = when (val result = PhoneSyncSender(context).syncMessagesToWatch()) {
                            is SyncResult.Success -> "Synced ${result.count} rounds to watch"
                            SyncResult.NoMessages -> "No rounds to sync"
                            is SyncResult.Failed ->
                                "Sync failed: ${result.error.message ?: "unknown error"}"
                        }
                        snackbarHostState.showSnackbar(msg)
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(messages) { message -> InsultPanel(message) }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The signature element: a live EKG pulse line. A bright ember segment traces the
 * waveform on a loop. Honours the system "remove animations" setting by drawing the
 * full line statically when animator duration scale is 0.
 */
@Composable
private fun VitalsHeader(roundCount: Int) {
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }

    // Only spin up the looping animation when motion is allowed. Under reduce-motion
    // the header draws statically, so we must not start a perpetual frame loop at all.
    // reduceMotion is remembered (stable for this composition), so the conditional
    // composable calls below are safe.
    val progress = if (reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "ekg")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ekg-trace",
        )
        animated
    }

    val ember = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            // A flat baseline punctuated by two QRS-style spikes.
            val pts = listOf(
                Offset(0f, mid),
                Offset(w * 0.18f, mid),
                Offset(w * 0.23f, mid),
                Offset(w * 0.27f, h * 0.18f),
                Offset(w * 0.30f, h * 0.86f),
                Offset(w * 0.34f, mid),
                Offset(w * 0.56f, mid),
                Offset(w * 0.61f, mid),
                Offset(w * 0.64f, h * 0.26f),
                Offset(w * 0.67f, h * 0.80f),
                Offset(w * 0.72f, mid),
                Offset(w, mid),
            )
            val path = Path().apply {
                moveTo(pts.first().x, pts.first().y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
            }

            drawPath(path, color = dim, style = Stroke(width = 1.5.dp.toPx()))

            if (reduceMotion) {
                drawPath(path, color = ember, style = Stroke(width = 2f.dp.toPx()))
            } else {
                val measure = PathMeasure()
                measure.setPath(path, forceClosed = false)
                val len = measure.length
                val head = progress * len
                val window = len * 0.20f
                val segment = Path()
                if (measure.getSegment(
                        startDistance = (head - window).coerceAtLeast(0f),
                        stopDistance = head,
                        destination = segment,
                        startWithMoveTo = true,
                    )
                ) {
                    drawPath(segment, color = ember, style = Stroke(width = 2.5f.dp.toPx()))
                }
            }
        }

        Text(
            text = "MEATSACK · VITALS",
            style = MaterialTheme.typography.labelMedium,
            color = ember,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 12.dp),
        )
        Text(
            text = "STATUS: $roundCount ROUNDS CHAMBERED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 10.dp),
        )
    }
}

@Composable
private fun SyncBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick, role = Role.Button, onClickLabel = "Sync to watch")
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "> SYNC TO WATCH",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "[ ▸ ]",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun InsultPanel(message: Message) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(11.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LVL ${message.level.value}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(10.dp))
                SeverityBar(level = message.level.value)
                Spacer(Modifier.size(12.dp))
                Text(
                    text = message.triggerType.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "▲${message.votesUp} ▼${message.votesDown}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun SeverityBar(level: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(MAX_LEVEL) { index ->
            Surface(
                color = if (index < level) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = RoundedCornerShape(1.dp),
                modifier = Modifier.size(width = 14.dp, height = 5.dp),
            ) {}
        }
    }
}
