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
import android.os.Bundle
import android.util.Log
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** True once the source's exports are known and this track can be liked. */
internal const val PLUGIN_LIKE = "pluginLike"

/** SessionResult extras when like or likeEpisode fails. */
internal const val PLUGIN_LIKE_CODE = "pluginLikeCode"
internal const val PLUGIN_LIKE_MESSAGE = "pluginLikeMessage"

/**
 * Hearts for plugin tracks. A song needs the like export. An episode needs
 * likeEpisode. isLiked fills the heart. The call itself runs in PluginRuntime.
 */
internal object PluginLikes {
    private const val TAG = "Mygomusic"
    private val exports = ConcurrentHashMap<String, Set<String>>()
    private val generation = AtomicInteger()

    fun clear() {
        generation.incrementAndGet()
        exports.clear()
    }

    /** An episode is a track whose source set showId. */
    fun episode(item: MediaItem): Boolean {
        return !item.mediaMetadata.extras?.getString(PLUGIN_SHOW).isNullOrEmpty()
    }

    fun canLike(names: Set<String>, episode: Boolean): Boolean {
        return if (episode) "likeEpisode" in names else "like" in names
    }

    suspend fun exports(context: Context, pluginId: String): Set<String> {
        exports[pluginId]?.let { return it }
        val seen = generation.get()
        val names = readExports(context, pluginId)
        if (generation.get() == seen) exports.putIfAbsent(pluginId, names)
        return exports[pluginId] ?: names
    }

    /** False when the source has no isLiked, or the call fails. The heart stays empty. */
    suspend fun isLiked(context: Context, pluginId: String, trackId: String): Boolean {
        if ("isLiked" !in exports(context, pluginId)) return false
        return try {
            likedFlag(call(context, pluginId, "isLiked", mapOf("ids" to listOf(trackId))), trackId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!missingExport(e)) Log.w(TAG, "plugin $pluginId isLiked", e)
            false
        }
    }

    suspend fun set(context: Context, pluginId: String, trackId: String, episode: Boolean, on: Boolean) {
        val export = if (episode) "likeEpisode" else "like"
        call(context, pluginId, export, mapOf("id" to trackId, "on" to on))
    }

    internal fun likedFlag(value: Any?, trackId: String): Boolean {
        val obj = when (value) {
            is JSONObject -> value
            else -> pluginJson(value) as? JSONObject
        } ?: return false
        if (!obj.has(trackId) || obj.isNull(trackId)) return false
        return obj.optBoolean(trackId, false)
    }

    private suspend fun readExports(context: Context, pluginId: String): Set<String> {
        return runtime(context, pluginId).exports()
    }

    private suspend fun call(
        context: Context,
        pluginId: String,
        export: String,
        args: Map<String, Any?>,
    ): Any? {
        return runtime(context, pluginId).call(export, args)
    }

    private fun runtime(context: Context, pluginId: String): PluginRuntime {
        val store = PluginStore(context.applicationContext)
        val dir = store.packageDir(pluginId)
        val manifest = PluginManifest.read(dir)
        return PluginRuntime(manifest, dir, store.dataDir(pluginId), pluginLocale(context))
    }
}

/** Keeps the fields already on the item. [heart] is left as it is when null. */
internal fun MediaItem.withPluginHeart(canLike: Boolean, heart: Boolean?): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle())
    extras.putBoolean(PLUGIN_LIKE, canLike)
    val meta = mediaMetadata.buildUpon().setExtras(extras)
    if (heart != null) meta.setUserRating(HeartRating(heart))
    return buildUpon().setMediaMetadata(meta.build()).build()
}
