package com.mirrorapps.kit.feedback.media

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The media upload against a fake backend and a fake Storage session. */
class MediaClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun client(code: String? = "test-code") = HttpMediaClient(
        sharedClient = OkHttpClient(),
        base = server.url("").toString().trimEnd('/'),
        credential = { code },
        backoffMs = 10,
    )

    private val data = ByteArray(10) { it.toByte() }

    private fun source(kind: MediaKind = MediaKind.AUDIO, bytes: ByteArray = data) = MediaSource(
        kind = kind,
        contentType = "audio/mp4",
        bytes = bytes.size.toLong(),
        recordedAt = 1_763_500_000_000L,
        durationMs = 4_000L,
        open = { offset -> ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()) },
    )

    private fun started() = MockResponse().setBody(
        JSONObject()
            .put("mediaId", "m_20261118_0123456789abcdef")
            .put("uploadUrl", server.url("/upload/session-1").toString())
            .toString(),
    )

    @Test fun `start, put, done`() = runBlocking {
        server.enqueue(started())
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setBody("""{"status":"transcribing"}"""))
        val progress = ArrayList<Float>()

        val r = client().upload(source(), "mirrormov") { progress += it }

        assertEquals(MediaUpload.Done("m_20261118_0123456789abcdef", "transcribing"), r)
        val start = server.takeRequest()
        assertEquals("/v1/media/start", start.path)
        assertEquals("Bearer test-code", start.getHeader("Authorization"))
        val body = JSONObject(start.body.readUtf8())
        assertEquals("audio", body.getString("kind"))
        assertEquals("audio/mp4", body.getString("contentType"))
        assertEquals(10, body.getInt("bytes"))
        assertEquals(1_763_500_000_000L, body.getLong("recordedAt"))
        assertEquals(4_000L, body.getLong("durationMs"))
        assertEquals("mirrormov", body.getString("app"))

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/upload/session-1", put.path)
        assertEquals("bytes 0-9/10", put.getHeader("Content-Range"))
        assertNull("the upload URL is the permission", put.getHeader("Authorization"))
        assertTrue(put.body.readByteArray().contentEquals(data))

        val done = server.takeRequest()
        assertEquals("/v1/media/m_20261118_0123456789abcdef/done", done.path)
        assertEquals("POST", done.method)
        assertEquals(1f, progress.last())
    }

    @Test fun `a dropped piece resumes from what Storage has`() = runBlocking {
        server.enqueue(started())
        server.enqueue(MockResponse().setResponseCode(503))
        // Asked how much it has: the first 4 bytes.
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-3"))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setBody("""{"status":"ready"}"""))

        val r = client().upload(source(), "mirrormap") {}

        assertEquals(MediaUpload.Done("m_20261118_0123456789abcdef", "ready"), r)
        server.takeRequest() // start
        assertEquals("bytes 0-9/10", server.takeRequest().getHeader("Content-Range"))
        assertEquals("bytes */10", server.takeRequest().getHeader("Content-Range"))
        val resumed = server.takeRequest()
        assertEquals("bytes 4-9/10", resumed.getHeader("Content-Range"))
        assertTrue(resumed.body.readByteArray().contentEquals(data.copyOfRange(4, 10)))
    }

    @Test fun `daily limit is a plain message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"daily_limit"}"""))
        val r = client().upload(source(), "mirrormap") {}
        assertTrue(r is MediaUpload.Failed)
        assertTrue((r as MediaUpload.Failed).message.contains("limit"))
    }

    @Test fun `no credential, no request`() = runBlocking {
        val r = client(code = null).upload(source(), "mirrormap") {}
        assertTrue(r is MediaUpload.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test fun `too big for its kind fails before anything is sent`() = runBlocking {
        val huge = MediaSource(MediaKind.IMAGE, "image/png", MediaKind.IMAGE.maxBytes + 1, 1_763_500_000_000L, 0) {
            ByteArrayInputStream(ByteArray(0))
        }
        val r = client().upload(huge, "mirrormap") {}
        assertTrue(r is MediaUpload.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test fun `state reads the transcript`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ready","transcript":"it froze on the hill","kind":"audio"}"""))
        val s = client().state("m_20261118_0123456789abcdef")
        assertEquals(MediaState("ready", "it froze on the hill"), s)
        assertEquals("/v1/media/m_20261118_0123456789abcdef", server.takeRequest().path)
    }

    @Test fun `range header gives the next offset`() {
        assertEquals(0L, ResumableRange.nextOffset(null))
        assertEquals(0L, ResumableRange.nextOffset(""))
        assertEquals(262_144L, ResumableRange.nextOffset("bytes=0-262143"))
    }

    @Test fun `video dates parse as UTC and reject unset clocks`() {
        assertEquals(1_763_476_212_000L, MediaReader.parseVideoDate("20251118T143012.000Z"))
        assertEquals(1_763_476_212_000L, MediaReader.parseVideoDate("20251118T143012Z"))
        assertNull(MediaReader.parseVideoDate("19040101T000000.000Z"))
        assertNull(MediaReader.parseVideoDate(""))
        assertNull(MediaReader.parseVideoDate(null))
        assertNull(MediaReader.parseVideoDate("garbage"))
    }
}
