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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private val REPO_NAME = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

/**
 * A desktop build takes the zip named for its OS, because that zip holds
 * helper programs. This app runs the plugin's JavaScript, so a zip whose
 * name contains one of these is not a candidate. The repository source is
 * used when the release has no other zip.
 */
private val ARCHIVE_PLATFORMS = listOf("darwin", "linux", "windows", "android")

private const val GITHUB_JSON = "application/vnd.github+json"
private const val USER_AGENT = "MyGramophone"
private const val MAX_API_BYTES = 1L shl 20

/**
 * owner/repo, or a GitHub page or clone address that names one.
 * Anything else is [PluginFailureKind.BadRepo].
 */
internal fun parsePluginRepo(input: String): String {
    var s = input.trim()
    val shown = s
    if (s.startsWithFold("git@github.com:")) {
        s = s.substring("git@github.com:".length)
    } else {
        s = when {
            s.startsWithFold("https://") -> s.substring("https://".length)
            s.startsWithFold("http://") -> s.substring("http://".length)
            else -> s
        }
        s = if (s.startsWithFold("www.")) s.substring("www.".length) else s
        if (s.startsWithFold("github.com/")) {
            var rest = s.substring("github.com/".length)
            val hash = rest.indexOf('#')
            if (hash >= 0) rest = rest.substring(0, hash)
            val query = rest.indexOf('?')
            if (query >= 0) rest = rest.substring(0, query)
            val parts = rest.split('/', limit = 3)
            if (parts.size == 3) rest = parts[0] + "/" + parts[1]
            s = rest
        }
    }
    if (s.endsWith("/")) s = s.dropLast(1)
    if (s.endsWith(".git")) s = s.dropLast(4)
    if (!REPO_NAME.matches(s) || s.contains("..")) {
        throw PluginPackageException(PluginFailureKind.BadRepo, shown)
    }
    return s
}

/** Index of a zip that names no platform, or -1 when every zip does. */
internal fun pickPluginArchive(names: List<String>): Int {
    var general = -1
    for ((index, name) in names.withIndex()) {
        val lower = name.lowercase()
        if (!lower.endsWith(".zip")) continue
        if (ARCHIVE_PLATFORMS.any { lower.contains(it) }) continue
        if (general < 0) general = index
    }
    return general
}

internal class PluginGithub(
    private val apiBase: String = "https://api.github.com",
    private val client: OkHttpClient = sharedClient,
) {
    suspend fun stage(repoInput: String, cacheDir: File): StagedPlugin = withContext(Dispatchers.IO) {
        val repo = parsePluginRepo(repoInput)
        val offer = resolve(repo)
        val zip = File(cacheDir, "mygomusic-github-${UUID.randomUUID()}.zip")
        var staged: StagedPlugin? = null
        try {
            download(offer.url, zip)
            staged = PluginPackages.stageZip(zip, cacheDir)
                .copy(repo = repo, originVersion = offer.version)
            staged
        } finally {
            zip.delete()
            if (!isActive) staged?.discard()
        }
    }

    private suspend fun resolve(repo: String): Offer {
        val (status, release) = getJson("/repos/$repo/releases/latest")
        if (status == 200 && release != null) {
            val tag = release.optString("tag_name")
            val zipball = release.optString("zipball_url")
            val assets = release.optJSONArray("assets")
            val names = mutableListOf<String>()
            val urls = mutableListOf<String>()
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    names += asset.optString("name")
                    urls += asset.optString("browser_download_url")
                }
            }
            val at = pickPluginArchive(names)
            val url = if (at >= 0) urls[at] else zipball
            if (tag.isEmpty() || !isHttp(url)) {
                throw PluginPackageException(PluginFailureKind.Github)
            }
            return Offer(tag, url)
        }
        val (commitStatus, commit) = getJson("/repos/$repo/commits/HEAD")
        if (commitStatus == 200 && commit != null) {
            val sha = commit.optString("sha")
            if (sha.isEmpty()) throw PluginPackageException(PluginFailureKind.NoRepo)
            return Offer(sha.take(12), "$apiBase/repos/$repo/zipball/$sha")
        }
        throw PluginPackageException(PluginFailureKind.NoRepo)
    }

    private suspend fun getJson(path: String): Pair<Int, JSONObject?> {
        open(apiBase + path, GITHUB_JSON).use { response ->
            when (response.code) {
                200 -> {
                    val text = readCapped(response, MAX_API_BYTES)
                    val obj = try {
                        JSONObject(text)
                    } catch (e: JSONException) {
                        throw PluginPackageException(PluginFailureKind.Github, cause = e)
                    }
                    return 200 to obj
                }
                404 -> return 404 to null
                403, 429 -> throw PluginPackageException(PluginFailureKind.RateLimit)
                else -> throw PluginPackageException(PluginFailureKind.Github)
            }
        }
    }

    private suspend fun download(url: String, dest: File) {
        if (!isHttp(url)) throw PluginPackageException(PluginFailureKind.Github)
        val accept = if (url.contains("/releases/assets/")) "application/octet-stream" else ""
        open(url, accept).use { response ->
            when (response.code) {
                200 -> {
                    val body = response.body ?: throw PluginPackageException(PluginFailureKind.Github)
                    val length = body.contentLength()
                    if (length > MAX_ARCHIVE_BYTES) {
                        throw PluginPackageException(PluginFailureKind.TooLarge)
                    }
                    dest.outputStream().use { out ->
                        body.byteStream().use { input ->
                            PluginPackages.copyLimited(input, out, MAX_ARCHIVE_BYTES)
                        }
                    }
                }
                403, 429 -> throw PluginPackageException(PluginFailureKind.RateLimit)
                else -> throw PluginPackageException(PluginFailureKind.Github)
            }
        }
    }

    private suspend fun open(url: String, accept: String): Response {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (accept.isNotEmpty()) builder.header("Accept", accept)
        val call = client.newCall(builder.build())
        coroutineContext.job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            return call.execute()
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException("github", e)
            throw PluginPackageException(PluginFailureKind.Github, cause = e)
        }
    }

    private fun readCapped(response: Response, max: Long): String {
        val body = response.body ?: throw PluginPackageException(PluginFailureKind.Github)
        val out = java.io.ByteArrayOutputStream()
        try {
            body.byteStream().use { input ->
                PluginPackages.copyLimited(input, out, max)
            }
        } catch (e: PluginPackageException) {
            if (e.kind == PluginFailureKind.TooLarge) {
                throw PluginPackageException(PluginFailureKind.Github, cause = e)
            }
            throw e
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private data class Offer(val version: String, val url: String)

    companion object {
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }
}

private fun isHttp(url: String): Boolean {
    return url.startsWith("https://") || url.startsWith("http://")
}

private fun String.startsWithFold(prefix: String): Boolean {
    return length >= prefix.length && regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)
}
