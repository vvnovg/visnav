package io.visnav.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Параметры ведения. Порог съезда — `max(offRouteM, min(offRouteSigmaK·σ, offRouteSigmaCapM))`: σ позиции
 * расширяет порог, но не больше чем до offRouteSigmaCapM (иначе при большой σ съезд не распознаётся вовсе);
 * нефинитная σ считается нулём.
 */
data class NavConfig(
    val offRouteM: Double = 40.0,
    val offRouteSigmaK: Double = 2.5,
    val offRouteSigmaCapM: Double = 150.0,
    val offRouteHoldMs: Long = 4000L,
    val rerouteCooldownMs: Long = 10_000L,
    val arriveM: Double = 25.0,
    /** Прибытие по прямой до цели — только если по маршруту осталось не больше arriveDirectRemainingM. */
    val arriveDirectM: Double = 30.0,
    val arriveDirectRemainingM: Double = 200.0,
    /** Прибытие при остановке (конечная скорость < arriveStopMps) не дальше arriveStopM по маршруту. */
    val arriveStopMps: Double = 1.0,
    val arriveStopM: Double = 60.0,
    val backtrackM: Double = 15.0,
    /**
     * Ветви маршрута не дальше ближайшей + branchSlackM: берётся ближайшая по маршруту к прогнозу прогресс + s·Δt
     * (самопересечения, разворот на узкой разделённой дороге, стоянка с шумом позиции).
     */
    val branchSlackM: Double = 10.0,
    /** Окно поиска вперёд: max(200, 5·v, v·Δt + staleJumpM), не больше maxWindowM (Δt — с прошлого обновления). */
    val maxWindowM: Double = 2000.0,
    /** Скачок прогресса больше v·Δt + staleJumpM за одно обновление — подсказки на этом обновлении не выдаются. */
    val staleJumpM: Double = 50.0,
    /** Маршрут не «захвачен» (машина ещё не была ближе порога): перестроение — только после сдвига и выдержки. */
    val unacquiredMoveM: Double = 100.0,
    val unacquiredWaitMs: Long = 30_000L,
    /**
     * Проверка направления маршрута, построенного без курса: пока прогресс меньше headingCheckM и только когда
     * маршрут захвачен или машина ближе headingCheckNearM к нему.
     */
    val headingCheckM: Double = 50.0,
    val headingCheckNearM: Double = 15.0,
    val minSpeedMps: Double = 5.0,
)

enum class PromptStage { FAR, NEAR, NOW }

sealed class NavEvent {
    abstract val tMs: Long
    data class RouteReady(override val tMs: Long, val route: Route, val maneuvers: List<Maneuver>, val reroute: Boolean) : NavEvent()

    /** thenManeuver — следующий манёвр, присоединённый к тексту через «затем» (близкие манёвры). */
    data class Prompt(
        override val tMs: Long, val maneuver: Int, val stage: PromptStage, val text: String, val distM: Double,
        val thenManeuver: Int? = null,
    ) : NavEvent()
    data class Arrived(override val tMs: Long) : NavEvent()
    data class RouteFailed(override val tMs: Long) : NavEvent()
}

/**
 * Ведение по маршруту: прогресс, подсказки на трёх дистанциях, съезд с учётом σ позиции, перестроение, прибытие.
 * Координаты — плоские (index.enu); вызывать на каждом выводе Localizer по порядку времени.
 *
 * Пока машина дальше порога съезда от маршрута, подсказки не выдаются. Неудачное перестроение оставляет прежний
 * маршрут; повтор — после rerouteCooldownMs.
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
    /** Машина хотя бы раз была ближе порога съезда к текущему маршруту. */
    private var acquired = false
    private var planE = 0.0
    private var planN = 0.0
    private var planT = 0L
    private var plannedWithHeading = false
    private var headingChecked = false
    private var lastT: Long? = null

    val nextManeuver: Int?
        get() = maneuvers.indices.firstOrNull {
            maneuvers[it].type != ManeuverType.DEPART && (maneuvers[it].atM > progressM + 1 || startUturn(it))
        }
    val distanceToNextM: Double? get() = nextManeuver?.let { maneuvers[it].atM - progressM }

    private fun key(i: Int, s: PromptStage) = i.toLong() * 4 + s.ordinal

    /** Разворот в начале маршрута против курса (atM = 0): следующий манёвр, пока машина не отъехала от старта. */
    private fun startUturn(i: Int): Boolean =
        route?.startsAgainstHeading == true && i == 1 && maneuvers[i].type == ManeuverType.UTURN && progressM <= 1.0

    /**
     * Новый маршрут; при неудаче прежний маршрут и его состояние сохраняются. После удачного плана в том же вызове
     * выдаётся подсказка о первом манёвре (прогресс 0), кроме «сейчас» — она прозвучит на следующем обновлении.
     * Исключение — разворот в начале маршрута против курса: «Развернитесь» звучит сразу.
     */
    private fun plan(tMs: Long, e: Double, n: Double, psi: Double?, reroute: Boolean, v: Double, threshold: Double): List<NavEvent> {
        lastRouteAttempt = tMs
        val r = router.route(e, n, psi, destE, destN) ?: return listOf(NavEvent.RouteFailed(tMs))
        route = r; maneuvers = buildManeuvers(r, index); progressM = 0.0; spoken.clear(); offSince = null
        acquired = false; planE = e; planN = n; planT = tMs
        plannedWithHeading = psi != null; headingChecked = false
        val out = ArrayList<NavEvent>()
        out += NavEvent.RouteReady(tMs, r, maneuvers, reroute)
        // Машина дальше порога съезда от начала маршрута — подсказок нет, как и при обычном ведении.
        if (hypot(e - r.points[0][0], n - r.points[0][1]) <= threshold) out += prompts(tMs, v, allowNow = false)
        return out
    }

    /** Начальный азимут маршрута: от points[0] к первой точке не ближе 1 м; null, если такой нет. */
    private fun initialBearing(r: Route): Double? {
        val a = r.points[0]
        val b = r.points.firstOrNull { hypot(it[0] - a[0], it[1] - a[1]) >= 1.0 } ?: return null
        return atan2(b[0] - a[0], b[1] - a[1])
    }

    fun update(tMs: Long, e: Double, n: Double, sigmaM: Double, psiRad: Double, speedMps: Double): List<NavEvent> {
        if (arrived) return emptyList()
        val dtS = lastT?.let { (tMs - it) / 1000.0 }
        lastT = tMs
        val psi = if (psiRad.isFinite() && speedMps.isFinite() && speedMps >= 3.0) psiRad else null
        val speed = if (speedMps.isFinite()) speedMps else 0.0
        val v = max(speed, config.minSpeedMps)
        val sigma = if (sigmaM.isFinite()) sigmaM else 0.0
        val threshold = max(config.offRouteM, min(config.offRouteSigmaK * sigma, config.offRouteSigmaCapM))
        val r = route
        if (r == null) {
            if (tMs - lastRouteAttempt < config.rerouteCooldownMs) return emptyList()
            return plan(tMs, e, n, psi, reroute = false, v, threshold)
        }
        // Прогресс: проекции на отрезки в окне вокруг текущего прогресса.
        val lo = progressM - config.backtrackM
        val hi = progressM + min(config.maxWindowM, maxOf(200.0, 5 * v, v * (dtS ?: 0.0) + config.staleJumpM))
        val segI = ArrayList<Int>(); val segT = ArrayList<Double>(); val segD = ArrayList<Double>(); val segAt = ArrayList<Double>()
        for (i in 1 until r.points.size) {
            if (r.cumM[i] < lo || r.cumM[i - 1] > hi) continue
            val a = r.points[i - 1]; val b = r.points[i]
            val de = b[0] - a[0]; val dn = b[1] - a[1]
            val len2 = de * de + dn * dn
            val t = if (len2 == 0.0) 0.0 else (((e - a[0]) * de + (n - a[1]) * dn) / len2).coerceIn(0.0, 1.0)
            segI += i; segT += t
            segD += hypot(e - (a[0] + t * de), n - (a[1] + t * dn))
            segAt += r.cumM[i - 1] + t * (r.cumM[i] - r.cumM[i - 1])
        }
        val bestD = segD.minOrNull() ?: Double.POSITIVE_INFINITY
        // Ветви — локальные минимумы расстояния вдоль маршрута (конец отрезка не ветвь, если проекция уходит на
        // соседний отрезок). Из ветвей не дальше bestD + branchSlackM берётся ближайшая по маршруту к прогнозу
        // progress + s·Δt (s — фактическая скорость, без нижней границы): на самопересечении и на развороте узкой
        // разделённой дороги прогресс не перескакивает на другую ветвь, в том числе на стоянке.
        val predicted = progressM + (if (speedMps.isFinite()) speedMps else 0.0) * (dtS ?: 0.0)
        var chosen: Double? = null
        for (k in segI.indices) {
            if (segT[k] >= 1.0 && k + 1 < segI.size && segI[k + 1] == segI[k] + 1 && segT[k + 1] > 0.0) continue
            if (segT[k] <= 0.0 && k > 0 && segI[k - 1] == segI[k] - 1 && segT[k - 1] < 1.0) continue
            if (segAt[k] < lo || segD[k] > bestD + config.branchSlackM) continue
            if (chosen == null || abs(segAt[k] - predicted) < abs(chosen - predicted)) chosen = segAt[k]
        }
        val before = progressM
        if (chosen != null) progressM = chosen.coerceAtLeast(0.0)
        // Маршрут построен без курса: при первом достоверном курсе на маршруте в начале пути проверяем направление.
        if (!plannedWithHeading && !headingChecked && psi != null && (acquired || bestD <= config.headingCheckNearM)) {
            headingChecked = true
            val b = initialBearing(r)
            if (progressM < config.headingCheckM && b != null && abs(wrapAngle(psi - b)) > PI / 2) {
                return plan(tMs, e, n, psi, reroute = false, v, threshold)
            }
        }
        // Съезд с маршрута.
        val off = bestD > threshold
        if (off) {
            val since = offSince ?: tMs.also { offSince = it }
            val due = if (acquired) tMs - since >= config.offRouteHoldMs
            else hypot(e - planE, n - planN) > config.unacquiredMoveM && tMs - planT >= config.unacquiredWaitMs
            if (due && tMs - lastRouteAttempt >= config.rerouteCooldownMs) {
                return plan(tMs, e, n, psi, reroute = true, v, threshold)
            }
        } else {
            offSince = null
            acquired = true
        }
        val out = ArrayList<NavEvent>()
        val remaining = r.lengthM - progressM
        val direct = remaining <= config.arriveDirectRemainingM && hypot(e - destE, n - destN) <= config.arriveDirectM
        val stopped = speedMps.isFinite() && speedMps < config.arriveStopMps && remaining <= config.arriveStopM
        if (remaining <= config.arriveM || direct || stopped) {
            arrived = true
            out += NavEvent.Prompt(tMs, maneuvers.lastIndex, PromptStage.NOW, Instructions.prompt(maneuvers.last(), null), 0.0)
            out += NavEvent.Arrived(tMs)
            return out
        }
        if (off) return out
        // Скачок прогресса (например, после пропадания позиции): подсказки о пройденном не выдаём.
        if (dtS != null && progressM - before > v * dtS + config.staleJumpM) return out
        return prompts(tMs, v, allowNow = true)
    }

    /** Подсказка о следующем манёвре по текущему прогрессу: «далеко», «близко», «сейчас» — каждая не больше раза. */
    private fun prompts(tMs: Long, v: Double, allowNow: Boolean): List<NavEvent> {
        val out = ArrayList<NavEvent>()
        val i = nextManeuver ?: return out
        val m = maneuvers[i]
        val dist = m.atM - progressM
        val far = (30 * v).coerceIn(300.0, 1000.0)
        val near = (8 * v).coerceIn(60.0, 200.0)
        val now = (2.5 * v).coerceIn(15.0, 50.0)
        // Близкий следующий манёвр присоединяется через «затем»; его «далеко» и «близко» считаются озвученными.
        val j = (i + 1).takeIf {
            it < maneuvers.size && maneuvers[it].type != ManeuverType.ARRIVE && maneuvers[it].atM - m.atM <= near
        }
        fun chained(text: String): String {
            if (j == null) return text
            spoken.add(key(j, PromptStage.FAR)); spoken.add(key(j, PromptStage.NEAR))
            return text + ", затем " + Instructions.shortAction(maneuvers[j])
        }
        when {
            (allowNow || startUturn(i)) && dist <= now && m.type != ManeuverType.ARRIVE &&
                spoken.add(key(i, PromptStage.NOW)) -> {
                spoken.add(key(i, PromptStage.NEAR)); spoken.add(key(i, PromptStage.FAR))
                out += NavEvent.Prompt(tMs, i, PromptStage.NOW, chained(Instructions.prompt(m, null)), dist, j)
            }
            dist <= near && spoken.add(key(i, PromptStage.NEAR)) -> {
                spoken.add(key(i, PromptStage.FAR))
                out += NavEvent.Prompt(tMs, i, PromptStage.NEAR, chained(Instructions.prompt(m, dist)), dist, j)
            }
            dist <= far && dist > near + 50 && spoken.add(key(i, PromptStage.FAR)) ->
                out += NavEvent.Prompt(tMs, i, PromptStage.FAR, Instructions.prompt(m, dist), dist)
        }
        return out
    }
}
