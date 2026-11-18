package io.github.soonsoonyy.processdiary

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.soonsoonyy.processdiary.model.Category
import io.github.soonsoonyy.processdiary.model.ProcInfo
import io.github.soonsoonyy.processdiary.runs.RunRegistry

/** IDE 실행과 프로세스 연결, "종료"가 IDE 를 통해 멈추는지 */
class RunRegistryTest : BasePlatformTestCase() {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private fun info(pid: Int, ppid: Int) = ProcInfo(pid, ppid, "x", "", "", 0.0, 0, 0.0, 0, 0, "", Category.OTHER, emptyList())

    fun testLinkAndStopThroughIde() {
        val cmd = if (windows) GeneralCommandLine("ping", "-n", "120", "127.0.0.1") else GeneralCommandLine("sleep", "120")
        val handler = OSProcessHandler(cmd)
        handler.startNotify()
        val pid = handler.process.pid().toInt()
        RunRegistry.add(RunRegistry.Entry(pid, "Dev server", project, handler, "Run", System.currentTimeMillis()))
        try {
            assertEquals("Dev server", RunRegistry.root(pid)?.name)
            // 하위의 하위 프로세스도 같은 실행으로
            val procs = mapOf(pid to info(pid, 1), 50001 to info(50001, pid), 50002 to info(50002, 50001), 50003 to info(50003, 1))
            assertEquals("Dev server", RunRegistry.find(50002, procs)?.name)
            assertNull("unrelated", RunRegistry.find(50003, procs))
            assertNull("unknown", RunRegistry.find(50004, procs))

            val r = ProcessActions.killNow(listOf(pid), force = false, tree = true)
            assertEquals("failures: ${r.failed}", 1, r.done)
            assertTrue("run stopped through the IDE handler", handler.waitFor(10_000))
            assertNull("terminated runs are not reported", RunRegistry.root(pid))
        } finally {
            RunRegistry.remove(handler)
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }
        assertTrue(RunRegistry.all().none { it.handler === handler })
    }
}
