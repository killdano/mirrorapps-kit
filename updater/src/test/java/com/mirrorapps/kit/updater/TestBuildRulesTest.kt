package com.mirrorapps.kit.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestBuildRulesTest {

    // The shape the backend answers, with made-up values.
    private val latest = """
        {"tag":"test-23","versionCode":100023,"versionName":"0.9-test.23",
         "publishedAt":"2026-11-19T08:00:00Z","size":21000000}
    """.trimIndent()

    private fun build(code: Long) = TestBuild("test-${code - 100000}", code, "0.9-test.${code - 100000}", "", 1L)

    @Test
    fun parsesTheBackendsAnswer() {
        val b = TestBuild.parse(latest)!!
        assertEquals("test-23", b.tag)
        assertEquals(100023L, b.versionCode)
        assertEquals("0.9-test.23", b.versionName)
        assertEquals(21_000_000L, b.size)
        assertEquals(23, b.number)
        assertEquals("test-23 (100023)", b.toString())
    }

    @Test
    fun refusesAnythingThatIsntATestBuild() {
        assertNull(TestBuild.parse("not json"))
        assertNull(TestBuild.parse("""{"error":"no_test_build"}"""))
        assertNull(TestBuild.parse("""{"tag":"v1.0","versionCode":46}"""))
        assertNull(TestBuild.parse("""{"tag":"test-23"}"""))
        assertNull(TestBuild.parse("""{"tag":"test-0","versionCode":100000}"""))
        assertEquals(0L, TestBuild.parse("""{"tag":"test-2","versionCode":100002,"size":-5}""")!!.size)
    }

    @Test
    fun testNumbers() {
        assertEquals(1, TestBuild.testNumber("test-1"))
        assertEquals(1234, TestBuild.testNumber("test-1234"))
        assertNull(TestBuild.testNumber("test-01"))
        assertNull(TestBuild.testNumber("test-"))
        assertNull(TestBuild.testNumber("test-2a"))
        assertNull(TestBuild.testNumber("v2"))
    }

    @Test
    fun aHigherVersionCodeInstallsAndAnythingElseIsUpToDate() {
        assertEquals(Decision.INSTALL, TestBuildRules.decide(build(100023), 100020, autoInstall = true))
        assertEquals(Decision.UP_TO_DATE, TestBuildRules.decide(build(100023), 100023, autoInstall = true))
        assertEquals(Decision.UP_TO_DATE, TestBuildRules.decide(build(100020), 100023, autoInstall = true))
        // A hand-built or store build (low versionCode) still takes a test build.
        assertEquals(Decision.INSTALL, TestBuildRules.decide(build(100001), 46, autoInstall = true))
    }

    @Test
    fun theSwitchOffOnlyReports() {
        assertEquals(Decision.NEWER_BUT_OFF, TestBuildRules.decide(build(100023), 100020, autoInstall = false))
        assertEquals(Decision.UP_TO_DATE, TestBuildRules.decide(build(100020), 100020, autoInstall = false))
    }

    @Test
    fun aBuildWaitingForTheTapIsntFetchedAgainForTwelveHours() {
        val now = 1_000_000_000L
        fun decide(code: Long, since: Long, alive: Boolean) = TestBuildRules.decide(
            build(100023), 100020, autoInstall = true,
            waitingCode = code, waitingSince = since, waitingSessionAlive = alive, now = now,
        )
        assertEquals(Decision.WAITING_FOR_TAP, decide(100023, now - 60_000, alive = true))
        assertEquals(Decision.INSTALL, decide(100023, now - TestBuildRules.TAP_WAIT_MS, alive = true))
        assertEquals(Decision.INSTALL, decide(100023, now - 60_000, alive = false))
        // An older build waiting for its tap gives way to the newer one.
        assertEquals(Decision.INSTALL, decide(100022, now - 60_000, alive = true))
        // "Nothing could be shown" resets the wait to 0: fetch again.
        assertEquals(Decision.INSTALL, decide(100023, 0L, alive = true))
    }

    @Test
    fun aPendingBuildLandsWhenTheRunningVersionReachesIt() {
        assertTrue(TestBuildRules.pendingLanded(100023, 100023))
        assertTrue(TestBuildRules.pendingLanded(100023, 100024))
        assertFalse(TestBuildRules.pendingLanded(100023, 100020))
        assertFalse(TestBuildRules.pendingLanded(0, 100020))
    }

    @Test
    fun megabytes() {
        assertEquals("21.0 MB", TestBuildRules.megabytes(21_000_000))
        assertEquals("0.5 MB", TestBuildRules.megabytes(500_000))
    }
}
