package dev.veil.browser

import android.content.Context
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Domain-level ad & tracker blocking, like Pi-hole / AdAway but only for this browser.
 * Uses the StevenBlack unified hosts list (~80k ad, tracking and malware domains),
 * cached on disk and refreshed weekly. A small bundled list works until the first download.
 */
object AdBlocker {
    private const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    @Volatile private var domains: Set<String> = emptySet()
    @Volatile var enabled = true
    val size get() = domains.size

    fun init(ctx: Context, scope: CoroutineScope) {
        enabled = prefs(ctx).getBoolean("adblock", true)
        scope.launch(Dispatchers.IO) {
            val cache = File(ctx.filesDir, "hosts.txt")
            domains = parse(
                if (cache.exists()) cache.readText()
                else ctx.assets.open("hosts_fallback.txt").bufferedReader().readText()
            )
            if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS) refresh(cache)
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        prefs(ctx).edit().putBoolean("adblock", on).apply()
    }

    private fun refresh(cache: File) {
        if (VpnManager.shouldBlockAll()) return // don't fetch outside the tunnel
        runCatching {
            val conn = URL(LIST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 30_000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = parse(text)
            if (parsed.size > 1000) { cache.writeText(text); domains = parsed }
        }
    }

    private fun parse(text: String): Set<String> {
        val out = HashSet<String>(100_000)
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("\\s+"))
            val host = (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
            if (host != "localhost" && host != "0.0.0.0" && host.contains('.')) out += host
        }
        return out
    }

    /** True if the host or any parent domain is on the list (ads.example.com → example.com). */
    fun isBlocked(host: String?): Boolean {
        if (!enabled || host.isNullOrEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in domains) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }

    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Hides the empty boxes left behind by blocked ads. */
    const val COSMETIC_JS = """
        (function(){
          if (document.getElementById('veil-cosmetic')) return;
          var s = document.createElement('style'); s.id = 'veil-cosmetic';
          s.textContent = 'ins.adsbygoogle,[id^="google_ads_"],[id^="div-gpt-ad"],iframe[src*="doubleclick.net"],' +
            '.ad-banner,.ad-container,.ad-slot,.adsbox,.advertisement,[data-ad-slot],[data-ad-unit],amp-ad,amp-embed' +
            '{display:none!important}';
          (document.head || document.documentElement).appendChild(s);
        })();
    """

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("veil", Context.MODE_PRIVATE)
}
