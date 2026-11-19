package io.github.soonsoonyy.processdiary.data

import io.github.soonsoonyy.processdiary.settings.ProcessDiarySettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Spring Boot Actuator 연동: Java 프로세스가 연 포트에서 /actuator 를 찾아 상태(health), 앱 정보(info), 엔드포인트 목록을 읽는다.
 * 이 컴퓨터(127.0.0.1) 안에서만 요청하고, 찾지 못한 포트는 잠시 다시 묻지 않는다.
 * 백그라운드 스레드에서 호출한다.
 */
object Actuator {
    data class Component(val name: String, val status: String)

    data class Info(
        val port: Int,
        val baseUrl: String,
        /** UP, DOWN, OUT_OF_SERVICE, UNKNOWN (읽지 못하면 "") */
        val status: String,
        val components: List<Component>,
        /** health 를 보려면 로그인이 필요함 (401/403) */
        val healthNeedsLogin: Boolean,
        val appName: String,
        val appVersion: String,
        /** 엔드포인트 이름 → 주소 (health, info, metrics, env, loggers, mappings ...) */
        val endpoints: Map<String, String>,
    )

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofMillis(800)).build()
    }

    private class Cached(val at: Long, val info: Info?)

    private val cache = ConcurrentHashMap<String, Cached>()


    /** 이 포트들 중 Actuator 가 있는 곳 (결과는 10초, 없으면 60초 동안 기억) */
    fun probe(pid: Int, ports: List<Int>): Info? {
        val key = "$pid:" + ports.joinToString(",")
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now - it.at < (if (it.info != null) 10_000 else 60_000)) return it.info }
        var info: Info? = null
        loop@ for (port in ports.take(5)) {
            for (base in paths()) {
                // 응답이 없으면 HTTP 포트가 아님 (Gradle 데몬 등): 다른 경로는 묻지 않음
                val r = get("http://127.0.0.1:$port$base") ?: break
                if (r.first == 200) {
                    val found = read(port, base, r.second)
                    if (found != null) { info = found; break@loop }
                }
            }
        }
        cache[key] = Cached(now, info)
        return info
    }

    /** 찾을 경로 (설정, 읽지 못하면 기본값) */
    private fun paths(): List<String> = try {
        ProcessDiarySettings.parsePaths(ProcessDiarySettings.get().state.actuatorPaths)
    } catch (_: Throwable) { ProcessDiarySettings.parsePaths(ProcessDiarySettings.DEFAULT_ACTUATOR_PATHS) }

    fun clearCache() = cache.clear()

    /** 아직 유효한 기억이 있으면 (true, 결과). 없으면 (false, null) */
    fun cached(pid: Int, ports: List<Int>): Pair<Boolean, Info?> {
        val c = cache["$pid:" + ports.joinToString(",")] ?: return false to null
        val fresh = System.currentTimeMillis() - c.at < (if (c.info != null) 10_000 else 60_000)
        return if (fresh) true to c.info else false to null
    }

    private fun get(url: String): Pair<Int, String>? = try {
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMillis(1500)).header("Accept", "application/json").GET().build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofString())
        res.statusCode() to (res.body() ?: "")
    } catch (_: Exception) { null }

    /** host:port/base 가 Actuator 면 정보 (아니면 null) */
    fun read(port: Int, base: String, body: String, host: String = "127.0.0.1"): Info? {
        val baseUrl = "http://$host:$port$base"
        val links = (Json.parseOrNull(body) as? Map<*, *>)?.get("_links") as? Map<*, *> ?: return null
        val endpoints = links.entries.mapNotNull { (k, v) ->
            val name = k as? String ?: return@mapNotNull null
            val href = (v as? Map<*, *>)?.get("href") as? String ?: return@mapNotNull null
            // 매개변수가 있는 주소(caches-cache: .../{cache})와 자기 자신은 빼기
            if (name == "self" || '{' in href) null
            else name to href.replace(Regex("^https?://[^/]+"), "http://$host:$port") // 서버가 알려준 주소 대신 실제로 연결된 주소
        }.toMap()
        var status = ""
        var components = emptyList<Component>()
        var needsLogin = false
        endpoints["health"]?.let { url ->
            val (hc, hb) = get(url) ?: return@let
            when (hc) {
                401, 403 -> needsLogin = true
                // 상태가 DOWN 이면 503 과 함께 같은 형식의 본문이 옴
                else -> (Json.parseOrNull(hb) as? Map<*, *>)?.let { h ->
                    status = h["status"] as? String ?: ""
                    components = ((h["components"] ?: h["details"]) as? Map<*, *>)?.entries?.mapNotNull { (n, c) ->
                        val s = (c as? Map<*, *>)?.get("status") as? String ?: return@mapNotNull null
                        Component(n.toString(), s)
                    }.orEmpty()
                }
            }
        }
        var appName = ""
        var appVersion = ""
        endpoints["info"]?.let { url ->
            val (ic, ib) = get(url) ?: return@let
            if (ic != 200) return@let
            val i = Json.parseOrNull(ib) as? Map<*, *> ?: return@let
            val app = i["app"] as? Map<*, *>
            val build = i["build"] as? Map<*, *>
            appName = (app?.get("name") ?: build?.get("name") ?: build?.get("artifact"))?.toString().orEmpty()
            appVersion = (app?.get("version") ?: build?.get("version"))?.toString().orEmpty()
        }
        return Info(port, baseUrl, status, components, needsLogin, appName, appVersion, endpoints)
    }
}

/** 작은 JSON 읽기 (Map / List / String / Double / Boolean / null) */
object Json {
    fun parseOrNull(s: String): Any? = try { Parser(s).run { val v = value(); ws(); if (i != s.length) null else v } } catch (_: Exception) { null }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        fun lit(w: String, v: Any?): Any? { require(s.startsWith(w, i)); i += w.length; return v }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
        fun str(): String {
            val sb = StringBuilder()
            i++ // "
            while (s[i] != '"') {
                val c = s[i++]
                if (c != '\\') { sb.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            }
            i++
            return sb.toString()
        }
        fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value(); ws()
                if (s[i++] == ']') return out
            }
        }
        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = str(); ws(); require(s[i++] == ':')
                out[k] = value(); ws()
                if (s[i++] == '}') return out
            }
        }
    }
}
