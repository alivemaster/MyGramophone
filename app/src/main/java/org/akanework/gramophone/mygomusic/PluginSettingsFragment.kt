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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.ConfigurationCompat
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.viewModels
import androidx.preference.DropDownPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingsActivity

class PluginSettingsActivity : BaseSettingsActivity(
    R.string.mygomusic_plugin_settings,
    { PluginSettingsFragment() },
) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotEmpty() }?.let { title ->
            findViewById<com.google.android.material.appbar.CollapsingToolbarLayout>(R.id.collapsingtoolbar)
                .title = title
        }
    }

    companion object {
        const val EXTRA_ID = "pluginId"
        const val EXTRA_TITLE = "pluginTitle"
    }
}

class PluginSettingsFragment : BasePreferenceFragment() {
    private val model: PluginSettingsViewModel by viewModels()
    private var built = false
    private var finished = false
    private val current = HashMap<String, String>()
    private val controls = HashMap<String, Preference>()
    private var applying = false
    private var specs: List<PluginSetting> = emptyList()
    private var progressDialog: AlertDialog? = null
    private var ioDialog: AlertDialog? = null
    private var saveDialog: AlertDialog? = null
    private var deleteDialog: AlertDialog? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        attachMenu()
        model.ui.observe(viewLifecycleOwner) { render(it) }
        model.load(requireActivity().intent.getStringExtra(PluginSettingsActivity.EXTRA_ID).orEmpty())
    }

    override fun onDestroyView() {
        progressDialog?.dismiss()
        progressDialog = null
        ioDialog?.setOnCancelListener(null)
        ioDialog?.dismiss()
        ioDialog = null
        saveDialog?.dismiss()
        saveDialog = null
        deleteDialog?.dismiss()
        deleteDialog = null
        activity?.findViewById<MaterialToolbar>(R.id.topAppBar)?.let { bar ->
            bar.setOnMenuItemClickListener(null)
            bar.menu.clear()
        }
        super.onDestroyView()
    }

    private fun render(ui: PluginSettingsUi) {
        if (ui.missing || ui.deleted) {
            if (!finished) {
                finished = true
                requireActivity().finish()
            }
            return
        }
        if (ui.ready && !built) {
            built = true
            build(ui)
        }
        controls.values.forEach { it.isEnabled = !ui.saving && ui.saveFailure == null }
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
        if (ui.failure) showIoFailure() else dismissIo()
        val save = ui.saveFailure
        if (save == null) dismissSave() else showSaveFailure(save)
        syncAccount(ui)
    }

    private fun build(ui: PluginSettingsUi) {
        val screen = preferenceScreen
        screen.removeAll()
        controls.clear()
        current.clear()
        specs = ui.settings
        screen.addPreference(enableSwitch(ui.enabled).apply { order = 0 })
        var position = 2
        if (ui.broken) {
            screen.addPreference(note("manifest", getString(R.string.mygomusic_plugin_error_manifest)))
            return
        }
        for (setting in ui.settings) {
            val stored = ui.values[setting.key].orEmpty()
            current[setting.key] = stored
            val control = control(setting, stored)
            controls[setting.key] = control
            control.order = position++
            screen.addPreference(control)
            if (pluginSettingKind(setting, stored) != PluginSettingKind.Switch && setting.help.isNotBlank()) {
                val help = note("h:" + setting.key, setting.help)
                help.order = position++
                screen.addPreference(help)
            }
            val link = httpLink(setting.link)
            if (link != null) {
                val row = linkRow(setting.key, link)
                row.order = position++
                screen.addPreference(row)
            }
        }
        val reach = note("reach", reachText(ui.network))
        reach.order = position
        screen.addPreference(reach)
    }

    private fun syncAccount(ui: PluginSettingsUi) {
        if (!built || !ui.sessionReady) return
        val screen = preferenceScreen
        val existing = findPreference<Preference>(ACCOUNT)
        if (!ui.offersSignIn) {
            if (existing != null) screen.removePreference(existing)
            return
        }
        val pref = existing ?: Preference(requireContext()).apply {
            key = ACCOUNT
            layoutResource = R.layout.preference_basic
            isPersistent = false
            isIconSpaceReserved = false
            order = 1
        }
        pref.title = getString(if (ui.signedIn) R.string.mygomusic_sign_out else R.string.mygomusic_sign_in)
        pref.summary = ui.accountName.takeIf { ui.signedIn && it.isNotBlank() }
        pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            if (ui.signedIn) model.signOut()
            true
        }
        if (existing == null) screen.addPreference(pref)
    }

    private fun reachText(network: List<String>): String {
        if (network.isEmpty()) return getString(R.string.mygomusic_plugin_permission_network_none)
        if (network.any { it == "*" }) return getString(R.string.mygomusic_plugin_reach_any)
        val language = ConfigurationCompat.getLocales(resources.configuration)[0]?.language
        val separator = if (language.equals("zh", ignoreCase = true)) "、" else ", "
        return getString(R.string.mygomusic_plugin_reach, network.joinToString(separator))
    }

    private fun enableSwitch(on: Boolean): SwitchPreferenceCompat {
        return SwitchPreferenceCompat(requireContext()).apply {
            key = ENABLE
            layoutResource = R.layout.preference_switch
            widgetLayoutResource = R.layout.preference_switch_widget
            isPersistent = false
            isIconSpaceReserved = false
            title = getString(R.string.mygomusic_plugin_enabled)
            isChecked = on
            setOnPreferenceChangeListener { _, newValue ->
                model.setEnabled(newValue == true)
                true
            }
        }
    }

    private fun control(setting: PluginSetting, stored: String): Preference {
        val context = requireContext()
        return when (pluginSettingKind(setting, stored)) {
            PluginSettingKind.Switch -> SwitchPreferenceCompat(context).apply {
                layoutResource = R.layout.preference_switch
                widgetLayoutResource = R.layout.preference_switch_widget
                isChecked = stored == "true"
                if (setting.help.isNotBlank()) summary = setting.help
                setOnPreferenceChangeListener { _, newValue ->
                    onValue(setting.key, if (newValue == true) "true" else "false")
                    true
                }
            }
            PluginSettingKind.Select -> DropDownPreference(context).apply {
                layoutResource = R.layout.preference_dropdown_md
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                bindSelect(this, setting, stored)
                setOnPreferenceChangeListener { _, newValue ->
                    val next = newValue as? String ?: return@setOnPreferenceChangeListener false
                    onValue(setting.key, next)
                    true
                }
            }
            PluginSettingKind.Password -> textField(setting, stored, password = true)
            PluginSettingKind.Text -> textField(setting, stored, password = false)
        }.apply {
            isPersistent = false
            isIconSpaceReserved = false
            key = "s:" + setting.key
            title = setting.label
        }
    }

    private fun textField(setting: PluginSetting, stored: String, password: Boolean): EditTextPreference {
        return EditTextPreference(requireContext()).apply {
            layoutResource = R.layout.preference_basic
            dialogTitle = setting.label
            if (password) {
                setOnBindEditTextListener { edit ->
                    edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    edit.setSingleLine(true)
                }
            } else {
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            text = stored
            setOnPreferenceChangeListener { _, newValue ->
                val next = newValue as? String ?: return@setOnPreferenceChangeListener false
                onValue(setting.key, next)
                true
            }
        }
    }

    private fun bindSelect(preference: DropDownPreference, setting: PluginSetting, stored: String) {
        val choices = selectChoices(setting.options, stored) {
            getString(R.string.mygomusic_plugin_current_value, it)
        } ?: return
        preference.entries = Array<CharSequence>(choices.first.size) { choices.first[it] }
        preference.entryValues = Array<CharSequence>(choices.second.size) { choices.second[it] }
        preference.value = stored
    }

    private fun note(key: String, text: String): Preference {
        return Preference(requireContext()).apply {
            this.key = key
            layoutResource = R.layout.preference_basic
            isPersistent = false
            isSelectable = false
            setIcon(R.drawable.ic_info)
            summary = text
        }
    }

    private fun linkRow(key: String, url: String): Preference {
        return Preference(requireContext()).apply {
            this.key = "l:$key"
            layoutResource = R.layout.preference_basic
            isPersistent = false
            isIconSpaceReserved = false
            title = getString(R.string.mygomusic_plugin_link)
            summary = url
            setOnPreferenceClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(requireContext(), R.string.no_app_found, Toast.LENGTH_LONG).show()
                }
                true
            }
        }
    }

    private fun onValue(key: String, next: String) {
        if (applying) return
        val previous = current[key].orEmpty()
        if (next == previous) return
        current[key] = next
        model.save(key, next, previous)
    }

    private fun applyValue(key: String, value: String) {
        current[key] = value
        applying = true
        try {
            applyControl(key, value)
        } finally {
            applying = false
        }
    }

    private fun applyControl(key: String, value: String) {
        when (val pref = controls[key]) {
            is SwitchPreferenceCompat -> pref.isChecked = value == "true"
            is EditTextPreference -> pref.text = value
            is DropDownPreference -> {
                val setting = specs.firstOrNull { it.key == key } ?: return
                bindSelect(pref, setting, value)
            }
        }
    }

    private fun showSaveFailure(failure: SettingSaveFailure) {
        if (saveDialog?.isShowing == true) return
        if (!failure.stored) applyValue(failure.key, failure.previous)
        else applyValue(failure.key, failure.attempted)
        val text = failure.message.ifBlank { getString(R.string.mygomusic_plugin_setting_failed) }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setMessage(text)
            .setPositiveButton(R.string.mygomusic_plugin_retry) { _, _ ->
                applyValue(failure.key, failure.attempted)
                model.save(failure.key, failure.attempted, failure.previous)
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                applyValue(failure.key, failure.previous)
                if (failure.stored) model.save(failure.key, failure.previous, failure.attempted)
                else model.clearSaveFailure()
            }
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener {
            if (saveDialog === dialog) saveDialog = null
        }
        saveDialog = dialog
        dialog.show()
    }

    private fun dismissSave() {
        saveDialog?.dismiss()
        saveDialog = null
    }

    private fun showIoFailure() {
        if (ioDialog?.isShowing == true) return
        val enabled = model.ui.value?.enabled ?: true
        findPreference<SwitchPreferenceCompat>(ENABLE)?.let { if (it.isChecked != enabled) it.isChecked = enabled }
        ioDialog = MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.mygomusic_plugin_error_io)
            .setPositiveButton(android.R.string.ok) { _, _ -> model.clearFailure() }
            .setOnCancelListener { model.clearFailure() }
            .show()
    }

    private fun dismissIo() {
        ioDialog?.setOnCancelListener(null)
        ioDialog?.dismiss()
        ioDialog = null
    }

    private fun attachMenu() {
        val bar = requireActivity().findViewById<MaterialToolbar>(R.id.topAppBar) ?: return
        bar.menu.clear()
        bar.overflowIcon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_more_vert_alt_topappbar)
        bar.inflateMenu(R.menu.mygomusic_plugin_item)
        bar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.mygomusic_plugin_delete) confirmDelete()
            true
        }
    }

    private fun confirmDelete() {
        if (deleteDialog?.isShowing == true) return
        val title = model.ui.value?.title?.ifEmpty { null }
            ?: requireActivity().intent.getStringExtra(PluginSettingsActivity.EXTRA_TITLE).orEmpty()
        val form = layoutInflater.inflate(R.layout.mygomusic_plugin_delete_data, null)
        form.findViewById<TextView>(R.id.mygomusic_plugin_delete_message).text =
            getString(R.string.mygomusic_plugin_delete_message, title)
        val check = form.findViewById<CheckBox>(R.id.mygomusic_plugin_delete_data)
        deleteDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mygomusic_plugin_delete_title)
            .setView(form)
            .setPositiveButton(R.string.delete) { _, _ -> model.delete(check.isChecked) }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                if (deleteDialog?.isShowing != true) deleteDialog = null
            }
            .show()
    }

    private companion object {
        const val ENABLE = "enabled"
        const val ACCOUNT = "account"
    }
}
