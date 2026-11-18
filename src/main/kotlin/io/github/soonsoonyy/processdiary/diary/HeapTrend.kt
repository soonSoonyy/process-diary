package io.github.soonsoonyy.processdiary.diary

/**
 * Java 힙의 장기 추이: 1분마다 "가장 낮았던 힙"(GC 직후에 가까운 값)을 최대 6시간 모아 기울기를 본다.
 * 사용량이 오르내려도 바닥이 계속 높아지면 메모리 누수일 가능성이 크다.
 */
class HeapTrend(private val maxPoints: Int = 360) {
    /** (분, 그 분의 최저 힙 바이트) */
    val points = ArrayDeque<Pair<Long, Long>>()
    var reported = false

    fun add(timeMs: Long, heapUsed: Long) {
        if (heapUsed < 0) return
        val minute = timeMs / 60_000
        val last = points.lastOrNull()
        if (last != null && last.first == minute) {
            if (heapUsed < last.second) { points.removeLast(); points.addLast(minute to heapUsed) }
        } else {
            points.addLast(minute to heapUsed)
            while (points.size > maxPoints) points.removeFirst()
        }
    }

    data class Result(val bytesPerHour: Double, val minutes: Long, val r2: Double)

    /** 최소제곱 직선 (점이 [minPoints]개 미만이면 null) */
    fun analyze(minPoints: Int = 15): Result? {
        if (points.size < minPoints) return null
        val xs = points.map { it.first.toDouble() }
        val ys = points.map { it.second.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val slope = sxy / sxx // 바이트/분
        val syy = ys.sumOf { (it - my) * (it - my) }
        val r2 = if (syy == 0.0) 0.0 else (sxy * sxy) / (sxx * syy)
        return Result(slope * 60, points.last().first - points.first().first, r2)
    }

    /** 누수 의심: 1시간 이상, 거의 직선으로(R² ≥ 0.8), 시간당 max(16MB, 최대 힙의 5%) 이상 증가 (하루 안에 힙이 찰 만한 속도) */
    fun suspicious(heapMax: Long): Result? {
        val r = analyze() ?: return null
        val limit = maxOf(16.0 * 1048576, if (heapMax > 0) heapMax * 0.05 else 0.0)
        return r.takeIf { it.minutes >= 60 && it.r2 >= 0.8 && it.bytesPerHour >= limit }
    }
}
