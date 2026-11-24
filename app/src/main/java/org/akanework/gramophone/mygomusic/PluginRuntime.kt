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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * One QuickJS runtime for a single export call, then closed.
 * quickjs-kt 1.0.5 counts [MEMORY_LIMIT_BYTES] in bytes and has no
 * evaluation timeout, so the coroutine timeout is the call limit.
 */
class PluginRuntime(
    private val manifest: PluginManifest,
    private val pluginDir: File,
    private val dataDir: File,
    private val locale: Locale,
) {
    suspend fun call(
        exportName: String,
        args: Map<String, Any?>? = null,
        timeoutMillis: Long = CALL_TIMEOUT_MILLIS,
    ): Any? {
        require(EXPORT_NAME.matches(exportName)) { exportName }
        val specifier = moduleSpecifier(manifest.main)
        val source = readModule(specifier)
        val dirPath = pluginDir.canonicalFile.path
        val dataPath = dataDir.canonicalFile.apply { mkdirs() }.path
        val language = hostLanguage(locale)
        val arch = hostArch()
        return withContext(Dispatchers.IO) {
            withTimeout(timeoutMillis) {
                var captured: Any? = null
                var saw = false
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
                        function("setting") { _ -> "" }
                        define("storage") {
                            function("get") { _ -> null }
                            function("set") { _ -> }
                            function("remove") { _ -> }
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
                    }
                    addModule(specifier, source)
                    evaluate<Any?>(caller(specifier), filename = CALLER, asModule = true)
                }
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
            const value = bridge.hasArgs ? await fn(bridge.args) : await fn();
            bridge.result(value);
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

private fun pluginJson(value: Any?): Any = when (value) {
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
