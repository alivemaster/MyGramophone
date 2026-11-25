/*
 *     Copyright (C) 2026 alivemaster
 *
 *     MyGramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     MyGramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.mygomusic

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics.LyricLine
import org.akanework.gramophone.logic.utils.SemanticLyrics.SyncedLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics.Word
import org.akanework.gramophone.logic.utils.parseLrc
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * Lyrics for a plugin track. Timed [lines] are used when the plugin sends any.
 * Otherwise [lrc] goes through [parseLrc]. A failure leaves the song with no lyrics.
 */
internal object PluginLyrics {
    private const val TAG = "Mygomusic"
    private const val TRANSLATION_WINDOW_MS = 1500L
    private const val IMPLIED_LINE_MS = 6000L

    suspend fun load(context: Context, item: MediaItem, trim: Boolean): SemanticLyrics? {
        val (pluginId, trackId) = parsePluginMediaId(item.mediaId) ?: return null
        return try {
            val store = PluginStore(context)
            val dir = store.packageDir(pluginId)
            val manifest = PluginManifest.read(dir)
            val runtime = PluginRuntime(manifest, dir, store.dataDir(pluginId), locale(context))
            read(runtime.call("lyrics", mapOf("id" to trackId)), trim)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (missingExport(e)) return null
            Log.w(TAG, "plugin $pluginId lyrics", e)
            null
        }
    }

    internal fun read(value: Any?, trim: Boolean): SemanticLyrics? {
        val obj = asObject(value) ?: return null
        val lines = obj.optJSONArray("lines")
        if (lines != null && lines.length() > 0) return fromLines(lines)
        val lrc = obj.optString("lrc")
        if (lrc.isBlank()) return null
        return fromLrc(lrc, obj.optString("translation"), trim)
    }

    private fun fromLines(lines: JSONArray): SemanticLyrics? {
        val built = mutableListOf<Pair<LyricLine, String?>>()
        for (i in 0 until lines.length()) {
            val obj = lines.optJSONObject(i) ?: continue
            val words = words(obj.optJSONArray("words"))
            val supplied = obj.optString("text").ifBlank { words.joinToString("") { it.text } }
            val text = if (words.isEmpty()) supplied.trim() else supplied
            if (text.isBlank()) continue
            val start = millis(obj, "start").coerceAtLeast(0).toULong()
            val end = millis(obj, "end")
            val explicit = end > 0 && end.toULong() > start
            val mapped = wordRanges(text, words)
            built += LyricLine(
                text,
                start,
                if (explicit) end.toULong() else 0u,
                !explicit,
                mapped,
                null,
                false,
            ) to obj.optString("translation").trim().ifBlank { null }
        }
        if (built.isEmpty()) return null
        built.sortBy { it.first.start }
        val out = mutableListOf<LyricLine>()
        for (i in built.indices) {
            val line = built[i].first
            if (line.endIsImplicit) {
                val next = built.getOrNull(i + 1)?.first?.start
                line.end = if (next != null && next > line.start) next - 1u else line.start + IMPLIED_LINE_MS.toULong()
                line.endIsImplicit = false
            }
            out += line
            val translation = built[i].second ?: continue
            out += LyricLine(translation, line.start, line.end, false, null, null, true)
        }
        return SyncedLyrics(out)
    }

    private fun fromLrc(lrc: String, translation: String, trim: Boolean): SemanticLyrics? {
        val main = parseLrc(lrc, trim, true) ?: return null
        if (translation.isBlank() || main !is SyncedLyrics) return main
        val extra = parseLrc(translation, trim, true) as? SyncedLyrics ?: return main
        val out = main.text.toMutableList()
        for (line in extra.text) {
            if (line.text.isBlank() || line.isTranslated) continue
            var best = -1
            var bestGap = TRANSLATION_WINDOW_MS
            for (i in out.indices) {
                if (out[i].isTranslated) continue
                val gap = abs(out[i].start.toLong() - line.start.toLong())
                if (gap < bestGap) {
                    best = i
                    bestGap = gap
                }
            }
            if (best < 0) continue
            val host = out[best]
            if (best + 1 < out.size && out[best + 1].isTranslated && out[best + 1].start == host.start) {
                continue
            }
            out.add(
                best + 1,
                LyricLine(line.text, host.start, host.end, false, null, null, true),
            )
        }
        return SyncedLyrics(out)
    }

    private data class RawWord(val start: Long, val end: Long, val text: String)

    private fun words(array: JSONArray?): List<RawWord> {
        if (array == null) return emptyList()
        val out = mutableListOf<RawWord>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val text = obj.optString("text")
            if (text.isEmpty()) continue
            out += RawWord(millis(obj, "start"), millis(obj, "end"), text)
        }
        return out
    }

    /** Ranges point into [text]. A word that is not in the line drops the timings. */
    private fun wordRanges(text: String, words: List<RawWord>): MutableList<Word>? {
        if (words.isEmpty()) return null
        var cursor = 0
        val out = mutableListOf<Word>()
        for (word in words) {
            val at = text.indexOf(word.text, cursor)
            if (at < 0) return null
            val start = word.start.coerceAtLeast(0).toULong()
            val end = word.end.coerceAtLeast(0).toULong()
            out += Word(
                start,
                if (end > start) end else start,
                at..(at + word.text.length - 1),
                rtl(word.text),
            )
            cursor = at + word.text.length
        }
        return out
    }

    private fun rtl(text: String): Boolean {
        for (ch in text) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
        }
        return false
    }

    private fun millis(obj: JSONObject, name: String): Long {
        if (!obj.has(name) || obj.isNull(name)) return 0
        return when (val value = obj.get(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0
            else -> 0
        }
    }

    private fun asObject(value: Any?): JSONObject? {
        return when (val wrapped = pluginJson(value)) {
            is JSONObject -> wrapped
            is String -> if (wrapped.startsWith("{")) {
                runCatching { JSONObject(wrapped) }.getOrNull()
            } else {
                null
            }
            else -> null
        }
    }

    private fun locale(context: Context): Locale {
        val config = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0]
        } else {
            @Suppress("DEPRECATION")
            config.locale
        }
    }
}
