package dev.pi.gui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import dev.pi.gui.commands.PiCommand
import dev.pi.gui.settings.PiSettings
import dev.pi.gui.settings.ThemeMode
import dev.pi.gui.ui.CommandPopup
import java.awt.Dimension
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

/** Renders the `/` popup offscreen so its appearance can be inspected without a running IDE. */
class CommandPopupPreview : BasePlatformTestCase() {

    fun testRenderCommandPopup() {
        render(dark = true, file = File("/tmp/pi-gui-commands-dark.png"))
        render(dark = false, file = File("/tmp/pi-gui-commands-light.png"))
    }

    private fun render(dark: Boolean, file: File) {
        JBColor.setDark(dark)
        PiSettings.getInstance().themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT

        val popup = CommandPopup { }
        // A realistic mix: pi's built-ins first, then extension and skill commands.
        val content = popup.contentForTest(
            dev.pi.gui.commands.BuiltinCommands.AS_COMMANDS.take(4) +
                listOf(
                    PiCommand("llama", "Manage llama.cpp router models", "extension"),
                    PiCommand("skill:xlsx", "Use this skill any time a spreadsheet file is the primary input or output.", "skill", location = "user"),
                )
        )

        val frame = javax.swing.JFrame()
        try {
            frame.isUndecorated = true
            frame.contentPane.add(content)
            frame.size = Dimension(560, 165)
            frame.pack()
            frame.setSize(560, 165)
            frame.validate()
            layoutTree(frame)

            val image = BufferedImage(560, 165, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                content.paint(g)
            } finally {
                g.dispose()
            }
            javax.imageio.ImageIO.write(image, "png", file)
            println("wrote ${file.absolutePath}")
        } finally {
            frame.dispose()
        }
    }

    private fun layoutTree(component: java.awt.Component) {
        if (component is java.awt.Container) {
            component.doLayout()
            component.components.forEach { layoutTree(it) }
        }
    }
}
