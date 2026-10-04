package io.visnav.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavFormatTest {
    private val enu = Enu(55.75, 37.60)

    @Test fun headerAndEvents() {
        assertEquals(
            "{\"type\":\"nav\",\"session_started_ms\":5,\"roads\":\"r1\",\"dest\":[55.7,37.6]," +
                "\"outages\":[[1,2]],\"jams\":[],\"spoofs\":[]}",
            NavFormat.header(5L, "r1", 55.7, 37.6, listOf(longArrayOf(1, 2)), emptyList(), emptyList()),
        )
        val p = NavFormat.event(NavEvent.Prompt(7L, 1, PromptStage.NEAR, "Через 80 м поверните направо", 81.0), enu)
        assertEquals("{\"t_ms\":7,\"ev\":\"prompt\",\"maneuver\":1,\"then\":null,\"stage\":\"near\",\"dist_m\":81.0," +
            "\"text\":\"Через 80 м поверните направо\"}", p)
        assertEquals("{\"t_ms\":9,\"ev\":\"arrive\"}", NavFormat.event(NavEvent.Arrived(9L), enu))
        val route = Route(listOf(RouteStep(0, true)), listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(100.0, 0.0)),
            doubleArrayOf(0.0, 100.0), 18.0)
        val m = listOf(Maneuver(ManeuverType.DEPART, 0.0, 0.0, 0.0, "Улица"), Maneuver(ManeuverType.ARRIVE, 100.0, 100.0, 0.0, null))
        val line = NavFormat.event(NavEvent.RouteReady(3L, route, m, false), enu)
        assertTrue(line.startsWith("{\"t_ms\":3,\"ev\":\"route\",\"reroute\":false,\"length_m\":100.0,\"duration_s\":18.0,\"polyline\":[["))
        assertTrue(line.contains("{\"type\":\"depart\",\"at_m\":0.0,") && line.contains("\"street\":\"Улица\",\"exit\":0}"))
        assertTrue(line.contains("{\"type\":\"arrive\",\"at_m\":100.0,") && line.contains("\"street\":null,\"exit\":0}"))
    }

    @Test fun nullsEscapingThenAndArriveText() {
        assertEquals(
            "{\"type\":\"nav\",\"session_started_ms\":5,\"roads\":null,\"dest\":[null,37.6]," +
                "\"outages\":[],\"jams\":[[3,4],[5,6]],\"spoofs\":[]}",
            NavFormat.header(5L, null, Double.NaN, 37.6, emptyList(), listOf(longArrayOf(3, 4), longArrayOf(5, 6)), emptyList()),
        )
        val esc = NavFormat.event(NavEvent.Prompt(1L, 0, PromptStage.FAR, "a\"b\\c", Double.NaN), enu)
        assertEquals("{\"t_ms\":1,\"ev\":\"prompt\",\"maneuver\":0,\"then\":null,\"stage\":\"far\",\"dist_m\":null," +
            "\"text\":\"a\\\"b\\\\c\"}", esc)
        val text = Instructions.prompt(Maneuver(ManeuverType.ARRIVE, 500.0, 0.0, 0.0, null), 400.0)
        assertEquals("Через 400 м — пункт назначения", text)
        val far = NavFormat.event(NavEvent.Prompt(2L, 3, PromptStage.FAR, text, 400.0, thenManeuver = 4), enu)
        assertEquals("{\"t_ms\":2,\"ev\":\"prompt\",\"maneuver\":3,\"then\":4,\"stage\":\"far\",\"dist_m\":400.0," +
            "\"text\":\"Через 400 м — пункт назначения\"}", far)
        assertEquals("{\"t_ms\":4,\"ev\":\"route_failed\"}", NavFormat.event(NavEvent.RouteFailed(4L), enu))
    }

    /**
     * Общий для Kotlin и Python журнал: тест пишет research/vpr_bench/tests/data/nav_golden.jsonl, если файла нет
     * или он отличается, и падает, пока сгенерированный текст не совпадёт с закоммиченным (его читает
     * test_naveval.py через read_nav).
     */
    @Test fun goldenNavLogMatchesCommittedFile() {
        val golden = File("../../research/vpr_bench/tests/data/nav_golden.jsonl")
        val route = Route(
            listOf(RouteStep(0, true), RouteStep(1, true), RouteStep(2, true)),
            listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(300.0, 0.0), doubleArrayOf(300.0, -200.0), doubleArrayOf(450.0, -200.0)),
            doubleArrayOf(0.0, 300.0, 500.0, 650.0), 75.5,
        )
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, 0.0, 0.0, 0.0, "Улица А"),
            Maneuver(ManeuverType.RIGHT, 300.0, 300.0, 0.0, "Тверская улица", angleDeg = 90.0),
            Maneuver(ManeuverType.LEFT, 500.0, 300.0, -200.0, null, angleDeg = -90.0),
            Maneuver(ManeuverType.ARRIVE, 650.0, 450.0, -200.0, null),
        )
        val lines = listOf(
            NavFormat.header(1_700_000_000_000L, "2026-10-01T00:00:00Z", 55.748, 37.604,
                listOf(longArrayOf(1_700_000_030_000L, 1_700_000_045_000L)), emptyList(), emptyList()),
            NavFormat.event(NavEvent.RouteReady(1_700_000_001_000L, route, ms, false), enu),
            NavFormat.event(NavEvent.Prompt(1_700_000_010_000L, 1, PromptStage.FAR, "Через 300 м поверните направо — Тверская улица", 300.0), enu),
            NavFormat.event(NavEvent.Prompt(1_700_000_020_000L, 1, PromptStage.NEAR,
                "Через 100 м поверните направо — Тверская улица, затем налево", 100.0, thenManeuver = 2), enu),
            NavFormat.event(NavEvent.Prompt(1_700_000_027_000L, 1, PromptStage.NOW,
                "Поверните направо — Тверская улица, затем налево", 20.0, thenManeuver = 2), enu),
            NavFormat.event(NavEvent.Prompt(1_700_000_050_000L, 3, PromptStage.NOW, "Вы прибыли", 0.0), enu),
            NavFormat.event(NavEvent.Arrived(1_700_000_050_000L), enu),
        )
        val text = lines.joinToString("\n", postfix = "\n")
        val committed = if (golden.isFile) golden.readText() else null
        if (committed != text) golden.writeText(text)
        assertEquals(committed, text, "nav_golden.jsonl перезаписан — закоммитьте его")
    }
}
