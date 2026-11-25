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

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import kotlin.coroutines.coroutineContext

/** mygo waits this long for signIn, and for the page it opens. */
internal const val SIGN_IN_TIMEOUT_MILLIS = 5L * 60L * 1000L

internal data class WebSignInRequest(
    val url: String,
    val cookiesOf: String,
    val until: List<String>,
    val userAgent: String,
)

internal data class RedirectWait(
    val port: Int,
    val path: String,
    val timeoutMs: Long,
    val page: String,
)

/** What a plugin's sign-in can ask the screen to do. Playback calls leave this empty. */
internal interface PluginHost {
    fun openUrl(url: String)
    fun signInPrompt(qr: String, text: String)
    suspend fun webSignIn(request: WebSignInRequest): String
}

internal object PluginHostNone : PluginHost {
    override fun openUrl(url: String) {
        throw IllegalStateException("can't open pages here")
    }

    override fun signInPrompt(qr: String, text: String) = Unit

    override suspend fun webSignIn(request: WebSignInRequest): String {
        throw IllegalStateException("can't show a sign-in page here")
    }
}

/** http and https only. Returns the trimmed address. */
internal fun requireWebPage(address: String): String {
    val page = webPage(address)
    if (page == null) throw IllegalArgumentException("not a web address: $address")
    return page
}

internal fun webPage(address: String): String? {
    val trimmed = address.trim()
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

internal fun webSignInRequest(network: List<String>, raw: Map<*, *>): WebSignInRequest {
    if (network.isEmpty()) throw IllegalArgumentException("webSignIn needs the network permission")
    val url = raw["url"] as? String ?: ""
    val cookiesOf = raw["cookiesOf"] as? String ?: ""
    if (httpsHost(url) == null) {
        throw IllegalArgumentException("not a secure web address to sign in on: $url")
    }
    val host = httpsHost(cookiesOf)
        ?: throw IllegalArgumentException("cookiesOf isn't a secure web address: $cookiesOf")
    if (!networkAllows(network, host)) {
        throw IllegalArgumentException("the plugin can't reach $host, so it can't have its cookies")
    }
    val until = stringList(raw["until"])
    if (until.isEmpty()) {
        throw IllegalArgumentException("webSignIn needs the names of the cookies that say it's done, as until")
    }
    val agent = raw["userAgent"] as? String ?: ""
    return WebSignInRequest(url.trim(), cookiesOf.trim(), until, agent)
}

internal fun redirectWait(network: List<String>, raw: Map<*, *>): RedirectWait {
    if (network.isEmpty()) throw IllegalArgumentException("waitForRedirect needs the network permission")
    val port = longArg(raw["port"])?.toInt() ?: 0
    if (port !in 1..65535) throw IllegalArgumentException("port")
    val path = raw["path"] as? String ?: ""
    if (path.length > 2048) throw IllegalArgumentException("path")
    var timeout = longArg(raw["timeout"]) ?: 0L
    if (timeout <= 0L || timeout > SIGN_IN_TIMEOUT_MILLIS) timeout = SIGN_IN_TIMEOUT_MILLIS
    var page = raw["page"] as? String ?: ""
    if (page.isEmpty()) page = "You're signed in. You can close this page."
    if (page.length > 65536) throw IllegalArgumentException("page")
    return RedirectWait(port, path, timeout, page)
}

/**
 * Cookie header for [cookiesOf], once every name in [until] is present.
 * Empty values are left out. Null when sign-in is not finished.
 */
internal fun cookieHeader(raw: String?, until: List<String>): String? {
    if (raw.isNullOrBlank() || until.isEmpty()) return null
    val have = LinkedHashMap<String, String>()
    for (part in raw.split(';')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val name = part.substring(0, eq).trim()
        val value = part.substring(eq + 1).trim()
        if (name.isEmpty() || value.isEmpty() || name in have) continue
        have[name] = value
    }
    if (until.any { it !in have }) return null
    return have.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/** Text the phone app should scan. Null when there is nothing to draw. */
internal fun qrMatrix(text: String, size: Int = 512): BitMatrix? {
    if (text.isEmpty() || size < 21) return null
    return try {
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
            ),
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * One redirect on 127.0.0.1. A browser's favicon request is not the sign-in.
 * The returned map is the query, first value of each name.
 */
internal suspend fun awaitRedirect(
    port: Int,
    path: String,
    page: String,
    timeoutMs: Long,
): Map<String, String> = withContext(Dispatchers.IO) {
    if (port !in 1..65535) throw IllegalArgumentException("port")
    val wait = if (timeoutMs <= 0L) SIGN_IN_TIMEOUT_MILLIS else timeoutMs
    val server = ServerSocket()
    try {
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
        coroutineContext.ensureActive()
        val deadline = System.nanoTime() + wait * 1_000_000L
        server.soTimeout = 1000
        while (true) {
            coroutineContext.ensureActive()
            if (System.nanoTime() >= deadline) {
                throw IllegalStateException("timed out waiting for the sign-in to come back")
            }
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: SocketException) {
                if (!isActive) throw CancellationException()
                throw e
            }
            socket.use { client ->
                val query = serveRedirect(client, path, page)
                if (query != null) return@withContext query
            }
        }
        throw IllegalStateException("timed out waiting for the sign-in to come back")
    } finally {
        runCatching { server.close() }
    }
}

private fun serveRedirect(socket: Socket, expectedPath: String, page: String): Map<String, String>? {
    socket.soTimeout = 5_000
    val header = readHeader(socket.getInputStream()) ?: return null
    val request = header.lineSequence().firstOrNull().orEmpty().trim()
    val target = request.split(' ').getOrNull(1).orEmpty()
    val split = target.indexOf('?')
    val path = if (split >= 0) target.substring(0, split) else target
    val query = if (split >= 0) target.substring(split + 1) else ""
    if (path == "/favicon.ico" || (expectedPath.isNotEmpty() && path != expectedPath)) {
        writePlain(socket, 404, "not found")
        return null
    }
    writePlain(socket, 200, page)
    return queryMap(query)
}

private fun readHeader(input: InputStream): String? {
    val out = ByteArrayOutputStream()
    val one = ByteArray(1)
    while (out.size() < 8192) {
        val n = input.read(one)
        if (n < 0) break
        out.write(one, 0, n)
        val bytes = out.toByteArray()
        val end = bytes.size
        if (end >= 4 &&
            bytes[end - 4] == '\r'.code.toByte() &&
            bytes[end - 3] == '\n'.code.toByte() &&
            bytes[end - 2] == '\r'.code.toByte() &&
            bytes[end - 1] == '\n'.code.toByte()
        ) {
            return String(bytes, Charsets.ISO_8859_1)
        }
    }
    return null
}

private fun writePlain(socket: Socket, code: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val reason = if (code == 200) "OK" else "Not Found"
    val head = "HTTP/1.1 $code $reason\r\n" +
        "Content-Type: text/plain; charset=utf-8\r\n" +
        "Content-Length: ${bytes.size}\r\n" +
        "Connection: close\r\n\r\n"
    val output = socket.getOutputStream()
    output.write(head.toByteArray(Charsets.US_ASCII))
    output.write(bytes)
    output.flush()
}

private fun queryMap(query: String): Map<String, String> {
    if (query.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in query.split('&')) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        val rawKey = if (eq >= 0) part.substring(0, eq) else part
        val rawValue = if (eq >= 0) part.substring(eq + 1) else ""
        val key = urlDecode(rawKey)
        if (key.isEmpty() || key in out) continue
        out[key] = urlDecode(rawValue)
    }
    return out
}

private fun urlDecode(text: String): String {
    return try {
        URLDecoder.decode(text, Charsets.UTF_8.name())
    } catch (_: Exception) {
        text
    }
}

private fun httpsHost(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    val uri = try {
        URI(trimmed)
    } catch (_: Exception) {
        return null
    }
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    val host = uri.host ?: return null
    if (host.isEmpty()) return null
    return host
}

private fun stringList(value: Any?): List<String> {
    val items = when (value) {
        is List<*> -> value
        is Array<*> -> value.asList()
        else -> return emptyList()
    }
    return items.mapNotNull { (it as? String)?.trim() }.filter { it.isNotEmpty() }
}

private fun longArg(value: Any?): Long? = when (value) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}
