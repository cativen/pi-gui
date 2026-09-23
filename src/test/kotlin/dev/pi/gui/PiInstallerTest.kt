package dev.pi.gui

import junit.framework.TestCase

class PiInstallerTest : TestCase() {

    fun testOfficialUnixInstallCommandIsExact() {
        assertEquals("curl -fsSL https://pi.dev/install.sh | sh", PiInstaller.command(isWindows = false))
    }

    fun testOfficialWindowsInstallCommandIsExact() {
        assertEquals(
            "powershell -c \"irm https://pi.dev/install.ps1 | iex\"",
            PiInstaller.command(isWindows = true),
        )
    }
}
