package com.dubcast.bff.service

/**
 * Single SRT cue: index + start/end timestamps preserved verbatim, text body
 * normalized so we can hand only the text portion to a translator and re-stitch
 * the original timing untouched.
 *
 * `text` may be multi-line in the source SRT. We join with '\n' on parse and
 * keep the same delimiter on serialize so wrapped subtitles round-trip cleanly.
 */
data class SrtCue(
    val index: Int,
    val startTimestamp: String,
    val endTimestamp: String,
    val text: String,
)

object SrtParser {
    private val TIMESTAMP_LINE = Regex(
        """\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3}).*"""
    )

    /**
     * Tolerant SRT parser:
     *  - Accepts both ',' and '.' in the millisecond separator (some upstream
     *    services emit '.' even though the spec is ',').
     *  - Skips blank lines between cues but does not require an exact blank-
     *    line count.
     *  - Drops cues whose body is empty (text-only blank cues are not useful
     *    and would confuse the LLM length contract).
     */
    fun parse(srt: String): List<SrtCue> {
        val cues = mutableListOf<SrtCue>()
        val lines = srt.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            // Skip blanks between cues
            while (i < lines.size && lines[i].isBlank()) i++
            if (i >= lines.size) break

            val indexLine = lines[i].trim()
            val index = indexLine.toIntOrNull()
            if (index == null) {
                // Not a cue index — advance and keep scanning. Defensive against
                // SRTs with a UTF-8 BOM or stray header.
                i++
                continue
            }
            i++
            if (i >= lines.size) break

            val tsMatch = TIMESTAMP_LINE.matchEntire(lines[i])
            if (tsMatch == null) {
                i++
                continue
            }
            val start = tsMatch.groupValues[1].replace('.', ',')
            val end = tsMatch.groupValues[2].replace('.', ',')
            i++

            val textBuf = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (textBuf.isNotEmpty()) textBuf.append('\n')
                textBuf.append(lines[i])
                i++
            }
            val text = textBuf.toString()
            if (text.isNotBlank()) {
                cues.add(SrtCue(index, start, end, text))
            }
        }
        return cues
    }

    /**
     * Serialize cues back to SRT. Re-numbers indices sequentially so a
     * downstream parser doesn't trip on gaps left by dropped blank cues.
     */
    fun serialize(cues: List<SrtCue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { idx, cue ->
            sb.append(idx + 1).append('\n')
            sb.append(cue.startTimestamp).append(" --> ").append(cue.endTimestamp).append('\n')
            sb.append(cue.text).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }
}
