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
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.UnzipParameters
import net.lingala.zip4j.util.BitUtils
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal data class StagedPlugin(
    val work: File,
    val root: File,
    val manifest: PluginManifest,
) {
    fun discard() {
        work.deleteRecursively()
    }
}

internal object PluginPackages {
    fun stageZip(zip: File, cacheDir: File): StagedPlugin {
        val work = newWorkDir(cacheDir)
        try {
            val unpacked = File(work, "unpacked")
            unzip(zip, unpacked)
            return inspect(work, unpacked)
        } catch (e: Throwable) {
            work.deleteRecursively()
            throw e
        }
    }

    fun stageDirectory(source: File, cacheDir: File): StagedPlugin {
        val work = newWorkDir(cacheDir)
        try {
            val unpacked = File(work, "unpacked")
            copyTree(source, unpacked, skipHidden = true)
            return inspect(work, unpacked)
        } catch (e: Throwable) {
            work.deleteRecursively()
            throw e
        }
    }

    fun stageDocumentTree(context: Context, tree: DocumentFile, cacheDir: File): StagedPlugin {
        val work = newWorkDir(cacheDir)
        try {
            val unpacked = File(work, "unpacked")
            copyDocumentTree(context, tree, unpacked)
            return inspect(work, unpacked)
        } catch (e: Throwable) {
            work.deleteRecursively()
            throw e
        }
    }

    fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            if (read.toLong() > maxBytes - total) {
                throw PluginPackageException(PluginFailureKind.TooLarge)
            }
            output.write(buffer, 0, read)
            total += read
        }
    }

    private fun inspect(work: File, unpacked: File): StagedPlugin {
        val root = pluginRoot(unpacked)
        val manifest = PluginManifest.read(root)
        return StagedPlugin(work, root, manifest)
    }

    private fun newWorkDir(cacheDir: File): File {
        val dir = File(cacheDir, "mygomusic-import-${UUID.randomUUID()}")
        if (!dir.mkdirs()) throw PluginPackageException(PluginFailureKind.Io)
        return dir
    }

    /** plugin.json at the top, or in the only real folder (a GitHub archive). */
    fun pluginRoot(dir: File): File {
        if (File(dir, "plugin.json").isFile) return dir
        // Finder puts this beside the plugin. It is not a second plugin root.
        val folders = dir.listFiles().orEmpty().filter {
            it.isDirectory && !it.name.startsWith(".") && it.name != "__MACOSX"
        }
        if (folders.size == 1 && File(folders[0], "plugin.json").isFile) return folders[0]
        throw PluginPackageException(PluginFailureKind.NotAPlugin)
    }

    private fun unzip(zipFile: File, dest: File) {
        if (zipFile.length() > MAX_ARCHIVE_BYTES) {
            throw PluginPackageException(PluginFailureKind.TooLarge)
        }
        val zip = ZipFile(zipFile)
        try {
            if (!zip.isValidZipFile) throw PluginPackageException(PluginFailureKind.NotAPlugin)
            val headers = zip.fileHeaders
            if (headers.size > MAX_PLUGIN_FILES) {
                throw PluginPackageException(PluginFailureKind.TooManyFiles)
            }
            var declared = 0L
            for (header in headers) {
                if (header.isUnixSymlink()) {
                    throw PluginPackageException(PluginFailureKind.Link, header.fileName)
                }
                val size = header.uncompressedSize
                if (size < 0 || declared > MAX_UNPACKED_BYTES - size) {
                    throw PluginPackageException(PluginFailureKind.TooLarge)
                }
                declared += size
            }
            if (!dest.isDirectory && !dest.mkdirs()) {
                throw PluginPackageException(PluginFailureKind.Io)
            }
            val params = UnzipParameters()
            params.isExtractSymbolicLinks = false
            try {
                zip.extractAll(dest.path, params)
            } catch (e: ZipException) {
                val message = e.message.orEmpty()
                if (message.contains("illegal file name", true) ||
                    message.contains("target directory", true)
                ) {
                    throw PluginPackageException(PluginFailureKind.ZipSlip, message, e)
                }
                throw PluginPackageException(PluginFailureKind.Io, cause = e)
            }
            File(dest, "__MACOSX").deleteRecursively()
            var unpacked = 0L
            var files = 0
            for (file in dest.walkTopDown()) {
                if (file.isSymlink()) throw PluginPackageException(PluginFailureKind.Link, file.name)
                if (!file.isFile) continue
                files++
                if (files > MAX_PLUGIN_FILES) {
                    throw PluginPackageException(PluginFailureKind.TooManyFiles)
                }
                val len = file.length()
                if (len < 0 || unpacked > MAX_UNPACKED_BYTES - len) {
                    throw PluginPackageException(PluginFailureKind.TooLarge)
                }
                unpacked += len
            }
        } catch (e: PluginPackageException) {
            throw e
        } catch (e: IOException) {
            throw PluginPackageException(PluginFailureKind.Io, cause = e)
        } finally {
            try {
                zip.close()
            } catch (_: IOException) {
            }
        }
    }

    fun copyTree(from: File, to: File, skipHidden: Boolean) {
        if (from.isSymlink()) throw PluginPackageException(PluginFailureKind.Link, from.name)
        if (!from.isDirectory) throw PluginPackageException(PluginFailureKind.NotAPlugin)
        var files = 0
        var bytes = 0L
        fun walk(src: File, dest: File) {
            if (!dest.isDirectory && !dest.mkdirs()) {
                throw PluginPackageException(PluginFailureKind.Io)
            }
            val children = src.listFiles() ?: throw PluginPackageException(PluginFailureKind.Io)
            for (child in children) {
                if (skipHidden && child.name.startsWith(".")) continue
                if (child.name.contains('/') || child.name.contains('\\') || child.name.contains('\u0000')) {
                    throw PluginPackageException(PluginFailureKind.ZipSlip, child.name)
                }
                if (child.isSymlink()) throw PluginPackageException(PluginFailureKind.Link, child.name)
                val target = File(dest, child.name)
                if (child.isDirectory) {
                    walk(child, target)
                } else if (child.isFile) {
                    files++
                    if (files > MAX_PLUGIN_FILES) {
                        throw PluginPackageException(PluginFailureKind.TooManyFiles)
                    }
                    val len = child.length()
                    if (len < 0 || bytes > MAX_UNPACKED_BYTES - len) {
                        throw PluginPackageException(PluginFailureKind.TooLarge)
                    }
                    child.inputStream().use { input ->
                        target.outputStream().use { output ->
                            copyLimited(input, output, MAX_UNPACKED_BYTES - bytes)
                        }
                    }
                    bytes += len
                }
            }
        }
        walk(from, to)
    }

    private fun copyDocumentTree(context: Context, tree: DocumentFile, dest: File) {
        var files = 0
        var bytes = 0L
        fun walk(src: DocumentFile, targetDir: File) {
            if (!targetDir.isDirectory && !targetDir.mkdirs()) {
                throw PluginPackageException(PluginFailureKind.Io)
            }
            for (child in src.listFiles()) {
                val name = child.name ?: continue
                if (name.startsWith(".")) continue
                if (name == "." || name == ".." || name.contains('/') || name.contains('\\') ||
                    name.contains('\u0000')
                ) {
                    throw PluginPackageException(PluginFailureKind.ZipSlip, name)
                }
                val target = File(targetDir, name)
                if (child.isDirectory) {
                    walk(child, target)
                } else if (child.isFile) {
                    files++
                    if (files > MAX_PLUGIN_FILES) {
                        throw PluginPackageException(PluginFailureKind.TooManyFiles)
                    }
                    val reported = child.length()
                    if (reported > 0 && bytes > MAX_UNPACKED_BYTES - reported) {
                        throw PluginPackageException(PluginFailureKind.TooLarge)
                    }
                    val input = context.contentResolver.openInputStream(child.uri)
                        ?: throw PluginPackageException(PluginFailureKind.Io)
                    input.use { stream ->
                        target.outputStream().use { output ->
                            bytes += copyLimited(stream, output, MAX_UNPACKED_BYTES - bytes)
                        }
                    }
                }
            }
        }
        walk(tree, dest)
    }
}

/**
 * Zip4j treats external-attributes byte 3 bit 5 as a Unix symlink and would
 * create that link on extract. The plugin contract rejects the package instead.
 */
internal fun FileHeader.isUnixSymlink(): Boolean {
    val attrs = externalFileAttributes ?: return false
    if (attrs.size < 4) return false
    return BitUtils.isBitSet(attrs[3], 5)
}

private fun File.isSymlink(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return java.nio.file.Files.isSymbolicLink(toPath())
    }
    val parentCanon = parentFile?.canonicalFile ?: return false
    val child = File(parentCanon, name)
    return child.canonicalPath != child.absolutePath
}
