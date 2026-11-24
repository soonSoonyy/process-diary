package io.github.soonsoonyy.processdiary

import io.github.soonsoonyy.processdiary.data.Critical
import io.github.soonsoonyy.processdiary.data.CriticalKind
import junit.framework.TestCase

/** 한 번 클릭으로 종료하면 안 되는 프로세스 판별 */
class CriticalTest : TestCase() {
    fun testDocker() {
        assertEquals(CriticalKind.DOCKER, Critical.kindOf("com.docker.backend", "/Applications/Docker.app/Contents/MacOS/com.docker.backend"))
        assertEquals(CriticalKind.DOCKER, Critical.kindOf("com.docker.backend.exe", "C:\\Program Files\\Docker\\Docker\\resources\\com.docker.backend.exe"))
        assertEquals(CriticalKind.DOCKER, Critical.kindOf("docker-proxy", "/usr/bin/docker-proxy"))
        assertEquals(CriticalKind.DOCKER, Critical.kindOf("vpnkit-bridge"))
        assertEquals(CriticalKind.DOCKER, Critical.kindOf("wslrelay.exe"))
        assertEquals(CriticalKind.DOCKER, Critical.kindOf("containerd-shim-runc-v2"))
    }

    fun testDatabase() {
        assertEquals(CriticalKind.DATABASE, Critical.kindOf("postgres", "/opt/homebrew/opt/postgresql@16/bin/postgres"))
        assertEquals(CriticalKind.DATABASE, Critical.kindOf("mysqld"))
        assertEquals(CriticalKind.DATABASE, Critical.kindOf("redis-server"))
        assertEquals(CriticalKind.DATABASE, Critical.kindOf("sqlservr.exe"))
    }

    fun testIde() {
        assertEquals(CriticalKind.IDE, Critical.kindOf("idea", "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea"))
        assertEquals(CriticalKind.IDE, Critical.kindOf("idea64.exe", "C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe"))
        assertEquals(CriticalKind.IDE, Critical.kindOf("webstorm"))
    }

    /** 개발 서버처럼 평범하게 종료해도 되는 것 */
    fun testOrdinaryDevProcesses() {
        for (n in listOf("node", "java", "python3", "ruby", "go", "docker", "npm", "gradle", "vite", "uvicorn")) {
            assertNull(n, Critical.kindOf(n, "/usr/local/bin/$n"))
        }
        // 이름이 IDE와 같아도 프로젝트 안의 스크립트 등은 IDE로 보지 않음
        assertNull(Critical.kindOf("studio", "/Users/me/work/app/bin/studio"))
    }
}
