package io.github.soonsoonyy.processdiary

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.sun.net.httpserver.HttpServer
import io.github.soonsoonyy.processdiary.data.Actuator
import io.github.soonsoonyy.processdiary.diary.DiaryLog
import io.github.soonsoonyy.processdiary.diary.DiaryLog.Kind
import io.github.soonsoonyy.processdiary.settings.ProcessDiaryConfigurable
import io.github.soonsoonyy.processdiary.settings.ProcessDiarySettings
import java.net.InetSocketAddress

/** 설정 화면의 새 항목이 실제 동작을 바꾸는지 */
class SettingsFeaturesTest : BasePlatformTestCase() {
    private val o get() = ProcessDiarySettings.get().state
    private lateinit var saved: ProcessDiarySettings.Options

    override fun setUp() {
        super.setUp()
        saved = XmlSerializer.deserialize(XmlSerializer.serialize(o), ProcessDiarySettings.Options::class.java)
        DiaryLog.get().clear()
    }

    override fun tearDown() {
        try {
            ProcessDiarySettings.get().loadState(saved)
            DiaryLog.get().clear()
            Actuator.clearCache()
        } finally { super.tearDown() }
    }

    fun testDiarySwitches() {
        DiaryLog.add(Kind.RUN_START, "a")
        assertEquals(1, DiaryLog.get().entries().size)
        o.diaryRuns = false
        DiaryLog.add(Kind.RUN_END, "not recorded")
        DiaryLog.add(Kind.STOP, "recorded")
        assertEquals(listOf("recorded", "a"), DiaryLog.get().entries().map { it.text })
        o.diary = false
        DiaryLog.add(Kind.ALERT, "diary off")
        assertEquals(2, DiaryLog.get().entries().size)
        // 최대 개수를 줄이면 오래된 것부터 정리
        o.diary = true; o.diaryRuns = true; o.diaryMax = 500
        repeat(700) { DiaryLog.add(Kind.STOP, "s$it") }
        assertEquals(500, DiaryLog.get().entries().size)
        o.diaryMax = 2000
        repeat(100) { DiaryLog.add(Kind.STOP, "t$it") }
        o.diaryMax = 500
        DiaryLog.get().trim()
        assertEquals(500, DiaryLog.get().entries().size)
        assertEquals("t99", DiaryLog.get().entries().first().text)
    }

    fun testActuatorPathsAndSwitch() {
        assertEquals(listOf("/actuator", "/manage", "/x"), ProcessDiarySettings.parsePaths("actuator, /manage/ ;x"))
        assertEquals(listOf("/actuator", "/manage", "/management"), ProcessDiarySettings.parsePaths("  "))
        assertEquals(5, ProcessDiarySettings.parsePaths("a,b,c,d,e,f,g").size)

        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/ops") { ex ->
            val b = """{"_links":{"self":{"href":"http://localhost/ops"}}}""".toByteArray()
            if (ex.requestURI.path != "/ops") ex.sendResponseHeaders(404, -1) else { ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            ex.close()
        }
        s.start()
        try {
            val port = s.address.port
            assertNull("default paths do not include /ops", Actuator.probe(10, listOf(port)))
            Actuator.clearCache()
            o.actuatorPaths = "/ops"
            assertEquals("http://127.0.0.1:$port/ops", Actuator.probe(11, listOf(port))?.baseUrl)
        } finally { s.stop(0) }
    }

    /** 설정 화면이 새 항목들과 연결돼 있는지 (값이 바뀌면 "변경됨"으로 보임) */
    fun testSettingsPageBindsNewOptions() {
        val c = ProcessDiaryConfigurable()
        try {
            assertNotNull(c.createComponent())
            for (change in listOf<() -> Unit>(
                { o.showRunNames = !o.showRunNames }, { o.stopViaIde = !o.stopViaIde }, { o.actuator = !o.actuator },
                { o.heapTrend = !o.heapTrend }, { o.diary = !o.diary }, { o.diaryAlerts = !o.diaryAlerts }, { o.diaryMax = 5000 },
                { o.actuatorPaths = "/ops" },
            )) {
                c.reset()
                assertFalse(c.isModified)
                change()
                assertTrue("page should notice the change", c.isModified)
                c.reset() // 화면을 새 값으로
                assertFalse(c.isModified)
            }
        } finally {
            c.disposeUIResources()
        }
    }
}
