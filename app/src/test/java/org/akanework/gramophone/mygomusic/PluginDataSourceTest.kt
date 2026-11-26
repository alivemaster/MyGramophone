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

import android.net.Uri
import androidx.core.net.toUri
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
class PluginDataSourceTest {
    @Test
    fun streamHeadersReplaceRequestHeadersAndKeepTheAgent() {
        val headers = PluginDataSource.audioHeaders(
            requestHeaders = mapOf("Cookie" to "a=1", "X-Client" to "mygo"),
            streamHeaders = mapOf("x-client" to "own", "X-Empty" to ""),
            target = "https://api.example.com/audio/1.mp3".toUri(),
        )
        assertEquals(PluginDataSource.USER_AGENT, headers["User-Agent"])
        assertEquals("a=1", headers["Cookie"])
        assertEquals("own", headers["x-client"])
        assertFalse(headers.containsKey("X-Client"))
        assertFalse(headers.containsKey("X-Empty"))
        assertEquals("x-client,Cookie", headers["X-MyGramophone-Stay"])
    }

    @Test
    fun headersStayOnAChildHostOnly() {
        assertTrue(PluginDataSource.stays("example.com", "cdn.example.com"))
        assertTrue(PluginDataSource.stays("Example.com", "example.com"))
        assertFalse(PluginDataSource.stays("127.0.0.1", "localhost"))
        assertFalse(PluginDataSource.stays("example.com", "example.com.evil.test"))
        assertFalse(PluginDataSource.stays("cdn.example.com", "other.example.com"))
    }

    @Test
    fun redirectDropsPluginHeadersButKeepsTheAgent() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            // localhost and 127.0.0.1 are different hosts for this rule, as in mygo.
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://localhost:${server.port}/song"),
            )
            server.enqueue(MockResponse().setBody("audio"))
            val response = PluginDataSource.newAudioCall(
                Request.Builder()
                    .url("http://127.0.0.1:${server.port}/a")
                    .header("User-Agent", PluginDataSource.USER_AGENT)
                    .header("Cookie", "a=1")
                    .header("X-Token", "secret")
                    .header("X-MyGramophone-Stay", "Cookie,X-Token")
                    .build(),
            ).execute()
            assertEquals(200, response.code)
            response.close()
            val first = server.takeRequest()
            assertEquals("a=1", first.getHeader("Cookie"))
            assertEquals("secret", first.getHeader("X-Token"))
            assertEquals(PluginDataSource.USER_AGENT, first.getHeader("User-Agent"))
            assertNull(first.getHeader("X-MyGramophone-Stay"))
            val second = server.takeRequest()
            assertEquals("/song", second.path)
            assertNull(second.headers.toString(), second.getHeader("Cookie"))
            assertNull(second.headers.toString(), second.getHeader("X-Token"))
            assertEquals(PluginDataSource.USER_AGENT, second.getHeader("User-Agent"))
            assertNull(second.getHeader("X-MyGramophone-Stay"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun manifestHeadersFillFromStorageAndSkipABrokenLine() {
        val dir = File.createTempFile("plugin-data", "").apply {
            delete()
            mkdirs()
        }
        val records = PluginRecords(dir)
        records.setSetting("server", "https://NAS.local:5666/")
        records.storageSet("token", "first")
        val rules = listOf(
            PluginHeaderRule(
                hosts = listOf("{setting:server}"),
                kinds = emptyList(),
                headers = mapOf("Cookie" to "music-token={storage:token}"),
            ),
            PluginHeaderRule(
                hosts = listOf("img.example.com"),
                kinds = listOf("image"),
                headers = mapOf("Referer" to "https://example.com/"),
            ),
        )
        val audio = PluginCovers.headerMap(
            rules,
            records,
            "http://nas.local:5666/a",
            "audio",
            listOf("*"),
        )
        assertEquals("music-token=first", audio["Cookie"])
        assertTrue(
            PluginCovers.headerMap(
                rules,
                records,
                "https://img.example.com/a.jpg",
                "audio",
                listOf("*"),
            ).isEmpty(),
        )
        assertEquals(
            "https://example.com/",
            PluginCovers.headerMap(
                rules,
                records,
                "https://img.example.com/a.jpg",
                "image",
                listOf("*"),
            )["Referer"],
        )
        records.setSetting("server", "evil.test")
        assertTrue(
            PluginCovers.headerMap(
                listOf(
                    PluginHeaderRule(
                        hosts = listOf("{setting:server}"),
                        kinds = emptyList(),
                        headers = mapOf("a" to "b"),
                    ),
                ),
                records,
                "https://evil.test/c.jpg",
                "image",
                listOf("*.example.com"),
            ).isEmpty(),
        )
        records.storageSet("token", "a\r\nX-Evil: 1")
        records.setSetting("server", "https://a.com/")
        assertTrue(
            PluginCovers.headerMap(
                listOf(
                    PluginHeaderRule(
                        hosts = listOf("a.com"),
                        kinds = emptyList(),
                        headers = mapOf("Cookie" to "t={storage:token}"),
                    ),
                ),
                records,
                "https://a.com/c.jpg",
                "image",
                listOf("a.com"),
            ).isEmpty(),
        )
    }

    @Test
    fun fileStreamStaysInsideThePlugin() {
        val root = File.createTempFile("plugin", "").apply {
            delete()
            mkdirs()
        }
        val song = File(root, "tone.mp3").apply { writeBytes(byteArrayOf(1)) }
        val opened = PluginDataSource.openTarget(root, Uri.fromFile(song).toString())
        assertEquals(song.canonicalFile, File(opened.path!!).canonicalFile)
        val outside = File(root.parentFile, "secret.mp3").apply { writeBytes(byteArrayOf(1)) }
        assertThrows(Exception::class.java) {
            PluginDataSource.openTarget(root, Uri.fromFile(outside).toString())
        }
    }

    @Test
    fun pluginItemKeepsTheFieldsTheDetailPageShows() {
        val item = PluginTrack(
            id = "1",
            name = "Song",
            artists = "A",
            album = "Al",
            imageUrl = "https://img.example/a.jpg",
            durationMs = 125_000,
            albumArtist = "B",
            released = "2024-03-01",
        ).toMediaItem("demo")
        assertEquals("Song", item.mediaMetadata.title.toString())
        assertEquals("A", item.mediaMetadata.artist.toString())
        assertEquals("Al", item.mediaMetadata.albumTitle.toString())
        assertEquals("B", item.mediaMetadata.albumArtist.toString())
        assertEquals(125_000L, item.mediaMetadata.durationMs)
        assertEquals("2024-03-01", item.mediaMetadata.extras?.getString(PLUGIN_RELEASED))
        assertEquals("plugin:demo:1", item.mediaId)
    }

    @Test
    fun playUriKeepsAColonInsideTheTrackId() {
        val uri = playUri("demo", "chart:1")
        assertEquals("demo" to "chart:1", parsePlayUri(uri))
    }

    @Test
    fun streamTypeSetsMime() {
        val body = PluginDataSource.readStream(
            mapOf("url" to "https://cdn.example/a", "type" to "FLAC", "headers" to mapOf("A" to "b")),
        )
        assertEquals("audio/flac", body.mime)
        assertEquals("b", body.headers["A"])
        assertThrows(Exception::class.java) {
            PluginDataSource.readStream(mapOf("url" to "https://cdn.example/a", "type" to "wma"))
        }
    }
}
