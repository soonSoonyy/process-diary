package io.github.soonsoonyy.processdiary

import io.github.soonsoonyy.processdiary.data.Collector
import junit.framework.TestCase
import java.util.concurrent.TimeUnit

/** 하위 프로세스까지 종료하는 기능이 실제로 프로세스 트리를 모두 끝내는지 */
class KillTreeTest : TestCase() {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }
        return cond()
    }

    /** 부모 셸 아래 자식 2개를 띄우는 명령 */
    private fun treeCommand() = if (windows) listOf("powershell", "-NoProfile", "-Command",
        "Start-Process -NoNewWindow ping -ArgumentList '-n','300','127.0.0.1'; Start-Process -NoNewWindow ping -ArgumentList '-n','300','127.0.0.1'; Start-Sleep 300")
    else listOf("sh", "-c", "sleep 300 & sleep 300 & wait")

    fun testKillTree() {
        val parent = ProcessBuilder(treeCommand()).start()
        try {
            val pid = parent.pid().toInt()
            assertTrue("children started", waitUntil(10_000) { ProcessActions.descendantsOf(pid).size >= 2 })
            val kids = ProcessActions.descendantsOf(pid)

            val r = ProcessActions.killNow(listOf(pid), force = true, tree = true)
            assertTrue("failures: ${r.failed}", r.failed.isEmpty())
            assertEquals(kids.size + 1, r.done)
            assertTrue("parent ended", parent.waitFor(10, TimeUnit.SECONDS))
            assertTrue("children ended", waitUntil(10_000) { kids.none { ProcessHandle.of(it.toLong()).map { h -> h.isAlive }.orElse(false) } })
        } finally {
            parent.descendants().forEach { it.destroyForcibly() }
            parent.destroyForcibly()
        }
    }

    private fun startSleeper(): Process = ProcessBuilder(
        if (windows) listOf("ping", "-n", "300", "127.0.0.1") else listOf("sleep", "300")
    ).start()

    /** 목록에서 본 프로세스와 시작 시각이 다르면 (PID 재사용) 종료하지 않음 */
    fun testRefusesReusedPid() {
        val p = startSleeper()
        try {
            val pid = p.pid().toInt()
            val realStart = p.toHandle().info().startInstant().get().toEpochMilli()
            // 한 시간 전에 시작한 다른 프로세스를 목록에서 봤다고 가정
            val r = ProcessActions.killNow(listOf(pid), force = true, tree = true, expectedStart = mapOf(pid to realStart - 3_600_000))
            assertEquals(0, r.done)
            assertEquals(1, r.failed.size)
            Thread.sleep(300)
            assertTrue("must still be alive", p.isAlive)
        } finally {
            p.destroyForcibly()
        }
    }

    /** 수집기(OSHI)가 본 시작 시각과 JDK가 보는 시작 시각이 허용 오차 안에서 같아야 정상 종료가 됨 */
    fun testKillsWhenStartTimeMatchesCollector() {
        val p = startSleeper()
        try {
            val pid = p.pid().toInt()
            val seen = Collector().quickList().first { it.pid == pid }
            assertTrue("collector start time", seen.startTime > 0)
            val r = ProcessActions.killNow(listOf(pid), force = true, tree = false, expectedStart = ProcessActions.startTimes(listOf(seen)))
            assertTrue("failures: ${r.failed}", r.failed.isEmpty())
            assertEquals(1, r.done)
            assertTrue("ended", p.waitFor(10, TimeUnit.SECONDS))
        } finally {
            p.destroyForcibly()
        }
    }

    /** 알림의 한 번 클릭 종료는 하위에 있는 DB 서버 같은 중요한 프로세스를 건너뜀 */
    fun testSkipsCriticalChildren() {
        val parent = ProcessBuilder(treeCommand()).start()
        try {
            val pid = parent.pid().toInt()
            assertTrue("children started", waitUntil(10_000) { ProcessActions.descendantsOf(pid).size >= 2 })
            val kids = parent.descendants().toList()
            val critical = kids.first()
            // 이 자식이 postgres 라고 알려 줌 (실제 DB 서버를 띄우지 않고 판별 경로를 검사)
            val r = ProcessActions.killNow(listOf(pid), force = true, tree = true,
                names = mapOf(critical.pid().toInt() to "postgres"), skipCritical = true)
            assertEquals("failures: ${r.failed}", 1, r.failed.size)
            assertTrue(r.failed[0], r.failed[0].startsWith("postgres"))
            assertTrue("stopped parent and other children: ${r.done}", r.done >= 2) // 부모 + 나머지 자식
            assertTrue("parent ended", parent.waitFor(10, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertTrue("critical child kept", critical.isAlive)
        } finally {
            parent.descendants().forEach { it.destroyForcibly() }
            parent.destroyForcibly()
        }
    }

    fun testProtectsIdeItself() {
        val self = ProcessHandle.current().pid().toInt()
        val r = ProcessActions.killNow(listOf(self), force = false, tree = false)
        assertEquals(0, r.done)
        assertEquals(1, r.failed.size)
    }
}
