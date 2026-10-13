package ro.davidmontei.loom.romm

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class RommUrlsTest {

    @Test fun typedSchemeIsKeptAndTrailingSlashAdded() {
        assertThat(RommUrls.candidates("https://romm.example.org").map { it.toString() })
            .containsExactly("https://romm.example.org/")
    }

    @Test fun publicHostTriesHttpsFirst() {
        assertThat(RommUrls.candidates("romm.example.org").map { it.scheme }).containsExactly("https", "http").inOrder()
    }

    @Test fun lanAndTailnetHostsTryHttpFirst() {
        for (host in listOf("192.168.1.20:8080", "100.101.102.103", "nas", "romm.local", "romm.tail1234.ts.net")) {
            assertThat(RommUrls.candidates(host).first().scheme).isEqualTo("http")
        }
    }

    @Test fun subPathIsPreservedAndQueryDropped() {
        assertThat(RommUrls.normalize("https://home.example.org/romm?x=1#frag").toString())
            .isEqualTo("https://home.example.org/romm/")
        val base = RommUrls.normalize("https://home.example.org/romm")!!
        assertThat(RommUrls.resolve(base, RommUrls.romFileContentPath(42)).toString())
            .isEqualTo("https://home.example.org/romm/api/roms/42/files/content/file")
    }

    @Test fun garbageIsRejected() {
        assertThat(RommUrls.candidates("   ")).isEmpty()
        assertThat(RommUrls.normalize("ftp://x")).isNull()
    }

    @Test fun privateHostDetection() {
        assertThat(RommUrls.isPrivateHost("10.0.0.2")).isTrue()
        assertThat(RommUrls.isPrivateHost("172.20.1.1")).isTrue()
        assertThat(RommUrls.isPrivateHost("100.64.0.1")).isTrue()
        assertThat(RommUrls.isPrivateHost("172.32.0.1")).isFalse()
        assertThat(RommUrls.isPrivateHost("romm.example.com")).isFalse()
    }

    @Test fun coverUrlEncodesRawTimestampAndUsesOrigin() {
        val base = "https://home.example.org/romm/".toHttpUrl()
        val url = RommUrls.coverUrl(base, "/assets/romm/resources/roms/1/7/cover/small.png?ts=2026-10-10 15:16:10.123+00:00")!!
        assertThat(url.encodedPath).isEqualTo("/assets/romm/resources/roms/1/7/cover/small.png")
        assertThat(url.queryParameter("ts")).isEqualTo("2026-10-10 15:16:10.123+00:00")
        // The raw space and '+' must be percent-encoded, or the '+' would decode as a space.
        assertThat(url.encodedQuery).doesNotContain(" ")
        assertThat(url.encodedQuery).contains("%2B00")
        assertThat(url.host).isEqualTo("home.example.org")
    }

    @Test fun missingCoverIsNull() {
        val base = "https://romm.example.org/".toHttpUrl()
        assertThat(RommUrls.coverUrl(base, "")).isNull()
        assertThat(RommUrls.coverUrl(base, null)).isNull()
    }

    @Test fun verificationUrlIsAbsolute() {
        val base = "https://home.example.org/romm/".toHttpUrl()
        assertThat(RommUrls.verificationUrl(base, "/pair/device?user_code=ABCD2345").toString())
            .isEqualTo("https://home.example.org/romm/pair/device?user_code=ABCD2345")
    }

    @Test fun sameServerComparesHostAndPort() {
        assertThat(RommUrls.sameServer("https://A.example.org/x/".toHttpUrl(), "https://a.example.org/".toHttpUrl())).isTrue()
        assertThat(RommUrls.sameServer("https://a.example.org/".toHttpUrl(), "http://a.example.org/".toHttpUrl())).isFalse()
    }
}
