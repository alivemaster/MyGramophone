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

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.DialogCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
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
            onTrack = { list, index ->
                PluginPlayback.play(mainActivity, pluginId(), list, index, currentTitle())
            },
            onTrackMenu = ::onTrackMenu,
            onPlayAll = { list, shuffle ->
                PluginPlayback.playAll(mainActivity, pluginId(), list, currentTitle(), shuffle)
            },
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
            shown = PluginPage.Groups(titleArg(), cached)
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
            is Open.Playlist -> openDetail(PluginPlaylistDetailFragment.KIND_PLAYLIST, open.id, row.title)
            is Open.Album -> openDetail(PluginPlaylistDetailFragment.KIND_ALBUM, open.id, row.title)
            is Open.Show -> openDetail(PluginPlaylistDetailFragment.KIND_SHOW, open.id, row.title)
            Open.Liked -> openDetail(PluginPlaylistDetailFragment.KIND_LIKED, "", row.title)
            is Open.Tracks -> openDetail(
                PluginPlaylistDetailFragment.KIND_GIVEN,
                "",
                row.title,
                cache.putTracks(open.tracks),
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
                R.id.mygomusic_unsave -> {
                    val id = (row.open as? Open.Playlist)?.id ?: return@setOnMenuItemClickListener true
                    mutate(row, id) { library.unsave(pluginId(), id) }
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
                    )
                    is PluginPage.Groups -> {
                        val token = cache.putGroups(result.groups)
                        mainActivity.startFragment(PluginPlaylistFragment()) {
                            putString(ARG_PLUGIN, pluginId())
                            putString(ARG_KIND, kind)
                            putString(ARG_PAGE, page)
                            putString(ARG_TITLE, result.title ?: title)
                            putInt(ARG_TOKEN, token)
                        }
                    }
                    is PluginPage.Account -> render(result)
                    is PluginPage.Message -> toast(result.text)
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
        val popup = PopupMenu(requireContext(), anchor)
        popup.inflate(R.menu.more_menu_less)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.play_next -> {
                    PluginPlayback.playNext(mainActivity, pluginId(), listOf(track))
                    true
                }
                R.id.add_to_queue -> {
                    PluginPlayback.enqueue(mainActivity, pluginId(), listOf(track))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun currentTitle(): String {
        return scaffold.collapsing?.title?.toString()?.ifEmpty { null } ?: titleArg()
    }

    private fun openDetail(kind: String, id: String, title: String, token: Int = 0, reload: String = "") {
        mainActivity.startFragment(PluginPlaylistDetailFragment()) {
            putString(PluginPlaylistDetailFragment.ARG_PLUGIN, pluginId())
            putString(PluginPlaylistDetailFragment.ARG_KIND, kind)
            putString(PluginPlaylistDetailFragment.ARG_ID, id)
            putString(PluginPlaylistDetailFragment.ARG_TITLE, title)
            putInt(PluginPlaylistDetailFragment.ARG_TOKEN, token)
            putString(PluginPlaylistDetailFragment.ARG_RELOAD, reload)
        }
    }

    private fun playStation(id: String, title: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            scaffold.refresh.isRefreshing = true
            try {
                when (val page = library.stationTracks(pluginId(), id)) {
                    is PluginPage.Tracks -> PluginPlayback.play(mainActivity, pluginId(), page.tracks, 0, title)
                    is PluginPage.Account -> render(page)
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
                    is PluginPage.Account -> render(page)
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
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename)
            .setView(R.layout.dialog_new_playlist)
            .setPositiveButton(android.R.string.ok) { which, _ ->
                val input = DialogCompat.requireViewById(which as AlertDialog, R.id.editText) as TextInputEditText
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                mutate(row, id) { library.rename(pluginId(), id, name) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        (DialogCompat.requireViewById(dialog, R.id.editText) as TextInputEditText).setText(row.title)
    }

    private fun confirmDelete(row: PluginRow) {
        val id = (row.open as? Open.Playlist)?.id ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_really, row.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                mutate(row, id) { library.delete(pluginId(), id) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun mutate(row: PluginRow, id: String = (row.open as? Open.Playlist)?.id.orEmpty(), block: suspend () -> Unit) {
        if (id.isEmpty() && row.open !is Open.Playlist) return
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
            }
            is PluginPage.Tracks -> {
                scaffold.status.visibility = View.GONE
                if (page.title.isNotEmpty()) scaffold.collapsing?.title = page.title
                rows.submitTracks(page.tracks)
            }
            is PluginPage.Account -> showAccount(page.code)
            is PluginPage.Message -> showMessage(page.text)
        }
    }

    private fun showAccount(code: String) {
        rows.clear()
        scaffold.message.text = if (code == "setup") {
            getString(R.string.mygomusic_open_settings)
        } else {
            getString(R.string.mygomusic_sign_in)
        }
        scaffold.action.visibility = View.VISIBLE
        scaffold.action.setText(if (code == "setup") R.string.mygomusic_open_settings else R.string.mygomusic_sign_in)
        scaffold.action.setOnClickListener {
            startActivity(Intent(requireContext(), PluginListActivity::class.java))
        }
        scaffold.status.visibility = View.VISIBLE
    }

    private fun showMessage(text: String) {
        rows.clear()
        scaffold.message.text = text
        scaffold.action.visibility = View.GONE
        scaffold.status.visibility = View.VISIBLE
    }

    private fun accountOrToast(error: Exception) {
        val call = error as? PluginCallException
        if (call?.code == "signIn" || call?.code == "setup") render(PluginPage.Account(call.code))
        else toast(error.message)
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
        const val KIND_ROOT = "root"
        const val KIND_BROWSE = "browse"
        const val KIND_ALBUMS = "albums"
        const val KIND_STATIONS = "stations"
        const val KIND_SHOWS = "shows"
    }
}

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
