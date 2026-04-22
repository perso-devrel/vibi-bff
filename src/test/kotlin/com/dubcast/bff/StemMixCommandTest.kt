package com.dubcast.bff

import com.dubcast.bff.model.StemMixSelection
import com.dubcast.bff.service.StemMixService
import java.io.File
import kotlin.test.*

class StemMixCommandTest {

    private val tmpDir = File(System.getProperty("java.io.tmpdir"))
    private val outFile = File(tmpDir, "out.mp3")
    private val aFile = File(tmpDir, "a.mp3")
    private val bFile = File(tmpDir, "b.mp3")
    private val cFile = File(tmpDir, "c.mp3")

    private fun cmdOf(vararg pairs: Pair<StemMixSelection, File>): List<String> =
        StemMixService.buildStemMixCommand(pairs.toList(), outFile)

    @Test
    fun `single stem builds volume + amix graph`() {
        val cmd = cmdOf(
            StemMixSelection("background", 0.6f) to aFile,
        )
        assertTrue(cmd.containsAll(listOf("-i", aFile.absolutePath)))
        val filters = cmd[cmd.indexOf("-filter_complex") + 1]
        assertTrue(filters.contains("[0:a]volume=0.6[a0]"))
        assertTrue(filters.contains("[a0]amix=inputs=1:duration=longest"))
    }

    @Test
    fun `multi-stem chain has one volume node per input`() {
        val cmd = cmdOf(
            StemMixSelection("background", 0.5f) to aFile,
            StemMixSelection("speaker_0",  1.2f) to bFile,
            StemMixSelection("speaker_1",  1.0f) to cFile,
        )
        val filters = cmd[cmd.indexOf("-filter_complex") + 1]
        assertTrue(filters.contains("[0:a]volume=0.5[a0]"))
        assertTrue(filters.contains("[1:a]volume=1.2[a1]"))
        assertTrue(filters.contains("[2:a]volume=1.0[a2]"))
        assertTrue(filters.contains("[a0][a1][a2]amix=inputs=3:duration=longest"))
    }

    @Test
    fun `output params include mp3 codec and bitrate`() {
        val cmd = cmdOf(StemMixSelection("background", 1f) to aFile)
        assertTrue(cmd.containsAll(listOf("-c:a", "libmp3lame", "-b:a", "192k")))
        assertEquals(outFile.absolutePath, cmd.last())
    }

    @Test
    fun `output is mapped to aout label`() {
        val cmd = cmdOf(StemMixSelection("background", 1f) to aFile)
        assertTrue(cmd.containsAll(listOf("-map", "[aout]")))
    }
}
