package io.github.soonsoonyy.processdiary

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.soonsoonyy.processdiary.alerts.UsageAlerts
import io.github.soonsoonyy.processdiary.data.Actuator
import io.github.soonsoonyy.processdiary.data.Collector
import io.github.soonsoonyy.processdiary.data.Docker
import io.github.soonsoonyy.processdiary.data.Jvm
import io.github.soonsoonyy.processdiary.diary.DiaryLog
import io.github.soonsoonyy.processdiary.diary.HeapTrend
import io.github.soonsoonyy.processdiary.data.JvmStats
import io.github.soonsoonyy.processdiary.model.ProcDetail
import io.github.soonsoonyy.processdiary.model.ProcInfo
import io.github.soonsoonyy.processdiary.model.SystemStats
import io.github.soonsoonyy.processdiary.settings.ProcessDiarySettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 2초마다 시스템 사용량을 수집하는 앱 전체 공용 서비스.
 * 프로세스 목록은 보고 있는 화면(도구 창/팝업)이 있을 때만 수집해 IDE 부담을 줄인다.
 * 아래 상태 값들은 모두 EDT(UI 스레드)에서만 읽고 쓴다.
 */
@Service(Service.Level.APP)
class ProcessDiaryService : Disposable {
    companion object {
        const val HISTORY = 60          // 2초 × 60 = 최근 2분
        private val LOG = logger<ProcessDiaryService>()
        fun get(): ProcessDiaryService = service()
    }

    private class Subscriber(val needsProcesses: () -> Boolean, val onUpdate: () -> Unit)

    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val alerts = UsageAlerts()
    private val lock = ReentrantLock()
    private var future: ScheduledFuture<*>? = null
    private val collector: Collector? by lazy {
        try { Collector() } catch (e: Throwable) { LOG.warn("시스템 정보 수집기를 초기화하지 못했습니다", e); error = e.message ?: e.toString(); null }
    }

    val history = ArrayDeque<SystemStats>()
    var processes: List<ProcInfo> = emptyList(); private set
    var byPid: Map<Int, ProcInfo> = emptyMap(); private set
    /** 프로세스 목록을 마지막으로 수집한 시각 */
    @Volatile var processesAt = 0L; private set
    val cpuHistory = HashMap<Int, ArrayDeque<Double>>()
    val memHistory = HashMap<Int, ArrayDeque<Double>>()
    /** Java 프로세스별 최근 성능 값 (힙·GC 그래프, GC 경고) */
    val jvmHistory = HashMap<Int, ArrayDeque<JvmStats>>()
    /** Java 프로세스 짧은 이름 (경고 알림용) */
    val jvmLabels = HashMap<Int, String>()
    /** Java 프로세스별 힙 장기 추이 (최대 6시간) */
    val heapTrends = HashMap<Int, HeapTrend>()
    @Volatile var error: String? = null; private set
    val cores: Int get() = collector?.cores ?: Runtime.getRuntime().availableProcessors()
    val latest: SystemStats? get() = history.lastOrNull()

    fun subscribe(parent: Disposable, needsProcesses: () -> Boolean, onUpdate: () -> Unit) {
        val s = Subscriber(needsProcesses, onUpdate)
        subscribers += s
        Disposer.register(parent) { subscribers -= s }
        start()
    }

    /** 수집 시작 (CPU/메모리 경고를 위해 IDE 시작 때도 호출) */
    fun start() = synchronized(this) { if (future == null) schedule() }

    private fun schedule() {
        future = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::tick, 0, ProcessDiarySettings.get().intervalMs, TimeUnit.MILLISECONDS)
    }

    /** 설정에서 갱신 주기를 바꾸면 다시 예약 */
    fun reschedule() = synchronized(this) {
        if (future != null) { future?.cancel(false); schedule() }
    }

    /** 그래프가 보여주는 기간 (예: "최근 2분") */
    fun historyLabel(): String {
        val sec = HISTORY * ProcessDiarySettings.get().intervalMs / 1000
        return if (sec % 60 == 0L) PD.msg("history.minutes", sec / 60) else PD.msg("history.seconds", sec)
    }

    /** 다음 주기를 기다리지 않고 바로 한 번 수집 */
    fun requestNow() = AppExecutorUtil.getAppExecutorService().execute(::tick)

    private fun tick() {
        if ((subscribers.isEmpty() && !alerts.enabled()) || !lock.tryLock()) return
        try {
            val c = collector ?: return
            val sys = c.system()
            // 경고 기준을 넘은 동안에는 "가장 많이 쓰는 프로세스"를 알려주기 위해 목록도 수집
            val procs = if (alerts.watching || subscribers.any { it.needsProcesses() }) c.processes(sys.memTotal) else null
            // JVM 성능 정보 파일만 읽으므로 가벼움 (도구 창을 닫아도 GC 경고가 동작하도록)
            val jvms = if (procs != null || alerts.gcEnabled()) sampleJvms() else null
            ApplicationManager.getApplication().invokeLater({ apply(sys, procs, jvms) }, ModalityState.any())
        } catch (e: Throwable) {
            LOG.warn(e)
        } finally {
            lock.unlock()
        }
    }

    private class JvmSample(val stats: JvmStats, val label: String?)

    private fun sampleJvms(): Map<Int, JvmSample> = try {
        Jvm.pids().mapNotNull { pid -> Jvm.readPerf(pid)?.let { pid to JvmSample(it.stats(), Jvm.labelOf(it)) } }.toMap()
    } catch (e: Throwable) { LOG.debug(e); emptyMap() }

    /** 최근 [windowMs] 동안 GC 로 멈춘 시간 비율(%) (자료가 10초 미만이면 null) */
    fun gcPercent(pid: Int, windowMs: Long = Long.MAX_VALUE): Double? {
        val h = jvmHistory[pid] ?: return null
        val last = h.lastOrNull() ?: return null
        if (last.frequency <= 0) return null
        val first = h.firstOrNull { (last.ticks - it.ticks) * 1000 / last.frequency <= windowMs } ?: return null
        val span = last.ticks - first.ticks
        if (span * 1000 / last.frequency < 10_000) return null
        return ((last.gcTicks - first.gcTicks).coerceAtLeast(0) * 100.0 / span).coerceIn(0.0, 100.0)
    }

    /** 샘플 사이마다의 GC 시간 비율(%) (그래프용) */
    fun gcSeries(pid: Int): List<Double> {
        val h = jvmHistory[pid]?.toList() ?: return emptyList()
        return h.zipWithNext { a, b -> if (b.ticks > a.ticks) ((b.gcTicks - a.gcTicks).coerceAtLeast(0) * 100.0 / (b.ticks - a.ticks)).coerceIn(0.0, 100.0) else 0.0 }
    }

    private fun apply(sys: SystemStats, procs: List<ProcInfo>?, jvms: Map<Int, JvmSample>? = null) {
        history.addLast(sys)
        while (history.size > HISTORY) history.removeFirst()
        if (procs != null) {
            processes = procs
            processesAt = sys.time
            byPid = procs.associateBy { it.pid }
            for (p in procs) {
                cpuHistory.getOrPut(p.pid) { ArrayDeque() }.also { it.addLast(p.cpu); if (it.size > HISTORY) it.removeFirst() }
                memHistory.getOrPut(p.pid) { ArrayDeque() }.also { it.addLast(p.rssBytes.toDouble()); if (it.size > HISTORY) it.removeFirst() }
            }
            cpuHistory.keys.retainAll(byPid.keys)
            memHistory.keys.retainAll(byPid.keys)
        }
        val trendOn = ProcessDiarySettings.get().state.heapTrend
        if (!trendOn) heapTrends.clear()
        if (jvms != null) {
            for ((pid, j) in jvms) {
                jvmHistory.getOrPut(pid) { ArrayDeque() }.also { it.addLast(j.stats); if (it.size > HISTORY) it.removeFirst() }
                j.label?.let { jvmLabels[pid] = it }
                if (!trendOn) continue
                val trend = heapTrends.getOrPut(pid) { HeapTrend() }
                trend.add(sys.time, j.stats.heapUsed)
                // 누수 의심은 다이어리에 한 번만 기록
                if (!trend.reported) trend.suspicious(j.stats.heapMax)?.let { r ->
                    trend.reported = true
                    DiaryLog.add(DiaryLog.Kind.TREND, PD.msg("diary.trend", (j.label ?: "java") + " (PID $pid)",
                        io.github.soonsoonyy.processdiary.ui.Fmt.mem(r.bytesPerHour.toLong()), io.github.soonsoonyy.processdiary.ui.Fmt.uptime(r.minutes * 60_000)), pid = pid)
                }
            }
            heapTrends.keys.retainAll(jvms.keys)
            jvmHistory.keys.retainAll(jvms.keys)
            jvmLabels.keys.retainAll(jvms.keys)
        }
        for (s in subscribers) {
            try { s.onUpdate() } catch (e: Throwable) { LOG.warn(e) }
        }
        try { alerts.check(sys, if (procs != null) processes else emptyList(), cores) } catch (e: Throwable) { LOG.warn(e) }
        if (jvms != null) try { alerts.checkGc(sys.time, jvmHistory.keys.toList(), ::gcPercent) { jvmLabels[it] } } catch (e: Throwable) { LOG.warn(e) }
    }

    /** 설정이 바뀌면 열려 있는 화면을 바로 다시 그림 */
    fun settingsChanged() {
        ApplicationManager.getApplication().invokeLater({
            if (!ProcessDiarySettings.get().state.heapTrend) heapTrends.clear()
            for (s in subscribers) try { s.onUpdate() } catch (e: Throwable) { LOG.warn(e) }
        }, ModalityState.any())
    }

    /** 포트를 쓰는 프로세스 PID (백그라운드 스레드에서 호출) */
    fun listenersOnPort(port: Int): List<Int> = try { collector?.listenersOnPort(port) ?: emptyList() } catch (e: Throwable) { LOG.debug(e); emptyList() }

    /** 현재 프로세스 목록을 바로 조회 (백그라운드 스레드에서 호출) */
    fun quickList(): List<ProcInfo> = try { collector?.quickList() ?: emptyList() } catch (e: Throwable) { LOG.debug(e); emptyList() }

    /** 선택한 프로세스 상세 정보 (백그라운드에서 조회 후 EDT로 전달) */
    fun loadDetail(pid: Int, onLoaded: (ProcDetail?) -> Unit) {
        AppExecutorUtil.getAppExecutorService().execute {
            var d = try { collector?.details(pid) } catch (e: Throwable) { LOG.debug(e); null }
            // Docker가 대신 열어 둔 포트면 어떤 컨테이너인지 함께
            val p = byPid[pid]
            if (d != null && p != null && p.ports.isNotEmpty() && Docker.isDockerProcess(p.name)) {
                val cs = try { (Docker.list(all = false) as? Docker.Result.Ok)?.containers.orEmpty() } catch (e: Throwable) { LOG.debug(e); emptyList() }
                d = d.copy(containers = cs.filter { c -> c.hostPorts.any { it in p.ports } })
            }
            // Java 서버면 Spring Boot Actuator 확인 (처음엔 시간이 걸릴 수 있으므로 상세 정보를 먼저 보여준 뒤 다시 전달)
            if (d != null && p != null && p.jvm != null && p.ports.isNotEmpty() && ProcessDiarySettings.get().state.actuator) {
                val (known, info) = Actuator.cached(pid, p.ports)
                if (known) d = d.copy(actuator = info)
                else {
                    val first = d
                    ApplicationManager.getApplication().invokeLater({ onLoaded(first) }, ModalityState.any())
                    d = d.copy(actuator = try { Actuator.probe(pid, p.ports) } catch (e: Throwable) { LOG.debug(e); null })
                }
            }
            val result = d
            ApplicationManager.getApplication().invokeLater({ onLoaded(result) }, ModalityState.any())
        }
    }

    override fun dispose() {
        future?.cancel(false)
    }
}
