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
import android.os.Build
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Opens a plugin track when ExoPlayer asks for its [PLAY_SCHEME] URI.
 * The stream address is resolved here, not when the item is queued.
 *
 * Request headers follow mygo-music. The user agent is [USER_AGENT]. Headers
 * from the plugin are removed when a redirect leaves the original host; a
 * child of that host keeps them. The user agent is not one of those headers,
 * so it stays. [stream] headers replace [plugin.json] headers of the same name.
 */
internal object PluginDataSource {
    const val USER_AGENT = "MyGoMusic/1.0"
    const val QUALITY_KEY = "mygomusic_quality"

    private const val TAG = "Mygomusic"
    private const val STAY = "X-MyGramophone-Stay"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .addInterceptor(HeadersStay())
        .build()

    private val calls: Call.Factory = StayFactory(httpClient)

    class Factory(context: Context) : DataSource.Factory {
        private val app = context.applicationContext
        private val upstream = DefaultDataSource.Factory(app, OkHttpDataSource.Factory(calls))

        override fun createDataSource(): DataSource = Opening(app, upstream.createDataSource())
    }

    fun quality(context: Context): String {
        val value = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(QUALITY_KEY, "high")
        return if (value == "standard" || value == "high" || value == "lossless") value else "high"
    }

    internal fun newAudioCall(request: Request): Call = calls.newCall(request)

    suspend fun played(context: Context, item: MediaItem) {
        val uri = item.localConfiguration?.uri ?: return
        val (pluginId, trackId) = parsePlayUri(uri) ?: return
        try {
            val loaded = load(context, pluginId)
            if (loaded.manifest.playback != "stream") return
            loaded.runtime.call("played", mapOf("id" to trackId))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (missingExport(e)) return
            Log.w(TAG, "plugin $pluginId played", e)
        }
    }

    internal fun audioHeaders(
        requestHeaders: Map<String, String>,
        streamHeaders: Map<String, String>,
        target: Uri,
    ): Map<String, String> {
        val scheme = target.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return emptyMap()
        val plugin = LinkedHashMap<String, String>()
        for ((name, value) in streamHeaders) {
            val kept = headerValue(name, value) ?: continue
            plugin[name] = kept
        }
        for ((name, value) in requestHeaders) {
            if (plugin.keys.any { it.equals(name, ignoreCase = true) }) continue
            val kept = headerValue(name, value) ?: continue
            plugin[name] = kept
        }
        val out = LinkedHashMap<String, String>()
        out["User-Agent"] = USER_AGENT
        for ((name, value) in plugin) out[name] = value
        if (plugin.isNotEmpty()) out[STAY] = plugin.keys.joinToString(",")
        return out
    }

    internal fun stays(origin: String, dest: String): Boolean {
        val host = origin.lowercase()
        val to = dest.lowercase()
        return to == host || to.endsWith(".$host")
    }

    internal fun openTarget(pluginDir: File, url: String): Uri {
        val uri = Uri.parse(url)
        when (uri.scheme?.lowercase()) {
            "file" -> {
                val path = uri.path?.takeIf { it.isNotEmpty() } ?: throw IOException("stream")
                val file = File(path)
                if (!file.isFile || !file.isInside(pluginDir)) throw IOException("stream")
                return Uri.fromFile(file)
            }
            "http", "https" -> {
                if (uri.host.isNullOrEmpty()) throw IOException("stream")
                return uri
            }
            else -> throw IOException("stream")
        }
    }

    internal fun readStream(value: Any?): StreamBody {
        val obj = pluginJson(value) as? JSONObject ?: throw IOException("stream")
        val url = obj.optString("url")
        if (url.isEmpty()) throw IOException("stream")
        val mime = streamMime(obj.optString("type")) ?: throw IOException("stream")
        val headers = LinkedHashMap<String, String>()
        val raw = obj.optJSONObject("headers")
        if (raw != null) {
            val names = raw.keys()
            while (names.hasNext()) {
                val name = names.next()
                val item = raw.opt(name)
                if (item is String) headers[name] = item
            }
        }
        return StreamBody(url, mime, headers)
    }

    private suspend fun resolve(context: Context, uri: Uri): Resolved {
        val (pluginId, trackId) = parsePlayUri(uri) ?: throw IOException("stream")
        return try {
            val loaded = load(context, pluginId)
            if (loaded.manifest.playback != "stream") throw IOException("stream")
            val body = readStream(
                loaded.runtime.call(
                    "stream",
                    mapOf("id" to trackId, "quality" to quality(context)),
                ),
            )
            val target = openTarget(loaded.dir, body.url)
            val records = PluginRecords(PluginStore(context).dataDir(pluginId))
            val fromManifest = PluginCovers.headerMap(
                loaded.manifest.headerRules,
                records,
                body.url,
                "audio",
                loaded.manifest.network,
            )
            Resolved(target, audioHeaders(fromManifest, body.headers, target), body.mime)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "plugin $pluginId stream", e)
            throw IOException(e.message?.ifBlank { null } ?: "stream", e)
        }
    }

    private fun load(context: Context, pluginId: String): Loaded {
        val store = PluginStore(context)
        val dir = store.packageDir(pluginId)
        val manifest = PluginManifest.read(dir)
        val runtime = PluginRuntime(manifest, dir, store.dataDir(pluginId), locale(context))
        return Loaded(dir, manifest, runtime)
    }

    private fun locale(context: Context): Locale {
        val config = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0]
        } else {
            @Suppress("DEPRECATION")
            config.locale
        }
    }

    private class Opening(
        private val context: Context,
        private val upstream: DataSource,
    ) : DataSource {
        private var opened = false
        private var mime: String? = null
        private var resolved: Pair<String, Resolved>? = null

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val fresh = if (dataSpec.uri.scheme == PLAY_SCHEME) resolveCached(dataSpec.uri) else null
            val spec = if (fresh != null) {
                mime = fresh.mime
                val headers = LinkedHashMap(dataSpec.httpRequestHeaders)
                headers.putAll(fresh.headers)
                dataSpec.withUri(fresh.uri).withRequestHeaders(headers)
            } else {
                mime = null
                dataSpec
            }
            opened = true
            return try {
                val length = upstream.open(spec)
                if (fresh != null) resolved = dataSpec.uri.toString() to fresh
                length
            } catch (e: IOException) {
                resolved = null
                opened = false
                throw e
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return upstream.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = upstream.getUri()

        override fun getResponseHeaders(): Map<String, List<String>> {
            val mimeType = mime ?: return upstream.getResponseHeaders()
            val headers = LinkedHashMap<String, List<String>>()
            for ((key, value) in upstream.getResponseHeaders()) {
                if (!key.equals("Content-Type", true)) headers[key] = value
            }
            headers["Content-Type"] = listOf(mimeType)
            return headers
        }

        override fun close() {
            if (!opened) return
            opened = false
            upstream.close()
        }

        private fun resolveCached(uri: Uri): Resolved {
            val key = uri.toString()
            resolved?.let { (cachedKey, hit) -> if (cachedKey == key) return hit }
            return runBlocking {
                withContext(Dispatchers.IO) { resolve(context, uri) }
            }
        }
    }

    private class StayFactory(private val client: OkHttpClient) : Call.Factory {
        override fun newCall(request: Request): Call {
            val names = request.header(STAY)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
            val cleaned = request.newBuilder()
                .removeHeader(STAY)
                .tag(StayNames::class.java, StayNames(names))
                .build()
            return client.newCall(cleaned)
        }
    }

    /**
     * OkHttp would follow the redirect itself and only hold back Authorization.
     * This follows instead, and drops every plugin header once the host is no
     * longer the original host or a child of it.
     */
    private class HeadersStay : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val initial = chain.request()
            val names = initial.tag(StayNames::class.java)?.names.orEmpty()
            val origin = initial.url.host
            var request = withoutLeaving(initial, origin, names)
            var response = chain.proceed(request)
            var made = 1
            while (response.code == 301 || response.code == 302 || response.code == 303 ||
                response.code == 307 || response.code == 308
            ) {
                val next = response.header("Location")?.let { response.request.url.resolve(it) }
                response.close()
                if (next == null) throw IOException("redirect")
                if (made >= 10) throw IOException("stopped after 10 redirects")
                made++
                request = withoutLeaving(request.newBuilder().url(next).build(), origin, names)
                response = chain.proceed(request)
            }
            return response
        }

        private fun withoutLeaving(request: Request, origin: String, names: Set<String>): Request {
            if (names.isEmpty() || stays(origin, request.url.host)) return request
            val builder = request.newBuilder()
            for (name in names) builder.removeHeader(name)
            return builder.build()
        }
    }

    private data class StayNames(val names: Set<String>)
    private data class Loaded(val dir: File, val manifest: PluginManifest, val runtime: PluginRuntime)
    private data class Resolved(val uri: Uri, val headers: Map<String, String>, val mime: String)

    internal data class StreamBody(val url: String, val mime: String, val headers: Map<String, String>)
}

private fun headerValue(name: String, value: String): String? {
    if (name.isEmpty() || name.equals("host", true) || name.equals("content-length", true)) return null
    if (value.isEmpty() || value.any { it == '\r' || it == '\n' }) return null
    return value
}

private fun streamMime(type: String): String? = when (type.trim().lowercase()) {
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "ogg" -> "audio/ogg"
    "wav" -> "audio/wav"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    else -> null
}

private fun missingExport(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current.message?.contains("missing export") == true) return true
        current = current.cause
    }
    return false
}
