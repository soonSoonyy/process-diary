package io.github.soonsoonyy.processdiary.runs

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.project.Project
import io.github.soonsoonyy.processdiary.model.ProcInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * IDE 에서 Run/Debug 로 실행 중인 프로세스와 그 실행 설정.
 * 표에 "어떤 Run 설정으로 띄운 것인지" 보여주고, 멈출 때는 IDE 를 통해 멈추게 하는 데 쓴다.
 */
object RunRegistry {
    class Entry(val pid: Int, val name: String, val project: Project, val handler: ProcessHandler, val executorId: String, val startedAt: Long)

    private val byPid = ConcurrentHashMap<Int, Entry>()

    fun add(e: Entry) { byPid[e.pid] = e }

    fun remove(handler: ProcessHandler) { byPid.values.removeIf { it.handler === handler } }

    fun all(): Collection<Entry> = byPid.values.filter { !it.handler.isProcessTerminated && !it.project.isDisposed }

    /** 이 PID 가 Run 으로 띄운 바로 그 프로세스면 */
    fun root(pid: Int): Entry? = byPid[pid]?.takeIf { !it.handler.isProcessTerminated && !it.project.isDisposed }

    /** 이 프로세스나 그 부모 중에 Run 으로 띄운 것이 있으면 그 실행 (부모를 따라 올라가며 찾음) */
    fun find(pid: Int, procs: Map<Int, ProcInfo>): Entry? {
        if (byPid.isEmpty()) return null
        var cur: Int? = pid
        var guard = 0
        while (cur != null && cur > 1 && guard++ < 64) {
            root(cur)?.let { return it }
            val p = procs[cur] ?: return null
            cur = p.ppid.takeIf { it != cur }
        }
        return null
    }

    /** IDE 의 Run 창에서 이 실행의 탭을 앞으로 */
    fun showInRunWindow(e: Entry): Boolean {
        val manager = RunContentManager.getInstance(e.project)
        val descriptor = manager.allDescriptors.firstOrNull { it.processHandler === e.handler } ?: return false
        val executor = ExecutorRegistry.getInstance().getExecutorById(e.executorId) ?: return false
        manager.toFrontRunContent(executor, descriptor)
        return true
    }
}
