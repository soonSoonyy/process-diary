package io.github.soonsoonyy.processdiary

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.soonsoonyy.processdiary.diary.DiaryLog
import io.github.soonsoonyy.processdiary.diary.DiaryLog.Kind
import io.github.soonsoonyy.processdiary.diary.HeapTrend
import io.github.soonsoonyy.processdiary.ui.DiaryPanel

class DiaryTest : BasePlatformTestCase() {
    override fun setUp() { super.setUp(); DiaryLog.get().clear() }
    override fun tearDown() { try { DiaryLog.get().clear() } finally { super.tearDown() } }

    fun testLogKeepsOrderLimitAndSurvivesReload() {
        val log = DiaryLog.get()
        DiaryLog.add(Kind.RUN_START, "\"Dev server\" started\twith tab\nand newline", project, 42)
        DiaryLog.add(Kind.PORT_AUTO, "Port 3000 freed", project)
        val e = log.entries()
        assertEquals(listOf(Kind.PORT_AUTO, Kind.RUN_START), e.map { it.kind }) // 최근 것부터
        assertEquals("\"Dev server\" started with tab and newline", e[1].text)
        assertEquals(42, e[1].pid)
        assertEquals(project.name, e[1].project)

        // 저장 → 다시 읽기
        val saved = DiaryLog.Options().apply { entries = log.state.entries.toMutableList() }
        val other = DiaryLog()
        other.loadState(saved)
        assertEquals(e, other.entries())

        repeat(DiaryLog.MAX + 50) { DiaryLog.add(Kind.STOP, "Stopped $it") }
        assertEquals(DiaryLog.MAX, log.entries().size)
        assertEquals("Stopped ${DiaryLog.MAX + 49}", log.entries().first().text)
        assertNull(DiaryLog.Entry.decode("garbage"))
    }

    fun testPanelFilters() {
        DiaryLog.add(Kind.RUN_START, "mine started", project)
        DiaryLog.add(Kind.ALERT, "CPU high") // 프로젝트와 상관없는 기록
        DiaryLog.get().add(DiaryLog.Entry(System.currentTimeMillis(), Kind.RUN_START, "other-project", 0, "other started"))
        val panel = DiaryPanel(project)
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("this project + global", 2, panel.table.rowCount)
            panel.projectBox.isSelected = false
            panel.rebuild()
            assertEquals(3, panel.table.rowCount)
            panel.groupBox.selectedIndex = DiaryLog.Group.ALERTS.ordinal
            assertEquals(1, panel.table.rowCount)
            // 새 기록이 오면 바로 보임
            panel.groupBox.selectedIndex = DiaryLog.Group.ALL.ordinal
            DiaryLog.add(Kind.STOP, "Stopped node (PID 1)", project)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(4, panel.table.rowCount)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testHeapTrend() {
        val mb = 1048576L
        // 2시간 동안 GC 후 바닥이 시간당 60MB 씩 오름 (분 안에서는 오르내림)
        val leak = HeapTrend()
        for (m in 0 until 120) for (s in 0 until 6) leak.add(m * 60_000L + s * 10_000, 200 * mb + m * mb + (s % 3) * 50 * mb)
        assertEquals(120, leak.points.size)
        assertEquals("lowest value of each minute", 200 * mb, leak.points.first().second)
        val r = leak.suspicious(1024 * mb)!!
        assertEquals(60.0 * mb, r.bytesPerHour, 1.0 * mb)
        assertTrue(r.r2 > 0.99)

        // 오르내리기만 하는 정상 상태
        val steady = HeapTrend()
        for (m in 0 until 120) steady.add(m * 60_000L, 300 * mb + (if (m % 2 == 0) 40 else 0) * mb)
        assertNull(steady.suspicious(1024 * mb))
        // 짧은 기록은 판단하지 않음
        val short = HeapTrend()
        for (m in 0 until 30) short.add(m * 60_000L, 100 * mb + m * 10 * mb)
        assertNull(short.suspicious(1024 * mb))
        // 6시간만 보관
        val long = HeapTrend()
        for (m in 0 until 500) long.add(m * 60_000L, mb)
        assertEquals(360, long.points.size)
    }
}
