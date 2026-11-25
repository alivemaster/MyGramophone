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

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import java.lang.ref.WeakReference
import java.util.Locale

data class SettingSaveFailure(
    val key: String,
    val previous: String,
    val attempted: String,
    val message: String,
    /** The new value is already in settings.json. Discard has to write [previous] back. */
    val stored: Boolean,
)

data class PluginSettingsUi(
    val ready: Boolean = false,
    val missing: Boolean = false,
    val broken: Boolean = false,
    val title: String = "",
    val enabled: Boolean = true,
    val settings: List<PluginSetting> = emptyList(),
    val values: Map<String, String> = emptyMap(),
    val network: List<String> = emptyList(),
    /** False until sign-in exports have been read. The row stays hidden until then. */
    val sessionReady: Boolean = false,
    /** The plugin exports sign-in, sign-out, or account. */
    val offersSignIn: Boolean = false,
    /** The plugin exports signIn. The account row can start it. */
    val canSignIn: Boolean = false,
    val signedIn: Boolean = false,
    val accountName: String = "",
    val signingIn: Boolean = false,
    val promptText: String = "",
    val promptQr: Bitmap? = null,
    /** Shown after a sign-in attempt stops short of an account. */
    val signInMessage: String? = null,
    val saving: Boolean = false,
    val busy: Boolean = false,
    val failure: Boolean = false,
    val saveFailure: SettingSaveFailure? = null,
    val deleted: Boolean = false,
)

class PluginSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store by lazy { PluginStore(app) }
    val ui = MutableLiveData(PluginSettingsUi())
    private val gate = Mutex()
    private var started = false
    private var pluginId = ""
    private var gone = false
    private var pendingSaves = 0
    private var sessionBusy = false
    private var signInJob: Job? = null
    @Volatile private var signGeneration = 0
    private var hostActivity: WeakReference<Activity>? = null

    fun load(id: String) {
        if (started) return
        started = true
        pluginId = id
        if (!isPluginId(id)) {
            update { it.copy(missing = true) }
            return
        }
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { read(id) }
            if (gone) return@launch
            update { next }
            if (!next.ready || next.broken) return@launch
            val session = withContext(Dispatchers.IO) { readSession(id) }
            if (!gone) {
                update {
                    it.copy(
                        sessionReady = true,
                        offersSignIn = session.offered,
                        canSignIn = session.canSignIn,
                        signedIn = session.signedIn,
                        accountName = session.name,
                    )
                }
            }
        }
    }

    fun signOut() {
        val state = ui.value ?: return
        if (!state.signedIn || sessionBusy || gone) return
        sessionBusy = true
        viewModelScope.launch {
            val ok = try {
                gate.withLock {
                    if (gone) false else withContext(Dispatchers.IO) { runSignOut() }
                }
            } finally {
                sessionBusy = false
            }
            if (gone) return@launch
            if (!ok) {
                update { it.copy(failure = true) }
                return@launch
            }
            val session = withContext(Dispatchers.IO) { readSession(pluginId) }
            if (gone) return@launch
            update {
                it.copy(
                    sessionReady = true,
                    offersSignIn = session.offered,
                    canSignIn = session.canSignIn,
                    signedIn = session.signedIn,
                    accountName = session.name,
                )
            }
        }
    }

    fun attachHost(activity: Activity) {
        hostActivity = WeakReference(activity)
    }

    fun signIn() {
        val state = ui.value ?: return
        if (!state.canSignIn || state.signedIn || sessionBusy || gone) return
        sessionBusy = true
        val generation = ++signGeneration
        signInJob = viewModelScope.launch {
            if (generation != signGeneration) {
                sessionBusy = false
                return@launch
            }
            update {
                it.copy(signingIn = true, promptText = "", promptQr = null, signInMessage = null)
            }
            val host = settingsHost(generation)
            try {
                val session = gate.withLock {
                    if (gone || generation != signGeneration) null
                    else withContext(Dispatchers.IO) { runSignIn(host) }
                }
                if (gone || generation != signGeneration) return@launch
                if (session == null) {
                    update { it.copy(signingIn = false, promptText = "", promptQr = null) }
                    return@launch
                }
                if (!session.signedIn) {
                    showSignInFailure(R.string.mygomusic_sign_in_failed)
                    return@launch
                }
                update {
                    it.copy(
                        signingIn = false,
                        promptText = "",
                        promptQr = null,
                        sessionReady = true,
                        offersSignIn = session.offered,
                        canSignIn = session.canSignIn,
                        signedIn = session.signedIn,
                        accountName = session.name,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                if (generation == signGeneration) showSignInFailure(R.string.mygomusic_sign_in_timeout)
            } catch (e: CancellationException) {
                if (generation == signGeneration) {
                    update { it.copy(signingIn = false, promptText = "", promptQr = null) }
                }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "signIn $pluginId", e)
                if (generation == signGeneration) {
                    val message = e.message?.trim().orEmpty().ifEmpty {
                        getApplication<Application>().getString(R.string.mygomusic_sign_in_failed)
                    }
                    update {
                        it.copy(
                            signingIn = false,
                            promptText = "",
                            promptQr = null,
                            signInMessage = message,
                        )
                    }
                }
            } finally {
                if (generation == signGeneration) sessionBusy = false
            }
        }
    }

    fun cancelSignIn() {
        if (signInJob?.isActive != true && ui.value?.signingIn != true) return
        signGeneration++
        signInJob?.cancel()
        sessionBusy = false
        update { it.copy(signingIn = false, promptText = "", promptQr = null) }
    }

    fun clearSignInMessage() {
        update { it.copy(signInMessage = null) }
    }

    fun setEnabled(on: Boolean) {
        if (!started || gone) return
        viewModelScope.launch {
            val ok = gate.withLock {
                if (gone) return@withLock false
                withContext(Dispatchers.IO) {
                    try {
                        store.setEnabled(pluginId, on)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "enabled", e)
                        false
                    }
                }
            }
            if (gone) return@launch
            if (ok) update { it.copy(enabled = on) } else update { it.copy(failure = true) }
        }
    }

    fun clearFailure() {
        update { it.copy(failure = false) }
    }

    fun clearSaveFailure() {
        update { it.copy(saveFailure = null) }
    }

    fun save(key: String, value: String, previous: String) {
        if (!started || gone || key.isEmpty()) return
        viewModelScope.launch {
            pendingSaves++
            update { it.copy(saving = true, saveFailure = null) }
            val result = try {
                gate.withLock {
                    if (gone) null else withContext(Dispatchers.IO) { notifySetting(key, value) }
                }
            } finally {
                pendingSaves--
            }
            if (result == null || gone) {
                update { it.copy(saving = pendingSaves > 0) }
                return@launch
            }
            update {
                it.copy(
                    saving = pendingSaves > 0,
                    values = if (result.stored) it.values + (key to value) else it.values,
                    saveFailure = result.error?.let { message ->
                        SettingSaveFailure(key, previous, value, message, result.stored)
                    },
                )
            }
        }
    }

    fun delete(deleteData: Boolean) {
        if (!started || gone || ui.value?.busy == true || ui.value?.signingIn == true) return
        viewModelScope.launch {
            update { it.copy(busy = true, failure = false) }
            val ok = gate.withLock {
                gone = true
                withContext(Dispatchers.IO) {
                    try {
                        store.delete(pluginId, deleteData)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "delete", e)
                        false
                    }
                }
            }
            if (ok) {
                update { it.copy(busy = false, deleted = true) }
            } else {
                gone = false
                update { it.copy(busy = false, failure = true) }
            }
        }
    }

    private fun read(id: String): PluginSettingsUi {
        return try {
            val dir = store.packageDir(id)
            if (!dir.isDirectory) return PluginSettingsUi(missing = true)
            val enabled = store.isEnabled(id)
            val manifest = try {
                PluginManifest.read(dir)
            } catch (e: Exception) {
                Log.w(TAG, "plugin $id", e)
                return PluginSettingsUi(ready = true, broken = true, enabled = enabled, title = id)
            }
            val records = PluginRecords(store.dataDir(id))
            val locale = locale()
            val settings = manifest.settings.map { display(manifest, it, locale) }
            PluginSettingsUi(
                ready = true,
                title = manifest.title(locale),
                enabled = enabled,
                settings = settings,
                values = settings.associate { it.key to records.setting(it.key) },
                network = manifest.network,
            )
        } catch (e: Exception) {
            Log.w(TAG, "plugin $id", e)
            PluginSettingsUi(missing = true)
        }
    }

    /**
     * The file is written first: host.setting reads it, and setSetting is told afterwards
     * when the plugin exports that. A missing export is not a failure.
     */
    private suspend fun notifySetting(key: String, value: String): SaveResult {
        try {
            PluginRecords(store.dataDir(pluginId)).setSetting(key, value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "setting $key", e)
            return SaveResult(e.message?.trim().orEmpty(), stored = false)
        }
        return try {
            val dir = store.packageDir(pluginId)
            val manifest = PluginManifest.read(dir)
            val runtime = PluginRuntime(manifest, dir, store.dataDir(pluginId), locale())
            if ("setSetting" in runtime.exports()) {
                runtime.call("setSetting", mapOf("key" to key, "value" to value))
            }
            SaveResult(null, stored = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "setting $key", e)
            SaveResult(e.message?.trim().orEmpty(), stored = true)
        }
    }

    private suspend fun readSession(id: String): PluginSession {
        val dir = store.packageDir(id)
        val manifest = PluginManifest.read(dir)
        val runtime = PluginRuntime(manifest, dir, store.dataDir(id), locale())
        val exports = try {
            runtime.exports()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "account $id", e)
            return PluginSession(false, false, false, "")
        }
        val offered = "signIn" in exports || "signOut" in exports || "account" in exports
        val canSignIn = "signIn" in exports
        if (!offered || "account" !in exports) return PluginSession(offered, canSignIn, false, "")
        return try {
            val name = accountLabel(runtime.call("account"))
            if (name == null) PluginSession(offered, canSignIn, false, "")
            else PluginSession(offered, canSignIn, true, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "account $id", e)
            PluginSession(offered, canSignIn, false, "")
        }
    }

    private suspend fun runSignIn(host: PluginHost): PluginSession {
        val dir = store.packageDir(pluginId)
        val manifest = PluginManifest.read(dir)
        val runtime = PluginRuntime(manifest, dir, store.dataDir(pluginId), locale(), host)
        try {
            runtime.call("signIn", timeoutMillis = SIGN_IN_TIMEOUT_MILLIS)
        } catch (e: IllegalStateException) {
            if (e.message != "plugin returned nothing") throw e
        }
        return readSession(pluginId)
    }

    private suspend fun runSignOut(): Boolean {
        return try {
            val dir = store.packageDir(pluginId)
            val manifest = PluginManifest.read(dir)
            val runtime = PluginRuntime(manifest, dir, store.dataDir(pluginId), locale())
            if ("signOut" in runtime.exports()) {
                try {
                    runtime.call("signOut")
                } catch (e: IllegalStateException) {
                    // signOut often returns undefined. The call still ran.
                    if (e.message != "plugin returned nothing") throw e
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "signOut $pluginId", e)
            false
        }
    }

    private fun display(manifest: PluginManifest, setting: PluginSetting, locale: Locale): PluginSetting {
        return setting.copy(
            label = manifest.text(locale, setting.label).ifEmpty { setting.key },
            help = manifest.text(locale, setting.help),
            options = setting.options.map { it.copy(label = manifest.text(locale, it.label)) },
        )
    }

    private fun locale(): Locale {
        val locales = ConfigurationCompat.getLocales(getApplication<Application>().resources.configuration)
        return locales[0] ?: Locale.getDefault()
    }

    private inline fun update(block: (PluginSettingsUi) -> PluginSettingsUi) {
        ui.value = block(ui.value ?: PluginSettingsUi())
    }

    private class SaveResult(val error: String?, val stored: Boolean)

    private data class PluginSession(
        val offered: Boolean,
        val canSignIn: Boolean,
        val signedIn: Boolean,
        val name: String,
    )

    private fun settingsHost(generation: Int): PluginHost {
        return object : PluginHost {
            override fun openUrl(url: String) {
                if (generation != signGeneration) return
                PluginWeb.open(hostContext(), url)
            }

            override fun signInPrompt(qr: String, text: String) {
                if (generation != signGeneration) return
                val bitmap = qrBitmap(qr)
                viewModelScope.launch(Dispatchers.Main) {
                    if (generation != signGeneration) return@launch
                    update { it.copy(promptText = text, promptQr = bitmap, signingIn = true) }
                }
            }

            override suspend fun webSignIn(request: WebSignInRequest): String {
                if (generation != signGeneration) throw CancellationException()
                return PluginWeb.signIn(hostContext(), request)
            }
        }
    }

    private fun hostContext(): android.content.Context {
        val activity = hostActivity?.get()
        if (activity != null && !activity.isDestroyed) return activity
        return getApplication()
    }

    private fun showSignInFailure(message: Int) {
        update {
            it.copy(
                signingIn = false,
                promptText = "",
                promptQr = null,
                signInMessage = getApplication<Application>().getString(message),
            )
        }
    }

    private companion object {
        const val TAG = "Mygomusic"
    }
}
