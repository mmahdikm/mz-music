package local.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.music.sound.*
import kotlin.math.round

@Composable
fun SoundScreen(model: MusicViewModel, onBack: () -> Unit) {
    val state by model.soundState.collectAsState()
    val document = state.document
    val settings = document.settings
    var presetMenu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = document.presets.find { it.id == settings.presetId }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back from Sound" }) { Glyph(Symbol.Back) }
            Column(Modifier.padding(start = 8.dp)) {
                Text("Sound", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text("Make it yours", color = Muted, fontSize = 13.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = !settings.tuned, enabled = state.ready,
                onClick = { if (settings.tuned) confirmation = "original" },
                label = { Text("Original") }, modifier = Modifier.weight(1f))
            FilterChip(selected = settings.tuned, enabled = state.ready,
                onClick = { model.soundCommand("tuned") { putBoolean("value", true) } },
                label = { Text("Tuned") }, modifier = Modifier.weight(1f))
        }
        Text(if (!state.ready) "Connecting to sound settings…" else if (settings.tuned)
            "EQ + leveling settings active · Limiter on" else "All app effects bypassed · Your settings are kept",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (state.error != null) Text(state.error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your preset", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Box {
                            OutlinedButton(onClick = { presetMenu = true }, enabled = state.ready) { Text(document.presetName) }
                            DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                                document.presets.forEach { preset ->
                                    DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                                        presetMenu = false; model.soundCommand("select") { putString("id", preset.id) }
                                    })
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = state.ready && selected != null && selected.id != "flat",
                                onClick = { model.soundCommand("save") }) { Text("Save changes") }
                            OutlinedButton(enabled = state.ready && document.presets.size < 50,
                                onClick = { dialog = "saveAs"; presetName = "" }) { Text("Save as…") }
                        }
                        if (selected != null && !selected.builtIn) Row {
                            TextButton(onClick = { dialog = "rename"; presetName = selected.name }, enabled = state.ready) { Text("Rename") }
                            TextButton(onClick = { dialog = "delete" }, enabled = state.ready) { Text("Delete") }
                        }
                        Text("Choose profiles manually. Headphones and Car start flat so you can tune them for your own setup.",
                            color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("10-band equaliser", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(enabled = state.ready, onClick = { model.soundCommand("reset") }) { Text("Reset to Flat") }
                }
                Text(if (state.sampleRate > 0) "${state.sampleRate / 1000.0} kHz source · Unavailable bands are disabled"
                    else "Band availability updates when a track plays", color = Muted, fontSize = 12.sp)
            }
            itemsIndexed(EQ_FREQUENCIES) { index, frequency ->
                val label = if (frequency >= 1000) "${frequency / 1000} kHz" else "$frequency Hz"
                val available = state.sampleRate == 0 || frequency < state.sampleRate / 2.0
                SoundSlider(label, settings.gainsDb[index], -12f..12f, 47, state.ready && available) { value ->
                    model.soundCommand("band") { putInt("index", index); putDouble("value", value) }
                }
            }
            item {
                HorizontalDivider(color = PanelHigh)
                Spacer(Modifier.height(16.dp))
                Text("Headroom & protection", fontWeight = FontWeight.SemiBold)
                Text("${db(state.headroomDb)} dB automatic EQ headroom${if (!settings.tuned) " when Tuned" else ""}",
                    color = Mint, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                SoundSlider("Preamp", settings.preampDb, -12f..0f, 23, state.ready) { value ->
                    model.soundCommand("preamp") { putDouble("value", value) }
                }
                Text("The limiter is always on in Tuned mode. It limits sample peaks to −1 dBFS; it is not true-peak protection.",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            item {
                HorizontalDivider(color = PanelHigh)
                Spacer(Modifier.height(16.dp))
                Text("Volume leveling", fontWeight = FontWeight.SemiBold)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Leveling.entries.forEach { mode ->
                        val name = mode.name.lowercase().replaceFirstChar { it.uppercase() }
                        FilterChip(selected = settings.leveling == mode, enabled = state.ready,
                            onClick = { model.soundCommand("leveling") { putString("value", mode.name) } },
                            label = { Text(name) }, modifier = Modifier.semantics { contentDescription = "$name leveling" })
                    }
                }
                Text(state.levelingStatus + if (!settings.tuned) " · Bypassed in Original" else "", color = Mint, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Limit ReplayGain boost to +6 dB", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = settings.limitLevelingBoost, enabled = state.ready,
                        onCheckedChange = { enabled ->
                            if (!enabled) confirmation = "boost"
                            else model.soundCommand("limitBoost") { putBoolean("value", true) }
                        }, modifier = Modifier.semantics { contentDescription = "Limit ReplayGain boost" })
                }
                Text("Uses existing ReplayGain tags only. Album mode preserves relative track levels. Missing tags leave the level unchanged; your files are never scanned for loudness or edited.",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Text("The limiter is not hearing protection. Original/Tuned is not loudness-matched and switching to Original can be much louder. During the short crossfade, Original peaks can exceed the Tuned ceiling. Lower your device volume before comparing. Tune car settings while parked.",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            item {
                HorizontalDivider(color = PanelHigh)
                Spacer(Modifier.height(16.dp))
                Text("Privacy & saved data", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Private listening", modifier = Modifier.weight(1f))
                    Switch(checked = settings.privateListening, enabled = state.ready,
                        onCheckedChange = { value -> model.soundCommand("private") { putBoolean("value", value) } },
                        modifier = Modifier.semantics { contentDescription = "Private listening" })
                }
                Text("Hide player titles and artwork on system controls and car displays, and block app screenshots/recents previews. Your library remains visible inside the app. Previously seen metadata cannot be recalled from other devices.",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                OutlinedButton(enabled = state.ready, onClick = { confirmation = "clear" }) { Text("Clear listening data") }
                Text("Stops playback and erases the saved queue and position. Music files, playlists, favorites and sound settings stay on your device.",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                state.storageMessage?.let { Text(it, color = Mint, fontSize = 13.sp) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        }
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(when (action) { "original" -> "Switch to Original?"; "boost" -> "Allow larger tag boosts?"; else -> "Clear listening data?" }) },
            text = { Text(when (action) {
                "original" -> "Original removes all app attenuation and limiting and may be much louder. Lower your device volume first."
                "boost" -> "Tags may request up to +30 dB. Incorrect tags can make quiet files much louder. The limiter does not guarantee safe listening volume."
                else -> "Playback will stop and the saved queue and position will be erased. Your music files, playlists, favorites and sound settings will stay."
            }) },
            confirmButton = { TextButton(onClick = {
                model.soundCommand(when (action) { "original" -> "tuned"; "boost" -> "limitBoost"; else -> "clearQueue" }) {
                    putBoolean("value", false); putBoolean("confirmed", true)
                }
                confirmation = null
            }) { Text(when (action) { "original" -> "Switch to Original"; "boost" -> "Allow larger boosts"; else -> "Clear queue" }) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
    if (dialog != null) {
        val deleting = dialog == "delete"
        val validName = presetName.trim().isNotEmpty() && presetName.trim().length <= 40 &&
            document.presets.none { it.name.equals(presetName.trim(), true) && !(dialog == "rename" && it.id == selected?.id) }
        AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(when (dialog) { "delete" -> "Delete preset?"; "rename" -> "Rename preset"; else -> "Save a new preset" }) },
            text = {
                if (deleting) Text("Delete ${selected?.name}? Your current sound settings will be kept as an unsaved preset.")
                else OutlinedTextField(value = presetName, onValueChange = { presetName = it.take(40) },
                    label = { Text("Preset name") }, singleLine = true,
                    supportingText = { Text("Use a unique name, up to 40 characters") })
            },
            confirmButton = {
                TextButton(enabled = state.ready && (deleting || validName), onClick = {
                    val operation = dialog!!
                    model.soundCommand(operation) { putString("name", presetName.trim()); putString("id", selected?.id) }
                    dialog = null
                }) { Text(if (deleting) "Delete" else "Save") }
            }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SoundSlider(label: String, value: Double, range: ClosedFloatingPointRange<Float>, steps: Int,
    enabled: Boolean, onChange: (Double) -> Unit) {
    var dragging by remember(label) { mutableStateOf<Float?>(null) }
    val shown = dragging?.toDouble() ?: value
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (enabled) Cream else Muted, fontSize = 14.sp)
            Text(db(shown) + " dB", color = if (enabled) Mint else Muted, fontSize = 13.sp)
        }
        Slider(value = shown.toFloat(), onValueChange = {
            val snapped = round(it * 2) / 2
            if (dragging != snapped) { dragging = snapped; onChange(snapped.toDouble()) }
        }, onValueChangeFinished = { dragging = null }, steps = steps, valueRange = range,
            enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label gain" },
            colors = SliderDefaults.colors(activeTrackColor = Mint, inactiveTrackColor = PanelHigh, thumbColor = Mint,
                activeTickColor = Mint, inactiveTickColor = PanelHigh))
    }
}
