package com.mirrorapps.kit.feedback

import java.util.zip.GZIPInputStream
import kotlinx.coroutines.flow.toList
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

class FeedbackTransportTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun transport(code: String? = "test-code") = HttpFeedbackTransport(
        sharedClient = OkHttpClient(),
        messageUrl = server.url("/v1/feedback/message").toString(),
        consentUrl = server.url("/v1/feedback/consent").toString(),
        installId = "install-1",
        accessCode = { code },
    )

    private fun sse(vararg events: Pair<String, String>) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" })

    // ---- parser ----

    @Test fun `parser joins multi-line data and skips comments`() {
        val p = SseParser()
        val lines = listOf(": keepalive", "event: delta", "data: line one", "data: line two", "")
        val events = lines.mapNotNull { p.feed(it) }
        assertEquals(listOf(SseParser.Event("delta", "line one\nline two")), events)
    }

    @Test fun `parser defaults the event name to message`() {
        val p = SseParser()
        p.feed("data: x")
        assertEquals(SseParser.Event("message", "x"), p.feed(""))
    }

    @Test fun `blank lines without data produce nothing`() {
        assertNull(SseParser().feed(""))
    }

    // ---- events ----

    @Test fun `events map from their json`() {
        fun ev(name: String, data: String) = FeedbackEvent.from(SseParser.Event(name, data))
        assertEquals(FeedbackEvent.Session("s1"), ev("session", """{"sessionId":"s1"}"""))
        assertEquals(FeedbackEvent.Delta("Hi "), ev("delta", """{"text":"Hi "}"""))
        assertEquals(FeedbackEvent.OfferPreview, ev("offer_preview", "{}"))
        assertEquals(FeedbackEvent.Outcome("filed", null), ev("outcome", """{"kind":"filed","issueUrl":"javascript:x"}"""))
        assertEquals(FeedbackEvent.Done, ev("done", "{}"))
        assertNull(ev("something_new", "{}"))
    }

    @Test fun `consent reason is capped`() {
        val long = "x".repeat(500)
        val e = FeedbackEvent.from(SseParser.Event("consent_request", JSONObject().put("reason", long).toString()))
        assertEquals(FeedbackEvent.MAX_REASON_CHARS, (e as FeedbackEvent.ConsentRequest).reason.length)
    }

    // ---- HTTP ----

    @Test fun `message posts gzipped json with headers and streams the reply`() = runBlocking {
        server.enqueue(sse(
            "session" to """{"sessionId":"s1"}""",
            "delta" to """{"text":"Hello "}""",
            "delta" to """{"text":"there"}""",
            "done" to "{}",
        ))
        val bundle = JSONObject().put("schema", 1)
        val events = transport().send(FeedbackMessage("mirrormap", null, "it broke", bundle)).toList()

        assertEquals(
            listOf(
                FeedbackEvent.Session("s1"),
                FeedbackEvent.Delta("Hello "),
                FeedbackEvent.Delta("there"),
                FeedbackEvent.Done,
            ),
            events,
        )
        val req = server.takeRequest()
        assertEquals("/v1/feedback/message", req.path)
        assertEquals("gzip", req.getHeader("Content-Encoding"))
        assertEquals("install-1", req.getHeader("X-Install-Id"))
        assertEquals("Bearer test-code", req.getHeader("Authorization"))
        assertEquals("text/event-stream", req.getHeader("Accept"))
        val body = JSONObject(GZIPInputStream(req.body.inputStream()).bufferedReader().readText())
        assertEquals("mirrormap", body.getString("app"))
        assertEquals("it broke", body.getString("text"))
        assertTrue(body.has("bundle"))
        assertTrue(!body.has("sessionId"))
        assertTrue(!body.has("media"))
    }

    @Test fun `no access code means no Authorization header`() = runBlocking {
        server.enqueue(sse("done" to "{}"))
        transport(code = null).send(FeedbackMessage("mirrormov", "s1", "hi", null)).toList()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `consent posts the answer and streams the follow-up`() = runBlocking {
        server.enqueue(sse("delta" to """{"text":"Thanks"}""", "done" to "{}"))
        val events = transport().consent("mirrormov", "s1", granted = false, bundle = null).toList()
        assertEquals(listOf(FeedbackEvent.Delta("Thanks"), FeedbackEvent.Done), events)
        val req = server.takeRequest()
        assertEquals("/v1/feedback/consent", req.path)
        val body = JSONObject(GZIPInputStream(req.body.inputStream()).bufferedReader().readText())
        assertEquals("mirrormov", body.getString("app"))
        assertEquals("s1", body.getString("sessionId"))
        assertEquals(false, body.getBoolean("granted"))
        assertTrue(!body.has("bundle"))
    }

    @Test fun `server error becomes an Error event`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val events = transport().send(FeedbackMessage("mirrormap", null, "hi", null)).toList()
        assertEquals(1, events.size)
        assertTrue(events[0] is FeedbackEvent.Error)
    }

    @Test fun `media ids go with the message, and text may be empty`() = runBlocking {
        server.enqueue(sse("done" to "{}"))
        transport().send(FeedbackMessage("mirrormov", "s1", "", null, listOf("m_20261118_0123456789abcdef"))).toList()
        val body = JSONObject(GZIPInputStream(server.takeRequest().body.inputStream()).bufferedReader().readText())
        assertEquals("mirrormov", body.getString("app"))
        assertEquals("", body.getString("text"))
        assertEquals("m_20261118_0123456789abcdef", body.getJSONArray("media").getString(0))
    }
}
