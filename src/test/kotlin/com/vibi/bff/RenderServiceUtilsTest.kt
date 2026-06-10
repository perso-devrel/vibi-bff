package com.vibi.bff

import com.vibi.bff.service.DirectiveStem
import com.vibi.bff.service.DirectiveWithStemFiles
import com.vibi.bff.service.RenderService
import com.vibi.bff.service.secondsToFfmpegArg
import java.io.File
import kotlin.test.*

class RenderServiceUtilsTest {

    private val service = RenderService(File(System.getProperty("java.io.tmpdir"), "renderservice-utils-test"))

    // ── secondsToFfmpegArg ─────────────────────────────────────────────────────

    @Test
    fun `secondsToFfmpegArg formats sub-millisecond as fixed decimal not scientific`() {
        // Regression guard: 0.000670 used to render as "6.7E-4" via Double.toString,
        // which ffmpeg rejected with "Invalid duration for option t" (exit 234).
        val arg = secondsToFfmpegArg(0.000670)
        assertEquals("0.000670", arg)
        assertFalse(arg.contains("E", ignoreCase = true), "must not contain scientific notation: $arg")
    }

    @Test
    fun `secondsToFfmpegArg rounds to microsecond precision`() {
        // ffmpeg AV_TIME_BASE is 1e6 — anything beyond 6 decimals is noise.
        assertEquals("1.500000", secondsToFfmpegArg(1.5))
        assertEquals("0.000000", secondsToFfmpegArg(0.0))
        assertEquals("3.000000", secondsToFfmpegArg(3.0))
    }

    @Test
    fun `secondsToFfmpegArg uses US locale for decimal separator`() {
        // Locale-sensitive String.format would output "1,500000" on de-DE / fr-FR
        // and ffmpeg rejects that. Locale.US fixes it regardless of JVM default.
        val arg = secondsToFfmpegArg(1.5)
        assertTrue(arg.contains("."), "expected dot decimal: $arg")
        assertFalse(arg.contains(","), "must not use comma decimal: $arg")
    }

    @Test
    fun `secondsToFfmpegArg handles very small and very large values`() {
        // Tiny — would otherwise be "6.702596628521888E-4" from Double.toString.
        val tiny = secondsToFfmpegArg(6.702596628521888E-4)
        assertEquals("0.000670", tiny)
        // Large — well within long-form range.
        assertEquals("3600.000000", secondsToFfmpegArg(3600.0))
    }

    // ── atempoChain ────────────────────────────────────────────────────────────

    @Test
    fun `atempoChain emits single filter for in-range speeds`() {
        assertEquals("atempo=0.5", service.atempoChain(0.5f))
        assertEquals("atempo=1.0", service.atempoChain(1.0f))
        assertEquals("atempo=2.0", service.atempoChain(2.0f))
    }

    @Test
    fun `atempoChain chains filters for fast speeds above 2x`() {
        // 4x = 2x * 2x
        assertEquals("atempo=2.0,atempo=2.0", service.atempoChain(4.0f))
    }

    @Test
    fun `atempoChain chains filters for slow speeds below half`() {
        // 0.25x = 0.5x * 0.5x
        assertEquals("atempo=0.5,atempo=0.5", service.atempoChain(0.25f))
    }

    @Test
    fun `atempoChain rejects zero and negative speed`() {
        // Regression guard — pre-fix this looped forever and hung the render
        // coroutine indefinitely.
        assertFailsWith<IllegalArgumentException> { service.atempoChain(0.0f) }
        assertFailsWith<IllegalArgumentException> { service.atempoChain(-1.0f) }
    }

    // ── separationDirectives → ffmpeg command ────────────────────────────────

    @Test
    fun `buildFfmpegCommand wires directive stems into amix and mutes base`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-stems").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val stem0 = File(tmp, "s0.mp3").apply { writeText("x") }
        val stem1 = File(tmp, "s1.mp3").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 10_000,
            outputFile = out,
            separationDirectives = listOf(
                DirectiveWithStemFiles(
                    rangeStartMs = 1000,
                    rangeEndMs = 4000,
                    muteOriginalSegmentAudio = true,
                    stems = listOf(
                        DirectiveStem(file = stem0, volume = 1.0f),
                        DirectiveStem(file = stem1, volume = 0.5f),
                    ),
                ),
            ),
        )

        // stem 파일들이 -i 입력으로 추가됐는지
        assertTrue(cmd.contains(stem0.absolutePath), "stem0 not in inputs: $cmd")
        assertTrue(cmd.contains(stem1.absolutePath), "stem1 not in inputs: $cmd")

        // filter_complex 내용 점검
        val filterIdx = cmd.indexOf("-filter_complex")
        assertTrue(filterIdx >= 0)
        val filter = cmd[filterIdx + 1]

        // base mute (rangeStartMs=1000 → 1.0s, rangeEndMs=4000 → 4.0s)
        assertTrue(
            filter.contains("[0:a]volume=enable='gt(between(t,1.0,4.0),0)':volume=0[base_muted]"),
            "expected base mute filter not found in: $filter",
        )
        // stem filters with atrim+adelay+volume — sourceOffsetMs=0 default → atrim=0.0:3.0
        assertTrue(
            filter.contains("atrim=0.0:3.0,asetpts=PTS-STARTPTS,adelay=1000|1000,volume=1.0[stem_0_0]"),
            "expected stem_0_0 filter not found in: $filter",
        )
        assertTrue(
            filter.contains("atrim=0.0:3.0,asetpts=PTS-STARTPTS,adelay=1000|1000,volume=0.5[stem_0_1]"),
            "expected stem_0_1 filter not found in: $filter",
        )
        // stems and base_muted go into the final amix
        assertTrue(filter.contains("[base_muted][stem_0_0][stem_0_1]amix="), "amix wiring missing in: $filter")
    }

    // ── baseVolume (영상 stream-copy fast path 의 세그먼트 볼륨) ───────────────────

    @Test
    fun `buildFfmpegCommand applies baseVolume to base audio when no other audio`() {
        // Fidelity guard: fast path 에선 per-segment trim 이 생략되므로 segment volumeScale 이
        // baseVolume 으로 들어와 base 오디오에 그대로 적용돼야 한다 (누락되면 볼륨 편집이 사라짐).
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-basevol").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 10_000,
            outputFile = out,
            baseVolume = 0.5f,
        )
        val filter = cmd[cmd.indexOf("-filter_complex") + 1]
        assertTrue(filter.contains("[0:a]volume=0.5[base_vol]"), "baseVolume filter missing in: $filter")
        assertTrue(filter.contains("[base_vol]anull[aout]"), "base_vol not routed to aout in: $filter")
        // 영상은 stream-copy 유지
        assertTrue(cmd.containsInOrder("-c:v", "copy"), "video must be stream-copied: $cmd")
    }

    @Test
    fun `buildFfmpegCommand applies baseVolume before separation mute window`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-basevol-mute").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 10_000,
            outputFile = out,
            separationDirectives = listOf(
                DirectiveWithStemFiles(
                    rangeStartMs = 1000,
                    rangeEndMs = 4000,
                    muteOriginalSegmentAudio = true,
                    stems = emptyList(),
                ),
            ),
            baseVolume = 0.5f,
        )
        val filter = cmd[cmd.indexOf("-filter_complex") + 1]
        // volume 먼저, 그 결과([base_vol])에 mute 윈도우 적용
        assertTrue(filter.contains("[0:a]volume=0.5[base_vol]"), "baseVolume filter missing in: $filter")
        assertTrue(
            filter.contains("[base_vol]volume=enable='gt(between(t,1.0,4.0),0)':volume=0[base_muted]"),
            "mute must chain off base_vol in: $filter",
        )
    }

    @Test
    fun `buildFfmpegCommand with default baseVolume leaves base untouched`() {
        // 하위호환 guard: 기존 호출자(baseVolume 미지정)는 [0:a] 를 그대로 써야 한다 — base_vol 라벨 없음.
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-basevol-default").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 10_000,
            outputFile = out,
        )
        val filter = cmd[cmd.indexOf("-filter_complex") + 1]
        assertFalse(filter.contains("base_vol"), "default baseVolume must not inject a volume filter: $filter")
        assertTrue(filter.contains("[0:a]anull[aout]"), "expected passthrough base in: $filter")
    }

    private fun List<String>.containsInOrder(a: String, b: String): Boolean {
        val i = indexOf(a)
        return i >= 0 && i + 1 < size && this[i + 1] == b
    }

    @Test
    fun `buildFfmpegCommand amix uses normalize=0 and alimiter for separation mix`() {
        // Regression guard: separation directive 구간에서 [base_muted] 는 silence 인데 amix 의 default
        // normalize=1 은 그 input 까지 N 으로 카운트해 stem 들을 1/(N+1) 로 나눠 사용자가 "분리된
        // stem 이 원본보다 작게 들림" 으로 체감하는 직접 원인이었다. normalize=0 합산 + alimiter 로
        // 천장 squash 가 운영 정책.
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-norm").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val stem = File(tmp, "s.mp3").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 5_000,
            outputFile = out,
            separationDirectives = listOf(
                DirectiveWithStemFiles(
                    rangeStartMs = 0,
                    rangeEndMs = 5_000,
                    muteOriginalSegmentAudio = true,
                    stems = listOf(DirectiveStem(file = stem, volume = 1.0f)),
                ),
            ),
        )

        val filter = cmd[cmd.indexOf("-filter_complex") + 1]
        assertTrue(filter.contains("normalize=0"), "amix must specify normalize=0: $filter")
        assertTrue(filter.contains("alimiter=limit="), "alimiter must follow amix: $filter")
        // 최종 -map 은 limiter 출력을 가리켜야 함.
        assertTrue(cmd.containsAll(listOf("-map", "[aout]")), "-map must point to [aout]: $cmd")
    }

    /**
     * 모바일이 영상 range delete 로 directive 를 split 한 경우, 뒤쪽 piece 는 `sourceOffsetMs > 0` 으로
     * 같은 stem audio 파일의 중간부터 재생되어야 한다. ffmpeg `atrim` 의 시작점이 sourceOffsetMs/1000
     * 으로 옮겨졌는지 검증.
     */
    @Test
    fun `buildFfmpegCommand atrim uses sourceOffsetMs for split directive`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-stems-split").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val stem = File(tmp, "s.mp3").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        // split 뒤쪽 piece — composition 의 [4s, 7s] 를 차지하지만 stem audio 의 [10s, 13s] 를 재생.
        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 20_000,
            outputFile = out,
            separationDirectives = listOf(
                DirectiveWithStemFiles(
                    rangeStartMs = 4_000,
                    rangeEndMs = 7_000,
                    muteOriginalSegmentAudio = true,
                    stems = listOf(DirectiveStem(file = stem, volume = 1.0f)),
                    sourceOffsetMs = 10_000,
                ),
            ),
        )

        val filterIdx = cmd.indexOf("-filter_complex")
        val filter = cmd[filterIdx + 1]
        // atrim 의 시작점 = sourceOffsetMs/1000 = 10.0, 끝점 = (sourceOffsetMs+rangeMs)/1000 = 13.0.
        // adelay 는 composition 좌표라 rangeStartMs (4000) 그대로.
        assertTrue(
            filter.contains("atrim=10.0:13.0,asetpts=PTS-STARTPTS,adelay=4000|4000,volume=1.0[stem_0_0]"),
            "expected split-piece atrim with sourceOffsetMs not found in: $filter",
        )
    }

    /**
     * Regression: 한글 파일명을 가진 stem 이 ffmpeg 입력 인자로 그대로 전달되는지
     * (escape 안 깨지는지). ProcessBuilder 는 인자 array 형태로 spawn 하므로 shell
     * escape 가 필요 없음.
     */
    @Test
    fun `buildFfmpegCommand passes Korean filename as -i input unchanged`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-korean").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val stem = File(tmp, "화자 1.wav").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 5_000,
            outputFile = out,
            separationDirectives = listOf(
                DirectiveWithStemFiles(
                    rangeStartMs = 0,
                    rangeEndMs = 5_000,
                    muteOriginalSegmentAudio = false,
                    stems = listOf(DirectiveStem(file = stem, volume = 1.0f)),
                ),
            ),
        )
        // 한글 파일명이 그대로 -i 인자에 포함되어야 함 (ProcessBuilder array 인자라
        // shell escape 불필요).
        assertTrue(
            cmd.contains(stem.absolutePath),
            "Korean filename must be passed verbatim, got: $cmd",
        )
        assertTrue(stem.absolutePath.contains("화자"), "test fixture must include Korean")
    }

    /**
     * 속도 조절된 구간의 stem: 클라이언트가 rangeStart/End 를 (속도 반영된) 압축 타임라인 위치로 보낸다.
     * stem audio 는 원본 tempo 라, 압축 슬롯 rangeMs(=2000) 에 들어갈 원본 구간은 rangeMs*speed(=4000) —
     * atrim 이 그만큼 떼어내고(0.0:4.0, 압축 슬롯 길이 0.0:2.0 이 아님), atempo=2.0 으로 다시 압축해야
     * 영상과 tempo 가 맞는다. atempo 는 asetpts 후 adelay 전에 와야 함(adelay 는 압축된 최종 위치).
     */
    @Test
    fun `buildFfmpegCommand applies atempo and extends atrim span for stem speed`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-stem-speed").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val stem = File(tmp, "s.mp3").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 10_000,
            outputFile = out,
            separationDirectives = listOf(
                DirectiveWithStemFiles(
                    rangeStartMs = 1_000,
                    rangeEndMs = 3_000,
                    muteOriginalSegmentAudio = true,
                    stems = listOf(DirectiveStem(file = stem, volume = 1.0f)),
                    appliedSpeedScale = 2.0f,
                ),
            ),
        )

        val filterIdx = cmd.indexOf("-filter_complex")
        val filter = cmd[filterIdx + 1]
        assertTrue(
            filter.contains(
                "atrim=0.0:4.0,asetpts=PTS-STARTPTS,atempo=2.0,adelay=1000|1000,volume=1.0[stem_0_0]",
            ),
            "expected span-extended atrim + atempo before adelay for stem speed, got: $filter",
        )
    }

    /**
     * Regression: speed=1.0(기본) 이면 atempo 미삽입 + atrim span 은 rangeMs 그대로 — 기존 렌더와
     * byte-identical 보장 (속도 미사용 프로젝트 회귀 0).
     */
    @Test
    fun `buildFfmpegCommand omits atempo when stem speed is 1`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-stem-nospeed").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val stem = File(tmp, "s.mp3").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 10_000,
            outputFile = out,
            separationDirectives = listOf(
                DirectiveWithStemFiles(
                    rangeStartMs = 1_000,
                    rangeEndMs = 3_000,
                    muteOriginalSegmentAudio = true,
                    stems = listOf(DirectiveStem(file = stem, volume = 1.0f)),
                    // appliedSpeedScale 기본 1.0f
                ),
            ),
        )

        val filterIdx = cmd.indexOf("-filter_complex")
        val filter = cmd[filterIdx + 1]
        assertTrue(
            filter.contains("atrim=0.0:2.0,asetpts=PTS-STARTPTS,adelay=1000|1000,volume=1.0[stem_0_0]"),
            "expected unchanged stem chain (no atempo, span=rangeMs), got: $filter",
        )
        assertTrue(!filter.contains("atempo"), "speed=1 must not insert atempo, got: $filter")
    }
}
