package io.github.soonsoonyy.processdiary

import io.github.soonsoonyy.processdiary.model.Category
import io.github.soonsoonyy.processdiary.model.Container
import io.github.soonsoonyy.processdiary.model.ProcInfo
import io.github.soonsoonyy.processdiary.runs.AutoResolve
import io.github.soonsoonyy.processdiary.runs.AutoResolve.Decision
import junit.framework.TestCase

/** 포트 충돌을 묻지 않고 처리해도 되는 경우와 아닌 경우 */
class AutoResolveTest : TestCase() {
    private val project = "/home/me/shop"
    private val runAt = 1_000_000L

    private fun proc(pid: Int = 100, cwd: String = "$project/api", user: String = "me", start: Long = runAt - 60_000,
                     name: String = "node", cat: Category = Category.DEV) =
        ProcInfo(pid, 1, name, "/usr/bin/$name", user, 0.0, 0, 0.0, 0, start, cwd, cat, listOf(3000))

    private fun container(dir: String) = Container("id", "db", "postgres", "running", "Up", "0.0.0.0:3000->5432/tcp", listOf(3000), "shop", dir)

    private fun decide(owners: List<ProcInfo?>, containers: List<Container> = emptyList(), ideTree: Set<Int> = emptySet(), path: String? = project) =
        AutoResolve.decide(owners, containers, path, "me", ideTree, { it <= 1 }, runAt)

    fun testLeftoverFromThisProjectIsHandled() {
        val d = decide(listOf(proc()))
        assertTrue(d is Decision.Processes)
        assertEquals(listOf(100), (d as Decision.Processes).targets.map { it.pid })
        // 프로젝트 폴더 자체에서 실행한 것도
        assertTrue(decide(listOf(proc(cwd = project))) is Decision.Processes)
    }

    fun testAsksWhenNotClearlyALeftover() {
        assertTrue("other project", decide(listOf(proc(cwd = "/home/me/other"))) is Decision.Ask)
        assertTrue("similar prefix", decide(listOf(proc(cwd = "/home/me/shop-admin"))) is Decision.Ask)
        assertTrue("no cwd", decide(listOf(proc(cwd = ""))) is Decision.Ask)
        assertTrue("other user", decide(listOf(proc(user = "alice"))) is Decision.Ask)
        assertTrue("running from IDE", decide(listOf(proc()), ideTree = setOf(100)) is Decision.Ask)
        assertTrue("started after this run", decide(listOf(proc(start = runAt + 10))) is Decision.Ask)
        assertTrue("system", decide(listOf(proc(cat = Category.SYSTEM))) is Decision.Ask)
        assertTrue("docker proxy", decide(listOf(proc(name = "com.docker.backend"))) is Decision.Ask)
        assertTrue("unknown pid", decide(listOf(null)) is Decision.Ask)
        assertTrue("protected", decide(listOf(proc(pid = 1))) is Decision.Ask)
        assertTrue("no owner", decide(emptyList()) is Decision.Ask)
        assertTrue("no project", decide(listOf(proc()), path = null) is Decision.Ask)
        assertTrue("one bad among many", decide(listOf(proc(pid = 100), proc(pid = 101, cwd = "/tmp"))) is Decision.Ask)
        assertTrue("too many", decide((100..110).map { proc(pid = it) }) is Decision.Ask)
    }

    fun testContainers() {
        assertTrue(decide(listOf(proc(name = "docker-proxy")), listOf(container("$project"))) is Decision.Containers)
        assertTrue("compose from other folder", decide(emptyList(), listOf(container("/home/me/other"))) is Decision.Ask)
        assertTrue("not compose", decide(emptyList(), listOf(container(""))) is Decision.Ask)
    }

    fun testWindowsPaths() {
        assertTrue(AutoResolve.inside("C:\\Work\\Shop\\api", "c:\\work\\shop"))
        assertFalse(AutoResolve.inside("C:\\Work\\Shop2", "C:\\Work\\Shop"))
        assertTrue(AutoResolve.inside("/home/me/shop", "/home/me/shop/"))
        assertFalse("unix paths are case sensitive", AutoResolve.inside("/home/me/Shop", "/home/me/shop"))
        val d = AutoResolve.decide(listOf(proc(cwd = "C:\\Users\\me\\shop\\api", user = "PC\\me")), emptyList(), "C:/Users/me/shop", "me", emptySet(), { false }, runAt)
        assertTrue("windows user with domain + path", d is Decision.Processes)
    }

    fun testLoopGuard() {
        AutoResolve.reset()
        assertTrue(AutoResolve.tryAcquire("p|Dev server", now = 0))
        assertFalse("same run config again within a minute", AutoResolve.tryAcquire("p|Dev server", now = 30_000))
        assertTrue("another run config", AutoResolve.tryAcquire("p|Worker", now = 30_000))
        assertTrue("after a minute", AutoResolve.tryAcquire("p|Dev server", now = 61_000))
    }
}
