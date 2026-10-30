package tech.openera.stickerbridge.data

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HttpApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = HttpApi(server.url("/").toString(), "test")
    }

    @After fun tearDown() = server.shutdown()

    @Test fun guardOnlyAllowsTheApiHostOverHttps() {
        val guard = ApiGuard("https://api.sticker.openera.tech/".toHttpUrl())
        assertTrue(guard.isAllowed("https://api.sticker.openera.tech/v1/stickers".toHttpUrl()))
        assertFalse(guard.isAllowed("http://api.sticker.openera.tech/v1/stickers".toHttpUrl()))
        assertFalse(guard.isAllowed("https://evil.example/v1/stickers".toHttpUrl()))
        assertFalse(guard.isAllowed("https://sticker.openera.tech/v1".toHttpUrl()))
        assertFalse(guard.isAllowed("https://api.sticker.openera.tech:8443/v1".toHttpUrl()))
    }

    @Test fun serverPathsCannotPointElsewhere() {
        try {
            api.resolve("https://evil.example/steal")
            fail("absolute URL to another host must be refused")
        } catch (e: IOException) { /* expected */ }
        // A protocol-relative path is treated as a path on the API host, never as another host.
        assertEquals(server.url("/").host, api.resolve("//evil.example/steal").host)
        assertEquals(server.url("/v1/stickers/a/file"), api.resolve("/v1/stickers/a/file"))
    }

    @Test fun redirectsAreNeverFollowed() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/x"))
        try {
            api.download("t", "v1/stickers/a/file")
            fail("a redirect must fail")
        } catch (e: ApiException) {
            assertEquals(302, e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun linkFlowSendsInstallIdAndPollToken() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":"SB-4821","pollToken":"p1","expiresAt":99}"""))
        server.enqueue(MockResponse().setBody("""{"status":"linked","token":"tok","account":{"id":"7","username":"mia"},"extra":1}"""))
        val start = api.startLink("install-1")
        assertEquals("SB-4821", start.code)
        val st = api.linkStatus(start.code, start.pollToken)
        assertEquals("linked", st.status)
        assertEquals("mia", st.account!!.username)

        val r1 = server.takeRequest()
        assertEquals("POST", r1.method)
        assertEquals("/v1/link/start", r1.path)
        assertEquals("""{"installId":"install-1"}""", r1.body.readUtf8())
        val r2 = server.takeRequest()
        assertEquals("/v1/link/status?code=SB-4821", r2.path)
        assertEquals("p1", r2.getHeader("X-Poll-Token"))
        assertEquals("test", r2.getHeader("User-Agent"))
    }

    @Test fun stickersUseBearerTokenAndEncodedCursor() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"a","file":"v1/stickers/a/file","animated":true}],"cursor":"c 2"}"""))
        val page = api.stickers("tok", "c/1&x")
        assertEquals(1, page.items.size)
        assertTrue(page.items[0].animated)
        val r = server.takeRequest()
        assertEquals("Bearer tok", r.getHeader("Authorization"))
        assertEquals("/v1/stickers?since=c%2F1%26x", r.path)
    }

    @Test fun httpErrorsCarryTheStatusCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            api.stickers("bad", null); fail()
        } catch (e: ApiException) {
            assertEquals(401, e.code)
        }
    }

    @Test fun noReleasePublishedMeansNoUpdate() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(api.latestApp())
    }

    @Test fun streamsUpdateDownloadWithProgress() = runTest {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val f = java.io.File.createTempFile("upd", ".apk")
        var last = 0L
        api.downloadTo("v1/app/apk/2", f) { done, _ -> last = done }
        assertEquals(bytes.size.toLong(), last)
        assertTrue(bytes.contentEquals(f.readBytes()))
        f.delete()
    }
}
