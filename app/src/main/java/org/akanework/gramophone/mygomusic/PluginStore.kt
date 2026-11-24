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
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class InstalledPlugin(
    val directoryName: String,
    val title: String,
    val version: String,
    val broken: Boolean,
)

data class PluginOrigin(
    val repo: String,
    val version: String,
)

/**
 * plugins/<id> is the package. data/<id> is kept when the package is deleted.
 * .order.json is the list order. .origin.json is where that package came from:
 * a local install stores the version and no repo, a GitHub install also stores
 * owner/repo. A package is replaced only by another from the same place.
 */
class PluginStore(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "mygomusic"))

    private val pluginsDir: File get() = File(root, "plugins")

    fun dataDir(id: String): File {
        if (!isPluginId(id)) throw PluginPackageException(PluginFailureKind.BadId, id)
        return File(File(root, "data"), id)
    }

    fun list(locale: Locale): List<InstalledPlugin> {
        val dirs = pluginsDir.listFiles().orEmpty().filter {
            it.isDirectory && isPluginId(it.name)
        }
        val byName = dirs.associateBy { it.name }
        val order = readOrder()
        val ordered = order.mapNotNull { byName[it] }
        val rest = dirs.filter { it.name !in order }.sortedBy { it.name }
        return (ordered + rest).map { dir ->
            try {
                val manifest = PluginManifest.read(dir)
                InstalledPlugin(dir.name, manifest.title(locale), manifest.version, false)
            } catch (e: Exception) {
                // Kept visible so a package that no longer parses can still be deleted.
                Log.w(TAG, "plugin ${dir.name}", e)
                InstalledPlugin(dir.name, dir.name, "", true)
            }
        }
    }

    /** [repo] empty means a folder or zip from this device. */
    fun canReplace(id: String, repo: String): Boolean {
        val dir = pluginDir(id)
        if (!dir.exists()) return true
        if (!dir.isDirectory) return false
        return readOrigin(dir).repo == repo
    }

    fun install(
        pluginRoot: File,
        manifest: PluginManifest,
        repo: String = "",
        originVersion: String = manifest.version,
    ) {
        if (!isPluginId(manifest.id)) {
            throw PluginPackageException(PluginFailureKind.BadId, manifest.id)
        }
        pluginsDir.mkdirs()
        val target = pluginDir(manifest.id)
        if (target.exists() && !target.isDirectory) {
            throw PluginPackageException(PluginFailureKind.Io)
        }
        if (target.isDirectory && readOrigin(target).repo != repo) {
            throw PluginPackageException(PluginFailureKind.AlreadyInstalled, manifest.id)
        }
        writeOrigin(pluginRoot, originVersion, repo)
        place(pluginRoot, target)
        remember(manifest.id)
    }

    fun delete(id: String, deleteData: Boolean = false) {
        if (deleteData) {
            val data = dataDir(id)
            if (data.exists() && !data.deleteRecursively()) {
                throw PluginPackageException(PluginFailureKind.Io)
            }
        }
        val dir = pluginDir(id)
        if (dir.exists() && !dir.deleteRecursively()) {
            throw PluginPackageException(PluginFailureKind.Io)
        }
        val order = readOrder()
        if (order.remove(id)) writeOrder(order)
    }

    fun readOrigin(dir: File): PluginOrigin {
        val file = File(dir, ORIGIN_FILE)
        if (!file.isFile) return PluginOrigin("", "")
        return try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            PluginOrigin(obj.optString("repo", ""), obj.optString("version", ""))
        } catch (_: Exception) {
            PluginOrigin("", "")
        }
    }

    private fun pluginDir(id: String): File {
        if (!isPluginId(id)) throw PluginPackageException(PluginFailureKind.BadId, id)
        return File(pluginsDir, id)
    }

    private fun writeOrigin(dir: File, version: String, repo: String) {
        val json = JSONObject()
        if (repo.isNotEmpty()) json.put("repo", repo)
        json.put("version", version)
        val tmp = File(dir, "$ORIGIN_FILE.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        val dest = File(dir, ORIGIN_FILE)
        if (dest.exists() && !dest.delete()) throw PluginPackageException(PluginFailureKind.Io)
        if (!tmp.renameTo(dest)) throw PluginPackageException(PluginFailureKind.Io)
    }

    private fun place(pluginRoot: File, target: File) {
        val backup = File(pluginsDir, ".replacing-${target.name}")
        if (backup.exists()) backup.deleteRecursively()
        val had = target.exists()
        if (had && !target.renameTo(backup)) throw PluginPackageException(PluginFailureKind.Io)
        try {
            moveTree(pluginRoot, target)
        } catch (e: Throwable) {
            if (had) backup.renameTo(target)
            if (e is PluginPackageException) throw e
            throw PluginPackageException(PluginFailureKind.Io, cause = e as? Exception)
        }
        backup.deleteRecursively()
    }

    private fun moveTree(from: File, to: File) {
        if (from.renameTo(to)) return
        PluginPackages.copyTree(from, to, skipHidden = false)
        if (!from.deleteRecursively()) throw PluginPackageException(PluginFailureKind.Io)
    }

    private fun remember(id: String) {
        val order = readOrder()
        if (id in order) return
        order.add(id)
        writeOrder(order)
    }

    private fun readOrder(): MutableList<String> {
        val file = File(pluginsDir, ORDER_FILE)
        if (!file.isFile) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            MutableList(arr.length()) { arr.getString(it) }.filter { isPluginId(it) }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeOrder(ids: List<String>) {
        pluginsDir.mkdirs()
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        val tmp = File(pluginsDir, "$ORDER_FILE.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        val dest = File(pluginsDir, ORDER_FILE)
        if (dest.exists() && !dest.delete()) throw PluginPackageException(PluginFailureKind.Io)
        if (!tmp.renameTo(dest)) throw PluginPackageException(PluginFailureKind.Io)
    }

    private companion object {
        const val TAG = "Mygomusic"
        const val ORIGIN_FILE = ".origin.json"
        const val ORDER_FILE = ".order.json"
    }
}
