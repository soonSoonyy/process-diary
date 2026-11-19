package io.github.soonsoonyy.processdiary.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import io.github.soonsoonyy.processdiary.PD
import io.github.soonsoonyy.processdiary.ProcessDiaryService
import io.github.soonsoonyy.processdiary.data.Actuator
import io.github.soonsoonyy.processdiary.data.Docker
import io.github.soonsoonyy.processdiary.diary.DiaryLog
import io.github.soonsoonyy.processdiary.ui.ProcessPanel
import io.github.soonsoonyy.processdiary.update.UpdateRepository

/** Settings → Tools → Process Diary */
class ProcessDiaryConfigurable : BoundConfigurable("Process Diary") {
    override fun createPanel(): DialogPanel {
        val o = ProcessDiarySettings.get().state
        // 언어 이름은 어떤 언어로 보고 있든 알아볼 수 있게 각 언어로 표시
        val languages = listOf(Choice("AUTO", PD.msg("settings.language.auto")), Choice("KO", "한국어"), Choice("EN", "English"))
        return panel {
            group(PD.msg("settings.group.display")) {
                row(PD.msg("settings.language")) {
                    comboBox(languages)
                        .bindItem({ languages.firstOrNull { it.value == o.language } ?: languages[0] }, { o.language = it?.value ?: "AUTO" })
                        .comment(PD.msg("settings.language.comment"))
                }
                row(PD.msg("settings.interval")) {
                    comboBox(ProcessDiarySettings.INTERVALS.map { Choice(it, PD.msg("settings.interval.value", it)) })
                        .bindItem({ Choice(o.intervalSec, PD.msg("settings.interval.value", o.intervalSec)) }, { o.intervalSec = it?.value ?: 2 })
                        .comment(PD.msg("settings.interval.comment"))
                }
                row(PD.msg("settings.statusBar")) {
                    comboBox(StatusFormat.entries.map { Choice(it, it.label) })
                        .bindItem({ Choice(o.statusFormat, o.statusFormat.label) }, { o.statusFormat = it?.value ?: StatusFormat.CPU_MEM })
                        .comment(PD.msg("settings.statusBar.comment"))
                }
                row(PD.msg("settings.defaultScope")) {
                    comboBox(ProcessPanel.Scope.entries)
                        .bindItem({ ProcessPanel.Scope.entries.firstOrNull { it.name == o.defaultScope } ?: ProcessPanel.Scope.MINE },
                            { o.defaultScope = (it ?: ProcessPanel.Scope.MINE).name })
                        .comment(PD.msg("settings.defaultScope.comment"))
                }
            }

            group(PD.msg("settings.group.watch")) {
                lateinit var ports: com.intellij.ui.dsl.builder.Cell<com.intellij.ui.components.JBCheckBox>
                row {
                    ports = checkBox(PD.msg("settings.watch.ports"))
                        .bindSelected({ o.watchPortConflicts }, { o.watchPortConflicts = it })
                }
                indent {
                    row {
                        checkBox(PD.msg("settings.watch.auto"))
                            .bindSelected({ o.autoResolvePorts }, { o.autoResolvePorts = it })
                            .comment(PD.msg("settings.watch.auto.comment"))
                    }.enabledIf(ports.selected)
                }
                row {
                    checkBox(PD.msg("settings.watch.leftovers"))
                        .bindSelected({ o.watchLeftovers }, { o.watchLeftovers = it })
                }
                row {
                    checkBox(PD.msg("settings.runs.names"))
                        .bindSelected({ o.showRunNames }, { o.showRunNames = it })
                        .comment(PD.msg("settings.runs.names.comment"))
                }
                row {
                    checkBox(PD.msg("settings.runs.stopViaIde"))
                        .bindSelected({ o.stopViaIde }, { o.stopViaIde = it })
                        .comment(PD.msg("settings.runs.stopViaIde.comment"))
                }
            }

            group(PD.msg("settings.group.java")) {
                lateinit var actuator: com.intellij.ui.dsl.builder.Cell<com.intellij.ui.components.JBCheckBox>
                row {
                    actuator = checkBox(PD.msg("settings.java.actuator"))
                        .bindSelected({ o.actuator }, { o.actuator = it })
                        .comment(PD.msg("settings.java.actuator.comment"))
                }
                indent {
                    row(PD.msg("settings.java.actuatorPaths")) {
                        textField()
                            .bindText({ o.actuatorPaths ?: ProcessDiarySettings.DEFAULT_ACTUATOR_PATHS },
                                { o.actuatorPaths = ProcessDiarySettings.parsePaths(it).joinToString(", ") })
                            .align(AlignX.FILL)
                            .comment(PD.msg("settings.java.actuatorPaths.comment"))
                    }.enabledIf(actuator.selected)
                }
                row {
                    checkBox(PD.msg("settings.java.heapTrend"))
                        .bindSelected({ o.heapTrend }, { o.heapTrend = it })
                        .comment(PD.msg("settings.java.heapTrend.comment"))
                }
            }

            group(PD.msg("settings.group.alerts")) {
                val pcts = ProcessDiarySettings.ALERT_PCTS.map { Choice(it, "$it%") }
                fun pct(v: Int) = pcts.firstOrNull { it.value == v } ?: pcts[2]
                row {
                    checkBox(PD.msg("settings.alert.cpu")).bindSelected({ o.alertCpu }, { o.alertCpu = it }).widthGroup("alertLabel")
                    comboBox(pcts).bindItem({ pct(o.alertCpuPct) }, { o.alertCpuPct = it?.value ?: 90 })
                }
                row {
                    checkBox(PD.msg("settings.alert.mem")).bindSelected({ o.alertMem }, { o.alertMem = it }).widthGroup("alertLabel")
                    comboBox(pcts).bindItem({ pct(o.alertMemPct) }, { o.alertMemPct = it?.value ?: 90 })
                }
                val gcPcts = ProcessDiarySettings.GC_PCTS.map { Choice(it, "$it%") }
                row {
                    checkBox(PD.msg("settings.alert.gc")).bindSelected({ o.alertGc }, { o.alertGc = it }).widthGroup("alertLabel")
                        .comment(PD.msg("settings.alert.gc.comment"))
                    comboBox(gcPcts).bindItem({ gcPcts.firstOrNull { it.value == o.alertGcPct } ?: gcPcts[1] }, { o.alertGcPct = it?.value ?: 25 })
                }
                val secs = ProcessDiarySettings.ALERT_SECS.map { Choice(it, if (it % 60 == 0) PD.msg("alert.minutes", it / 60) else PD.msg("alert.seconds", it)) }
                row(PD.msg("settings.alert.duration")) {
                    comboBox(secs).bindItem({ secs.firstOrNull { it.value == o.alertSec } ?: secs[1] }, { o.alertSec = it?.value ?: 60 })
                        .comment(PD.msg("settings.alert.comment"))
                }
            }

            group(PD.msg("settings.group.diary")) {
                lateinit var diary: com.intellij.ui.dsl.builder.Cell<com.intellij.ui.components.JBCheckBox>
                row {
                    diary = checkBox(PD.msg("settings.diary"))
                        .bindSelected({ o.diary }, { o.diary = it })
                        .comment(PD.msg("settings.diary.comment"))
                }
                indent {
                    row(PD.msg("settings.diary.record")) {
                        checkBox(PD.msg("diary.group.runs")).bindSelected({ o.diaryRuns }, { o.diaryRuns = it })
                        checkBox(PD.msg("diary.group.ports")).bindSelected({ o.diaryPorts }, { o.diaryPorts = it })
                        checkBox(PD.msg("diary.group.stops")).bindSelected({ o.diaryStops }, { o.diaryStops = it })
                    }.enabledIf(diary.selected)
                    row("") {
                        checkBox(PD.msg("diary.group.alerts")).bindSelected({ o.diaryAlerts }, { o.diaryAlerts = it })
                        checkBox(PD.msg("diary.group.tools")).bindSelected({ o.diaryTools }, { o.diaryTools = it })
                    }.enabledIf(diary.selected)
                    val maxes = ProcessDiarySettings.DIARY_MAX.map { Choice(it, PD.msg("settings.diary.max.value", it)) }
                    row(PD.msg("settings.diary.max")) {
                        comboBox(maxes).bindItem({ maxes.firstOrNull { it.value == o.diaryMax } ?: maxes[2] }, { o.diaryMax = it?.value ?: 2000 })
                        button(PD.msg("settings.diary.clearNow")) {
                            if (Messages.showYesNoDialog(PD.msg("diary.clear.confirm"), PD.msg("diary.clear"), Messages.getQuestionIcon()) == Messages.YES) {
                                DiaryLog.get().clear()
                            }
                        }
                    }.enabledIf(diary.selected)
                }
            }

            group(PD.msg("settings.group.docker")) {
                row(PD.msg("settings.docker.path")) {
                    textField()
                        .bindText({ o.dockerPath ?: "" }, { o.dockerPath = it.trim() })
                        .align(AlignX.FILL)
                        .comment(PD.msg("settings.docker.comment", Docker.cli() ?: PD.msg("settings.docker.notFound")))
                }
            }

            group(PD.msg("settings.group.update")) {
                row {
                    checkBox(PD.msg("settings.update"))
                        .bindSelected({ UpdateRepository.isEnabled() }, { UpdateRepository.setEnabled(it) })
                        .comment(PD.msg("settings.update.comment") + "<br>" + UpdateRepository.URL)
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        ProcessDiaryService.get().reschedule() // 갱신 주기 바로 적용
        Actuator.clearCache()                  // 경로가 바뀌었을 수 있음
        DiaryLog.get().trim()                  // 최대 개수를 줄였으면 바로 정리
        ProcessDiaryService.get().settingsChanged()
    }
}

/** 콤보 상자 항목 (값 + 화면에 보일 글자) */
private data class Choice<T>(val value: T, val label: String) {
    override fun toString() = label
}
