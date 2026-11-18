package io.github.soonsoonyy.processdiary.data

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * jcmd 로 실행 중인 JVM 에 명령 보내기 (스레드 덤프, 힙 덤프, GC).
 * 대상 JVM 과 같은 사용자여야 하고, 대상이 -XX:+DisableAttachMechanism 이면 안 된다.
 * 모든 함수는 시간이 걸리므로 백그라운드 스레드에서 호출한다.
 */
object JavaTools {
    data class Result(val ok: Boolean, val output: String)

    private val windows = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")
    private val exe = if (windows) "jcmd.exe" else "jcmd"

    /** jcmd 위치: 대상 JVM 과 같은 JDK → IDE 에 들어 있는 Java(JBR) 순 */
    fun jcmd(javaPath: String): String? {
        val candidates = buildList {
            if (javaPath.isNotEmpty()) File(javaPath).parentFile?.let { add(File(it, exe)) }
            add(File(System.getProperty("java.home"), "bin/$exe"))
        }
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.path
    }

    fun run(pid: Int, javaPath: String, timeoutSec: Long, vararg command: String): Result {
        val tool = jcmd(javaPath) ?: return Result(false, "jcmd not found")
        return try {
            val p = ProcessBuilder(listOf(tool, pid.toString()) + command).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return Result(false, "timeout")
            }
            reader.join(2000)
            // 환경 변수 안내("Picked up JAVA_TOOL_OPTIONS ...")와 jcmd 가 붙이는 "PID:" 줄은 빼고
            val text = out.toString().lineSequence()
                .filterNot { it.startsWith("Picked up JAVA_TOOL_OPTIONS") || it.startsWith("Picked up _JAVA_OPTIONS") || it.startsWith("Picked up JDK_JAVA_OPTIONS") }
                .dropWhile { it.isBlank() || it.trim() == "$pid:" }
                .joinToString("\n")
            // jcmd 는 붙지 못해도 종료 코드 0 을 줄 때가 있어 출력으로도 판단
            val failed = p.exitValue() != 0 || text.contains("AttachNotSupportedException") || text.contains("Unable to open socket file") ||
                text.contains("Could not find any processes") || text.lineSequence().any { it.startsWith("Exception") || it.startsWith("Error:") }
            Result(!failed, text.trim())
        } catch (e: Exception) {
            Result(false, e.message ?: e.toString())
        }
    }

    fun threadDump(pid: Int, javaPath: String): Result = run(pid, javaPath, 30, "Thread.print", "-l")

    /** 힙 덤프를 [file] 에 저장 (살아 있는 객체만: 먼저 GC 가 한 번 일어남) */
    fun heapDump(pid: Int, javaPath: String, file: File): Result {
        val r = run(pid, javaPath, 600, "GC.heap_dump", file.absolutePath)
        return if (r.ok && !file.isFile) Result(false, r.output.ifEmpty { "no file was written" }) else r
    }

    fun runGc(pid: Int, javaPath: String): Result = run(pid, javaPath, 60, "GC.run")

    /** 힙 덤프 저장 위치: 다운로드 폴더(없으면 홈) / 이름-PID-시각.hprof */
    fun heapDumpFile(label: String, pid: Int): File {
        val home = File(System.getProperty("user.home"))
        val dir = File(home, "Downloads").takeIf { it.isDirectory } ?: home
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val time = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
        return File(dir, "$safe-$pid-$time.hprof")
    }
}
