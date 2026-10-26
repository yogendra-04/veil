package dev.veil.browser

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.veil.browser.VpnManager.Status
import dev.veil.browser.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private class Tab(val web: WebView) {
        var blocked = 0
        val title: String get() = web.title?.takeIf { it.isNotBlank() } ?: web.url ?: "New tab"
    }

    private lateinit var b: ActivityMainBinding
    private val tabs = mutableListOf<Tab>()
    private var current = -1
    private val tab get() = tabs.getOrNull(current)

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) startVpn() else toast("VPN permission is needed to connect")
    }
    private val pickConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importConfig(contentResolver.openInputStream(it)?.bufferedReader()?.readText().orEmpty()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Android 15+ draws edge-to-edge: pad for status bar, nav bar and keyboard ourselves.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsetsCompat.CONSUMED
        }

        VpnManager.init(this)
        AdBlocker.init(this, lifecycleScope)

        b.url.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                tab?.web?.loadUrl(toUrl(b.url.text.toString())); hideKeyboard(); true
            } else false
        }
        b.shield.setOnClickListener { toggleAdblock() }
        b.vpn.setOnClickListener { showVpnSheet() }
        b.tabs.setOnClickListener { showTabs() }
        b.menu.setOnClickListener { showMenu(it) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = tab?.web
                when {
                    w?.canGoBack() == true -> w.goBack()
                    tabs.size > 1 -> closeTab(current)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })

        lifecycleScope.launch { VpnManager.state.collect { renderVpn(it) } }

        // If the VPN was on last time, reconnect before loading anything (kill switch holds meanwhile).
        if (VpnManager.enabled) {
            if (VpnService.prepare(this) == null) startVpn() else vpnConsent.launch(VpnService.prepare(this))
        } else lifecycleScope.launch { VpnManager.refreshIp() }

        newTab(intent?.dataString ?: HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { newTab(it) }
    }

    // ---------------- tabs ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String?, transport: WebView.WebViewTransport? = null, resultMsg: Message? = null) {
        val web = WebView(this)
        val t = Tab(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false   // no popup spam
            builtInZoomControls = true; displayZoomControls = false
            loadWithOverviewMode = true; useWideViewPort = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false; allowContentAccess = false
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)  // block cross-site tracking cookies

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (VpnManager.shouldBlockAll()) {        // kill switch
                    return if (req.isForMainFrame) htmlPage(KILL_SWITCH_PAGE) else AdBlocker.emptyResponse()
                }
                if (!req.isForMainFrame && AdBlocker.isBlocked(req.url.host)) {   // ad / tracker
                    runOnUiThread { t.blocked++; if (t === tab) renderShield() }
                    return AdBlocker.emptyResponse()
                }
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.scheme == "http" || u.scheme == "https") return false
                // tel:, mailto:, intent:, market: → hand to the right app (only on a user tap)
                if (req.hasGesture()) runCatching {
                    val i = if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
                    } else Intent(Intent.ACTION_VIEW, u)
                    startActivity(i)
                }
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                t.blocked = 0
                if (t === tab) { renderUrl(); renderShield() }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (AdBlocker.enabled) view.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
                if (t === tab) renderUrl()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (t !== tab) return
                b.progress.visibility = if (p in 1..99) View.VISIBLE else View.INVISIBLE
                b.progress.setProgressCompat(p, true)
            }
            override fun onReceivedTitle(view: WebView, title: String?) { if (t === tab) renderTabs() }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, msg: Message): Boolean {
                if (!isUserGesture) return false                // drop popups the user didn't ask for
                val tr = msg.obj as WebView.WebViewTransport
                newTab(null, tr, msg)
                return true
            }
            override fun onCloseWindow(window: WebView) { tabs.indexOfFirst { it.web === window }.takeIf { it >= 0 }?.let(::closeTab) }
        }
        web.setDownloadListener { url, _, _, _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        tabs += t
        b.content.addView(web)
        switchTo(tabs.lastIndex)
        if (transport != null && resultMsg != null) { transport.webView = web; resultMsg.sendToTarget() }
        else web.loadUrl(toUrl(url ?: HOME))
    }

    private fun switchTo(i: Int) {
        current = i
        tabs.forEachIndexed { idx, t -> t.web.visibility = if (idx == i) View.VISIBLE else View.GONE }
        renderUrl(); renderShield(); renderTabs()
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        b.content.removeView(t.web)
        t.web.destroy()
        if (tabs.isEmpty()) newTab(HOME) else switchTo(i.coerceAtMost(tabs.lastIndex))
    }

    private fun showTabs() {
        val items = tabs.map { it.title }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Tabs")
            .setSingleChoiceItems(items, current) { d, which -> switchTo(which); d.dismiss() }
            .setPositiveButton("New tab") { _, _ -> newTab(HOME) }
            .setNeutralButton("Close this tab") { _, _ -> closeTab(current) }
            .show()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Reload"); menu.add("Forward"); menu.add("New tab")
            menu.add(if (AdBlocker.enabled) "Turn ad blocker off" else "Turn ad blocker on")
            menu.add("VPN settings"); menu.add("Clear browsing data")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Reload" -> tab?.web?.reload()
                    "Forward" -> tab?.web?.let { w -> if (w.canGoForward()) w.goForward() }
                    "New tab" -> newTab(HOME)
                    "VPN settings" -> showVpnSheet()
                    "Clear browsing data" -> clearData()
                    else -> toggleAdblock()
                }
                true
            }
        }.show()
    }

    private fun clearData() {
        tabs.forEach { it.web.clearCache(true); it.web.clearHistory() }
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        toast("Cookies, cache and site data cleared")
    }

    // ---------------- ad blocker ----------------

    private fun toggleAdblock() {
        AdBlocker.setEnabled(this, !AdBlocker.enabled)
        toast(if (AdBlocker.enabled) "Ad blocker on" else "Ad blocker off for all sites")
        tab?.web?.reload()
        renderShield()
    }

    // ---------------- VPN ----------------

    private fun showVpnSheet() {
        val s = VpnManager.state.value
        val ep = VpnManager.endpoint(this)
        val msg = buildString {
            append(statusLine(s)).append("\n\n")
            append(if (ep != null) "Server: $ep" else "No WireGuard config imported yet.")
            append("\n\nOnly Veil's traffic uses the VPN. If it drops, Veil blocks pages instead of using your real connection.")
        }
        val on = VpnManager.enabled
        MaterialAlertDialogBuilder(this)
            .setTitle("VPN")
            .setMessage(msg)
            .setPositiveButton(if (on) "Disconnect" else "Connect") { _, _ ->
                if (on) lifecycleScope.launch { VpnManager.disconnect(this@MainActivity); tab?.web?.reload() }
                else requestVpn()
            }
            .setNegativeButton("Import file") { _, _ -> pickConfig.launch(arrayOf("*/*")) }
            .setNeutralButton("Paste config") { _, _ -> pasteConfig() }
            .show()
    }

    private fun pasteConfig() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (!text.contains("[Interface]")) toast("Copy your WireGuard config text first, then tap Paste")
        else importConfig(text)
    }

    private fun importConfig(text: String) {
        try {
            VpnManager.saveConfig(this, text)
            toast("Config saved")
            requestVpn()
        } catch (e: Exception) { toast(e.message ?: "Invalid config") }
    }

    private fun requestVpn() {
        if (!VpnManager.hasConfig(this)) { toast("Import a WireGuard config first"); return }
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else startVpn()
    }

    private fun startVpn() = lifecycleScope.launch {
        VpnManager.connect(this@MainActivity)
        if (VpnManager.state.value.status == Status.ON) tab?.web?.reload()
    }

    // ---------------- rendering ----------------

    private fun renderUrl() {
        if (b.url.hasFocus()) return
        val u = tab?.web?.url.orEmpty()
        b.url.setText(if (u.startsWith("https://duckduckgo.com/?q=") || u.startsWith("data:")) "" else u)
    }
    private fun renderTabs() { b.tabs.text = tabs.size.toString() }
    private fun renderShield() {
        b.shield.text = if (AdBlocker.enabled) (tab?.blocked ?: 0).toString() else "off"
        b.shield.chipIconTint = tint(if (AdBlocker.enabled) R.color.good else R.color.idle)
    }
    private fun renderVpn(s: VpnManager.State) {
        b.vpn.text = when (s.status) { Status.ON -> "VPN"; Status.CONNECTING -> "…"; Status.ERROR -> "VPN!"; Status.OFF -> "VPN" }
        b.vpn.chipIconTint = tint(when (s.status) {
            Status.ON -> R.color.good; Status.CONNECTING -> R.color.warn; Status.ERROR -> R.color.bad; Status.OFF -> R.color.idle
        })
        if (s.status == Status.ERROR) toast(statusLine(s))
    }
    private fun statusLine(s: VpnManager.State) = when (s.status) {
        Status.ON -> "Connected — sites see ${s.ip}"
        Status.CONNECTING -> "Connecting…"
        Status.ERROR -> "Not connected — pages are blocked. ${s.detail}"
        Status.OFF -> "Off — sites see your real IP${if (s.ip.isNotEmpty()) " (${s.ip})" else ""}"
    }

    // ---------------- helpers ----------------

    private fun tint(color: Int) = ColorStateList.valueOf(ContextCompat.getColor(this, color))
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun hideKeyboard() {
        b.url.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.url.windowToken, 0)
    }
    private fun htmlPage(html: String) = WebResourceResponse("text/html", "utf-8", html.byteInputStream())

    override fun onDestroy() { tabs.forEach { it.web.destroy() }; super.onDestroy() }

    companion object {
        const val HOME = "https://duckduckgo.com"
        fun toUrl(input: String): String {
            val s = input.trim()
            return when {
                s.isEmpty() -> HOME
                Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s) -> s
                Regex("^[^\\s]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(s) -> "https://$s"
                else -> "https://duckduckgo.com/?q=" + Uri.encode(s)
            }
        }
        private const val KILL_SWITCH_PAGE = """<!doctype html><meta name=viewport content="width=device-width">
<body style="font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:90vh;text-align:center;padding:24px;color:#555">
<h2 style="color:#222">Blocked by Veil</h2>
<p>The VPN isn't connected, so this page was blocked to keep your real IP hidden.<br>Tap the VPN chip to reconnect.</p></body>"""
    }
}
