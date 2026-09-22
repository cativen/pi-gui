package dev.pi.gui

import dev.pi.gui.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class AttachmentTest {

    @Test
    fun `selected file references expose a compact line label`() {
        val ref = Attachment.FileRef(
            "Mapper.xml", "/tmp/Mapper.xml", "Mapper.xml:50-88", false, 10,
            lineStart = 50, lineEnd = 88,
        )

        assertEquals("Line 50–88", ref.lineLabel)
    }

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `image extensions are recognised case-insensitively`() {
        assertTrue(Attachment.isImage(tmp.newFile("a.png")))
        assertTrue(Attachment.isImage(tmp.newFile("b.JPG")))
        assertTrue(Attachment.isImage(tmp.newFile("c.webp")))
        assertFalse(Attachment.isImage(tmp.newFile("d.log")))
        assertFalse(Attachment.isImage(tmp.newFile("e.xlsx")))
        assertFalse(Attachment.isImage(tmp.newFile("noext")))
    }

    @Test
    fun `mime types map to what pi expects`() {
        assertEquals("image/png", Attachment.mimeTypeOf(tmp.newFile("a.png")))
        assertEquals("image/jpeg", Attachment.mimeTypeOf(tmp.newFile("b.jpeg")))
        assertEquals("image/jpeg", Attachment.mimeTypeOf(tmp.newFile("c.jpg")))
        assertEquals("application/octet-stream", Attachment.mimeTypeOf(tmp.newFile("d.bin")))
    }

    @Test
    fun `image is read and base64 encoded`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val file = tmp.newFile("pic.png").apply { writeBytes(bytes) }

        val image = Attachment.imageFrom(file)!!
        assertEquals("pic.png", image.displayName)
        assertEquals("image/png", image.mimeType)
        assertEquals(5L, image.byteSize)
        assertArrayEquals(bytes, Base64.getDecoder().decode(image.base64))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)

    /** pi rejects images over 10 MB server-side; catching it here keeps the failure local. */
    @Test
    fun `oversized images are rejected`() {
        val file = tmp.newFile("huge.png")
        file.writeBytes(ByteArray((Attachment.MAX_IMAGE_BYTES + 1).toInt()))
        assertNull(Attachment.imageFrom(file))
    }

    @Test
    fun `a directory is not a readable image`() {
        assertNull(Attachment.imageFrom(tmp.newFolder("dir.png")))
    }

    @Test
    fun `file refs inside the project use a relative mention`() {
        val project = tmp.newFolder("proj")
        val nested = java.io.File(project, "src/db").apply { mkdirs() }
        val file = java.io.File(nested, "query.sql").apply { writeText("select 1") }

        val ref = Attachment.fileRefFrom(file, project.absolutePath)
        assertEquals("src/db/query.sql", ref.mentionPath)
        assertEquals("query.sql", ref.displayName)
        assertFalse(ref.isDirectory)
    }

    @Test
    fun `file refs outside the project keep the absolute path`() {
        val project = tmp.newFolder("proj2")
        val outside = tmp.newFile("elsewhere.log")

        val ref = Attachment.fileRefFrom(outside, project.absolutePath)
        assertEquals(outside.absolutePath.replace('\\', '/'), ref.mentionPath)
    }

    /** A directory mention ends in "/" so the agent treats it as a tree, not a file. */
    @Test
    fun `directory mentions get a trailing slash`() {
        val project = tmp.newFolder("proj3")
        val dir = java.io.File(project, "logs").apply { mkdirs() }

        val ref = Attachment.fileRefFrom(dir, project.absolutePath)
        assertEquals("logs/", ref.mentionPath)
        assertTrue(ref.isDirectory)
        assertEquals("DIR", ref.extensionLabel)
    }

    @Test
    fun `extension label is derived from the name`() {
        val project = tmp.newFolder("proj4")
        assertEquals(
            "XLSX",
            Attachment.fileRefFrom(java.io.File(project, "book.xlsx"), project.absolutePath).extensionLabel,
        )
        assertEquals(
            "LOG",
            Attachment.fileRefFrom(java.io.File(project, "app.log"), project.absolutePath).extensionLabel,
        )
        assertEquals(
            "FILE",
            Attachment.fileRefFrom(java.io.File(project, "README"), project.absolutePath).extensionLabel,
        )
    }

    @Test
    fun `byte sizes are human readable`() {
        assertEquals("512 B", Attachment.formatBytes(512))
        assertEquals("2 KB", Attachment.formatBytes(2048))
        assertEquals("1.5 MB", Attachment.formatBytes((1.5 * 1024 * 1024).toLong()))
    }
}
