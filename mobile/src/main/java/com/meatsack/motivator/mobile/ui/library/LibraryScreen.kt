package com.meatsack.motivator.mobile.ui.library

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meatsack.motivator.mobile.sync.PhoneSyncSender
import com.meatsack.motivator.mobile.sync.SyncResult
import com.meatsack.motivator.mobile.ui.theme.LocalThemeChoice
import com.meatsack.motivator.mobile.ui.theme.MeatsackTheme
import com.meatsack.motivator.mobile.ui.theme.ThemeChoice
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.GenerationLimits.RETIRE_DOWNVOTES
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import kotlinx.coroutines.launch

private const val MAX_LEVEL = 4 // EscalationLevel: AGGRESSIVE(1)..EXISTENTIAL(4)

@Composable
fun LibraryScreen(viewModel: LibraryViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val counts by viewModel.counts.collectAsState()
    val shownCount = counts[filter] ?: 0
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val bubblegum = LocalThemeChoice.current == ThemeChoice.BUBBLEGUM

    LaunchedEffect(filter) { listState.scrollToItem(0) }

    val onSync: () -> Unit = {
        scope.launch {
            val msg = when (val result = PhoneSyncSender(context).syncMessagesToWatch()) {
                is SyncResult.Success -> "Synced ${result.count} rounds to watch"
                SyncResult.NoMessages -> "No rounds to sync"
                is SyncResult.Failed ->
                    "Sync failed: ${result.error.message ?: "unknown error"}"
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.autoSyncResults.collect { result ->
            if (result is SyncResult.Failed) {
                snackbarHostState.showSnackbar(
                    "Sync failed: ${result.error.message ?: "unknown error"}",
                )
            }
            viewModel.consumeAutoSyncResult()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (bubblegum) BubblegumHeader(shownCount) else VitalsHeader(shownCount)

            val barPadding = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
            if (bubblegum) {
                BubblegumSyncBar(onClick = onSync, modifier = barPadding)
            } else {
                SyncBar(onClick = onSync, modifier = barPadding)
            }

            LibraryFilterChips(
                selected = filter,
                counts = counts,
                bubblegum = bubblegum,
                onSelect = viewModel::setFilter,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (filter == LibraryFilter.RETIRED && shownCount > 0) {
                DeleteAllRetiredBar(
                    count = shownCount,
                    bubblegum = bubblegum,
                    onConfirmed = viewModel::deleteAllRetired,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
            }

            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emptyStateText(filter),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    // Stable identity across inserts and prunes; display order itself is pinned by FrozenOrder.
                    items(messages, key = { it.id }) { message ->
                        val actionsState = LibraryFilters.stateOf(message)
                        val actions = remember(message.id, actionsState) {
                            CardActions(
                                state = actionsState,
                                onVoteUp = { viewModel.voteUp(message.id) },
                                onVoteDown = { viewModel.voteDown(message.id) },
                                onArchive = { viewModel.archive(message.id) },
                                onUnarchive = { viewModel.unarchive(message.id) },
                                onDelete = { viewModel.delete(message.id) },
                            )
                        }
                        if (bubblegum) BubblegumPanel(message, actions) else InsultPanel(message, actions)
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Prose shown in place of the list when the selected chip has no rows. */
private fun emptyStateText(filter: LibraryFilter): String = when (filter) {
    LibraryFilter.ACTIVE -> "No active insults. Restore some from Archived or press Generate in Settings."
    LibraryFilter.ARCHIVED -> "Nothing archived yet. Tap Archive on a card to keep it out of rotation."
    LibraryFilter.RETIRED -> "No retired insults. $RETIRE_DOWNVOTES downvotes retire one."
}

/** Everything a card can do; which controls render depends on [state] (spec §Filter chips). */
@Immutable
internal data class CardActions(
    val state: LibraryFilter,
    val onVoteUp: () -> Unit,
    val onVoteDown: () -> Unit,
    val onArchive: () -> Unit,
    val onUnarchive: () -> Unit,
    val onDelete: () -> Unit,
)

/** A tappable text action (Archive / Restore / Delete) with a 48 dp touch target. */
@Composable
private fun SecondaryAction(text: String, onClickLabel: String, color: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clickable(onClick = onClick, role = Role.Button, onClickLabel = onClickLabel)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 4.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/**
 * Per-state action row rendered on its own line beneath a card's meta row:
 *  ACTIVE   → votes + Archive
 *  RETIRED  → Archive + Delete (votes shown read-only: the third 👎 already retired it)
 *  ARCHIVED → Restore (votes shown read-only)
 */
@Composable
private fun CardActionRow(
    message: Message,
    actions: CardActions,
    upGlyph: String,
    downGlyph: String,
    voteColor: Color,
    archiveLabel: String,
    restoreLabel: String,
    deleteLabel: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        VoteControls(
            upGlyph = upGlyph,
            downGlyph = downGlyph,
            votesUp = message.votesUp,
            votesDown = message.votesDown,
            color = voteColor,
            enabled = actions.state == LibraryFilter.ACTIVE,
            onVoteUp = actions.onVoteUp,
            onVoteDown = actions.onVoteDown,
        )
        Spacer(Modifier.width(4.dp))
        when (actions.state) {
            LibraryFilter.ACTIVE ->
                SecondaryAction(archiveLabel, "Archive", MaterialTheme.colorScheme.onSurfaceVariant, actions.onArchive)
            LibraryFilter.RETIRED -> {
                SecondaryAction(archiveLabel, "Archive", MaterialTheme.colorScheme.onSurfaceVariant, actions.onArchive)
                Spacer(Modifier.width(12.dp))
                SecondaryAction(deleteLabel, "Delete", MaterialTheme.colorScheme.error, actions.onDelete)
            }
            LibraryFilter.ARCHIVED ->
                SecondaryAction(restoreLabel, "Unarchive", MaterialTheme.colorScheme.primary, actions.onUnarchive)
        }
    }
}

/**
 * Two vote controls, tappable only when `enabled` (Active cards); archived and retired cards
 * show the counts read-only. Glyph strings are theme-supplied (▲/▼ for Vitals,
 * 💕/💔 for Bubblegum) and don't reach TalkBack. `contentDescription` reports the
 * live count ("Upvotes: N" / "Downvotes: N"); the fixed `onClickLabel` ("Vote up" /
 * "Vote down") supplies the action hint. Both are theme-independent.
 */
@Composable
private fun VoteControls(
    upGlyph: String,
    downGlyph: String,
    votesUp: Int,
    votesDown: Int,
    color: Color,
    enabled: Boolean,
    onVoteUp: () -> Unit,
    onVoteDown: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        VoteCell("$upGlyph $votesUp", "Upvotes: $votesUp", "Vote up", color, enabled, onVoteUp)
        VoteCell("$downGlyph $votesDown", "Downvotes: $votesDown", "Vote down", color, enabled, onVoteDown)
    }
}

@Composable
private fun VoteCell(
    text: String,
    description: String,
    clickLabel: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val base = Modifier.semantics { contentDescription = description }
    val modifier = if (enabled) {
        base.clickable(onClick = onClick, role = Role.Button, onClickLabel = clickLabel)
    } else {
        base
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

// ============================ Vitals theme ============================

/**
 * The Vitals signature: a live EKG pulse line. A bright ember segment traces the
 * waveform on a loop. Honours the system "remove animations" setting by drawing the
 * full line statically when animator duration scale is 0.
 */
@Composable
private fun VitalsHeader(roundCount: Int) {
    val reduceMotion = rememberReduceMotion()

    // Only spin up the looping animation when motion is allowed. Under reduce-motion
    // the header draws statically, so we must not start a perpetual frame loop at all.
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
private fun InsultPanel(message: Message, actions: CardActions) {
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
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardActionRow(
                    message = message,
                    actions = actions,
                    upGlyph = "▲",
                    downGlyph = "▼",
                    voteColor = MaterialTheme.colorScheme.secondary,
                    archiveLabel = "[ ARCHIVE ]",
                    restoreLabel = "[ RESTORE ]",
                    deleteLabel = "[ DELETE ]",
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

// ============================ Bubblegum theme ============================

/**
 * The Bubblegum signature: a lipstick kiss mark that gently pulses, over the app name
 * in script. Same reduce-motion accommodation as the Vitals EKG — no pulse when the
 * system disables animations.
 */
@Composable
private fun BubblegumHeader(roundCount: Int) {
    val reduceMotion = rememberReduceMotion()
    val scale = if (reduceMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "kiss")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "kiss-pulse",
        )
        animated
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFFFD1E8), MaterialTheme.colorScheme.background),
                ),
            )
            .padding(top = 16.dp, bottom = 16.dp),
    ) {
        Text(
            text = "💋",
            fontSize = MaterialTheme.typography.headlineMedium.fontSize * 2f,
            modifier = Modifier.scale(scale),
        )
        Text(
            text = "Meatsack Motivator",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "$roundCount sweet nothings loaded 💕",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BubblegumSyncBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(percent = 50),
            )
            .clickable(onClick = onClick, role = Role.Button, onClickLabel = "Sync to watch")
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Text(
            text = "Sync to Watch 💋",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun BubblegumPanel(message: Message, actions: CardActions) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeartSeverity(level = message.level.value)
                Spacer(Modifier.size(10.dp))
                TriggerChip(message.triggerType.name)
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardActionRow(
                    message = message,
                    actions = actions,
                    upGlyph = "💕",
                    downGlyph = "💔",
                    voteColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    archiveLabel = "Keep 🗂",
                    restoreLabel = "Restore 💫",
                    deleteLabel = "Bye 🗑",
                )
            }
        }
    }
}

@Composable
private fun HeartSeverity(level: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(MAX_LEVEL) { index ->
            Text(
                text = if (index < level) "♥" else "♡",
                style = MaterialTheme.typography.labelMedium,
                color = if (index < level) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    // outline is too pale on white to read; muted keeps empty hearts visible.
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun TriggerChip(triggerName: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(
            text = triggerName.replace('_', ' ').lowercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

// ============================ shared ============================

/** True when the system "remove animations" setting is on (animator scale == 0). */
@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

// ============================ previews ============================

private const val PREVIEW_TEXT =
    "You soft-bellied comfort addict. Ninety minutes. Your muscles scream; your excuses drown them out."

private fun previewMessage(votesUp: Int, votesDown: Int, isActive: Boolean = true) = Message(
    text = PREVIEW_TEXT,
    level = EscalationLevel.SAVAGE,
    triggerType = TriggerType.INACTIVITY,
    tone = MessageTone.FULL_SEND,
    source = MessageSource.PRE_WRITTEN,
    votesUp = votesUp,
    votesDown = votesDown,
    isActive = isActive,
)

private fun previewActions(state: LibraryFilter) = CardActions(
    state = state,
    onVoteUp = {},
    onVoteDown = {},
    onArchive = {},
    onUnarchive = {},
    onDelete = {},
)

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PreviewVitalsActive() {
    MeatsackTheme(choice = ThemeChoice.VITALS) {
        InsultPanel(previewMessage(votesUp = 3, votesDown = 1), previewActions(LibraryFilter.ACTIVE))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PreviewVitalsArchived() {
    MeatsackTheme(choice = ThemeChoice.VITALS) {
        InsultPanel(
            previewMessage(votesUp = 8, votesDown = 2, isActive = false),
            previewActions(LibraryFilter.ARCHIVED),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PreviewVitalsRetired() {
    MeatsackTheme(choice = ThemeChoice.VITALS) {
        InsultPanel(previewMessage(votesUp = 0, votesDown = 3), previewActions(LibraryFilter.RETIRED))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PreviewBubblegumActive() {
    MeatsackTheme(choice = ThemeChoice.BUBBLEGUM) {
        BubblegumPanel(previewMessage(votesUp = 3, votesDown = 1), previewActions(LibraryFilter.ACTIVE))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PreviewBubblegumArchived() {
    MeatsackTheme(choice = ThemeChoice.BUBBLEGUM) {
        BubblegumPanel(
            previewMessage(votesUp = 8, votesDown = 2, isActive = false),
            previewActions(LibraryFilter.ARCHIVED),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PreviewBubblegumRetired() {
    MeatsackTheme(choice = ThemeChoice.BUBBLEGUM) {
        BubblegumPanel(previewMessage(votesUp = 0, votesDown = 3), previewActions(LibraryFilter.RETIRED))
    }
}
