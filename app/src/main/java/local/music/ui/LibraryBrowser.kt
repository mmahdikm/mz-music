package local.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.music.R
import local.music.library.Song
import local.music.playback.QueueUiState

@Composable
fun ColumnScope.LibraryBrowser(library: LibraryState, playback: PlaybackState, model: MusicViewModel,
    requestPermission: () -> Unit, openSettings: () -> Unit, onOpenPlayer: () -> Unit,
    onSound: () -> Unit, onQueue: () -> Unit) {
    val collection by model.collectionsState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Songs") }
    var filter by rememberSaveable { mutableStateOf("") }
    var create by rememberSaveable { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val selected = collection.playlists.firstOrNull { it.id == collection.selectedId }
    BackHandler(tab == "Playlists" && selected != null) { model.selectPlaylist(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_music), null, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
        Text("mz music", color = Mint, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 10.dp))
        TextButton(onClick = onQueue) { Text("Queue") }
        TextButton(onClick = onSound) { Text("Sound") }
    }
    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Your library", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (library.permissionGranted) IconButton(onClick = { model.refresh() }, enabled = !library.loading,
            modifier = Modifier.semantics { contentDescription = "Refresh music library" }) { Glyph(Symbol.Refresh) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Songs", "Favorites", "Playlists").forEach { name ->
            FilterChip(selected = tab == name, onClick = { tab = name; filter = "" }, label = { Text(name) })
        }
    }
    if (!playback.connected) ConnectionBanner(playback, model::connect)
    library.error?.let { MessageCard(it) }
    if (collection.error != null) TextButton(onClick = model::retryCollections) { Text("Retry saved collections") }
    when {
        !library.permissionGranted -> EmptyState("Bring your music along",
            "Allow access to audio files to see the music saved on your phone. Everything stays on your device.", Modifier.weight(1f)) {
            Button(onClick = requestPermission) { Text("Allow music access") }
            TextButton(onClick = openSettings) { Text("Open app settings") }
        }
        tab == "Playlists" -> {
            if (!collection.ready) {
                EmptyState("Loading your playlists…", "Your saved collections stay on this device.", Modifier.weight(1f)) {}
            } else if (selected == null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${collection.playlists.size} playlists", color = Muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = { create = true }, enabled = !collection.busy) { Text("New playlist") }
                }
                if (collection.playlists.isEmpty()) EmptyState("A mix of your own", "Create a playlist, then add songs from their menus.", Modifier.weight(1f)) {}
                else LazyColumn(Modifier.weight(1f)) {
                    items(collection.playlists, key = { it.id }) { playlist ->
                        ListItem(headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${playlist.count} songs") },
                            modifier = Modifier.clickable(enabled = !collection.busy) { model.selectPlaylist(playlist.id) },
                            colors = ListItemDefaults.colors(containerColor = Ink))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.selectPlaylist(null) }, enabled = !collection.busy,
                        modifier = Modifier.semantics { contentDescription = "Back to playlists" }) { Glyph(Symbol.Back) }
                    Column(Modifier.weight(1f)) {
                        Text(selected.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${selected.count} songs · Saved order", color = Muted, fontSize = 12.sp)
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !collection.busy,
                            modifier = Modifier.semantics { contentDescription = "Playlist options" }) { Text("⋮", fontSize = 26.sp) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename playlist") }, onClick = { menu = false; rename = true })
                            DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; delete = true })
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = playback.connected && !collection.busy && collection.selectedSongs.isNotEmpty(), onClick = {
                        model.playSong(collection.selectedSongs.first(), collection.selectedSongs); onOpenPlayer()
                    }) { Text("Play playlist") }
                    TextButton(onClick = { tab = "Songs" }) { Text("Add songs") }
                }
                if (collection.selectedSongs.isEmpty()) EmptyState("Ready for your favorites", "Find a song, open its menu and choose Add to playlist.", Modifier.weight(1f)) {}
                else LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(collection.selectedSongs, key = { _, s -> s.uri }) { index, song ->
                        SongLine(song, playback.track?.uri == song.uri, playback.connected && !collection.busy,
                            onPlay = { model.playSong(song, collection.selectedSongs); onOpenPlayer() }) {
                            SongMenu(song, model, playlistId = selected.id, index = index, count = collection.selectedSongs.size)
                        }
                    }
                }
            }
        }
        else -> {
            val keyboard = LocalSoftwareKeyboardController.current
            OutlinedTextField(value = if (tab == "Songs") library.query else filter,
                onValueChange = { if (tab == "Songs") model.search(it) else filter = it.take(120) },
                label = { Text(if (tab == "Songs") "Search songs, artists or albums" else "Search favorites") },
                singleLine = true, keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            val songs = if (tab == "Songs") library.songs else collection.favorites.filter {
                listOf(it.title, it.artist, it.album).any { text -> text.contains(filter.trim(), ignoreCase = true) }
            }
            if (library.loading && tab == "Songs") LinearProgressIndicator(Modifier.fillMaxWidth())
            if (songs.isEmpty()) EmptyState(
                if (library.loading && tab == "Songs") "Finding your music…" else if ((if (tab == "Songs") library.query else filter).isNotBlank()) "No matching songs"
                else if (tab == "Favorites") "Keep the ones you love" else "A little quiet here",
                if (tab == "Favorites") "Use a song’s menu to add it to Favorites."
                else if (library.query.isNotBlank()) "Try a different title, artist or album."
                else "Copy music into your phone’s Music folder, then refresh.", Modifier.weight(1f)) {}
            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(songs, key = { it.uri }) { song ->
                    SongLine(song, song.uri == playback.track?.uri, playback.connected,
                        onPlay = { model.playSong(song, songs); onOpenPlayer() }) { SongMenu(song, model) }
                }
                if (tab == "Songs" && library.hasMore) item {
                    if (library.songs.size < local.music.playback.MAX_QUEUE_TRACKS)
                        TextButton(onClick = model::loadMore, enabled = !library.loading) { Text("Load more songs") }
                    else Text("Showing 2,000 results. Refine your search to find other songs.", color = Muted, modifier = Modifier.padding(20.dp))
                }
            }
        }
    }
    if (playback.track != null) {
        playback.error?.let { MessageCard(it) }
        MiniPlayer(playback, onOpenPlayer, model::togglePlayback, model::next)
    }
    if (create) PlaylistNameDialog("New playlist", onDismiss = { create = false }) {
        model.createPlaylist(it); create = false
    }
    if (rename && selected != null) PlaylistNameDialog("Rename playlist", selected.name, { rename = false }) {
        model.renamePlaylist(selected.id, it); rename = false
    }
    if (delete && selected != null) AlertDialog(onDismissRequest = { delete = false },
        title = { Text("Delete playlist?") }, text = { Text("Delete “${selected.name}”? Your music files and current queue will stay.") },
        confirmButton = { TextButton(onClick = { model.deletePlaylist(selected.id); delete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
}

@Composable
fun SongLine(song: Song, selected: Boolean, enabled: Boolean, onPlay: () -> Unit, options: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) Panel else Ink).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(enabled = enabled, onClick = onPlay).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.artworkUri, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.title, color = if (selected) Mint else Cream, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(song.artist, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            }
        }
        options()
    }
}

@Composable
fun SongMenu(song: Song, model: MusicViewModel, playlistId: String? = null, index: Int = 0, count: Int = 0) {
    val collection by model.collectionsState.collectAsStateWithLifecycle()
    val playback by model.playbackState.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Options for ${song.title}" }) { Text("⋮", fontSize = 26.sp) }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("Play next") }, enabled = playback.connected, onClick = { expanded = false; model.enqueue(song, true) })
            DropdownMenuItem(text = { Text("Add to queue") }, enabled = playback.connected, onClick = { expanded = false; model.enqueue(song, false) })
            DropdownMenuItem(text = { Text(if (collection.favorites.any { it.uri == song.uri }) "Remove from favorites" else "Add to favorites") },
                enabled = collection.ready && !collection.busy, onClick = { expanded = false; model.toggleFavorite(song) })
            DropdownMenuItem(text = { Text("Add to playlist") }, enabled = collection.ready && !collection.busy, onClick = { expanded = false; picker = true })
            if (playlistId != null) {
                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0 && !collection.busy, onClick = { expanded = false; model.moveInPlaylist(playlistId, song, -1) })
                DropdownMenuItem(text = { Text("Move down") }, enabled = index < count - 1 && !collection.busy, onClick = { expanded = false; model.moveInPlaylist(playlistId, song, 1) })
                DropdownMenuItem(text = { Text("Remove from playlist") }, enabled = !collection.busy, onClick = { expanded = false; model.removeFromPlaylist(playlistId, song) })
            }
        }
    }
    if (picker) PlaylistPicker(song, model) { picker = false }
}

@Composable
fun PlaylistPicker(song: Song, model: MusicViewModel, onDismiss: () -> Unit) {
    val collection by model.collectionsState.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    if (create) PlaylistNameDialog("New playlist", onDismiss = { create = false }) { model.createPlaylist(it, listOf(song)); onDismiss() }
    else AlertDialog(onDismissRequest = onDismiss, title = { Text("Add to playlist") }, text = {
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            item { TextButton(onClick = { create = true }) { Text("New playlist") } }
            items(collection.playlists, key = { it.id }) { playlist ->
                TextButton(onClick = { model.addToPlaylist(playlist.id, song); onDismiss() }, enabled = !collection.busy) { Text(playlist.name) }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun PlaylistNameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Playlist name") }, singleLine = true,
            supportingText = { Text("${name.length}/60 · Music files stay on your device") })
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun ColumnScope.QueueScreen(queue: QueueUiState, playback: PlaybackState, model: MusicViewModel, onBack: () -> Unit) {
    var save by rememberSaveable { mutableStateOf(false) }
    val collection by model.collectionsState.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back from queue" }) { Glyph(Symbol.Back) }
        Text("Your queue", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = { save = true }, enabled = queue.songs.isNotEmpty() && !queue.loading && queue.error == null && collection.ready && !collection.busy) { Text("Save as playlist") }
    }
    PlaybackModes(playback, model)
    Text("${queue.songs.size} songs · " + if (playback.shuffle) "Saved order shown · Shuffle changes playback order" else "Plays in this order", color = Muted,
        fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    if (queue.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (queue.error != null) {
        MessageCard(queue.error)
        TextButton(onClick = model::refreshQueue) { Text("Refresh queue") }
    }
    if (queue.songs.isEmpty()) EmptyState("Your next listen starts here", "Choose a song or use Add to queue. Adding alone does not start playback.", Modifier.weight(1f)) {}
    else LazyColumn(Modifier.weight(1f)) {
        itemsIndexed(queue.songs, key = { index, _ -> index }) { index, song ->
            val enabled = playback.connected && !queue.loading && queue.error == null
            SongLine(song, index == playback.queueIndex, enabled, onPlay = { model.playQueue(index) }) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Queue options ${index + 1}" }) { Text("⋮", fontSize = 26.sp) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0, onClick = { menu = false; model.moveQueue(index, index - 1) })
                        DropdownMenuItem(text = { Text("Move down") }, enabled = index < queue.songs.lastIndex, onClick = { menu = false; model.moveQueue(index, index + 1) })
                        DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { menu = false; model.removeQueue(index) })
                    }
                }
            }
        }
    }
    if (playback.track != null) MiniPlayer(playback, onBack, model::togglePlayback, model::next)
    if (save) PlaylistNameDialog("Save queue as playlist", onDismiss = { save = false }) { model.createPlaylist(it, queue.songs); save = false }
}

@Composable
fun PlaybackModes(playback: PlaybackState, model: MusicViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        FilterChip(selected = playback.shuffle, enabled = playback.connected, onClick = model::toggleShuffle,
            label = { Text(if (playback.shuffle) "Shuffle on" else "Shuffle off") }, modifier = Modifier.semantics { contentDescription = "Toggle shuffle" })
        FilterChip(selected = playback.repeat != 0, enabled = playback.connected, onClick = model::cycleRepeat,
            label = { Text(when (playback.repeat) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> "Repeat off" }) },
            modifier = Modifier.semantics { contentDescription = "Change repeat mode" })
    }
}
