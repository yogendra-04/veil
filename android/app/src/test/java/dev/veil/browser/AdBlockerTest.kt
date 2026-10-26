package dev.veil.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs during the CI build against the real bundled filter lists. */
class AdBlockerTest {
    private val block = HashSet<String>(); private val third = HashSet<String>(); private val allow = HashSet<String>()
    private val generic = LinkedHashSet<String>(); private val site = HashMap<String, MutableList<String>>()

    private fun parseAll() {
        val dir = File("src/main/assets/filters")
        val files = dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
        assertTrue("filter lists were not bundled into ${dir.absolutePath}", files.size >= 3)
        files.forEach { AdBlocker.parse(it.readText(), block, third, allow, generic, site) }
        println("Veil rules: block=${block.size} thirdParty=${third.size} allow=${allow.size} genericCss=${generic.size} siteRules=${site.size}")
    }

    @Test fun realListsBlockCommonAdNetworks() {
        parseAll()
        assertTrue("too few blocked domains: ${block.size}", block.size > 50_000)
        assertTrue("too few hiding rules: ${generic.size}", generic.size > 1_000)
        listOf("doubleclick.net", "googlesyndication.com", "adnxs.com", "taboola.com").forEach {
            assertTrue("$it should be blocked", it in block || it in third)
        }
        listOf("google.com", "youtube.com", "github.com", "wikipedia.org").forEach {
            assertFalse("$it must NOT be blocked outright", it in block)
        }
    }

    @Test fun parsesRuleSyntax() {
        val b = HashSet<String>(); val t = HashSet<String>(); val a = HashSet<String>()
        val g = LinkedHashSet<String>(); val s = HashMap<String, MutableList<String>>()
        AdBlocker.parse(
            """
            ! comment
            ||ads.example.com^
            ||track.example.org^${'$'}third-party
            @@||good.example.com^
            ||example.net/ads/banner.js
            ||cdn.example.io^${'$'}domain=foo.com
            0.0.0.0 tracker.example.biz
            ##.ad-banner
            news.example.in##.sponsored
            ~skip.com##.x
            """.trimIndent(), b, t, a, g, s,
        )
        assertEquals(setOf("ads.example.com", "tracker.example.biz"), b)
        assertEquals(setOf("track.example.org"), t)
        assertEquals(setOf("good.example.com"), a)
        assertEquals(listOf(".ad-banner"), g.toList())
        assertEquals(listOf(".sponsored"), s["news.example.in"])
    }

    @Test fun registrableDomain() {
        assertEquals("example.com", AdBlocker.siteOf("cdn.ads.example.com"))
        assertEquals("news.co.in", AdBlocker.siteOf("static.news.co.in"))
    }
}
