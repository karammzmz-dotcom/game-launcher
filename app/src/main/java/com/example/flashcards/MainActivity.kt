package com.example.flashcards

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
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
        val ts = ThemeSettings(applicationContext)
        setContent { AppTheme(ts) { App(store, speaker, ts) } }
    }
    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }
}

// ---------------------------------------------------------------- Theme settings (colors + fonts)
class AppFont(val name: String, private val sys: String) {
    val family: FontFamily by lazy { FontFamily(Typeface.create(sys, Typeface.NORMAL)) }
}

/** 10 built-in fonts (come with Android, nothing to bundle). */
val FONTS = listOf(
    AppFont("Roboto", "sans-serif"),
    AppFont("Light", "sans-serif-light"),
    AppFont("Condensed", "sans-serif-condensed"),
    AppFont("Medium", "sans-serif-medium"),
    AppFont("Black", "sans-serif-black"),
    AppFont("Small Caps", "sans-serif-smallcaps"),
    AppFont("Serif", "serif"),
    AppFont("Monospace", "monospace"),
    AppFont("Handwritten", "casual"),
    AppFont("Script", "cursive")
)
const val FONT_CUSTOM = -1

class ThemeSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("theme", Context.MODE_PRIVATE)
    private fun load(k: String): Color? = if (prefs.contains(k)) Color(prefs.getInt(k, 0)) else null

    var primary by mutableStateOf<Color?>(load("primary"))
    var accent by mutableStateOf<Color?>(load("accent"))
    var background by mutableStateOf<Color?>(load("bg"))
    var fontIndex by mutableStateOf(prefs.getInt("font", 0))
    var customName by mutableStateOf<String?>(prefs.getString("customName", null))
    var customFamily by mutableStateOf<FontFamily?>(null)

    init {
        prefs.getString("customPath", null)?.let { p ->
            try { customFamily = FontFamily(Typeface.createFromFile(p)) }
            catch (_: Exception) { customFamily = null; if (fontIndex == FONT_CUSTOM) fontIndex = 0 }
        }
    }

    val family: FontFamily
        get() = if (fontIndex == FONT_CUSTOM) customFamily ?: FONTS[0].family else FONTS.getOrElse(fontIndex) { FONTS[0] }.family

    fun persist() {
        prefs.edit().apply {
            fun put(k: String, c: Color?) { if (c == null) remove(k) else putInt(k, c.toArgb()) }
            put("primary", primary); put("accent", accent); put("bg", background)
            putInt("font", fontIndex)
        }.apply()
    }

    fun selectFont(i: Int) { fontIndex = i; persist() }

    fun reset() { primary = null; accent = null; background = null; fontIndex = 0; persist() }

    /** Copies a .ttf/.otf the user picked into app storage and activates it. */
    fun importFont(uri: Uri): Boolean {
        val f = File(app.filesDir, "custom_font_${System.currentTimeMillis()}.ttf")
        return try {
            app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val tf = Typeface.createFromFile(f)
            val oldPath = prefs.getString("customPath", null)
            var name: String? = null
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) { val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ix >= 0) name = c.getString(ix) }
            }
            customFamily = FontFamily(tf)
            customName = name?.substringBeforeLast('.') ?: "Custom font"
            fontIndex = FONT_CUSTOM
            prefs.edit().putString("customPath", f.absolutePath).putString("customName", customName).apply()
            persist()
            oldPath?.let { File(it).delete() }
            true
        } catch (_: Exception) { f.delete(); false }
    }
}

fun onColor(c: Color) = if (c.luminance() > .5f) Color(0xFF1B1A24) else Color.White

fun Typography.withFont(f: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f), displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f), headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f), titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f)
)

@Composable
fun AppTheme(ts: ThemeSettings, content: @Composable () -> Unit) {
    val base = if (isSystemInDarkTheme())
        darkColorScheme(primary = Color(0xFFA29BFE), secondary = Color(0xFF55EFC4), background = Color(0xFF14131F), surface = Color(0xFF1E1D2B))
    else lightColorScheme(primary = Color(0xFF6C5CE7), secondary = Color(0xFF00B894), background = Color(0xFFF7F6FF), surface = Color.White)
    val p = ts.primary ?: base.primary
    val s = ts.accent ?: base.secondary
    val bg = ts.background ?: base.background
    val onBg = onColor(bg)
    var scheme = base.copy(
        primary = p, onPrimary = onColor(p), secondary = s, onSecondary = onColor(s),
        primaryContainer = lerp(bg, p, .28f), onPrimaryContainer = onBg,
        secondaryContainer = lerp(bg, s, .28f), onSecondaryContainer = onBg
    )
    if (ts.background != null) scheme = scheme.copy(
        background = bg, onBackground = onBg, surface = lerp(bg, onBg, .06f), onSurface = onBg,
        surfaceVariant = lerp(bg, onBg, .12f), onSurfaceVariant = onBg.copy(alpha = .75f), outline = onBg.copy(alpha = .5f),
        surfaceContainerLowest = lerp(bg, onBg, .02f), surfaceContainerLow = lerp(bg, onBg, .04f), surfaceContainer = lerp(bg, onBg, .08f),
        surfaceContainerHigh = lerp(bg, onBg, .10f), surfaceContainerHighest = lerp(bg, onBg, .12f)
    )
    val family = ts.family
    val typo = remember(family) { Typography().withFont(family) }
    MaterialTheme(colorScheme = scheme, typography = typo, content = content)
}

sealed interface Screen {
    object Home : Screen
    object SettingsS : Screen
    data class DeckS(val id: String) : Screen
    data class StudyS(val id: String, val home: Boolean = false) : Screen
    data class TestS(val id: String, val home: Boolean = false) : Screen
}

@Composable
fun App(store: Store, sp: Speaker, ts: ThemeSettings) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(screen != Screen.Home) {
        screen = when (val s = screen) {
            is Screen.TestS -> if (s.home) Screen.Home else Screen.DeckS(s.id)
            is Screen.StudyS -> if (s.home) Screen.Home else Screen.DeckS(s.id)
            else -> Screen.Home
        }
    }
    when (val s = screen) {
        Screen.Home -> HomeScreen(store,
            open = { screen = Screen.DeckS(it) },
            onStudy = { screen = Screen.StudyS(it, true) },
            onTest = { screen = Screen.TestS(it, true) },
            onSettings = { screen = Screen.SettingsS })
        Screen.SettingsS -> SettingsScreen(ts) { screen = Screen.Home }
        is Screen.DeckS -> {
            val d = store.decks.firstOrNull { it.id == s.id }
            if (d == null) LaunchedEffect(Unit) { screen = Screen.Home }
            else DeckScreen(store, sp, d, { screen = Screen.StudyS(d.id) }, { screen = Screen.TestS(d.id) }, { screen = Screen.Home })
        }
        is Screen.StudyS -> {
            val d = store.decks.firstOrNull { it.id == s.id }
            if (d == null) LaunchedEffect(Unit) { screen = Screen.Home }
            else StudyScreen(store, sp, d) { screen = if (s.home) Screen.Home else Screen.DeckS(d.id) }
        }
        is Screen.TestS -> {
            val d = store.decks.firstOrNull { it.id == s.id }
            if (d == null) LaunchedEffect(Unit) { screen = Screen.Home }
            else TestScreen(store, sp, d) { screen = if (s.home) Screen.Home else Screen.DeckS(d.id) }
        }
    }
}

// ---------------------------------------------------------------- Flashcard look + flip
fun fit(text: String, base: Int): TextUnit {
    val f = when { text.length > 120 -> .55f; text.length > 60 -> .7f; text.length > 30 -> .85f; else -> 1f }
    return (base * f).sp
}

/** One side of an index card: paper, colored top band, header line and ruled lines. */
@Composable
fun CardFace(back: Boolean, label: String, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val lightBg = cs.background.luminance() > .5f
    val base = if (lightBg) Color(0xFFFFFDF6) else Color(0xFF2B2A38)
    val accent = if (back) cs.secondary else cs.primary
    val paper = if (back) lerp(base, accent, if (lightBg) .14f else .22f) else base
    val ink = if (paper.luminance() > .5f) Color(0xFF22212B) else Color(0xFFF4F3FB)
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(14.dp), color = paper, contentColor = ink, shadowElevation = 8.dp) {
        Box(Modifier.fillMaxSize().drawBehind {
            val w = size.width; val h = size.height
            drawRect(accent, size = Size(w, 6.dp.toPx()))
            val head = 40.dp.toPx(); val step = 24.dp.toPx()
            var y = head + step
            while (y < h - 6.dp.toPx()) { drawLine(accent.copy(alpha = .16f), Offset(0f, y), Offset(w, y), 1.dp.toPx()); y += step }
            drawLine(accent.copy(alpha = .6f), Offset(0f, head), Offset(w, head), 2.dp.toPx())
        }) {
            Text(label, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 14.dp), color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Icon(Icons.Default.Sync, null, Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp).size(16.dp), tint = accent.copy(alpha = .7f))
            Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 8.dp), Arrangement.Center, Alignment.CenterHorizontally, content = content)
        }
    }
}

@Composable
fun FaceText(text: String, base: Int) =
    Text(text, fontSize = fit(text, base), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 6, overflow = TextOverflow.Ellipsis)

/** Tap to flip around the vertical axis with a little lift in the middle of the turn. */
@Composable
fun FlipCard(flipped: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, front: @Composable () -> Unit, back: @Composable () -> Unit) {
    val rot by animateFloatAsState(if (flipped) 180f else 0f, tween(600, easing = FastOutSlowInEasing), label = "flip")
    val dens = LocalDensity.current.density
    val lift = 1f + 0.06f * sin(rot * PI.toFloat() / 180f)
    Box(
        modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .graphicsLayer { rotationY = rot; cameraDistance = 14f * dens; scaleX = lift; scaleY = lift }
    ) {
        if (rot <= 90f) front() else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { back() }
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
fun HomeScreen(store: Store, open: (String) -> Unit, onStudy: (String) -> Unit, onTest: (String) -> Unit, onSettings: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("en-US") }
    var delDeck by remember { mutableStateOf<Deck?>(null) }
    val cs = MaterialTheme.colorScheme
    val onP = cs.onPrimary
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flashcards", fontWeight = FontWeight.ExtraBold) },
                actions = { IconButton(onSettings) { Icon(Icons.Default.Settings, "Appearance") } }
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New deck") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(store.decks, key = { it.id }) { d ->
                val deckCards = store.cards.filter { it.deckId == d.id }
                val n = deckCards.size
                val weak = deckCards.count { it.level <= 1 }
                val canTest = deckCards.map { it.back }.distinct().size >= 2
                val btn = ButtonDefaults.buttonColors(containerColor = onP, contentColor = cs.primary, disabledContainerColor = onP.copy(alpha = .35f), disabledContentColor = cs.primary.copy(alpha = .5f))
                Card(Modifier.fillMaxWidth().clickable { open(d.id) }, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.background(Brush.linearGradient(listOf(cs.primary, lerp(cs.primary, cs.secondary, .45f)))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, color = onP, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("$n cards · $weak to review", color = onP.copy(alpha = .85f))
                            }
                            IconButton({ delDeck = d }) { Icon(Icons.Default.Delete, "Delete deck", tint = onP) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button({ onStudy(d.id) }, Modifier.weight(1f), enabled = n > 0, colors = btn) {
                                Icon(Icons.Default.Style, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Study")
                            }
                            Button({ onTest(d.id) }, Modifier.weight(1f), enabled = canTest, colors = btn) {
                                Icon(Icons.Default.School, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Test")
                            }
                        }
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

// ---------------------------------------------------------------- Appearance (colors + fonts)
@Composable
fun ColorWheelPicker(key: Any, initial: Color, onChange: (Color) -> Unit) {
    val init = remember(key) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember(key) { mutableStateOf(init[0]) }
    var sat by remember(key) { mutableStateOf(init[1]) }
    var v by remember(key) { mutableStateOf(init[2]) }
    val cb by rememberUpdatedState(onChange)
    fun emit() = cb(Color.hsv(hue, sat, v))
    val hues = remember { listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it, 1f, 1f) } }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier.size(250.dp).pointerInput(key) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun upd(p: Offset) {
                        val dx = p.x - size.width / 2f
                        val dy = p.y - size.height / 2f
                        val r = min(size.width, size.height) / 2f
                        hue = (atan2(dy, dx) * 180f / PI.toFloat() + 360f) % 360f
                        sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
                        emit()
                    }
                    upd(down.position); down.consume()
                    drag(down.id) { ch -> upd(ch.position); ch.consume() }
                }
            }
        ) {
            val r = size.minDimension / 2f
            drawCircle(Brush.sweepGradient(hues, center), r)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r)
            drawCircle(Color.Black.copy(alpha = 1f - v), r)
            val a = hue * PI.toFloat() / 180f
            val pos = center + Offset(cos(a) * sat * r, sin(a) * sat * r)
            drawCircle(Color.hsv(hue, sat, v), 13.dp.toPx(), pos)
            drawCircle(Color.White, 13.dp.toPx(), pos, style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = .35f), 15.dp.toPx(), pos, style = Stroke(1.dp.toPx()))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Brightness", Modifier.width(90.dp))
            Slider(v, { v = it; emit() }, valueRange = 0f..1f)
        }
        Text("#%06X".format(Color.hsv(hue, sat, v).toArgb() and 0xFFFFFF), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun FontRow(name: String, family: FontFamily, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) cs.primary.copy(alpha = .14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected, onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = cs.onSurface.copy(alpha = .65f))
            Text("The quick brown fox", fontFamily = family, fontSize = 20.sp)
        }
    }
}

@Composable
fun SettingsScreen(ts: ThemeSettings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var target by remember { mutableStateOf(0) }
    var flipped by remember { mutableStateOf(false) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Toast.makeText(ctx, if (ts.importFont(uri)) "Font loaded" else "That file isn't a usable font", Toast.LENGTH_SHORT).show()
    }
    val targets = listOf("Main" to cs.primary, "Accent" to cs.secondary, "Background" to cs.background)

    Scaffold(topBar = { SmallBar("Appearance", onBack) { TextButton({ ts.reset() }) { Text("Reset") } } }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlipCard(flipped, { flipped = !flipped }, Modifier.fillMaxWidth().height(140.dp),
                front = { CardFace(false, "FRONT") { FaceText("Hello", 28) } },
                back = { CardFace(true, "BACK") { FaceText("Bonjour", 28) } })

            Text("Colors", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                targets.forEachIndexed { i, (label, col) ->
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { target = i }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(col)
                            .border(if (target == i) 4.dp else 1.dp, if (target == i) cs.onSurface else cs.outline, CircleShape))
                        Text(label, Modifier.padding(top = 4.dp), fontWeight = if (target == i) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            ColorWheelPicker(target, targets[target].second) { c ->
                when (target) { 0 -> ts.primary = c; 1 -> ts.accent = c; else -> ts.background = c }
                ts.persist()
            }

            Text("Font", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            FONTS.forEachIndexed { i, f -> FontRow(f.name, f.family, ts.fontIndex == i) { ts.selectFont(i) } }
            ts.customFamily?.let { fam -> FontRow(ts.customName ?: "Custom font", fam, ts.fontIndex == FONT_CUSTOM) { ts.selectFont(FONT_CUSTOM) } }
            OutlinedButton({ fontPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose font from file (.ttf / .otf)")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- Study (big flip cards)
@Composable
fun StudyScreen(store: Store, sp: Speaker, deck: Deck, onBack: () -> Unit) {
    var weakOnly by remember { mutableStateOf(false) }
    var seed by remember { mutableStateOf(0) }
    val cards = remember(deck.id, weakOnly, seed) {
        store.cards.filter { it.deckId == deck.id && (!weakOnly || it.level <= 1) }.let { if (seed > 0) it.shuffled(Random(seed)) else it }
    }
    val pager = rememberPagerState { cards.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(cards) { if (cards.isNotEmpty()) pager.scrollToPage(0) }

    Scaffold(topBar = { SmallBar("Study · ${deck.name}", onBack) { IconButton({ seed++ }) { Icon(Icons.Default.Shuffle, "Shuffle") } } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(weakOnly, { weakOnly = !weakOnly }, label = { Text("Weak only") })
                Spacer(Modifier.weight(1f))
                Text("${if (cards.isEmpty()) 0 else pager.currentPage + 1} / ${cards.size}", style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { if (cards.isEmpty()) 0f else (pager.currentPage + 1f) / cards.size }, Modifier.fillMaxWidth())
            if (cards.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text("No cards to study.") }
            } else {
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 16.dp, verticalAlignment = Alignment.CenterVertically) { i ->
                    val c = cards[i]
                    var flipped by remember(c.id) { mutableStateOf(false) }
                    FlipCard(flipped, { flipped = !flipped }, Modifier.fillMaxWidth().height(340.dp).padding(horizontal = 6.dp),
                        front = {
                            CardFace(false, "FRONT") {
                                c.image?.let { Thumb(it, 120.dp); Spacer(Modifier.height(8.dp)) }
                                FaceText(c.front, 30)
                                IconButton({ sp.speak(c.front, deck.lang, 1f) }) { Icon(Icons.Default.VolumeUp, "Speak") }
                            }
                        },
                        back = {
                            CardFace(true, "BACK") {
                                FaceText(c.back, 30)
                                IconButton({ sp.speak(c.back, deck.lang, 1f) }) { Icon(Icons.Default.VolumeUp, "Speak") }
                            }
                        })
                }
                fun mark(level: (Int) -> Int) {
                    cards.getOrNull(pager.currentPage)?.let { c ->
                        val live = store.cards.firstOrNull { it.id == c.id } ?: c
                        store.upsert(live.copy(level = level(live.level)))
                        if (pager.currentPage + 1 < cards.size) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous") }
                    OutlinedButton({ mark { 0 } }) { Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Again") }
                    Button({ mark { minOf(3, it + 1) } }) { Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Got it") }
                    IconButton({ scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(cards.size - 1)) } }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next") }
                }
            }
        }
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
fun DeckScreen(store: Store, sp: Speaker, deck: Deck, onStudy: () -> Unit, onTest: () -> Unit, onBack: () -> Unit) {
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
            // study + test
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onStudy, Modifier.weight(1f), enabled = all.isNotEmpty()) { Icon(Icons.Default.Style, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Study") }
                    Button(onTest, Modifier.weight(1f), enabled = all.map { it.back }.distinct().size >= 2) { Icon(Icons.Default.School, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Test") }
                }
            }
            // read aloud
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton({ playing = !playing }) {
                                Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (playing) "Stop" else "Read aloud")
                            }
                            FilterChip(repeat, { repeat = !repeat }, label = { Text("Repeat") }, leadingIcon = { Icon(Icons.Default.Repeat, null, Modifier.size(18.dp)) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Speed ${"%.1f".format(speed)}x", Modifier.width(92.dp))
                            Slider(speed, { speed = (it * 10).roundToInt() / 10f }, valueRange = 0.5f..1.5f)
                        }
                    }
                }
            }
            items(list, key = { it.id }) { c ->
                var flipped by remember(c.id) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FlipCard(flipped, { flipped = !flipped }, Modifier.fillMaxWidth().height(170.dp),
                        front = { CardFace(false, "FRONT") { c.image?.let { Thumb(it, 48.dp) }; FaceText(c.front, 22) } },
                        back = { CardFace(true, "BACK") { FaceText(c.back, 22) } })
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
                    Spacer(Modifier.height(6.dp))
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
                Box(Modifier.fillMaxWidth().height(if (cur.image != null) 280.dp else 200.dp).padding(horizontal = 4.dp, vertical = 6.dp)) {
                    CardFace(false, "QUESTION") {
                        cur.image?.let { Thumb(it, 110.dp); Spacer(Modifier.height(6.dp)) }
                        FaceText(cur.front, 28)
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
