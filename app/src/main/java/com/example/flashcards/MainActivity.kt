package com.example.flashcards

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random

val LANGS = listOf("en-US" to "English", "es-ES" to "Español", "fr-FR" to "Français", "de-DE" to "Deutsch",
    "it-IT" to "Italiano", "pt-BR" to "Português", "ru-RU" to "Русский", "ja-JP" to "日本語",
    "ko-KR" to "한국어", "zh-CN" to "中文", "hi-IN" to "हिन्दी", "ar" to "العربية")
val LEVELS = listOf("New", "Learning", "Familiar", "Mastered")
val LEVEL_COLORS = listOf(Color(0xFFE17055), Color(0xFFFDCB6E), Color(0xFF74B9FF), Color(0xFF00B894))
val GREEN = Color(0xFFB8F2D0)
val RED = Color(0xFFFFC9C9)

class MainActivity : ComponentActivity() {
    private lateinit var speaker: Speaker
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(applicationContext)
        speaker = Speaker(applicationContext)
        setContent { AppTheme { App(store, speaker) } }
    }
    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(primary = Color(0xFFA29BFE), secondary = Color(0xFF55EFC4), background = Color(0xFF14131F), surface = Color(0xFF1E1D2B))
    else lightColorScheme(primary = Color(0xFF6C5CE7), secondary = Color(0xFF00B894), background = Color(0xFFF7F6FF), surface = Color.White)
    MaterialTheme(colorScheme = scheme, content = content)
}

sealed interface Screen {
    object Home : Screen
    data class DeckS(val id: String) : Screen
    data class TestS(val id: String) : Screen
}

@Composable
fun App(store: Store, sp: Speaker) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(screen != Screen.Home) {
        screen = when (val s = screen) { is Screen.TestS -> Screen.DeckS(s.id); else -> Screen.Home }
    }
    when (val s = screen) {
        Screen.Home -> HomeScreen(store) { screen = Screen.DeckS(it) }
        is Screen.DeckS -> {
            val d = store.decks.firstOrNull { it.id == s.id }
            if (d == null) LaunchedEffect(Unit) { screen = Screen.Home }
            else DeckScreen(store, sp, d, { screen = Screen.TestS(d.id) }, { screen = Screen.Home })
        }
        is Screen.TestS -> {
            val d = store.decks.firstOrNull { it.id == s.id }
            if (d == null) LaunchedEffect(Unit) { screen = Screen.Home }
            else TestScreen(store, sp, d) { screen = Screen.DeckS(d.id) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmallBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1) },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions
    )
}

// ---------------------------------------------------------------- Home
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(store: Store, open: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("en-US") }
    var delDeck by remember { mutableStateOf<Deck?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Flashcards", fontWeight = FontWeight.ExtraBold) }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New deck") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(store.decks, key = { it.id }) { d ->
                val n = store.cards.count { it.deckId == d.id }
                val weak = store.cards.count { it.deckId == d.id && it.level <= 1 }
                Card(Modifier.fillMaxWidth().clickable { open(d.id) }, shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.background(Brush.linearGradient(listOf(Color(0xFF6C5CE7), Color(0xFFA29BFE)))).padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("$n cards · $weak to review", color = Color.White.copy(alpha = .85f))
                        }
                        IconButton({ delDeck = d }) { Icon(Icons.Default.Delete, "Delete deck", tint = Color.White) }
                    }
                }
            }
        }
    }
    if (adding) AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text("New deck") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Deck name") }, singleLine = true)
                LangPicker(lang) { lang = it }
            }
        },
        confirmButton = { TextButton({ if (name.isNotBlank()) store.addDeck(name.trim(), lang); name = ""; adding = false }) { Text("Create") } },
        dismissButton = { TextButton({ adding = false }) { Text("Cancel") } }
    )
    delDeck?.let { d ->
        AlertDialog(onDismissRequest = { delDeck = null }, title = { Text("Delete \"${d.name}\"?") }, text = { Text("All cards in this deck will be deleted.") },
            confirmButton = { TextButton({ store.deleteDeck(d); delDeck = null }) { Text("Delete") } },
            dismissButton = { TextButton({ delDeck = null }) { Text("Cancel") } })
    }
}

@Composable
fun LangPicker(lang: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) { Text("Speech language: ${LANGS.firstOrNull { it.first == lang }?.second ?: lang}") }
        DropdownMenu(open, { open = false }) {
            LANGS.forEach { (code, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onPick(code); open = false }) }
        }
    }
}

// ---------------------------------------------------------------- Deck
enum class Order(val label: String) { Manual("Manual order"), AZ("A–Z"), Level("Weakest first"), Shuffle("Shuffle") }

fun shareFile(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    ctx.startActivity(Intent.createChooser(i, "Share flashcards"))
}

fun playFile(path: String) {
    try { MediaPlayer().apply { setDataSource(path); prepare(); start(); setOnCompletionListener { it.release() } } } catch (_: Exception) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(store: Store, sp: Speaker, deck: Deck, onTest: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var weakOnly by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf(Order.Manual) }
    var seed by remember { mutableStateOf(1) }
    var editing by remember { mutableStateOf<Flash?>(null) }
    var menu by remember { mutableStateOf(false) }
    var orderMenu by remember { mutableStateOf(false) }
    var langDlg by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(true) }
    var speed by remember { mutableStateOf(1f) }
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    val rp by rememberUpdatedState(repeat)
    val sd by rememberUpdatedState(speed)

    val all = store.cards.filter { it.deckId == deck.id }
    var list = all.filter { (!weakOnly || it.level <= 1) && (query.isBlank() || it.front.contains(query, true) || it.back.contains(query, true)) }
    list = when (order) {
        Order.Manual -> list
        Order.AZ -> list.sortedBy { it.front.lowercase() }
        Order.Level -> list.sortedBy { it.level }
        Order.Shuffle -> list.shuffled(Random(seed))
    }
    val shownNow by rememberUpdatedState(list)

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val t = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            Toast.makeText(ctx, "Imported ${store.importCsv(deck.id, t)} cards", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(playing) {
        if (playing) {
            try {
                do {
                    for (c in shownNow) { sp.say(c.front, deck.lang, sd); delay(300); sp.say(c.back, deck.lang, sd); delay(700) }
                } while (rp && shownNow.isNotEmpty())
            } finally { playing = false }
        }
    }

    Scaffold(topBar = {
        SmallBar(deck.name, onBack) {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Speech language") }, onClick = { menu = false; langDlg = true })
                DropdownMenuItem(text = { Text("Export / share CSV") }, onClick = { menu = false; shareFile(ctx, store.csvFile(deck.id, false)) })
                DropdownMenuItem(text = { Text("Get PC template") }, onClick = { menu = false; shareFile(ctx, store.csvFile(deck.id, true)) })
                DropdownMenuItem(text = { Text("Import CSV") }, onClick = { menu = false; importer.launch(arrayOf("*/*")) })
            }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // quick add
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(front, { front = it }, Modifier.fillMaxWidth(), label = { Text("Front") }, singleLine = true)
                            OutlinedTextField(back, { back = it }, Modifier.fillMaxWidth(), label = { Text("Back") }, singleLine = true)
                        }
                        FilledIconButton(onClick = {
                            if (front.isNotBlank() && back.isNotBlank()) { store.upsert(Flash(deckId = deck.id, front = front.trim(), back = back.trim())); front = ""; back = "" }
                        }, Modifier.padding(start = 10.dp).size(52.dp)) { Icon(Icons.Default.Add, "Add card") }
                    }
                }
            }
            // search + filters
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search cards") },
                        leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(weakOnly, { weakOnly = !weakOnly }, label = { Text("Weak only") })
                        Box {
                            AssistChip({ orderMenu = true }, label = { Text(order.label) })
                            DropdownMenu(orderMenu, { orderMenu = false }) {
                                Order.values().forEach { o -> DropdownMenuItem(text = { Text(o.label) }, onClick = { order = o; seed++; orderMenu = false }) }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("${list.size}/${all.size}", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            // read aloud + test
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton({ playing = !playing }) {
                                Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (playing) "Stop" else "Read aloud")
                            }
                            FilterChip(repeat, { repeat = !repeat }, label = { Text("Repeat") }, leadingIcon = { Icon(Icons.Default.Repeat, null, Modifier.size(18.dp)) })
                            Spacer(Modifier.weight(1f))
                            Button(onTest, enabled = all.size >= 2) { Text("Test") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Speed ${"%.1f".format(speed)}x", Modifier.width(92.dp))
                            Slider(speed, { speed = (it * 10).roundToInt() / 10f }, valueRange = 0.5f..1.5f)
                        }
                    }
                }
            }
            items(list, key = { it.id }) { c ->
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.front, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text(c.back, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                            }
                            c.image?.let { Thumb(it, 56.dp) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistChip({ store.upsert(c.copy(level = (c.level + 1) % 4)) }, label = { Text(LEVELS[c.level]) },
                                leadingIcon = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(LEVEL_COLORS[c.level])) })
                            Spacer(Modifier.weight(1f))
                            if (order == Order.Manual && query.isBlank() && !weakOnly) {
                                IconButton({ store.move(c, -1) }, Modifier.size(36.dp)) { Icon(Icons.Default.ArrowUpward, "Up") }
                                IconButton({ store.move(c, 1) }, Modifier.size(36.dp)) { Icon(Icons.Default.ArrowDownward, "Down") }
                            }
                            if (c.audio != null) IconButton({ playFile(c.audio) }, Modifier.size(36.dp)) { Icon(Icons.Default.Mic, "Play recording") }
                            IconButton({ sp.speak(c.front, deck.lang, speed); }, Modifier.size(36.dp)) { Icon(Icons.Default.VolumeUp, "Speak") }
                            IconButton({ editing = c }, Modifier.size(36.dp)) { Icon(Icons.Default.Edit, "Edit") }
                            IconButton({ store.copy(c) }, Modifier.size(36.dp)) { Icon(Icons.Default.ContentCopy, "Copy") }
                            IconButton({ store.delete(c) }, Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { EditorDialog(store, it) { editing = null } }
    if (langDlg) AlertDialog(onDismissRequest = { langDlg = false }, title = { Text("Speech language") },
        text = { LangPicker(deck.lang) { store.updateDeck(deck.copy(lang = it)); langDlg = false } },
        confirmButton = { TextButton({ langDlg = false }) { Text("Close") } })
}

@Composable
fun Thumb(path: String, h: Dp) {
    val bmp = remember(path) {
        try { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() } catch (_: Exception) { null }
    }
    if (bmp != null) Image(bmp, null, Modifier.height(h).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
}

// ---------------------------------------------------------------- Editor (image + sound)
@Composable
fun EditorDialog(store: Store, card: Flash, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf(card) }
    var camFile by remember { mutableStateOf<File?>(null) }
    var rec by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) c = c.copy(image = store.copyMedia(u, "jpg")) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) camFile?.let { c = c.copy(image = it.absolutePath) } }

    @Suppress("DEPRECATION")
    fun startRec() {
        try {
            val f = store.newMedia("m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            rec = r; recFile = f
        } catch (_: Exception) { Toast.makeText(ctx, "Could not start recording", Toast.LENGTH_SHORT).show() }
    }
    fun stopRec() {
        try { rec?.stop() } catch (_: Exception) {}
        rec?.release(); rec = null
        recFile?.let { c = c.copy(audio = it.absolutePath) }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startRec() }

    AlertDialog(
        onDismissRequest = { if (rec != null) stopRec(); onDismiss() },
        title = { Text("Edit card") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(c.front, { c = c.copy(front = it) }, label = { Text("Front") })
                OutlinedTextField(c.back, { c = c.copy(back = it) }, label = { Text("Back") })
                Text("Memorization level", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LEVELS.forEachIndexed { i, l -> FilterChip(c.level == i, { c = c.copy(level = i) }, label = { Text(l, fontSize = 11.sp, maxLines = 1) }) }
                }
                Text("Image", fontWeight = FontWeight.SemiBold)
                c.image?.let { Thumb(it, 120.dp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pick.launch("image/*") }) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(4.dp)); Text("Gallery") }
                    OutlinedButton({ val f = store.newMedia("jpg"); camFile = f; cam.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)) }) {
                        Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(4.dp)); Text("Camera")
                    }
                    if (c.image != null) IconButton({ c = c.copy(image = null) }) { Icon(Icons.Default.Close, "Remove image") }
                }
                Text("Sound", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (rec == null) OutlinedButton({ mic.launch(Manifest.permission.RECORD_AUDIO) }) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(4.dp)); Text("Record") }
                    else Button({ stopRec() }) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop") }
                    c.audio?.let { a ->
                        IconButton({ playFile(a) }) { Icon(Icons.Default.PlayArrow, "Play") }
                        IconButton({ c = c.copy(audio = null) }) { Icon(Icons.Default.Close, "Remove sound") }
                    }
                }
            }
        },
        confirmButton = { TextButton({ if (rec != null) stopRec(); if (c.front.isNotBlank() && c.back.isNotBlank()) store.upsert(c); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton({ if (rec != null) stopRec(); onDismiss() }) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- Test mode
@Composable
fun TestScreen(store: Store, sp: Speaker, deck: Deck, onBack: () -> Unit) {
    val all = store.cards.filter { it.deckId == deck.id }
    var weakOnly by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf(10f) }
    var qs by remember { mutableStateOf<List<Flash>?>(null) }
    val pool = all.filter { !weakOnly || it.level <= 1 }
    Scaffold(topBar = { SmallBar("Test · ${deck.name}", onBack) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().padding(20.dp)) {
            val q = qs
            if (q == null) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (all.map { it.back }.distinct().size < 2) Text("Add at least 2 cards with different answers to start a test.")
                    else {
                        Text("Scope", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!weakOnly, { weakOnly = false }, label = { Text("All cards (${all.size})") })
                            FilterChip(weakOnly, { weakOnly = true }, label = { Text("Weak only (${all.count { it.level <= 1 }})") })
                        }
                        val n = count.roundToInt().coerceIn(1, pool.size.coerceAtLeast(1))
                        Text("Questions: $n")
                        Slider(count, { count = it }, valueRange = 1f..50f)
                        Button({ qs = pool.shuffled().take(n) }, Modifier.fillMaxWidth(), enabled = pool.isNotEmpty()) { Text("Start test") }
                    }
                }
            } else Quiz(store, sp, deck, all, q, { qs = null }, onBack)
        }
    }
}

@Composable
fun Quiz(store: Store, sp: Speaker, deck: Deck, all: List<Flash>, qs: List<Flash>, onRetry: () -> Unit, onDone: () -> Unit) {
    var idx by remember { mutableStateOf(0) }
    var score by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (finished) {
        var go by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { go = true }
        val pct by animateIntAsState(if (go) score * 100 / qs.size else 0, tween(1000), label = "pct")
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("$pct%", fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Text("$score / ${qs.size} correct", fontSize = 20.sp)
            Spacer(Modifier.height(24.dp))
            Button(onRetry) { Text("Try again") }
            TextButton(onDone) { Text("Back to deck") }
        }
    } else {
        val q = qs[idx]
        val options = remember(idx) { (all.map { it.back }.filter { it != q.back }.distinct().shuffled().take(3) + q.back).shuffled() }
        LaunchedEffect(idx) { sp.speak(q.front, deck.lang, 1f) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(progress = { idx / qs.size.toFloat() }, Modifier.fillMaxWidth())
            AnimatedContent(q, label = "question") { cur ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        cur.image?.let { Thumb(it, 140.dp) }
                        Text(cur.front, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        IconButton({ sp.speak(cur.front, deck.lang, 1f) }) { Icon(Icons.Default.VolumeUp, "Speak") }
                    }
                }
            }
            val neutral = MaterialTheme.colorScheme.surfaceVariant
            options.forEach { opt ->
                val target = when { picked == null -> neutral; opt == q.back -> GREEN; opt == picked -> RED; else -> neutral }
                OptionButton(opt, target, picked == opt || (picked != null && opt == q.back)) {
                    if (picked == null) {
                        picked = opt
                        val ok = opt == q.back
                        if (ok) score++
                        store.upsert(q.copy(level = if (ok) minOf(3, q.level + 1) else 0))
                        scope.launch { delay(1000); if (idx + 1 < qs.size) { idx++; picked = null } else finished = true }
                    }
                }
            }
        }
    }
}

@Composable
fun OptionButton(text: String, color: Color, active: Boolean, onClick: () -> Unit) {
    val c by animateColorAsState(color, label = "c")
    val s by animateFloatAsState(if (active) 1.04f else 1f, label = "s")
    val semantic = color == GREEN || color == RED
    Box(Modifier.fillMaxWidth().scale(s).clip(RoundedCornerShape(16.dp)).background(c).clickable(onClick = onClick).padding(18.dp)) {
        Text(text, fontSize = 18.sp, color = if (semantic) Color.Black else MaterialTheme.colorScheme.onSurface)
    }
}
