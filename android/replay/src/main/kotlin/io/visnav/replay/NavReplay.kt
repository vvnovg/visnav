package io.visnav.replay

import io.visnav.core.Enu
import io.visnav.core.NavEvent
import io.visnav.core.NavFormat
import io.visnav.core.RoadIndex
import io.visnav.core.RoadPack
import io.visnav.core.RouteFollower
import java.io.File

/** Ведение по маршруту по готовой траектории replay: тот же RouteFollower, что на телефоне. */
fun runNavigation(points: List<TrajPoint>, roads: RoadPack, destLat: Double, destLon: Double): Pair<Enu, List<NavEvent>> {
    require(points.isNotEmpty()) { "no trajectory points to navigate" }
    val enu = Enu(points[0].lat, points[0].lon)
    val dest = enu.toEn(destLat, destLon)
    val follower = RouteFollower(RoadIndex(roads, enu), dest[0], dest[1])
    val events = ArrayList<NavEvent>()
    for (p in points) {
        if (!p.lat.isFinite() || !p.lon.isFinite()) continue
        val en = enu.toEn(p.lat, p.lon)
        events += follower.update(p.tMs, en[0], en[1], p.sigmaM, p.psiRad, p.speedMps)
    }
    return enu to events
}

fun writeNav(file: File, header: String, enu: Enu, events: List<NavEvent>) {
    file.parentFile?.mkdirs()
    file.bufferedWriter().use { w ->
        w.write(header); w.newLine()
        for (e in events) { w.write(NavFormat.event(e, enu)); w.newLine() }
    }
}
