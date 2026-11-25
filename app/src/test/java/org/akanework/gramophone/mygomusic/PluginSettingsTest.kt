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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class PluginSettingsTest {
    @Test
    fun settingsFollowTheManifestAndFallBackToText() {
        val root = Files.createTempDirectory("mygomusic-settings").toFile()
        try {
            writePlugin(
                root,
                """
                {"id":"demo","name":"Demo","main":"index.js","settings":[
                  {"key":"greeting","label":"Greeting","help":"Shown on Home"},
                  {"key":"secret","label":"Secret","type":"password"},
                  {"key":"history","label":"History","type":"boolean"},
                  {"key":"quality","label":"Quality","type":"select","options":[
                    {"value":"","label":"High"},
                    {"value":"low","label":"Low"}
                  ]},
                  {"key":"again","label":"Again"},
                  {"key":"quality","label":"Ignored duplicate"},
                  {"key":"mode","label":"Mode","type":"select","options":[{"value":"a","label":"Same"},{"value":"b","label":"Same"}]},
                  {"key":"other","label":"Other","type":"slider"}
                ]}
                """.trimIndent(),
            )
            File(root, "locales").mkdirs()
            File(root, "locales/zh-CN.json").writeText("""{"Greeting":"问候","Shown on Home":"显示在首页","High":"高"}""")
            val manifest = PluginManifest.read(root)
            assertEquals(listOf("greeting", "secret", "history", "quality", "again", "mode", "other"), manifest.settings.map { it.key })
            val greeting = manifest.settings[0]
            assertEquals("问候", manifest.text(Locale.forLanguageTag("zh-CN"), greeting.label))
            assertEquals("显示在首页", manifest.text(Locale.forLanguageTag("zh-CN"), greeting.help))
            assertEquals("高", manifest.text(Locale.CHINA, manifest.settings[3].options[0].label))
            assertEquals("", manifest.settings[3].options[0].value)
            assertEquals(PluginSettingKind.Text, pluginSettingKind(greeting, ""))
            assertEquals(PluginSettingKind.Password, pluginSettingKind(manifest.settings[1], "secret"))
            assertEquals(PluginSettingKind.Switch, pluginSettingKind(manifest.settings[2], ""))
            assertEquals(PluginSettingKind.Switch, pluginSettingKind(manifest.settings[2], "false"))
            assertEquals(PluginSettingKind.Text, pluginSettingKind(manifest.settings[2], "yes"))
            assertEquals(PluginSettingKind.Select, pluginSettingKind(manifest.settings[3], "nope"))
            assertEquals(PluginSettingKind.Text, pluginSettingKind(manifest.settings[5], ""))
            assertEquals(PluginSettingKind.Text, pluginSettingKind(manifest.settings[6], ""))
            val choices = selectChoices(manifest.settings[3].options, "nope") { "now:$it" }
            assertEquals(listOf("now:nope", "High", "Low"), choices?.first)
            assertEquals(listOf("nope", "", "low"), choices?.second)
            assertNull(selectChoices(manifest.settings[5].options, "") { it })
            assertEquals("https://example.com/a", httpLink(" https://example.com/a "))
            assertNull(httpLink("file:///tmp/plugin"))
            assertNull(httpLink("not a url"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun accountNullIsSignedOutAndAProfileKeepsItsName() {
        assertNull(accountLabel(null))
        assertEquals("Ada", accountLabel(mapOf("id" to "1", "name" to "Ada")))
        assertEquals("", accountLabel(mapOf("id" to "1")))
    }

    @Test
    fun badSettingsAreRejected() {
        val root = Files.createTempDirectory("mygomusic-settings").toFile()
        try {
            writePlugin(root, """{"id":"demo","main":"index.js","settings":{"key":"a"}}""")
            assertKind(PluginFailureKind.BadManifest) { PluginManifest.read(root) }
            writePlugin(root, """{"id":"demo","main":"index.js","settings":[{"label":"No key"}]}""")
            assertKind(PluginFailureKind.BadManifest) { PluginManifest.read(root) }
            writePlugin(root, """{"id":"demo","main":"index.js"}""")
            assertTrue(PluginManifest.read(root).settings.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun enabledFlagStaysInTheDataDirectory() {
        val root = Files.createTempDirectory("mygomusic-enabled").toFile()
        try {
            val plugin = File(root, "plugins/demo")
            plugin.mkdirs()
            File(plugin, "plugin.json").writeText("""{"id":"demo","name":"Demo","main":"index.js"}""")
            File(plugin, "index.js").writeText("export {}")
            val store = PluginStore(root)
            assertTrue(store.isEnabled("demo"))
            assertTrue(store.list(Locale.ENGLISH).single().enabled)
            store.setEnabled("demo", false)
            assertFalse(store.isEnabled("demo"))
            assertFalse(store.list(Locale.ENGLISH).single().enabled)
            store.setEnabled("demo", true)
            assertTrue(store.list(Locale.ENGLISH).single().enabled)
            assertKind(PluginFailureKind.BadId) { store.setEnabled("../x", false) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun referencePluginsKeepTheirSettings() {
        val emby = referencePlugin("mygo-music-emby-jellyfin-v0.1.0")
        assumeTrue(emby != null)
        val settings = PluginManifest.read(emby!!).settings
        assertEquals(4, settings.size)
        assertEquals("server", settings[0].key)
        assertTrue(settings[0].link.startsWith("https://"))
        assertEquals(PluginSettingKind.Text, pluginSettingKind(settings[3], ""))
        val kugou = referencePlugin("mygo-music-kugou-lite-1.5.1")
        assumeTrue(kugou != null)
        val platform = PluginManifest.read(kugou!!).settings.first { it.key == "platform" }
        assertEquals(PluginSettingKind.Select, pluginSettingKind(platform, "nope"))
        val qq = referencePlugin("mygo-music-qq-plugin-main")
        assumeTrue(qq != null)
        val login = PluginManifest.read(File(qq!!, "dist")).settings.single()
        assertEquals("", login.type)
        assertEquals(PluginSettingKind.Text, pluginSettingKind(login, "qq"))
    }

    private fun writePlugin(root: File, json: String) {
        root.mkdirs()
        File(root, "plugin.json").writeText(json)
        File(root, "index.js").writeText("export {}")
    }

    private fun assertKind(kind: PluginFailureKind, block: () -> Unit) {
        try {
            block()
        } catch (e: PluginPackageException) {
            assertEquals(kind, e.kind)
            return
        }
        throw AssertionError(kind)
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
}
