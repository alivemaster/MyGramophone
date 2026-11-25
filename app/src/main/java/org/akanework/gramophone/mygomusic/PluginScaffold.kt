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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.ConfigurationCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.allowDiskAccessInStrictMode
import org.akanework.gramophone.logic.ui.MyRecyclerView
import java.util.Locale

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
    // This library paints the disc behind the arrow as a fixed light gray.
    refresh.setProgressBackgroundColorSchemeColor(
        MaterialColors.getColor(refresh, com.google.android.material.R.attr.colorSurfaceContainerHigh),
    )
    refresh.setColorSchemeColors(
        MaterialColors.getColor(refresh, androidx.appcompat.R.attr.colorPrimary),
    )
}

private var accountDialog: AlertDialog? = null

/** Sign-in and setup stay in a dialog so the list underneath is left as it is. */
internal fun showPluginAccountDialog(
    activity: FragmentActivity,
    pluginId: String,
    account: PluginPage.Account,
) {
    if (activity.isFinishing || accountDialog?.isShowing == true) return
    val message = account.message.trim().ifEmpty {
        activity.getString(
            if (account.code == "setup") R.string.mygomusic_needs_setup else R.string.mygomusic_needs_sign_in,
        )
    }
    val action = if (account.code == "setup") R.string.mygomusic_open_settings else R.string.mygomusic_sign_in
    val dialog = MaterialAlertDialogBuilder(activity)
        .setMessage(message)
        .setPositiveButton(action) { _, _ ->
            if (!isPluginId(pluginId)) return@setPositiveButton
            activity.startActivity(Intent(activity, PluginSettingsActivity::class.java).apply {
                putExtra(PluginSettingsActivity.EXTRA_ID, pluginId)
                putExtra(PluginSettingsActivity.EXTRA_TITLE, pluginTitle(activity, pluginId))
            })
        }
        .setNegativeButton(android.R.string.cancel) { _, _ -> }
        .setCancelable(true)
        .create()
    dialog.setCanceledOnTouchOutside(true)
    var closing = false
    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            closing = true
            if (accountDialog === dialog) accountDialog = null
            dialog.dismiss()
        }
    }
    activity.lifecycle.addObserver(observer)
    dialog.setOnDismissListener {
        if (!closing) activity.lifecycle.removeObserver(observer)
        if (accountDialog === dialog) accountDialog = null
    }
    accountDialog = dialog
    dialog.show()
}

private fun pluginTitle(activity: FragmentActivity, pluginId: String): String {
    return allowDiskAccessInStrictMode {
        runCatching {
            val locale = ConfigurationCompat.getLocales(activity.resources.configuration)[0]
                ?: Locale.getDefault()
            PluginStore(activity).list(locale).firstOrNull { it.directoryName == pluginId }?.title
        }.getOrNull()
    }.orEmpty().ifEmpty { pluginId }
}
