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

import org.json.JSONObject
import java.net.URI

data class PluginSettingOption(
    val value: String,
    val label: String,
)

data class PluginSetting(
    val key: String,
    val label: String,
    val help: String,
    val link: String,
    val type: String,
    val options: List<PluginSettingOption>,
)

internal enum class PluginSettingKind {
    Text,
    Password,
    Switch,
    Select,
}

internal fun parsePluginSettings(obj: JSONObject): List<PluginSetting> {
    if (!obj.has("settings") || obj.isNull("settings")) return emptyList()
    val arr = obj.optJSONArray("settings") ?: throw PluginPackageException(PluginFailureKind.BadManifest)
    val seen = HashSet<String>()
    val out = mutableListOf<PluginSetting>()
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: throw PluginPackageException(PluginFailureKind.BadManifest)
        val key = settingText(item, "key") ?: throw PluginPackageException(PluginFailureKind.BadManifest)
        if (key.isEmpty()) throw PluginPackageException(PluginFailureKind.BadManifest)
        if (!seen.add(key)) continue
        out += PluginSetting(
            key = key,
            label = settingText(item, "label").orEmpty(),
            help = settingText(item, "help").orEmpty(),
            link = settingText(item, "link").orEmpty(),
            type = settingText(item, "type").orEmpty(),
            options = settingOptions(item),
        )
    }
    return out
}

/** Missing type, text, unknown types and a select that cannot be shown are text. */
internal fun pluginSettingKind(setting: PluginSetting, stored: String): PluginSettingKind {
    return when (setting.type) {
        "password" -> PluginSettingKind.Password
        "boolean" -> when (stored) {
            "", "true", "false" -> PluginSettingKind.Switch
            else -> PluginSettingKind.Text
        }
        "select" -> if (validSelect(setting.options)) PluginSettingKind.Select else PluginSettingKind.Text
        else -> PluginSettingKind.Text
    }
}

internal fun selectChoices(
    options: List<PluginSettingOption>,
    stored: String,
    current: (String) -> String,
): Pair<List<String>, List<String>>? {
    if (!validSelect(options)) return null
    val labels = ArrayList<String>(options.size + 1)
    val values = ArrayList<String>(options.size + 1)
    if (options.none { it.value == stored }) {
        var label = current(stored).ifEmpty { stored }
        while (label in labels || options.any { it.label == label }) label += " "
        labels += label
        values += stored
    }
    for (option in options) {
        labels += option.label
        values += option.value
    }
    return labels to values
}

/**
 * [account] returns null when nobody is signed in, and a profile object otherwise.
 * The name may be empty. A value that is not that object is not a signed-in account.
 */
internal fun accountLabel(value: Any?): String? {
    if (value == null || value is Unit) return null
    val obj = pluginJson(value)
    if (obj !is JSONObject) return null
    return obj.optString("name").trim()
}

/** http and https only, matching host.openURL. */
internal fun httpLink(link: String): String? {
    val trimmed = link.trim()
    if (trimmed.isEmpty()) return null
    val uri = try {
        URI(trimmed)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrEmpty()) return null
    return trimmed
}

private fun validSelect(options: List<PluginSettingOption>): Boolean {
    if (options.isEmpty()) return false
    val values = HashSet<String>()
    val labels = HashSet<String>()
    for (option in options) {
        if (option.label.isEmpty() || !values.add(option.value) || !labels.add(option.label)) return false
    }
    return true
}

private fun settingOptions(item: JSONObject): List<PluginSettingOption> {
    if (!item.has("options") || item.isNull("options")) return emptyList()
    val arr = item.optJSONArray("options") ?: throw PluginPackageException(PluginFailureKind.BadManifest)
    val out = ArrayList<PluginSettingOption>(arr.length())
    for (i in 0 until arr.length()) {
        val option = arr.optJSONObject(i) ?: throw PluginPackageException(PluginFailureKind.BadManifest)
        out += PluginSettingOption(
            value = settingText(option, "value").orEmpty(),
            label = settingText(option, "label").orEmpty(),
        )
    }
    return out
}

/** Null when the key is absent. A present non-string is a bad manifest. */
private fun settingText(obj: JSONObject, key: String): String? {
    if (!obj.has(key) || obj.isNull(key)) return null
    if (obj.opt(key) !is String) throw PluginPackageException(PluginFailureKind.BadManifest)
    return obj.getString(key)
}
