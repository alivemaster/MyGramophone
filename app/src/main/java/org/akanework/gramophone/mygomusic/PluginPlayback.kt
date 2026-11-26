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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.akanework.gramophone.logic.setMediaItemsSeamlessly
import org.akanework.gramophone.logic.setMediaItemsWithTitle
import org.akanework.gramophone.ui.MainActivity
import java.util.Locale

/** Album release text kept for the detail page. The source's own wording. */
internal const val PLUGIN_RELEASED = "pluginReleased"

/** Episode show id. Empty means the track is a song, so the heart uses like. */
internal const val PLUGIN_SHOW = "pluginShow"

internal const val PLAY_SCHEME = "mygramophone"

internal fun pluginMediaId(pluginId: String, trackId: String): String {
    return "plugin:${Uri.encode(pluginId)}:${Uri.encode(trackId)}"
}

/** The first raw colon separates the two encoded ids. */
internal fun parsePluginMediaId(mediaId: String): Pair<String, String>? {
    if (!mediaId.startsWith("plugin:")) return null
    val rest = mediaId.removePrefix("plugin:")
    val split = rest.indexOf(':')
    if (split <= 0 || split >= rest.lastIndex) return null
    val pluginId = Uri.decode(rest.substring(0, split))
    val trackId = Uri.decode(rest.substring(split + 1))
    if (!isPluginId(pluginId) || trackId.isEmpty()) return null
    return pluginId to trackId
}

internal fun playUri(pluginId: String, trackId: String, unavailable: Boolean = false): Uri {
    val base = "$PLAY_SCHEME://play/${Uri.encode(pluginId)}/${Uri.encode(trackId)}"
    return (if (unavailable) "$base?unavailable=1" else base).toUri()
}

/** The source marked this track unplayable. The queue still keeps it. */
internal fun playUriUnavailable(uri: Uri): Boolean = uri.getQueryParameter("unavailable") == "1"

/** Path segments are decoded, so a slash inside an id survives as one segment. */
internal fun parsePlayUri(uri: Uri): Pair<String, String>? {
    if (uri.scheme != PLAY_SCHEME || uri.host != "play") return null
    val parts = uri.pathSegments
    if (parts.size != 2) return null
    val pluginId = parts[0]
    val trackId = parts[1]
    if (!isPluginId(pluginId) || trackId.isEmpty()) return null
    return pluginId to trackId
}

internal fun pluginTitle(context: Context, pluginId: String): String {
    val store = PluginStore(context.applicationContext)
    return try {
        PluginManifest.read(store.packageDir(pluginId)).title(pluginLocale(context))
    } catch (_: Exception) {
        pluginId
    }
}

internal fun pluginLocale(context: Context): Locale {
    val config = context.resources.configuration
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        config.locales[0]
    } else {
        @Suppress("DEPRECATION")
        config.locale
    }
}

internal fun PluginTrack.toMediaItem(pluginId: String): MediaItem {
    // Both ids are percent-encoded so a ':' inside either one cannot split mediaId.
    val metadata = MediaMetadata.Builder()
        .setTitle(name)
        .setArtist(artists.ifBlank { null })
        .setAlbumTitle(album.ifBlank { null })
        .setAlbumArtist(albumArtist.ifBlank { null })
        .setArtworkUri(imageUrl?.takeIf { it.isNotBlank() }?.toUri())
        .setUserRating(HeartRating(false))
        .apply {
            if (durationMs > 0) setDurationMs(durationMs)
            val extras = Bundle()
            if (released.isNotEmpty()) extras.putString(PLUGIN_RELEASED, released)
            if (showId.isNotEmpty()) extras.putString(PLUGIN_SHOW, showId)
            if (!extras.isEmpty) setExtras(extras)
        }
        .build()
    return MediaItem.Builder()
        .setMediaId(pluginMediaId(pluginId, id))
        // localConfiguration makes the queue keep this item. The stream URL is filled in when it plays.
        .setUri(playUri(pluginId, id, unavailable))
        .setMediaMetadata(metadata)
        .build()
}

internal object PluginPlayback {
    fun play(activity: MainActivity, pluginId: String, tracks: List<PluginTrack>, index: Int, title: String) {
        if (tracks.isEmpty()) return
        val items = tracks.map { it.toMediaItem(pluginId) }
        val at = index.coerceIn(0, items.lastIndex)
        val controller = activity.getPlayer() ?: return
        val current = controller.currentMediaItem
        controller.setMediaItemsSeamlessly(items, at, title)
        controller.prepare()
        controller.play()
        if (current?.mediaId == items[at].mediaId) {
            activity.playerBottomSheet.open()
        }
    }

    fun playNext(activity: MainActivity, pluginId: String, tracks: List<PluginTrack>) {
        if (tracks.isEmpty()) return
        val controller = activity.getPlayer() ?: return
        val at = (controller.currentMediaItemIndex + 1).coerceAtLeast(0)
        controller.addMediaItems(at, tracks.map { it.toMediaItem(pluginId) })
    }

    fun playAll(activity: MainActivity, pluginId: String, tracks: List<PluginTrack>, title: String, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        val controller = activity.getPlayer() ?: return
        controller.setMediaItemsWithTitle(
            tracks.map { it.toMediaItem(pluginId) },
            title = title,
            shuffleEnabled = shuffle,
            repeatMode = Player.REPEAT_MODE_OFF,
        )
        controller.prepare()
        controller.play()
    }

    fun enqueue(activity: MainActivity, pluginId: String, tracks: List<PluginTrack>) {
        if (tracks.isEmpty()) return
        val controller = activity.getPlayer() ?: return
        controller.addMediaItems(tracks.map { it.toMediaItem(pluginId) })
    }
}
