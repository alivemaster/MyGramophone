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

import com.dokar.quickjs.binding.JsObject
import com.dokar.quickjs.binding.toJsObject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Plugin fetch. A redirect to a host outside [network] fails. Cookie and
 * Authorization are not sent on to a different host.
 */
internal object PluginFetch {
    private const val MAX_BODY = 32 * 1024 * 1024
    private const val MAX_REDIRECTS = 10

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun request(
        network: List<String>,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        redirect: String,
    ): JsObject {
        if (redirect != "follow" && redirect != "error" && redirect != "manual") {
            throw IllegalArgumentException("redirect")
        }
        val allow = Allow(network)
        var current = build(url, method, headers, body, allow)
        var followed = 0
        while (true) {
            val response = current.await()
            val code = response.code
            val redirecting = code == 301 || code == 302 || code == 303 || code == 307 || code == 308
            if (!redirecting || redirect == "manual") return taken(response)
            val location = response.header("Location")
            response.close()
            if (redirect == "error" || location.isNullOrEmpty()) {
                throw IllegalArgumentException("redirect")
            }
            if (++followed > MAX_REDIRECTS) throw IOException("stopped after 10 redirects")
            current = redirected(current, location, code, allow)
        }
    }

    private fun build(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        allow: Allow,
    ): Request {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("not a web address")
        checkUrl(httpUrl.scheme, httpUrl.host, allow, redirected = false)
        val verb = method.uppercase()
        val built = Headers.Builder()
        for ((name, value) in headers) {
            if (name.equals("Host", ignoreCase = true)) allow.check(hostname(value), redirected = false)
            built.add(name, value)
        }
        val type = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.toMediaTypeOrNull()
        val requestBody = when (verb) {
            "GET", "HEAD" -> null
            else -> (body ?: "").toRequestBody(type)
        }
        return Request.Builder().url(httpUrl).headers(built.build()).method(verb, requestBody).build()
    }

    private fun redirected(previous: Request, location: String, code: Int, allow: Allow): Request {
        val nextUrl = previous.url.resolve(location) ?: throw IllegalArgumentException("redirect")
        checkUrl(nextUrl.scheme, nextUrl.host, allow, redirected = true)
        val builder = previous.newBuilder().url(nextUrl)
        val toGet = code == 303 ||
            ((code == 301 || code == 302) && previous.method != "GET" && previous.method != "HEAD")
        if (toGet) builder.method("GET", null)
        if (!nextUrl.host.equals(previous.url.host, ignoreCase = true)) {
            builder.removeHeader("Authorization")
            builder.removeHeader("Cookie")
            builder.removeHeader("Proxy-Authorization")
            builder.removeHeader("Host")
        }
        val built = builder.build()
        val host = built.header("Host")
        if (host != null) allow.check(hostname(host), redirected = true)
        return built
    }

    private fun taken(response: Response): JsObject = response.use { resp ->
        val headers = LinkedHashMap<String, String>()
        for (i in 0 until resp.headers.size) {
            val name = resp.headers.name(i).lowercase()
            val value = resp.headers.value(i)
            val prev = headers[name]
            headers[name] = if (prev == null) value else "$prev, $value"
        }
        mapOf(
            "ok" to (resp.code in 200..299),
            "status" to resp.code,
            "statusText" to resp.message,
            "url" to resp.request.url.toString(),
            "headers" to headers.toJsObject(),
            "setCookie" to resp.headers.values("set-cookie"),
            "body" to readBody(resp),
        ).toJsObject()
    }

    private fun readBody(response: Response): String {
        val body = response.body ?: return ""
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BODY) throw IOException("response is too large")
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), charset)
        }
    }

    private suspend fun Request.await(): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(this)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private fun checkUrl(scheme: String, host: String, allow: Allow, redirected: Boolean) {
        if ((scheme != "http" && scheme != "https") || host.isEmpty()) {
            throw IllegalArgumentException("not a web address")
        }
        allow.check(host, redirected)
    }

    private fun hostname(hostport: String): String {
        val trimmed = hostport.trim()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end > 1) return trimmed.substring(1, end)
        }
        val colon = trimmed.lastIndexOf(':')
        if (colon > 0 && trimmed.indexOf(':') == colon) return trimmed.substring(0, colon)
        return trimmed
    }

    private class Allow(patterns: List<String>) {
        private val all = patterns.any { it.trim() == "*" }
        private val exact = HashSet<String>()
        private val suffixes = ArrayList<String>()

        init {
            for (raw in patterns) {
                val host = raw.trim().lowercase()
                when {
                    host.isEmpty() || host == "*" -> {}
                    host.startsWith("*.") && host.length > 2 -> suffixes.add(host.substring(1))
                    else -> exact.add(host)
                }
            }
        }

        fun check(host: String, redirected: Boolean) {
            if (all) return
            val name = host.lowercase()
            if (name in exact) return
            if (suffixes.any { name.length > it.length && name.endsWith(it) }) return
            val where = if (redirected) "redirected to host" else "host"
            throw IllegalArgumentException("$where $name is not allowed")
        }
    }
}
