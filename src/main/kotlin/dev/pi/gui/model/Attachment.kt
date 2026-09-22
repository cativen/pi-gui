package dev.pi.gui.model

import java.io.File
import java.util.Base64

/**
 * Something the user attached to the next message.
 *
 * pi accepts two very different things. Images travel inline as base64 in the prompt's `images`
 * array and are actually seen by the model. Everything else has no binary channel in the RPC
 * protocol, so it is referenced by path and the agent reads it with its own tools — which also
 * keeps a 50 MB log out of the context window.
 */
sealed class Attachment {
    abstract val displayName: String

    data class Image(
        override val displayName: String,
        val mimeType: String,
        /** Base64 payload, no data-URL prefix. */
        val base64: String,
        val byteSize: Long,
        /** Absolute path when it came from disk; null for a clipboard paste. */
        val sourcePath: String?,
    ) : Attachment()

    data class FileRef(
        override val displayName: String,
        val absolutePath: String,
        /** Path as it will appear in the `@mention`, relative to the project when possible. */
        val mentionPath: String,
        val isDirectory: Boolean,
        val byteSize: Long,
        /** Selected source lines, when the reference came from an editor selection. */
        val lineStart: Int? = null,
        val lineEnd: Int? = null,
    ) : Attachment() {
        val extensionLabel: String
            get() = when {
                isDirectory -> "DIR"
                else -> displayName.substringAfterLast('.', "").uppercase().take(5)
                    .ifEmpty { "FILE" }
            }

        val lineLabel: String?
            get() = lineStart?.let { start ->
                val end = lineEnd ?: start
                if (end == start) "Line $start" else "Line $start–$end"
            }
    }

    companion object {
        /** Matches pi's own server-side validation, so we reject before the agent does. */
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_IMAGES = 10

        private val IMAGE_MIME = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "bmp" to "image/bmp",
        )

        fun isImage(file: File): Boolean =
            IMAGE_MIME.containsKey(file.extension.lowercase())

        fun mimeTypeOf(file: File): String =
            IMAGE_MIME[file.extension.lowercase()] ?: "application/octet-stream"

        /** Reads and encodes an image. Returns null when it is too large or unreadable. */
        fun imageFrom(file: File): Image? {
            if (!file.isFile) return null
            if (file.length() > MAX_IMAGE_BYTES) return null
            val bytes = try { file.readBytes() } catch (e: Exception) { return null }
            return Image(
                displayName = file.name,
                mimeType = mimeTypeOf(file),
                base64 = Base64.getEncoder().encodeToString(bytes),
                byteSize = bytes.size.toLong(),
                sourcePath = file.absolutePath,
            )
        }

        fun fileRefFrom(file: File, projectBasePath: String?): FileRef {
            val path = file.absolutePath.replace('\\', '/')
            val relative =
                if (projectBasePath != null && path.startsWith("${projectBasePath.replace('\\', '/')}/")) {
                    path.removePrefix("${projectBasePath.replace('\\', '/')}/")
                } else {
                    path
                }
            val mention = if (file.isDirectory && !relative.endsWith("/")) "$relative/" else relative
            return FileRef(
                displayName = file.name,
                absolutePath = file.absolutePath,
                mentionPath = mention,
                isDirectory = file.isDirectory,
                byteSize = if (file.isFile) file.length() else 0,
            )
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
