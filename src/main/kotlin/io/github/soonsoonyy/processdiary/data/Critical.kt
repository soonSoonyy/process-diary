package io.github.soonsoonyy.processdiary.data

import io.github.soonsoonyy.processdiary.PD

/**
 * 종료하면 다른 작업까지 함께 망가지는 프로세스.
 * 포트 충돌·남은 프로세스 알림처럼 "한 번 클릭"으로 종료하는 곳에서는 건너뛰고,
 * 사용자가 직접 고른 경우에는 확인창에 경고를 덧붙인다.
 */
enum class CriticalKind {
    /** Docker 엔진/포트 포워더: 종료하면 모든 컨테이너가 멈춤 */
    DOCKER,
    /** DB 서버: 연결된 다른 앱과 진행 중인 트랜잭션이 끊김 */
    DATABASE,
    /** 다른 IDE/에디터: 저장하지 않은 작업이 사라짐 */
    IDE;

    val label: String get() = PD.msg("critical.kind.${name.lowercase()}")
}

object Critical {
    private val DOCKER = setOf(
        "dockerd", "docker-proxy", "containerd", "vpnkit", "vpnkit-bridge", "docker desktop", "docker desktop backend",
        "wslrelay", "rootlesskit", "colima", "limactl", "orbstack", "orbstack helper", "podman", "gvproxy",
    )
    private val DATABASE = setOf(
        "postgres", "postmaster", "mysqld", "mariadbd", "mongod", "mongos", "redis-server", "valkey-server",
        "sqlservr", "oracle", "tnslsnr", "clickhouse", "clickhouse-server", "memcached", "cockroach",
    )
    private val IDE = setOf(
        "idea", "webstorm", "pycharm", "goland", "clion", "rider", "datagrip", "phpstorm", "rubymine",
        "rustrover", "dataspell", "aqua", "studio", "fleet", "code", "cursor", "windsurf", "eclipse", "sublime_text",
    )

    /** 이름(.exe 제외, 소문자)과 경로로 판별. 해당하지 않으면 null */
    fun kindOf(name: String, path: String = ""): CriticalKind? {
        val n = Describe.baseName(name).lowercase()
        val p = path.replace('\\', '/').lowercase()
        if (n.startsWith("com.docker.") || n in DOCKER || n.startsWith("containerd-shim") ||
            "/docker.app/" in p || "/docker/docker/resources/" in p || "/orbstack.app/" in p) return CriticalKind.DOCKER
        if (n in DATABASE) return CriticalKind.DATABASE
        // JetBrains 런처는 idea64 처럼 뒤에 64가 붙음
        if (n.removeSuffix("64") in IDE &&
            (p.isEmpty() || "/jetbrains/" in p || ".app/" in p || "/program files" in p || "/toolbox/" in p || "/opt/" in p || "/snap/" in p ||
                "/usr/share/" in p || "/applications/" in p || "/appdata/" in p)) return CriticalKind.IDE
        return null
    }

    /** 실행 중인 PID로 판별 (이름을 모를 때) */
    fun kindOf(pid: Int): CriticalKind? = try {
        ProcessHandle.of(pid.toLong()).flatMap { it.info().command() }
            .map { cmd -> kindOf(cmd.substringAfterLast('/').substringAfterLast('\\'), cmd) }.orElse(null)
    } catch (_: Exception) { null }
}
