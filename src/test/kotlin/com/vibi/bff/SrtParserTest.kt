package com.vibi.bff

import com.vibi.bff.service.SrtParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SrtParserTest {

    @Test
    fun `parses standard SRT with CRLF and re-numbers on serialize`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            Hello world

            2
            00:00:04,000 --> 00:00:06,000
            Second line
            wraps here

        """.trimIndent().replace("\n", "\r\n")

        val cues = SrtParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals("00:00:01,000", cues[0].startTimestamp)
        assertEquals("00:00:03,500", cues[0].endTimestamp)
        assertEquals("Hello world", cues[0].text)
        assertEquals("Second line\nwraps here", cues[1].text)

        val translated = cues.mapIndexed { idx, cue -> cue.copy(text = "T$idx") }
        val out = SrtParser.serialize(translated)
        assert(out.contains("00:00:01,000 --> 00:00:03,500"))
        assert(out.contains("T0"))
        assert(out.contains("T1"))
    }

    @Test
    fun `tolerates dot millisecond separator`() {
        val srt = """
            1
            00:00:01.000 --> 00:00:02.500
            One
        """.trimIndent()

        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals("00:00:01,000", cues[0].startTimestamp)
        assertEquals("00:00:02,500", cues[0].endTimestamp)
    }

    @Test
    fun `drops empty-text cues`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            ok

            2
            00:00:03,000 --> 00:00:04,000


            3
            00:00:05,000 --> 00:00:06,000
            also ok
        """.trimIndent()

        val cues = SrtParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals("ok", cues[0].text)
        assertEquals("also ok", cues[1].text)
    }
}
