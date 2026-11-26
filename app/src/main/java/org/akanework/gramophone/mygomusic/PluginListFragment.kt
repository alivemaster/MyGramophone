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
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.allowDiskAccessInStrictMode
import org.akanework.gramophone.logic.dpToPx
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingsActivity
import java.util.Locale

class PluginListActivity : BaseSettingsActivity(
    R.string.mygomusic_plugin_settings,
    { PluginListFragment() })

class PluginListFragment : BasePreferenceFragment() {
    private val model: PluginListViewModel by viewModels()
    private var addButton: FloatingActionButton? = null
    private var progressDialog: AlertDialog? = null
    private var confirmDialog: AlertDialog? = null
    private var failureDialog: AlertDialog? = null
    private var githubDialog: AlertDialog? = null
    private var rendered: List<InstalledPlugin>? = null
    private var sawFailure: PluginPackageException? = null

    private val openZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) model.stageZip(uri)
    }
    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) model.stageTree(uri)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_mygomusic_plugins, rootKey)
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
        fab.setOnClickListener { showAddChoices() }
        fab.show()
        addButton = fab
        model.ui.observe(viewLifecycleOwner) { render(it) }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onDestroyView() {
        progressDialog?.dismiss()
        progressDialog = null
        confirmDialog?.setOnCancelListener(null)
        confirmDialog?.dismiss()
        confirmDialog = null
        failureDialog?.setOnCancelListener(null)
        failureDialog?.dismiss()
        failureDialog = null
        githubDialog?.setOnDismissListener(null)
        githubDialog?.dismiss()
        githubDialog = null
        rendered = null
        addButton?.let { button ->
            button.setOnClickListener(null)
            (button.parent as? ViewGroup)?.removeView(button)
        }
        addButton = null
        super.onDestroyView()
    }

    private fun render(ui: PluginListUi) {
        if (ui.failure != null && ui.failure !== sawFailure) rendered = null
        sawFailure = ui.failure
        renderList(ui.plugins)
        if (ui.busy) {
            if (progressDialog?.isShowing != true) {
                progressDialog = MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.mygomusic_plugin_working)
                    .setView(R.layout.mygomusic_plugin_working)
                    .setCancelable(false)
                    .show()
            }
        } else {
            progressDialog?.dismiss()
            progressDialog = null
        }
        val manifest = ui.confirm
        if (manifest == null) {
            confirmDialog?.setOnCancelListener(null)
            confirmDialog?.dismiss()
            confirmDialog = null
        } else if (confirmDialog?.isShowing != true) {
            confirmDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mygomusic_plugin_install_title)
                .setMessage(pluginInstallMessage(requireContext(), manifest, ui.confirmRepo))
                .setPositiveButton(R.string.mygomusic_plugin_install) { _, _ -> model.confirmInstall() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> model.discardPending() }
                .setOnCancelListener { model.discardPending() }
                .show()
        }
        val failure = ui.failure
        if (failure == null) {
            failureDialog?.setOnCancelListener(null)
            failureDialog?.dismiss()
            failureDialog = null
        } else if (failureDialog?.isShowing != true) {
            failureDialog = MaterialAlertDialogBuilder(requireContext())
                .setMessage(pluginFailureMessage(requireContext(), failure))
                .setPositiveButton(android.R.string.ok) { _, _ -> model.clearFailure() }
                .setOnCancelListener { model.clearFailure() }
                .show()
        }
    }

    private fun renderList(plugins: List<InstalledPlugin>) {
        if (rendered == plugins) return
        rendered = plugins
        val category = findPreference<PreferenceCategory>("mygomusic_plugin_list") ?: return
        val empty = findPreference<Preference>("mygomusic_plugin_list_empty") ?: return
        empty.isVisible = plugins.isEmpty()
        val stale = buildList {
            for (i in 0 until category.preferenceCount) {
                val preference = category.getPreference(i)
                if (preference.key.startsWith(ITEM_PREFIX)) add(preference)
            }
        }
        stale.forEach { category.removePreference(it) }
        for (plugin in plugins) {
            category.addPreference(InstalledPluginPreference(requireContext()).apply {
                key = ITEM_PREFIX + plugin.directoryName
                title = plugin.title
                summary = when {
                    plugin.broken -> getString(R.string.mygomusic_plugin_error_manifest)
                    plugin.version.isEmpty() -> getString(R.string.mygomusic_plugin_version_unknown)
                    else -> plugin.version
                }
                isPersistent = false
                isIconSpaceReserved = false
                enabledPlugin = plugin.enabled
                onToggle = { on -> model.setEnabled(plugin.directoryName, on) }
                onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    startActivity(Intent(requireContext(), PluginSettingsActivity::class.java).apply {
                        putExtra(PluginSettingsActivity.EXTRA_ID, plugin.directoryName)
                        putExtra(PluginSettingsActivity.EXTRA_TITLE, plugin.title)
                    })
                    true
                }
            })
        }
    }

    private fun showAddChoices() {
        MaterialAlertDialogBuilder(requireContext())
            .setItems(
                arrayOf(
                    getString(R.string.mygomusic_plugin_add_github),
                    getString(R.string.mygomusic_plugin_pick_folder),
                    getString(R.string.mygomusic_plugin_pick_zip),
                )
            ) { _, which ->
                when (which) {
                    0 -> askGithub()
                    1 -> allowDiskAccessInStrictMode { openTree.launch(null) }
                    else -> allowDiskAccessInStrictMode { openZip.launch(arrayOf("*/*")) }
                }
            }
            .show()
    }

    private fun askGithub() {
        if (model.ui.value?.busy == true) return
        val form = layoutInflater.inflate(R.layout.mygomusic_plugin_github, null)
        val field = form.findViewById<TextInputEditText>(R.id.editText)
        val box = form.findViewById<TextInputLayout>(R.id.inputLayout)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mygomusic_plugin_github_title)
            .setMessage(R.string.mygomusic_plugin_github_hint)
            .setView(form)
            .setPositiveButton(R.string.mygomusic_plugin_install, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.isEnabled = false
            field.addTextChangedListener(afterTextChanged = {
                box.error = null
                button.isEnabled = !it.isNullOrBlank()
            })
            val submit = {
                try {
                    val repo = parsePluginRepo(field.text?.toString().orEmpty())
                    dialog.dismiss()
                    model.stageGithub(repo)
                } catch (e: PluginPackageException) {
                    box.error = pluginFailureMessage(requireContext(), e)
                }
            }
            button.setOnClickListener { submit() }
            field.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE && button.isEnabled) {
                    submit()
                    true
                } else {
                    false
                }
            }
        }
        dialog.setOnDismissListener {
            if (githubDialog === dialog) githubDialog = null
        }
        githubDialog = dialog
        dialog.show()
    }

    private companion object {
        const val ITEM_PREFIX = "mygomusic_installed_"
    }
}

internal fun pluginInstallMessage(context: Context, manifest: PluginManifest, repo: String): String {
    val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
    return buildString {
        append(manifest.title(locale))
        if (manifest.version.isNotEmpty()) {
            append('\n')
            append(context.getString(R.string.mygomusic_plugin_install_version, manifest.version))
        }
        if (repo.isNotEmpty()) {
            append('\n')
            append(context.getString(R.string.mygomusic_plugin_install_from, repo))
        }
        append("\n\n")
        when {
            manifest.network.isEmpty() ->
                append(context.getString(R.string.mygomusic_plugin_permission_network_none))
            manifest.network.any { it == "*" } ->
                append(context.getString(R.string.mygomusic_plugin_permission_network_any))
            else -> {
                append(context.getString(R.string.mygomusic_plugin_permission_network))
                append('\n')
                append(manifest.network.joinToString("\n"))
            }
        }
        if (manifest.process.isNotEmpty()) {
            append("\n\n")
            if (manifest.process.any { it == "*" }) {
                append(context.getString(R.string.mygomusic_plugin_permission_process_any))
            } else {
                append(context.getString(R.string.mygomusic_plugin_permission_process))
                append('\n')
                append(manifest.process.joinToString("\n"))
            }
        }
    }
}

internal fun pluginFailureMessage(context: Context, error: PluginPackageException): String {
    return when (error.kind) {
        PluginFailureKind.NotAPlugin -> context.getString(R.string.mygomusic_plugin_error_not_plugin)
        PluginFailureKind.BadId -> context.getString(R.string.mygomusic_plugin_error_bad_id)
        PluginFailureKind.MainNotCompiled -> context.getString(R.string.mygomusic_plugin_error_main)
        PluginFailureKind.ZipSlip -> context.getString(R.string.mygomusic_plugin_error_zip_slip)
        PluginFailureKind.Link -> context.getString(R.string.mygomusic_plugin_error_link)
        PluginFailureKind.TooLarge -> context.getString(R.string.mygomusic_plugin_error_too_large)
        PluginFailureKind.TooManyFiles -> context.getString(R.string.mygomusic_plugin_error_too_many)
        PluginFailureKind.BadManifest -> context.getString(R.string.mygomusic_plugin_error_manifest)
        PluginFailureKind.AlreadyInstalled ->
            context.getString(R.string.mygomusic_plugin_error_installed, error.detail ?: "")
        PluginFailureKind.Io -> context.getString(R.string.mygomusic_plugin_error_io)
        PluginFailureKind.BadRepo -> context.getString(R.string.mygomusic_plugin_error_repo)
        PluginFailureKind.NoRepo -> context.getString(R.string.mygomusic_plugin_error_no_repo)
        PluginFailureKind.RateLimit -> context.getString(R.string.mygomusic_plugin_error_rate)
        PluginFailureKind.Github -> context.getString(R.string.mygomusic_plugin_error_github)
    }
}

private class InstalledPluginPreference(context: android.content.Context) : Preference(context) {
    var enabledPlugin: Boolean = true
    var onToggle: ((Boolean) -> Unit)? = null

    init {
        layoutResource = R.layout.preference_switch
        widgetLayoutResource = R.layout.preference_switch_widget
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val sw = holder.findViewById(androidx.preference.R.id.switchWidget) as? CompoundButton ?: return
        // The row opens this plugin. The switch is its own control, so it takes the click.
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = enabledPlugin
        sw.isClickable = true
        sw.isFocusable = true
        sw.contentDescription = sw.context.getString(R.string.mygomusic_plugin_enabled)
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked != enabledPlugin) onToggle?.invoke(checked)
        }
    }
}
