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
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.ui.fragments.BaseFragment
import org.akanework.gramophone.ui.fragments.ViewPagerFragment

/**
 * First level of a plugin tab, and any later page that is still a list of rows.
 * Created with the tab. The runtime starts on the first [onResume], not when the view is created.
 */
class PluginPlaylistFragment : BaseFragment(null) {
    private val library by lazy { PluginLibrary(requireContext()) }
    private val cache: PluginPageCache by activityViewModels()
    private var shown: PluginPage? = null
    private var loadJob: Job? = null
    private lateinit var scaffold: PluginScaffold
    private lateinit var rows: PluginRowsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val nested = kind() != KIND_ROOT
        scaffold = pluginScaffold(inflater, nested)
        rows = PluginRowsAdapter(
            fragment = this,
            pluginId = pluginId(),
            onRow = ::onRow,
            onRowMenu = ::onRowMenu,
            onTrack = { list, index, title ->
                PluginPlayback.play(mainActivity, pluginId(), list, index, title.ifEmpty { currentTitle() })
            },
            onTrackMenu = ::onTrackMenu,
            onPlayAll = { list, shuffle ->
                PluginPlayback.playAll(mainActivity, pluginId(), list, currentTitle(), shuffle)
            },
            onCreate = ::createPlaylist,
        )
        scaffold.list.adapter = rows
        scaffold.list.enableEdgeToEdgePaddingListener()
        if (!nested) {
            (parentFragment as? ViewPagerFragment)?.let { parent ->
                // Local tabs share one pool and key holders by layout id. These rows use the
                // same layouts with different holders, so sharing that pool casts the wrong type.
                scaffold.list.setAppBar(parent.appBarLayout)
            }
        } else {
            scaffold.appBar?.enableEdgeToEdgePaddingListener()
            scaffold.list.setAppBar(scaffold.appBar)
            scaffold.collapsing?.title = titleArg()
            scaffold.toolbar?.setNavigationOnClickListener {
                requireActivity().supportFragmentManager.popBackStack()
            }
        }
        scaffold.refresh.setOnRefreshListener { load(force = true) }
        val token = requireArguments().getInt(ARG_TOKEN, 0)
        val cached = if (token != 0) cache.groups(token) else null
        if (cached != null) {
            shown = PluginPage.Groups(
                titleArg(),
                cached,
                add = requireArguments().getBoolean(ARG_ADD),
                artist = requireArguments().getBoolean(ARG_ARTIST),
                album = requireArguments().getBoolean(ARG_ALBUM),
            )
            render(shown!!)
        }
        return scaffold.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rows.observePlayer()
    }

    override fun onResume() {
        super.onResume()
        if (shown == null) load(force = false)
    }

    /** Same as a local song tab: jump to the row that is already playing. */
    fun onTabReselected() {
        val index = rows.playingIndex()
        if (index >= 0) scaffold.list.scrollToPositionWithOffsetCompat(index, 0)
    }

    private fun onRow(row: PluginRow) {
        when (val open = row.open) {
            is Open.Playlist -> openSongs(PluginPlaylistDetailFragment.KIND_PLAYLIST, open.id, row.title, open)
            is Open.Album -> openSongs(PluginPlaylistDetailFragment.KIND_ALBUM, open.id, row.title, open)
            is Open.Artist -> mainActivity.openPluginArtist(pluginId(), open.id, row.title)
            is Open.Show -> openSongs(PluginPlaylistDetailFragment.KIND_SHOW, open.id, row.title, open)
            Open.Liked -> openSongs(PluginPlaylistDetailFragment.KIND_LIKED, "", row.title, Open.Liked)
            is Open.Tracks -> openDetail(
                PluginPlaylistDetailFragment.KIND_GIVEN,
                "",
                row.title,
                cache.putTracks(open.tracks),
                add = pageAdd(),
                artist = pageArtist(),
                album = pageAlbum(),
            )
            is Open.Page -> openRemote(KIND_BROWSE, open.page, row.title) {
                library.browse(pluginId(), open.page, row.title)
            }
            Open.Albums -> openRemote(KIND_ALBUMS, "", row.title) { library.albums(pluginId(), row.title) }
            Open.StationList -> openRemote(KIND_STATIONS, "", row.title) { library.stations(pluginId(), row.title) }
            Open.ShowList -> openRemote(KIND_SHOWS, "", row.title) { library.shows(pluginId(), row.title) }
            is Open.Station -> playStation(open.id, row.title)
            Open.None -> Unit
        }
    }

    private fun onRowMenu(row: PluginRow, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.inflate(R.menu.mygomusic_row_menu)
        popup.menu.findItem(R.id.play).isVisible = row.menu.play
        popup.menu.findItem(R.id.play_next).isVisible = row.menu.play
        popup.menu.findItem(R.id.add_to_queue).isVisible = row.menu.play
        popup.menu.findItem(R.id.rename).isVisible = row.menu.rename
        popup.menu.findItem(R.id.delete).isVisible = row.menu.delete
        popup.menu.findItem(R.id.mygomusic_save).isVisible = row.menu.save
        popup.menu.findItem(R.id.mygomusic_unsave).isVisible = row.menu.unsave
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.play -> {
                    useSongs(row) { tracks, title ->
                        PluginPlayback.play(mainActivity, pluginId(), tracks, 0, title)
                    }
                    true
                }
                R.id.play_next -> {
                    useSongs(row) { tracks, _ -> PluginPlayback.playNext(mainActivity, pluginId(), tracks) }
                    true
                }
                R.id.add_to_queue -> {
                    useSongs(row) { tracks, _ -> PluginPlayback.enqueue(mainActivity, pluginId(), tracks) }
                    true
                }
                R.id.rename -> {
                    rename(row)
                    true
                }
                R.id.delete -> {
                    confirmDelete(row)
                    true
                }
                R.id.mygomusic_save -> {
                    val id = (row.open as? Open.Playlist)?.id ?: return@setOnMenuItemClickListener true
                    mutate { library.save(pluginId(), id) }
                    true
                }
                R.id.mygomusic_unsave -> {
                    val id = (row.open as? Open.Playlist)?.id ?: return@setOnMenuItemClickListener true
                    mutate { library.unsave(pluginId(), id) }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openRemote(kind: String, page: String, title: String, load: suspend () -> PluginPage) {
        viewLifecycleOwner.lifecycleScope.launch {
            scaffold.refresh.isRefreshing = true
            try {
                when (val result = load()) {
                    is PluginPage.Tracks -> openDetail(
                        PluginPlaylistDetailFragment.KIND_GIVEN,
                        "",
                        result.title.ifEmpty { title },
                        cache.putTracks(result.tracks),
                        page,
                        add = result.add,
                        remove = result.remove,
                        move = result.move,
                        artist = result.artist,
                        album = result.album,
                        saved = savedArg(result.saved),
                    )
                    is PluginPage.Groups -> {
                        val token = cache.putGroups(result.groups)
                        mainActivity.startFragment(PluginPlaylistFragment()) {
                            putString(ARG_PLUGIN, pluginId())
                            putString(ARG_KIND, kind)
                            putString(ARG_PAGE, page)
                            putString(ARG_TITLE, result.title ?: title)
                            putInt(ARG_TOKEN, token)
                            putBoolean(ARG_ADD, result.add)
                            putBoolean(ARG_ARTIST, result.artist)
                            putBoolean(ARG_ALBUM, result.album)
                        }
                    }
                    is PluginPage.Account -> if (view != null) showPluginAccountDialog(requireActivity(), pluginId(), result)
                    is PluginPage.Message -> toast(result.text)
                    is PluginPage.Artist -> toast(null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message)
            } finally {
                if (view != null) scaffold.refresh.isRefreshing = false
            }
        }
    }

    private fun onTrackMenu(track: PluginTrack, anchor: View) {
        PopupMenu(requireContext(), anchor).showPluginTrackMenu(
            context = requireContext(),
            add = pageAdd(),
            remove = false,
            links = trackLinks(track, pageArtist(), pageAlbum()),
            onPlayNext = { PluginPlayback.playNext(mainActivity, pluginId(), listOf(track)) },
            onQueue = { PluginPlayback.enqueue(mainActivity, pluginId(), listOf(track)) },
            onAdd = { addTrack(track) },
            onRemove = {},
            onLink = ::openLink,
        )
    }

    private fun openLink(link: TrackLink) {
        if (link.artist) mainActivity.openPluginArtist(pluginId(), link.id, link.name)
        else openSongs(PluginPlaylistDetailFragment.KIND_ALBUM, link.id, link.name, Open.Album(link.id))
    }

    private fun addTrack(track: PluginTrack) {
        choosePluginPlaylist(
            pluginId(),
            library,
            busy = { scaffold.refresh.isRefreshing = it },
            onNew = {
                createPluginPlaylist(pluginId(), library, track.id) { id ->
                    if (id.isNotEmpty()) {
                        Toast.makeText(requireContext(), R.string.mygomusic_added, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        ) { id ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    library.addTracks(pluginId(), id, listOf(track.id))
                    if (view != null) {
                        Toast.makeText(requireContext(), R.string.mygomusic_added, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    accountOrToast(e)
                }
            }
        }
    }

    private fun pageAdd(): Boolean = pageFlag(PluginPage.Groups::add, PluginPage.Tracks::add, ARG_ADD)

    private fun pageArtist(): Boolean = pageFlag(PluginPage.Groups::artist, PluginPage.Tracks::artist, ARG_ARTIST)

    private fun pageAlbum(): Boolean = pageFlag(PluginPage.Groups::album, PluginPage.Tracks::album, ARG_ALBUM)

    private fun pageFlag(
        group: (PluginPage.Groups) -> Boolean,
        tracks: (PluginPage.Tracks) -> Boolean,
        arg: String,
    ): Boolean {
        val page = shown
        if (page is PluginPage.Groups) return group(page)
        if (page is PluginPage.Tracks) return tracks(page)
        return arguments?.getBoolean(arg) == true
    }

    private fun currentTitle(): String {
        return scaffold.collapsing?.title?.toString()?.ifEmpty { null } ?: titleArg()
    }

    private fun openSongs(kind: String, id: String, title: String, open: Open) {
        viewLifecycleOwner.lifecycleScope.launch {
            scaffold.refresh.isRefreshing = true
            try {
                when (val page = library.songs(pluginId(), open, title)) {
                    is PluginPage.Tracks -> {
                        val playlist = open as? Open.Playlist
                        openDetail(
                            kind,
                            id,
                            page.title.ifEmpty { title },
                            cache.putTracks(page.tracks),
                        add = page.add,
                        remove = page.remove,
                        move = page.move,
                        mine = playlist?.mine == true,
                        fixed = playlist?.fixed == true,
                        artist = page.artist,
                        album = page.album,
                        saved = savedArg(page.saved),
                    )
                    }
                    is PluginPage.Account -> if (view != null) showPluginAccountDialog(requireActivity(), pluginId(), page)
                    is PluginPage.Message -> toast(page.text)
                    else -> toast(null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                accountOrToast(e)
            } finally {
                if (view != null) scaffold.refresh.isRefreshing = false
            }
        }
    }

    private fun openDetail(
        kind: String,
        id: String,
        title: String,
        token: Int = 0,
        reload: String = "",
        add: Boolean = false,
        remove: Boolean = false,
        move: Boolean = false,
        mine: Boolean = false,
        fixed: Boolean = false,
        artist: Boolean = false,
        album: Boolean = false,
        saved: Int = -1,
    ) {
        mainActivity.openPluginDetail(
            pluginId = pluginId(),
            kind = kind,
            id = id,
            title = title,
            token = token,
            reload = reload,
            add = add,
            remove = remove,
            move = move,
            mine = mine,
            fixed = fixed,
            artist = artist,
            album = album,
            saved = saved,
        )
    }

    private fun playStation(id: String, title: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            scaffold.refresh.isRefreshing = true
            try {
                when (val page = library.stationTracks(pluginId(), id)) {
                    is PluginPage.Tracks -> PluginPlayback.play(mainActivity, pluginId(), page.tracks, 0, title)
                    is PluginPage.Account -> if (view != null) showPluginAccountDialog(requireActivity(), pluginId(), page)
                    is PluginPage.Message -> toast(page.text)
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message)
            } finally {
                if (view != null) scaffold.refresh.isRefreshing = false
            }
        }
    }

    private fun useSongs(row: PluginRow, block: (List<PluginTrack>, String) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            scaffold.refresh.isRefreshing = true
            try {
                when (val page = library.songs(pluginId(), row.open, row.title)) {
                    is PluginPage.Tracks -> block(page.tracks, page.title.ifEmpty { row.title })
                    is PluginPage.Account -> if (view != null) showPluginAccountDialog(requireActivity(), pluginId(), page)
                    is PluginPage.Message -> toast(page.text)
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                accountOrToast(e)
            } finally {
                if (view != null) scaffold.refresh.isRefreshing = false
            }
        }
    }

    private fun rename(row: PluginRow) {
        val id = (row.open as? Open.Playlist)?.id ?: return
        pluginPlaylistNameDialog(R.string.rename, row.title) { name ->
            mutate { library.rename(pluginId(), id, name) }
        }
    }

    private fun createPlaylist() {
        createPluginPlaylist(pluginId(), library) {
            if (view != null) load(force = true)
        }
    }

    private fun confirmDelete(row: PluginRow) {
        val id = (row.open as? Open.Playlist)?.id ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_really, row.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                mutate { library.delete(pluginId(), id) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun mutate(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                block()
                load(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                accountOrToast(e)
            }
        }
    }

    private fun load(force: Boolean) {
        if (!force && shown != null) return
        if (pluginId().isEmpty()) return
        loadJob?.cancel()
        scaffold.refresh.isRefreshing = true
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val page = try {
                when (kind()) {
                    KIND_BROWSE -> library.browse(pluginId(), pageArg(), titleArg())
                    KIND_ALBUMS -> library.albums(pluginId(), titleArg())
                    KIND_STATIONS -> library.stations(pluginId(), titleArg())
                    KIND_SHOWS -> library.shows(pluginId(), titleArg())
                    else -> library.root(pluginId())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PluginPage.Message(e.message?.ifBlank { null } ?: getString(R.string.mygomusic_plugin_failed))
            }
            shown = page
            if (view != null) {
                render(page)
                scaffold.refresh.isRefreshing = false
            }
        }
    }

    private fun render(page: PluginPage) {
        when (page) {
            is PluginPage.Groups -> {
                scaffold.status.visibility = View.GONE
                rows.submitGroups(page.groups)
                if (!page.title.isNullOrEmpty()) scaffold.collapsing?.title = page.title
                page.account?.let { showPluginAccountDialog(requireActivity(), pluginId(), it) }
            }
            is PluginPage.Tracks -> {
                scaffold.status.visibility = View.GONE
                if (page.title.isNotEmpty()) scaffold.collapsing?.title = page.title
                rows.submitTracks(page.tracks)
            }
            is PluginPage.Account -> showPluginAccountDialog(requireActivity(), pluginId(), page)
            is PluginPage.Message -> showMessage(page.text)
            is PluginPage.Artist -> showMessage(getString(R.string.mygomusic_empty))
        }
    }

    private fun showMessage(text: String) {
        rows.clear()
        scaffold.message.text = text
        scaffold.action.visibility = View.GONE
        scaffold.status.visibility = View.VISIBLE
    }

    private fun accountOrToast(error: Exception) {
        val call = error as? PluginCallException
        if (call != null && (call.code == "signIn" || call.code == "setup")) {
            if (view != null) {
                showPluginAccountDialog(requireActivity(), pluginId(), PluginPage.Account(call.code, call.message?.trim().orEmpty()))
            }
            return
        }
        toast(error.message)
    }

    private fun toast(text: String?) {
        val message = text?.ifBlank { null } ?: getString(R.string.mygomusic_plugin_failed)
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun pluginId(): String = arguments?.getString(ARG_PLUGIN).orEmpty()

    private fun kind(): String = arguments?.getString(ARG_KIND) ?: KIND_ROOT

    private fun pageArg(): String = arguments?.getString(ARG_PAGE).orEmpty()

    private fun titleArg(): String = arguments?.getString(ARG_TITLE).orEmpty()

    companion object {
        const val ARG_PLUGIN = "pluginId"
        const val ARG_KIND = "kind"
        const val ARG_PAGE = "page"
        const val ARG_TITLE = "title"
        const val ARG_TOKEN = "token"
        const val ARG_ADD = "add"
        const val ARG_ARTIST = "artist"
        const val ARG_ALBUM = "album"
        const val KIND_ROOT = "root"
        const val KIND_BROWSE = "browse"
        const val KIND_ALBUMS = "albums"
        const val KIND_STATIONS = "stations"
        const val KIND_SHOWS = "shows"
    }
}

internal fun PopupMenu.showPluginTrackMenu(
    context: Context,
    add: Boolean,
    remove: Boolean,
    links: List<TrackLink> = emptyList(),
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onLink: (TrackLink) -> Unit = {},
) {
    inflate(R.menu.more_menu_less)
    menu.findItem(R.id.add_to_playlist).isVisible = add
    menu.findItem(R.id.mygomusic_remove).isVisible = remove
    // One item per artist who has a page, then the album. A credit with no id is only a name.
    links.forEachIndexed { index, link ->
        val label = context.getString(
            if (link.artist) R.string.mygomusic_go_to_artist else R.string.mygomusic_go_to_album,
            link.name,
        )
        menu.add(Menu.NONE, TRACK_LINK + index, 100 + index, label)
    }
    setOnMenuItemClickListener { item ->
        val linkIndex = item.itemId - TRACK_LINK
        when {
            item.itemId == R.id.play_next -> {
                onPlayNext()
                true
            }
            item.itemId == R.id.add_to_queue -> {
                onQueue()
                true
            }
            item.itemId == R.id.add_to_playlist -> {
                onAdd()
                true
            }
            item.itemId == R.id.mygomusic_remove -> {
                onRemove()
                true
            }
            linkIndex in links.indices -> {
                onLink(links[linkIndex])
                true
            }
            else -> false
        }
    }
    show()
}

private const val TRACK_LINK = 0x4D100000

internal class PluginPageCache : ViewModel() {
    private var next = 1
    private val tracks = HashMap<Int, List<PluginTrack>>()
    private val groups = HashMap<Int, List<PluginGroup>>()

    fun putTracks(value: List<PluginTrack>): Int {
        val id = next++
        tracks[id] = value
        return id
    }

    fun tracks(id: Int): List<PluginTrack>? = tracks[id]

    fun putGroups(value: List<PluginGroup>): Int {
        val id = next++
        groups[id] = value
        return id
    }

    fun groups(id: Int): List<PluginGroup>? = groups[id]
}
