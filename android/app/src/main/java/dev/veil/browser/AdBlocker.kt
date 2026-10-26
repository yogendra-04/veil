package dev.veil.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ad & tracker blocker built from the same public filter lists uBlock Origin uses.
 *
 *  Network:  requests to ad/tracker domains are dropped ("||ads.example.com^" rules + hosts lists),
 *            honouring "$third-party" and "@@" exception rules.
 *  Cosmetic: EasyList element-hiding rules ("##.ad-banner", "site.com##.promo") are injected as CSS
 *            at document start, so ad boxes never appear.
 *  YouTube:  a small script strips ad slots from the player data.
 *
 * Lists ship inside the APK (downloaded at build time) and refresh from the internet weekly.
 */
object AdBlocker {
    /** name → URL. The CI build downloads these into assets/filters/<name>.txt. */
    val SOURCES = linkedMapOf(
        "easylist" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy" to "https://easylist.to/easylist/easyprivacy.txt",
        "indianlist" to "https://easylist-downloads.adblockplus.org/indianlist.txt",
        "adguard-dns" to "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
        "stevenblack" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
    )
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private class Rules(
        val block: Set<String>,            // block on every site
        val blockThirdParty: Set<String>,  // block only when loaded by another site
        val allow: Set<String>,            // @@ exceptions
        val genericCss: String,            // ##selector rules for every site
        val siteSelectors: Map<String, List<String>>, // site.com##selector
    )

    @Volatile private var rules = Rules(emptySet(), emptySet(), emptySet(), "", emptyMap())
    private val ready = CountDownLatch(1)
    @Volatile var enabled = true
    /** Bumped whenever the rules change, so tabs know to re-install their CSS. */
    @Volatile var version = 0
        private set
    val size get() = rules.block.size + rules.blockThirdParty.size

    fun init(ctx: Context, scope: CoroutineScope) {
        enabled = prefs(ctx).getBoolean("adblock", true)
        scope.launch(Dispatchers.IO) {
            rules = build(ctx)
            version++
            ready.countDown()
            refreshIfStale(ctx)
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        prefs(ctx).edit().putBoolean("adblock", on).apply()
    }

    // ---------------- loading ----------------

    private fun dir(ctx: Context) = File(ctx.filesDir, "filters").apply { mkdirs() }

    /** Prefer a downloaded (newer) copy, else the copy bundled in the APK, else the tiny fallback. */
    private fun readList(ctx: Context, name: String): String? {
        val f = File(dir(ctx), "$name.txt")
        if (f.length() > 0) return runCatching { f.readText() }.getOrNull()
        return runCatching { ctx.assets.open("filters/$name.txt").bufferedReader().use { it.readText() } }.getOrNull()
    }

    private fun build(ctx: Context): Rules {
        val block = HashSet<String>(200_000)
        val third = HashSet<String>(20_000)
        val allow = HashSet<String>(5_000)
        val generic = LinkedHashSet<String>(20_000)
        val site = HashMap<String, MutableList<String>>(20_000)
        var any = false
        for (name in SOURCES.keys) {
            val text = readList(ctx, name) ?: continue
            any = true
            parse(text, block, third, allow, generic, site)
        }
        if (!any) runCatching {
            parse(ctx.assets.open("hosts_fallback.txt").bufferedReader().readText(), block, third, allow, generic, site)
        }
        // Group selectors into chunks: one invalid selector would otherwise void the whole CSS rule.
        val css = generic.chunked(400).joinToString("\n") { it.joinToString(",") + "{display:none!important}" }
        return Rules(block, third, allow, css, site)
    }

    private val HOST = Regex("^[a-z0-9.-]+\\.[a-z0-9-]+$")
    /** Options that don't stop us from treating a rule as "block this whole domain". */
    private val TYPE_OPTS = setOf("script", "image", "stylesheet", "xmlhttprequest", "subdocument", "media",
        "font", "object", "ping", "websocket", "other", "popup", "all", "important", "1p", "first-party", "doc", "document")

    internal fun parse(
        text: String, block: MutableSet<String>, third: MutableSet<String>, allow: MutableSet<String>,
        generic: MutableSet<String>, site: MutableMap<String, MutableList<String>>,
    ) {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '[' || line[0] == '#' && !line.startsWith("##")) continue

            // ---- cosmetic: "##sel" or "a.com,b.com##sel" (skip exceptions/extended syntax) ----
            val hash = line.indexOf("##")
            if (hash >= 0) {
                if (line.contains("#@#") || line.contains("#?#") || line.contains("#$#") || line.contains("#%#")) continue
                val sel = line.substring(hash + 2)
                if (sel.isEmpty() || sel.contains(":-abp-") || sel.contains("+js(") || sel.contains(":style(") ||
                    sel.contains(":xpath(") || sel.contains(":matches-") || sel.contains(":upward(") ||
                    sel.contains(":remove(") || sel.contains("{") || sel.contains("}")) continue
                val domains = line.substring(0, hash)
                if (domains.isEmpty()) generic += sel
                else domains.split(',').forEach { d ->
                    val dd = d.trim().lowercase()
                    if (dd.isNotEmpty() && !dd.startsWith("~") && !dd.contains('*')) site.getOrPut(dd) { mutableListOf() } += sel
                }
                continue
            }
            if (line.contains("#@#") || line.contains("#?#")) continue

            // ---- hosts format: "0.0.0.0 ads.example.com" ----
            if (line[0].isDigit() && (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 "))) {
                val h = line.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').lowercase()
                if (h != "0.0.0.0" && h != "localhost" && HOST.matches(h)) block += h
                continue
            }

            // ---- network: "||host^", "||host^$third-party", "@@||host^" ----
            val exception = line.startsWith("@@")
            val body = if (exception) line.substring(2) else line
            if (!body.startsWith("||")) continue
            val opts = body.substringAfter('$', "")
            val pattern = body.substringBefore('$').removePrefix("||")
            val host = when {
                pattern.endsWith("^") -> pattern.dropLast(1)
                pattern.endsWith("^|") -> pattern.dropLast(2)
                else -> continue // has a path → URL rule, not a domain rule
            }.lowercase()
            if (!HOST.matches(host)) continue
            var thirdOnly = false
            var usable = true
            if (opts.isNotEmpty()) for (o in opts.split(',')) {
                when {
                    o == "third-party" || o == "3p" -> thirdOnly = true
                    o.startsWith("~") -> {}                       // "not this type" — still mostly ads
                    o in TYPE_OPTS -> {}
                    else -> { usable = false; break }             // domain=, csp=, redirect=, denyallow=…
                }
            }
            if (!usable) continue
            when {
                exception -> allow += host
                thirdOnly -> third += host
                else -> block += host
            }
        }
    }

    private fun refreshIfStale(ctx: Context) {
        val d = dir(ctx)
        val stale = SOURCES.keys.any { val f = File(d, "$it.txt"); !f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS }
        val bundledAge = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val apkFresh = System.currentTimeMillis() - bundledAge < MAX_AGE_MS
        if (!stale || (apkFresh && SOURCES.keys.none { File(d, "$it.txt").exists() })) return
        if (VpnManager.shouldBlockAll()) return // never fetch outside the tunnel
        var changed = false
        for ((name, url) in SOURCES) runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            if (body.length > 10_000) { File(d, "$name.txt").writeText(body); changed = true }
        }
        if (changed) { rules = build(ctx); version++ }
    }

    // ---------------- matching ----------------

    private val TWO_PART_TLDS = setOf("co", "com", "net", "org", "gov", "ac", "edu", "gen", "firm", "ind", "nic", "res")

    /** Rough "registrable domain": ads.news.co.in → news.co.in, cdn.example.com → example.com */
    fun siteOf(host: String): String {
        val p = host.split('.')
        if (p.size <= 2) return host
        val n = if (p.last().length == 2 && p[p.size - 2] in TWO_PART_TLDS) 3 else 2
        return p.takeLast(n).joinToString(".")
    }

    private inline fun anyParent(host: String, test: (String) -> Boolean): Boolean {
        var h = host
        while (true) {
            if (test(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }

    /** Should a sub-resource request to [url], made by a page on [pageHost], be blocked? */
    fun shouldBlock(url: Uri, pageHost: String?): Boolean {
        if (!enabled) return false
        val host = url.host?.lowercase() ?: return false
        if (!ready.await(3, TimeUnit.SECONDS)) return false // first launch: give the lists a moment to load
        val r = rules
        if (anyParent(host) { it in r.allow }) return false
        if (anyParent(host) { it in r.block }) return true
        if (pageHost != null && siteOf(host) != siteOf(pageHost) && anyParent(host) { it in r.blockThirdParty }) return true
        return false
    }

    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    // ---------------- page scripts ----------------

    private fun jsString(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> {}
            '<' -> append("\\u003c"); ' ' -> append("\\u2028"); ' ' -> append("\\u2029")
            else -> append(c)
        }
        append('"')
    }

    /** Runs at document start in every frame: generic hiding CSS + YouTube ad stripping. */
    fun documentStartScript(): String = """
(function(){
  if (window.__veil === $version) return; window.__veil = $version;
  var css = ${jsString(rules.genericCss)};
  function add(){ var s=document.getElementById('veil-generic')||document.createElement('style'); s.id='veil-generic'; s.textContent=css;
    (document.head||document.documentElement).appendChild(s); }
  if (document.documentElement) add(); else document.addEventListener('readystatechange', add, {once:true});
  $YOUTUBE_JS
})();
"""

    /** Site-specific hiding CSS for the current page (e.g. rules written for timesofindia.com). */
    fun siteScript(host: String?): String? {
        if (!enabled || host == null) return null
        val sels = ArrayList<String>()
        anyParent(host.lowercase()) { rules.siteSelectors[it]?.let(sels::addAll); false }
        if (sels.isEmpty()) return null
        val css = sels.chunked(200).joinToString("\n") { it.joinToString(",") + "{display:none!important}" }
        return """(function(){var s=document.getElementById('veil-site')||document.createElement('style');s.id='veil-site';
s.textContent=${jsString(css)};(document.head||document.documentElement).appendChild(s);})();"""
    }

    /** Removes ad slots from YouTube's player data before the player reads it (same idea as uBlock's json-prune). */
    private const val YOUTUBE_JS = """
  if (/(^|\.)youtube\.com$/.test(location.hostname) && !window.__veilYT) { window.__veilYT = 1;
    var KEYS = ['adPlacements','adSlots','playerAds','adBreakHeartbeatParams'];
    function prune(o){ try { if (o && typeof o === 'object') {
      KEYS.forEach(function(k){ if (k in o) delete o[k]; });
      if (o.playerResponse) prune(o.playerResponse);
      if (Array.isArray(o)) o.forEach(prune);
    } } catch(e){} return o; }
    var jp = JSON.parse; JSON.parse = function(){ return prune(jp.apply(this, arguments)); };
    var rj = Response.prototype.json; Response.prototype.json = function(){ return rj.apply(this, arguments).then(prune); };
    var ipr; try { Object.defineProperty(window, 'ytInitialPlayerResponse', { configurable: true,
      get: function(){ return ipr; }, set: function(v){ ipr = prune(v); } }); } catch(e){}
    // Skip any video ad that still slips through, and hide promo slots.
    setInterval(function(){
      var p = document.querySelector('.ad-showing video');
      if (p && isFinite(p.duration)) { p.muted = true; p.currentTime = p.duration; }
      var skip = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');
      if (skip) skip.click();
    }, 500);
    var ys = document.createElement('style');
    ys.textContent = 'ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ad-slot-renderer,ytd-ad-slot-renderer,ytm-promoted-video-renderer,ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,.ytp-ad-overlay-container,ytm-item-section-renderer:has(ad-slot-renderer){display:none!important}';
    document.addEventListener('DOMContentLoaded', function(){ document.head.appendChild(ys); });
  }
"""

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("veil", Context.MODE_PRIVATE)
}
