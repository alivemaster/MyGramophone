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

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.os.ConfigurationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.Locale
import java.util.UUID

data class PluginListUi(
    val plugins: List<InstalledPlugin> = emptyList(),
    val busy: Boolean = false,
    val confirm: PluginManifest? = null,
    val failure: PluginPackageException? = null,
)

class PluginListViewModel(app: Application) : AndroidViewModel(app) {
    // filesDir stats on every read. First touch stays on the IO dispatcher.
    private val store by lazy { PluginStore(app) }
    val ui = MutableLiveData(PluginListUi())
    private var pending: StagedPlugin? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val plugins = withContext(Dispatchers.IO) { store.list(locale()) }
            update { it.copy(plugins = plugins) }
        }
    }

    fun stageZip(uri: Uri) {
        if (ui.value?.busy == true) return
        viewModelScope.launch {
            update { it.copy(busy = true, failure = null, confirm = null) }
            try {
                val staged = withContext(Dispatchers.IO) {
                    val cache = getApplication<Application>().cacheDir
                    val zip = File(cache, "mygomusic-archive-${UUID.randomUUID()}.zip")
                    val granted = takeRead(uri)
                    try {
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                            zip.outputStream().use { output ->
                                PluginPackages.copyLimited(input, output, MAX_ARCHIVE_BYTES)
                            }
                        } ?: throw PluginPackageException(PluginFailureKind.Io)
                        PluginPackages.stageZip(zip, cache)
                    } finally {
                        zip.delete()
                        if (granted) releaseRead(uri)
                    }
                }
                publish(staged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PluginPackageException) {
                Log.w(TAG, "stage zip", e)
                update { it.copy(busy = false, failure = e) }
            } catch (e: Exception) {
                Log.w(TAG, "stage zip", e)
                update { it.copy(busy = false, failure = PluginPackageException(PluginFailureKind.Io, cause = e)) }
            }
        }
    }

    fun stageTree(uri: Uri) {
        if (ui.value?.busy == true) return
        viewModelScope.launch {
            update { it.copy(busy = true, failure = null, confirm = null) }
            try {
                val staged = withContext(Dispatchers.IO) {
                    val granted = takeRead(uri)
                    try {
                        val tree = DocumentFile.fromTreeUri(getApplication(), uri)
                            ?: throw PluginPackageException(PluginFailureKind.NotAPlugin)
                        PluginPackages.stageDocumentTree(
                            getApplication(),
                            tree,
                            getApplication<Application>().cacheDir,
                        )
                    } finally {
                        if (granted) releaseRead(uri)
                    }
                }
                publish(staged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PluginPackageException) {
                Log.w(TAG, "stage folder", e)
                update { it.copy(busy = false, failure = e) }
            } catch (e: Exception) {
                Log.w(TAG, "stage folder", e)
                update { it.copy(busy = false, failure = PluginPackageException(PluginFailureKind.Io, cause = e)) }
            }
        }
    }

    fun confirmInstall() {
        val staged = pending ?: return
        if (ui.value?.busy == true) return
        viewModelScope.launch {
            yield()
            if (pending !== staged) return@launch
            pending = null
            update { it.copy(busy = true, confirm = null) }
            try {
                val plugins = withContext(Dispatchers.IO) {
                    try {
                        store.install(staged.root, staged.manifest)
                        store.list(locale())
                    } finally {
                        staged.discard()
                    }
                }
                update { it.copy(busy = false, plugins = plugins) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PluginPackageException) {
                Log.w(TAG, "install", e)
                update { it.copy(busy = false, failure = e) }
            } catch (e: Exception) {
                Log.w(TAG, "install", e)
                update { it.copy(busy = false, failure = PluginPackageException(PluginFailureKind.Io, cause = e)) }
            }
        }
    }

    fun discardPending() {
        val staged = pending
        pending = null
        update { it.copy(confirm = null) }
        if (staged != null) {
            viewModelScope.launch(Dispatchers.IO) { staged.discard() }
        }
    }

    fun clearFailure() {
        update { it.copy(failure = null) }
    }

    fun delete(id: String, deleteData: Boolean) {
        if (ui.value?.busy == true) return
        viewModelScope.launch {
            update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) { store.delete(id, deleteData) }
                val plugins = withContext(Dispatchers.IO) { store.list(locale()) }
                update { it.copy(busy = false, plugins = plugins) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "delete", e)
                update { it.copy(busy = false, failure = PluginPackageException(PluginFailureKind.Io, cause = e)) }
            }
        }
    }

    override fun onCleared() {
        val staged = pending
        pending = null
        // viewModelScope is already cancelled here.
        if (staged != null) {
            CoroutineScope(Dispatchers.IO).launch { staged.discard() }
        }
    }

    private suspend fun publish(staged: StagedPlugin) {
        val allowed = withContext(Dispatchers.IO) { store.canReplaceWithLocal(staged.manifest.id) }
        if (!allowed) {
            val title = staged.manifest.title(locale())
            withContext(Dispatchers.IO) { staged.discard() }
            update {
                it.copy(
                    busy = false,
                    failure = PluginPackageException(PluginFailureKind.AlreadyInstalled, title),
                )
            }
            return
        }
        val previous = pending
        pending = staged
        if (previous != null) {
            withContext(Dispatchers.IO) { previous.discard() }
        }
        update { it.copy(busy = false, confirm = staged.manifest) }
    }

    private fun locale(): Locale {
        val locales = ConfigurationCompat.getLocales(getApplication<Application>().resources.configuration)
        return locales[0] ?: Locale.getDefault()
    }

    private fun takeRead(uri: Uri): Boolean {
        return try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun releaseRead(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
    }

    private inline fun update(block: (PluginListUi) -> PluginListUi) {
        ui.value = block(ui.value ?: PluginListUi())
    }

    private companion object {
        const val TAG = "Mygomusic"
    }
}
