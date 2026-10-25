package com.ideawood.artists.ui

import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ideawood.artists.core.*
import com.ideawood.artists.engine.JobState
import java.io.File
import java.util.Locale

fun fmtTime(sec: Double): String {
    val s = maxOf(0.0, sec)
    val m = (s / 60).toInt()
    return String.format(Locale.ROOT, "%d:%05.2f", m, s - m * 60)
}

// ------------------------------------------------------------------------------------------ small building blocks
@Composable
fun Dropdown(label: String, options: List<String>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(selected, maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(o) }) }
            }
        }
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${String.format(Locale.ROOT, "%.2f", value)}", style = MaterialTheme.typography.labelMedium)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun HexField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val valid = Regex("^#[0-9A-Fa-f]{6}$").matches(text)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { v -> text = v; if (Regex("^#[0-9A-Fa-f]{6}$").matches(v)) onChange(v.uppercase()) },
            label = { Text(label) }, singleLine = true, isError = !valid, modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(36.dp).background(if (valid) Color(AColor.parseColor(text)) else Color.Gray))
    }
}

@Composable
fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

// ------------------------------------------------------------------------------------------ job progress card
@Composable
fun JobCard(job: JobState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    if (!job.running && job.error == null) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (job.running) {
                Text(job.title, style = MaterialTheme.typography.titleSmall)
                Text(job.status, style = MaterialTheme.typography.bodySmall)
                if (job.progress >= 0) LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Keep going in the background - you can switch apps.", style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            } else {
                Text(job.error ?: "", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ 1. Song
@Composable
fun SongScreen(vm: AppViewModel, onTapSync: () -> Unit) {
    vm.rev
    val p = vm.project
    val running = vm.job.collectAsState().value.running
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pickAudio(uri) }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importModel(vm.modelKey, uri) }
    val pickLrc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importLrc(uri) }
    val modelInstalled = vm.models.find(vm.modelKey) != null
    vm.speechReport?.let { rep ->
        AlertDialog(
            onDismissRequest = { vm.speechReport = null },
            confirmButton = { TextButton(onClick = { vm.speechReport = null }) { Text("Close") } },
            title = { Text("Speech engine test") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { androidx.compose.foundation.text.selection.SelectionContainer { Text(rep, style = MaterialTheme.typography.bodySmall) } } },
        )
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("1. Your song")
        Button(onClick = { pickAudio.launch(arrayOf("audio/*", "video/*")) }) { Text(if (p.audio.isBlank()) "Choose the song" else "Change song") }
        if (p.audio.isNotBlank()) Text("${File(p.audio).name.substringAfter('_').substringAfter('_')} - ${fmtTime(p.duration)}")
        OutlinedTextField(p.title, { v -> vm.edit { it.title = v } }, label = { Text("Song title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(p.artist, { v -> vm.edit { it.artist = v } }, label = { Text("Artist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            p.lyricsText, { v -> vm.edit { it.lyricsText = v } }, minLines = 8, modifier = Modifier.fillMaxWidth(),
            label = { Text("Lyrics (blank line between stanzas; [Chorus] headers are fine)") },
        )

        SectionTitle("2. Give the lyrics their timing")
        Text("Speech model (downloaded once, then everything works offline):", style = MaterialTheme.typography.bodySmall)
        Dropdown("Model", Asr.MODELS.keys.toList().map { Asr.MODELS.getValue(it).label }, Asr.MODELS.getValue(vm.modelKey).label) { label ->
            vm.modelKey = Asr.MODELS.entries.first { it.value.label == label }.key
        }
        val prog = vm.modelProgress
        if (prog != null) {
            LinearProgressIndicator(progress = { prog / 100f }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { vm.cancelDownload() }) { Text("Pause download") }
        } else if (!modelInstalled) {
            Text("Not on this phone yet (${Asr.MODELS.getValue(vm.modelKey).approxMb} MB, Wi-Fi recommended).", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.downloadModel(vm.modelKey) }) { Text("Download") }
                OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) { Text("I have the file") }
            }
        } else Text("Model ready.", color = MaterialTheme.colorScheme.primary)
        Dropdown("Language of the song", Asr.LANGUAGES.keys.toList(),
            Asr.LANGUAGES.entries.firstOrNull { it.value == p.language }?.key ?: "Auto-detect") { k ->
            vm.edit { it.language = Asr.LANGUAGES.getValue(k) }
        }
        ToggleRow("Fast mode (recommended on phones)", vm.speechFast) { v -> vm.setSpeechFast(v) }
        Button(onClick = { vm.autoSync() }, enabled = !running && p.audio.isNotBlank() && modelInstalled, modifier = Modifier.fillMaxWidth()) {
            Text("Auto-sync with the song")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTapSync, enabled = !running && p.audio.isNotBlank() && p.lyricsText.isNotBlank()) { Text("Tap to sync") }
            OutlinedButton(onClick = { vm.spreadEvenly() }, enabled = p.audio.isNotBlank() && p.lyricsText.isNotBlank()) { Text("Spread evenly") }
            OutlinedButton(onClick = { pickLrc.launch(arrayOf("*/*")) }) { Text("Import LRC") }
        }
        OutlinedButton(onClick = { vm.testSpeech() }, enabled = !vm.speechTesting && !running) {
            Text(if (vm.speechTesting) "Testing..." else "Test speech engine")
        }
        if (vm.syncReport.isNotEmpty()) Text(vm.syncReport, style = MaterialTheme.typography.bodyMedium)
        Text("Tip: for songs Whisper cannot recognise (Luganda, Runyankole...), use Tap to sync - the lyrics keep your spelling.",
            style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------------------------------ tap to sync
@Composable
fun TapSyncScreen(vm: AppViewModel, onClose: () -> Unit) {
    val lines = remember { Lyrics.parseLyrics(vm.project.lyricsText) }
    val taps = remember { mutableStateListOf<Double>() }
    DisposableEffect(Unit) { vm.play(0.0); onDispose { vm.stopPlay() } }
    val idx = taps.size
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tap the big button the moment each line STARTS.", style = MaterialTheme.typography.titleMedium)
        Text("Line ${minOf(idx + 1, lines.size)} of ${lines.size}", style = MaterialTheme.typography.labelMedium)
        Card(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (idx > 0) Text(lines[idx - 1].text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                if (idx < lines.size) Text(lines[idx].text, style = MaterialTheme.typography.headlineSmall)
                else Text("All lines tapped. Press Finish.", style = MaterialTheme.typography.headlineSmall)
                if (idx + 1 < lines.size) Text(lines[idx + 1].text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
        }
        Button(
            onClick = { if (idx < lines.size) taps.add(vm.playerPositionSec()) },
            enabled = idx < lines.size, modifier = Modifier.fillMaxWidth().height(120.dp),
        ) { Text("TAP", style = MaterialTheme.typography.headlineMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (taps.isNotEmpty()) taps.removeAt(taps.lastIndex) }) { Text("Undo") }
            OutlinedButton(onClick = onClose) { Text("Cancel") }
            Button(onClick = {
                if (taps.isNotEmpty()) vm.applyTaps(taps.toList(), minOf(vm.project.duration, taps.last() + 3.0))
                onClose()
            }, enabled = taps.isNotEmpty()) { Text("Finish (${taps.size})") }
        }
    }
}

// ------------------------------------------------------------------------------------------ 2. Timing
@Composable
fun TimingScreen(vm: AppViewModel) {
    vm.rev
    val lines = vm.project.lines
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No timed lyrics yet. Go to the Song tab and sync your lyrics first.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                SectionTitle("Check and fix the timing (${lines.size} lines)")
                Text("Shift everything:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-0.5, -0.1, 0.1, 0.5).forEach { d ->
                        OutlinedButton(onClick = { vm.shiftAll(d) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text((if (d > 0) "+" else "") + d.toString() + "s")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.exportLrc(false) }) { Text("Save LRC") }
                    OutlinedButton(onClick = { vm.exportSrt() }) { Text("Save SRT") }
                    OutlinedButton(onClick = { vm.exportProject() }) { Text("Save project") }
                }
            }
        }
        items(count = lines.size, key = { "${vm.structRev}_$it" }) { i -> LineRow(vm, i, lines[i]) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun LineRow(vm: AppViewModel, i: Int, ln: LyricLine) {
    var s by remember { mutableStateOf(String.format(Locale.ROOT, "%.2f", ln.start)) }
    var e by remember { mutableStateOf(String.format(Locale.ROOT, "%.2f", ln.end)) }
    var t by remember { mutableStateOf(ln.text) }
    val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(s, { v -> s = v; val a = v.toDoubleOrNull(); val b = e.toDoubleOrNull(); if (a != null && b != null) vm.setLineTimes(i, a, b) },
                    label = { Text("Start (s)") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                OutlinedTextField(e, { v -> e = v; val a = s.toDoubleOrNull(); val b = v.toDoubleOrNull(); if (a != null && b != null) vm.setLineTimes(i, a, b) },
                    label = { Text("End (s)") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(t, { v -> t = v; vm.setLineText(i, v) }, label = { Text("Line ${i + 1}") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.play(maxOf(0.0, ln.start - 1.0)) }) { Text("Play") }
                TextButton(onClick = { vm.stopPlay() }) { Text("Stop") }
                TextButton(onClick = { vm.splitLine(i) }) { Text("Split") }
                TextButton(onClick = { vm.mergeNext(i) }) { Text("Merge") }
                TextButton(onClick = { vm.deleteLine(i) }) { Text("Delete") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ 3. Look
@Composable
fun LookScreen(vm: AppViewModel) {
    vm.rev
    val st = vm.project.style
    var previewT by remember { mutableFloatStateOf(0f) }
    val maxT = maxOf(1f, vm.project.duration.toFloat())
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("3. The look")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(PRESETS.keys.toList()) { name ->
                FilterChip(selected = st.name == name, onClick = { vm.applyPreset(name) }, label = { Text(name) })
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val img = vm.previewImage
                if (img != null) Image(bitmap = img.asImageBitmap(), contentDescription = "Preview", modifier = Modifier.fillMaxWidth())
                else Text("Press Refresh preview to see how the lyrics will look.", style = MaterialTheme.typography.bodySmall)
                Slider(value = previewT, onValueChange = { previewT = it }, valueRange = 0f..maxT)
                Text("Preview at ${fmtTime(previewT.toDouble())}", style = MaterialTheme.typography.labelSmall)
                Button(onClick = { vm.refreshPreview(previewT.toDouble()) }, enabled = !vm.previewBusy) {
                    Text(if (vm.previewBusy) "Drawing..." else "Refresh preview")
                }
            }
        }
        SectionTitle("Text")
        Dropdown("Font", CURATED_FONTS.map { it.label }, CURATED_FONTS.firstOrNull { it.family == st.font }?.label ?: st.font) { l ->
            CURATED_FONTS.first { it.label == l }.let { c -> vm.styleEdit { s -> s.font = c.family; s.bold = c.bold } }
        }
        LabeledSlider("Size", st.size.toFloat(), 0.03f..0.16f) { v -> vm.styleEdit { it.size = v.toDouble() } }
        LabeledSlider("Vertical position", st.posY.toFloat(), 0.1f..0.95f) { v -> vm.styleEdit { it.posY = v.toDouble() } }
        ToggleRow("CAPITAL LETTERS", st.uppercase) { v -> vm.styleEdit { it.uppercase = v } }
        ToggleRow("Glow", st.glow) { v -> vm.styleEdit { it.glow = v } }
        ToggleRow("Box behind text", st.box) { v -> vm.styleEdit { it.box = v } }
        ToggleRow("Show the next line", st.showNext) { v -> vm.styleEdit { it.showNext = v } }
        HexField("Not yet sung", st.colorUnsung) { v -> vm.styleEdit { it.colorUnsung = v } }
        HexField("Sung (highlight)", st.colorSung) { v -> vm.styleEdit { it.colorSung = v } }
        HexField("Outline", st.outlineColor) { v -> vm.styleEdit { it.outlineColor = v } }
        if (st.glow) HexField("Glow colour", st.glowColor) { v -> vm.styleEdit { it.glowColor = v } }
        SectionTitle("Motion")
        Dropdown("Highlight", HIGHLIGHTS, st.highlight) { v -> vm.styleEdit { it.highlight = v } }
        Dropdown("Entrance", ANIMATIONS, st.animIn) { v -> vm.styleEdit { it.animIn = v } }
        Dropdown("Scene transition", TRANSITIONS, st.transition) { v -> vm.styleEdit { it.transition = v } }
        SectionTitle("Background")
        Dropdown("Colour grade", GRADES.keys.toList(), st.grade) { v -> vm.styleEdit { it.grade = v } }
        LabeledSlider("Darken", st.bgDim.toFloat(), 0f..0.9f) { v -> vm.styleEdit { it.bgDim = v.toDouble() } }
        LabeledSlider("Blur", st.bgBlur.toFloat(), 0f..30f) { v -> vm.styleEdit { it.bgBlur = v.toDouble() } }
        LabeledSlider("Film grain", st.grain.toFloat(), 0f..0.3f) { v -> vm.styleEdit { it.grain = v.toDouble() } }
        ToggleRow("Vignette", st.vignette) { v -> vm.styleEdit { it.vignette = v } }
        ToggleRow("Slow zoom on pictures (Ken Burns)", st.kenBurns) { v -> vm.styleEdit { it.kenBurns = v } }
        Dropdown("Audio visualiser", VIZ_MODES, st.viz) { v -> vm.styleEdit { it.viz = v } }
        ToggleRow("Title card at the start", st.titleCard) { v -> vm.styleEdit { it.titleCard = v } }
    }
}

// ------------------------------------------------------------------------------------------ 4. Visuals
@Composable
fun VisualsScreen(vm: AppViewModel) {
    vm.rev
    val p = vm.project
    var pickFor by remember { mutableIntStateOf(-1) }
    val ctx = LocalContext.current
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pickFor >= 0) vm.setSceneMedia(pickFor, uri, ctx.contentResolver.getType(uri))
    }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.setLogo(uri) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("4. Backgrounds")
        Text("One background per stanza; a repeated chorus shares the same one. Plain animated gradients are used where you add nothing.",
            style = MaterialTheme.typography.bodySmall)
        SectionTitle("AI backgrounds")
        Text("Finds a picture for each stanza from your lyrics (needs internet). The free server can take up to a minute to wake up. " +
            "Pictures you picked yourself are kept.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = vm.visualsKey, onValueChange = { vm.saveVisualsKey(it) }, label = { Text("Visuals key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Dropdown("Media", VisualsApi.MEDIA, vm.visualsMedia) { v -> vm.visualsMedia = v }
        if (vm.visualsMedia == "video") Text("Video backgrounds use the slower standard renderer.", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { vm.generateVisuals() }, modifier = Modifier.fillMaxWidth(),
            enabled = !vm.job.collectAsState().value.running && vm.visualsKey.isNotBlank() && p.scenes.isNotEmpty(),
        ) { Text("Find backgrounds for my lyrics") }
        if (p.scenes.isEmpty()) Text("Sync the lyrics first - scenes are planned from them.")
        p.scenes.forEachIndexed { i, sc ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Scene ${i + 1}: ${fmtTime(sc.start)} - ${fmtTime(sc.end)}", style = MaterialTheme.typography.titleSmall)
                    if (sc.label.isNotEmpty()) Text("\"${sc.label}...\"", style = MaterialTheme.typography.bodySmall)
                    Text(if (sc.kind == "gradient") "Animated gradient" else "${sc.kind}: ${File(sc.path).name.takeLast(30)}", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickFor = i; pickMedia.launch(arrayOf("image/*", "video/*")) }) { Text("Pick photo / video") }
                        if (sc.kind != "gradient") TextButton(onClick = { vm.clearScene(i) }) { Text("Use gradient") }
                    }
                }
            }
        }
        SectionTitle("Logo (optional)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickLogo.launch(arrayOf("image/*")) }) { Text(if (p.logo.isEmpty()) "Add logo" else "Change logo") }
            if (p.logo.isNotEmpty()) TextButton(onClick = { vm.setLogo(null) }) { Text("Remove") }
        }
        if (p.logo.isNotEmpty()) {
            Dropdown("Logo position", LOGO_POSITIONS, p.logoPos) { v -> vm.edit { it.logoPos = v } }
            LabeledSlider("Logo size", p.logoSize.toFloat(), 0.05f..0.4f) { v -> vm.edit { it.logoSize = v.toDouble() } }
        }
    }
}

// ------------------------------------------------------------------------------------------ 5. Export
@Composable
fun ExportScreen(vm: AppViewModel) {
    vm.rev
    val p = vm.project
    val job = vm.job.collectAsState().value
    val ctx = LocalContext.current
    val ready = p.audio.isNotBlank() && p.lines.any { it.timed }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("5. Export")
        Dropdown("Video shape", FORMATS.keys.toList(), p.fmt) { v -> vm.edit { it.fmt = v } }
        Dropdown("Quality", QUALITIES.keys.toList(), p.quality) { v -> vm.edit { it.quality = v } }
        if ((QUALITIES[p.quality] ?: 720) >= 1440) Text("2K/4K is very heavy on a phone: it takes long and the phone gets hot. 1080p is plenty for YouTube and Reels.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        ToggleRow("Make the volume even (loudness normalise)", p.normalizeAudio) { v -> vm.edit { it.normalizeAudio = v } }
        ToggleRow("Small credit line at the end", p.credit) { v -> vm.edit { it.credit = v } }
        if (!ready) Text("Choose a song and sync the lyrics first.", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.render(true) }, enabled = ready && !job.running) { Text("Preview 22 s") }
            Button(onClick = { vm.render(false) }, enabled = ready && !job.running) { Text("Render video") }
        }
        val saved = vm.savedVideo
        if (saved != null) {
            Text("Done! Saved in Movies/IdeawoodStudio.", color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, saved).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(i, "Share your lyric video"))
                }) { Text("Share") }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(saved, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }) { Text("Play") }
            }
        }
    }
    val clip = vm.previewClip
    if (clip != null) {
        AlertDialog(
            onDismissRequest = { vm.previewClip = null; clip.delete() },
            confirmButton = { TextButton(onClick = { vm.previewClip = null; clip.delete() }) { Text("Close") } },
            title = { Text("Preview") },
            text = {
                AndroidView(factory = { c ->
                    VideoView(c).apply {
                        setVideoPath(clip.path)
                        setOnPreparedListener { it.isLooping = true; start() }
                    }
                }, modifier = Modifier.fillMaxWidth().height(300.dp))
            },
        )
    }
}
