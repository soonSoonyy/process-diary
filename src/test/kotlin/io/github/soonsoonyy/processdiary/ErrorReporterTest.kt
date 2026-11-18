package io.github.soonsoonyy.processdiary

import io.github.soonsoonyy.processdiary.update.GitHubErrorReporter
import junit.framework.TestCase
import java.net.URLDecoder

class ErrorReporterTest : TestCase() {
    private fun decode(url: String) = URLDecoder.decode(url.replace("%20", "+"), Charsets.UTF_8)

    fun testIssueUrl() {
        val trace = "java.lang.IllegalStateException: boom & bust\n\tat a.B.c(B.kt:10)"
        val url = GitHubErrorReporter.issueUrl(listOf(trace), "boom", "clicked Stop", "1.3.0", "IntelliJ IDEA 2025.2", "Mac OS X 15")
        assertTrue(url.startsWith("https://github.com/soonSoonyy/process-diary/issues/new?labels=bug&title="))
        val text = decode(url)
        assertTrue(text, text.contains("title=Error: java.lang.IllegalStateException: boom & bust"))
        assertTrue(text.contains("clicked Stop"))
        assertTrue(text.contains("Process Diary 1.3.0"))
        assertTrue(text.contains("at a.B.c(B.kt:10)"))
        assertFalse("spaces are %20, not +", url.contains("+"))
    }

    fun testLongTraceIsTruncated() {
        val trace = "java.lang.RuntimeException: x\n" + (1..3000).joinToString("\n") { "\tat pkg.Cls.method$it(Cls.kt:$it)" }
        val url = GitHubErrorReporter.issueUrl(listOf(trace), null, null, "1.3.0", "IDE", "OS")
        assertTrue("length ${url.length}", url.length <= 7500)
        assertTrue(decode(url).contains("(truncated)"))
        assertTrue(decode(url).contains("java.lang.RuntimeException: x"))
    }
}
