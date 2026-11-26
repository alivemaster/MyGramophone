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
import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.ConfigurationCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

/**
 * Text field from the playlist name dialog. The title, buttons and keyboard follow that dialog.
 * [onBind] runs once the dialog is showing, so it can replace the positive button's click listener.
 * Until then, that button only closes the dialog.
 */
internal fun pluginTextDialog(
    context: Context,
    title: CharSequence,
    hint: CharSequence,
    initial: String,
    password: Boolean = false,
    positive: Int = android.R.string.ok,
    message: CharSequence? = null,
    onBind: (AlertDialog, TextInputEditText, TextInputLayout) -> Unit = { _, _, _ -> },
): AlertDialog {
    val form = LayoutInflater.from(context).inflate(R.layout.dialog_new_playlist, null)
    val field = form.findViewById<TextInputEditText>(R.id.editText)
    val box = form.findViewById<TextInputLayout>(R.id.inputLayout)
    if (password) {
        field.setSingleLine(true)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    box.hint = hint
    field.setText(initial)
    field.setSelection(field.text?.length ?: 0)
    field.imeOptions = EditorInfo.IME_ACTION_DONE
    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setView(form)
        .setPositiveButton(positive, null)
        .setNegativeButton(android.R.string.cancel, null)
    if (!message.isNullOrEmpty()) builder.setMessage(message)
    val dialog = builder.create()
    field.setOnEditorActionListener { _, actionId, _ ->
        if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        if (button.isEnabled) button.performClick()
        true
    }
    dialog.setOnShowListener {
        val window = dialog.window
        if (window != null) {
            // Same fix as the playlist name dialog: a short screen will not take input without it.
            window.decorView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, window.decorView.measuredHeight)
            field.requestFocus()
            field.post {
                if (ViewCompat.getRootWindowInsets(window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) != true
                ) {
                    WindowInsetsControllerCompat(window, field).show(WindowInsetsCompat.Type.ime())
                }
            }
        }
        onBind(dialog, field, box)
    }
    dialog.show()
    return dialog
}

/** Sign-in and setup open the plugin settings. Anything else is the plugin's message. */
internal fun reportPluginCall(fragment: Fragment, pluginId: String, error: Exception) {
    val call = error as? PluginCallException
    if (call != null && (call.code == "signIn" || call.code == "setup")) {
        if (fragment.view != null) {
            showPluginAccountDialog(
                fragment.requireActivity(),
                pluginId,
                PluginPage.Account(call.code, call.message?.trim().orEmpty()),
            )
        }
        return
    }
    val text = error.message?.ifBlank { null } ?: fragment.getString(R.string.mygomusic_plugin_failed)
    Toast.makeText(fragment.requireContext(), text, Toast.LENGTH_LONG).show()
}

/**
 * Name field from the local playlist dialog. The hint stays on the plugin wording.
 */
internal fun Fragment.pluginPlaylistNameDialog(
    title: Int,
    initial: String,
    positive: Int = android.R.string.ok,
    onOk: (String) -> Unit,
) {
    pluginTextDialog(
        requireContext(),
        title = getString(title),
        hint = getString(R.string.mygomusic_playlist_name),
        initial = initial,
        positive = positive,
    ) { dialog, field, _ ->
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = field.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            onOk(name)
        }
    }
}

/**
 * Asks for a name, creates the playlist, and adds [trackId] when the plugin returned an id.
 */
internal fun Fragment.createPluginPlaylist(
    pluginId: String,
    library: PluginLibrary,
    trackId: String? = null,
    onDone: (String) -> Unit,
) {
    pluginPlaylistNameDialog(R.string.mygomusic_new_playlist, "", R.string.mygomusic_create) { name ->
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val id = library.create(pluginId, name)
                if (!trackId.isNullOrEmpty() && id.isNotEmpty()) {
                    library.addTracks(pluginId, id, listOf(trackId))
                }
                if (view != null) onDone(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (view != null) reportPluginCall(this@createPluginPlaylist, pluginId, e)
            }
        }
    }
}

/**
 * Same list dialog as adding a song to a local playlist: title, playlist icon, names, and cancel.
 * The last row creates a playlist, and only when the plugin exports `createPlaylist`, same as the plus.
 */
internal fun Fragment.choosePluginPlaylist(
    pluginId: String,
    library: PluginLibrary,
    busy: (Boolean) -> Unit,
    onNew: () -> Unit,
    onId: (String) -> Unit,
) {
    viewLifecycleOwner.lifecycleScope.launch {
        busy(true)
        try {
            val picker = library.writablePlaylists(pluginId)
            if (view == null) return@launch
            val items = picker.choices.map { it.name }.toMutableList()
            if (picker.create) items += getString(R.string.mygomusic_new_playlist_item)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mygomusic_add_to_playlist)
                .setIcon(R.drawable.ic_playlist_play)
                .setItems(items.toTypedArray()) { _, which ->
                    if (picker.create && which == picker.choices.size) onNew()
                    else picker.choices.getOrNull(which)?.let { onId(it.id) }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (view != null) reportPluginCall(this@choosePluginPlaylist, pluginId, e)
        } finally {
            if (view != null) busy(false)
        }
    }
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
