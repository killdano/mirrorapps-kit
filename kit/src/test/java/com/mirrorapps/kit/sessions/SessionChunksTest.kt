package com.mirrorapps.kit.sessions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of session recording, checked against trip-review/SPEC.md
 * in the backend repo. Moved from Dash's TripChunksTest.
 */
class SessionChunksTest {

    private val client = JSONObject().put("name", "MirrorMap Dash").put("platform", "android")
    private val header = SessionChunks.Header(app = "mirrormap", source = "dash", session = "dash-abc")

    private fun notifEvent(t: Long) =
        SessionChunks.event(t, "maps_notif", mapOf("op" to "posted", "id" to 7))

    private fun pieceJson(p: SessionChunks.Piece) = JSONObject(String(p.plain(), Charsets.UTF_8))

    // keys(), not keySet(): tests compile against Android's org.json, which has no keySet().
    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    // -- windowing ----------------------------------------------------------

    @Test
    fun emptyWindowIsNeverUploaded() {
        val w = SessionWindow(1_000)
        assertFalse(w.worthUploading(active = false))
        assertFalse(w.worthUploading(active = true))
    }

    @Test
    fun logsAloneAreAnIdleWindow() {
        val w = SessionWindow(1_000)
        w.addLog(1_001, "[12:00:00] D/Thing: idle")
        assertFalse(w.worthUploading(active = false))
    }

    @Test
    fun unworthyEventAloneIsIdle() {
        val w = SessionWindow(1_000)
        w.addEvent(notifEvent(1_001), isNotif = true, worthy = false)
        assertFalse(w.worthUploading(active = false))
    }

    @Test
    fun worthyEventMakesWindowWorthUploading() {
        val w = SessionWindow(1_000)
        w.addEvent(notifEvent(1_001), isNotif = true, worthy = true)
        assertTrue(w.worthUploading(active = false))
    }

    @Test
    fun beingActiveKeepsAQuietWindow() {
        val w = SessionWindow(1_000)
        w.addLog(1_001, "line")
        assertTrue(w.worthUploading(active = true))
    }

    @Test
    fun endUploadsEvenAnEmptyWindow() {
        val w = SessionWindow(1_000)
        w.end = "arrived"
        assertTrue(w.worthUploading(active = false))
    }

    @Test
    fun logLinesAreCapped() {
        val w = SessionWindow(0)
        repeat(SessionChunks.MAX_LOG_LINES + 25) { w.addLog(it.toLong(), "l$it") }
        assertEquals(SessionChunks.MAX_LOG_LINES, w.logs.size)
        assertEquals(25, w.droppedLogs)
        assertEquals("l0", w.logs.first().line)
    }

    @Test
    fun notificationCapIsSeparateFromOtherEvents() {
        val w = SessionWindow(0)
        repeat(SessionChunks.MAX_NOTIF_EVENTS + 3) { w.addEvent(notifEvent(it.toLong()), isNotif = true, worthy = true) }
        // Other events still get in after the notification cap is hit.
        w.addEvent(SessionChunks.event(1, "share", emptyMap()), isNotif = false, worthy = true)
        assertEquals(SessionChunks.MAX_NOTIF_EVENTS + 1, w.events.size)
        assertEquals(3, w.droppedNotifs)
        assertEquals(0, w.droppedEvents)
        val dropped = w.droppedJson()
        assertNotNull(dropped)
        assertEquals(3, dropped!!.getInt("notifs"))
    }

    @Test
    fun nothingDroppedMeansNoDroppedField() {
        val w = SessionWindow(0)
        w.addLog(1, "x")
        assertNull(w.droppedJson())
    }

    // -- extras ---------------------------------------------------------------

    @Test
    fun extrasKeepTextNumbersAndBooleansVerbatim() {
        val styled: CharSequence = StringBuilder("Head ").append("north")
        val j = SessionChunks.captureExtras(listOf(
            "android.title" to styled,
            "android.text" to "toward Main St",
            "android.progress" to 42,
            "android.when" to 1_731_900_000_123L,
            "android.showWhen" to true,
            "f" to 0.1f,
            "d" to 2.5,
            "s" to 3.toShort(),
        ))
        assertEquals("Head north", j.getString("android.title"))
        assertEquals("toward Main St", j.getString("android.text"))
        assertEquals(42, j.getInt("android.progress"))
        assertEquals(1_731_900_000_123L, j.getLong("android.when"))
        assertTrue(j.getBoolean("android.showWhen"))
        assertEquals(0.1, j.getDouble("f"), 0.0)
        assertEquals(2.5, j.getDouble("d"), 0.0)
        assertEquals(3, j.getInt("s"))
    }

    @Test
    fun extrasSkipEverythingElse() {
        val j = SessionChunks.captureExtras(listOf(
            "android.largeIcon" to Any(),
            "android.picture" to ByteArray(4),
            "android.people" to arrayOf("a"),
            "android.car.EXTENSIONS" to hashMapOf("k" to "v"),
            "android.subText" to null,
            "android.title" to "kept",
        ))
        assertEquals(1, j.length())
        assertEquals("kept", j.getString("android.title"))
    }

    @Test
    fun nonFiniteNumbersBecomeText() {
        val j = SessionChunks.captureExtras(listOf("n" to Double.NaN, "i" to Float.POSITIVE_INFINITY))
        assertEquals("NaN", j.getString("n"))
        assertEquals("Infinity", j.getString("i"))
    }

    private enum class Verdict { ARRIVAL }

    @Test
    fun detailValuesBecomeJson() {
        val e = SessionChunks.event(5, "companion", mapOf(
            "what" to "start",
            "origin" to null,
            "verdict" to Verdict.ARRIVAL,
            "arrived" to false,
            "pts" to listOf(1, 2),
        ))
        assertEquals(5L, e.getLong("t"))
        assertEquals("companion", e.getString("k"))
        assertEquals("start", e.getString("what"))
        assertTrue(e.isNull("origin"))
        assertEquals("ARRIVAL", e.getString("verdict"))
        assertFalse(e.getBoolean("arrived"))
        assertEquals(2, e.getJSONArray("pts").getInt(1))
    }

    // -- chunk shape ------------------------------------------------------------

    @Test
    fun chunkMatchesSpec() {
        val w = SessionWindow(1_000)
        w.addEvent(notifEvent(1_100), isNotif = true, worthy = true)
        w.addLog(1_200, "[12:00:00] I/Thing: hello")
        val pieces = SessionChunks.buildPieces(w, header, firstSeq = 4, t1 = 2_000, client = client)
        assertEquals(1, pieces.size)
        val p = pieces[0]
        assertEquals(4, p.seq)
        val j = pieceJson(p)
        assertEquals(p.rawBytes, j.toString().toByteArray(Charsets.UTF_8).size)
        assertEquals(
            setOf("v", "app", "source", "session", "seq", "t0", "t1", "client", "end", "events", "logs"),
            j.keyNames(),
        )
        assertEquals(1, j.getInt("v"))
        // The backend refuses a chunk whose app isn't a session app's id.
        assertEquals("mirrormap", j.getString("app"))
        assertEquals("dash", j.getString("source"))
        assertEquals("dash-abc", j.getString("session"))
        assertEquals(4, j.getInt("seq"))
        assertEquals(1_000L, j.getLong("t0"))
        assertEquals(2_000L, j.getLong("t1"))
        assertEquals("android", j.getJSONObject("client").getString("platform"))
        assertTrue(j.isNull("end"))
        assertFalse(j.has("fixes"))
        val ev = j.getJSONArray("events").getJSONObject(0)
        assertEquals("maps_notif", ev.getString("k"))
        assertEquals(1_100L, ev.getLong("t"))
        val log = j.getJSONArray("logs").getJSONArray(0)
        assertEquals(1_200L, log.getLong(0))
        assertEquals("[12:00:00] I/Thing: hello", log.getString(1))
    }

    @Test
    fun endIsSent() {
        val w = SessionWindow(1_000)
        w.end = "companion_ended"
        val j = pieceJson(SessionChunks.buildPieces(w, header, 0, 2_000, client).single())
        assertEquals("companion_ended", j.getString("end"))
    }

    @Test
    fun droppedCountsTravelWithTheChunk() {
        val w = SessionWindow(0)
        repeat(SessionChunks.MAX_LOG_LINES + 2) { w.addLog(it.toLong(), "x") }
        w.end = "arrived"
        val j = pieceJson(SessionChunks.buildPieces(w, header, 0, 1, client).single())
        assertEquals(2, j.getJSONObject("dropped").getInt("logs"))
    }

    @Test
    fun bigWindowSplitsInTimeOrderWithEndOnTheLastPiece() {
        val w = SessionWindow(0)
        for (i in 0 until 200) {
            w.addEvent(
                SessionChunks.event(i * 10L, "maps_notif", mapOf("text" to "notification $i ".repeat(20))),
                isNotif = true, worthy = true,
            )
            w.addLog(i * 10L + 5, "log line $i ".repeat(20))
        }
        w.end = "arrived"
        val pieces = SessionChunks.buildPieces(w, header, firstSeq = 10, t1 = 5_000, client = client, maxRawBytes = 20_000)
        assertTrue("expected a split, got ${pieces.size}", pieces.size > 1)
        assertEquals((10 until 10 + pieces.size).toList(), pieces.map { it.seq })

        val jsons = pieces.map { pieceJson(it) }
        jsons.forEach { assertTrue(it.toString().toByteArray().size <= 20_000) }
        jsons.dropLast(1).forEach { assertTrue(it.isNull("end")) }
        assertEquals("arrived", jsons.last().getString("end"))

        // Every item arrives once, and the pieces follow each other in time.
        val evT = jsons.flatMap { j -> j.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it).getLong("t") } } }
        val logT = jsons.flatMap { j -> j.getJSONArray("logs").let { a -> (0 until a.length()).map { a.getJSONArray(it).getLong(0) } } }
        assertEquals((0 until 200).map { it * 10L }, evT)
        assertEquals((0 until 200).map { it * 10L + 5 }, logT)
        jsons.forEach { assertEquals(0L, it.getLong("t0")) }
    }

    @Test
    fun gzipRoundTrips() {
        val raw = "{\"v\":1}".toByteArray()
        val p = SessionChunks.Piece(seq = 0, gzip = SessionChunks.gzip(raw), rawBytes = raw.size, end = null)
        assertEquals("{\"v\":1}", String(p.plain()))
    }

    // -- enabled + consent ------------------------------------------------------

    @Test
    fun enabledOwner() {
        val e = SessionChunks.parseEnabled("""{"record":true,"role":"owner","uploadEverySec":120}""")
        assertEquals(SessionChunks.Enabled(true, "owner", 120), e)
    }

    @Test
    fun enabledOff() {
        val e = SessionChunks.parseEnabled("""{"record":false}""")
        assertEquals(SessionChunks.Enabled(false, null, SessionChunks.DEFAULT_UPLOAD_EVERY_SEC), e)
    }

    @Test
    fun enabledCadenceHasAFloor() {
        assertEquals(30, SessionChunks.parseEnabled("""{"record":true,"role":"user","uploadEverySec":5}""")!!.uploadEverySec)
        assertEquals(120, SessionChunks.parseEnabled("""{"record":true,"role":"user","uploadEverySec":0}""")!!.uploadEverySec)
    }

    @Test
    fun enabledGarbageKeepsTheCache() {
        assertNull(SessionChunks.parseEnabled("<html>"))
    }

    @Test
    fun consentBodiesNameTheApp() {
        val on = SessionChunks.consentOnBody("mirrormap", version = 1, platform = "dash")
        assertEquals(setOf("app", "on", "version", "platform"), on.keyNames())
        assertEquals("mirrormap", on.getString("app"))
        assertTrue(on.getBoolean("on"))
        assertEquals(1, on.getInt("version"))
        assertEquals("dash", on.getString("platform"))

        val off = SessionChunks.consentOffBody("mirrormsg", deleteData = false)
        assertEquals(setOf("app", "on"), off.keyNames())
        assertEquals("mirrormsg", off.getString("app"))
        assertFalse(off.getBoolean("on"))

        val del = SessionChunks.consentOffBody("mirrormap", deleteData = true)
        assertEquals(setOf("app", "on", "deleteData"), del.keyNames())
        assertFalse(del.getBoolean("on"))
        assertTrue(del.getBoolean("deleteData"))
    }

    // -- fixes ------------------------------------------------------------------

    private val movHeader = SessionChunks.Header(app = "mirrormov", source = "dash", session = "dashmov-abc")

    @Test
    fun fixIsTheSpecArray() {
        val f = SessionChunks.Fix(t = 1_700, lat = 12.3456789, lng = -98.7654321, acc = 4.26, speed = 5.678, heading = 271.04)
        assertEquals("[1700,12.345679,-98.765432,4.3,5.68,271,0]", f.json().toString())
    }

    @Test
    fun unknownFixFieldsAreMinusOne() {
        val f = SessionChunks.Fix(t = 1, lat = 1.0, lng = 2.0, acc = Double.NaN, speed = -3.0, sent = true)
        val a = f.json()
        assertEquals(-1, a.getInt(3))
        assertEquals(-1, a.getInt(4))
        assertEquals(-1, a.getInt(5))
        assertEquals(1, a.getInt(6))
    }

    @Test
    fun slowFixesAloneAreAnIdleWindow() {
        val w = SessionWindow(0)
        w.addFix(SessionChunks.Fix(t = 1, lat = 1.0, lng = 2.0, speed = 0.4))
        w.addFix(SessionChunks.Fix(t = 2, lat = 1.0, lng = 2.0))
        assertFalse(w.worthUploading(active = false))
        assertTrue(w.worthUploading(active = true))
    }

    @Test
    fun aMovingFixMakesTheWindowWorthUploading() {
        val w = SessionWindow(0)
        w.addFix(SessionChunks.Fix(t = 1, lat = 1.0, lng = 2.0, speed = 2.5))
        assertTrue(w.worthUploading(active = false))
    }

    @Test
    fun fixWithoutAPositionIsIgnored() {
        val w = SessionWindow(0)
        w.addFix(SessionChunks.Fix(t = 1, lat = Double.NaN, lng = 2.0, speed = 9.0))
        assertTrue(w.fixes.isEmpty())
        assertFalse(w.worthUploading(active = true))
    }

    @Test
    fun fixesAreCappedAndCounted() {
        val w = SessionWindow(0)
        repeat(SessionChunks.MAX_FIXES + 4) { w.addFix(SessionChunks.Fix(t = it.toLong(), lat = 1.0, lng = 2.0)) }
        assertEquals(SessionChunks.MAX_FIXES, w.fixes.size)
        assertEquals(4, w.droppedFixes)
        assertEquals(4, w.droppedJson()!!.getInt("fixes"))
    }

    @Test
    fun chunkCarriesFixesWhenThereAreSome() {
        val w = SessionWindow(1_000)
        w.addFix(SessionChunks.Fix(t = 1_100, lat = 10.0, lng = 20.0, acc = 3.0, speed = 4.0, heading = 90.0))
        w.addEvent(SessionChunks.event(1_150, "mov_state", mapOf("what" to "start", "sport" to "Ride")), isNotif = false, worthy = true)
        val j = pieceJson(SessionChunks.buildPieces(w, movHeader, 0, 2_000, client).single())
        assertEquals("mirrormov", j.getString("app"))
        val fixes = j.getJSONArray("fixes")
        assertEquals(1, fixes.length())
        assertEquals(1_100L, fixes.getJSONArray(0).getLong(0))
        assertEquals(10.0, fixes.getJSONArray(0).getDouble(1), 0.0)
        assertEquals(0, fixes.getJSONArray(0).getInt(6))
        assertEquals("mov_state", j.getJSONArray("events").getJSONObject(0).getString("k"))
    }

    @Test
    fun bigWindowSplitsFixesInTimeOrderToo() {
        val w = SessionWindow(0)
        for (i in 0 until 400) {
            w.addFix(SessionChunks.Fix(t = i * 10L, lat = 1.0 + i / 1e5, lng = 2.0, acc = 5.0, speed = 3.0, heading = 180.0))
            w.addLog(i * 10L + 5, "log line $i ".repeat(10))
        }
        w.end = "saved"
        val pieces = SessionChunks.buildPieces(w, movHeader, firstSeq = 0, t1 = 5_000, client = client, maxRawBytes = 20_000)
        assertTrue("expected a split, got ${pieces.size}", pieces.size > 1)
        val jsons = pieces.map { pieceJson(it) }
        jsons.forEach { assertTrue(it.toString().toByteArray().size <= 20_000) }
        assertEquals("saved", jsons.last().getString("end"))
        val fixT = jsons.flatMap { j ->
            if (!j.has("fixes")) emptyList() else j.getJSONArray("fixes").let { a -> (0 until a.length()).map { a.getJSONArray(it).getLong(0) } }
        }
        assertEquals((0 until 400).map { it * 10L }, fixT)
    }

    @Test
    fun logLineIsATwoElementArray() {
        val j = SessionChunks.chunkJson(header, 0, 0, 1, client, null, emptyList(), listOf(SessionChunks.LogLine(9, "x")))
        assertEquals(JSONArray().put(9L).put("x").toString(), j.getJSONArray("logs").getJSONArray(0).toString())
    }
}
