package dev.pi.gui.web

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Hosts the chat UI in JCEF and carries messages between it and the plugin.
 *
 * The page is assembled in Kotlin and handed to the browser as one self-contained document —
 * stylesheet and script inlined — rather than served over a custom scheme. A plugin's resources
 * live inside a jar, which Chromium cannot fetch directly, and the alternatives (registering a
 * scheme handler, or unpacking to a temp directory) add moving parts for no gain when the whole
 * app is three small files.
 */
class PiWebView(
    /** Page under `/web`, without the extension: "transcript" or "index". */
    private val page: String,
    private val onMessage: (JsonObject) -> Unit,
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(PiWebView::class.java)
    private val gson = Gson()

    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)

    /** Events produced before the page finished loading, replayed once it is ready. */
    private val pending = mutableListOf<String>()
    @Volatile private var ready = false
    @Volatile private var disposed = false

    val component: JComponent get() = this

    init {
        Disposer.register(this, browser)

        query.addHandler { payload ->
            if (!disposed) {
                try {
                    val json = JsonParser.parseString(payload)
                    if (json.isJsonObject) onMessage(json.asJsonObject)
                } catch (e: Exception) {
                    log.warn("Unparseable message from the web view: ${payload.take(200)}", e)
                }
            }
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true) return
                installBridge()
            }
        }, browser.cefBrowser)

        add(browser.component, BorderLayout.CENTER)
        browser.loadHTML(page())
    }

    /**
     * Exposes `window.__piSend` to the page. `JBCefJSQuery.inject` expands to the call that
     * reaches [query]'s handler, so the script has no knowledge of how the bridge works.
     */
    private fun installBridge() {
        val js = """
            window.__piSend = function (payload) { ${query.inject("payload")} };
            if (window.pi && window.pi.__bridgeReady) window.pi.__bridgeReady();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)

        synchronized(pending) {
            ready = true
            pending.forEach { dispatch(it) }
            pending.clear()
        }
    }

    /** Push one event into the page. Safe to call before the page is ready. */
    fun post(event: Map<String, Any?>) {
        val json = gson.toJson(event)
        synchronized(pending) {
            if (!ready) {
                pending.add(json)
                return
            }
        }
        dispatch(json)
    }

    private fun dispatch(json: String) {
        if (disposed) return
        // JSON is a subset of JS object syntax, so the payload can be embedded as a literal.
        browser.cefBrowser.executeJavaScript(
            "window.pi && window.pi.on($json);",
            browser.cefBrowser.url,
            0,
        )
    }

    private fun page(): String {
        val html = resource("/web/$page.html")
        val css = resource("/web/app.css")
        val js = resource("/web/$page.js")
        return html
            .replace(
                """<link rel="stylesheet" href="app.css">""",
                "<style>\n$css\n</style>",
            )
            .replace(
                """<script src="$page.js"></script>""",
                "<script>\n$js\n</script>",
            )
            // Inlining makes an external-source policy wrong; the document still reaches no
            // network origin at all, which is what the policy is there to guarantee.
            .replace(
                """default-src 'none'; style-src 'unsafe-inline' 'self'; script-src 'self'; img-src data:;""",
                """default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:;""",
            )
    }

    private fun resource(path: String): String =
        PiWebView::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("missing bundled resource $path")

    override fun dispose() {
        disposed = true
        // `query` and `browser` are both registered with the Disposer; nothing else to unwind.
    }

    companion object {
        /** JCEF is absent from some IDE builds and can be switched off by the user. */
        fun isAvailable(): Boolean = try {
            JBCefApp.isSupported()
        } catch (e: Throwable) {
            false
        }
    }
}
