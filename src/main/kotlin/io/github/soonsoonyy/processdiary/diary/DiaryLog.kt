package io.github.soonsoonyy.processdiary.diary

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process Diary 의 "일기": 실행 시작/종료, 포트 충돌 처리, 종료한 프로세스, 경고, Docker·Java 동작을 시간순으로 기록.
 * IDE 를 다시 켜도 남도록 저장하고 (이 컴퓨터에만), 최근 [MAX]개만 보관한다.
 */
@Service(Service.Level.APP)
@State(name = "ProcessDiaryLog", storages = [Storage(value = "processDiaryLog.xml", roamingType = RoamingType.DISABLED)])
class DiaryLog : SimplePersistentStateComponent<DiaryLog.Options>(Options()) {
    enum class Kind {
        RUN_START, RUN_END, PORT_CONFLICT, PORT_AUTO, PORT_FREED, LEFTOVER, STOP, ALERT, DOCKER, JAVA, TREND;

        /** 보기 필터 묶음 */
        val group: Group get() = when (this) {
            RUN_START, RUN_END -> Group.RUNS
            PORT_CONFLICT, PORT_AUTO, PORT_FREED -> Group.PORTS
            LEFTOVER, STOP -> Group.STOPS
            ALERT, TREND -> Group.ALERTS
            DOCKER, JAVA -> Group.TOOLS
        }
    }

    enum class Group { ALL, RUNS, PORTS, STOPS, ALERTS, TOOLS }

    data class Entry(val time: Long, val kind: Kind, val project: String, val pid: Int, val text: String) {
        fun encode() = listOf(time.toString(), kind.name, project.clean(), pid.toString(), text.clean()).joinToString("\t")

        companion object {
            private fun String.clean() = replace('\t', ' ').replace('\n', ' ')
            fun decode(s: String): Entry? {
                val f = s.split('\t', limit = 5)
                if (f.size < 5) return null
                return Entry(f[0].toLongOrNull() ?: return null, Kind.entries.firstOrNull { it.name == f[1] } ?: return null, f[2], f[3].toIntOrNull() ?: 0, f[4])
            }
        }
    }

    class Options : BaseState() {
        var entries by list<String>()
    }

    companion object {
        const val MAX = 2000
        fun get(): DiaryLog = service()

        /** 어디서든 한 줄로 기록 (프로젝트를 모르면 null) */
        fun add(kind: Kind, text: String, project: Project? = null, pid: Int = 0) =
            try { get().add(Entry(System.currentTimeMillis(), kind, project?.name.orEmpty(), pid, text)) } catch (_: Throwable) {}
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun add(e: Entry) {
        synchronized(this) {
            val list = state.entries
            list += e.encode()
            if (list.size > MAX) list.subList(0, list.size - MAX).clear()
            state.intIncrementModificationCount()
        }
        listeners.forEach { try { it() } catch (_: Throwable) {} }
    }

    /** 최근 것부터 */
    fun entries(): List<Entry> = synchronized(this) { state.entries.mapNotNull(Entry::decode) }.asReversed()

    fun clear() {
        synchronized(this) { state.entries.clear(); state.intIncrementModificationCount() }
        listeners.forEach { try { it() } catch (_: Throwable) {} }
    }
}
