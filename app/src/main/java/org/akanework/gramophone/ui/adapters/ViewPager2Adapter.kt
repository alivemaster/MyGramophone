/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.ui.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.allowDiskAccessInStrictMode
import org.akanework.gramophone.logic.getStringStrict
import org.akanework.gramophone.logic.hasImprovedMediaStore
import org.akanework.gramophone.mygomusic.PLUGIN_TAB_MARK
import org.akanework.gramophone.mygomusic.PluginPlaylistFragment
import org.akanework.gramophone.mygomusic.PluginStore
import org.akanework.gramophone.mygomusic.pluginTabId
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.fragments.AdapterFragment
import java.util.Locale

/**
 * This is the ViewPager2 adapter.
 */
class ViewPager2Adapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle,
    private val context: Context,
    private val viewPager2: ViewPager2
) : FragmentStateAdapter(fragmentManager, lifecycle),
    SharedPreferences.OnSharedPreferenceChangeListener, DefaultLifecycleObserver {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private var tabs = mapSettingToTabList(prefs.getStringStrict("tabs", "")!!)
    private var pluginTabs = readPluginTabs()

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
        lifecycle.addObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "tabs") {
            apply(mapSettingToTabList(prefs.getStringStrict("tabs", "")!!), readPluginTabs())
        }
    }

    /** Installed plugins are not stored in the tab-order setting. */
    @SuppressLint("NotifyDataSetChanged")
    fun refreshPlugins() {
        val next = readPluginTabs()
        if (next == pluginTabs) return
        apply(null, next)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun apply(newTabs: List<Tab?>?, newPlugins: List<PluginTab>?) {
        val current = if (itemCount > 0 && viewPager2.currentItem in 0 until itemCount) {
            getItemId(viewPager2.currentItem)
        } else {
            null
        }
        if (newTabs != null) tabs = newTabs
        if (newPlugins != null) pluginTabs = newPlugins
        viewPager2.adapter!!.notifyDataSetChanged()
        if (current != null) {
            val index = (0 until itemCount).indexOfFirst { getItemId(it) == current }
            if (index >= 0) viewPager2.setCurrentItem(index, false)
        }
        updateTabVisibility()
    }

    fun label(position: Int): String {
        val local = visibleLocalCount()
        return if (position < local) context.getString(tabs[position]!!.label)
        else pluginTabs[position - local].title
    }

    override fun getItemCount() = visibleLocalCount() + pluginTabs.size

    override fun createFragment(position: Int): Fragment {
        val local = visibleLocalCount()
        if (position < local) {
            return AdapterFragment().apply {
                arguments = Bundle().apply {
                    putInt("ID", tabs[position]!!.id)
                }
            }
        }
        return PluginPlaylistFragment().apply {
            arguments = Bundle().apply {
                putString(PluginPlaylistFragment.ARG_PLUGIN, pluginTabs[position - local].id)
            }
        }
    }

    override fun getItemId(position: Int): Long {
        val local = visibleLocalCount()
        return if (position < local) tabs[position]!!.id.toLong()
        else pluginTabId(pluginTabs[position - local].id)
    }

    override fun containsItem(itemId: Long): Boolean {
        if (itemId and PLUGIN_TAB_MARK != 0L) return pluginTabs.any { pluginTabId(it.id) == itemId }
        return tabs.any { it?.id?.toLong() == itemId }
    }

    private fun visibleLocalCount(): Int {
        val index = tabs.indexOf(null)
        if (index == -1) throw IllegalStateException("indexOf null is -1 in tab list?")
        return index
    }

    private fun updateTabVisibility() {
        val tabLayout = (context as? MainActivity)?.findViewById<TabLayout>(R.id.tab_layout)
        if (itemCount < 2) {
            tabLayout?.visibility = View.GONE
            viewPager2.isUserInputEnabled = false
        } else {
            tabLayout?.visibility = View.VISIBLE
            viewPager2.isUserInputEnabled = true
        }
    }

    private fun readPluginTabs(): List<PluginTab> {
        // Listing the plugin directory is a disk read. The pager has to update on the main thread.
        return allowDiskAccessInStrictMode {
            try {
                PluginStore(context).list(uiLocale()).filter { !it.broken && it.enabled }
                    .map { PluginTab(it.directoryName, it.title) }
            } catch (e: Exception) {
                Log.w("ViewPager2Adapter", "plugins", e)
                emptyList()
            }
        }
    }

    private fun uiLocale(): Locale {
        val config = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0]
        } else {
            @Suppress("DEPRECATION")
            config.locale
        }
    }

    private data class PluginTab(val id: String, val title: String)

    companion object {
        enum class Tab(val id: Int, val label: Int) {
            // Do not rename entries here, names are written to disk. Order is default tab order
            Songs(R.id.songs, R.string.category_songs),
            Albums(R.id.albums, R.string.category_albums),
            Artists(R.id.artists, R.string.category_artists),
            Genres(R.id.genres, R.string.category_genres),
            Dates(R.id.dates, R.string.category_dates),
            Folders(R.id.folders, R.string.folders),
            FileSystem(R.id.detailed_folders, R.string.filesystem),
            Playlist(R.id.playlists, R.string.category_playlists)
        }

        fun mapSettingToTabList(setting: String): List<Tab?> {
            val stList = if (!setting.isEmpty())
                setting.split(",").flatMap {
                    if (it.isEmpty())
                        listOf(null)
                    else
                        try {
                            val t = Tab.valueOf(it)
                            if (!hasImprovedMediaStore() && t == Tab.Genres)
                                listOf() else listOf(t)
                        } catch (_: IllegalArgumentException) {
                            listOf() // this tab was removed
                        }
                }.toMutableList()
            else mutableListOf()
            Tab.entries.forEach {
                if (stList.indexOf(it) != stList.lastIndexOf(it))
                    stList.removeAll { i -> i == it }
                if (!stList.contains(it) && (it != Tab.Genres || hasImprovedMediaStore()))
                    stList.add(it)
            }
            if (!stList.contains(null))
                stList.add(null)
            return stList
        }

        fun mapTabListToSetting(tabList: List<Tab?>) = tabList.joinToString(",") { it?.name ?: "" }
    }
}
