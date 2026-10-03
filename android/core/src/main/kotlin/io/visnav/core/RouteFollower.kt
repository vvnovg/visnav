package io.visnav.core

import kotlin.math.hypot
import kotlin.math.max

data class NavConfig(
    val offRouteM: Double = 40.0,
    val offRouteSigmaK: Double = 2.5,
    val offRouteHoldMs: Long = 4000L,
    val rerouteCooldownMs: Long = 10_000L,
    val arriveM: Double = 25.0,
    val backtrackM: Double = 15.0,
    val minSpeedMps: Double = 5.0,
)

enum class PromptStage { FAR, NEAR, NOW }

sealed class NavEvent {
    abstract val tMs: Long
    data class RouteReady(override val tMs: Long, val route: Route, val maneuvers: List<Maneuver>, val reroute: Boolean) : NavEvent()
    data class Prompt(override val tMs: Long, val maneuver: Int, val stage: PromptStage, val text: String, val distM: Double) : NavEvent()
    data class Arrived(override val tMs: Long) : NavEvent()
    data class RouteFailed(override val tMs: Long) : NavEvent()
}

/**
 * Ведение по маршруту: прогресс, подсказки на трёх дистанциях, съезд с учётом σ позиции, перестроение, прибытие.
 * Координаты — плоские (index.enu); вызывать на каждом выводе Localizer по порядку времени.
 */
class RouteFollower(
    private val index: RoadIndex, private val destE: Double, private val destN: Double,
    private val config: NavConfig = NavConfig(), routerConfig: RouterConfig = RouterConfig(),
) {
    private val router = Router(index, routerConfig)
    var route: Route? = null; private set
    var maneuvers: List<Maneuver> = emptyList(); private set
    var progressM = 0.0; private set
    var arrived = false; private set
    private val spoken = HashSet<Long>()
    private var offSince: Long? = null
    private var lastRouteAttempt = Long.MIN_VALUE / 2

    val nextManeuver: Int?
        get() = maneuvers.indices.firstOrNull { maneuvers[it].type != ManeuverType.DEPART && maneuvers[it].atM > progressM + 1 }
    val distanceToNextM: Double? get() = nextManeuver?.let { maneuvers[it].atM - progressM }

    private fun key(i: Int, s: PromptStage) = i.toLong() * 4 + s.ordinal

    private fun plan(tMs: Long, e: Double, n: Double, psi: Double?, reroute: Boolean): NavEvent {
        lastRouteAttempt = tMs
        val r = router.route(e, n, psi, destE, destN) ?: return NavEvent.RouteFailed(tMs)
        route = r; maneuvers = buildManeuvers(r, index); progressM = 0.0; spoken.clear(); offSince = null
        return NavEvent.RouteReady(tMs, r, maneuvers, reroute)
    }

    fun update(tMs: Long, e: Double, n: Double, sigmaM: Double, psiRad: Double, speedMps: Double): List<NavEvent> {
        if (arrived) return emptyList()
        val psi = if (psiRad.isFinite() && speedMps.isFinite() && speedMps >= 3.0) psiRad else null
        val r = route
        if (r == null) {
            if (tMs - lastRouteAttempt < config.rerouteCooldownMs) return emptyList()
            return listOf(plan(tMs, e, n, psi, reroute = false))
        }
        val v = max(if (speedMps.isFinite()) speedMps else 0.0, config.minSpeedMps)
        // Прогресс: ближайшая проекция в окне вокруг текущего прогресса.
        val lo = progressM - config.backtrackM
        val hi = progressM + max(200.0, 5 * v)
        var bestD = Double.POSITIVE_INFINITY
        var bestAt = progressM
        for (i in 1 until r.points.size) {
            if (r.cumM[i] < lo || r.cumM[i - 1] > hi) continue
            val a = r.points[i - 1]; val b = r.points[i]
            val de = b[0] - a[0]; val dn = b[1] - a[1]
            val len2 = de * de + dn * dn
            val t = if (len2 == 0.0) 0.0 else (((e - a[0]) * de + (n - a[1]) * dn) / len2).coerceIn(0.0, 1.0)
            val d = hypot(e - (a[0] + t * de), n - (a[1] + t * dn))
            if (d < bestD) { bestD = d; bestAt = r.cumM[i - 1] + t * (r.cumM[i] - r.cumM[i - 1]) }
        }
        if (bestAt >= lo) progressM = max(progressM - config.backtrackM, bestAt).coerceAtLeast(0.0)
        // Съезд с маршрута.
        if (bestD > max(config.offRouteM, config.offRouteSigmaK * sigmaM)) {
            val since = offSince ?: tMs.also { offSince = it }
            if (tMs - since >= config.offRouteHoldMs && tMs - lastRouteAttempt >= config.rerouteCooldownMs) {
                route = null
                return listOf(plan(tMs, e, n, psi, reroute = true))
            }
        } else {
            offSince = null
        }
        val out = ArrayList<NavEvent>()
        if (r.lengthM - progressM <= config.arriveM) {
            arrived = true
            out += NavEvent.Prompt(tMs, maneuvers.lastIndex, PromptStage.NOW, Instructions.prompt(maneuvers.last(), null), 0.0)
            out += NavEvent.Arrived(tMs)
            return out
        }
        val i = nextManeuver ?: return out
        val m = maneuvers[i]
        val dist = m.atM - progressM
        val far = (30 * v).coerceIn(300.0, 1000.0)
        val near = (8 * v).coerceIn(60.0, 200.0)
        val now = (2.5 * v).coerceIn(15.0, 50.0)
        when {
            dist <= now && m.type != ManeuverType.ARRIVE && spoken.add(key(i, PromptStage.NOW)) -> {
                spoken.add(key(i, PromptStage.NEAR)); spoken.add(key(i, PromptStage.FAR))
                out += NavEvent.Prompt(tMs, i, PromptStage.NOW, Instructions.prompt(m, null), dist)
            }
            dist <= near && spoken.add(key(i, PromptStage.NEAR)) -> {
                spoken.add(key(i, PromptStage.FAR))
                out += NavEvent.Prompt(tMs, i, PromptStage.NEAR, Instructions.prompt(m, dist), dist)
            }
            dist <= far && dist > near + 50 && spoken.add(key(i, PromptStage.FAR)) ->
                out += NavEvent.Prompt(tMs, i, PromptStage.FAR, Instructions.prompt(m, dist), dist)
        }
        return out
    }
}
