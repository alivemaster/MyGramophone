/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.getBitrate
import org.akanework.gramophone.logic.getFile
import org.akanework.gramophone.logic.hasImprovedMediaStore
import org.akanework.gramophone.logic.toLocaleString
import org.akanework.gramophone.logic.toMediaStoreId
import org.akanework.gramophone.logic.ui.placeholderScaleToFit
import org.akanework.gramophone.logic.utils.CalculationUtils.convertDurationToTimeStamp
import org.akanework.gramophone.mygomusic.PLUGIN_RELEASED
import org.akanework.gramophone.mygomusic.parsePluginMediaId
import org.akanework.gramophone.mygomusic.pluginTitle

class DetailDialogFragment : BaseFragment(false) {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_info_song, container, false)
        rootView.findViewById<AppBarLayout>(R.id.appbarlayout).enableEdgeToEdgePaddingListener()
        rootView.findViewById<View>(R.id.scrollView).enableEdgeToEdgePaddingListener()
        rootView.findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        val rawId = requireArguments().getString("Id")
        if (parsePluginMediaId(rawId.orEmpty()) != null) {
            if (!showPlugin(rootView, rawId!!)) {
                parentFragmentManager.popBackStack()
                return null
            }
            return rootView
        }
        val id = rawId?.toMediaStoreId()
        val mediaItem = runBlocking { mainActivity.reader.idMapFlow.map { it[id] }.first() }
        if (mediaItem == null) {
            parentFragmentManager.popBackStack()
            return null
        }
        val mediaMetadata = mediaItem.mediaMetadata
        val albumCoverImageView = rootView.findViewById<ImageView>(R.id.album_cover)
        val titleTextView = rootView.findViewById<TextView>(R.id.title)
        val artistTextView = rootView.findViewById<TextView>(R.id.artist)
        val albumArtistTextView = rootView.findViewById<TextView>(R.id.album_artist)
        val discNumberTextView = rootView.findViewById<TextView>(R.id.disc_number)
        val trackNumberTextView = rootView.findViewById<TextView>(R.id.track_num)
        val genreTextView = rootView.findViewById<TextView>(R.id.genre)
        val genreBox = rootView.findViewById<View>(R.id.genre_box)
        val yearTextView = rootView.findViewById<TextView>(R.id.date)
        val albumTextView = rootView.findViewById<TextView>(R.id.album)
        val durationTextView = rootView.findViewById<TextView>(R.id.duration)
        val mimeTypeTextView = rootView.findViewById<TextView>(R.id.mime)
        val pathTextView = rootView.findViewById<TextView>(R.id.path)
        val bitRateTextView = rootView.findViewById<TextView>(R.id.bit_rate)
        albumCoverImageView.load(mediaMetadata.artworkUri) {
            placeholderScaleToFit(R.drawable.ic_default_cover)
            crossfade(true)
            error(R.drawable.ic_default_cover)
        }
        titleTextView.text = mediaMetadata.title
        artistTextView.text = mediaMetadata.artist
        albumTextView.text = mediaMetadata.albumTitle
        if (mediaMetadata.albumArtist != null) {
            albumArtistTextView.text = mediaMetadata.albumArtist
        }
        discNumberTextView.text = mediaMetadata.discNumber?.toLocaleString()
        trackNumberTextView.text = mediaMetadata.trackNumber?.toLocaleString()
        if (hasImprovedMediaStore()) {
            if (mediaMetadata.genre != null) {
                genreTextView.text = mediaMetadata.genre
            }
        } else genreBox.visibility = View.GONE
        if (mediaMetadata.releaseYear != null || mediaMetadata.recordingYear != null) {
            yearTextView.text =
                (mediaMetadata.releaseYear ?: mediaMetadata.recordingYear)?.toLocaleString()
        }
        mediaMetadata.durationMs?.let { durationTextView.text = convertDurationToTimeStamp(it) }
        mimeTypeTextView.text = mediaItem.localConfiguration?.mimeType ?: "(null)"
        pathTextView.text = mediaItem.getFile()?.path
            ?: mediaItem.requestMetadata.mediaUri?.toString() ?: "(null)"
        val context = requireContext().applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val bitrate = mediaItem.getBitrate(context) // disk access
            withContext(Dispatchers.Main) {
                bitRateTextView.text = if (bitrate != null) {
                    getString(R.string.bitrate_format, bitrate / 1000)
                } else {
                    getString(R.string.bitrate_unknown)
                }
            }
        }
        return rootView
    }

    /**
     * Plugin tracks are not in the local library. Show the fields the source
     * put on the queue item, and the plugin name. File rows stay hidden.
     */
    private fun showPlugin(rootView: View, mediaId: String): Boolean {
        val player = mainActivity.getPlayer() ?: return false
        val item = queuedItem(player, mediaId) ?: return false
        val metadata = item.mediaMetadata
        rootView.findViewById<ImageView>(R.id.album_cover).load(metadata.artworkUri) {
            placeholderScaleToFit(R.drawable.ic_default_cover)
            crossfade(true)
            error(R.drawable.ic_default_cover)
        }
        rootView.findViewById<TextView>(R.id.title).text =
            metadata.title?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_title)
        textRow(rootView, R.id.artist_box, R.id.artist, metadata.artist)
        textRow(rootView, R.id.album_box, R.id.album, metadata.albumTitle)
        textRow(rootView, R.id.album_artist_box, R.id.album_artist, metadata.albumArtist)
        textRow(rootView, R.id.year_box, R.id.date, metadata.extras?.getString(PLUGIN_RELEASED))
        val duration = playedDuration(player, mediaId) ?: metadata.durationMs?.takeIf { it > 0 }
        textRow(
            rootView,
            R.id.duration_box,
            R.id.duration,
            duration?.let { convertDurationToTimeStamp(it) },
        )
        for (box in intArrayOf(
            R.id.disc_box,
            R.id.track_box,
            R.id.genre_box,
            R.id.bitrate_box,
            R.id.mime_box,
            R.id.path_box,
        )) {
            rootView.findViewById<View>(box).visibility = View.GONE
        }
        val source = rootView.findViewById<TextView>(R.id.source)
        rootView.findViewById<View>(R.id.source_divider).visibility = View.VISIBLE
        rootView.findViewById<View>(R.id.source_box).visibility = View.VISIBLE
        val pluginId = parsePluginMediaId(mediaId)?.first ?: return true
        val appContext = requireContext().applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val name = pluginTitle(appContext, pluginId)
            withContext(Dispatchers.Main) {
                if (view != null) source.text = name
            }
        }
        return true
    }

    private fun textRow(root: View, boxId: Int, valueId: Int, value: CharSequence?) {
        val box = root.findViewById<View>(boxId)
        if (value.isNullOrBlank()) {
            box.visibility = View.GONE
        } else {
            box.visibility = View.VISIBLE
            root.findViewById<TextView>(valueId).text = value
        }
    }

    private fun queuedItem(player: Player, mediaId: String): MediaItem? {
        if (player.currentMediaItem?.mediaId == mediaId) return player.currentMediaItem
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            if (item.mediaId == mediaId) return item
        }
        return null
    }

    private fun playedDuration(player: Player, mediaId: String): Long? {
        if (player.currentMediaItem?.mediaId != mediaId) return null
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration < 0) return null
        return duration
    }
}