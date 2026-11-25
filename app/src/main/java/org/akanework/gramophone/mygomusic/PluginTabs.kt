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

/** Bit 62. Resource ids used by the local tabs fit in 32 bits. */
internal const val PLUGIN_TAB_MARK = 1L shl 62

/**
 * Stable pager id for one plugin. Cleared from [containsItem] once that plugin
 * is no longer installed, so the pager drops its fragment.
 */
internal fun pluginTabId(pluginId: String): Long {
    var hash = 0xcbf29ce484222325u
    for (b in pluginId.toByteArray(Charsets.UTF_8)) {
        hash = hash xor b.toULong()
        hash *= 0x100000001b3u
    }
    return PLUGIN_TAB_MARK or (hash.toLong() and (PLUGIN_TAB_MARK - 1))
}
