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
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class PluginTrack(
    val id: String,
    val name: String,
    val artists: String,
    val album: String,
    val imageUrl: String?,
    val durationMs: Long = 0,
    val albumArtist: String = "",
    /** Album release text as the source wrote it, such as a year or a date. */
    val released: String = "",
)

internal data class PluginCaps(
    val rename: Boolean,
    val delete: Boolean,
    val unsave: Boolean,
)

/** Rename and delete are only for a listener's own playlist that the source does not keep fixed. */
internal data class RowMenu(
    val play: Boolean = false,
    val rename: Boolean = false,
    val delete: Boolean = false,
    val unsave: Boolean = false,
) {
    val any: Boolean get() = play || rename || delete || unsave
}

internal sealed interface Open {
    data class Playlist(val id: String) : Open
    data class Tracks(val tracks: List<PluginTrack>) : Open
    data class Album(val id: String) : Open
    data class Show(val id: String) : Open
    data class Page(val page: String) : Open
    data object Albums : Open
    data object StationList : Open
    data object ShowList : Open
    data object Liked : Open
    data class Station(val id: String) : Open
    data object None : Open
}

internal data class PluginRow(
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val open: Open,
    val menu: RowMenu,
)

internal data class PluginGroup(
    val title: String,
    val rows: List<PluginRow>,
)

internal sealed interface PluginPage {
    data class Groups(
        val title: String?,
        val groups: List<PluginGroup>,
        val account: Account? = null,
    ) : PluginPage
    data class Tracks(val title: String, val tracks: List<PluginTrack>) : PluginPage
    data class Account(val code: String, val message: String) : PluginPage
    data class Message(val text: String) : PluginPage
}

/** A sign-in or setup failure must not throw away rows that were already listed. */
internal fun rootPage(
    groups: List<PluginGroup>,
    account: PluginPage.Account?,
    failure: PluginPage.Message?,
    empty: PluginPage.Message,
): PluginPage {
    if (groups.isNotEmpty()) return PluginPage.Groups(null, groups, account)
    return account ?: failure ?: empty
}

internal fun groupsFromPlaylists(
    lists: JSONArray?,
    mineTitle: String,
    savedTitle: String,
    caps: PluginCaps,
    songs: (Int) -> String,
): List<PluginGroup> {
    val mine = mutableListOf<PluginRow>()
    val saved = mutableListOf<PluginRow>()
    if (lists != null) {
        for (i in 0 until lists.length()) {
            val obj = lists.optJSONObject(i) ?: continue
            val row = playlistRow(obj, caps, songs) ?: continue
            if (obj.bool("mine")) mine += row else saved += row
        }
    }
    return buildList {
        if (mine.isNotEmpty()) add(PluginGroup(mineTitle, mine))
        if (saved.isNotEmpty()) add(PluginGroup(savedTitle, saved))
    }
}

internal fun groupsFromSections(
    sections: JSONArray?,
    fallbackTitle: String,
    caps: PluginCaps,
    songs: (Int) -> String,
    skip: (String, String) -> Unit,
): List<PluginGroup> {
    if (sections == null) return emptyList()
    val groups = mutableListOf<PluginGroup>()
    for (i in 0 until sections.length()) {
        val section = sections.optJSONObject(i) ?: continue
        val title = section.str("title").ifEmpty { section.str("pageTitle") }.ifEmpty { fallbackTitle }
        val rows = mutableListOf<PluginRow>()
        val tracks = parseTracks(section.optJSONArray("tracks"))
        if (tracks.isNotEmpty()) {
            val rowTitle = section.str("pageTitle").ifEmpty { section.str("title") }.ifEmpty { fallbackTitle }
            rows += PluginRow(rowTitle, songs(tracks.size), null, Open.Tracks(tracks), RowMenu(play = true))
        }
        rows += albumRows(section.optJSONArray("albums"))
        val artists = section.optJSONArray("artists")
        if (artists != null && artists.length() > 0) skip(title, "artists")
        rows += playlistRows(section.optJSONArray("playlists"), caps, songs)
        rows += showRows(section.optJSONArray("shows"))
        rows += stationRows(section.optJSONArray("stations"))
        rows += linkRows(section.optJSONArray("links"))
        val more = section.str("morePage")
        if (more.isNotEmpty()) {
            val moreTitle = section.str("pageTitle").ifEmpty { section.str("title") }.ifEmpty { fallbackTitle }
            rows += PluginRow(moreTitle, section.str("subtitle"), null, Open.Page(more), RowMenu())
        }
        if (rows.isEmpty()) {
            val empty = section.str("empty")
            if (empty.isEmpty()) continue
            rows += PluginRow(empty, "", null, Open.None, RowMenu())
        }
        groups += PluginGroup(title, rows)
    }
    return groups
}

private fun playlistRow(obj: JSONObject, caps: PluginCaps, songs: (Int) -> String): PluginRow? {
    val id = obj.str("id")
    val name = obj.str("name")
    if (id.isEmpty() || name.isEmpty()) return null
    val mine = obj.bool("mine")
    val fixed = obj.bool("fixed")
    val saved = obj.bool("saved")
    val subtitle = obj.str("owner").ifEmpty {
        val count = obj.int("trackCount").takeIf { it > 0 } ?: parseTracks(obj.optJSONArray("tracks")).size
        if (count > 0) songs(count) else obj.str("description")
    }
    return PluginRow(
        title = name,
        subtitle = subtitle,
        imageUrl = obj.str("imageUrl").ifEmpty { null },
        open = Open.Playlist(id),
        menu = RowMenu(
            play = true,
            rename = caps.rename && mine && !fixed,
            delete = caps.delete && mine && !fixed,
            unsave = caps.unsave && saved && !mine,
        ),
    )
}

private fun playlistRows(arr: JSONArray?, caps: PluginCaps, songs: (Int) -> String): List<PluginRow> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            playlistRow(obj, caps, songs)?.let { add(it) }
        }
    }
}

private fun albumRows(arr: JSONArray?): List<PluginRow> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.str("id")
            val name = obj.str("name")
            if (id.isEmpty() || name.isEmpty()) continue
            add(PluginRow(name, artistNames(obj.optJSONArray("artists")), obj.str("imageUrl").ifEmpty { null }, Open.Album(id), RowMenu(play = true)))
        }
    }
}

private fun showRows(arr: JSONArray?): List<PluginRow> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.str("id")
            val name = obj.str("name")
            if (id.isEmpty() || name.isEmpty()) continue
            add(PluginRow(name, obj.str("publisher"), obj.str("imageUrl").ifEmpty { null }, Open.Show(id), RowMenu(play = true)))
        }
    }
}

private fun stationRows(arr: JSONArray?): List<PluginRow> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.str("id")
            val name = obj.str("name")
            if (id.isEmpty() || name.isEmpty()) continue
            add(PluginRow(name, obj.str("description"), obj.str("imageUrl").ifEmpty { null }, Open.Station(id), RowMenu()))
        }
    }
}

private fun linkRows(arr: JSONArray?): List<PluginRow> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val page = obj.str("page")
            val title = obj.str("title")
            if (page.isEmpty() || title.isEmpty()) continue
            add(PluginRow(title, obj.str("subtitle"), obj.str("imageUrl").ifEmpty { null }, Open.Page(page), RowMenu()))
        }
    }
}

internal fun parseTracks(arr: JSONArray?): List<PluginTrack> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.str("id")
            val name = obj.str("name")
            if (id.isEmpty() || name.isEmpty()) continue
            val album = obj.optJSONObject("album")
            val image = obj.str("imageUrl").ifEmpty { album?.str("imageUrl").orEmpty() }.ifEmpty { null }
            add(
                PluginTrack(
                    id,
                    name,
                    artistNames(obj.optJSONArray("artists")),
                    album?.str("name").orEmpty(),
                    image,
                    (obj.opt("duration") as? Number)?.toLong() ?: 0,
                    artistNames(album?.optJSONArray("artists")),
                    album?.str("released").orEmpty(),
                ),
            )
        }
    }
}

private fun artistNames(arr: JSONArray?): String {
    if (arr == null) return ""
    return buildList {
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.str("name").orEmpty()
            if (name.isNotEmpty()) add(name)
        }
    }.joinToString(", ")
}

private fun JSONObject.str(key: String): String = opt(key) as? String ?: ""

private fun JSONObject.bool(key: String): Boolean = opt(key) == true

private fun JSONObject.int(key: String): Int = (opt(key) as? Number)?.toInt() ?: 0

internal class PluginLibrary(context: Context) {
    private val app = context.applicationContext

    suspend fun root(pluginId: String): PluginPage = load(pluginId) { ctx ->
        val caps = caps(ctx.exports)
        val groups = mutableListOf<PluginGroup>()
        var account: PluginPage.Account? = null
        var failure: PluginPage.Message? = null
        val nav = ctx.manifest.navigation.ifEmpty { defaultNav() }
        for (item in nav) {
            when (val page = navGroup(ctx, item, caps)) {
                null -> Unit
                is PluginPage.Account -> if (account == null) account = page
                is PluginPage.Message -> failure = page
                is PluginPage.Groups -> groups += page.groups
                is PluginPage.Tracks -> groups += PluginGroup(page.title, listOf(trackRow(page.title, page.tracks)))
            }
        }
        if ("playlists" in ctx.exports) {
            when (val page = catch(pluginId) {
                val lists = asArray(ctx.runtime.call("playlists"))
                PluginPage.Groups(null, groupsFromPlaylists(lists, app.getString(R.string.mygomusic_my_playlists), app.getString(R.string.mygomusic_saved_playlists), caps, songs))
            }) {
                is PluginPage.Account -> if (account == null) account = page
                is PluginPage.Message -> failure = page
                is PluginPage.Groups -> groups += page.groups
                else -> Unit
            }
        }
        rootPage(groups, account, failure, PluginPage.Message(app.getString(R.string.mygomusic_empty)))
    }

    suspend fun browse(pluginId: String, page: String, title: String): PluginPage = load(pluginId) { ctx ->
        if ("browse" !in ctx.exports) return@load emptyPage()
        val obj = asObject(ctx.runtime.call("browse", mapOf("page" to page)))
        opened(obj, obj.str("title").ifEmpty { title }, caps(ctx.exports))
    }

    suspend fun albums(pluginId: String, title: String): PluginPage = load(pluginId) { ctx ->
        if ("savedAlbums" !in ctx.exports) return@load emptyPage()
        val found = paged(ctx, "savedAlbums") { it.optJSONArray("albums") ?: JSONArray() }
        val rows = albumRows(found)
        if (rows.isEmpty()) emptyPage() else PluginPage.Groups(title, listOf(PluginGroup(title, rows)))
    }

    suspend fun stations(pluginId: String, title: String): PluginPage = load(pluginId) { ctx ->
        if ("stations" !in ctx.exports) return@load emptyPage()
        val rows = stationRows(asArray(ctx.runtime.call("stations")))
        if (rows.isEmpty()) emptyPage() else PluginPage.Groups(title, listOf(PluginGroup(title, rows)))
    }

    suspend fun shows(pluginId: String, title: String): PluginPage = load(pluginId) { ctx ->
        if ("shows" !in ctx.exports) return@load emptyPage()
        val rows = showRows(asArray(ctx.runtime.call("shows")))
        if (rows.isEmpty()) emptyPage() else PluginPage.Groups(title, listOf(PluginGroup(title, rows)))
    }

    suspend fun songs(pluginId: String, open: Open, title: String): PluginPage = when (open) {
        is Open.Tracks -> PluginPage.Tracks(title, open.tracks)
        is Open.Playlist -> playlist(pluginId, open.id, title)
        is Open.Album -> album(pluginId, open.id, title)
        is Open.Show -> show(pluginId, open.id, title)
        Open.Liked -> liked(pluginId, title)
        else -> PluginPage.Message(app.getString(R.string.mygomusic_empty))
    }

    suspend fun stationTracks(pluginId: String, id: String): PluginPage = load(pluginId) { ctx ->
        if ("stationNext" !in ctx.exports) return@load emptyPage()
        val tracks = parseTracks(asArray(ctx.runtime.call("stationNext", mapOf("id" to id))))
        if (tracks.isEmpty()) emptyPage() else PluginPage.Tracks("", tracks)
    }

    suspend fun rename(pluginId: String, id: String, name: String) = act(pluginId) {
        it.runtime.call("renamePlaylist", mapOf("id" to id, "name" to name))
    }

    suspend fun delete(pluginId: String, id: String) = act(pluginId) {
        it.runtime.call("deletePlaylist", mapOf("id" to id))
    }

    suspend fun unsave(pluginId: String, id: String) = act(pluginId) {
        it.runtime.call("savePlaylist", mapOf("id" to id, "on" to false))
    }

    private suspend fun playlist(pluginId: String, id: String, title: String): PluginPage = load(pluginId) { ctx ->
        val obj = asObject(ctx.runtime.call("playlist", mapOf("id" to id)))
        val tracks = parseTracks(obj.optJSONArray("tracks"))
        PluginPage.Tracks(obj.str("name").ifEmpty { title }, tracks)
    }

    private suspend fun album(pluginId: String, id: String, title: String): PluginPage = load(pluginId) { ctx ->
        val obj = asObject(ctx.runtime.call("album", mapOf("id" to id)))
        val album = obj.optJSONObject("album")
        val tracks = parseTracks(obj.optJSONArray("tracks"))
        PluginPage.Tracks(album?.str("name").orEmpty().ifEmpty { title }, tracks)
    }

    private suspend fun show(pluginId: String, id: String, title: String): PluginPage = load(pluginId) { ctx ->
        val episodes = paged(ctx, "show", mapOf("id" to id)) { page ->
            page.optJSONArray("episodes") ?: JSONArray()
        }
        PluginPage.Tracks(title, parseTracks(episodes))
    }

    private suspend fun liked(pluginId: String, title: String): PluginPage = load(pluginId) { ctx ->
        if ("liked" !in ctx.exports) return@load emptyPage()
        val tracks = paged(ctx, "liked") { it.optJSONArray("tracks") ?: JSONArray() }
        PluginPage.Tracks(title, parseTracks(tracks))
    }

    private suspend fun navGroup(ctx: Ctx, item: PluginNav, caps: PluginCaps): PluginPage? {
        if (item.builtin.isNotEmpty()) {
            return when (item.builtin) {
                "home" -> if ("home" !in ctx.exports) null else catch(ctx.pluginId) {
                    val obj = asObject(ctx.runtime.call("home"))
                    val title = navTitle(ctx.manifest, item, R.string.mygomusic_home)
                    PluginPage.Groups(title, groupsFromSections(obj.optJSONArray("sections"), title, caps, songs) { section, kind ->
                        Log.w(TAG, "plugin ${ctx.pluginId} skipped $kind in $section")
                    })
                }
                "liked" -> if ("liked" !in ctx.exports) null else rowPage(navTitle(ctx.manifest, item, R.string.mygomusic_liked), Open.Liked, play = true)
                "savedAlbums" -> if ("savedAlbums" !in ctx.exports) null else rowPage(navTitle(ctx.manifest, item, R.string.mygomusic_saved_albums), Open.Albums, play = false)
                "stations" -> if ("stations" !in ctx.exports) null else rowPage(navTitle(ctx.manifest, item, R.string.mygomusic_stations), Open.StationList, play = false)
                "shows" -> if ("shows" !in ctx.exports) null else rowPage(navTitle(ctx.manifest, item, R.string.mygomusic_shows), Open.ShowList, play = false)
                else -> {
                    Log.w(TAG, "plugin ${ctx.pluginId} skipped builtin ${item.builtin}")
                    null
                }
            }
        }
        if (item.page.isEmpty() || "browse" !in ctx.exports) return null
        val title = navTitle(ctx.manifest, item, 0).ifEmpty { item.page }
        return rowPage(title, Open.Page(item.page), play = false)
    }

    private fun rowPage(title: String, open: Open, play: Boolean): PluginPage.Groups {
        val row = PluginRow(title, "", null, open, RowMenu(play = play))
        return PluginPage.Groups(title, listOf(PluginGroup(title, listOf(row))))
    }

    private fun opened(obj: JSONObject, title: String, caps: PluginCaps): PluginPage {
        val groups = groupsFromSections(obj.optJSONArray("sections"), title, caps, songs) { section, kind ->
            Log.w(TAG, "skipped $kind in $section")
        }
        val rows = groups.flatMap { it.rows }
        // A page whose only row is already tracks opens those tracks. Links and playlists stay a list.
        if (rows.size == 1 && rows[0].open is Open.Tracks) {
            return PluginPage.Tracks(rows[0].title.ifEmpty { title }, (rows[0].open as Open.Tracks).tracks)
        }
        if (rows.isEmpty()) return emptyPage()
        return PluginPage.Groups(title, groups)
    }

    private suspend fun paged(ctx: Ctx, export: String, extra: Map<String, Any?> = emptyMap(), read: (JSONObject) -> JSONArray): JSONArray {
        val all = JSONArray()
        var offset = 0
        repeat(40) {
            val args = extra.toMutableMap()
            args["offset"] = offset
            val page = asObject(ctx.runtime.call(export, args))
            val chunk = read(page)
            for (i in 0 until chunk.length()) all.put(chunk.get(i))
            if (!page.bool("more") || chunk.length() == 0) return all
            offset += chunk.length()
        }
        return all
    }

    private fun trackRow(title: String, tracks: List<PluginTrack>): PluginRow {
        return PluginRow(title, songs(tracks.size), null, Open.Tracks(tracks), RowMenu(play = true))
    }

    private fun navTitle(manifest: PluginManifest, item: PluginNav, fallback: Int): String {
        if (item.title.isNotEmpty()) return manifest.text(locale(), item.title)
        if (fallback == 0) return ""
        return app.getString(fallback)
    }

    private fun caps(exports: Set<String>) = PluginCaps(
        rename = "renamePlaylist" in exports,
        delete = "deletePlaylist" in exports,
        unsave = "savePlaylist" in exports,
    )

    private fun defaultNav() = listOf(
        PluginNav("", "", "home"),
        PluginNav("", "", "liked"),
        PluginNav("", "", "savedAlbums"),
        PluginNav("", "", "stations"),
        PluginNav("", "", "shows"),
    )

    private fun emptyPage() = PluginPage.Message(app.getString(R.string.mygomusic_empty))

    private val songs: (Int) -> String = { count ->
        app.resources.getQuantityString(R.plurals.songs, count, count)
    }

    private suspend fun catch(pluginId: String, block: suspend () -> PluginPage): PluginPage {
        return try {
            block()
        } catch (e: PluginCallException) {
            logged(pluginId, e)
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun logged(pluginId: String, e: PluginCallException): PluginPage {
        if (e.code == "signIn" || e.code == "setup") {
            return PluginPage.Account(e.code, e.message?.trim().orEmpty())
        }
        Log.w(TAG, "plugin $pluginId", e)
        val message = e.message?.trim().orEmpty()
        return PluginPage.Message(message.ifEmpty { app.getString(R.string.mygomusic_plugin_failed) })
    }

    private suspend fun load(pluginId: String, block: suspend (Ctx) -> PluginPage): PluginPage {
        return withContext(Dispatchers.IO) {
            try {
                val ctx = ctx(pluginId) ?: return@withContext PluginPage.Message(app.getString(R.string.mygomusic_stream_only))
                block(ctx)
            } catch (e: PluginCallException) {
                logged(pluginId, e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "plugin $pluginId", e)
                PluginPage.Message(e.message?.takeIf { it.isNotBlank() } ?: app.getString(R.string.mygomusic_plugin_failed))
            }
        }
    }

    private suspend fun act(pluginId: String, block: suspend (Ctx) -> Unit) {
        withContext(Dispatchers.IO) {
            val ctx = ctx(pluginId) ?: throw PluginCallException("", app.getString(R.string.mygomusic_stream_only))
            block(ctx)
        }
    }

    private suspend fun ctx(pluginId: String): Ctx? {
        val store = PluginStore(app)
        val dir = store.packageDir(pluginId)
        val manifest = PluginManifest.read(dir)
        if (manifest.playback != "stream") return null
        val runtime = PluginRuntime(manifest, dir, store.dataDir(pluginId), locale())
        return Ctx(pluginId, manifest, runtime, runtime.exports())
    }

    private fun locale(): Locale {
        val config = app.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0]
        } else {
            @Suppress("DEPRECATION")
            config.locale
        }
    }

    private class Ctx(
        val pluginId: String,
        val manifest: PluginManifest,
        val runtime: PluginRuntime,
        val exports: Set<String>,
    )

    private companion object {
        const val TAG = "Mygomusic"
    }
}

private fun asObject(value: Any?): JSONObject = pluginJson(value) as? JSONObject ?: JSONObject()

private fun asArray(value: Any?): JSONArray = when (val wrapped = pluginJson(value)) {
    is JSONArray -> wrapped
    is JSONObject -> JSONArray().put(wrapped)
    else -> JSONArray()
}
