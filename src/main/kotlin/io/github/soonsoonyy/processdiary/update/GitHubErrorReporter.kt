package io.github.soonsoonyy.processdiary.update

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import io.github.soonsoonyy.processdiary.PD
import java.awt.Component
import java.net.URLEncoder

/**
 * IDE 오류 창(빨간 느낌표)에서 이 플러그인의 오류를 GitHub 이슈로 보고.
 * 서버로 직접 보내지 않고, 내용을 채운 "새 이슈" 페이지를 브라우저로 열어 사용자가 확인 후 올리게 한다.
 */
class GitHubErrorReporter : ErrorReportSubmitter() {
    override fun getReportActionText(): String = PD.msg("error.report.action")

    override fun getPrivacyNoticeText(): String = PD.msg("error.report.privacy")

    override fun submit(events: Array<out IdeaLoggingEvent>, additionalInfo: String?, parentComponent: Component,
                        consumer: Consumer<in SubmittedReportInfo>): Boolean {
        val version = pluginDescriptor?.version ?: "?"
        val ide = ApplicationInfo.getInstance().let { "${it.fullApplicationName} (${it.build.asString()})" }
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"
        BrowserUtil.browse(issueUrl(events.map { it.throwableText ?: it.message.orEmpty() }, events.firstOrNull()?.message, additionalInfo, version, ide, os))
        consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        return true
    }

    companion object {
        const val PLUGIN_ID = "io.github.soonsoonyy.processdiary"
        const val REPO = "https://github.com/soonSoonyy/process-diary"
        /** 브라우저 주소 길이 제한을 넘지 않도록 */
        private const val MAX_URL = 7500

        fun issueUrl(traces: List<String>, message: String?, userNote: String?, version: String, ide: String, os: String): String {
            val first = traces.firstOrNull().orEmpty()
            // 제목: 예외 종류와 메시지 첫 줄 (경로나 사용자 이름이 섞이지 않게 짧게)
            val title = "Error: " + (first.lineSequence().firstOrNull()?.trim()?.take(120)?.ifEmpty { null } ?: message?.take(120) ?: "unknown")
            fun body(trace: String) = buildString {
                appendLine("**What were you doing?**")
                appendLine(userNote?.trim()?.ifEmpty { null } ?: "<!-- describe the steps here -->")
                appendLine()
                appendLine("**Environment**")
                appendLine("- Process Diary $version")
                appendLine("- $ide")
                appendLine("- $os")
                appendLine()
                appendLine("**Stack trace**")
                appendLine("```")
                appendLine(trace)
                appendLine("```")
            }
            fun url(body: String) = "$REPO/issues/new?labels=bug&title=${enc(title)}&body=${enc(body)}"
            var trace = traces.joinToString("\n\n").trim()
            var u = url(body(trace))
            // 너무 길면 스택 트레이스 뒷부분을 잘라냄
            while (u.length > MAX_URL && trace.length > 200) {
                trace = trace.take((trace.length * 0.7).toInt()) + "\n\t... (truncated)"
                u = url(body(trace))
            }
            return u
        }

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    }
}
