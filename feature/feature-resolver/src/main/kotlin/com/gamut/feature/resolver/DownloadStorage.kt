package com.gamut.feature.resolver

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import com.gamut.core.model.LocalCovers
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadStorage
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: StorageSettings,
    ) {
        private val resolver: ContentResolver get() = context.contentResolver

        val privateDir: File = File(context.filesDir, "downloads")

        private val customFolder: Uri? get() = settings.current.musicFolderUri?.let(Uri::parse)

        fun isUri(location: String) = location.startsWith("content://")

        fun privateBaseFor(isrc: String) = isrc.replace(Regex("[^A-Za-z0-9._-]"), "_")

        fun isAtDestination(location: String): Boolean {
            val tree = customFolder ?: return !isUri(location)
            if (!isUri(location)) return false
            return try {
                val doc = Uri.parse(location)
                doc.authority == tree.authority &&
                    DocumentsContract.getTreeDocumentId(doc) == DocumentsContract.getTreeDocumentId(tree)
            } catch (_: Exception) {
                false
            }
        }

        fun copyOut(
            location: String,
            target: File,
        ) {
            val input = if (isUri(location)) resolver.openInputStream(Uri.parse(location)) else File(location).inputStream()
            (input ?: throw IOException("Couldn\u2019t open the song")).use { source ->
                target.outputStream().use { sink -> source.copyTo(sink, COPY_BUFFER) }
            }
        }

        fun coverFile(isrc: String): File = File(File(context.filesDir, LocalCovers.DIR), LocalCovers.fileName(isrc))

        fun adopt(tree: Uri): Boolean {
            if (tree.authority != LOCAL_AUTHORITY) return false
            try {
                resolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                return false
            }
            settings.update { it.copy(musicFolderUri = tree.toString()) }
            return true
        }

        fun folderAvailable(): Boolean? = customFolder?.let { hasWriteGrant(it) && reachable(it) }

        fun folderLabel(tree: String): String =
            try {
                val id = DocumentsContract.getTreeDocumentId(Uri.parse(tree))
                val volume = id.substringBefore(':')
                val path = id.substringAfter(':', "").trim('/')
                val root = if (volume == "primary") "Internal storage" else "External storage"
                if (path.isEmpty()) root else "$root/$path"
            } catch (_: Exception) {
                "Chosen folder"
            }

        fun checkWritable() {
            val tree = customFolder ?: return
            if (!hasWriteGrant(tree) || !reachable(tree)) throw IOException(UNAVAILABLE)
        }

        fun store(
            part: File,
            privateBase: String,
            displayBase: String,
            extension: String,
            forcePrivate: Boolean = false,
        ): String {
            val tree = customFolder?.takeUnless { forcePrivate } ?: return storeInPrivate(part, privateBase, extension)
            return storeInFolder(tree, part, displayBase, extension)
        }

        private fun storeInPrivate(
            part: File,
            base: String,
            extension: String,
        ): String {
            val target = File(privateDir, "$base.$extension")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Couldn\u2019t save the file")
            return target.absolutePath
        }

        private fun storeInFolder(
            tree: Uri,
            part: File,
            displayBase: String,
            extension: String,
        ): String {
            if (!hasWriteGrant(tree)) throw IOException(UNAVAILABLE)
            val parent =
                try {
                    DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                } catch (e: Exception) {
                    throw IOException(UNAVAILABLE, e)
                }
            val mime =
                when (extension) {
                    "opus" -> "audio/ogg"
                    "webm" -> "audio/webm"
                    else -> "audio/mp4"
                }
            val created: Uri? =
                try {
                    DocumentsContract.createDocument(resolver, parent, mime, "$displayBase.$extension")
                } catch (e: Exception) {
                    throw IOException(UNAVAILABLE, e)
                }
            var doc: Uri = created ?: throw IOException(UNAVAILABLE)

            try {
                val out = resolver.openOutputStream(doc, "wt") ?: throw IOException("Couldn\u2019t write to the music folder")
                out.use { sink -> part.inputStream().use { it.copyTo(sink, COPY_BUFFER) } }
                doc = withExpectedExtension(doc, "$displayBase.$extension", extension)
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                throw if (e is IOException) e else IOException("Couldn\u2019t write to the music folder", e)
            }
            part.delete()
            requestMediaScan(doc, mime)
            return doc.toString()
        }

        private fun withExpectedExtension(
            doc: Uri,
            wanted: String,
            extension: String,
        ): Uri {
            val actual = queryString(doc, DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: return doc
            if (actual.endsWith(".$extension", ignoreCase = true)) return doc
            return runCatching { DocumentsContract.renameDocument(resolver, doc, wanted) }.getOrNull() ?: doc
        }

        fun exists(location: String): Boolean? {
            if (!isUri(location)) return File(location).exists()
            val uri = Uri.parse(location)
            if (queryString(uri, DocumentsContract.Document.COLUMN_DOCUMENT_ID) != null) return true
            return if (reachable(uri)) false else null
        }

        fun playableUri(location: String?): Uri? {
            if (location == null) return null
            if (!isUri(location)) return File(location).takeIf { it.exists() }?.let(Uri::fromFile)
            return Uri.parse(location).takeIf { exists(location) == true }
        }

        fun delete(location: String) {
            if (!isUri(location)) {
                File(location).delete()
                return
            }
            val uri = Uri.parse(location)
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            requestMediaScan(uri, null)
        }

        private fun requestMediaScan(
            doc: Uri,
            mime: String?,
        ) {
            try {
                val id = DocumentsContract.getDocumentId(doc)
                val volume = id.substringBefore(':')
                val path = id.substringAfter(':', "")
                if (path.isEmpty()) return
                val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
                MediaScannerConnection.scanFile(context, arrayOf("$root/$path"), arrayOf(mime), null)
            } catch (_: Exception) {
            }
        }

        fun extensionOf(location: String): String =
            if (isUri(location)) {
                Uri
                    .parse(location)
                    .lastPathSegment
                    ?.substringAfterLast('.', "")
                    .orEmpty()
                    .lowercase()
            } else {
                File(location).extension.lowercase()
            }

        fun baseNameOf(location: String): String =
            if (isUri(location)) {
                Uri
                    .parse(location)
                    .lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    .orEmpty()
            } else {
                File(location).nameWithoutExtension
            }

        fun fileNameFor(
            artist: String,
            title: String,
            fallback: String,
        ): String {
            val raw = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { fallback }
            val clean =
                raw
                    .replace(UNSAFE_NAME_CHARS, "_")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .trim('.')
                    .take(MAX_NAME_LENGTH)
                    .trim()
            return clean.ifBlank { fallback }
        }

        private fun hasWriteGrant(tree: Uri) = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

        private fun reachable(uri: Uri): Boolean =
            try {
                val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                resolver
                    .query(root, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                    ?.use { it.moveToFirst() } ?: false
            } catch (_: Exception) {
                false
            }

        private fun queryString(
            uri: Uri,
            column: String,
        ): String? =
            try {
                resolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            } catch (_: Exception) {
                null
            }

        private companion object {
            const val LOCAL_AUTHORITY = "com.android.externalstorage.documents"
            const val UNAVAILABLE = "Music folder isn\u2019t available. Choose it again in Storage settings."
            const val COPY_BUFFER = 64 * 1024
            const val MAX_NAME_LENGTH = 120
            val UNSAFE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
        }
    }
