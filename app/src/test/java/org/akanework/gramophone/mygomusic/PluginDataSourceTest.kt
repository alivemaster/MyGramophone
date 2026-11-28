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
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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
        assertFalse((item.mediaMetadata.userRating as HeartRating).isHeart)
        assertFalse(PluginLikes.episode(item))
    }

    @Test
    fun playerNamesTheFirstArtistWhoHasAPage() {
        val item = PluginTrack(
            id = "t",
            name = "Song",
            artists = "A, B, C",
            album = "Album",
            imageUrl = null,
            credits = listOf(
                PluginPerson("", "A"),
                PluginPerson("2", "B"),
                PluginPerson("3", "C"),
            ),
            albumId = "al",
        ).toMediaItem("demo")
        assertEquals("B", item.mediaMetadata.artist.toString())
        assertEquals("2", item.mediaMetadata.extras?.getString(PLUGIN_ARTIST_ID))
        assertEquals("B", item.mediaMetadata.extras?.getString(PLUGIN_ARTIST_NAME))
        assertEquals("al", item.mediaMetadata.extras?.getString(PLUGIN_ALBUM_ID))
        val kept = item.withPluginHeart(true, false)
        assertEquals("2", kept.mediaMetadata.extras?.getString(PLUGIN_ARTIST_ID))
        assertEquals("al", kept.mediaMetadata.extras?.getString(PLUGIN_ALBUM_ID))
    }

    @Test
    fun episodeUsesShowIdAndHeartKeepsTheOtherFields() {
        val item = PluginTrack(
            id = "ep",
            name = "第一期",
            artists = "A",
            album = "",
            imageUrl = null,
            showId = "show-5",
            released = "2020",
        ).toMediaItem("demo")
        assertEquals("show-5", item.mediaMetadata.extras?.getString(PLUGIN_SHOW))
        assertTrue(PluginLikes.episode(item))
        assertFalse(PluginLikes.canLike(setOf("like"), episode = true))
        assertTrue(PluginLikes.canLike(setOf("like", "likeEpisode"), episode = true))
        assertTrue(PluginLikes.canLike(setOf("like"), episode = false))
        assertFalse(PluginLikes.canLike(emptySet(), episode = false))
        val next = item.withPluginHeart(true, true)
        assertEquals("2020", next.mediaMetadata.extras?.getString(PLUGIN_RELEASED))
        assertEquals("show-5", next.mediaMetadata.extras?.getString(PLUGIN_SHOW))
        assertTrue(next.mediaMetadata.extras!!.getBoolean(PLUGIN_LIKE))
        assertTrue((next.mediaMetadata.userRating as HeartRating).isHeart)
        assertTrue(PluginLikes.likedFlag(mapOf("ep" to true, "other" to false), "ep"))
        assertFalse(PluginLikes.likedFlag(mapOf("ep" to true), "missing"))
    }

    @Test
    fun unavailableTrackStaysInTheQueueAndTheStreamErrorSaysSo() {
        val tracks = parseTracks(
            org.json.JSONArray(
                """[{"id":"1","name":"No"},{"id":"2","name":"Yes","unavailable":true}]""",
            ),
        )
        assertFalse(tracks[0].unavailable)
        assertTrue(tracks[1].unavailable)
        val blocked = tracks[1].toMediaItem("demo")
        assertEquals("demo" to "2", parsePlayUri(blocked.localConfiguration!!.uri))
        assertTrue(playUriUnavailable(blocked.localConfiguration!!.uri))
        assertFalse(playUriUnavailable(tracks[0].toMediaItem("demo").localConfiguration!!.uri))
        assertTrue(pluginUnavailable(java.io.IOException("stream", PluginCallException("unavailable", "vip"))))
        assertFalse(pluginUnavailable(java.io.IOException("stream", PluginCallException("signIn", "in"))))
        val blockedPlay = java.io.IOException("stream", PluginCallException("unavailable", "vip"))
        val timedOut = java.io.IOException("read", java.net.SocketTimeoutException("timeout"))
        val pluginFault = java.io.IOException("stream", PluginCallException("", "plugin failed"))
        val network = java.net.UnknownHostException("cdn.example")
        val signIn = java.io.IOException("stream", PluginCallException("signIn", "in"))
        val setup = java.io.IOException("stream", PluginCallException("setup", "set"))
        val pluginUri = tracks[0].toMediaItem("demo").localConfiguration!!.uri
        assertTrue(skipFailedPluginTrack(pluginUri, blockedPlay))
        assertTrue(skipFailedPluginTrack(pluginUri, timedOut))
        assertTrue(skipFailedPluginTrack(pluginUri, pluginFault))
        assertTrue(skipFailedPluginTrack(pluginUri, network))
        assertFalse(skipFailedPluginTrack(pluginUri, signIn))
        assertFalse(skipFailedPluginTrack(pluginUri, setup))
        assertFalse(skipFailedPluginTrack("file:///music/a.flac".toUri(), network))
        assertFalse(skipFailedPluginTrack("content://media/external/audio/media/3".toUri(), timedOut))
        assertFalse(skipFailedPluginTrack(null, network))
        assertEquals("vip", pluginFailureText(blockedPlay).message)
        assertEquals("unavailable", pluginFailureText(blockedPlay).code)
        assertEquals("timeout", pluginFailureText(timedOut).message)
        assertEquals("plugin failed", pluginFailureText(pluginFault).message)
        assertEquals("cdn.example", pluginFailureText(network).message)
        assertEquals("in", pluginFailureText(signIn).message)
        assertEquals("signIn", pluginFailureText(signIn).code)
        assertEquals("set", pluginFailureText(setup).message)
        val marked = java.io.IOException("stream", PluginCallException("unavailable", "unavailable"))
        assertEquals(null, pluginFailureText(marked).message)
        assertEquals("unavailable", pluginFailureText(marked).code)
        val silentSignIn = java.io.IOException("stream", PluginCallException("signIn", ""))
        assertEquals(null, pluginFailureText(silentSignIn).message)
        assertEquals("signIn", pluginFailureText(silentSignIn).code)
        val policy = PluginTrackLoadPolicy()
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadError(pluginUri)))
        assertTrue(policy.getRetryDelayMsFor(loadError("file:///music/a.flac".toUri())) != C.TIME_UNSET)
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

private fun loadError(uri: Uri): LoadErrorHandlingPolicy.LoadErrorInfo {
    val spec = DataSpec.Builder().setUri(uri).build()
    return LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo.Builder(0, spec, 0).build(),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        java.io.IOException("down"),
        1,
    )
}
