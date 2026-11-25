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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.ui.MyRecyclerView

internal class PluginScaffold(
    val root: View,
    val refresh: SwipeRefreshLayout,
    val list: MyRecyclerView,
    val status: View,
    val message: TextView,
    val action: MaterialButton,
    val appBar: AppBarLayout?,
    val collapsing: CollapsingToolbarLayout?,
    val toolbar: MaterialToolbar?,
)

internal fun pluginScaffold(inflater: LayoutInflater, nested: Boolean): PluginScaffold {
    val context = inflater.context
    val match = ViewGroup.LayoutParams.MATCH_PARENT
    if (!nested) {
        val frame = FrameLayout(context)
        val refresh = SwipeRefreshLayout(context)
        inflater.inflate(R.layout.fragment_recyclerview, refresh, true)
        frame.addView(refresh, FrameLayout.LayoutParams(match, match))
        val status = inflater.inflate(R.layout.mygomusic_plugin_status, frame, false)
        frame.addView(status, FrameLayout.LayoutParams(match, match))
        tint(refresh)
        return scaffold(frame, refresh, refresh.findViewById(R.id.recyclerview), status, null, null, null)
    }
    val page = inflater.inflate(R.layout.fragment_general_sub, null, false) as CoordinatorLayout
    val list = page.findViewById<MyRecyclerView>(R.id.recyclerview)
    val parent = list.parent as ViewGroup
    val index = parent.indexOfChild(list)
    val params = list.layoutParams
    parent.removeView(list)
    val refresh = SwipeRefreshLayout(context)
    refresh.addView(list, ViewGroup.LayoutParams(match, match))
    parent.addView(refresh, index, params)
    val status = inflater.inflate(R.layout.mygomusic_plugin_status, page, false)
    page.addView(
        status,
        CoordinatorLayout.LayoutParams(match, match).apply {
            behavior = AppBarLayout.ScrollingViewBehavior()
        },
    )
    tint(refresh)
    return scaffold(
        page,
        refresh,
        list,
        status,
        page.findViewById(R.id.appbarlayout),
        page.findViewById(R.id.collapsingtoolbar),
        page.findViewById(R.id.topAppBar),
    )
}

private fun scaffold(
    root: View,
    refresh: SwipeRefreshLayout,
    list: MyRecyclerView,
    status: View,
    appBar: AppBarLayout?,
    collapsing: CollapsingToolbarLayout?,
    toolbar: MaterialToolbar?,
) = PluginScaffold(
    root,
    refresh,
    list,
    status,
    status.findViewById(R.id.mygomusic_status_message),
    status.findViewById(R.id.mygomusic_status_action),
    appBar,
    collapsing,
    toolbar,
)

private fun tint(refresh: SwipeRefreshLayout) {
    refresh.setColorSchemeColors(
        MaterialColors.getColor(refresh, androidx.appcompat.R.attr.colorPrimary),
    )
}
