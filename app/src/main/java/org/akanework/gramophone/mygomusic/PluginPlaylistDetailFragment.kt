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
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
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
            onTrack = { list, index ->
                PluginPlayback.play(mainActivity, pluginId(), list, index, titleArg())
            },
            onTrackMenu = ::onTrackMenu,
            onPlayAll = { list, shuffle ->
                PluginPlayback.playAll(mainActivity, pluginId(), list, titleArg(), shuffle)
            },
        )
        scaffold.list.adapter = rows
        scaffold.list.enableEdgeToEdgePaddingListener()
        scaffold.appBar?.enableEdgeToEdgePaddingListener()
        scaffold.list.setAppBar(scaffold.appBar)
        scaffold.collapsing?.title = titleArg()
        scaffold.toolbar?.setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        scaffold.refresh.setOnRefreshListener { load(force = true) }
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
                if (call?.code == "signIn" || call?.code == "setup") PluginPage.Account(call.code)
                else PluginPage.Message(e.message?.ifBlank { null } ?: getString(R.string.mygomusic_plugin_failed))
            }
            if (view == null) return@launch
            scaffold.refresh.isRefreshing = false
            when (page) {
                is PluginPage.Tracks -> {
                    tracks = page.tracks
                    if (page.title.isNotEmpty()) scaffold.collapsing?.title = page.title
                    showTracks(page.tracks)
                }
                is PluginPage.Account -> showAccount(page.code)
                is PluginPage.Message -> showMessage(page.text)
                is PluginPage.Groups -> showMessage(getString(R.string.mygomusic_empty))
            }
        }
    }

    private suspend fun fetch(): PluginPage {
        val title = titleArg()
        return when (kind()) {
            KIND_PLAYLIST -> library.songs(pluginId(), Open.Playlist(idArg()), title)
            KIND_ALBUM -> library.songs(pluginId(), Open.Album(idArg()), title)
            KIND_SHOW -> library.songs(pluginId(), Open.Show(idArg()), title)
            KIND_LIKED -> library.songs(pluginId(), Open.Liked, title)
            else -> {
                val reload = arguments?.getString(ARG_RELOAD).orEmpty()
                if (reload.isNotEmpty()) library.browse(pluginId(), reload, title)
                else PluginPage.Tracks(title, tracks ?: emptyList())
            }
        }
    }

    private fun showTracks(list: List<PluginTrack>) {
        if (list.isEmpty()) {
            showMessage(getString(R.string.mygomusic_empty))
            return
        }
        scaffold.status.visibility = View.GONE
        rows.submitTracks(list)
    }

    private fun showAccount(code: String) {
        rows.clear()
        scaffold.message.setText(if (code == "setup") R.string.mygomusic_open_settings else R.string.mygomusic_sign_in)
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
        const val KIND_PLAYLIST = "playlist"
        const val KIND_ALBUM = "album"
        const val KIND_SHOW = "show"
        const val KIND_LIKED = "liked"
        const val KIND_GIVEN = "given"
    }
}
