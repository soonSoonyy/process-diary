package io.github.soonsoonyy.processdiary

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.github.soonsoonyy.processdiary.data.Critical
import io.github.soonsoonyy.processdiary.data.CriticalKind
import io.github.soonsoonyy.processdiary.model.ProcInfo
import java.awt.datatransfer.StringSelection

/** 종료, 복사, 브라우저 열기 같은 공용 동작 */
object ProcessActions {
    private val selfPid = ProcessHandle.current().pid().toInt()

    fun isProtected(pid: Int) = pid <= 1 || pid == selfPid

    /** 종료 결과 */
    data class KillResult(val done: Int, val failed: List<String>)

    /**
     * 목록에서 본 프로세스의 시작 시각(ms)과 OS가 보고하는 시작 시각의 허용 오차.
     * OSHI와 JDK가 부팅 시각을 서로 다른 방법으로 계산해(Linux) 1초 안팎 차이가 날 수 있다.
     */
    private const val START_TOLERANCE_MS = 2000L

    /** 하위 프로세스 PID (깊은 것부터: 자식을 먼저 종료해야 부모가 다시 띄우지 못함) */
    fun descendantsOf(pid: Int): List<Int> = try {
        ProcessHandle.of(pid.toLong()).map { h -> descendantsOf(h) }.orElse(emptyList()).map { it.pid().toInt() }
    } catch (_: Exception) { emptyList() }

    private fun descendantsOf(h: ProcessHandle): List<ProcessHandle> = try { h.descendants().toList().reversed() } catch (_: Exception) { emptyList() }

    /**
     * 이 핸들이 목록에서 본 그 프로세스인지 (PID 재사용 방지).
     * true = 같음, false = PID가 다른 프로세스에 재사용됨, null = 확인할 수 없음
     */
    fun isSameProcess(h: ProcessHandle, expectedStartMs: Long): Boolean? {
        if (expectedStartMs <= 0) return null
        val actual = try { h.info().startInstant().orElse(null) } catch (_: Exception) { null } ?: return null
        return kotlin.math.abs(actual.toEpochMilli() - expectedStartMs) <= START_TOLERANCE_MS
    }

    private fun nameOf(h: ProcessHandle?, pid: Int, names: Map<Int, String>): String = names[pid]
        ?: h?.let { runCatching { it.info().command().orElse(null) }.getOrNull() }?.substringAfterLast('/')?.substringAfterLast('\\')
        ?: "PID $pid"

    /**
     * 확인창 없이 바로 종료 (백그라운드 스레드에서 호출 가능).
     *
     * - tree=true 이면 하위 프로세스도 함께 종료한다.
     * - expectedStart(PID → 목록에서 본 시작 시각 ms)가 주어지면, 그 사이 PID가 다른 프로세스에 재사용됐을 때
     *   그 프로세스와 하위 프로세스를 건드리지 않는다. 종료는 확인한 핸들로 하므로
     *   확인과 종료 사이에 PID가 바뀌어도 JDK가 시작 시각을 다시 비교해 엉뚱한 프로세스를 종료하지 않는다.
     * - skipCritical=true 이면 Docker·DB 서버·IDE 처럼 다른 작업까지 망가뜨리는 프로세스는 건너뛴다
     *   (사용자 확인 없이 한 번에 종료하는 알림 버튼용).
     */
    fun killNow(
        pids: List<Int>, force: Boolean, tree: Boolean,
        names: Map<Int, String> = emptyMap(),
        expectedStart: Map<Int, Long> = emptyMap(),
        skipCritical: Boolean = false,
    ): KillResult {
        val failed = ArrayList<String>()
        val order = LinkedHashMap<Int, ProcessHandle?>()
        for (pid in pids) {
            val h = try { ProcessHandle.of(pid.toLong()).orElse(null) } catch (_: Exception) { null }
            val expected = expectedStart[pid]
            if (h != null && expected != null && isSameProcess(h, expected) == false) {
                failed += PD.msg("kill.fail.reused", nameOf(null, pid, names))
                continue // 재사용된 PID의 하위 프로세스도 건드리지 않음
            }
            if (tree && h != null) for (d in descendantsOf(h)) order.putIfAbsent(d.pid().toInt(), d)
            order.putIfAbsent(pid, h)
        }
        var done = 0
        for ((pid, h) in order) {
            val name = nameOf(h, pid, names)
            if (isProtected(pid)) {
                failed += PD.msg(if (pid == selfPid) "kill.fail.protected.ide" else "kill.fail.protected", name)
                continue
            }
            if (h == null || !h.isAlive) {
                if (pid in pids) failed += PD.msg("kill.fail.gone", name)
                continue
            }
            if (skipCritical) {
                val kind = Critical.kindOf(name, runCatching { h.info().command().orElse("") }.getOrDefault(""))
                if (kind != null) {
                    failed += PD.msg("kill.fail.critical", name, kind.label)
                    continue
                }
            }
            val ok = try { if (force) h.destroyForcibly() else h.destroy() } catch (_: Exception) { false }
            if (ok) done++ else failed += PD.msg("kill.fail.denied", name)
        }
        return KillResult(done, failed)
    }

    /** 종료 대상(하위 포함) 중 중요한 프로세스에 대한 경고 문구 */
    fun criticalWarnings(targets: List<ProcInfo>, tree: Boolean): List<String> {
        val found = LinkedHashMap<String, CriticalKind>()
        for (p in targets) {
            Critical.kindOf(p.name, p.path)?.let { found.putIfAbsent(p.name, it) }
            if (tree) for (d in descendantsOf(p.pid)) {
                val cmd = runCatching { ProcessHandle.of(d.toLong()).flatMap { it.info().command() }.orElse("") }.getOrDefault("")
                if (cmd.isEmpty()) continue
                val n = cmd.substringAfterLast('/').substringAfterLast('\\')
                Critical.kindOf(n, cmd)?.let { found.putIfAbsent(n, it) }
            }
        }
        return found.entries.take(3).map { (n, k) -> PD.msg("kill.confirm.critical.${k.name.lowercase()}", n) }
    }

    /** 확인창을 띄운 뒤 종료하고 결과를 알림으로 보여줌 */
    fun kill(project: Project?, targets: List<ProcInfo>, force: Boolean, tree: Boolean = false) {
        if (targets.isEmpty()) return
        val verb = PD.msg(if (force) "kill.verb.force" else "kill.verb")
        val childCount = if (tree) targets.sumOf { descendantsOf(it.pid).size } else 0
        val childText = if (childCount > 0) "\n" + PD.msg("kill.confirm.children", childCount) else ""
        val list = targets.take(10).joinToString("\n") { "• ${it.name} (PID ${it.pid})" } +
            (if (targets.size > 10) "\n" + PD.msg("kill.confirm.more", targets.size - 10) else "")
        val critical = criticalWarnings(targets, tree)
        val warn = (critical + PD.msg("kill.confirm.warning")).joinToString("\n")
        val msg = if (targets.size == 1) PD.msg("kill.confirm.one", targets[0].name, targets[0].pid, verb) + childText + "\n" + warn
        else PD.msg("kill.confirm.many", targets.size, verb) + childText + "\n\n" + list + "\n\n" + warn
        if (Messages.showYesNoDialog(project, msg, PD.msg("kill.confirm.title", verb), verb, PD.msg("common.cancel"), Messages.getWarningIcon()) != Messages.YES) return

        val r = killNow(targets.map { it.pid }, force, tree, targets.associate { it.pid to it.name },
            expectedStart = startTimes(targets))
        notifyResult(project, r)
        ProcessDiaryService.get().requestNow()
    }

    /** 목록에서 본 시작 시각 (PID 재사용 확인용) */
    fun startTimes(procs: Collection<ProcInfo>): Map<Int, Long> = procs.filter { it.startTime > 0 }.associate { it.pid to it.startTime }

    fun notifyResult(project: Project?, r: KillResult) {
        val text = buildString {
            append(PD.msg("kill.result.done", r.done))
            if (r.failed.isNotEmpty()) append("<br>" + PD.msg("kill.result.failed", r.failed.size) + "<br>" + r.failed.take(5).joinToString("<br>"))
        }
        notify(project, text, if (r.failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
    }

    /** 포트가 비워질 때까지 기다림 (최대 timeoutMs). 비면 true */
    fun waitPortFree(port: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        val service = ProcessDiaryService.get()
        while (System.currentTimeMillis() < end) {
            if (service.listenersOnPort(port).isEmpty()) return true
            Thread.sleep(300)
        }
        return service.listenersOnPort(port).isEmpty()
    }

    fun copy(text: String, project: Project?) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify(project, PD.msg("copy.done", text), NotificationType.INFORMATION)
    }

    fun openPort(port: Int) = BrowserUtil.browse("http://localhost:$port")

    fun notify(project: Project?, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Process Diary")
            .createNotification(text, type).notify(project)
    }
}
