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

    fun headers(
        rules: List<PluginHeaderRule>,
        records: PluginRecords,
        url: String,
        network: List<String>,
    ): NetworkHeaders {
        val builder = NetworkHeaders.Builder()
        for ((name, value) in headerMap(rules, records, url, "image", network)) {
            builder.set(name, value)
        }
        return builder.build()
    }

    /** First rule to set a name wins. An empty placeholder or a line break drops that header. */
    fun headerMap(
        rules: List<PluginHeaderRule>,
        records: PluginRecords,
        url: String,
        kind: String,
        network: List<String>,
    ): Map<String, String> {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return emptyMap()
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return emptyMap()
        val host = parsed.host?.lowercase() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (rule in rules) {
            if (rule.kinds.isNotEmpty() && kind !in rule.kinds) continue
            if (rule.hosts.none { hostMatches(it, host, records, network) }) continue
            for ((name, template) in rule.headers) {
                if (name.isEmpty() || out.keys.any { it.equals(name, ignoreCase = true) }) continue
                if (name.equals("host", true) || name.equals("content-length", true)) continue
                val value = substitute(template, records)
                if (value.isEmpty()) continue
                out[name] = value
            }
        }
        return out
    }
}

private fun hostMatches(
    pattern: String,
    host: String,
    records: PluginRecords,
    network: List<String>,
): Boolean {
    if (pattern.startsWith("{setting:") && pattern.endsWith("}")) {
        val key = pattern.removePrefix("{setting:").removeSuffix("}")
        val named = settingHost(records, key) ?: return false
        return named == host && networkReaches(network, host)
    }
    val rule = pattern.lowercase()
    if (rule == host) return true
    if (rule.startsWith("*.")) {
        val suffix = rule.removePrefix("*")
        return host.endsWith(suffix) && host.length > suffix.length
    }
    return false
}

internal fun networkReaches(network: List<String>, host: String): Boolean {
    val wanted = host.lowercase()
    for (raw in network) {
        val rule = raw.lowercase()
        if (rule == "*" || rule == wanted) return true
        if (rule.startsWith("*.")) {
            val suffix = rule.removePrefix("*")
            if (wanted.endsWith(suffix) && wanted.length > suffix.length) return true
        }
    }
    return false
}

private fun settingHost(records: PluginRecords, key: String): String? {
    val raw = records.setting(key).trim()
    if (raw.isEmpty()) return null
    val withScheme = if ("://" in raw) raw else "https://$raw"
    return runCatching { Uri.parse(withScheme).host }.getOrNull()?.lowercase()
}

private val placeholder = Regex("\\{(setting|storage):([^{}]+)}")

private fun substitute(template: String, records: PluginRecords): String {
    var missing = false
    val filled = placeholder.replace(template) { match ->
        val value = when (match.groupValues[1]) {
            "setting" -> records.setting(match.groupValues[2])
            else -> storageText(records, match.groupValues[2])
        }
        if (value.isEmpty()) missing = true
        value
    }
    if (missing || filled.any { it == '\r' || it == '\n' }) return ""
    return filled
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
 * Picture headers are for the host in the rule. OkHttp copies them onto a
 * redirect; drop those names when the host changes. Audio uses the player
 * client instead, which keeps a header on a child of the original host.
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
