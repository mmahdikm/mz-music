package local.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import local.music.library.Song
import local.music.R
import java.util.Locale

val Ink = Color(0xFF101512)
val Panel = Color(0xFF1B241F)
val PanelHigh = Color(0xFF28372E)
val Mint = Color(0xFFB9E6C8)
val Cream = Color(0xFFF2F2E9)
val Muted = Color(0xFFADB9AE)

@Composable
fun LocalMusicApp(model: MusicViewModel, requestPermission: () -> Unit, openSettings: () -> Unit) {
    val library by model.libraryState.collectAsStateWithLifecycle()
    val playback by model.playbackState.collectAsStateWithLifecycle()
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    var soundOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    val queue by model.queueState.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { model.updatePlayback(); delay(500) }
        }
    }
    BackHandler(nowPlaying || soundOpen || queueOpen) { if (soundOpen) soundOpen = false else if (queueOpen) queueOpen = false else nowPlaying = false }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Mint, onPrimary = Ink, background = Ink, onBackground = Cream,
        secondary = Color(0xFFF3A9B8), secondaryContainer = Color(0xFF452D37), onSecondaryContainer = Cream,
        surface = Panel, onSurface = Cream, surfaceVariant = PanelHigh, onSurfaceVariant = Muted,
        error = Color(0xFFFFB4AB), onErrorContainer = Color(0xFFFFDAD5),
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.safeDrawingPadding().fillMaxSize().widthIn(max = 720.dp)) {
                message?.let { text ->
                    Row(Modifier.fillMaxWidth().background(PanelHigh).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = model::dismissMessage) { Text("Dismiss") }
                    }
                }
                if (soundOpen) {
                    SoundScreen(model, onBack = { soundOpen = false })
                } else if (queueOpen) {
                    QueueScreen(queue, playback, model, onBack = { queueOpen = false })
                } else if (nowPlaying && playback.track != null) {
                    NowPlaying(playback, model, onBack = { nowPlaying = false }, onSound = { soundOpen = true }, onQueue = { queueOpen = true; model.refreshQueue() })
                } else {
                    LibraryBrowser(library, playback, model, requestPermission, openSettings,
                        onOpenPlayer = { nowPlaying = true }, onSound = { soundOpen = true }, onQueue = { queueOpen = true; model.refreshQueue() })
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(state: PlaybackState, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    Surface(color = PanelHigh, shape = RoundedCornerShape(18.dp), modifier = Modifier.padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen)
                .padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(state.track?.artworkUri, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(state.track?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.track?.artist.orEmpty(), color = Muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TransportButton(if (state.playWhenReady) Symbol.Pause else Symbol.Play,
                if (state.playWhenReady) "Pause" else "Play", state.connected, onToggle)
            TransportButton(Symbol.Next, "Next track", state.connected && state.canNext, onNext)
        }
    }
}

@Composable
private fun NowPlaying(state: PlaybackState, model: MusicViewModel, onBack: () -> Unit, onSound: () -> Unit, onQueue: () -> Unit) {
    val sound by model.soundState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TransportButton(Symbol.Back, "Back to library", true, onBack)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NOW PLAYING", fontSize = 11.sp, letterSpacing = 2.sp, color = Mint)
                Text("Track ${state.queueIndex + 1} of ${state.queueSize}", color = Muted,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = onQueue) { Text("Queue") }
            TextButton(onClick = onSound) { Text("Sound") }
        }
        Spacer(Modifier.height(24.dp))
        Artwork(state.track?.artworkUri, Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp)))
        Spacer(Modifier.height(16.dp))
        Text(state.track?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
        Text(state.track?.artist.orEmpty(), color = Muted, fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(state.track?.album.orEmpty(), color = Muted, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        state.track?.let {
            val queue by model.queueState.collectAsStateWithLifecycle()
            val real = queue.songs.getOrNull(state.queueIndex)
            if (real != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SongMenu(real, model) }
        }
        Spacer(Modifier.height(8.dp))
        if (!state.connected) ConnectionBanner(state, model::connect)
        if (state.error != null) MessageCard(state.error)
        SeekBar(state, model::seek)
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            TransportButton(Symbol.Previous, "Previous track", state.connected && state.canPrevious, model::previous)
            FilledIconButton(onClick = model::togglePlayback, enabled = state.connected,
                modifier = Modifier.size(76.dp).semantics { contentDescription = if (state.playWhenReady) "Pause" else "Play" },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Mint, contentColor = Ink)) {
                if (state.buffering) CircularProgressIndicator(Modifier.size(30.dp), color = Ink, strokeWidth = 2.dp)
                else Glyph(if (state.playWhenReady) Symbol.Pause else Symbol.Play, Modifier.size(32.dp))
            }
            TransportButton(Symbol.Next, "Next track", state.connected && state.canNext, model::next)
        }
        PlaybackModes(state, model)
        Text(if (state.buffering) "Loading audio…" else if (state.isPlaying) "Playing from your device" else "Ready when you are",
            color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = PanelHigh)
        Text(sound.summary, color = Muted, fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 20.dp))
    }
}

@Composable
private fun SeekBar(state: PlaybackState, onSeek: (Long) -> Unit) {
    var dragging by remember(state.track?.uri) { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1).toFloat()
    val position = dragging ?: state.positionMs.toFloat().coerceIn(0f, duration)
    Slider(value = position, onValueChange = { dragging = it },
        onValueChangeFinished = { dragging?.let { onSeek(it.toLong()) }; dragging = null },
        valueRange = 0f..duration, enabled = state.connected && state.durationMs > 0,
        colors = SliderDefaults.colors(activeTrackColor = Mint, inactiveTrackColor = PanelHigh, thumbColor = Mint),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(dragging?.toLong() ?: state.positionMs), fontSize = 12.sp, color = Muted)
        Text(if (state.durationMs > 0) formatTime(state.durationMs) else "--:--", fontSize = 12.sp, color = Muted)
    }
}

@Composable
fun ConnectionBanner(state: PlaybackState, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (state.connecting) "Connecting to your player…" else "Playback is disconnected.",
            color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (!state.connecting) TextButton(onClick = onRetry) { Text("Reconnect") }
    }
}

@Composable
fun MessageCard(message: String) {
    Surface(color = Color(0xFF3D2926), shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(message, color = Color(0xFFFFDAD5), fontSize = 13.sp, modifier = Modifier.padding(14.dp))
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, actions: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(Panel), contentAlignment = Alignment.Center) {
            Glyph(Symbol.Music, Modifier.size(36.dp), Mint)
        }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp))
        Text(message, color = Muted, fontSize = 15.sp, lineHeight = 23.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        actions()
    }
}

@Composable
private fun TransportButton(symbol: Symbol, label: String, enabled: Boolean, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Glyph(symbol)
    }
}

enum class Symbol { Play, Pause, Previous, Next, Back, Music, Refresh }

@Composable
fun Glyph(symbol: Symbol, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, point(x1, y1), point(x2, y2), 2f * unit, StrokeCap.Round)
        fun triangle(a: Float, b: Float, c: Float) {
            drawPath(Path().apply { moveTo(a * unit, 5f * unit); lineTo(b * unit, 12f * unit)
                lineTo(c * unit, 19f * unit); close() }, color)
        }
        when (symbol) {
            Symbol.Play -> triangle(8f, 19f, 8f)
            Symbol.Pause -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
            Symbol.Next -> { triangle(5f, 16f, 5f); line(19f, 5f, 19f, 19f) }
            Symbol.Previous -> { triangle(19f, 8f, 19f); line(5f, 5f, 5f, 19f) }
            Symbol.Back -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
            Symbol.Music -> {
                line(15f, 4f, 15f, 17f); line(15f, 4f, 20f, 3f)
                drawCircle(color, 4f * unit, point(11f, 18f))
                line(4f, 7f, 4f, 12f); line(8f, 5f, 8f, 11f)
            }
            Symbol.Refresh -> {
                drawArc(color, 35f, 290f, false, topLeft = point(4f, 4f),
                    size = androidx.compose.ui.geometry.Size(16f * unit, 16f * unit), style = Stroke(2f * unit))
                line(20f, 4f, 20f, 10f); line(14f, 10f, 20f, 10f)
            }
        }
    }
}

fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
