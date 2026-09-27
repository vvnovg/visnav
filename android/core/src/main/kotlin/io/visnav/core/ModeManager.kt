package io.visnav.core

/** Режимы FR-15 в порядке ухудшения. */
enum class NavMode { GNSS, FUSED, VISUAL, DEAD_RECKONING }

data class ModeConfig(
    val worseDelayMs: Double = 2_000.0,
    val betterDelayMs: Double = 10_000.0,
    val visualFreshMs: Double = 10_000.0,
)

/** Переключение режимов с гистерезисом: ухудшение быстро, улучшение осторожно. */
class ModeManager(private val config: ModeConfig = ModeConfig()) {
    var mode: NavMode = NavMode.GNSS
        private set
    private var pending: NavMode? = null
    private var pendingSince = 0.0

    fun update(tMs: Double, health: GnssHealth, lastVisualOkMs: Double?): NavMode {
        val visualFresh = lastVisualOkMs != null && tMs - lastVisualOkMs <= config.visualFreshMs
        val target = when (health) {
            GnssHealth.GOOD -> NavMode.GNSS
            GnssHealth.DEGRADED -> NavMode.FUSED
            GnssHealth.UNTRUSTED -> if (visualFresh) NavMode.VISUAL else NavMode.DEAD_RECKONING
        }
        if (target == mode) { pending = null; return mode }
        if (pending != target) { pending = target; pendingSince = tMs }
        val delay = when {
            mode == NavMode.DEAD_RECKONING && target == NavMode.VISUAL -> 0.0
            target.ordinal > mode.ordinal -> config.worseDelayMs
            else -> config.betterDelayMs
        }
        if (tMs - pendingSince >= delay) { mode = target; pending = null }
        return mode
    }
}
