package com.mirrorapps.kit.privacy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** SPEC.md, MirrorMsg section: what a message's shape keeps and what it hides. */
class ShapeFilterTest {

    private fun shape(s: String) = ShapeFilter.shape(s)

    @Test fun `letters become x and X, digits 9, punctuation and spacing stay`() {
        assertEquals("Xxxx xx 9:99, xx?", shape("Meet at 5:30, ok?"))
        assertEquals("XXX-999 xx \$99.99!", shape("ABC-123 is \$45.67!"))
    }

    @Test fun `other scripts are letters too`() {
        assertEquals("Xxxxx", shape("Ñandú"))
        assertEquals("xxx", shape("你好吗"))
        assertEquals("Xxxxxx", shape("Привет"))
        assertEquals("xxxxx", shape("مرحبا"))
    }

    @Test fun `accents written as combining marks don't add letters`() {
        // "é" as e + U+0301.
        assertEquals("xx", shape("éa"))
    }

    @Test fun `each emoji is one E, however it's built`() {
        assertEquals("E", shape("😀"))
        assertEquals("E", shape("👍🏽")) // skin tone
        assertEquals("E", shape("👩‍💻")) // ZWJ sequence
        assertEquals("E", shape("👨‍👩‍👧‍👦")) // family
        assertEquals("E", shape("❤️")) // VS16
        assertEquals("E", shape("🇪🇸")) // flag
        assertEquals("EE", shape("🇪🇸🇺🇸"))
        assertEquals("E", shape("1️⃣")) // keycap
        assertEquals("Xx E E", shape("Hi 😀 ❤️"))
    }

    @Test fun `a digit not followed by a keycap is a digit`() {
        assertEquals("9 99", shape("1 23"))
    }

    @Test fun `newlines and tabs are kept`() {
        assertEquals("xx\nxx\txx", shape("ab\ncd\tef"))
    }

    @Test fun `length counts code points of the original`() {
        assertEquals(3, ShapeFilter.length("abc"))
        assertEquals(1, ShapeFilter.length("😀"))
        val j = ShapeFilter.shapeJson("Hi 😀") as JSONObject
        assertEquals("Xx E", j.getString("shape"))
        assertEquals(4, j.getInt("len"))
    }

    @Test fun `names become Name and a length`() {
        val j = ShapeFilter.nameJson("Alex Doe") as JSONObject
        assertEquals("Name", j.getString("name"))
        assertEquals(8, j.getInt("len"))
        assertEquals(2, j.length())
    }

    @Test fun `null text is JSON null`() {
        assertSame(JSONObject.NULL, ShapeFilter.shapeJson(null))
        assertSame(JSONObject.NULL, ShapeFilter.nameJson(null))
    }

    @Test fun `no letter or digit of the original survives`() {
        val text = "Call Sam at 555-0100 about the Main St thing 🙂"
        val s = shape(text)
        assertEquals(s.length, s.count { it in "xX9E" || !it.isLetterOrDigit() })
        assertEquals("Xxxx Xxx xx 999-9999 xxxxx xxx Xxxx Xx xxxxx E", s)
    }

    @Test fun `log lines keep everything but tagged values`() {
        val line = "forward pkg=com.example.chat from=" +
            PiiTags.wrap(PiiTags.KIND_NAME, "Alex") + " text=" +
            PiiTags.wrap(PiiTags.KIND_MESSAGE, "See you at 8!") + " ok"
        assertEquals(
            "forward pkg=com.example.chat from=[Name len=4] text=[shape \"Xxx xxx xx 9!\" len=13] ok",
            ShapeFilter.filterLogLine(line),
        )
    }

    @Test fun `an unclosed tag runs to the end of the line`() {
        val line = "text=" + PiiTags.OPEN + PiiTags.KIND_MESSAGE + "secret words"
        assertEquals("text=[shape \"xxxxxx xxxxx\" len=12]", ShapeFilter.filterLogLine(line))
    }

    @Test fun `untagged lines pass unchanged`() {
        assertEquals("conn connected=true watch=basalt", ShapeFilter.filterLogLine("conn connected=true watch=basalt"))
    }
}
