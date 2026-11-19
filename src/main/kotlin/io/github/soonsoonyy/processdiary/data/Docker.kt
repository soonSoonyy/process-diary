package io.github.soonsoonyy.processdiary.data

import java.io.File

/**
 * 포트를 Docker가 쥐고 있을 때 Docker 자체가 아니라 그 포트를 공개한 컨테이너만 멈추기 위한 도우미.
 * Finder/시작 메뉴에서 켠 IDE는 PATH가 짧을 수 있어 흔한 설치 위치도 함께 찾는다.
 */
object Docker {
    private fun candidates(): List<File> {
        val exe = if (File.separatorChar == '\\') "docker.exe" else "docker"
        val fromPath = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it, exe) }
        val home = System.getProperty("user.home")
        val known = listOf(
            "/usr/local/bin/docker", "/opt/homebrew/bin/docker", "/usr/bin/docker", "$home/.docker/bin/docker",
            "$home/.orbstack/bin/docker", "/Applications/Docker.app/Contents/Resources/bin/docker",
            "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe",
        ).map(::File)
        return fromPath + known
    }

    /** docker 명령 경로 (없으면 null) */
    fun cli(): String? = candidates().firstOrNull { it.isFile && it.canExecute() }?.absolutePath

    /** 이 포트를 공개(publish)한 실행 중인 컨테이너 이름 */
    fun containersPublishing(port: Int): List<String> {
        val docker = cli() ?: return emptyList()
        val r = Exec.run(listOf(docker, "ps", "--filter", "publish=$port", "--format", "{{.Names}}"), timeoutMs = 5000)
        if (!r.ok) return emptyList()
        return r.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** 컨테이너 정지. 실패하면 오류 설명, 성공하면 null */
    fun stop(container: String): String? {
        val docker = cli() ?: return "docker command not found"
        // docker stop 은 기본 10초 동안 정상 종료를 기다린 뒤 강제 종료함
        val r = Exec.run(listOf(docker, "stop", container), timeoutMs = 30_000)
        return when {
            r.timedOut -> "timed out"
            r.exitCode == null -> "docker command could not be started"
            r.exitCode != 0 -> "exit code ${r.exitCode}"
            else -> null
        }
    }
}
