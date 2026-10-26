package dev.veil.browser

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * WireGuard tunnel using the official wireguard-android library.
 *
 * The config is rewritten with `IncludedApplications = <this app>`, so ONLY Veil's traffic goes
 * through the VPN — the rest of the phone keeps its normal connection. Android still shows the
 * standard key icon because it is a real VpnService.
 *
 * Kill switch: while the VPN is enabled but not confirmed up, the browser blocks every request
 * (see MainActivity's WebViewClient), so nothing loads over your real connection.
 */
object VpnManager {
    enum class Status { OFF, CONNECTING, ON, ERROR }
    data class State(val status: Status, val detail: String = "", val ip: String = "")

    private val _state = MutableStateFlow(State(Status.OFF))
    val state: StateFlow<State> = _state

    @Volatile var enabled = false
        private set

    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "veil"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && enabled && _state.value.status == Status.ON) {
                _state.value = State(Status.ERROR, "Tunnel stopped — traffic is blocked")
            }
        }
    }

    fun shouldBlockAll() = enabled && _state.value.status != Status.ON

    fun init(ctx: Context) { enabled = prefs(ctx).getBoolean("vpn", false) }

    private fun configFile(ctx: Context) = File(ctx.filesDir, "wg0.conf")
    fun hasConfig(ctx: Context) = configFile(ctx).exists()

    /** Validates and stores a wg-quick style config. Throws with a readable message if invalid. */
    fun saveConfig(ctx: Context, text: String) {
        try { Config.parse(text.byteInputStream()) }
        catch (e: Exception) { throw IllegalArgumentException("That doesn't look like a valid WireGuard config (${e.message})") }
        configFile(ctx).writeText(text)
    }

    /** Endpoint shown in the UI, e.g. "203.0.113.5:51820". */
    fun endpoint(ctx: Context): String? = runCatching {
        Config.parse(configFile(ctx).inputStream()).peers.firstOrNull()?.endpoint?.orElse(null)?.toString()
    }.getOrNull()

    private fun restrictToApp(text: String, pkg: String): String {
        val out = StringBuilder()
        text.lineSequence().forEach { line ->
            val key = line.substringBefore('=').trim().lowercase()
            if (key == "includedapplications" || key == "excludedapplications") return@forEach
            out.appendLine(line)
            if (line.trim().equals("[Interface]", ignoreCase = true)) out.appendLine("IncludedApplications = $pkg")
        }
        return out.toString()
    }

    private fun backend(ctx: Context) = backend ?: GoBackend(ctx.applicationContext).also { backend = it }

    /** Caller must have obtained VpnService.prepare() consent first. */
    suspend fun connect(ctx: Context) = withContext(Dispatchers.IO) {
        setEnabled(ctx, true)
        _state.value = State(Status.CONNECTING)
        try {
            if (!hasConfig(ctx)) error("Import a WireGuard config first")
            val text = restrictToApp(configFile(ctx).readText(), ctx.packageName)
            backend(ctx).setState(tunnel, Tunnel.State.UP, Config.parse(text.byteInputStream()))
            val ip = fetchIp() ?: error("Tunnel is up but nothing gets through — check the server and config")
            _state.value = State(Status.ON, ip = ip)
        } catch (e: Exception) {
            _state.value = State(Status.ERROR, e.message ?: e.javaClass.simpleName)
        }
        Unit
    }

    suspend fun disconnect(ctx: Context) = withContext(Dispatchers.IO) {
        setEnabled(ctx, false)
        runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
        _state.value = State(Status.OFF, ip = fetchIp() ?: "")
        Unit
    }

    suspend fun refreshIp() = withContext(Dispatchers.IO) {
        val s = _state.value
        if (s.status == Status.ON || s.status == Status.OFF) _state.value = s.copy(ip = fetchIp() ?: "")
        Unit
    }

    private fun fetchIp(): String? = runCatching {
        val c = URL("https://api.ipify.org").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.inputStream.bufferedReader().use { it.readText().trim() }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        prefs(ctx).edit().putBoolean("vpn", on).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("veil", Context.MODE_PRIVATE)
}
