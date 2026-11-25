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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

class PluginSignInTest {
    @Test
    fun pagesAreHttpOrHttps() {
        assertEquals("https://example.com/a", requireWebPage(" https://example.com/a "))
        assertEquals("http://127.0.0.1:9/cb", requireWebPage("http://127.0.0.1:9/cb"))
        assertRejected("file:///etc/passwd")
        assertRejected("javascript:alert(1)")
        assertRejected("not a url")
    }

    @Test
    fun webSignInStaysOnAnAllowedSecureHost() {
        val network = listOf("api.example.com", "*.example.org")
        val asked = webSignInRequest(
            network,
            mapOf(
                "url" to "https://accounts.example.com/login",
                "cookiesOf" to "https://api.example.com/me",
                "until" to listOf("SID"),
                "userAgent" to "App",
            ),
        )
        assertEquals("https://accounts.example.com/login", asked.url)
        assertEquals(listOf("SID"), asked.until)
        assertEquals("App", asked.userAgent)
        webSignInRequest(
            network,
            mapOf(
                "url" to "https://accounts.example.com/login",
                "cookiesOf" to "https://www.example.org/",
                "until" to listOf("SID"),
            ),
        )
        assertThrows("not a secure") {
            webSignInRequest(
                network,
                mapOf(
                    "url" to "http://accounts.example.com/login",
                    "cookiesOf" to "https://api.example.com/",
                    "until" to listOf("SID"),
                ),
            )
        }
        assertThrows("can't reach") {
            webSignInRequest(
                network,
                mapOf(
                    "url" to "https://accounts.example.com/login",
                    "cookiesOf" to "https://other.example.net/",
                    "until" to listOf("SID"),
                ),
            )
        }
        assertThrows("until") {
            webSignInRequest(
                network,
                mapOf(
                    "url" to "https://accounts.example.com/login",
                    "cookiesOf" to "https://api.example.com/",
                    "until" to emptyList<String>(),
                ),
            )
        }
        assertThrows("network permission") {
            webSignInRequest(
                emptyList(),
                mapOf(
                    "url" to "https://a.example.com",
                    "cookiesOf" to "https://a.example.com",
                    "until" to listOf("SID"),
                ),
            )
        }
    }

    @Test
    fun cookieHeaderWaitsForEveryName() {
        assertNull(cookieHeader("SID=abc", listOf("SID", "HSID")))
        assertNull(cookieHeader("SID=; HSID=def", listOf("SID")))
        assertEquals("SID=abc; HSID=def", cookieHeader(" SID=abc; HSID=def ", listOf("HSID", "SID")))
    }

    @Test
    fun qrEncodesTheText() {
        assertNull(qrMatrix(""))
        val matrix = qrMatrix("https://music.163.com/login?codekey=abc")
        assertNotNull(matrix)
        matrix!!
        assertTrue(matrix.width > 20)
        var dark = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) dark++
            }
        }
        assertTrue(dark > 20)
    }

    @Test
    fun redirectReturnsTheFirstQuery() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val answer = async(Dispatchers.IO) {
            awaitRedirect(port, "/cb", "signed in", 5_000)
        }
        val miss = request("http://127.0.0.1:$port/nope")
        assertEquals(404, miss.first)
        assertEquals("not found", miss.second)
        val hit = request("http://127.0.0.1:$port/cb?code=a%20b&code=second&state=s")
        assertEquals(200, hit.first)
        assertEquals("signed in", hit.second)
        assertEquals(mapOf("code" to "a b", "state" to "s"), answer.await())
    }

    @Test
    fun redirectTimesOut() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        try {
            awaitRedirect(port, "/cb", "x", 400)
            fail("waited")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("timed out"))
        }
    }

    private fun assertRejected(address: String) {
        try {
            requireWebPage(address)
            fail(address)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not a web address"))
        }
    }

    private fun assertThrows(part: String, block: () -> Unit) {
        try {
            block()
            fail(part)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains(part))
        }
    }

    private fun request(address: String): Pair<Int, String> {
        var last: Exception? = null
        repeat(20) {
            try {
                val conn = URL(address).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream.bufferedReader().readText()
                conn.disconnect()
                return code to text
            } catch (e: Exception) {
                last = e
                Thread.sleep(50)
            }
        }
        throw last ?: IllegalStateException(address)
    }
}
