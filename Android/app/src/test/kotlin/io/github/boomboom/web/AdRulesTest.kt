package io.github.boomboom.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The iPhone app's own rules, read the way the hidden tab reads them. */
class AdRulesTest {
    private val rules = AdRules.parse(File("../../Packages/BoomEngine/Sources/BoomPlayer/Resources/ad-rules.json").readText())

    @Test
    fun neverRefusesTheMediaRequests() {
        val media = listOf(
            "https://rr5---sn-ci5gup-cvhe.googlevideo.com/videoplayback?expire=1&ei=x&ip=1&id=o-A&itag=251&source=youtube&ctier=A&mime=audio%2Fwebm",
            "https://rr1---sn-abc.googlevideo.com/videoplayback?ctier=SH&itag=140&pcm2=yes&sabr=1",
            "https://redirector.googlevideo.com/videoplayback?itag=251&ctier=A",
        )
        for (url in media) {
            assertTrue(AdRules.isMedia(url))
            assertFalse(url, rules.refuses(url))
        }
    }

    @Test
    fun refusesAdAndTrackingHosts() {
        val refused = listOf(
            "https://googleads.g.doubleclick.net/pagead/id",
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://www.google-analytics.com/analytics.js",
            "https://www.youtube.com/pagead/viewthroughconversion/1/",
            "https://music.youtube.com/api/stats/ads?ver=2",
            "https://www.youtube.com/ptracking?pltype=content",
            "https://music.youtube.com/youtubei/v1/player/ad_break?prettyPrint=false",
            "https://s0.2mdn.net/ads/x.mp4",
        )
        for (url in refused) assertTrue(url, rules.refuses(url))
        assertEquals(refused.size.toLong(), rules.refused)
    }

    @Test
    fun letsTheSiteItselfThrough() {
        val allowed = listOf(
            "https://music.youtube.com/",
            "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            "https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
            "https://www.youtube.com/s/player/abc/player_ias.vflset/en_US/base.js",
            "https://accounts.google.com/ServiceLogin",
            "https://i.ytimg.com/vi/x/hqdefault.jpg",
            "https://music.youtube.com/api/stats/playback?ns=yt",
        )
        for (url in allowed) assertFalse(url, rules.refuses(url))
    }

    @Test
    fun aRuleThatWouldRefuseTheMediaIsLeftOut() {
        val text = """[{"trigger":{"url-filter":"ctier=A"},"action":{"type":"block"}},
            {"trigger":{"url-filter":"^https?://([a-z0-9-]+\\.)*doubleclick\\.net/"},"action":{"type":"block"}}]"""
        val parsed = AdRules.parse(text)
        assertFalse(parsed.refuses("https://rr5---sn-x.googlevideo.com/videoplayback?ctier=A"))
        assertFalse(parsed.refuses("https://example.com/?ctier=A"))
        assertTrue(parsed.refuses("https://ad.doubleclick.net/x"))
    }
}
