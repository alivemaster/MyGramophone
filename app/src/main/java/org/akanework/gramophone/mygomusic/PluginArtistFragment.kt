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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.fragments.BaseFragment

/** Artist page. Albums use the local album grid; tracks use the plugin track rows. */
class PluginArtistFragment : BaseFragment(true) {
    private val library by lazy { PluginLibrary(requireContext()) }
    private val cache: PluginPageCache by activityViewModels()
    private var shown: PluginPage.Artist? = null
    private var loadJob: Job? = null
    private var following: Boolean? = null
    private var canAdd = false
    private var canArtist = false
    private var canAlbum = false
    private var busy = false
    private lateinit var scaffold: PluginScaffold
    private lateinit var rows: PluginRowsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        scaffold = pluginScaffold(inflater, nested = true)
        rows = PluginRowsAdapter(
            fragment = this,
            pluginId = pluginId(),
            onRow = ::onRow,
            onRowMenu = ::onRowMenu,
            onTrack = { list, index, title ->
                PluginPlayback.play(mainActivity, pluginId(), list, index, title.ifEmpty { titleArg() })
            },
            onTrackMenu = ::onTrackMenu,
            onPlayAll = { list, shuffle ->
                PluginPlayback.playAll(mainActivity, pluginId(), list, titleArg(), shuffle)
            },
            onCreate = {},
        )
        scaffold.list.adapter = rows
        scaffold.list.enableEdgeToEdgePaddingListener()
        scaffold.appBar?.enableEdgeToEdgePaddingListener()
        scaffold.list.setAppBar(scaffold.appBar)
        scaffold.collapsing?.title = titleArg()
        scaffold.toolbar?.setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        scaffold.toolbar?.inflateMenu(R.menu.mygomusic_page_menu)
        scaffold.toolbar?.menu?.findItem(R.id.mygomusic_save)?.isVisible = false
        scaffold.toolbar?.setOnMenuItemClickListener { item ->
            if (item.itemId != R.id.mygomusic_follow) return@setOnMenuItemClickListener false
            toggleFollow()
            true
        }
        scaffold.refresh.setOnRefreshListener { load(force = true) }
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

    private fun onRow(row: PluginRow) {
        val album = row.open as? Open.Album ?: return
        openPluginAlbum(library, cache, pluginId(), album.id, row.title) { scaffold.refresh.isRefreshing = it }
    }

    private fun onRowMenu(row: PluginRow, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.inflate(R.menu.mygomusic_row_menu)
        popup.menu.findItem(R.id.play).isVisible = row.menu.play
        popup.menu.findItem(R.id.play_next).isVisible = row.menu.play
        popup.menu.findItem(R.id.add_to_queue).isVisible = row.menu.play
        popup.menu.findItem(R.id.rename).isVisible = false
        popup.menu.findItem(R.id.delete).isVisible = false
        popup.menu.findItem(R.id.mygomusic_save).isVisible = false
        popup.menu.findItem(R.id.mygomusic_unsave).isVisible = false
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
                else -> false
            }
        }
        popup.show()
    }

    private fun onTrackMenu(track: PluginTrack, anchor: View) {
        PopupMenu(requireContext(), anchor).showPluginTrackMenu(
            context = requireContext(),
            add = canAdd,
            remove = false,
            links = trackLinks(track, canArtist, canAlbum),
            onPlayNext = { PluginPlayback.playNext(mainActivity, pluginId(), listOf(track)) },
            onQueue = { PluginPlayback.enqueue(mainActivity, pluginId(), listOf(track)) },
            onAdd = { addTrack(track) },
            onRemove = {},
            onLink = ::openLink,
        )
    }

    private fun openLink(link: TrackLink) {
        if (link.artist) mainActivity.openPluginArtist(pluginId(), link.id, link.name)
        else openPluginAlbum(library, cache, pluginId(), link.id, link.name) { scaffold.refresh.isRefreshing = it }
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
                    if (view != null) reportPluginCall(this@PluginArtistFragment, pluginId(), e)
                }
            }
        }
    }

    private fun toggleFollow() {
        val current = following ?: return
        val id = idArg()
        if (busy || id.isEmpty()) return
        busy = true
        applyFollow()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                library.follow(pluginId(), id, !current)
                if (view == null) return@launch
                following = !current
                shown = shown?.copy(following = !current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (view != null) reportPluginCall(this@PluginArtistFragment, pluginId(), e)
            } finally {
                busy = false
                if (view != null) applyFollow()
            }
        }
    }

    private fun applyFollow() {
        val item = scaffold.toolbar?.menu?.findItem(R.id.mygomusic_follow) ?: return
        val state = following
        item.isVisible = state != null
        item.isEnabled = !busy
        if (state != null) item.setTitle(if (state) R.string.mygomusic_unfollow else R.string.mygomusic_follow)
    }

    private fun useSongs(row: PluginRow, block: (List<PluginTrack>, String) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            scaffold.refresh.isRefreshing = true
            try {
                when (val page = library.songs(pluginId(), row.open, row.title)) {
                    is PluginPage.Tracks -> if (view != null) block(page.tracks, page.title.ifEmpty { row.title })
                    is PluginPage.Account -> if (view != null) showPluginAccountDialog(requireActivity(), pluginId(), page)
                    is PluginPage.Message -> if (view != null) toast(page.text)
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (view != null) reportPluginCall(this@PluginArtistFragment, pluginId(), e)
            } finally {
                if (view != null) scaffold.refresh.isRefreshing = false
            }
        }
    }

    private fun load(force: Boolean) {
        if (!force && shown != null) return
        if (pluginId().isEmpty() || idArg().isEmpty()) return
        loadJob?.cancel()
        scaffold.refresh.isRefreshing = true
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val page = try {
                library.artist(pluginId(), idArg(), titleArg())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val call = e as? PluginCallException
                if (call != null && (call.code == "signIn" || call.code == "setup")) {
                    PluginPage.Account(call.code, call.message?.trim().orEmpty())
                } else {
                    PluginPage.Message(e.message?.ifBlank { null } ?: getString(R.string.mygomusic_plugin_failed))
                }
            }
            if (view == null) return@launch
            scaffold.refresh.isRefreshing = false
            when (page) {
                is PluginPage.Artist -> {
                    shown = page
                    show(page)
                }
                is PluginPage.Account -> {
                    showPluginAccountDialog(requireActivity(), pluginId(), page)
                    if (shown == null) parentFragmentManager.popBackStack()
                }
                is PluginPage.Message -> showMessage(page.text)
                else -> showMessage(getString(R.string.mygomusic_empty))
            }
        }
    }

    private fun show(page: PluginPage.Artist) {
        following = page.following
        canAdd = page.add
        canArtist = page.artist
        canAlbum = page.album
        applyFollow()
        if (page.title.isNotEmpty()) {
            scaffold.collapsing?.title = page.title
        }
        if (page.albums.isEmpty() && page.tracks.isEmpty()) {
            showMessage(getString(R.string.mygomusic_empty))
            return
        }
        scaffold.status.visibility = View.GONE
        rows.submitArtist(page.albums, page.tracks)
    }

    private fun showMessage(text: String) {
        rows.clear()
        scaffold.message.text = text
        scaffold.action.visibility = View.GONE
        scaffold.status.visibility = View.VISIBLE
    }

    private fun toast(text: String?) {
        val message = text?.ifBlank { null } ?: getString(R.string.mygomusic_plugin_failed)
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun pluginId(): String = arguments?.getString(ARG_PLUGIN).orEmpty()

    private fun idArg(): String = arguments?.getString(ARG_ID).orEmpty()

    private fun titleArg(): String = arguments?.getString(ARG_TITLE).orEmpty()

    companion object {
        const val ARG_PLUGIN = "pluginId"
        const val ARG_ID = "id"
        const val ARG_TITLE = "title"
    }
}

internal fun MainActivity.openPluginArtist(pluginId: String, id: String, title: String) {
    if (id.isEmpty()) return
    startFragment(PluginArtistFragment()) {
        putString(PluginArtistFragment.ARG_PLUGIN, pluginId)
        putString(PluginArtistFragment.ARG_ID, id)
        putString(PluginArtistFragment.ARG_TITLE, title)
    }
}
