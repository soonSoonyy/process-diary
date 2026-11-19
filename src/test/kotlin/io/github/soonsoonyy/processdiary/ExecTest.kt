package io.github.soonsoonyy.processdiary

import io.github.soonsoonyy.processdiary.data.Exec
import junit.framework.TestCase

/** 외부 명령이 멈추거나 에러 출력을 많이 내도 시간 제한 안에 돌아오는지 */
class ExecTest : TestCase() {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    fun testReadsOutput() {
        val r = if (windows) Exec.run(listOf("cmd", "/c", "echo hello")) else Exec.run(listOf("sh", "-c", "echo hello"))
        assertTrue(r.ok)
        assertEquals("hello", r.output.trim())
    }

    fun testMissingCommand() {
        val r = Exec.run(listOf("process-diary-no-such-command-xyz"))
        assertFalse(r.ok)
        assertNull(r.exitCode)
        assertEquals("", r.output)
    }

    /** 출력 없이 멈춘 명령: 예전에는 읽기가 끝날 때까지 기다려 제한 시간이 무시됐음 */
    fun testTimeoutIsEnforced() {
        val cmd = if (windows) listOf("ping", "-n", "30", "127.0.0.1") else listOf("sh", "-c", "sleep 30")
        val started = System.currentTimeMillis()
        val r = Exec.run(cmd, timeoutMs = 700)
        val took = System.currentTimeMillis() - started
        assertTrue("timed out", r.timedOut)
        assertEquals("", r.output)
        assertTrue("returned in ${took}ms", took < 5000)
    }

    /** 에러 출력을 파이프 버퍼(수십 KB)보다 많이 내는 명령도 끝까지 실행됨 */
    fun testLargeStderrDoesNotBlock() {
        if (windows) return
        val r = Exec.run(listOf("sh", "-c", "i=0; while [ \$i -lt 4000 ]; do echo 'error line padding padding padding padding' 1>&2; i=\$((i+1)); done; echo done"), timeoutMs = 20_000)
        assertFalse("timed out", r.timedOut)
        assertEquals("done", r.output.trim())
    }
}
