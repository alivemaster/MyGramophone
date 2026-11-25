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

import android.net.Uri
import coil3.network.NetworkHeaders
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

internal object PluginCovers {
    /** List rows ask the image server for about this many pixels. */
    const val LIST_PIXELS = 40

    val client: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(CrossHostStripper())
        .build()

    fun sized(url: String?, pixels: Int): String? {
        if (url.isNullOrBlank()) return null
        return url.replace("{size}", pixels.toString())
            .replace("%7Bsize%7D", pixels.toString(), ignoreCase = true)
    }

    fun headers(rules: List<PluginHeaderRule>, records: PluginRecords, url: String): NetworkHeaders {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()
            ?: return NetworkHeaders.Builder().build()
        val builder = NetworkHeaders.Builder()
        for (rule in rules) {
            if (rule.kinds.isNotEmpty() && "image" !in rule.kinds) continue
            if (rule.hosts.none { hostMatches(it, host, records) }) continue
            for ((name, template) in rule.headers) {
                val value = substitute(template, records)
                if (name.isEmpty() || value.isEmpty()) continue
                builder.set(name, value)
            }
        }
        return builder.build()
    }
}

private fun hostMatches(pattern: String, host: String, records: PluginRecords): Boolean {
    if (pattern.startsWith("{setting:") && pattern.endsWith("}")) {
        val key = pattern.removePrefix("{setting:").removeSuffix("}")
        return settingHost(records, key) == host
    }
    val rule = pattern.lowercase()
    if (rule == host) return true
    if (rule.startsWith("*.")) {
        val suffix = rule.removePrefix("*")
        return host.endsWith(suffix) && host.length > suffix.length
    }
    return false
}

private fun settingHost(records: PluginRecords, key: String): String? {
    val raw = records.setting(key).trim()
    if (raw.isEmpty()) return null
    val withScheme = if ("://" in raw) raw else "https://$raw"
    return runCatching { Uri.parse(withScheme).host }.getOrNull()?.lowercase()
}

private fun substitute(template: String, records: PluginRecords): String {
    val setting = Regex("\\{setting:([^{}]+)}")
    val storage = Regex("\\{storage:([^{}]+)}")
    val withSettings = setting.replace(template) { records.setting(it.groupValues[1]) }
    return storage.replace(withSettings) { storageText(records, it.groupValues[1]) }
}

private fun storageText(records: PluginRecords, key: String): String {
    return when (val hit = records.storageGet(key)) {
        Stored.Missing -> ""
        is Stored.Present -> when (val value = hit.value) {
            null -> ""
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> ""
        }
    }
}

/**
 * Headers from plugin.json are for the host in the rule. OkHttp copies them
 * onto a redirect; drop those names when the host changes. Stream headers
 * follow the same rule once playback sends them through this client.
 */
private class CrossHostStripper : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val original = chain.call().request()
        if (request.url.host.equals(original.url.host, ignoreCase = true)) {
            return chain.proceed(request)
        }
        val builder = request.newBuilder()
        for (name in original.headers.names()) {
            if (name.lowercase() in KEEP) continue
            builder.removeHeader(name)
        }
        return chain.proceed(builder.build())
    }

    private companion object {
        val KEEP = setOf("user-agent", "accept", "accept-language", "range")
    }
}
