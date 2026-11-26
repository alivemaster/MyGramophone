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

import android.os.Build
import android.util.Log
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.toJsObject
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * One QuickJS runtime for a single export call, then closed.
 * quickjs-kt 1.0.5 counts [MEMORY_LIMIT_BYTES] in bytes and has no
 * evaluation timeout, so the coroutine timeout is the call limit.
 */
class PluginCallException(
    val code: String,
    message: String,
) : Exception(message)

internal class PluginRuntime(
    private val manifest: PluginManifest,
    private val pluginDir: File,
    private val dataDir: File,
    private val locale: Locale,
    private val host: PluginHost = PluginHostNone,
) {
    suspend fun call(
        exportName: String,
        args: Map<String, Any?>? = null,
        timeoutMillis: Long = CALL_TIMEOUT_MILLIS,
    ): Any? {
        require(EXPORT_NAME.matches(exportName)) { exportName }
        val specifier = moduleSpecifier(manifest.main)
        return evaluate(caller(specifier), exportName, args, timeoutMillis)
    }

    suspend fun exports(): Set<String> {
        val specifier = moduleSpecifier(manifest.main)
        val value = evaluate(exportList(specifier), "exports", null, CALL_TIMEOUT_MILLIS)
        val names = value as? List<*> ?: return emptySet()
        return names.mapNotNull { it as? String }.toSet()
    }

    private suspend fun evaluate(
        script: String,
        exportName: String,
        args: Map<String, Any?>?,
        timeoutMillis: Long,
    ): Any? {
        val specifier = moduleSpecifier(manifest.main)
        val source = readModule(specifier)
        val dirPath = pluginDir.canonicalFile.path
        val dataPath = dataDir.canonicalFile.apply { mkdirs() }.path
        val records = PluginRecords(File(dataPath))
        val language = hostLanguage(locale)
        val arch = hostArch()
        return withContext(Dispatchers.IO) {
            withTimeout(timeoutMillis) {
                var captured: Any? = null
                var saw = false
                var failure: PluginCallException? = null
                quickJs {
                    memoryLimit = MEMORY_LIMIT_BYTES
                    define("console") {
                        function("log") { incoming -> console(Log.INFO, incoming) }
                        function("info") { incoming -> console(Log.INFO, incoming) }
                        function("warn") { incoming -> console(Log.WARN, incoming) }
                        function("error") { incoming -> console(Log.ERROR, incoming) }
                    }
                    define("host") {
                        property("platform") { getter { "android" } }
                        property("arch") { getter { arch } }
                        property("dir") { getter { dirPath } }
                        property("dataDir") { getter { dataPath } }
                        function("language") { _ -> language }
                        function("t") { incoming ->
                            val text = incoming.firstOrNull() as? String ?: return@function ""
                            translate(text)
                        }
                        function("setting") { incoming ->
                            records.setting(argString(incoming, 0))
                        }
                        define("storage") {
                            function("get") { incoming ->
                                when (val hit = records.storageGet(argString(incoming, 0))) {
                                    Stored.Missing -> Unit
                                    is Stored.Present -> hit.value
                                }
                            }
                            function("set") { incoming ->
                                val key = argString(incoming, 0)
                                val value = if (incoming.size < 2) null else incoming[1]
                                records.storageSet(key, value)
                            }
                            function("remove") { incoming ->
                                records.storageRemove(argString(incoming, 0))
                            }
                        }
                        define("crypto") {
                            function("md5") { incoming -> md5Hex(argString(incoming, 0)) }
                            function("aesEcbEncrypt") { incoming ->
                                aesEcbEncrypt(argString(incoming, 0), argString(incoming, 1))
                            }
                        }
                        function("openURL") { incoming ->
                            val address = incoming.firstOrNull() as? String ?: ""
                            host.openUrl(requireWebPage(address))
                        }
                        function("signInPrompt") { incoming ->
                            val raw = incoming.firstOrNull() as? Map<*, *>
                            val qr = raw?.get("qr") as? String ?: ""
                            val text = raw?.get("text") as? String ?: ""
                            host.signInPrompt(qr, text)
                        }
                        asyncFunction("waitForRedirect") { incoming ->
                            val raw = incoming.firstOrNull() as? Map<*, *>
                                ?: throw IllegalArgumentException("waitForRedirect")
                            val wait = redirectWait(manifest.network, raw)
                            awaitRedirect(wait.port, wait.path, wait.page, wait.timeoutMs).toJsObject()
                        }
                        asyncFunction("webSignIn") { incoming ->
                            val raw = incoming.firstOrNull() as? Map<*, *>
                                ?: throw IllegalArgumentException("webSignIn")
                            val request = webSignInRequest(manifest.network, raw)
                            mapOf("cookie" to host.webSignIn(request)).toJsObject()
                        }
                        function("spawn") { _ ->
                            throw IllegalStateException("This app does not run external programs.")
                        }
                    }
                    define("__mygramophone") {
                        property("exportName") { getter { exportName } }
                        property("hasArgs") { getter { args != null } }
                        property("args") { getter { args?.toJsObject() } }
                        function("result") { incoming ->
                            captured = incoming.firstOrNull()
                            saw = true
                        }
                        function("fail") { incoming ->
                            failure = PluginCallException(
                                incoming.getOrNull(0) as? String ?: "",
                                incoming.getOrNull(1) as? String ?: "",
                            )
                        }
                        asyncFunction("fetch") { incoming ->
                            val req = incoming.firstOrNull() as? Map<*, *>
                                ?: throw IllegalArgumentException("fetch")
                            PluginFetch.request(
                                network = manifest.network,
                                url = req["url"] as? String ?: throw IllegalArgumentException("fetch"),
                                method = req["method"] as? String ?: "GET",
                                headers = stringMap(req["headers"]),
                                body = req["body"] as? String,
                                redirect = req["redirect"] as? String ?: "follow",
                            )
                        }
                        asyncFunction("sleep") { incoming ->
                            val ms = (incoming.firstOrNull() as? Number)?.toLong() ?: 0L
                            delay(ms.coerceIn(0L, 60_000L))
                        }
                    }
                    // QuickJS has no URL. Plugins call the WHATWG one; the script is whatwg-url.
                    evaluate<Any?>(urlScript, filename = "mygramophone-url.js")
                    evaluate<Any?>(FETCH_SHIM, filename = "mygramophone-fetch.js")
                    evaluate<Any?>(TIMER_SHIM, filename = "mygramophone-timers.js")
                    addModule(specifier, source)
                    evaluate<Any?>(script, filename = CALLER, asModule = true)
                }
                if (failure != null) throw failure
                if (!saw) throw IllegalStateException("plugin returned nothing")
                captured
            }
        }
    }

    private fun console(priority: Int, args: Array<Any?>) {
        val text = args.joinToString(" ") { consoleText(it) }
        Log.println(priority, TAG, "plugin ${manifest.id} $text")
    }

    private fun translate(text: String): String {
        val tags = listOf(
            locale.toLanguageTag(),
            listOf(locale.language, locale.country).filter { it.isNotEmpty() }.joinToString("-"),
            locale.language,
        ).distinct()
        for (tag in tags) {
            val hit = manifest.locales[tag]?.get(text)
            if (!hit.isNullOrEmpty()) return hit
        }
        if (locale.language.equals("zh", ignoreCase = true)) {
            val hit = manifest.locales["zh-CN"]?.get(text)
            if (!hit.isNullOrEmpty()) return hit
        }
        return text
    }

    private fun readModule(relative: String): String {
        if (!isContainedRelative(relative)) throw IllegalArgumentException(relative)
        val file = File(pluginDir, relative)
        if (!file.isFile || !file.isInside(pluginDir)) throw IllegalArgumentException(relative)
        return file.readText(Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "Mygomusic"
        const val CALLER = "mygramophone-call.js"
        val urlScript: String by lazy {
            val stream = PluginRuntime::class.java.classLoader
                ?.getResourceAsStream("mygomusic/url.js")
                ?: throw IllegalStateException("mygomusic/url.js")
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val FETCH_SHIM = """
            globalThis.fetch = async function (input, init) {
              let url = input;
              let method = "GET";
              let headers = {};
              let body = null;
              let redirect = "follow";
              if (input !== null && typeof input === "object") {
                url = input.url;
                if (input.method) method = input.method;
                if (input.headers) headers = input.headers;
                if (input.body != null) body = input.body;
                if (input.redirect) redirect = input.redirect;
              }
              if (init != null) {
                if (init.method) method = init.method;
                if (init.headers) headers = init.headers;
                if (init.body != null) body = init.body;
                if (init.redirect) redirect = init.redirect;
              }
              if (body != null && typeof body !== "string") body = String(body);
              const raw = await globalThis.__mygramophone.fetch({
                url: String(url),
                method: String(method),
                headers: headers,
                body: body,
                redirect: String(redirect),
              });
              const headersOut = {
                get(name) {
                  const value = raw.headers[String(name).toLowerCase()];
                  return value == null ? null : String(value);
                },
                getSetCookie() {
                  return raw.setCookie ? raw.setCookie.slice() : [];
                },
                has(name) {
                  return this.get(name) != null;
                },
              };
              return {
                ok: raw.ok,
                status: raw.status,
                statusText: raw.statusText,
                url: raw.url,
                headers: headersOut,
                async text() { return raw.body; },
                async json() { return JSON.parse(raw.body); },
                async arrayBuffer() {
                  const bytes = raw.bodyBase64 ? base64Bytes(raw.bodyBase64) : latin1(raw.body || "");
                  return bytes.buffer;
                },
              };
            };
            function latin1(text) {
              const bytes = new Uint8Array(text.length);
              for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i) & 255;
              return bytes;
            }
            function base64Bytes(text) {
              const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
              const clean = String(text).replace(/=+$/, "");
              const out = [];
              let buffer = 0;
              let bits = 0;
              for (let i = 0; i < clean.length; i++) {
                const value = alphabet.indexOf(clean.charAt(i));
                if (value < 0) continue;
                buffer = (buffer << 6) | value;
                bits += 6;
                if (bits >= 8) {
                  bits -= 8;
                  out.push((buffer >> bits) & 255);
                }
              }
              const bytes = new Uint8Array(out.length);
              for (let i = 0; i < out.length; i++) bytes[i] = out[i];
              return bytes;
            }
        """.trimIndent()
        // quickjs-kt has no timers. QR login polls with setTimeout, and fetch aborts the same way.
        val TIMER_SHIM = """
            (function () {
              if (typeof globalThis.setTimeout !== "function") {
                const sleep = globalThis.__mygramophone.sleep;
                const queue = [];
                const repeats = new Map();
                let seq = 1;
                let pumping = false;
                function arm() {
                  if (pumping) return;
                  pumping = true;
                  (async () => {
                    try {
                      while (queue.length) {
                        let soonest = queue[0];
                        for (let i = 1; i < queue.length; i++) {
                          if (queue[i].at < soonest.at) soonest = queue[i];
                        }
                        const wait = soonest.at - Date.now();
                        if (wait > 50) await sleep(50);
                        else await sleep(Math.max(0, wait));
                        const now = Date.now();
                        let index = 0;
                        while (index < queue.length) {
                          if (queue[index].at > now) { index++; continue; }
                          const item = queue.splice(index, 1)[0];
                          try { item.fn(); } catch (e) { console.error(e); }
                        }
                      }
                    } finally {
                      pumping = false;
                      if (queue.length) arm();
                    }
                  })();
                }
                globalThis.setTimeout = function (fn, ms) {
                  if (typeof fn !== "function") return 0;
                  const id = seq++;
                  queue.push({ id: id, fn: fn, at: Date.now() + Math.max(0, Number(ms) || 0) });
                  arm();
                  return id;
                };
                globalThis.clearTimeout = function (id) {
                  const index = queue.findIndex(function (item) { return item.id === id; });
                  if (index >= 0) queue.splice(index, 1);
                };
                globalThis.setInterval = function (fn, ms) {
                  if (typeof fn !== "function") return 0;
                  const period = Math.max(0, Number(ms) || 0);
                  const handle = { id: seq++, on: true };
                  repeats.set(handle.id, handle);
                  const tick = function () {
                    if (!handle.on) return;
                    handle.timer = globalThis.setTimeout(function () {
                      if (!handle.on) return;
                      try { fn(); } catch (e) { console.error(e); }
                      tick();
                    }, period);
                  };
                  tick();
                  return handle.id;
                };
                globalThis.clearInterval = function (id) {
                  const handle = repeats.get(id);
                  if (!handle) return;
                  handle.on = false;
                  repeats.delete(id);
                  globalThis.clearTimeout(handle.timer);
                };
              }
              if (typeof globalThis.AbortController === "undefined") {
                function AbortSignal() { this.aborted = false; }
                AbortSignal.prototype.addEventListener = function () {};
                AbortSignal.prototype.removeEventListener = function () {};
                function AbortController() { this.signal = new AbortSignal(); }
                AbortController.prototype.abort = function () { this.signal.aborted = true; };
                globalThis.AbortController = AbortController;
              }
            })();
        """.trimIndent()
        const val MEMORY_LIMIT_BYTES = 256L * 1024 * 1024
        const val CALL_TIMEOUT_MILLIS = 30_000L
        val EXPORT_NAME = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

        fun moduleSpecifier(main: String): String {
            if (!isContainedRelative(main) || main.any { it == '"' || it == '\'' || it == '\n' || it == '\r' }) {
                throw IllegalArgumentException(main)
            }
            return main
        }

        fun caller(specifier: String): String = """
            import * as plugin from "$specifier";
            const bridge = globalThis.__mygramophone;
            const fn = plugin[bridge.exportName];
            if (typeof fn !== "function") {
              throw new Error("missing export " + bridge.exportName);
            }
            try {
              const value = bridge.hasArgs ? await fn(bridge.args) : await fn();
              bridge.result(value);
            } catch (e) {
              let code = "";
              let message = "";
              if (e != null && typeof e === "object") {
                if (e.code != null) code = String(e.code);
                if (e.message != null) message = String(e.message);
              } else if (e != null) {
                message = String(e);
              }
              bridge.fail(code, message);
            }
        """.trimIndent()

        fun exportList(specifier: String): String = """
            import * as plugin from "$specifier";
            const names = Object.keys(plugin).filter((key) => typeof plugin[key] === "function");
            globalThis.__mygramophone.result(names);
        """.trimIndent()

        fun hostLanguage(locale: Locale): String {
            return if (locale.language.equals("zh", ignoreCase = true)) "zh-CN" else "en"
        }

        fun hostArch(): String {
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            return when {
                abi.startsWith("arm64") -> "arm64"
                abi.startsWith("armeabi") || abi.startsWith("arm") -> "arm"
                abi.startsWith("x86_64") -> "x86_64"
                abi.startsWith("x86") -> "x86"
                else -> "arm64"
            }
        }
    }
}

internal fun pluginJsonText(value: Any?): String = when (val wrapped = pluginJson(value)) {
    JSONObject.NULL -> "null"
    is JSONObject, is JSONArray -> wrapped.toString()
    is String -> JSONObject.quote(wrapped)
    else -> wrapped.toString()
}

private fun stringMap(value: Any?): Map<String, String> {
    val map = value as? Map<*, *> ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    for ((key, item) in map) {
        if (key is String && item != null) out[key] = item as? String ?: item.toString()
    }
    return out
}

private fun argString(args: Array<Any?>, index: Int): String {
    val value = args.getOrNull(index)
    if (value !is String) throw IllegalArgumentException("expected a string")
    return value
}

private fun md5Hex(text: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
    return hex(digest)
}

private fun aesEcbEncrypt(key: String, text: String): String {
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
    return hex(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
}

private fun hex(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    val digits = "0123456789abcdef"
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xff
        out[i * 2] = digits[v ushr 4]
        out[i * 2 + 1] = digits[v and 0x0f]
    }
    return String(out)
}

internal fun pluginJson(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply {
        for ((key, item) in value) {
            if (key is String) put(key, pluginJson(item))
        }
    }
    is List<*> -> JSONArray().apply { value.forEach { put(pluginJson(it)) } }
    is Set<*> -> JSONArray().apply { value.forEach { put(pluginJson(it)) } }
    is Boolean, is String, is Int, is Long, is Double -> value
    is Float -> value.toDouble()
    is Byte, is Short -> value.toInt()
    else -> value.toString()
}

private fun consoleText(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is Map<*, *>, is List<*>, is Set<*> -> pluginJsonText(value)
    else -> value.toString()
}
