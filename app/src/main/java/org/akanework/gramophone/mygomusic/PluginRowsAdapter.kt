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
import androidx.core.view.updateLayoutParams
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.TooltipCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.error
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.ui.MyRecyclerView
import org.akanework.gramophone.logic.ui.placeholderScaleToFit
import org.akanework.gramophone.ui.MediaControllerViewModel
import org.akanework.gramophone.ui.components.CustomGridLayoutManager
import org.akanework.gramophone.ui.components.NowPlayingDrawable

/**
 * Rows use [R.layout.adapter_list_card_larger]. Album cards on an artist page use
 * [R.layout.adapter_grid_card]. The count bar is [R.layout.general_decor].
 * Song rows show the same now-playing mark as the local song list.
 */
internal class PluginRowsAdapter(
    private val fragment: Fragment,
    private val pluginId: String,
    private val onRow: (PluginRow) -> Unit,
    private val onRowMenu: (PluginRow, View) -> Unit,
    private val onTrack: (List<PluginTrack>, Int, String) -> Unit,
    private val onTrackMenu: (PluginTrack, View) -> Unit,
    private val onPlayAll: (List<PluginTrack>, Boolean) -> Unit,
    private val onCreate: () -> Unit,
) : MyRecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val context = fragment.requireContext()
    private var entries: List<Entry> = emptyList()
    private var tracks: List<PluginTrack> = emptyList()
    private var cards = false
    private var gridHeight = 0
    private var coverPixels = PluginCovers.UNSPECIFIED_PIXELS
    private var listView: MyRecyclerView? = null
    private var currentMediaItem: String? = null
    private var currentIsPlaying: Boolean? = null

    /** Long-press reorders tracks. Only an own, unfixed playlist that exports `movePlaylistTrack`. */
    var reorderEnabled: Boolean = false

    override fun onAttachedToRecyclerView(recyclerView: MyRecyclerView) {
        listView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: MyRecyclerView) {
        if (listView === recyclerView) listView = null
    }

    override fun onWidthChanged(width: Int) {
        val view = listView ?: return
        val cardPadding = context.resources.getDimensionPixelSize(R.dimen.grid_card_side_padding)
        val marginTop = context.resources.getDimensionPixelSize(R.dimen.grid_card_margin_top)
        val marginLabel = context.resources.getDimensionPixelSize(R.dimen.grid_card_margin_label)
        val paddingBottom = context.resources.getDimensionPixelSize(R.dimen.grid_card_padding_bottom)
        val labelHeight = context.resources.getDimensionPixelSize(R.dimen.grid_card_label_height)
        val columns = CustomGridLayoutManager.FULL_SPAN_COUNT / pluginRowSpan(hasCards = true, lowWidth())
        var cover = width - view.paddingLeft - view.paddingRight - 2 * cardPadding
        if (columns <= 0) return
        cover /= columns
        cover -= 2 * cardPadding
        if (cover < 1) return
        coverPixels = cover
        val height = cover + marginTop + labelHeight + 2 * marginLabel + paddingBottom
        if (height == gridHeight) return
        gridHeight = height
        if (cards) notifyDataSetChanged()
    }

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
        reorderEnabled = false
        cards = false
        val rows = groups.sumOf { it.rows.size }
        entries = buildList {
            if (rows > 0) add(Entry.Decor(rows, false))
            for (group in groups) {
                if (group.rows.isEmpty() && group.tracks.isEmpty() && !group.counted) continue
                val only = group.rows.singleOrNull()
                if (group.title.isNotEmpty() && (group.counted || group.tracks.isNotEmpty() || only == null || only.title != group.title)) {
                    add(Entry.Header(group.title))
                }
                if (group.counted) add(Entry.Decor(group.rows.size, false, group.create))
                group.tracks.forEachIndexed { index, track ->
                    add(Entry.Track(track, index, group.tracks, group.title))
                }
                group.rows.forEach { add(Entry.Row(it)) }
            }
        }
        notifyDataSetChanged()
    }

    fun submitTracks(list: List<PluginTrack>) {
        tracks = list
        cards = false
        entries = buildList {
            if (list.isNotEmpty()) add(Entry.Decor(list.size, true))
            list.forEachIndexed { index, track -> add(Entry.Track(track, index, null, "")) }
        }
        notifyDataSetChanged()
    }

    /** Albums from one `artist()` call, then that call's tracks. */
    fun submitArtist(albums: List<PluginRow>, songs: List<PluginTrack>) {
        tracks = songs
        reorderEnabled = false
        cards = albums.isNotEmpty()
        entries = buildList {
            if (albums.isNotEmpty()) {
                add(Entry.Decor(albums.size, tracks = false, albums = true))
                albums.forEach { add(Entry.Card(it)) }
            }
            if (songs.isNotEmpty()) {
                add(Entry.Decor(songs.size, tracks = true))
                songs.forEachIndexed { index, track ->
                    add(Entry.Track(track, index, null, ""))
                }
            }
        }
        notifyDataSetChanged()
    }

    fun clear() {
        entries = emptyList()
        tracks = emptyList()
        cards = false
        reorderEnabled = false
        notifyDataSetChanged()
    }

    fun spanSize(): Int = pluginRowSpan(cards, lowWidth())

    fun fullSpan(position: Int): Boolean {
        val entry = entries.getOrNull(position) ?: return true
        return pluginRowFullSpan(cards, entry is Entry.Card, entry is Entry.Decor || entry is Entry.Header)
    }

    fun isCard(position: Int): Boolean = entries.getOrNull(position) is Entry.Card

    /** Column of a card within its run, starting at 0. */
    fun cardColumn(position: Int): Int {
        var count = 0
        var index = position
        while (index >= 0 && entries.getOrNull(index) is Entry.Card) {
            count++
            index--
        }
        return (count - 1).coerceAtLeast(0)
    }

    private fun lowWidth(): Boolean {
        val configuration = context.resources.configuration
        return configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
            configuration.screenWidthDp < 600
    }

    /** Track index at this adapter position, or -1 for a header or count bar. */
    fun trackIndex(position: Int): Int {
        val entry = entries.getOrNull(position) as? Entry.Track ?: return -1
        return entry.index
    }

    /** Moves one track. [from] and [to] are track indices in the list before this step. */
    fun moveTrack(from: Int, to: Int) {
        if (from == to || from !in tracks.indices || to !in tracks.indices) return
        val fromPos = entries.indexOfFirst { it is Entry.Track && it.index == from }
        val toPos = entries.indexOfFirst { it is Entry.Track && it.index == to }
        if (fromPos < 0 || toPos < 0) return
        tracks = moveItem(tracks, from, to)
        val next = entries.toMutableList()
        next.add(toPos, next.removeAt(fromPos))
        var index = 0
        entries = next.map { entry ->
            if (entry is Entry.Track) entry.copy(index = index++) else entry
        }
        notifyItemMoved(fromPos, toPos)
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
        is Entry.Card -> R.layout.adapter_grid_card
        is Entry.Row, is Entry.Track -> R.layout.adapter_list_card_larger
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = fragment.layoutInflater.inflate(viewType, parent, false)
        return when (viewType) {
            R.layout.general_decor -> DecorHolder(view)
            R.layout.mygomusic_section -> HeaderHolder(view)
            R.layout.adapter_grid_card -> CardHolder(view)
            else -> RowHolder(view).also(::addRowRipple)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = entries[position]) {
            is Entry.Decor -> bindDecor(holder as DecorHolder, entry)
            is Entry.Header -> (holder as HeaderHolder).title.text = entry.title
            is Entry.Card -> bindCard(holder as CardHolder, entry.row)
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
        if (holder is CardHolder) {
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
            holder.cover.dispose()
        }
        if (holder is DecorHolder) {
            holder.playAll.setOnClickListener(null)
            holder.shuffleAll.setOnClickListener(null)
            holder.create.setOnClickListener(null)
            holder.create.visibility = View.GONE
        }
        super.onViewRecycled(holder)
    }

    private fun bindDecor(holder: DecorHolder, decor: Entry.Decor) {
        val songs = decor.tracks && decor.count > 0
        holder.playAll.visibility = if (songs) View.VISIBLE else View.GONE
        holder.shuffleAll.visibility = if (songs) View.VISIBLE else View.GONE
        holder.sort.visibility = View.GONE
        holder.create.visibility = if (decor.create) View.VISIBLE else View.GONE
        if (decor.create) {
            val label = context.getString(R.string.mygomusic_new_playlist)
            holder.create.contentDescription = label
            TooltipCompat.setTooltipText(holder.create, label)
        }
        holder.jumpUp.visibility = View.GONE
        holder.jumpDown.visibility = View.GONE
        val plural = when {
            decor.albums -> R.plurals.albums
            decor.tracks -> R.plurals.songs
            else -> R.plurals.items
        }
        holder.counter.text = context.resources.getQuantityString(plural, decor.count, decor.count)
        holder.playAll.setOnClickListener { onPlayAll(tracks, false) }
        holder.shuffleAll.setOnClickListener { onPlayAll(tracks, true) }
        holder.create.setOnClickListener(if (decor.create) { _ -> onCreate() } else null)
    }

    private fun bindRow(holder: RowHolder, row: PluginRow) {
        holder.title.text = row.title
        holder.artist.text = row.subtitle
        rowText(holder, unavailable = false)
        holder.nowPlaying.visibility = View.GONE
        val placeholder = if (row.open is Open.Artist) {
            R.drawable.ic_default_cover_artist
        } else {
            R.drawable.ic_default_cover_playlist
        }
        loadCover(holder.cover, row.imageUrl, placeholder)
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
            val current = trackEntry(holder) ?: return@setOnClickListener
            if (!current.track.unavailable) onTrack(current.queue ?: tracks, current.index, current.queueTitle)
        }
        if (reorderEnabled) {
            // The drag helper takes the long press. The more button still opens the menu.
            holder.itemView.setOnLongClickListener(null)
        } else {
            holder.itemView.setOnLongClickListener {
                val current = currentTrack(holder) ?: return@setOnLongClickListener false
                onTrackMenu(current, it)
                true
            }
        }
        holder.more.setOnClickListener {
            val current = currentTrack(holder) ?: return@setOnClickListener
            onTrackMenu(current, it)
        }
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

    private fun bindCard(holder: CardHolder, row: PluginRow) {
        holder.title.text = row.title
        holder.artist.text = row.subtitle
        // Plugin albums do not carry a track count.
        holder.count.text = ""
        if (gridHeight > 0 && holder.itemView.layoutParams.height != gridHeight) {
            holder.itemView.updateLayoutParams<ViewGroup.LayoutParams> { height = gridHeight }
        }
        loadCover(holder.cover, row.imageUrl, R.drawable.ic_default_cover, coverPixels)
        holder.itemView.setOnClickListener { onRow(row) }
        val menu = row.menu.any && row.open !is Open.None
        holder.itemView.setOnLongClickListener {
            if (!menu) return@setOnLongClickListener false
            onRowMenu(row, it)
            true
        }
    }

    private fun loadCover(view: ImageView, url: String?, placeholder: Int, pixels: Int = PluginCovers.LIST_PIXELS) {
        // The image loader adds this plugin's request headers and fills in {size}.
        view.load(PluginCovers.sized(url, pixels)) {
            placeholderScaleToFit(placeholder)
            error(placeholder)
        }
    }

    private fun currentTrack(holder: RowHolder): PluginTrack? = trackEntry(holder)?.track

    private fun trackEntry(holder: RowHolder): Entry.Track? {
        return entries.getOrNull(holder.bindingAdapterPosition) as? Entry.Track
    }

    private fun notifyPlaying() {
        if (entries.none { it is Entry.Track }) return
        notifyDataSetChanged()
    }

    private sealed interface Entry {
        data class Decor(
            val count: Int,
            val tracks: Boolean,
            val create: Boolean = false,
            val albums: Boolean = false,
        ) : Entry
        data class Header(val title: String) : Entry
        data class Row(val row: PluginRow) : Entry
        data class Card(val row: PluginRow) : Entry
        /** [queue] null uses the track page's live list, which drag reorders. */
        data class Track(
            val track: PluginTrack,
            val index: Int,
            val queue: List<PluginTrack>?,
            val queueTitle: String,
        ) : Entry
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

    private class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val artist: TextView = view.findViewById(R.id.artist)
        val count: TextView = view.findViewById(R.id.track_count)
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val artist: TextView = view.findViewById(R.id.artist)
        val nowPlaying: ImageView = view.findViewById(R.id.now_playing)
        val more: MaterialButton = view.findViewById(R.id.more)
    }
}

/**
 * Drag on a track page. [from] and [to] reported on drop are the indices before the gesture,
 * with [to] the index the track occupies afterwards.
 */
internal class PluginTrackMoveCallback(
    private val rows: PluginRowsAdapter,
    private val onMoved: (from: Int, to: Int) -> Unit,
) : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
    0,
) {
    private var dragFrom = -1
    private var dragTo = -1

    override fun isLongPressDragEnabled(): Boolean = rows.reorderEnabled

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
            dragFrom = rows.trackIndex(viewHolder.bindingAdapterPosition)
            dragTo = dragFrom
        }
        super.onSelectedChanged(viewHolder, actionState)
    }

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        if (!rows.reorderEnabled || rows.trackIndex(viewHolder.bindingAdapterPosition) < 0) {
            return makeMovementFlags(0, 0)
        }
        return makeMovementFlags(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0,
        )
    }

    override fun canDropOver(
        recyclerView: RecyclerView,
        current: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = rows.trackIndex(target.bindingAdapterPosition) >= 0

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        val from = rows.trackIndex(viewHolder.bindingAdapterPosition)
        val to = rows.trackIndex(target.bindingAdapterPosition)
        if (from < 0 || to < 0 || from == to) return false
        rows.moveTrack(from, to)
        dragTo = to
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        val from = dragFrom
        val to = dragTo
        dragFrom = -1
        dragTo = -1
        if (from >= 0 && to >= 0 && from != to) onMoved(from, to)
    }
}

/** Album cards use the local album grid. Elsewhere rows keep the list span. */
internal fun pluginRowSpan(hasCards: Boolean, lowWidth: Boolean): Int = when {
    hasCards && lowWidth -> CustomGridLayoutManager.GRID_PORTRAIT_SPAN_SIZE
    hasCards -> CustomGridLayoutManager.GRID_LANDSCAPE_SPAN_SIZE
    lowWidth -> CustomGridLayoutManager.LIST_PORTRAIT_SPAN_SIZE
    else -> CustomGridLayoutManager.LIST_LANDSCAPE_SPAN_SIZE
}

/** On a card page, titles and tracks take the whole row so they do not sit beside a card. */
internal fun pluginRowFullSpan(hasCards: Boolean, card: Boolean, bar: Boolean): Boolean {
    if (card) return false
    if (hasCards) return true
    return bar
}
