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
    private val caps = PluginCaps(rename = true, delete = true, unsave = true)

    @Test
    fun trackSectionIsOneRowAndArtistsAreSkipped() {
        val sections = JSONArray(
            """[{"title":"Picked","tracks":[{"id":"t1","name":"Song","artists":[{"name":"A"}],"album":{"name":"Al"}}],"artists":[{"id":"a","name":"A"}]}]""",
        )
        val skipped = mutableListOf<String>()
        val groups = groupsFromSections(sections, "Home", caps, { "$it" }) { _, kind -> skipped += kind }
        assertEquals(listOf("artists"), skipped)
        assertEquals(1, groups.single().rows.size)
        assertTrue(groups.single().rows.single().open is Open.Tracks)
    }

    @Test
    fun playlistsSplitMineAndSavedAndChartsStayPlain() {
        val lists = JSONArray(
            """[{"id":"p1","name":"Favourites","mine":true,"fixed":true},{"id":"p2","name":"Saved","saved":true},{"id":"chart:1","name":"Hot"}]""",
        )
        val groups = groupsFromPlaylists(lists, "Mine", "Saved", caps) { "$it" }
        assertEquals("Mine", groups[0].title)
        assertEquals(false, groups[0].rows.single().menu.rename)
        assertEquals("Saved", groups[1].title)
        assertEquals(true, groups[1].rows[0].menu.unsave)
        val hot = groups[1].rows[1]
        assertEquals("chart:1", (hot.open as Open.Playlist).id)
        assertEquals(false, hot.menu.rename)
        assertEquals(false, hot.menu.delete)
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
