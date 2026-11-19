package io.github.soonsoonyy.processdiary.settings

import io.github.soonsoonyy.processdiary.PD
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** 상태 표시줄에 무엇을 보여줄지 */
enum class StatusFormat {
    // toString()을 바꾸지 않음: 설정 파일에는 이름(CPU_MEM 등)으로 저장돼야 다시 읽을 수 있음
    CPU_MEM, CPU, NET, ALL;

    val label: String get() = PD.msg("status.format.${name.lowercase()}")
}

/** 앱 전체 설정 (Settings → Tools → Process Diary) */
@Service(Service.Level.APP)
@State(name = "ProcessDiarySettings", storages = [Storage("processDiary.xml")])
class ProcessDiarySettings : SimplePersistentStateComponent<ProcessDiarySettings.Options>(Options()) {
    class Options : BaseState() {
        /** 갱신 주기(초) */
        var intervalSec by property(2)
        var statusFormat by enum(StatusFormat.CPU_MEM)
        /** 처음 열 때의 보기 범위 (ProcessPanel.Scope 이름) */
        var defaultScope by string("MINE")
        var watchPortConflicts by property(true)
        var watchLeftovers by property(true)
        /** 포트 충돌이 이 프로젝트에서 남겨진 프로세스 때문이면 묻지 않고 정리 후 다시 실행 */
        var autoResolvePorts by property(true)
        /** "자동 업데이트 켜기" 알림을 이미 보여줬는지 */
        var updatePrompted by property(false)
        /** 화면 언어: AUTO(IDE 언어) / KO / EN */
        var language by string("AUTO")
        /** CPU/메모리 사용량 경고 */
        var alertCpu by property(true)
        var alertCpuPct by property(90)
        var alertMem by property(true)
        var alertMemPct by property(90)
        /** Java 프로세스가 GC 에 쓰는 시간 비율 경고 */
        var alertGc by property(true)
        var alertGcPct by property(25)
        /** 이 시간(초) 넘게 계속되면 경고 */
        var alertSec by property(60)
        /** docker 실행 파일 경로 (비우면 자동으로 찾음) */
        var dockerPath by string("")
        /** IDE 실행: 표에 Run 설정 이름 표시 / 종료는 IDE 를 통해 */
        var showRunNames by property(true)
        var stopViaIde by property(true)
        /** Java: Spring Boot Actuator 확인과 경로, 힙 장기 추이 */
        var actuator by property(true)
        var actuatorPaths by string("/actuator, /manage, /management")
        var heapTrend by property(true)
        /** 다이어리: 기록 여부, 종류별 기록, 최대 개수 */
        var diary by property(true)
        var diaryRuns by property(true)
        var diaryPorts by property(true)
        var diaryStops by property(true)
        var diaryAlerts by property(true)
        var diaryTools by property(true)
        var diaryMax by property(2000)
    }

    companion object {
        val INTERVALS = listOf(1, 2, 5)
        val ALERT_PCTS = listOf(80, 85, 90, 95)
        val ALERT_SECS = listOf(30, 60, 120, 300)
        val GC_PCTS = listOf(10, 25, 50)
        val DIARY_MAX = listOf(500, 1000, 2000, 5000)
        const val DEFAULT_ACTUATOR_PATHS = "/actuator, /manage, /management"

        /** "/actuator, manage" → ["/actuator", "/manage"] (최대 5개, 비면 기본값) */
        fun parsePaths(text: String?): List<String> =
            (text ?: "").split(',', ' ', ';').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
                .map { if (it.startsWith("/")) it else "/$it" }.distinct().take(5)
                .ifEmpty { parsePaths(DEFAULT_ACTUATOR_PATHS) }
        fun get(): ProcessDiarySettings = service()
    }

    val intervalMs: Long get() = (state.intervalSec.takeIf { it in INTERVALS } ?: 2) * 1000L
}
