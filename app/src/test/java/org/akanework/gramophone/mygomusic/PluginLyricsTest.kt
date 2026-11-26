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
import coil3.size.Dimension
import coil3.size.Size
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.akanework.gramophone.logic.utils.SemanticLyrics.SyncedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
class PluginLyricsTest {
    @Test
    fun timedWordsAndTranslationBecomeSyncedLines() {
        val lyrics = PluginLyrics.read(
            mapOf(
                "lrc" to "[00:00.50]ignored when lines are present",
                "lines" to listOf(
                    mapOf(
                        "start" to 1000,
                        "end" to 3000,
                        "translation" to "A line sung word by word",
                        "words" to listOf(
                            mapOf("start" to 1000, "end" to 1600, "text" to "Word "),
                            mapOf("start" to 1600, "end" to 2200, "text" to "by "),
                            mapOf("start" to 2200, "end" to 3000, "text" to "word"),
                        ),
                    ),
                ),
            ),
            trim = true,
        ) as SyncedLyrics
        val line = lyrics.text[0]
        assertEquals("Word by word", line.text)
        assertEquals(1000uL, line.start)
        assertEquals(3000uL, line.end)
        assertFalse(line.isTranslated)
        val words = line.words!!
        assertEquals(0..4, words[0].charRange)
        assertEquals(1000uL, words[0].begin)
        assertEquals(1600uL, words[0].endInclusive)
        assertEquals(5..7, words[1].charRange)
        assertEquals(8..11, words[2].charRange)
        val translation = lyrics.text[1]
        assertTrue(translation.isTranslated)
        assertEquals("A line sung word by word", translation.text)
        assertEquals(line.start, translation.start)
        assertNull(translation.words)
    }

    @Test
    fun lrcUsesTheExistingParserAndKeepsANearbyTranslation() {
        val lyrics = PluginLyrics.read(
            mapOf(
                "lrc" to "[00:01.00]A plain line\n[00:04.00]And another",
                "translation" to "[00:01.00]Translated",
            ),
            trim = true,
        ) as SyncedLyrics
        assertEquals("A plain line", lyrics.text[0].text)
        assertEquals("Translated", lyrics.text[1].text)
        assertTrue(lyrics.text[1].isTranslated)
        assertEquals(lyrics.text[0].start, lyrics.text[1].start)
        assertEquals("And another", lyrics.text[2].text)
        assertFalse(lyrics.text[2].isTranslated)
    }

    @Test
    fun emptyLyricsStayEmpty() {
        assertNull(PluginLyrics.read(mapOf("lrc" to "", "lines" to emptyList<Any>()), trim = true))
        assertEquals("demo" to "chart:1", parsePluginMediaId(pluginMediaId("demo", "chart:1")))
    }

    @Test
    fun coverSizePlaceholderAndUnspecifiedPixels() {
        assertEquals(
            "https://img.example/a.jpg?w=40",
            PluginCovers.sized("https://img.example/a.jpg?w={size}", 40),
        )
        assertEquals(
            "https://img.example/a.jpg?w=40",
            PluginCovers.sized("https://img.example/a.jpg?w=%7Bsize%7D", 40),
        )
        assertEquals(600, coverPixels(Size(Dimension.Undefined, Dimension.Undefined)))
        assertEquals(128, coverPixels(Size(Dimension.Pixels(128), Dimension.Pixels(96))))
    }

    @Test
    fun firstPluginImageRuleWins() {
        val context = RuntimeEnvironment.getApplication() as Context
        writePlugin(
            context,
            "a",
            """{"id":"a","permissions":{"network":["*"]},"requestHeaders":[{"hosts":["img.example.com"],"kinds":["audio"],"headers":{"Cookie":"audio"}}]}""",
        )
        writePlugin(
            context,
            "b",
            """{"id":"b","permissions":{"network":["*"]},"requestHeaders":[{"hosts":["img.example.com"],"headers":{"Cookie":"from-b"}}]}""",
        )
        PluginHeaderCache.invalidate()
        val headers = PluginCovers.pluginImageHeaders(context, "https://img.example.com/a.jpg")
        assertEquals("from-b", headers["Cookie"])
    }

    @Test
    fun pictureRedirectDropsPluginHeadersButKeepsTheAgent() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://localhost:${server.port}/cover"),
            )
            server.enqueue(MockResponse().setBody("img"))
            val response = PluginCovers.client.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:${server.port}/a")
                    .header("Cookie", "a=1")
                    .header(PluginDataSource.STAY, "Cookie")
                    .build(),
            ).execute()
            assertEquals(200, response.code)
            response.close()
            val first = server.takeRequest()
            assertEquals("a=1", first.getHeader("Cookie"))
            assertEquals(PluginDataSource.USER_AGENT, first.getHeader("User-Agent"))
            assertNull(first.getHeader(PluginDataSource.STAY))
            val second = server.takeRequest()
            assertEquals("/cover", second.path)
            assertNull(second.headers.toString(), second.getHeader("Cookie"))
            assertEquals(PluginDataSource.USER_AGENT, second.getHeader("User-Agent"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun imageHeadersFollowSettingAndStorageChanges() {
        val context = RuntimeEnvironment.getApplication() as Context
        writePlugin(
            context,
            "tok",
            """{"id":"tok","permissions":{"network":["*"]},"requestHeaders":[{"hosts":["{setting:server}"],"headers":{"Cookie":"t={storage:token}"}}]}""",
        )
        val records = PluginRecords(File(context.filesDir, "mygomusic/data/tok"))
        records.setSetting("server", "https://covers.example/")
        records.storageSet("token", "one")
        assertEquals(
            "t=one",
            PluginCovers.pluginImageHeaders(context, "https://covers.example/a.jpg")["Cookie"],
        )
        records.storageSet("token", "two")
        assertEquals(
            "t=two",
            PluginCovers.pluginImageHeaders(context, "https://covers.example/a.jpg")["Cookie"],
        )
        records.setSetting("server", "https://other.example/")
        assertTrue(PluginCovers.pluginImageHeaders(context, "https://covers.example/a.jpg").isEmpty())
    }

    private fun writePlugin(context: Context, id: String, json: String) {
        val dir = File(context.filesDir, "mygomusic/plugins/$id")
        dir.mkdirs()
        File(dir, "plugin.json").writeText(json)
        File(dir, "index.js").writeText("export function home(){return {};}\n")
    }
}
