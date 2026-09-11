package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pi.gui.model.Attachment
import dev.pi.gui.ui.ChatPanel
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File

/**
 * Image paste must work through the component's `TransferHandler`, not a key binding: the IDE's
 * own `$Paste` action can consume Cmd+V before any Swing `InputMap` is consulted, and every
 * paste route — IDE action, `JTextComponent.paste()`, drag-and-drop — ends at `importData`.
 */
class ImagePasteTest : BasePlatformTestCase() {

    private class ImageTransferable(private val image: java.awt.Image) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(f: DataFlavor?) = f == DataFlavor.imageFlavor
        override fun getTransferData(f: DataFlavor?): Any = image
    }

    private class FileListTransferable(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(f: DataFlavor?) = f == DataFlavor.javaFileListFlavor
        override fun getTransferData(f: DataFlavor?): Any = files
    }

    private fun image(w: Int = 8, h: Int = 8): BufferedImage =
        BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also {
            it.createGraphics().apply { color = java.awt.Color.BLUE; fillRect(0, 0, w, h); dispose() }
        }

    /** Drives the real handler the way Swing and the IDE both do. */
    private fun importInto(panel: ChatPanel, transferable: Transferable): Boolean {
        val input = panel.inputForTest()
        val handler = input.transferHandler
        assertNotNull("composer must have a transfer handler", handler)
        return handler.importData(javax.swing.TransferHandler.TransferSupport(input, transferable))
    }

    private fun waitForAttachment(panel: ChatPanel): List<Attachment> {
        // Encoding runs on a pooled thread, then hops back to the EDT.
        repeat(60) {
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            if (panel.attachmentsForTest().isNotEmpty()) return panel.attachmentsForTest()
            Thread.sleep(50)
        }
        return panel.attachmentsForTest()
    }

    fun testPastingAnImageCreatesAnAttachment() {
        val panel = ChatPanel(project)
        try {
            assertTrue("handler must accept the image", importInto(panel, ImageTransferable(image())))
            val attachments = waitForAttachment(panel)
            assertEquals(1, attachments.size)
            val attachment = attachments.single() as Attachment.Image
            assertEquals("image/png", attachment.mimeType)
            assertTrue("payload must be real base64 PNG", attachment.base64.isNotBlank())
            // Decodes back to a PNG of the original size.
            val bytes = java.util.Base64.getDecoder().decode(attachment.base64)
            val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
            assertEquals(8, decoded.width)
            assertEquals(8, decoded.height)
        } finally {
            panel.dispose()
        }
    }

    fun testHandlerAdvertisesImageSupport() {
        val panel = ChatPanel(project)
        try {
            val input = panel.inputForTest()
            val support = javax.swing.TransferHandler.TransferSupport(input, ImageTransferable(image()))
            assertTrue(input.transferHandler.canImport(support))
        } finally {
            panel.dispose()
        }
    }

    /** An image copied in Finder arrives as a file list; that route must still win. */
    fun testImageFileFromFinderIsAttachedAsAFile() {
        val png = File.createTempFile("pi-paste", ".png").also {
            javax.imageio.ImageIO.write(image(), "png", it)
            it.deleteOnExit()
        }
        val panel = ChatPanel(project)
        try {
            assertTrue(importInto(panel, FileListTransferable(listOf(png))))
            val attachments = waitForAttachment(panel)
            assertEquals(1, attachments.size)
            val attachment = attachments.single() as Attachment.Image
            assertEquals(png.name, attachment.displayName)
            assertEquals(png.absolutePath, attachment.sourcePath)
        } finally {
            panel.dispose()
            png.delete()
        }
    }

    /** Text must still reach the editor rather than being eaten as an attachment. */
    fun testPastingTextIsNotTurnedIntoAnAttachment() {
        val panel = ChatPanel(project)
        try {
            val input = panel.inputForTest()
            input.text = ""
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection("just words"), null)
            input.paste()
            assertEquals("just words", input.text)
            assertTrue(panel.attachmentsForTest().isEmpty())
        } finally {
            panel.dispose()
        }
    }

    fun testOversizedPastedImageIsRejected() {
        val panel = ChatPanel(project)
        try {
            // Per-pixel noise, because PNG packs flat regions down well below the cap.
            val side = 2400
            val pixels = IntArray(side * side)
            val random = java.util.Random(1)
            for (i in pixels.indices) pixels[i] = random.nextInt()
            val huge = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
            huge.setRGB(0, 0, side, side, pixels, 0, side)
            importInto(panel, ImageTransferable(huge))
            repeat(80) {
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(50)
            }
            assertTrue(
                "an oversized image must not be attached",
                panel.attachmentsForTest().isEmpty(),
            )
        } finally {
            panel.dispose()
        }
    }
}
