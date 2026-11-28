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

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.viewModels
import androidx.preference.DropDownPreference
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
    private var textDialog: AlertDialog? = null
    private var deleteDialog: AlertDialog? = null
    private var promptDialog: AlertDialog? = null
    private var promptImage: ImageView? = null
    private var promptLabel: TextView? = null
    private var signInDialog: AlertDialog? = null
    private var noticeDialog: AlertDialog? = null
    private var updateErrorDialog: AlertDialog? = null
    private var updateDialog: AlertDialog? = null
    private var shownGeneration = -1

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        attachMenu()
        model.attachHost(requireActivity())
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
        textDialog?.setOnDismissListener(null)
        textDialog?.dismiss()
        textDialog = null
        deleteDialog?.dismiss()
        deleteDialog = null
        dismissPrompt()
        dismissSignInMessage()
        noticeDialog?.setOnCancelListener(null)
        noticeDialog?.dismiss()
        noticeDialog = null
        updateErrorDialog?.setOnCancelListener(null)
        updateErrorDialog?.dismiss()
        updateErrorDialog = null
        updateDialog?.setOnCancelListener(null)
        updateDialog?.dismiss()
        updateDialog = null
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
        if (ui.ready && ui.generation != shownGeneration) {
            shownGeneration = ui.generation
            built = true
            build(ui)
            if (ui.title.isNotEmpty()) {
                activity?.findViewById<com.google.android.material.appbar.CollapsingToolbarLayout>(
                    R.id.collapsingtoolbar,
                )?.title = ui.title
            }
        }
        syncUpdateItem(ui.fromGithub)
        controls.values.forEach { it.isEnabled = !ui.saving && !ui.signingIn && ui.saveFailure == null }
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
        syncPrompt(ui)
        if (ui.signInMessage == null) dismissSignInMessage() else showSignInMessage(ui.signInMessage)
        val save = ui.saveFailure
        if (save == null) dismissSave() else showSaveFailure(save)
        syncAccount(ui)
        syncNotice(ui.notice)
        syncUpdateError(ui.updateError)
        syncUpdateOffer(ui)
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
        reach.order = position++
        screen.addPreference(reach)
        val programs = processText(ui.process) ?: return
        val process = note("process", programs)
        process.order = position
        screen.addPreference(process)
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
        pref.isEnabled = !ui.saving && !ui.signingIn && ui.saveFailure == null &&
            (ui.signedIn || ui.canSignIn)
        pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            if (ui.signedIn) model.signOut() else model.signIn()
            true
        }
        if (existing == null) screen.addPreference(pref)
    }

    private fun reachText(network: List<String>): String {
        if (network.isEmpty()) return getString(R.string.mygomusic_plugin_permission_network_none)
        if (network.any { it == "*" }) return getString(R.string.mygomusic_plugin_reach_any)
        return getString(R.string.mygomusic_plugin_reach, joined(network))
    }

    private fun processText(process: List<String>): String? {
        if (process.isEmpty()) return null
        if (process.any { it == "*" }) return getString(R.string.mygomusic_plugin_permission_process_any)
        return getString(R.string.mygomusic_plugin_permission_process) + ": " + joined(process)
    }

    private fun joined(items: List<String>): String = items.joinToString(", ")

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

    private fun textField(setting: PluginSetting, stored: String, password: Boolean): Preference {
        return Preference(requireContext()).apply {
            layoutResource = R.layout.preference_basic
            if (!password) summary = textSummary(stored)
            setOnPreferenceClickListener {
                openText(setting, password)
                true
            }
        }
    }

    private fun openText(setting: PluginSetting, password: Boolean) {
        if (textDialog?.isShowing == true) return
        val dialog = pluginTextDialog(
            context = requireContext(),
            title = setting.label,
            hint = setting.label,
            initial = current[setting.key].orEmpty(),
            password = password,
        ) { shown, field, _ ->
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next = field.text?.toString().orEmpty()
                shown.dismiss()
                onValue(setting.key, next)
            }
        }
        dialog.setOnDismissListener {
            if (textDialog === dialog) textDialog = null
        }
        textDialog = dialog
    }

    private fun reflectText(key: String, value: String) {
        val row = controls[key] ?: return
        if (row is SwitchPreferenceCompat || row is DropDownPreference) return
        val setting = specs.firstOrNull { it.key == key } ?: return
        if (pluginSettingKind(setting, value) == PluginSettingKind.Password) return
        row.summary = textSummary(value)
    }

    private fun textSummary(value: String): CharSequence {
        return value.ifEmpty { getString(androidx.preference.R.string.not_set) }
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
                PluginWeb.open(requireContext(), url)
                true
            }
        }
    }

    private fun onValue(key: String, next: String) {
        if (applying) return
        val previous = current[key].orEmpty()
        if (next == previous) return
        current[key] = next
        reflectText(key, next)
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
            is DropDownPreference -> {
                val setting = specs.firstOrNull { it.key == key } ?: return
                bindSelect(pref, setting, value)
            }
            else -> reflectText(key, value)
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

    private fun syncPrompt(ui: PluginSettingsUi) {
        if (!ui.signingIn) {
            dismissPrompt()
            return
        }
        if (promptDialog == null) {
            val form = layoutInflater.inflate(R.layout.mygomusic_sign_in, null)
            promptImage = form.findViewById(R.id.mygomusic_sign_in_qr)
            promptLabel = form.findViewById(R.id.mygomusic_sign_in_text)
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setView(form)
                .setNegativeButton(android.R.string.cancel) { _, _ -> model.cancelSignIn() }
                .create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnCancelListener { model.cancelSignIn() }
            dialog.setOnDismissListener {
                if (promptDialog === dialog) {
                    promptDialog = null
                    promptImage = null
                    promptLabel = null
                }
            }
            promptDialog = dialog
            dialog.show()
        }
        val qr = ui.promptQr
        promptImage?.apply {
            if (qr == null) {
                setImageDrawable(null)
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                setImageBitmap(qr)
            }
        }
        promptLabel?.text = ui.promptText.ifBlank { getString(R.string.mygomusic_sign_in_waiting) }
    }

    private fun dismissPrompt() {
        val dialog = promptDialog ?: return
        dialog.setOnCancelListener(null)
        dialog.dismiss()
    }

    private fun showSignInMessage(message: String) {
        if (signInDialog?.isShowing == true) return
        signInDialog = MaterialAlertDialogBuilder(requireContext())
            .setMessage(message.ifBlank { getString(R.string.mygomusic_sign_in_failed) })
            .setPositiveButton(android.R.string.ok) { _, _ -> model.clearSignInMessage() }
            .setOnCancelListener { model.clearSignInMessage() }
            .show()
    }

    private fun dismissSignInMessage() {
        signInDialog?.setOnCancelListener(null)
        signInDialog?.dismiss()
        signInDialog = null
    }

    private fun attachMenu() {
        val bar = requireActivity().findViewById<MaterialToolbar>(R.id.topAppBar) ?: return
        bar.menu.clear()
        bar.overflowIcon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_more_vert_alt_topappbar)
        bar.inflateMenu(R.menu.mygomusic_plugin_item)
        bar.menu.findItem(R.id.mygomusic_plugin_check_update)?.isVisible = false
        bar.setOnMenuItemClickListener { item ->
            if (model.ui.value?.signingIn == true) return@setOnMenuItemClickListener true
            when (item.itemId) {
                R.id.mygomusic_plugin_check_update -> model.checkUpdate()
                R.id.mygomusic_plugin_delete -> confirmDelete()
            }
            true
        }
    }

    private fun syncUpdateItem(fromGithub: Boolean) {
        val bar = activity?.findViewById<MaterialToolbar>(R.id.topAppBar) ?: return
        bar.menu.findItem(R.id.mygomusic_plugin_check_update)?.isVisible = fromGithub
    }

    private fun syncNotice(notice: String?) {
        if (notice == null) {
            noticeDialog?.setOnCancelListener(null)
            noticeDialog?.dismiss()
            noticeDialog = null
            return
        }
        if (noticeDialog?.isShowing == true) return
        noticeDialog = MaterialAlertDialogBuilder(requireContext())
            .setMessage(notice)
            .setPositiveButton(android.R.string.ok) { _, _ -> model.clearNotice() }
            .setOnCancelListener { model.clearNotice() }
            .show()
    }

    private fun syncUpdateError(error: PluginPackageException?) {
        if (error == null) {
            updateErrorDialog?.setOnCancelListener(null)
            updateErrorDialog?.dismiss()
            updateErrorDialog = null
            return
        }
        if (updateErrorDialog?.isShowing == true) return
        updateErrorDialog = MaterialAlertDialogBuilder(requireContext())
            .setMessage(pluginFailureMessage(requireContext(), error))
            .setPositiveButton(android.R.string.ok) { _, _ -> model.clearUpdateError() }
            .setOnCancelListener { model.clearUpdateError() }
            .show()
    }

    private fun syncUpdateOffer(ui: PluginSettingsUi) {
        val manifest = ui.updateOffer
        if (manifest == null) {
            updateDialog?.setOnCancelListener(null)
            updateDialog?.dismiss()
            updateDialog = null
            return
        }
        if (updateDialog?.isShowing == true) return
        updateDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mygomusic_plugin_update_title)
            .setMessage(pluginInstallMessage(requireContext(), manifest, ui.updateRepo))
            .setPositiveButton(R.string.mygomusic_plugin_update) { _, _ -> model.confirmUpdate() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> model.discardUpdate() }
            .setOnCancelListener { model.discardUpdate() }
            .show()
    }

    private fun confirmDelete() {
        if (deleteDialog?.isShowing == true) return
        val title = model.ui.value?.title?.ifEmpty { null }
            ?: requireActivity().intent.getStringExtra(PluginSettingsActivity.EXTRA_TITLE).orEmpty()
        val form = layoutInflater.inflate(R.layout.mygomusic_plugin_delete_data, null)
        form.findViewById<TextView>(R.id.mygomusic_plugin_delete_message).text =
            getString(R.string.mygomusic_plugin_remove_message, title)
        val check = form.findViewById<CheckBox>(R.id.mygomusic_plugin_delete_data)
        deleteDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mygomusic_plugin_remove_title)
            .setView(form)
            .setPositiveButton(R.string.remove) { _, _ -> model.delete(check.isChecked) }
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
