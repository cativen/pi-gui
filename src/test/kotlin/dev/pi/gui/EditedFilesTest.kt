package dev.pi.gui

import dev.pi.gui.edits.EditedFiles
import dev.pi.gui.model.ContentBlock
import dev.pi.gui.model.PiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditedFilesTest {

    private fun toolCall(id: String, name: String, path: String) =
        PiMessage.Assistant(
            mutableListOf(
                ContentBlock.ToolCall(id, name, """{"file_path": "$path", "content": "x"}"""),
            )
        )

    private fun result(id: String, error: Boolean = false) =
        PiMessage.ToolResult(id, "edit", if (error) "failed" else "ok", error)

    // -------------------------------------------------------- tool matching

    @Test
    fun `built-in and decorated write names are recognised`() {
        listOf("write", "Write", "write_file", "fs.write", "mcp_write").forEach {
            assertTrue(it, EditedFiles.isWriteTool(it))
        }
        listOf("read", "bash", "grep", "rewrite_history").forEach {
            assertFalse(it, EditedFiles.isWriteTool(it))
        }
    }

    @Test
    fun `built-in and decorated edit names are recognised`() {
        listOf("edit", "edit_file", "fs.edit", "mcp_edit", "str_replace_editor", "replace_editor")
            .forEach { assertTrue(it, EditedFiles.isEditTool(it)) }
        listOf("read", "bash", "list").forEach { assertFalse(it, EditedFiles.isEditTool(it)) }
    }

    // ----------------------------------------------------------- extraction

    @Test
    fun `a successful edit counts`() {
        val edits = EditedFiles.collect(
            listOf(toolCall("c1", "edit", "src/A.kt"), result("c1")),
            "/proj",
        )
        assertEquals(1, edits.size)
        assertEquals("src/A.kt", edits[0].displayPath)
        // Canonical paths use '\' on Windows, '/' elsewhere.
        assertTrue(edits[0].absolutePath.replace('\\', '/').endsWith("/proj/src/A.kt"))
    }

    /** A failed tool call wrote nothing, so it must not be reported as an edit. */
    @Test
    fun `a failed edit does not count`() {
        val edits = EditedFiles.collect(
            listOf(toolCall("c1", "edit", "src/A.kt"), result("c1", error = true)),
            "/proj",
        )
        assertTrue(edits.isEmpty())
    }

    /** Still streaming: the result has not arrived, so nothing is proven yet. */
    @Test
    fun `an edit without a result does not count`() {
        val edits = EditedFiles.collect(listOf(toolCall("c1", "edit", "src/A.kt")), "/proj")
        assertTrue(edits.isEmpty())
    }

    /** Prose is not evidence — only tool calls are. */
    @Test
    fun `text claiming an edit does not count`() {
        val edits = EditedFiles.collect(
            listOf(PiMessage.Assistant(mutableListOf(ContentBlock.Text("I updated src/A.kt for you")))),
            "/proj",
        )
        assertTrue(edits.isEmpty())
    }

    @Test
    fun `non-writing tools are ignored`() {
        val edits = EditedFiles.collect(
            listOf(toolCall("c1", "read", "src/A.kt"), result("c1")),
            "/proj",
        )
        assertTrue(edits.isEmpty())
    }

    @Test
    fun `repeated edits to one file collapse with a count`() {
        val edits = EditedFiles.collect(
            listOf(
                toolCall("c1", "edit", "src/A.kt"), result("c1"),
                toolCall("c2", "edit", "src/A.kt"), result("c2"),
                toolCall("c3", "write", "src/B.kt"), result("c3"),
            ),
            "/proj",
        )
        assertEquals(2, edits.size)
        assertEquals(2, edits.first { it.displayPath == "src/A.kt" }.editCount)
        assertEquals(1, edits.first { it.displayPath == "src/B.kt" }.editCount)
    }

    @Test
    fun `first-seen order is preserved`() {
        val edits = EditedFiles.collect(
            listOf(
                toolCall("c1", "edit", "z.kt"), result("c1"),
                toolCall("c2", "edit", "a.kt"), result("c2"),
            ),
            "/proj",
        )
        assertEquals(listOf("z.kt", "a.kt"), edits.map { it.displayPath })
    }

    /**
     * Paths are canonicalized so the same file reached by different routes dedupes. The expected
     * value is canonicalized too, because on macOS `/etc` and `/tmp` are symlinks.
     */
    @Test
    fun `absolute paths outside the project keep their full path`() {
        val outside = java.io.File(System.getProperty("java.io.tmpdir"), "pi-outside.txt")
        val edits = EditedFiles.collect(
            listOf(toolCall("c1", "write", outside.absolutePath), result("c1")),
            "/proj",
        )
        val expected = try { outside.canonicalPath } catch (e: Exception) { outside.absolutePath }
        assertEquals(expected, edits.single().displayPath)
        assertEquals(expected, edits.single().absolutePath)
    }

    // ------------------------------------------------------ argument parsing

    @Test
    fun `path is read from either argument name`() {
        assertEquals("a/b.kt", EditedFiles.pathArgument("""{"file_path": "a/b.kt"}"""))
        assertEquals("a/b.kt", EditedFiles.pathArgument("""{"path": "a/b.kt"}"""))
        assertNull(EditedFiles.pathArgument("""{"query": "x"}"""))
        assertNull(EditedFiles.pathArgument("""{"file_path": ""}"""))
    }

    /** Windows paths arrive JSON-escaped and must survive unescaping. */
    @Test
    fun `escaped path separators are unescaped`() {
        assertEquals("""C:\src\A.kt""", EditedFiles.pathArgument("""{"file_path": "C:\\src\\A.kt"}"""))
    }

    @Test
    fun `pretty printed arguments are handled`() {
        val json = """
            {
              "file_path": "src/Main.kt",
              "old_string": "a"
            }
        """.trimIndent()
        assertEquals("src/Main.kt", EditedFiles.pathArgument(json))
    }
}
