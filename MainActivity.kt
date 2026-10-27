package com.example.gamelauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { LauncherApp() }
    }
}

enum class Console(val title: String, val bg: Brush, val card: Color, val accent: Color,
                   val text: Color, val onAccent: Color, val shape: Shape) {
    Xbox("Xbox", Brush.verticalGradient(listOf(Color(0xFF0B1F0B), Color(0xFF181818))),
        Color(0xFF2B2B2B), Color(0xFF9BF00B), Color.White, Color.Black, RoundedCornerShape(4.dp)),
    PlayStation("PlayStation", Brush.linearGradient(listOf(Color(0xFF00123A), Color(0xFF0057C2))),
        Color(0x33FFFFFF), Color.White, Color.White, Color(0xFF00123A), RoundedCornerShape(26.dp)),
    Switch("Nintendo Switch", Brush.verticalGradient(listOf(Color(0xFFEDEDED), Color(0xFFDADADA))),
        Color.White, Color(0xFFE60012), Color(0xFF2D2D2D), Color.White, RoundedCornerShape(14.dp))
}

data class App(val label: String, val pkg: String, val icon: ImageBitmap)
// size: 0 small, 1 medium, 2 large, 3 wide banner
data class Cfg(val name: String? = null, val size: Int = 1, val hidden: Boolean = false, val icon: String? = null)
data class Profile(val name: String, val style: String = "Xbox", val bgColor: Long? = null, val bgImage: String? = null,
                   val showHidden: Boolean = false, val apps: Map<String, Cfg> = emptyMap())

class Store(val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("launcher", 0)
    var profiles by mutableStateOf(load())
    var cur by mutableIntStateOf(prefs.getInt("cur", 0).coerceIn(0, profiles.size - 1))
    val p get() = profiles[cur]
    fun cfg(pkg: String) = p.apps[pkg] ?: Cfg()
    fun update(f: (Profile) -> Profile) { profiles = profiles.mapIndexed { i, x -> if (i == cur) f(x) else x }; save() }
    fun setCfg(pkg: String, f: (Cfg) -> Cfg) = update { it.copy(apps = it.apps + (pkg to f(cfg(pkg)))) }
    fun add() { profiles = profiles + Profile("Profile ${profiles.size + 1}", p.style); cur = profiles.size - 1; save() }
    fun remove() { if (profiles.size > 1) { profiles = profiles.filterIndexed { i, _ -> i != cur }; cur = 0; save() } }
    fun select(i: Int) { cur = i; save() }
    private fun load(): List<Profile> = runCatching {
        val a = JSONArray(prefs.getString("d", null)!!)
        List(a.length()) { i ->
            val o = a.getJSONObject(i); val ap = o.getJSONObject("a")
            Profile(o.getString("n"), o.getString("s"), if (o.has("c")) o.getLong("c") else null,
                o.optString("i").ifEmpty { null }, o.optBoolean("h"),
                ap.keys().asSequence().associateWith { k ->
                    val x = ap.getJSONObject(k)
                    Cfg(x.optString("n").ifEmpty { null }, x.optInt("z", 1), x.optBoolean("h"), x.optString("i").ifEmpty { null })
                })
        }
    }.getOrDefault(listOf(Profile("Default")))
    private fun save() {
        val a = JSONArray()
        profiles.forEach { p ->
            val ap = JSONObject()
            p.apps.forEach { (k, v) -> ap.put(k, JSONObject().put("n", v.name).put("z", v.size).put("h", v.hidden).put("i", v.icon)) }
            a.put(JSONObject().put("n", p.name).put("s", p.style).put("c", p.bgColor).put("i", p.bgImage).put("h", p.showHidden).put("a", ap))
        }
        prefs.edit().putString("d", a.toString()).putInt("cur", cur).apply()
    }
}

fun loadApps(ctx: Context): List<App> {
    val pm = ctx.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm).toBitmap(144, 144).asImageBitmap()) }
        .sortedBy { it.label.lowercase() }
}

fun importImage(ctx: Context, uri: Uri, max: Int): String? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > max) s *= 2
    val b = ctx.contentResolver.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
    }!!
    val n = "img_${System.nanoTime()}.jpg"
    File(ctx.filesDir, n).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }; n
}.getOrNull()

@Composable
fun fileImage(name: String?): ImageBitmap? {
    val ctx = LocalContext.current
    return produceState<ImageBitmap?>(null, name) {
        value = name?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(File(ctx.filesDir, it).path)?.asImageBitmap() } }
    }.value
}

fun Modifier.padA(f: () -> Unit) = onKeyEvent {
    if (it.type == KeyEventType.KeyUp && it.key == Key.ButtonA) { f(); true } else false
}

@Composable
fun Tile(c: Console, app: App, g: Cfg, onOpen: () -> Unit, onEdit: () -> Unit, mod: Modifier = Modifier) {
    val h = listOf(84, 112, 150, 112)[g.size].dp
    val w = if (g.size == 3) 200.dp else h
    val custom = fileImage(g.icon)
    var f by remember { mutableStateOf(false) }
    var lp by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.08f else 1f, label = "s")
    Column(mod.width(w).alpha(if (g.hidden) .4f else 1f).scale(s).onKeyEvent {
        val k = it.key; val up = it.type == KeyEventType.KeyUp
        when {
            // hold OK on a remote = edit this app
            it.type == KeyEventType.KeyDown && it.nativeKeyEvent.isLongPress &&
                (k == Key.Enter || k == Key.DirectionCenter || k == Key.ButtonA) -> { lp = true; onEdit(); true }
            up && (k == Key.Menu || k == Key.ButtonY || k == Key.ButtonX) -> { onEdit(); true }
            up && k == Key.ButtonA -> { onOpen(); true }
            else -> false
        }
    }.onFocusChanged { f = it.isFocused }.combinedClickable(onLongClick = onEdit) { if (lp) lp = false else onOpen() },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(w, h).clip(c.shape).background(c.card).border(4.dp, if (f) c.accent else Color.Transparent, c.shape)) {
            if (custom != null) Image(custom, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Image(app.icon, null, Modifier.fillMaxSize().padding(h / 5))
        }
        Text(g.name ?: app.label, color = c.text, fontSize = 13.sp, maxLines = 1,
            modifier = Modifier.padding(top = 8.dp).alpha(if (c != Console.PlayStation || f) 1f else 0f))
    }
}

@Composable
fun Pill(c: Console, text: String, selected: Boolean = false, mod: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(mod.padA(onClick).onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).clip(c.shape)
        .background(if (selected) c.accent else c.card).border(3.dp, if (f) c.text else Color.Transparent, c.shape)
        .padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) c.onAccent else c.text, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Swatch(c: Console, col: Color, sel: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(Modifier.size(44.dp).padA(onClick).onFocusChanged { f = it.isFocused }.clickable(onClick = onClick)
        .clip(CircleShape).background(col).border(3.dp, if (f || sel) c.text else Color.Transparent, CircleShape))
}

@Composable
fun Field(c: Console, v: String, on: (String) -> Unit, mod: Modifier = Modifier) {
    var f by remember { mutableStateOf(false) }
    BasicTextField(v, on, mod.onFocusChanged { f = it.isFocused }.clip(c.shape).background(c.card)
        .border(3.dp, if (f) c.text else Color.Transparent, c.shape).padding(16.dp),
        singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 16.sp), cursorBrush = SolidColor(c.text))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Section(c: Console, t: String, content: @Composable () -> Unit) {
    Text(t, color = c.text.copy(alpha = .6f), fontSize = 13.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
fun Page(title: String, c: Console, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(title, color = c.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold); content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Home(c: Console, st: Store, apps: List<App>, open: (App) -> Unit, edit: (App) -> Unit, settings: () -> Unit) {
    val first = remember { FocusRequester() }
    val shown = apps.filter { st.p.showHidden || !st.cfg(it.pkg).hidden }
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) runCatching { first.requestFocus() } }
    val centered = c == Console.PlayStation
    // Wrapping grid: every app is visible and one tap away, no horizontal swiping.
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(if (c == Console.Switch) "Home" else st.p.name, color = c.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Pill(c, "Settings", onClick = settings)
        }
        Spacer(Modifier.height(20.dp))
        FlowRow(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (centered) 28.dp else 18.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            shown.forEachIndexed { i, a ->
                Tile(c, a, st.cfg(a.pkg), { open(a) }, { edit(a) }, if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
        if (c == Console.Switch) Text("A Open    Hold A / Y Edit    + Settings    L / R Style", color = c.text.copy(alpha = .6f), fontSize = 13.sp)
    }
}

@Composable
fun EditScreen(c: Console, app: App, g: Cfg, set: ((Cfg) -> Cfg) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u?.let { importImage(ctx, it, 512) }?.let { n -> set { it.copy(icon = n) } }
    }
    Page(app.label, c) {
        Section(c, "Name") { Field(c, g.name ?: "", { v -> set { it.copy(name = v.ifBlank { null }) } }, Modifier.fillMaxWidth()) }
        Section(c, "Size") {
            listOf("Small", "Medium", "Large", "Wide").forEachIndexed { i, t ->
                Pill(c, t, g.size == i, if (g.size == i) Modifier.focusRequester(fr) else Modifier) { set { it.copy(size = i) } }
            }
        }
        Section(c, "Icon / banner") {
            Pill(c, "Choose image") { pick.launch("image/*") }
            Pill(c, "Reset") { set { it.copy(icon = null) } }
        }
        Section(c, "Visibility") { Pill(c, if (g.hidden) "Hidden" else "Visible") { set { it.copy(hidden = !g.hidden) } } }
        Section(c, "") { Pill(c, "Done", onClick = back) }
    }
}

@Composable
fun SettingsScreen(c: Console, st: Store, close: () -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u?.let { importImage(ctx, it, 1600) }?.let { n -> st.update { it.copy(bgImage = n) } }
    }
    val colors = listOf(0xFF000000, 0xFF111827, 0xFF0B3D2E, 0xFF3B0A45, 0xFF7A1F1F, 0xFFF2F2F2)
    Page("Settings", c) {
        Section(c, "Profile") {
            st.profiles.forEachIndexed { i, p -> Pill(c, p.name, i == st.cur, if (i == st.cur) Modifier.focusRequester(fr) else Modifier) { st.select(i) } }
            Pill(c, "+ New") { st.add() }
        }
        Field(c, st.p.name, { v -> st.update { it.copy(name = v) } }, Modifier.fillMaxWidth().padding(top = 10.dp))
        if (st.profiles.size > 1) Pill(c, "Delete this profile", mod = Modifier.padding(top = 10.dp)) { st.remove() }
        Section(c, "Style") { Console.entries.forEach { s -> Pill(c, s.title, s == c) { st.update { it.copy(style = s.name) } } } }
        Section(c, "Background") {
            Pill(c, "Default", st.p.bgColor == null && st.p.bgImage == null) { st.update { it.copy(bgColor = null, bgImage = null) } }
            colors.forEach { col -> Swatch(c, Color(col), st.p.bgColor == col && st.p.bgImage == null) { st.update { it.copy(bgColor = col, bgImage = null) } } }
            Pill(c, "Image…", st.p.bgImage != null) { pick.launch("image/*") }
        }
        Section(c, "Library") {
            Pill(c, if (st.p.showHidden) "Hidden apps: shown" else "Hidden apps: not shown") { st.update { it.copy(showHidden = !it.showHidden) } }
            Pill(c, "Set as default launcher") { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        }
        Section(c, "") { Pill(c, "Back", onClick = close) }
    }
}

@Composable
fun LauncherApp() {
    val ctx = LocalContext.current
    val st = remember { Store(ctx) }
    val c = runCatching { Console.valueOf(st.p.style) }.getOrDefault(Console.Xbox)
    var screen by remember { mutableStateOf("home") }
    var editApp by remember { mutableStateOf<App?>(null) }
    var apps by remember { mutableStateOf(listOf<App>()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    BackHandler(screen != "home") { screen = "home" }
    val bgImg = fileImage(st.p.bgImage)
    fun style(n: Console) = st.update { it.copy(style = n.name) }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        st.p.bgColor?.let { Box(Modifier.fillMaxSize().background(Color(it))) }
        bgImg?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (st.p.bgColor != null || bgImg != null)
            Box(Modifier.fillMaxSize().background(if (c == Console.Switch) Color.White.copy(alpha = .55f) else Color.Black.copy(alpha = .4f)))
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyUp) false else when (e.key) {
                Key.ButtonStart -> if (screen == "home") { screen = "settings"; true } else false
                Key.ButtonB, Key.Escape -> if (screen != "home") { screen = "home"; true } else false
                Key.ButtonR1 -> { style(Console.entries[(c.ordinal + 1) % 3]); true }
                Key.ButtonL1 -> { style(Console.entries[(c.ordinal + 2) % 3]); true }
                else -> false
            }
        }.safeDrawingPadding()) {
            val ea = editApp
            when {
                screen == "settings" -> SettingsScreen(c, st) { screen = "home" }
                screen == "edit" && ea != null -> EditScreen(c, ea, st.cfg(ea.pkg), { f -> st.setCfg(ea.pkg, f) }) { screen = "home" }
                else -> Home(c, st, apps, { a -> ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let(ctx::startActivity) },
                    { a -> editApp = a; screen = "edit" }, { screen = "settings" })
            }
        }
    }
}
