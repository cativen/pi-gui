package dev.pi.gui.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.pi.gui.model.Attachment
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Row of pending attachments shown above the composer; each chip can be removed. */
class AttachmentStrip(
    private val onRemove: (Attachment) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(6))) {

    init {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(4)
        isVisible = false
    }

    fun setAttachments(attachments: List<Attachment>) {
        removeAll()
        attachments.forEach { add(chipFor(it)) }
        isVisible = attachments.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun chipFor(attachment: Attachment): JPanel {
        val chip = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = JBUI.scale(8)
                    g2.color = PiTheme.toolBg
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                    g2.color = PiTheme.toolBorder
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                } finally {
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 4)
            toolTipText = tooltipFor(attachment)
        }

        chip.add(contentFor(attachment), BorderLayout.CENTER)
        chip.add(removeButton(attachment), BorderLayout.EAST)
        return chip
    }

    private fun contentFor(attachment: Attachment): JPanel {
        val holder = JPanel(BorderLayout(JBUI.scale(4), 0)).apply { isOpaque = false }

        when (attachment) {
            is Attachment.Image -> {
                val thumb = JBLabel(AllIcons.FileTypes.Image).apply {
                    preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
                    horizontalAlignment = SwingConstants.CENTER
                }
                holder.add(thumb, BorderLayout.WEST)
                // Decoding happens off the EDT; a 10 MB PNG would otherwise stall the UI.
                loadThumbnailAsync(attachment, thumb)
            }

            is Attachment.FileRef -> {
                holder.add(
                    JBLabel().apply {
                        icon = if (attachment.isDirectory) AllIcons.Nodes.Folder else AllIcons.FileTypes.Text
                        foreground = PiTheme.mutedFg()
                        preferredSize = Dimension(JBUI.scale(16), JBUI.scale(18))
                    },
                    BorderLayout.WEST,
                )
            }
        }

        holder.add(
            JBLabel(shorten(attachment.displayName)).apply {
                font = font.deriveFont(font.size2D - 1f)
                foreground = PiTheme.textFg()
            },
            BorderLayout.CENTER,
        )
        (attachment as? Attachment.FileRef)?.lineLabel?.let { range ->
            holder.add(
                JBLabel(range).apply {
                    font = font.deriveFont(font.size2D - 2f)
                    foreground = PiTheme.accent
                    border = JBUI.Borders.emptyLeft(5)
                },
                BorderLayout.EAST,
            )
        }
        return holder
    }

    private fun loadThumbnailAsync(image: Attachment.Image, target: JBLabel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val icon = try {
                val bytes = Base64.getDecoder().decode(image.base64)
                ImageIO.read(ByteArrayInputStream(bytes))?.let { source ->
                    val size = JBUI.scale(28)
                    val scale = minOf(size.toDouble() / source.width, size.toDouble() / source.height)
                    val w = (source.width * scale).toInt().coerceAtLeast(1)
                    val h = (source.height * scale).toInt().coerceAtLeast(1)
                    ImageIcon(source.getScaledInstance(w, h, Image.SCALE_SMOOTH))
                }
            } catch (e: Exception) {
                null
            }
            if (icon != null) {
                ApplicationManager.getApplication().invokeLater {
                    target.icon = icon
                    target.repaint()
                }
            }
        }
    }

    private fun removeButton(attachment: Attachment): JBLabel =
        JBLabel(AllIcons.Actions.Close).apply {
            toolTipText = "Remove"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.emptyLeft(4)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onRemove(attachment)
                override fun mouseEntered(e: MouseEvent) {
                    icon = AllIcons.Actions.CloseHovered
                }
                override fun mouseExited(e: MouseEvent) {
                    icon = AllIcons.Actions.Close
                }
            })
        }

    private fun tooltipFor(attachment: Attachment): String = when (attachment) {
        is Attachment.Image ->
            "${attachment.displayName}  ·  ${Attachment.formatBytes(attachment.byteSize)}\n" +
                "Sent inline to the model"
        is Attachment.FileRef ->
            "${attachment.absolutePath}\n" +
                "Referenced as @${attachment.mentionPath} — pi will read it with its own tools"
    }

    private fun shorten(name: String): String =
        if (name.length <= 24) name else name.take(11) + "…" + name.takeLast(10)

    override fun getBackground(): Color = PiTheme.surfaceBg
}
