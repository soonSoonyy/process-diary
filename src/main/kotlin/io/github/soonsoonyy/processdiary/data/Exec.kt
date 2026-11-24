package io.github.soonsoonyy.processdiary.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 외부 명령(lsof, docker 등)을 시간 제한 안에서 실행한다.
 *
 * - 표준 출력은 별도 스레드에서 읽는다: 읽기가 끝나기를 기다리다 제한 시간이 무시되는 일이 없도록
 * - 표준 에러는 버린다: 읽지 않은 에러 출력이 파이프 버퍼를 채워 명령이 멈추는 일이 없도록
 * - 제한 시간이 지나면 명령(과 하위 프로세스)을 강제 종료하고 빈 결과를 돌려준다
 */
object Exec {
    data class Result(val output: String, val exitCode: Int?, val timedOut: Boolean) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    private val NOT_STARTED = Result("", null, false)

    /** 실행 결과. 시작하지 못하면 exitCode=null, 시간 초과면 timedOut=true (출력은 비움) */
    fun run(cmd: List<String>, timeoutMs: Long = 5000): Result {
        val pr = try {
            ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: Exception) {
            return NOT_STARTED
        }
        try { pr.outputStream.close() } catch (_: IOException) {} // 입력을 기다리는 명령이 멈추지 않도록

        val out = ByteArrayOutputStream()
        val reader = Thread({
            try { pr.inputStream.use { it.copyTo(out) } } catch (_: IOException) {}
        }, "ProcessDiary-exec-reader").apply { isDaemon = true; start() }

        val finished = try { pr.waitFor(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        if (!finished) {
            try { pr.descendants().forEach { it.destroyForcibly() } } catch (_: Exception) {}
            pr.destroyForcibly()
        }
        // 출력을 물려받은 하위 프로세스가 남아 있으면 읽기가 끝나지 않을 수 있으므로 오래 기다리지 않음
        try { reader.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (reader.isAlive) try { pr.inputStream.close() } catch (_: IOException) {}

        if (!finished) return Result("", null, true)
        return Result(out.toString(Charsets.UTF_8), pr.exitValue(), false)
    }

    /** 성공 여부와 관계없이 표준 출력만 (실패·시간 초과면 빈 문자열) */
    fun output(vararg cmd: String, timeoutMs: Long = 5000): String = run(cmd.toList(), timeoutMs).output
}
