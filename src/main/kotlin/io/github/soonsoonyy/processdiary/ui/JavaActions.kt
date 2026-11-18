package io.github.soonsoonyy.processdiary.ui

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.LightVirtualFile
import io.github.soonsoonyy.processdiary.PD
import io.github.soonsoonyy.processdiary.ProcessActions
import io.github.soonsoonyy.processdiary.ProcessDiaryService
import io.github.soonsoonyy.processdiary.data.JavaTools
import io.github.soonsoonyy.processdiary.data.Jvm
import io.github.soonsoonyy.processdiary.model.ProcInfo

/** 상세 패널의 Java 동작: 스레드 덤프 / 힙 덤프 / GC 실행 */
object JavaActions {
    private fun name(p: ProcInfo) = "${p.jvm?.label ?: p.name} (PID ${p.pid})"

    /** 스레드 덤프를 받아 편집기 탭에 읽기 전용으로 연다 */
    fun threadDump(project: Project, p: ProcInfo) {
        object : Task.Backgroundable(project, PD.msg("java.threadDump.progress", name(p)), true) {
            override fun run(indicator: ProgressIndicator) {
                val r = JavaTools.threadDump(p.pid, p.path)
                if (!r.ok) return fail(project, p, r.output)
                val time = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
                val header = "# ${PD.msg("java.threadDump.header", name(p), time)}\n\n"
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val file = LightVirtualFile("Thread dump - ${p.jvm?.label ?: p.name} ${p.pid} $time.txt", PlainTextFileType.INSTANCE, header + r.output)
                    file.isWritable = false
                    FileEditorManager.getInstance(project).openFile(file, true)
                }
            }
        }.queue()
    }

    /** 확인 후 힙 덤프 (.hprof) 를 다운로드 폴더에 저장 */
    fun heapDump(project: Project, p: ProcInfo) {
        val file = JavaTools.heapDumpFile(p.jvm?.label ?: p.name, p.pid)
        val used = p.jvm?.heapUsed?.takeIf { it > 0 }?.let { Fmt.mem(it) } ?: "?"
        if (Messages.showYesNoDialog(project, PD.msg("java.heapDump.confirm", name(p), used, file.path), PD.msg("java.heapDump"),
                PD.msg("java.heapDump"), PD.msg("common.cancel"), Messages.getQuestionIcon()) != Messages.YES) return
        object : Task.Backgroundable(project, PD.msg("java.heapDump.progress", name(p)), false) {
            override fun run(indicator: ProgressIndicator) {
                val r = JavaTools.heapDump(p.pid, p.path, file)
                if (!r.ok) return fail(project, p, r.output)
                NotificationGroupManager.getInstance().getNotificationGroup("Process Diary")
                    .createNotification(PD.msg("java.heapDump.done", StringUtil.escapeXmlEntities(name(p)), Fmt.mem(file.length()),
                        StringUtil.escapeXmlEntities(file.path)), NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) { RevealFileAction.openFile(file) })
                    .addAction(NotificationAction.createSimple(PD.msg("java.copyPath")) { ProcessActions.copy(file.path, project) })
                    .notify(project)
            }
        }.queue()
    }

    /** GC 를 한 번 실행하고 힙이 얼마나 줄었는지 알림 */
    fun runGc(project: Project, p: ProcInfo) {
        object : Task.Backgroundable(project, PD.msg("java.gc.progress", name(p)), false) {
            override fun run(indicator: ProgressIndicator) {
                val before = Jvm.readPerf(p.pid)?.heapUsed ?: -1
                val r = JavaTools.runGc(p.pid, p.path)
                if (!r.ok) return fail(project, p, r.output)
                val after = Jvm.readPerf(p.pid)?.heapUsed ?: -1
                val text = if (before > 0 && after >= 0) PD.msg("java.gc.done", StringUtil.escapeXmlEntities(name(p)), Fmt.mem(before), Fmt.mem(after))
                else PD.msg("java.gc.doneShort", StringUtil.escapeXmlEntities(name(p)))
                ProcessActions.notify(project, text, NotificationType.INFORMATION)
                ProcessDiaryService.get().requestNow()
            }
        }.queue()
    }

    private fun fail(project: Project, p: ProcInfo, output: String) {
        val reason = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200).orEmpty()
        ProcessActions.notify(project, PD.msg("java.failed", StringUtil.escapeXmlEntities(name(p))) + "<br>" +
            StringUtil.escapeXmlEntities(reason) + "<br>" + PD.msg("java.failed.hint"), NotificationType.ERROR)
    }
}
