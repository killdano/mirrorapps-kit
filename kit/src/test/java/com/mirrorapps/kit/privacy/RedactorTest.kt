package com.mirrorapps.kit.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tagging calls Dash's log lines use, written with [PiiTags]. */
private object Pii {
    const val OPEN = PiiTags.OPEN
    const val CLOSE = PiiTags.CLOSE
    fun dest(v: Any?) = PiiTags.wrap(PiiTags.KIND_DESTINATION, v)
    fun addr(v: Any?) = PiiTags.wrap(PiiTags.KIND_ADDRESS, v)
    fun url(v: Any?) = PiiTags.wrap(PiiTags.KIND_URL, v)
    fun nav(v: Any?) = PiiTags.wrap(PiiTags.KIND_NAV_TEXT, v)
    fun place(v: Any?) = PiiTags.wrap(PiiTags.KIND_PLACE, v)
    fun strip(s: String) = PiiTags.strip(s)
}


/**
 * Line shapes are copied from real Dash logs; every value in them is fake
 * (Empire State Building coordinates, made-up codes and place ids).
 */
class RedactorTest {

    private fun redacted(vararg secrets: String) = Redactor(Redactor.Mode.REDACTED, secrets.toList())
    private fun full(vararg secrets: String) = Redactor(Redactor.Mode.FULL, secrets.toList())

    // ---- tagged values ----

    @Test fun `tagged destination becomes a numbered placeholder`() {
        val r = redacted()
        val out = r.redact("[13:55:01] I/MapsNotifListener: pushing companion route for '${Pii.dest("Empire State Building")}' (mode=CAR)")
        assertEquals("[13:55:01] I/MapsNotifListener: pushing companion route for '[DESTINATION 1]' (mode=CAR)", out)
        assertEquals("Empire State Building", r.values["DESTINATION 1"])
    }

    @Test fun `same value keeps its number across lines and texts`() {
        val r = redacted()
        val a = r.redact("for '${Pii.dest("Home")}'")
        val b = r.redact("superseded prior session for '${Pii.dest("Work")}' then '${Pii.dest("Home")}'")
        assertEquals("for '[DESTINATION 1]'", a)
        assertEquals("superseded prior session for '[DESTINATION 2]' then '[DESTINATION 1]'", b)
        assertEquals(2, r.counts[Redactor.TYPE_DESTINATION])
    }

    @Test fun `each tag kind maps to its own type`() {
        val r = redacted()
        val out = r.redact(
            "${Pii.addr("350 5th Ave")} ${Pii.url("https://example.test/x")} " +
                "${Pii.nav("Turn left onto 5th Ave")} ${Pii.place("Midtown")}"
        )
        assertEquals("[ADDRESS 1] [URL 1] [NAV_TEXT 1] [PLACE 1]", out)
    }

    @Test fun `full mode keeps tagged values and drops the markers`() {
        val out = full().redact("raw posted=1 title='${Pii.nav("Turn left")}' text='${Pii.nav("onto 5th Ave")}'")
        assertEquals("raw posted=1 title='Turn left' text='onto 5th Ave'", out)
    }

    @Test fun `full mode keeps untagged coordinates and Maps links`() {
        val line = "captured origin → 40.748817,-73.985428 via https://maps.app.goo.gl/AbCdEfGh123"
        assertEquals(line, full().redact(line))
    }

    @Test fun `a tag cut off before its closer hides the rest of the line`() {
        val out = redacted().redact("for '${Pii.OPEN}DEmpire Sta\nnext line")
        assertEquals("for '[DESTINATION 1]\nnext line", out)
        assertFalse(out.contains(Pii.OPEN))
    }

    @Test fun `a lone closer is dropped`() {
        assertEquals("abc", full().redact("ab${Pii.CLOSE}c"))
    }

    // ---- untagged location ----

    @Test fun `coordinate pair in a GPS fix`() {
        val r = redacted()
        val out = r.redact("[19:02:24] V/LiveLocationStream: fix: 40.748817,-73.985428 bearing=null speed=null m/s acc=12.0m")
        assertEquals("[19:02:24] V/LiveLocationStream: fix: [LOCATION 1] bearing=null speed=null m/s acc=12.0m", out)
        assertEquals("40.748817,-73.985428", r.values["LOCATION 1"])
    }

    @Test fun `parenthesised anchor with a space`() {
        val out = redacted().redact("route state set — rawPts=165, anchor=(40.748817, -73.985428), mode=2, companion=true")
        assertEquals("route state set — rawPts=165, anchor=([LOCATION 1]), mode=2, companion=true", out)
    }

    @Test fun `LatLng toString`() {
        val out = redacted().redact("handle: starting pipeline url=x mode=CAR origin=LatLng(lat=40.7488, lng=-73.9854)")
        assertEquals("handle: starting pipeline url=x mode=CAR origin=LatLng([LOCATION 1])", out)
    }

    @Test fun `ordinary numbers are left alone`() {
        val line = "eta=1234s dist=5.25km speed=1.5, 2.5 acc=12.0m pct=67.0% heap: 35784B free"
        assertEquals(line, redacted().redact(line))
    }

    @Test fun `out-of-range pair is not a coordinate`() {
        val line = "ratio 123.4567,45.1234"
        assertEquals(line, redacted().redact(line))
    }

    @Test fun `short Maps link`() {
        val out = redacted().redact("received share intent: Url(url=https://maps.app.goo.gl/AbCdEfGh123)")
        assertEquals("received share intent: Url(url=[URL 1])", out)
    }

    @Test fun `resolved Maps directions link with coordinates and an address`() {
        val r = redacted()
        val out = r.redact(
            "resolved → https://www.google.com/maps/dir/40.748817,-73.985428/350+5th+Ave/" +
                "data=!4m12!1s0x0:0x1!8m2!3d40.7484405!4d-73.9856644!3e0?utm_source=mstt_1"
        )
        assertEquals("resolved → [URL 1]", out)
        assertFalse(out.contains("40.74"))
        assertFalse(out.contains("5th"))
    }

    @Test fun `tile request path keeps zoom and hides x and y`() {
        val out = redacted().redact("V/MovServer: GET /tiles/cyclosm/17/38596/49262.png -> 200")
        assertEquals("V/MovServer: GET /tiles/cyclosm/17/[TILE 1].png -> 200", out)
    }

    // ---- always removed ----

    @Test fun `full access code keeps prefix and last four`() {
        val out = full().redact("accessCode set — mm_live_AbCdEfGhIjKlMnOpQrSt")
        assertEquals("accessCode set — mm_live_…QrSt", out)
    }

    @Test fun `already truncated code keeps only the prefix`() {
        assertEquals("GET /v1/me codePrefix=mm_live_…", full().redact("GET /v1/me codePrefix=mm_live_AbCd…"))
        assertEquals("POST routes intermediate=false codePrefix=mm_live_…", full().redact("POST routes intermediate=false codePrefix=mm_live_AbCd"))
    }

    @Test fun `exact secrets are removed in every mode`() {
        val secret = "fake-secret-1234"
        assertEquals("code [DEV_CODE] ok", full(secret).redact("code fake-secret-1234 ok"))
        assertEquals("code [DEV_CODE] ok", redacted(secret).redact("code fake-secret-1234 ok"))
    }

    @Test fun `tokens, emails and Bluetooth addresses`() {
        val out = full().redact(
            "activating tokenPrefix=abcdefgh01234567… Authorization: Bearer abc.def " +
                "user someone@example.com strap AA:BB:CC:11:22:33"
        )
        assertEquals(
            "activating tokenPrefix=[TOKEN] Authorization: Bearer [TOKEN] user [EMAIL] strap [BT_ADDR]",
            out,
        )
    }

    @Test fun `secret inside a tagged value is still removed in full mode`() {
        val out = full("fake-secret-1234").redact("text=\"${Pii.addr("fake-secret-1234")}\"")
        assertEquals("text=\"[DEV_CODE]\"", out)
    }

    // ---- Pii ----

    @Test fun `strip removes markers and keeps values`() {
        assertEquals("for 'Home' at 'Turn left'", Pii.strip("for '${Pii.dest("Home")}' at '${Pii.nav("Turn left")}'"))
    }

    @Test fun `strip leaves untagged text untouched`() {
        val s = "plain line 40.748817,-73.985428"
        assertTrue(Pii.strip(s) === s)
    }

    @Test fun `null values are written as null without markers`() {
        assertEquals("null", Pii.dest(null))
    }
}
