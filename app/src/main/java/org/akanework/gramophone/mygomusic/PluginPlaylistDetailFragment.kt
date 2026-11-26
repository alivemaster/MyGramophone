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
import androidx.recyclerview.widget.ItemTouchHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.ui.fragments.BaseFragment

/** Track page opened from a plugin row. Same shell as the local album and playlist page. */
class PluginPlaylistDetailFragment : BaseFragment(true) {
    private val library by lazy { PluginLibrary(requireContext()) }
    private val cache: PluginPageCache by activityViewModels()
    private var tracks: List<PluginTrack>? = null
    private var loadJob: Job? = null
    private var canAdd = false
    private var canRemove = false
    private var canMove = false
    private var moving = false
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
            onRow = {},
            onRowMenu = { _, _ -> },
            onTrack = { list, index, title ->
                PluginPlayback.play(mainActivity, pluginId(), list, index, title.ifEmpty { titleArg() })
            },
            onTrackMenu = ::onTrackMenu,
            onPlayAll = { list, shuffle ->
                PluginPlayback.playAll(mainActivity, pluginId(), list, titleArg(), shuffle)
            },
            onCreate = {},
        )
        ItemTouchHelper(PluginTrackMoveCallback(rows, ::onTracksMoved)).attachToRecyclerView(scaffold.list)
        scaffold.list.adapter = rows
        scaffold.list.enableEdgeToEdgePaddingListener()
        scaffold.appBar?.enableEdgeToEdgePaddingListener()
        scaffold.list.setAppBar(scaffold.appBar)
        scaffold.collapsing?.title = titleArg()
        scaffold.toolbar?.setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        scaffold.refresh.setOnRefreshListener { load(force = true) }
        canAdd = requireArguments().getBoolean(ARG_ADD)
        canRemove = requireArguments().getBoolean(ARG_REMOVE)
        canMove = requireArguments().getBoolean(ARG_MOVE)
        val token = requireArguments().getInt(ARG_TOKEN, 0)
        val cached = if (token != 0) cache.tracks(token) else null
        if (cached != null) {
            tracks = cached
            showTracks(cached)
        }
        return scaffold.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rows.observePlayer()
    }

    override fun onResume() {
        super.onResume()
        if (tracks == null) load(force = false)
    }

    private fun onTrackMenu(track: PluginTrack, anchor: View) {
        PopupMenu(requireContext(), anchor).showPluginTrackMenu(
            add = canAdd,
            remove = canRemove,
            onPlayNext = { PluginPlayback.playNext(mainActivity, pluginId(), listOf(track)) },
            onQueue = { PluginPlayback.enqueue(mainActivity, pluginId(), listOf(track)) },
            onAdd = { addTrack(track) },
            onRemove = { removeTrack(track) },
        )
    }

    private fun addTrack(track: PluginTrack) {
        choosePluginPlaylist(
            pluginId(),
            library,
            busy = { scaffold.refresh.isRefreshing = it },
            onNew = {
                createPluginPlaylist(pluginId(), library, track.id) { id ->
                    if (kind() == KIND_PLAYLIST && id == idArg()) load(force = true)
                    else if (id.isNotEmpty()) {
                        Toast.makeText(requireContext(), R.string.mygomusic_added, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        ) { id ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    library.addTracks(pluginId(), id, listOf(track.id))
                    if (view == null) return@launch
                    if (kind() == KIND_PLAYLIST && id == idArg()) load(force = true)
                    else Toast.makeText(requireContext(), R.string.mygomusic_added, Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportPluginCall(this@PluginPlaylistDetailFragment, pluginId(), e)
                }
            }
        }
    }

    private fun removeTrack(track: PluginTrack) {
        val id = idArg()
        if (kind() != KIND_PLAYLIST || id.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                library.removeTracks(pluginId(), id, listOf(track.id))
                load(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportPluginCall(this@PluginPlaylistDetailFragment, pluginId(), e)
            }
        }
    }

    private fun onTracksMoved(from: Int, to: Int) {
        if (moving || kind() != KIND_PLAYLIST) return
        val id = idArg()
        if (id.isEmpty()) return
        moving = true
        rows.reorderEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            try {
                library.moveTrack(pluginId(), id, from, to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                reportPluginCall(this@PluginPlaylistDetailFragment, pluginId(), e)
                load(force = true)
            } finally {
                moving = false
                if (!failed && view != null) rows.reorderEnabled = canMove
            }
        }
    }

    private fun load(force: Boolean) {
        if (!force && tracks != null) return
        if (pluginId().isEmpty()) return
        loadJob?.cancel()
        scaffold.refresh.isRefreshing = true
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val page = try {
                fetch()
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
                is PluginPage.Tracks -> {
                    canAdd = page.add
                    canRemove = page.remove
                    canMove = page.move
                    tracks = page.tracks
                    if (page.title.isNotEmpty()) scaffold.collapsing?.title = page.title
                    showTracks(page.tracks)
                }
                is PluginPage.Account -> {
                    showPluginAccountDialog(requireActivity(), pluginId(), page)
                    if (tracks == null) parentFragmentManager.popBackStack()
                }
                is PluginPage.Message -> showMessage(page.text)
                is PluginPage.Groups -> showMessage(getString(R.string.mygomusic_empty))
            }
        }
    }

    private suspend fun fetch(): PluginPage {
        val title = titleArg()
        return when (kind()) {
            KIND_PLAYLIST -> library.songs(
                pluginId(),
                Open.Playlist(
                    idArg(),
                    arguments?.getBoolean(ARG_MINE) == true,
                    arguments?.getBoolean(ARG_FIXED) == true,
                ),
                title,
            )
            KIND_ALBUM -> library.songs(pluginId(), Open.Album(idArg()), title)
            KIND_SHOW -> library.songs(pluginId(), Open.Show(idArg()), title)
            KIND_LIKED -> library.songs(pluginId(), Open.Liked, title)
            else -> {
                val reload = arguments?.getString(ARG_RELOAD).orEmpty()
                if (reload.isNotEmpty()) library.browse(pluginId(), reload, title)
                else PluginPage.Tracks(title, tracks ?: emptyList(), canAdd, canRemove, canMove)
            }
        }
    }

    private fun showTracks(list: List<PluginTrack>) {
        rows.reorderEnabled = canMove && list.isNotEmpty() && !moving
        if (list.isEmpty()) {
            showMessage(getString(R.string.mygomusic_empty))
            return
        }
        scaffold.status.visibility = View.GONE
        rows.submitTracks(list)
    }

    private fun showMessage(text: String) {
        rows.clear()
        scaffold.message.text = text
        scaffold.action.visibility = View.GONE
        scaffold.status.visibility = View.VISIBLE
    }

    private fun pluginId(): String = arguments?.getString(ARG_PLUGIN).orEmpty()

    private fun kind(): String = arguments?.getString(ARG_KIND).orEmpty()

    private fun idArg(): String = arguments?.getString(ARG_ID).orEmpty()

    private fun titleArg(): String = arguments?.getString(ARG_TITLE).orEmpty()

    companion object {
        const val ARG_PLUGIN = "pluginId"
        const val ARG_KIND = "kind"
        const val ARG_ID = "id"
        const val ARG_TITLE = "title"
        const val ARG_TOKEN = "token"
        const val ARG_RELOAD = "reload"
        const val ARG_ADD = "add"
        const val ARG_REMOVE = "remove"
        const val ARG_MOVE = "move"
        const val ARG_MINE = "mine"
        const val ARG_FIXED = "fixed"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_ALBUM = "album"
        const val KIND_SHOW = "show"
        const val KIND_LIKED = "liked"
        const val KIND_GIVEN = "given"
    }
}
