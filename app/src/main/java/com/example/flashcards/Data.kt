package com.example.flashcards

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

data class Deck(val id: String = UUID.randomUUID().toString(), val name: String, val lang: String = "en-US")
data class Flash(
    val id: String = UUID.randomUUID().toString(), val deckId: String,
    val front: String, val back: String, val level: Int = 0,
    val image: String? = null, val audio: String? = null
)

class Store(private val ctx: Context) {
    private val file = File(ctx.filesDir, "data.json")
    val decks = mutableStateListOf<Deck>()
    val cards = mutableStateListOf<Flash>()

    init {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                val d = o.getJSONArray("decks")
                for (i in 0 until d.length()) d.getJSONObject(i).let { decks.add(Deck(it.getString("id"), it.getString("name"), it.optString("lang", "en-US"))) }
                val c = o.getJSONArray("cards")
                for (i in 0 until c.length()) c.getJSONObject(i).let {
                    cards.add(Flash(it.getString("id"), it.getString("deckId"), it.getString("front"), it.getString("back"),
                        it.optInt("level"), it.optString("image").ifEmpty { null }, it.optString("audio").ifEmpty { null }))
                }
            }
        } catch (_: Exception) {}
        if (decks.isEmpty()) { decks.add(Deck(name = "My first deck")); save() }
    }

    fun save() {
        val o = JSONObject()
        o.put("decks", JSONArray().also { a -> decks.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("lang", it.lang)) } })
        o.put("cards", JSONArray().also { a ->
            cards.forEach {
                a.put(JSONObject().put("id", it.id).put("deckId", it.deckId).put("front", it.front).put("back", it.back)
                    .put("level", it.level).put("image", it.image ?: "").put("audio", it.audio ?: ""))
            }
        })
        file.writeText(o.toString())
    }

    fun upsert(c: Flash) { val i = cards.indexOfFirst { it.id == c.id }; if (i >= 0) cards[i] = c else cards.add(c); save() }
    fun delete(c: Flash) { cards.removeAll { it.id == c.id }; save() }
    fun copy(c: Flash) { upsert(c.copy(id = UUID.randomUUID().toString())) }
    fun addDeck(name: String, lang: String) { decks.add(Deck(name = name, lang = lang)); save() }
    fun updateDeck(d: Deck) { val i = decks.indexOfFirst { it.id == d.id }; if (i >= 0) decks[i] = d; save() }
    fun deleteDeck(d: Deck) { cards.removeAll { it.deckId == d.id }; decks.removeAll { it.id == d.id }; save() }

    /** Swap a card with its neighbour inside the same deck (manual ordering). */
    fun move(c: Flash, dir: Int) {
        val i = cards.indexOfFirst { it.id == c.id }
        var j = i + dir
        while (j in cards.indices && cards[j].deckId != c.deckId) j += dir
        if (i < 0 || j !in cards.indices) return
        val t = cards[i]; cards[i] = cards[j]; cards[j] = t; save()
    }

    fun newMedia(ext: String): File = File(File(ctx.filesDir, "media").apply { mkdirs() }, "${UUID.randomUUID()}.$ext")
    fun copyMedia(uri: Uri, ext: String): String {
        val f = newMedia(ext)
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f.absolutePath
    }

    private fun esc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun csvFile(deckId: String, template: Boolean): File {
        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, if (template) "flashcards_template.csv" else "flashcards.csv")
        val sb = StringBuilder("front,back,level\n")
        if (template) sb.append("hello,bonjour,0\nthank you,merci,0\n")
        else cards.filter { it.deckId == deckId }.forEach { sb.append("${esc(it.front)},${esc(it.back)},${it.level}\n") }
        f.writeText("\uFEFF$sb")
        return f
    }

    fun importCsv(deckId: String, text: String): Int {
        var n = 0
        parseCsv(text.removePrefix("\uFEFF")).forEachIndexed { i, r ->
            if (r.size >= 2 && r[0].isNotBlank() && !(i == 0 && r[0].trim().equals("front", true))) {
                cards.add(Flash(deckId = deckId, front = r[0].trim(), back = r[1].trim(), level = r.getOrNull(2)?.trim()?.toIntOrNull()?.coerceIn(0, 3) ?: 0))
                n++
            }
        }
        save(); return n
    }

    private fun parseCsv(t: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>()
        val sb = StringBuilder(); var q = false; var i = 0
        while (i < t.length) {
            val c = t[i]
            if (q) {
                if (c == '"') { if (i + 1 < t.length && t[i + 1] == '"') { sb.append('"'); i++ } else q = false } else sb.append(c)
            } else when (c) {
                '"' -> q = true
                ',' -> { row.add(sb.toString()); sb.clear() }
                '\n' -> { row.add(sb.toString()); sb.clear(); rows.add(row); row = mutableListOf() }
                '\r' -> {}
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows
    }
}

class Speaker(ctx: Context) {
    private var ready = false
    private var done: (() -> Unit)? = null
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(ctx) { s ->
            ready = s == TextToSpeech.SUCCESS
            if (ready) tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done?.invoke() }
                override fun onError(id: String?) { done?.invoke() }
            })
        }
    }

    fun speak(text: String, lang: String, rate: Float) {
        if (!ready || text.isBlank()) return
        tts.language = Locale.forLanguageTag(lang)
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    suspend fun say(text: String, lang: String, rate: Float) = suspendCancellableCoroutine<Unit> { c ->
        if (!ready || text.isBlank()) { c.resume(Unit); return@suspendCancellableCoroutine }
        done = { done = null; if (c.isActive) c.resume(Unit) }
        c.invokeOnCancellation { tts.stop() }
        speak(text, lang, rate)
    }

    fun stop() { tts.stop() }
    fun shutdown() { tts.stop(); tts.shutdown() }
}
