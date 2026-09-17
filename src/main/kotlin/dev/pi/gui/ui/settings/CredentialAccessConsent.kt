package dev.pi.gui.ui.settings

import com.intellij.openapi.ui.Messages
import dev.pi.gui.i18n.PiBundle
import dev.pi.gui.settings.PiSettings
import java.awt.Component

/**
 * The single consent boundary for local AI credentials.
 *
 * The decision is persisted because imported providers are used across IDE restarts.  Denial is
 * fail-closed: callers must not probe cc-switch or read Pi GUI's imported-provider sidecar.
 */
object CredentialAccessConsent {

    fun isGranted(): Boolean = PiSettings.getInstance().credentialAccessGranted

    fun request(parent: Component): Boolean {
        if (isGranted()) return true

        val choice = Messages.showDialog(
            parent,
            PiBundle.message("credentials.consent.message"),
            PiBundle.message("credentials.consent.title"),
            arrayOf(
                PiBundle.message("credentials.consent.allow"),
                PiBundle.message("credentials.consent.deny"),
            ),
            1,
            Messages.getQuestionIcon(),
        )
        if (choice != 0) return false

        PiSettings.getInstance().apply {
            credentialAccessGranted = true
            fireChanged()
        }
        return true
    }
}
