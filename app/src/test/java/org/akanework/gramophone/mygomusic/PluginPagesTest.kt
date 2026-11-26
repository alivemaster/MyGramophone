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

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginPagesTest {
    private val caps = PluginCaps(rename = true, delete = true, save = true)

    @Test
    fun trackSectionIsOneRowAndArtistsAreSkipped() {
        val sections = JSONArray(
            """[{"title":"Picked","tracks":[{"id":"t1","name":"Song","artists":[{"name":"A"}],"album":{"name":"Al"}}],"artists":[{"id":"a","name":"A"}]}]""",
        )
        val skipped = mutableListOf<String>()
        val groups = groupsFromSections(sections, "Home", caps, { "$it" }, "See more") { _, kind -> skipped += kind }
        assertEquals(listOf("artists"), skipped)
        assertEquals(1, groups.single().rows.size)
        assertTrue(groups.single().rows.single().open is Open.Tracks)
        assertTrue(groups.single().tracks.isEmpty())
    }

    @Test
    fun tracksAndMorePageStayInTheSection() {
        val sections = JSONArray(
            """[{"title":"Recently Played","tracks":[{"id":"t1","name":"A"},{"id":"t2","name":"B"}],"morePage":"recentlyPlayed"}]""",
        )
        val group = groupsFromSections(sections, "Home", caps, { "$it" }, "See more") { _, _ -> }.single()
        assertEquals(listOf("t1", "t2"), group.tracks.map { it.id })
        val more = group.rows.single()
        assertEquals("See more", more.title)
        assertEquals("", more.subtitle)
        assertEquals("recentlyPlayed", (more.open as Open.Page).page)
    }

    @Test
    fun morePageBesideAlbumsIsSeeMore() {
        val sections = JSONArray(
            """[{"title":"Recently Added","albums":[{"id":"a","name":"Al"}],"morePage":"recentlyAdded"}]""",
        )
        val rows = groupsFromSections(sections, "Home", caps, { "$it" }, "See more") { _, _ -> }.single().rows
        assertTrue(rows[0].open is Open.Album)
        assertEquals("See more", rows[1].title)
        assertEquals("recentlyAdded", (rows[1].open as Open.Page).page)
    }

    @Test
    fun morePageAloneKeepsTheSectionTitle() {
        val sections = JSONArray("""[{"title":"Artists","subtitle":"All of them","morePage":"artists"}]""")
        val row = groupsFromSections(sections, "Home", caps, { "$it" }, "See more") { _, _ -> }.single().rows.single()
        assertEquals("Artists", row.title)
        assertEquals("All of them", row.subtitle)
        assertEquals("artists", (row.open as Open.Page).page)
    }

    @Test
    fun playlistsSplitMineAndSavedAndChartsStayPlain() {
        val lists = JSONArray(
            """[{"id":"p1","name":"Favourites","mine":true,"fixed":true},{"id":"p2","name":"Saved","saved":true},{"id":"chart:1","name":"Hot"}]""",
        )
        val groups = groupsFromPlaylists(lists, "Mine", "Saved", caps) { "$it" }
        assertEquals("Mine", groups[0].title)
        assertEquals(true, groups[0].counted)
        assertEquals(false, groups[0].create)
        assertEquals(false, groups[0].rows.single().menu.rename)
        assertEquals(false, groups[0].rows.single().menu.save)
        assertEquals("Saved", groups[1].title)
        assertEquals(false, groups[1].counted)
        assertEquals(true, groups[1].rows[0].menu.unsave)
        assertEquals(false, groups[1].rows[0].menu.save)
        val hot = groups[1].rows[1]
        assertEquals("chart:1", (hot.open as Open.Playlist).id)
        assertEquals(false, hot.menu.rename)
        assertEquals(false, hot.menu.delete)
        assertEquals(true, hot.menu.save)
        assertEquals(false, hot.menu.unsave)
    }

    @Test
    fun emptyMineStaysOnlyWhenCreating() {
        val kept = groupsFromPlaylists(JSONArray("[]"), "Mine", "Saved", caps.copy(create = true)) { "$it" }
        assertEquals(1, kept.size)
        assertEquals(true, kept[0].counted)
        assertEquals(true, kept[0].create)
        assertTrue(kept[0].rows.isEmpty())
        assertTrue(groupsFromPlaylists(JSONArray("[]"), "Mine", "Saved", caps) { "$it" }.isEmpty())
    }

    @Test
    fun trackWritesFollowThePlaylistAndTheExports() {
        val exports = setOf("addToPlaylist", "removeFromPlaylist", "movePlaylistTrack")
        val own = trackPageFlags(playlist = true, mine = true, fixed = false, exports)
        assertEquals(TrackWrites(add = true, remove = true, move = true), own)
        val fixed = trackPageFlags(playlist = true, mine = true, fixed = true, exports)
        assertEquals(true, fixed.add)
        assertEquals(false, fixed.remove)
        assertEquals(false, fixed.move)
        val daily = trackPageFlags(playlist = false, mine = false, fixed = false, exports)
        assertEquals(TrackWrites(add = true), daily)
        val missing = trackPageFlags(playlist = true, mine = true, fixed = false, emptySet())
        assertEquals(TrackWrites(), missing)
    }

    @Test
    fun writablePlaylistsAreOwnAndUnfixed() {
        val lists = JSONArray(
            """[{"id":"a","name":"Plain"},{"id":"b","name":"Liked","mine":true,"fixed":true},{"id":"c","name":"Road","mine":true},{"id":"d","name":"Other","saved":true}]""",
        )
        assertEquals(listOf("c"), writablePlaylistChoices(lists).map { it.id })
        assertTrue(writablePlaylistChoices(null).isEmpty())
    }

    @Test
    fun moveKeepsTheDestinationIndexFromBeforeTheMove() {
        assertEquals(listOf("b", "c", "a", "d"), moveItem(listOf("a", "b", "c", "d"), 0, 2))
        assertEquals(listOf("a", "d", "b", "c"), moveItem(listOf("a", "b", "c", "d"), 3, 1))
        assertEquals(listOf("a", "b"), moveItem(listOf("a", "b"), 0, 0))
    }

    @Test
    fun signInKeepsRowsAlreadyListed() {
        val groups = listOf(PluginGroup("Radio", listOf(PluginRow("Radio", "", null, Open.StationList, RowMenu()))))
        val account = PluginPage.Account("setup", "Fill in the server")
        val page = rootPage(groups, account, PluginPage.Message("failed"), PluginPage.Message("empty"))
        val shown = page as PluginPage.Groups
        assertEquals(groups, shown.groups)
        assertEquals(account, shown.account)
    }

    @Test
    fun failureStaysWhenNothingWasListed() {
        val failure = PluginPage.Message("offline")
        assertEquals(failure, rootPage(emptyList(), null, failure, PluginPage.Message("empty")))
    }

    @Test
    fun signInWithoutRowsStaysAccount() {
        val account = PluginPage.Account("signIn", "请先扫码登录网易云音乐")
        val page = rootPage(emptyList(), account, PluginPage.Message("failed"), PluginPage.Message("empty"))
        assertEquals(account, page)
    }

    @Test
    fun pluginTabIdStaysOffResourceIds() {
        val first = pluginTabId("netease")
        assertEquals(first, pluginTabId("netease"))
        assertTrue(first and PLUGIN_TAB_MARK != 0L)
        assertTrue(first != pluginTabId("qqmusic"))
        assertTrue(pluginTabId("demo") != 0x7f0a0123L)
    }
}
