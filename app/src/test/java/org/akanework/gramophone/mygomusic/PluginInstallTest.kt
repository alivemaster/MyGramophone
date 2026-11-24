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

import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.util.BitUtils
import org.json.JSONObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class PluginInstallTest {
    @Test
    fun compiledMainIsAcceptedWithoutRunningIt() {
        val root = tempDir()
        try {
            writePlugin(root, """{"id":"demo","name":"Demo","version":"1.2.3","main":"index.js","minAppVersion":"99.0.0"}""")
            File(root, "index.js").writeText("this is not javascript")
            val manifest = PluginManifest.read(root)
            assertEquals("demo", manifest.id)
            assertEquals("1.2.3", manifest.version)
            assertEquals("99.0.0", manifest.minAppVersion)
            assertEquals(HOST_VERSION, "0.1.0-mygramophone")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun typescriptAndMissingMainAreRejected() {
        val root = tempDir()
        try {
            writePlugin(root, """{"id":"demo","main":"index.ts"}""")
            File(root, "index.ts").writeText("export {}")
            assertKind(PluginFailureKind.MainNotCompiled) { PluginManifest.read(root) }
            writePlugin(root, """{"id":"demo","main":"index.js"}""")
            File(root, "index.ts").delete()
            assertKind(PluginFailureKind.MainNotCompiled) { PluginManifest.read(root) }
            writePlugin(root, """{"id":"demo","main":"../index.js"}""")
            assertKind(PluginFailureKind.MainNotCompiled) { PluginManifest.read(root) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun badIdsAreRejected() {
        val root = tempDir()
        try {
            File(root, "index.js").writeText("export {}")
            writePlugin(root, """{"id":"local","main":"index.js"}""")
            assertKind(PluginFailureKind.BadId) { PluginManifest.read(root) }
            writePlugin(root, """{"id":"../up","main":"index.js"}""")
            assertKind(PluginFailureKind.BadId) { PluginManifest.read(root) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun permissionsAndTranslationsFollowTheManifest() {
        val root = tempDir()
        try {
            writePlugin(
                root,
                """{"id":"demo","name":"QQ Music","main":"dist/index.js","permissions":{"network":["*"],"process":true},"requestHeaders":[{"hosts":["{setting:server}"],"kinds":["image","audio"],"headers":{"authorization":"t"}}]}""",
            )
            File(root, "dist").mkdirs()
            File(root, "dist/index.js").writeText("export {}")
            File(root, "locales").mkdirs()
            File(root, "locales/zh-CN.json").writeText("""{"QQ Music":"QQ 音乐"}""")
            val manifest = PluginManifest.read(root)
            assertEquals(listOf("*"), manifest.network)
            assertEquals(listOf("*"), manifest.process)
            assertEquals("QQ 音乐", manifest.title(Locale.forLanguageTag("zh-CN")))
            assertEquals("QQ Music", manifest.title(Locale.ENGLISH))
            writePlugin(
                root,
                """{"id":"demo","main":"dist/index.js","permissions":{"network":["api.example.com"]},"requestHeaders":[{"hosts":["other.example.com"],"headers":{"x":"y"}}]}""",
            )
            assertKind(PluginFailureKind.BadManifest) { PluginManifest.read(root) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun referencePluginsAreCompiledJavascript() {
        val netease = referencePlugin("netease-0.1.14")
        assumeTrue(netease != null)
        assertEquals("netease", PluginManifest.read(netease!!).id)
        val qq = referencePlugin("mygo-music-qq-plugin-main")
        assumeTrue(qq != null)
        assertEquals("dist/index.js", PluginManifest.read(qq!!).main)
        assertEquals("index.js", PluginManifest.read(File(qq, "dist")).main)
        val apple = referencePlugin("mygo-music-apple-music-0.3.5")
        assumeTrue(apple != null)
        assertTrue(PluginManifest.read(apple!!).process.isNotEmpty())
        val emby = referencePlugin("mygo-music-emby-jellyfin-v0.1.0")
        assumeTrue(emby != null)
        assertEquals("emby", PluginManifest.read(emby!!).id)
    }

    @Test
    fun zipRootAndSingleFolderArePlugins() {
        val cache = tempDir()
        try {
            val zip = File(cache, "root.zip")
            zipOf(zip, mapOf("plugin.json" to manifest("root"), "index.js" to "export {}"))
            val staged = PluginPackages.stageZip(zip, cache)
            assertEquals("root", staged.manifest.id)
            staged.discard()

            val wrapped = File(cache, "wrapped.zip")
            zipOf(
                wrapped,
                mapOf(
                    "repo-abc/plugin.json" to manifest("wrapped"),
                    "repo-abc/index.js" to "export {}",
                    "__MACOSX/._plugin.json" to "junk",
                ),
            )
            val nested = PluginPackages.stageZip(wrapped, cache)
            assertEquals("wrapped", nested.manifest.id)
            nested.discard()
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun twoFoldersAndZipSlipAreRejected() {
        val cache = tempDir()
        try {
            val zip = File(cache, "two.zip")
            zipOf(
                zip,
                mapOf(
                    "a/plugin.json" to manifest("a"),
                    "a/index.js" to "export {}",
                    "b/readme.txt" to "x",
                ),
            )
            assertKind(PluginFailureKind.NotAPlugin) { PluginPackages.stageZip(zip, cache) }

            val slip = File(cache, "slip.zip")
            ZipOutputStream(FileOutputStream(slip)).use { out ->
                out.putNextEntry(ZipEntry("../../outside.txt"))
                out.write(byteArrayOf(1))
                out.closeEntry()
                out.putNextEntry(ZipEntry("plugin.json"))
                out.write(manifest("slip").toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("index.js"))
                out.write("export {}".toByteArray())
                out.closeEntry()
            }
            assertKind(PluginFailureKind.ZipSlip) { PluginPackages.stageZip(slip, cache) }
            assertFalse(File(cache, "outside.txt").exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun symlinkBitIsRejected() {
        val header = FileHeader()
        header.externalFileAttributes = byteArrayOf(0, 0, 0, BitUtils.setBit(0.toByte(), 5))
        assertTrue(header.isUnixSymlink())
        header.externalFileAttributes = byteArrayOf(0, 0, 0, 0)
        assertFalse(header.isUnixSymlink())
    }

    @Test
    fun directoryCopySkipsHiddenFiles() {
        val cache = tempDir()
        try {
            val src = File(cache, "src")
            writePlugin(src, manifest("dir"))
            File(src, "index.js").writeText("not javascript")
            File(src, ".git").mkdirs()
            File(src, ".git/HEAD").writeText("ref")
            val staged = PluginPackages.stageDirectory(src, cache)
            assertEquals("dir", staged.manifest.id)
            assertFalse(File(staged.root, ".git").exists())
            assertTrue(File(staged.root, "index.js").isFile)
            staged.discard()
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun installListsInOrderAndDeleteKeepsData() {
        val root = tempDir()
        try {
            val store = PluginStore(root)
            val cache = File(root, "cache").apply { mkdirs() }
            installFixture(store, cache, "a", "1.0.0")
            installFixture(store, cache, "b", "2.0.0")
            val names = store.list(Locale.ENGLISH).map { it.directoryName }
            assertEquals(listOf("a", "b"), names)
            val origin = JSONObject(File(storeDir(root, "a"), ".origin.json").readText())
            assertFalse(origin.has("repo"))
            assertEquals("1.0.0", origin.getString("version"))
            installFixture(store, cache, "a", "1.0.1")
            assertEquals(listOf("a", "b"), store.list(Locale.ENGLISH).map { it.directoryName })
            assertEquals("1.0.1", store.readOrigin(storeDir(root, "a")).version)
            val data = store.dataDir("a")
            data.mkdirs()
            File(data, "settings.json").writeText("{}")
            store.delete("a")
            assertFalse(storeDir(root, "a").exists())
            assertTrue(File(data, "settings.json").isFile)
            assertEquals(listOf("b"), store.list(Locale.ENGLISH).map { it.directoryName })
            val removed = store.dataDir("b")
            removed.mkdirs()
            File(removed, "settings.json").writeText("{}")
            store.delete("b", deleteData = true)
            assertFalse(storeDir(root, "b").exists())
            assertFalse(removed.exists())
            assertEquals(emptyList<String>(), store.list(Locale.ENGLISH).map { it.directoryName })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localInstallDoesNotReplaceAnotherSource() {
        val root = tempDir()
        try {
            val store = PluginStore(root)
            val cache = File(root, "cache").apply { mkdirs() }
            installFixture(store, cache, "demo", "1")
            File(storeDir(root, "demo"), ".origin.json").writeText("""{"repo":"owner/demo","version":"9"}""")
            assertFalse(store.canReplace("demo", ""))
            val again = fixture(cache, "demo", "2")
            assertKind(PluginFailureKind.AlreadyInstalled) { store.install(again.root, again.manifest) }
            again.discard()
            assertEquals("owner/demo", store.readOrigin(storeDir(root, "demo")).repo)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun githubInstallReplacesOnlyTheSameRepository() {
        val root = tempDir()
        try {
            val store = PluginStore(root)
            val cache = File(root, "cache").apply { mkdirs() }
            val first = fixture(cache, "demo", "1")
            store.install(first.root, first.manifest, "owner/demo", "v1")
            first.discard()
            assertEquals(PluginOrigin("owner/demo", "v1"), store.readOrigin(storeDir(root, "demo")))
            val again = fixture(cache, "demo", "2")
            store.install(again.root, again.manifest, "owner/demo", "v2")
            again.discard()
            assertEquals("v2", store.readOrigin(storeDir(root, "demo")).version)
            assertEquals(listOf("demo"), store.list(Locale.ENGLISH).map { it.directoryName })
            val other = fixture(cache, "demo", "3")
            assertKind(PluginFailureKind.AlreadyInstalled) {
                store.install(other.root, other.manifest, "someone/else", "v3")
            }
            other.discard()
            assertEquals("owner/demo", store.readOrigin(storeDir(root, "demo")).repo)
            assertFalse(store.canReplace("demo", ""))
            val local = fixture(cache, "demo", "4")
            assertKind(PluginFailureKind.AlreadyInstalled) { store.install(local.root, local.manifest) }
            local.discard()
            assertEquals("v2", store.readOrigin(storeDir(root, "demo")).version)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun githubInstallDoesNotReplaceALocalPlugin() {
        val root = tempDir()
        try {
            val store = PluginStore(root)
            val cache = File(root, "cache").apply { mkdirs() }
            installFixture(store, cache, "demo", "1")
            assertFalse(store.canReplace("demo", "owner/demo"))
            val remote = fixture(cache, "demo", "2")
            assertKind(PluginFailureKind.AlreadyInstalled) {
                store.install(remote.root, remote.manifest, "owner/demo", "v9")
            }
            remote.discard()
            assertEquals("", store.readOrigin(storeDir(root, "demo")).repo)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repositoryTextIsOwnerAndNameOrAGitHubAddress() {
        val accepted = mapOf(
            "owner/repo" to "owner/repo",
            " owner/repo " to "owner/repo",
            "https://github.com/owner/repo" to "owner/repo",
            "https://github.com/owner/repo.git" to "owner/repo",
            "github.com/owner/repo/" to "owner/repo",
            "https://www.github.com/o-1/r_2.x" to "o-1/r_2.x",
            "https://github.com/owner/repo/tree/main/src" to "owner/repo",
            "https://github.com/owner/repo/releases/tag/v1" to "owner/repo",
            "https://github.com/owner/repo?tab=readme#install" to "owner/repo",
            "HTTPS://GitHub.com/owner/repo" to "owner/repo",
            "git@github.com:owner/repo.git" to "owner/repo",
        )
        for ((input, want) in accepted) {
            assertEquals(input, want, parsePluginRepo(input))
        }
        for (input in listOf("", "owner", "a/b/c", "https://example.com/a/b", "https://gitlab.com/a/b", "https://github.com/owner", "../x", "a/..")) {
            assertKind(PluginFailureKind.BadRepo) { parsePluginRepo(input) }
        }
    }

    @Test
    fun portableZipIsPreferredOverAPlatformZip() {
        assertEquals(2, pickPluginArchive(listOf("x-linux-amd64.zip", "x-darwin-arm64.zip", "x.zip")))
        assertEquals(0, pickPluginArchive(listOf("plugin.zip", "notes.txt")))
        assertEquals(1, pickPluginArchive(listOf("plugin-android-arm64.zip", "plugin.zip")))
        assertEquals(-1, pickPluginArchive(listOf("x-linux-amd64.zip")))
        assertEquals(-1, pickPluginArchive(listOf("plugin-android-arm64.zip")))
        assertEquals(-1, pickPluginArchive(listOf("x-darwin-arm64.tar.gz")))
        assertEquals(-1, pickPluginArchive(emptyList()))
    }

    @Test
    fun githubReleaseUsesThePortableZipAndRecordsTheTag() {
        val cache = tempDir()
        try {
            val archive = archiveBytes(cache, "owner-repo-abc/")
            FakeGithub(
                tag = "v1.2.0",
                assets = listOf("plugin-android-arm64.zip", "plugin-darwin-arm64.zip", "plugin.zip"),
                archive = archive,
            ).use { github ->
                val staged = runBlocking {
                    PluginGithub(github.base).stage("https://github.com/owner/repo/tree/main", cache)
                }
                assertEquals("good", staged.manifest.id)
                assertEquals("owner/repo", staged.repo)
                assertEquals("v1.2.0", staged.originVersion)
                assertEquals(listOf("/repos/owner/repo/releases/latest", "/asset/plugin.zip"), github.paths)
                assertEquals("MyGramophone", github.agents.first())
                assertEquals("application/vnd.github+json", github.accepts.first())
                val store = PluginStore(File(cache, "installed"))
                store.install(staged.root, staged.manifest, staged.repo, staged.originVersion)
                staged.discard()
                val origin = JSONObject(File(cache, "installed/plugins/good/.origin.json").readText())
                assertEquals("owner/repo", origin.getString("repo"))
                assertEquals("v1.2.0", origin.getString("version"))
            }
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun platformZipAloneFallsBackToSource() {
        val cache = tempDir()
        try {
            FakeGithub(
                tag = "v1",
                assets = listOf("plugin-android-arm64.zip"),
                archive = archiveBytes(cache, ""),
            ).use { github ->
                val staged = runBlocking { PluginGithub(github.base).stage("owner/repo", cache) }
                staged.discard()
                assertEquals("v1", staged.originVersion)
                assertEquals(listOf("/repos/owner/repo/releases/latest", "/zip"), github.paths)
            }
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun missingReleaseUsesTheLatestCommit() {
        val cache = tempDir()
        try {
            FakeGithub(sha = "0123456789abcdef0123", archive = archiveBytes(cache, "owner-repo-0123/")).use { github ->
                val staged = runBlocking { PluginGithub(github.base).stage("owner/repo", cache) }
                staged.discard()
                assertEquals("0123456789ab", staged.originVersion)
                assertTrue(github.paths.contains("/repos/owner/repo/commits/HEAD"))
                assertTrue(github.paths.contains("/repos/owner/repo/zipball/0123456789abcdef0123"))
            }
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun missingRepositoryAndRateLimitAreDistinct() {
        val cache = tempDir()
        try {
            FakeGithub(archive = byteArrayOf()).use { github ->
                assertKind(PluginFailureKind.NoRepo) {
                    runBlocking { PluginGithub(github.base).stage("owner/repo", cache) }
                }
            }
            FakeGithub(archive = byteArrayOf(), releaseCode = 429).use { github ->
                assertKind(PluginFailureKind.RateLimit) {
                    runBlocking { PluginGithub(github.base).stage("owner/repo", cache) }
                }
                assertEquals(listOf("/repos/owner/repo/releases/latest"), github.paths)
            }
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun installFixture(store: PluginStore, cache: File, id: String, version: String) {
        val staged = fixture(cache, id, version)
        store.install(staged.root, staged.manifest)
        staged.discard()
    }

    private fun fixture(cache: File, id: String, version: String): StagedPlugin {
        val src = File(cache, "src-$id-$version")
        writePlugin(src, """{"id":"$id","name":"$id","version":"$version","main":"index.js"}""")
        File(src, "index.js").writeText("export {}")
        return PluginPackages.stageDirectory(src, cache)
    }

    private fun storeDir(root: File, id: String) = File(root, "plugins/$id")

    private fun manifest(id: String) =
        """{"id":"$id","name":"$id","version":"1","main":"index.js"}"""

    private fun archiveBytes(dir: File, top: String): ByteArray {
        val file = File(dir, "archive-${top.replace("/", "")}-${System.nanoTime()}.zip")
        zipOf(
            file,
            mapOf(
                "${top}plugin.json" to manifest("good"),
                "${top}index.js" to "export {}",
            ),
        )
        return file.readBytes()
    }

    private fun writePlugin(root: File, json: String) {
        root.mkdirs()
        File(root, "plugin.json").writeText(json)
    }

    private fun zipOf(dest: File, entries: Map<String, String>) {
        if (dest.exists()) dest.delete()
        val zip = ZipFile(dest)
        try {
            for ((name, body) in entries) {
                val tmp = File.createTempFile("entry", ".bin", dest.parentFile)
                tmp.writeText(body)
                val params = ZipParameters()
                params.fileNameInZip = name
                zip.addFile(tmp, params)
                tmp.delete()
            }
        } finally {
            zip.close()
        }
    }

    private fun assertKind(kind: PluginFailureKind, block: () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: PluginPackageException) {
            assertEquals(kind, e.kind)
        }
    }

    private fun referencePlugin(name: String): File? {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, "plugins_ref/$name")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun tempDir(): File = Files.createTempDirectory("mygomusic").toFile()
}

/** One repository on a local GitHub API: a release, or commits when [tag] is empty. */
private class FakeGithub(
    private val tag: String = "",
    private val sha: String = "",
    private val assets: List<String> = emptyList(),
    private val archive: ByteArray,
    private val releaseCode: Int? = null,
) : Closeable {
    val paths = mutableListOf<String>()
    val accepts = mutableListOf<String>()
    val agents = mutableListOf<String>()
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String

    init {
        base = "http://127.0.0.1:${server.address.port}"
        server.executor = pool
        server.createContext("/") { exchange -> serve(exchange) }
        server.start()
    }

    private fun serve(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        paths += path
        accepts += exchange.requestHeaders.getFirst("Accept").orEmpty()
        agents += exchange.requestHeaders.getFirst("User-Agent").orEmpty()
        when {
            path.endsWith("/releases/latest") -> {
                val code = releaseCode
                if (code != null) {
                    empty(exchange, code)
                } else if (tag.isEmpty()) {
                    empty(exchange, 404)
                } else {
                    val listed = assets.joinToString(",") { name ->
                        val url = "$base/asset/$name"
                        """{"name":${JSONObject.quote(name)},"browser_download_url":${JSONObject.quote(url)},"url":${JSONObject.quote(url)}}"""
                    }
                    val body = """{"tag_name":${JSONObject.quote(tag)},"zipball_url":${JSONObject.quote("$base/zip")},"assets":[$listed]}"""
                    bytes(exchange, 200, body.toByteArray(Charsets.UTF_8), "application/json")
                }
            }
            path.endsWith("/commits/HEAD") -> {
                if (sha.isEmpty()) {
                    empty(exchange, 404)
                } else {
                    val body = """{"sha":${JSONObject.quote(sha)}}"""
                    bytes(exchange, 200, body.toByteArray(Charsets.UTF_8), "application/json")
                }
            }
            else -> bytes(exchange, 200, archive, "application/zip")
        }
    }

    private fun empty(exchange: HttpExchange, code: Int) {
        exchange.sendResponseHeaders(code, -1)
        exchange.close()
    }

    private fun bytes(exchange: HttpExchange, code: Int, body: ByteArray, type: String) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}
