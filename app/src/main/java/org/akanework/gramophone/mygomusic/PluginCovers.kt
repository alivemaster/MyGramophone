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
import android.net.Uri
import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageResult
import coil3.size.Dimension
import coil3.size.Size
import okhttp3.Call
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object PluginCovers {
    /** List rows ask the image server for about this many pixels. */
    const val LIST_PIXELS = 40

    /** A cover whose URL does not say how big to draw it. */
    const val UNSPECIFIED_PIXELS = 600

    /**
     * Picture fetches. The user agent is [PluginDataSource.USER_AGENT]. Plugin
     * headers are removed when a redirect leaves the original host; a child of
     * that host keeps them.
     */
    val client: Call.Factory = PluginDataSource.newStayingClient {
        connectTimeout(30, TimeUnit.SECONDS)
        readTimeout(30, TimeUnit.SECONDS)
        callTimeout(30, TimeUnit.SECONDS)
    }

    fun sized(url: String?, pixels: Int): String? {
        if (url.isNullOrBlank()) return null
        return url.replace("{size}", pixels.toString())
            .replace("%7Bsize%7D", pixels.toString(), ignoreCase = true)
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

    /**
     * Headers for this picture, from the first installed plugin whose rule
     * matches the host. An empty result means no plugin claims it.
     */
    fun pluginImageHeaders(context: Context, url: String): Map<String, String> {
        val store = PluginStore(context)
        for (plugin in store.list(Locale.ROOT)) {
            if (plugin.broken) continue
            val manifest = try {
                PluginManifest.read(store.packageDir(plugin.directoryName))
            } catch (_: Exception) {
                continue
            }
            if (manifest.headerRules.isEmpty()) continue
            val headers = headerMap(
                manifest.headerRules,
                PluginRecords(store.dataDir(plugin.directoryName)),
                url,
                "image",
                manifest.network,
            )
            if (headers.isNotEmpty()) return headers
        }
        return emptyMap()
    }
}

/** List rows pass [PluginCovers.LIST_PIXELS]. Anything Coil did not size uses 600. */
internal fun coverPixels(size: Size): Int {
    val width = (size.width as? Dimension.Pixels)?.px ?: 0
    val height = (size.height as? Dimension.Pixels)?.px ?: 0
    val px = maxOf(width, height)
    return if (px > 0) px else PluginCovers.UNSPECIFIED_PIXELS
}

/**
 * Replaces `{size}` in cover URLs and attaches the plugin's image headers.
 * The queue keeps the original URL. List rows already ask for about 40 pixels.
 */
internal class PluginCoverInterceptor(private val context: Context) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val raw = when (val data = chain.request.data) {
            is String -> data
            is Uri -> data.toString()
            else -> return chain.proceed()
        }
        val placeholder = raw.contains("{size}") || raw.contains("%7Bsize%7D", ignoreCase = true)
        val http = raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        if (!placeholder && !http) return chain.proceed()
        val sized = if (placeholder) PluginCovers.sized(raw, coverPixels(chain.size)) ?: raw else raw
        val plugin = if (sized.startsWith("http://", true) || sized.startsWith("https://", true)) {
            PluginCovers.pluginImageHeaders(context, sized)
        } else {
            emptyMap()
        }
        if (sized == raw && plugin.isEmpty()) return chain.proceed()
        val builder = chain.request.newBuilder().data(sized)
        if (plugin.isNotEmpty()) {
            val headers = chain.request.httpHeaders.newBuilder()
            for ((name, value) in plugin) headers.set(name, value)
            headers.set(PluginDataSource.STAY, plugin.keys.joinToString(","))
            builder.httpHeaders(headers.build())
        }
        return chain.withRequest(builder.build()).proceed()
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

