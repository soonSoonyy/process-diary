package io.github.soonsoonyy.processdiary

import com.sun.net.httpserver.HttpServer
import io.github.soonsoonyy.processdiary.data.Actuator
import io.github.soonsoonyy.processdiary.data.Json
import junit.framework.TestCase
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Spring Boot 3 의 실제 응답 모양을 흉내 낸 서버로 확인 */
class ActuatorTest : TestCase() {
    private fun server(health: Pair<Int, String>, base: String = "/actuator"): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = s.address.port
        fun reply(path: String, code: Int, body: String) = s.createContext(path) { ex ->
            if (ex.requestURI.path != path) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            val b = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/vnd.spring-boot.actuator.v3+json")
            ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        val h = "http://localhost:$port$base"
        reply(base, 200, """{"_links":{"self":{"href":"$h","templated":false},"health":{"href":"$h/health","templated":false},
            "health-path":{"href":"$h/health/{*path}","templated":true},"info":{"href":"$h/info","templated":false},
            "metrics-requiredMetricName":{"href":"$h/metrics/{requiredMetricName}","templated":true},"metrics":{"href":"$h/metrics","templated":false},
            "loggers":{"href":"$h/loggers","templated":false}}}""")
        reply("$base/health", health.first, health.second)
        reply("$base/info", 200, """{"app":{"name":"service-registry","version":"1.4.2"},"build":{"artifact":"service-registry"}}""")
        s.start()
        return s
    }

    fun testUpWithComponents() {
        val s = server(200 to """{"status":"UP","components":{"db":{"status":"UP","details":{"database":"PostgreSQL"}},"diskSpace":{"status":"UP"},"ping":{"status":"UP"}}}""")
        try {
            val port = s.address.port
            val info = Actuator.probe(1, listOf(port))!!
            assertEquals("UP", info.status)
            assertEquals(listOf("db", "diskSpace", "ping"), info.components.map { it.name })
            assertEquals("service-registry", info.appName)
            assertEquals("1.4.2", info.appVersion)
            assertEquals(setOf("health", "info", "metrics", "loggers"), info.endpoints.keys)
            assertEquals("http://127.0.0.1:$port/actuator/health", info.endpoints["health"])
            // 기억해 둔 결과
            assertEquals(true to info, Actuator.cached(1, listOf(port)))
        } finally { s.stop(0); Actuator.clearCache() }
    }

    fun testDownAndLoginAndOtherBase() {
        val down = server(503 to """{"status":"DOWN","components":{"redis":{"status":"DOWN","details":{"error":"Connection refused"}}}}""")
        val secured = server(401 to "")
        val legacy = server(200 to """{"status":"UP"}""", base = "/manage")
        try {
            val d = Actuator.probe(2, listOf(down.address.port))!!
            assertEquals("DOWN", d.status)
            assertEquals("redis", d.components.single().name)
            val l = Actuator.probe(3, listOf(secured.address.port))!!
            assertTrue(l.healthNeedsLogin)
            assertEquals("", l.status)
            assertEquals("UP", Actuator.probe(4, listOf(legacy.address.port))!!.status)
        } finally { down.stop(0); secured.stop(0); legacy.stop(0); Actuator.clearCache() }
    }

    fun testNotActuator() {
        // 응답하지 않는 포트(HTTP 아님)와 404 만 주는 HTTP 서버
        ServerSocket(0).use { silent ->
            val plain = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
            try {
                val t = System.currentTimeMillis()
                assertNull(Actuator.probe(5, listOf(silent.localPort, plain.address.port)))
                assertTrue("gives up quickly: ${System.currentTimeMillis() - t} ms", System.currentTimeMillis() - t < 5000)
                assertEquals(true to null, Actuator.cached(5, listOf(silent.localPort, plain.address.port)))
            } finally { plain.stop(0); Actuator.clearCache() }
        }
    }

    fun testJson() {
        assertEquals(mapOf("a" to listOf(1.0, true, null, "x\"yé")), Json.parseOrNull("""{"a":[1,true,null,"x\"yé"]}"""))
        assertEquals(emptyMap<String, Any>(), Json.parseOrNull(" { } "))
        assertNull(Json.parseOrNull("{\"a\":"))
        assertNull(Json.parseOrNull("<html>"))
    }
}
