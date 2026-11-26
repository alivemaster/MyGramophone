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

import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.error
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.ui.placeholderScaleToFit
import org.akanework.gramophone.ui.MediaControllerViewModel
import org.akanework.gramophone.ui.components.CustomGridLayoutManager
import org.akanework.gramophone.ui.components.NowPlayingDrawable

/**
 * Rows use [R.layout.adapter_list_card_larger]. The count bar is [R.layout.general_decor].
 * Song rows show the same now-playing mark as the local song list.
 */
internal class PluginRowsAdapter(
    private val fragment: Fragment,
    private val pluginId: String,
    private val onRow: (PluginRow) -> Unit,
    private val onRowMenu: (PluginRow, View) -> Unit,
    private val onTrack: (List<PluginTrack>, Int) -> Unit,
    private val onTrackMenu: (PluginTrack, View) -> Unit,
    private val onPlayAll: (List<PluginTrack>, Boolean) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val context = fragment.requireContext()
    private var entries: List<Entry> = emptyList()
    private var tracks: List<PluginTrack> = emptyList()
    private var currentMediaItem: String? = null
    private var currentIsPlaying: Boolean? = null

    fun observePlayer() {
        val players: MediaControllerViewModel by fragment.activityViewModels()
        players.addRecreationalPlayerListener(
            fragment.viewLifecycleOwner.lifecycle,
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentMediaItem = mediaItem?.mediaId
                    notifyPlaying()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    val player = players.get() ?: return
                    currentIsPlaying = playWhenReady &&
                        player.playbackState != Player.STATE_ENDED &&
                        player.playbackState != Player.STATE_IDLE
                    notifyPlaying()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val player = players.get() ?: return
                    currentIsPlaying = player.playWhenReady &&
                        playbackState != Player.STATE_ENDED &&
                        playbackState != Player.STATE_IDLE
                    notifyPlaying()
                }
            },
        ) {
            currentMediaItem = it.currentMediaItem?.mediaId
            currentIsPlaying = it.playWhenReady &&
                it.playbackState != Player.STATE_ENDED &&
                it.playbackState != Player.STATE_IDLE
            notifyPlaying()
        }
    }

    fun submitGroups(groups: List<PluginGroup>) {
        tracks = emptyList()
        val rows = groups.sumOf { it.rows.size }
        entries = buildList {
            if (rows > 0) add(Entry.Decor(rows, false))
            for (group in groups) {
                if (group.rows.isEmpty()) continue
                val only = group.rows.singleOrNull()
                if (group.title.isNotEmpty() && (only == null || only.title != group.title)) {
                    add(Entry.Header(group.title))
                }
                group.rows.forEach { add(Entry.Row(it)) }
            }
        }
        notifyDataSetChanged()
    }

    fun submitTracks(list: List<PluginTrack>) {
        tracks = list
        entries = buildList {
            if (list.isNotEmpty()) add(Entry.Decor(list.size, true))
            list.forEachIndexed { index, track -> add(Entry.Track(track, index)) }
        }
        notifyDataSetChanged()
    }

    fun clear() {
        entries = emptyList()
        tracks = emptyList()
        notifyDataSetChanged()
    }

    fun spanSize(): Int {
        val lowWidth = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
            context.resources.configuration.screenWidthDp < 600
        return if (lowWidth) {
            CustomGridLayoutManager.LIST_PORTRAIT_SPAN_SIZE
        } else {
            CustomGridLayoutManager.LIST_LANDSCAPE_SPAN_SIZE
        }
    }

    fun fullSpan(position: Int): Boolean {
        val entry = entries.getOrNull(position) ?: return true
        return entry is Entry.Decor || entry is Entry.Header
    }

    /** Adapter position of the track that is current in the player, or -1. */
    fun playingIndex(): Int {
        val id = currentMediaItem ?: return -1
        return entries.indexOfFirst { entry ->
            entry is Entry.Track && pluginMediaId(pluginId, entry.track.id) == id
        }
    }

    override fun getItemCount(): Int = entries.size

    override fun getItemViewType(position: Int): Int = when (entries[position]) {
        is Entry.Decor -> R.layout.general_decor
        is Entry.Header -> R.layout.mygomusic_section
        is Entry.Row, is Entry.Track -> R.layout.adapter_list_card_larger
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = fragment.layoutInflater.inflate(viewType, parent, false)
        return when (viewType) {
            R.layout.general_decor -> DecorHolder(view)
            R.layout.mygomusic_section -> HeaderHolder(view)
            else -> RowHolder(view).also(::addRowRipple)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = entries[position]) {
            is Entry.Decor -> bindDecor(holder as DecorHolder, entry)
            is Entry.Header -> (holder as HeaderHolder).title.text = entry.title
            is Entry.Row -> bindRow(holder as RowHolder, entry.row)
            is Entry.Track -> bindTrack(holder as RowHolder, entry)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is RowHolder) {
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
            holder.more.setOnClickListener(null)
            (holder.nowPlaying.drawable as? NowPlayingDrawable)?.level2Done = null
            holder.nowPlaying.setImageDrawable(null)
            holder.nowPlaying.visibility = View.GONE
            holder.cover.dispose()
        }
        if (holder is DecorHolder) {
            holder.playAll.setOnClickListener(null)
            holder.shuffleAll.setOnClickListener(null)
        }
        super.onViewRecycled(holder)
    }

    private fun bindDecor(holder: DecorHolder, decor: Entry.Decor) {
        val songs = decor.tracks && decor.count > 0
        holder.playAll.visibility = if (songs) View.VISIBLE else View.GONE
        holder.shuffleAll.visibility = if (songs) View.VISIBLE else View.GONE
        holder.sort.visibility = View.GONE
        holder.create.visibility = View.GONE
        holder.jumpUp.visibility = View.GONE
        holder.jumpDown.visibility = View.GONE
        val plural = if (decor.tracks) R.plurals.songs else R.plurals.items
        holder.counter.text = context.resources.getQuantityString(plural, decor.count, decor.count)
        holder.playAll.setOnClickListener { onPlayAll(tracks, false) }
        holder.shuffleAll.setOnClickListener { onPlayAll(tracks, true) }
    }

    private fun bindRow(holder: RowHolder, row: PluginRow) {
        holder.title.text = row.title
        holder.artist.text = row.subtitle
        rowText(holder, unavailable = false)
        holder.nowPlaying.visibility = View.GONE
        loadCover(holder.cover, row.imageUrl, R.drawable.ic_default_cover_playlist)
        val menu = row.menu.any && row.open !is Open.None
        holder.more.visibility = if (menu) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onRow(row) }
        holder.itemView.setOnLongClickListener {
            if (!menu) return@setOnLongClickListener false
            onRowMenu(row, it)
            true
        }
        holder.more.setOnClickListener { if (menu) onRowMenu(row, it) }
    }

    private fun bindTrack(holder: RowHolder, entry: Entry.Track) {
        val track = entry.track
        holder.title.text = track.name
        holder.artist.text = track.artists.ifBlank { context.getString(R.string.unknown_artist) }
        rowText(holder, track.unavailable)
        loadCover(holder.cover, track.imageUrl, R.drawable.ic_default_cover)
        holder.more.visibility = View.VISIBLE
        // A track the source cannot play still takes the click, so the ripple stays.
        holder.itemView.setOnClickListener {
            if (!track.unavailable) onTrack(tracks, entry.index)
        }
        holder.itemView.setOnLongClickListener {
            onTrackMenu(track, it)
            true
        }
        holder.more.setOnClickListener { onTrackMenu(track, it) }
        val playing = currentMediaItem != null &&
            currentMediaItem == pluginMediaId(pluginId, track.id)
        if (!playing) {
            holder.nowPlaying.visibility = View.GONE
            holder.nowPlaying.setImageDrawable(null)
            return
        }
        holder.nowPlaying.setImageDrawable(
            NowPlayingDrawable(context).also { it.level = if (currentIsPlaying == true) 1 else 0 },
        )
        holder.nowPlaying.visibility = View.VISIBLE
    }

    /** Disabled content is on-surface at 38% opacity. */
    private fun rowText(holder: RowHolder, unavailable: Boolean) {
        val title = MaterialColors.getColor(
            holder.title,
            com.google.android.material.R.attr.colorOnSurface,
        )
        val artist = MaterialColors.getColor(
            holder.artist,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        if (!unavailable) {
            holder.title.setTextColor(title)
            holder.artist.setTextColor(artist)
            return
        }
        holder.title.setTextColor(Color.argb((0.38f * 255).toInt(), Color.red(title), Color.green(title), Color.blue(title)))
        holder.artist.setTextColor(Color.argb((0.38f * 255).toInt(), Color.red(artist), Color.green(artist), Color.blue(artist)))
    }

    /** The shared song row has no ripple of its own. Plugin rows keep one, including when a track cannot be played. */
    private fun addRowRipple(holder: RowHolder) {
        val value = android.util.TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) return
        if (value.resourceId == 0) return
        holder.itemView.foreground = AppCompatResources.getDrawable(context, value.resourceId)
    }

    private fun loadCover(view: ImageView, url: String?, placeholder: Int) {
        // About 40 pixels. The image loader adds this plugin's request headers.
        view.load(PluginCovers.sized(url, PluginCovers.LIST_PIXELS)) {
            placeholderScaleToFit(placeholder)
            error(placeholder)
        }
    }

    private fun notifyPlaying() {
        if (tracks.isEmpty()) return
        notifyDataSetChanged()
    }

    private sealed interface Entry {
        data class Decor(val count: Int, val tracks: Boolean) : Entry
        data class Header(val title: String) : Entry
        data class Row(val row: PluginRow) : Entry
        data class Track(val track: PluginTrack, val index: Int) : Entry
    }

    private class DecorHolder(view: View) : RecyclerView.ViewHolder(view) {
        val counter: TextView = view.findViewById(R.id.song_counter)
        val playAll: MaterialButton = view.findViewById(R.id.play_all)
        val shuffleAll: MaterialButton = view.findViewById(R.id.shuffle_all)
        val sort: MaterialButton = view.findViewById(R.id.sort)
        val create: MaterialButton = view.findViewById(R.id.create_playlist)
        val jumpUp: MaterialButton = view.findViewById(R.id.jumpUp)
        val jumpDown: MaterialButton = view.findViewById(R.id.jumpDown)
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val artist: TextView = view.findViewById(R.id.artist)
        val nowPlaying: ImageView = view.findViewById(R.id.now_playing)
        val more: MaterialButton = view.findViewById(R.id.more)
    }
}
