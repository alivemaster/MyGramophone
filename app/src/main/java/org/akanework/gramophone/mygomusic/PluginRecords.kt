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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A stored JSON value. [Missing] is JavaScript `undefined`, not JSON null. */
internal sealed interface Stored {
    data object Missing : Stored
    class Present(val value: Any?) : Stored
}

/** The setting and storage reads used when filling request headers. */
internal interface HeaderValues {
    fun setting(key: String): String
    fun storageGet(key: String): Stored
}

/**
 * settings.json is an object of strings. storage.json is an object of JSON
 * values. Both live in the plugin data directory and stay when the package
 * is deleted.
 */
internal class PluginRecords(private val dir: File) : HeaderValues {
    private val lock = lockFor(dir)

    override fun setting(key: String): String = synchronized(lock) {
        settingText(read(SETTINGS).opt(key))
    }

    fun setSetting(key: String, value: String) {
        synchronized(lock) {
            val root = read(SETTINGS)
            root.put(key, value)
            write(SETTINGS, root)
        }
        PluginHeaderCache.invalidate()
    }

    override fun storageGet(key: String): Stored = synchronized(lock) {
        val root = read(STORAGE)
        if (!root.has(key)) return Stored.Missing
        return Stored.Present(jsonToJs(root.get(key)))
    }

    fun storageSet(key: String, value: Any?) {
        synchronized(lock) {
            val root = read(STORAGE)
            root.put(key, pluginJson(value))
            write(STORAGE, root)
        }
        PluginHeaderCache.invalidate()
    }

    fun storageRemove(key: String) {
        synchronized(lock) {
            val root = read(STORAGE)
            root.remove(key)
            write(STORAGE, root)
        }
        PluginHeaderCache.invalidate()
    }

    /** A copy that header filling can keep without reading this directory again. */
    fun freeze(): HeaderValues = synchronized(lock) {
        val settingsJson = read(SETTINGS)
        val settings = buildMap {
            val keys = settingsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, settingText(settingsJson.opt(key)))
            }
        }
        val storageJson = read(STORAGE)
        val storage = buildMap {
            val keys = storageJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, Stored.Present(jsonToJs(storageJson.get(key))))
            }
        }
        FrozenRecords(settings, storage)
    }

    private fun settingText(value: Any?): String = when (value) {
        is String -> value
        null, JSONObject.NULL -> ""
        else -> value.toString()
    }

    private fun read(name: String): JSONObject {
        val file = File(dir, name)
        if (!file.isFile) return JSONObject()
        try {
            return JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: JSONException) {
            throw IOException(name, e)
        }
    }

    private fun write(name: String, obj: JSONObject) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException(name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(obj.toString(), Charsets.UTF_8)
        val dest = File(dir, name)
        if (dest.exists() && !dest.delete()) throw IOException(name)
        if (!tmp.renameTo(dest)) throw IOException(name)
    }

    private companion object {
        const val SETTINGS = "settings.json"
        const val STORAGE = "storage.json"
        private val locks = ConcurrentHashMap<String, Any>()

        fun lockFor(dir: File): Any {
            val key = try {
                dir.canonicalPath
            } catch (_: IOException) {
                dir.absolutePath
            }
            return locks.getOrPut(key) { Any() }
        }
    }
}

private class FrozenRecords(
    private val settings: Map<String, String>,
    private val storage: Map<String, Stored>,
) : HeaderValues {
    override fun setting(key: String): String = settings[key] ?: ""

    override fun storageGet(key: String): Stored = storage[key] ?: Stored.Missing
}

internal fun jsonToJs(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> {
        val out = LinkedHashMap<String, Any?>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = jsonToJs(value.get(key))
        }
        out.toJsObject()
    }
    is JSONArray -> List(value.length()) { jsonToJs(value.get(it)) }
    is JsObject -> value
    else -> value
}
