package com.vibi.bff.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 영상/오디오 입력에서 mp3 추출. 음성분리 업로드 등에서 동일 패턴 — `-vn -c:a libmp3lame
 * -q:a <quality>` 만 파라미터로 받아 통합. timeout 도 caller 별로 다른 수치를 그대로 전달.
 */
internal suspend fun extractMp3(
    input: File,
    output: File,
    quality: Int = 5,
    timeoutMinutes: Long = 5,
    label: String = "ffmpeg extract-mp3 ${input.name}",
) {
    FfmpegRunner.run(
        listOf(
            "ffmpeg", "-y",
            "-i", input.absolutePath,
            "-vn",
            "-c:a", "libmp3lame",
            "-q:a", "$quality",
            output.absolutePath,
        ),
        label,
        timeoutMinutes = timeoutMinutes,
    )
}

/**
 * ffmpeg 프로세스가 non-zero exit 로 종료되었을 때 던져지는 전용 예외. caller 가
 * `e.exitCode` 로 분기 가능 — 기존엔 메시지 regex 로 추출했음.
 */
class FfmpegProcessException(
    val exitCode: Int,
    val label: String,
    val tailStderr: String,
) : RuntimeException("$label failed (exit=$exitCode):\nstderr=$tailStderr")

/**
 * Shared ffmpeg/ffprobe process runner. Drains stdout in a background thread
 * to avoid OS pipe buffer (~64KB) deadlock that occurs when callers use
 * `bufferedReader().readText()` after `waitFor()` — ffmpeg can stall waiting
 * for the reader before the parent reaches the readText call.
 *
 * 모든 public API 는 `suspend` + `Dispatchers.IO` 로 감싼다 — Process.waitFor 와
 * stdout drain 은 blocking I/O 라 Netty event loop 에서 직접 호출되면 안 됨.
 * caller (RenderService 등) 는 이미 suspend 컨텍스트라 그대로 호출 가능.
 */
object FfmpegRunner {
    private val log = LoggerFactory.getLogger("com.vibi.bff.service.FfmpegRunner")

    /**
     * Runs the given command with stderr merged into stdout, drains output on
     * a daemon thread, then waits up to [timeoutMinutes]. On non-zero exit or
     * timeout throws RuntimeException with the last 3KB of output.
     *
     * @return captured output (full, not truncated) for callers that want to log/parse it.
     */
    suspend fun run(
        cmd: List<String>,
        label: String,
        timeoutMinutes: Long = 10,
    ): String = withContext(Dispatchers.IO) {
        // stderr 별도 capture: ffmpeg 의 progress (`-progress pipe:1`) 는 stdout 으로
        // 가고 일반 로그/에러는 stderr. 분리하면 caller 가 stdout 만 parse 해서 progress
        // 추출 가능 (parser 가 비-progress 라인을 무시할 필요 X), stderr 는 실패 시
        // 컨텍스트 로그용으로만 사용.
        val process = ProcessBuilder(cmd).start()
        drainAndAwaitBlocking(process, label, timeoutMinutes)
    }

    /**
     * Suspending wrapper: 외부 caller 가 ProcessBuilder 직접 띄운 후 결과를 drain 하고
     * 싶을 때 사용. 내부적으로 Dispatchers.IO 격리.
     */
    suspend fun drainAndAwait(
        process: Process,
        label: String,
        timeoutMinutes: Long = 10,
    ): String = withContext(Dispatchers.IO) {
        drainAndAwaitBlocking(process, label, timeoutMinutes)
    }

    private const val OUTPUT_BUFFER_CAP = 64 * 1024
    private const val READER_JOIN_TIMEOUT_MS = 30_000L

    private fun drainAndAwaitBlocking(
        process: Process,
        label: String,
        timeoutMinutes: Long,
    ): String {
        // stdout/stderr 분리 drain. stdout 은 caller 의 parse 대상 (progress 등),
        // stderr 는 실패 컨텍스트용. 둘 다 별도 daemon 스레드에서 흘려보내야 ffmpeg 가
        // pipe-buffer 64KB 한도로 stall 하지 않는다. 누적은 OUTPUT_BUFFER_CAP 에서 cap —
        // 초과 시 head 유지하고 추가 라인은 skip (progress 라인 보존이 stderr tail 보다 중요).
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val outReader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach {
                    if (outBuf.length < OUTPUT_BUFFER_CAP) outBuf.appendLine(it)
                }
            }
        }.also { it.isDaemon = true; it.start() }
        val errReader = Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach {
                    if (errBuf.length < OUTPUT_BUFFER_CAP) errBuf.appendLine(it)
                }
            }
        }.also { it.isDaemon = true; it.start() }
        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        // process 가 끝나면 inputStream/errorStream 도 EOF 가 와 reader 가 자연 종료.
        // 명시적 close + bounded join — 무한 대기 시 stuck reader 가 ffmpeg 종료 후에도 main 을 막을 수 있음.
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        outReader.join(READER_JOIN_TIMEOUT_MS)
        errReader.join(READER_JOIN_TIMEOUT_MS)
        if (outReader.isAlive || errReader.isAlive) {
            process.destroyForcibly()
            log.warn("{} drain reader did not join within {}ms — destroying process", label, READER_JOIN_TIMEOUT_MS)
            throw FfmpegProcessException(
                exitCode = -1,
                label = label,
                tailStderr = errBuf.toString().takeLast(3000),
            )
        }
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException(
                "$label timed out after ${timeoutMinutes}min\n" +
                    "stderr=${errBuf.toString().takeLast(2000)}"
            )
        }
        val exit = process.exitValue()
        if (exit != 0) {
            throw FfmpegProcessException(
                exitCode = exit,
                label = label,
                tailStderr = errBuf.toString().takeLast(3000),
            )
        }
        return outBuf.toString()
    }
}
