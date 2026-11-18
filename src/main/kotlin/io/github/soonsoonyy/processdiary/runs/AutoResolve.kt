package io.github.soonsoonyy.processdiary.runs

import io.github.soonsoonyy.processdiary.data.Docker
import io.github.soonsoonyy.processdiary.model.Category
import io.github.soonsoonyy.processdiary.model.Container
import io.github.soonsoonyy.processdiary.model.ProcInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * 포트 충돌을 사용자에게 묻지 않고 처리해도 되는지 판단하는 규칙.
 * "이 프로젝트에서 예전에 실행해 두고 잊은 것"이 확실할 때만 자동으로 처리하고, 조금이라도 애매하면 묻는다.
 */
object AutoResolve {
    /** 판단 결과 (자동 처리 불가면 이유) */
    sealed class Decision {
        data class Processes(val targets: List<ProcInfo>) : Decision()
        data class Containers(val targets: List<Container>) : Decision()
        data class Ask(val reason: String) : Decision()
    }

    /** 한 번에 자동으로 끝낼 수 있는 최대 개수 (그보다 많으면 묻기) */
    private const val MAX_TARGETS = 5

    /**
     * @param owners 포트를 쓰는 프로세스 (정보를 못 읽은 PID 는 null)
     * @param containers 포트를 게시한 Docker 컨테이너
     * @param ideTree 지금 IDE 가 실행 중인 프로세스들 (IDE 와 그 하위)
     * @param runStartedAt 이번 실행을 시작한 시각
     */
    fun decide(
        owners: List<ProcInfo?>,
        containers: List<Container>,
        projectPath: String?,
        me: String,
        ideTree: Set<Int>,
        protectedPids: (Int) -> Boolean,
        runStartedAt: Long,
    ): Decision {
        if (projectPath.isNullOrBlank()) return Decision.Ask("no project folder")
        if (containers.isNotEmpty()) {
            if (containers.size > MAX_TARGETS) return Decision.Ask("too many containers")
            val outside = containers.firstOrNull { it.composeDir.isEmpty() || !inside(it.composeDir, projectPath) }
            return if (outside == null) Decision.Containers(containers) else Decision.Ask("container ${outside.name} is not from this project")
        }
        if (owners.isEmpty()) return Decision.Ask("no owner found")
        if (owners.size > MAX_TARGETS) return Decision.Ask("too many processes")
        val list = ArrayList<ProcInfo>()
        for (p in owners) {
            p ?: return Decision.Ask("unknown process")
            val why = rejectReason(p, projectPath, me, ideTree, protectedPids, runStartedAt)
            if (why != null) return Decision.Ask(why)
            list += p
        }
        return Decision.Processes(list)
    }

    /** 이 프로세스를 자동으로 끝내면 안 되는 이유 (괜찮으면 null) */
    fun rejectReason(p: ProcInfo, projectPath: String, me: String, ideTree: Set<Int>, protectedPids: (Int) -> Boolean, runStartedAt: Long): String? = when {
        protectedPids(p.pid) -> "protected"
        p.pid in ideTree -> "still running from the IDE"
        !p.user.substringAfterLast('\\').equals(me.substringAfterLast('\\'), ignoreCase = true) -> "another user"
        p.category == Category.SYSTEM -> "system process"
        Docker.isDockerProcess(p.name) -> "docker process"
        p.cwd.isEmpty() || !inside(p.cwd, projectPath) -> "outside this project"
        p.startTime <= 0 || p.startTime >= runStartedAt -> "started after this run"
        else -> null
    }

    fun inside(dir: String, base: String): Boolean {
        val d = dir.replace('\\', '/').trimEnd('/')
        val b = base.replace('\\', '/').trimEnd('/')
        if (b.isEmpty()) return false
        val ignoreCase = d.length > 1 && d[1] == ':' // 윈도우 경로는 대소문자 무시
        return d.equals(b, ignoreCase) || d.startsWith("$b/", ignoreCase)
    }

    // ---------------------------------------------------------------- 반복 방지
    private val lastAuto = ConcurrentHashMap<String, Long>()

    /** 같은 실행 설정에서 1분 안에 또 충돌하면 자동 처리하지 않음 (계속 죽이고 다시 실행하는 반복 방지) */
    fun tryAcquire(key: String, now: Long = System.currentTimeMillis(), windowMs: Long = 60_000): Boolean {
        val last = lastAuto[key]
        if (last != null && now - last < windowMs) return false
        lastAuto[key] = now
        return true
    }

    fun reset() = lastAuto.clear()
}
