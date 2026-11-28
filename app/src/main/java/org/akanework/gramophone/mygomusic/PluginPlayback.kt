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
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.akanework.gramophone.logic.setMediaItemsSeamlessly
import org.akanework.gramophone.logic.setMediaItemsWithTitle
import org.akanework.gramophone.ui.MainActivity
import java.util.Locale

/** Album release text kept for the detail page. The source's own wording. */
internal const val PLUGIN_RELEASED = "pluginReleased"

/** Episode show id. Empty means the track is a song, so the heart uses like. */
internal const val PLUGIN_SHOW = "pluginShow"

/** The one artist named on the player. The player line has room for one person. */
internal const val PLUGIN_ARTIST_ID = "pluginArtistId"
internal const val PLUGIN_ARTIST_NAME = "pluginArtistName"

/** Album opened from the song title on the player. */
internal const val PLUGIN_ALBUM_ID = "pluginAlbumId"

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

/**
 * A plugin track skips when playback throws, including an OkHttp timeout.
 * signIn and setup stay: the listener has to act, and the next track fails
 * the same way. A local file stays on its error.
 */
internal fun skipFailedPluginTrack(uri: Uri?, error: Throwable?): Boolean {
    if (uri?.scheme != PLAY_SCHEME) return false
    return !pluginAsksListener(error)
}

/**
 * Words for the toast. [message] is what the plugin or the network said.
 * [code] is set when that text is only a placeholder, so the app can use its own sentence.
 */
internal class PluginFailureText(val message: String?, val code: String?)

internal fun pluginFailureText(error: Throwable?): PluginFailureText {
    var current = error
    val seen = HashSet<Throwable>()
    var call: PluginCallException? = null
    var thrown: String? = null
    while (current != null && seen.add(current)) {
        if (current is PluginCallException) call = current
        val text = current.message?.trim()?.takeIf { it.isNotEmpty() && !placeholder(it) }
        if (current !is PluginCallException && text != null) thrown = text
        current = current.cause
    }
    val reported = call
    val said = reported?.message?.trim()?.takeIf {
        it.isNotEmpty() && !placeholder(it) && it != reported.code
    }
    if (said != null) return PluginFailureText(said, reported?.code)
    if (reported != null) return PluginFailureText(null, reported.code.ifEmpty { null })
    return PluginFailureText(thrown, null)
}

private fun placeholder(text: String): Boolean = text == "stream" || text == "Source error" || text == "unavailable"

/** Plugin audio is not loaded again after it throws. The track is skipped from that error. */
internal class PluginTrackLoadPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (loadErrorInfo.loadEventInfo.dataSpec.uri.scheme == PLAY_SCHEME) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

private fun pluginAsksListener(error: Throwable?): Boolean {
    var current = error
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is PluginCallException &&
            (current.code == "signIn" || current.code == "setup")
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

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

/** First credited artist who has a page. The player line shows this one name. */
internal fun PluginTrack.playerArtist(): PluginPerson? {
    return credits.firstOrNull { it.id.isNotEmpty() && it.name.isNotEmpty() }
}

internal fun PluginTrack.toMediaItem(pluginId: String): MediaItem {
    val shown = playerArtist()
    // Both ids are percent-encoded so a ':' inside either one cannot split mediaId.
    val metadata = MediaMetadata.Builder()
        .setTitle(name)
        .setArtist(shown?.name ?: artists.ifBlank { null })
        .setAlbumTitle(album.ifBlank { null })
        .setAlbumArtist(albumArtist.ifBlank { null })
        .setArtworkUri(imageUrl?.takeIf { it.isNotBlank() }?.toUri())
        .setUserRating(HeartRating(false))
        .apply {
            if (durationMs > 0) setDurationMs(durationMs)
            val extras = Bundle()
            if (released.isNotEmpty()) extras.putString(PLUGIN_RELEASED, released)
            if (showId.isNotEmpty()) extras.putString(PLUGIN_SHOW, showId)
            if (shown != null) {
                extras.putString(PLUGIN_ARTIST_ID, shown.id)
                extras.putString(PLUGIN_ARTIST_NAME, shown.name)
            }
            if (albumId.isNotEmpty()) extras.putString(PLUGIN_ALBUM_ID, albumId)
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
