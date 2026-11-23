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

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.dpToPx
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingsActivity

/** Absent means off. Callers must pass false as the default. */
object MygomusicPrefs {
    const val ENABLED = "mygomusic_plugins"
}

class PluginListActivity : BaseSettingsActivity(
    R.string.mygomusic_plugin_settings,
    { PluginListFragment() })

class PluginListFragment : BasePreferenceFragment() {
    private var addButton: FloatingActionButton? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_mygomusic_plugins, rootKey)
        check(findPreference<SwitchPreferenceCompat>(MygomusicPrefs.ENABLED) != null)
        requireNotNull(findPreference<Preference>("mygomusic_plugin_list_empty")).apply {
            isSelectable = false
            isPersistent = false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val coordinator = requireActivity().findViewById<View>(R.id.appbarlayout).parent as ViewGroup
        val fab = layoutInflater.inflate(
            R.layout.mygomusic_plugin_add, coordinator, false
        ) as FloatingActionButton
        coordinator.addView(fab)
        val baseMargin = 16.dpToPx(fab.context)
        ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val start = if (v.layoutDirection == View.LAYOUT_DIRECTION_RTL) bars.right else bars.left
            val end = if (v.layoutDirection == View.LAYOUT_DIRECTION_RTL) bars.left else bars.right
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = baseMargin + start
                marginEnd = baseMargin + end
                bottomMargin = baseMargin + bars.bottom
            }
            insets
        }
        ViewCompat.requestApplyInsets(fab)
        fab.setOnClickListener { showInstallMenu(it) }
        addButton = fab
        syncAddButton()
    }

    override fun onDestroyView() {
        addButton?.let { button ->
            button.setOnClickListener(null)
            (button.parent as? ViewGroup)?.removeView(button)
        }
        addButton = null
        super.onDestroyView()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        if (key == MygomusicPrefs.ENABLED) syncAddButton()
    }

    private fun syncAddButton() {
        val fab = addButton ?: return
        val enabled = findPreference<SwitchPreferenceCompat>(MygomusicPrefs.ENABLED)?.isChecked == true
        if (enabled) fab.show() else fab.hide()
    }

    private fun showInstallMenu(anchor: View) {
        PopupMenu(anchor.context, anchor).apply {
            menuInflater.inflate(R.menu.mygomusic_plugin_add, menu)
            setOnMenuItemClickListener { true }
            show()
        }
    }
}
