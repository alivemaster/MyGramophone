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

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Host version told to plugins. This is not a mygo-music release.
 *
 * [minAppVersion] is stored and not compared with this string. A plugin's
 * "0.1.x" is a mygo-music version; treating [HOST_VERSION] as one of those
 * would either pretend to be mygo-music or reject every current plugin.
 * A numeric minAppVersion is refused only after this app records that it
 * does not implement the host ability that version requires. Nothing is
 * recorded yet. Install does not run the plugin: a compiled `.js` main
 * inside the package is enough. TypeScript is not compiled.
 */
const val HOST_VERSION = "0.1.0-mygramophone"

internal const val LOCAL_PLUGIN_ID = "local"
internal val PLUGIN_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")

internal const val MAX_ARCHIVE_BYTES = 1L shl 30
internal const val MAX_UNPACKED_BYTES = 2L shl 30
internal const val MAX_PLUGIN_FILES = 50_000
private const val MAX_MANIFEST_BYTES = 1L shl 20

enum class PluginFailureKind {
    NotAPlugin,
    BadId,
    MainNotCompiled,
    ZipSlip,
    Link,
    TooLarge,
    TooManyFiles,
    BadManifest,
    AlreadyInstalled,
    Io,
}

class PluginPackageException(
    val kind: PluginFailureKind,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail, cause)

internal fun isPluginId(id: String): Boolean = id != LOCAL_PLUGIN_ID && PLUGIN_ID.matches(id)

/** A relative path that cannot climb out of the plugin directory. */
internal fun isContainedRelative(path: String): Boolean {
    if (path.isEmpty() || path[0] == '/' || path.contains('\\') || path.contains('\u0000')) {
        return false
    }
    return path.split('/').none { it.isEmpty() || it == ".." }
}

internal fun File.isInside(root: File): Boolean {
    val rootCanon = root.canonicalFile
    val childPath = canonicalFile.path
    val rootPath = rootCanon.path.trimEnd('\\', '/') + File.separator
    return if (File.separatorChar == '\\') {
        childPath.equals(rootCanon.path, ignoreCase = true) ||
            childPath.startsWith(rootPath, ignoreCase = true)
    } else {
        childPath == rootCanon.path || childPath.startsWith(rootPath)
    }
}

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val main: String,
    val minAppVersion: String?,
    val network: List<String>,
    val process: List<String>,
    val locales: Map<String, Map<String, String>>,
) {
    fun title(locale: Locale): String {
        val tags = listOf(
            locale.toLanguageTag(),
            listOf(locale.language, locale.country).filter { it.isNotEmpty() }.joinToString("-"),
            locale.language,
        ).distinct()
        for (tag in tags) {
            val hit = locales[tag]?.get(name)
            if (!hit.isNullOrEmpty()) return hit
        }
        if (locale.language.equals("zh", ignoreCase = true)) {
            val hit = locales["zh-CN"]?.get(name)
            if (!hit.isNullOrEmpty()) return hit
        }
        return name
    }

    companion object {
        fun read(root: File): PluginManifest {
            try {
                return parse(root)
            } catch (e: PluginPackageException) {
                throw e
            } catch (e: Exception) {
                throw PluginPackageException(PluginFailureKind.BadManifest, cause = e)
            }
        }

        private fun parse(root: File): PluginManifest {
            val file = File(root, "plugin.json")
            if (!file.isFile) throw PluginPackageException(PluginFailureKind.NotAPlugin)
            if (file.length() > MAX_MANIFEST_BYTES) {
                throw PluginPackageException(PluginFailureKind.BadManifest)
            }
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val id = requiredString(obj, "id")
            if (!isPluginId(id)) throw PluginPackageException(PluginFailureKind.BadId, id)
            val name = optionalString(obj, "name")?.takeIf { it.isNotEmpty() } ?: id
            val version = optionalString(obj, "version") ?: ""
            val minAppVersion = optionalString(obj, "minAppVersion")
            val main = optionalString(obj, "main")?.takeIf { it.isNotEmpty() } ?: "index.js"
            if (!main.endsWith(".js") || !isContainedRelative(main)) {
                throw PluginPackageException(PluginFailureKind.MainNotCompiled, main)
            }
            val mainFile = File(root, main)
            if (!mainFile.isFile || !mainFile.isInside(root)) {
                throw PluginPackageException(PluginFailureKind.MainNotCompiled, main)
            }
            if (obj.has("permissions") && obj.optJSONObject("permissions") == null) {
                throw PluginPackageException(PluginFailureKind.BadManifest)
            }
            if (obj.has("requestHeaders") && obj.optJSONArray("requestHeaders") == null) {
                throw PluginPackageException(PluginFailureKind.BadManifest)
            }
            val (network, process) = permissions(obj.optJSONObject("permissions"))
            checkRequestHeaders(network, obj.optJSONArray("requestHeaders"))
            return PluginManifest(
                id = id,
                name = name,
                version = version,
                main = main,
                minAppVersion = minAppVersion,
                network = network,
                process = process,
                locales = locales(root),
            )
        }

        private fun requiredString(obj: JSONObject, key: String): String {
            if (obj.opt(key) !is String) throw PluginPackageException(PluginFailureKind.BadManifest)
            return obj.getString(key)
        }

        private fun optionalString(obj: JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            if (obj.opt(key) !is String) throw PluginPackageException(PluginFailureKind.BadManifest)
            return obj.getString(key)
        }

        private fun permissions(obj: JSONObject?): Pair<List<String>, List<String>> {
            if (obj == null) return emptyList<String>() to emptyList()
            val network = mutableListOf<String>()
            if (obj.has("network")) {
                val arr = obj.optJSONArray("network")
                    ?: throw PluginPackageException(PluginFailureKind.BadManifest)
                for (i in 0 until arr.length()) network += arr.getString(i)
            }
            val process = mutableListOf<String>()
            if (obj.has("process")) {
                when (val raw = obj.get("process")) {
                    is Boolean -> if (raw) process += "*"
                    is JSONArray -> for (i in 0 until raw.length()) process += raw.getString(i)
                    else -> throw PluginPackageException(PluginFailureKind.BadManifest)
                }
            }
            return network to process
        }

        private fun checkRequestHeaders(network: List<String>, rules: JSONArray?) {
            if (rules == null) return
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i)
                    ?: throw PluginPackageException(PluginFailureKind.BadManifest)
                val hosts = rule.optJSONArray("hosts")
                    ?: throw PluginPackageException(PluginFailureKind.BadManifest)
                val headers = rule.optJSONObject("headers")
                    ?: throw PluginPackageException(PluginFailureKind.BadManifest)
                if (hosts.length() == 0 || headers.length() == 0) {
                    throw PluginPackageException(PluginFailureKind.BadManifest)
                }
                val kinds = rule.optJSONArray("kinds")
                if (kinds != null) {
                    for (k in 0 until kinds.length()) {
                        val kind = kinds.optString(k)
                        if (kind != "image" && kind != "audio") {
                            throw PluginPackageException(PluginFailureKind.BadManifest)
                        }
                    }
                }
                for (h in 0 until hosts.length()) {
                    val host = hosts.optString(h)
                    if (host.startsWith("{setting:") && host.endsWith("}")) {
                        val key = host.removePrefix("{setting:").removeSuffix("}")
                        if (key.isEmpty() || key.contains('{') || key.contains('}')) {
                            throw PluginPackageException(PluginFailureKind.BadManifest)
                        }
                        continue
                    }
                    if (host == "*" || host.any { it in "{}/:" } || !covers(network, host)) {
                        throw PluginPackageException(PluginFailureKind.BadManifest)
                    }
                }
                val names = headers.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    if (name.equals("host", true) || name.equals("content-length", true)) {
                        throw PluginPackageException(PluginFailureKind.BadManifest)
                    }
                }
            }
        }

        private fun covers(network: List<String>, pattern: String): Boolean {
            val wanted = pattern.lowercase()
            for (raw in network) {
                val host = raw.lowercase()
                if (host == "*" || host == wanted) return true
                if (host.startsWith("*.")) {
                    val suffix = host.substring(1)
                    val trimmed = if (wanted.startsWith("*")) wanted.substring(1) else wanted
                    if (trimmed.endsWith(suffix)) return true
                }
            }
            return false
        }

        private fun locales(root: File): Map<String, Map<String, String>> {
            val dir = File(root, "locales")
            if (!dir.isDirectory) return emptyMap()
            val out = mutableMapOf<String, Map<String, String>>()
            for (file in dir.listFiles().orEmpty()) {
                if (!file.isFile || !file.name.endsWith(".json")) continue
                val code = file.name.removeSuffix(".json")
                if (!code.matches(Regex("^[A-Za-z0-9-]+$"))) continue
                if (file.length() > MAX_MANIFEST_BYTES) {
                    throw PluginPackageException(PluginFailureKind.BadManifest)
                }
                val parsed = JSONObject(file.readText(Charsets.UTF_8))
                val texts = mutableMapOf<String, String>()
                val keys = parsed.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = parsed.opt(key)
                    if (value !is String) throw PluginPackageException(PluginFailureKind.BadManifest)
                    texts[key] = value
                }
                out[code] = texts
            }
            return out
        }
    }
}
