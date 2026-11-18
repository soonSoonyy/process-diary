package io.github.soonsoonyy.processdiary.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Java 프로세스 정보: 무엇을 실행하는지(메인 클래스/jar), 힙 사용량, Java 버전.
 *
 * JVM은 기본으로 임시 폴더의 `hsperfdata_<사용자>/<PID>` 파일에 성능 정보를 기록한다 (jps, jstat 이 읽는 파일).
 * 이 파일을 직접 읽으므로 JDK 도구나 attach 권한 없이 맥/윈도우/리눅스에서 똑같이 동작한다.
 * 파일이 없으면(-XX:-UsePerfData, 다른 사용자의 프로세스 등) 실행 명령에서 메인 클래스만 알아낸다.
 */
data class JvmInfo(
    /** 메인 클래스 전체 이름 또는 jar 경로 */
    val main: String,
    /** 표에 보일 짧은 이름 (예: ShopApplication, Gradle Daemon, app.jar) */
    val label: String,
    /** 설명 종류 (desc.java.<kind>) */
    val kind: String,
    val javaVersion: String = "",
    /** 힙 사용량/확보량 (바이트, 모르면 -1) */
    val heapUsed: Long = -1,
    val heapCommitted: Long = -1,
    /** 성능 정보 파일에서 읽은 자세한 값 (없으면 null) */
    val stats: JvmStats? = null,
)

/** JVM 이 성능 정보로 공개하는 값 한 시점 (모르는 값은 -1) */
data class JvmStats(
    val heapUsed: Long,
    val heapCommitted: Long,
    val heapMax: Long,
    val metaspaceUsed: Long,
    val threads: Long,
    val daemonThreads: Long,
    val peakThreads: Long,
    val classes: Long,
    /** 젊은 세대 GC / 전체(Full) GC / 그 밖의 GC 일시정지 횟수 */
    val youngGc: Long,
    val fullGc: Long,
    val otherGc: Long,
    /** GC 로 멈춘 시간 누적 (JVM 시계 단위) */
    val gcTicks: Long,
    /** JVM 시계 현재값과 초당 눈금 수 */
    val ticks: Long,
    val frequency: Long,
    val gcName: String,
    val lastGcCause: String,
    val vmName: String,
) {
    val gcCount: Long get() = listOf(youngGc, fullGc, otherGc).filter { it >= 0 }.sum()
    val gcTimeMs: Long get() = if (frequency > 0) gcTicks * 1000 / frequency else -1
}

object Jvm {
    private class Cached(val startTime: Long, val info: JvmInfo?)

    private val cache = ConcurrentHashMap<Int, Cached>()

    fun isJava(name: String): Boolean {
        val n = Describe.baseName(name).lowercase()
        return n == "java" || n == "javaw"
    }

    /**
     * Java 프로세스 정보 (아니면 null). 메인 클래스는 프로세스마다 한 번만 알아내고, 힙은 매번 새로 읽는다.
     * [args] 는 perf 파일이 없을 때만 호출된다.
     */
    fun info(pid: Int, startTime: Long, args: () -> List<String>): JvmInfo? {
        val perf = readPerf(pid)
        val c = cache[pid]
        val base = if (c != null && c.startTime == startTime) c.info else {
            val main = perf?.strings?.get("sun.rt.javaCommand")?.let(::mainFromJavaCommand) ?: mainFromArgs(safe(args))
            val made = main?.let { describe(it, perf?.strings?.get("java.property.java.version") ?: "", perf?.strings?.get("java.property.java.class.path") ?: "") }
            cache[pid] = Cached(startTime, made)
            made
        } ?: return null
        if (perf == null) return base
        return base.copy(heapUsed = perf.heapUsed, heapCommitted = perf.heapCommitted, stats = perf.stats())
    }

    /** 끝난 프로세스는 캐시에서 제거 */
    fun retain(alive: Set<Int>) { cache.keys.retainAll(alive) }

    private fun safe(f: () -> List<String>) = try { f() } catch (_: Throwable) { emptyList() }

    // ---------------------------------------------------------------- 메인 클래스 → 이름/종류
    private val KNOWN = listOf(
        "org.gradle.launcher.daemon.bootstrap.GradleDaemon" to ("Gradle Daemon" to "gradleDaemon"),
        "worker.org.gradle.process.internal.worker.GradleWorkerMain" to ("Gradle Worker" to "gradleWorker"),
        "org.gradle.process.internal.worker.GradleWorkerMain" to ("Gradle Worker" to "gradleWorker"),
        "org.gradle.wrapper.GradleWrapperMain" to ("Gradle" to "gradle"),
        "org.gradle.launcher.GradleMain" to ("Gradle" to "gradle"),
        "org.jetbrains.kotlin.daemon.KotlinCompileDaemon" to ("Kotlin Daemon" to "kotlinDaemon"),
        "com.intellij.idea.Main" to ("JetBrains IDE" to "ide"),
        "org.jetbrains.jps.cmdline.BuildMain" to ("IDE Build" to "jps"),
        "org.jetbrains.jps.cmdline.Launcher" to ("IDE Build" to "jps"),
        "org.codehaus.plexus.classworlds.launcher.Launcher" to ("Maven" to "maven"),
        "org.apache.catalina.startup.Bootstrap" to ("Tomcat" to "tomcat"),
        "org.elasticsearch.bootstrap.Elasticsearch" to ("Elasticsearch" to "server"),
        "kafka.Kafka" to ("Kafka" to "server"),
        "org.apache.zookeeper.server.quorum.QuorumPeerMain" to ("ZooKeeper" to "server"),
        "org.jetbrains.idea.maven.server.RemoteMavenServer36" to ("IDE Maven Server" to "ideHelper"),
        "org.jetbrains.idea.maven.server.RemoteMavenServer40" to ("IDE Maven Server" to "ideHelper"),
        "org.jetbrains.plugins.gradle.tooling.proxy.Main" to ("IDE Gradle Proxy" to "ideHelper"),
        "com.intellij.rt.junit.JUnitStarter" to ("JUnit tests" to "tests"),
        "org.junit.platform.console.ConsoleLauncher" to ("JUnit tests" to "tests"),
    ).toMap()

    fun describe(main: String, javaVersion: String = "", classPath: String = ""): JvmInfo {
        KNOWN[main]?.let { (label, kind) -> return JvmInfo(main, label, kind, javaVersion) }
        if (main.endsWith(".jar", ignoreCase = true) || main.endsWith(".war", ignoreCase = true)) {
            val file = main.substringAfterLast('/').substringAfterLast('\\')
            return JvmInfo(main, file, "jar", javaVersion)
        }
        if (main.startsWith("org.springframework.boot.loader.")) return JvmInfo(main, "Spring Boot", "spring", javaVersion)
        val simple = main.substringAfterLast('/').substringAfterLast('.').ifEmpty { main }
        val spring = "spring-boot" in classPath || "springframework" in classPath
        return JvmInfo(main, simple, if (spring) "spring" else "app", javaVersion)
    }

    /** sun.rt.javaCommand 값 ("com.example.App --port 8080" 또는 "build/libs/app.jar") 에서 첫 단어 */
    fun mainFromJavaCommand(cmd: String): String? = cmd.trim().takeIf { it.isNotEmpty() }?.let { c ->
        // jar 경로에 공백이 있을 수 있으므로 .jar 로 끝나는 부분까지 우선
        Regex("^(.+?\\.(?:jar|war))(\\s|$)", RegexOption.IGNORE_CASE).find(c)?.groupValues?.get(1) ?: c.substringBefore(' ')
    }

    /** 실행 명령의 인자 목록에서 메인 클래스/jar 찾기 (java 실행 파일 자체는 첫 번째) */
    fun mainFromArgs(args: List<String>): String? {
        val withValue = setOf("-cp", "-classpath", "--class-path", "-p", "--module-path", "--add-modules", "--add-opens", "--add-exports",
            "--add-reads", "--limit-modules", "--upgrade-module-path", "--patch-module", "--enable-native-access")
        var i = 1
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-jar" -> return args.getOrNull(i + 1)
                a == "-m" || a == "--module" -> return args.getOrNull(i + 1)?.substringAfter('/')
                a.startsWith("--module=") -> return a.substringAfter('=').substringAfter('/')
                a in withValue -> i++
                a.startsWith("-") -> {}
                a.startsWith("@") -> {} // 인자 파일
                else -> return a
            }
            i++
        }
        return null
    }

    // ---------------------------------------------------------------- hsperfdata 읽기
    class Perf(val strings: Map<String, String>, val heapUsed: Long, val heapCommitted: Long, val longs: Map<String, Long> = emptyMap()) {
        private fun l(k: String) = longs[k] ?: -1L

        /** 자세한 값으로 정리 */
        fun stats(): JvmStats {
            val genMax = longs.filterKeys { genMaxKey.matches(it) }.values
            // G1 등은 세대마다 "최대 = 힙 전체"로 같은 값을 보고하고, Parallel/Serial 은 세대별로 나눠 보고함
            val heapMax = when {
                genMax.isEmpty() -> -1
                genMax.distinct().size == 1 && genMax.size > 1 -> genMax.first()
                else -> genMax.sum()
            }
            val collectors = longs.keys.mapNotNull { collectorKey.find(it)?.groupValues?.get(1)?.toInt() }.distinct().sorted()
            fun inv(i: Int) = l("sun.gc.collector.$i.invocations")
            return JvmStats(
                heapUsed = heapUsed, heapCommitted = heapCommitted, heapMax = heapMax,
                metaspaceUsed = l("sun.gc.metaspace.used"),
                threads = l("java.threads.live"), daemonThreads = l("java.threads.daemon"), peakThreads = l("java.threads.livePeak"),
                // 공유 아카이브(CDS)에서 불러온 클래스는 따로 셈
                classes = if (l("java.cls.loadedClasses") < 0) -1 else
                    l("java.cls.loadedClasses") + maxOf(0, l("java.cls.sharedLoadedClasses")) - maxOf(0, l("java.cls.unloadedClasses")) - maxOf(0, l("java.cls.sharedUnloadedClasses")),
                youngGc = inv(0), fullGc = inv(1), otherGc = collectors.filter { it >= 2 }.sumOf { maxOf(0, inv(it)) },
                gcTicks = collectors.sumOf { maxOf(0, l("sun.gc.collector.$it.time")) },
                ticks = l("sun.os.hrt.ticks"), frequency = l("sun.os.hrt.frequency"),
                gcName = strings["sun.gc.policy.name"].orEmpty(), lastGcCause = strings["sun.gc.lastCause"].orEmpty(),
                vmName = strings["java.property.java.vm.name"].orEmpty(),
            )
        }
    }

    private val genMaxKey = Regex("^sun\\.gc\\.generation\\.\\d+\\.maxCapacity$")
    private val collectorKey = Regex("^sun\\.gc\\.collector\\.(\\d+)\\.time$")
    private val longPrefixes = listOf("sun.gc.", "java.threads.", "java.cls.", "sun.os.hrt.")

    /** 이 사용자의 JVM 들 (성능 정보 파일이 있는 PID). 같은 사용자 프로세스만 */
    fun pids(): List<Int> = perfDirs.flatMap { d -> d.list()?.mapNotNull { it.toIntOrNull() } ?: emptyList() }.distinct()
        .filter { ProcessHandle.of(it.toLong()).map { h -> h.isAlive }.orElse(false) }

    /** perf 정보만으로 짧은 이름 (경고 알림용) */
    fun labelOf(perf: Perf): String? = perf.strings["sun.rt.javaCommand"]?.let(::mainFromJavaCommand)?.let { describe(it).label }

    private val perfDirs: List<File> by lazy {
        val user = System.getProperty("user.name") ?: ""
        listOfNotNull(System.getProperty("java.io.tmpdir"), System.getenv("TMPDIR"), System.getenv("TEMP"), "/tmp")
            .map { File(it, "hsperfdata_$user") }
            .distinctBy { it.absolutePath.trimEnd('/', '\\') }
    }

    private val windows = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    fun readPerf(pid: Int): Perf? {
        // 윈도우: 파일은 JVM 이 잠가 두므로 같은 내용의 공유 메모리를 읽음
        if (windows) WinNative.perfData(pid)?.let { return parsePerf(it) }
        for (dir in perfDirs) {
            val f = File(dir, pid.toString())
            if (!f.isFile) continue
            val bytes = try { f.readBytes() } catch (_: Exception) { continue }
            return parsePerf(bytes)
        }
        return null
    }

    /** 진단용: 어느 폴더를 찾았고 왜 못 읽었는지 */
    fun perfDiagnostics(pid: Int): String = perfDirs.joinToString("; ") { dir ->
        val f = File(dir, pid.toString())
        val read = if (f.isFile) try { f.readBytes().size.toString() + " bytes" } catch (e: Exception) { "read error: $e" } else "missing"
        "${dir.path} (dir=${dir.isDirectory}, files=${dir.list()?.take(5)}) -> $read"
    }

    private val usedKey = Regex("^sun\\.gc\\.generation\\.\\d+\\.space\\.\\d+\\.used$")
    private val capacityKey = Regex("^sun\\.gc\\.generation\\.\\d+\\.capacity$")
    private val wantedStrings = setOf("sun.rt.javaCommand", "java.property.java.version", "java.property.java.class.path",
        "sun.gc.policy.name", "sun.gc.lastCause", "java.property.java.vm.name")

    /** PerfData 형식 (버전 2): 앞부분(prologue) 뒤에 이름-값 항목이 이어진다 */
    fun parsePerf(b: ByteArray): Perf? {
        if (b.size < 32) return null
        if (ByteBuffer.wrap(b, 0, 4).order(ByteOrder.BIG_ENDIAN).int != 0xcafec0c0.toInt()) return null
        val bb = ByteBuffer.wrap(b).order(if (b[4].toInt() == 0) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        if (b[5].toInt() != 2) return null
        val entryOffset = bb.getInt(24)
        val count = bb.getInt(28)
        val strings = HashMap<String, String>()
        val longs = HashMap<String, Long>()
        var used = 0L
        var committed = 0L
        var hasHeap = false
        var off = entryOffset
        var n = 0
        while (n++ < count && off in 0..(b.size - 20)) {
            val len = bb.getInt(off)
            if (len <= 0 || off + len > b.size) break
            val nameOff = bb.getInt(off + 4)
            val vecLen = bb.getInt(off + 8)
            val type = b[off + 12].toInt().toChar()
            val dataOff = bb.getInt(off + 16)
            val name = cString(b, off + nameOff, off + len)
            if (type == 'J' && vecLen == 0 && off + dataOff + 8 <= b.size) {
                if (longPrefixes.any { name.startsWith(it) }) longs[name] = bb.getLong(off + dataOff)
                when {
                    usedKey.matches(name) -> { used += bb.getLong(off + dataOff); hasHeap = true }
                    capacityKey.matches(name) -> committed += bb.getLong(off + dataOff)
                }
            } else if (type == 'B' && vecLen > 0 && name in wantedStrings) {
                strings[name] = cString(b, off + dataOff, minOf(off + dataOff + vecLen, b.size))
            }
            off += len
        }
        return Perf(strings, if (hasHeap) used else -1, if (hasHeap) committed else -1, longs)
    }

    private fun cString(b: ByteArray, from: Int, limit: Int): String {
        if (from !in b.indices) return ""
        var end = from
        while (end < limit && end < b.size && b[end].toInt() != 0) end++
        return String(b, from, end - from, Charsets.UTF_8)
    }
}
