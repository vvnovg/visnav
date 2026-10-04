package io.visnav.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Colours of the map style, all `#RRGGBB`. */
data class MapPalette(
    val background: String,
    val water: String,
    val park: String,
    val building: String,
    val roadMinor: String,
    val roadMajor: String,
    val roadMotorway: String,
    val casing: String,
    val label: String,
    val labelHalo: String,
    val route: String,
) {
    companion object {
        val DAY = MapPalette(
            background = "#F2EFE9", water = "#AAD3DF", park = "#CDEBB0", building = "#D9D0C9",
            roadMinor = "#FFFFFF", roadMajor = "#FCD6A4", roadMotorway = "#E892A2", casing = "#BBBBBB",
            label = "#333333", labelHalo = "#FFFFFF", route = "#1E88E5",
        )
        val NIGHT = MapPalette(
            background = "#1E2126", water = "#2B3A4A", park = "#24332A", building = "#2E3135",
            roadMinor = "#3B4048", roadMajor = "#6B5B3E", roadMotorway = "#7A4A55", casing = "#15171A",
            label = "#D0D0D0", labelHalo = "#1E2126", route = "#64B5F6",
        )
    }
}

/** MapLibre style (spec version 8) for offline OpenMapTiles mbtiles with navigation overlay layers. */
object MapStyle {
    const val ROUTE_SOURCE = "route"
    const val MANEUVER_SOURCE = "maneuvers"
    const val ACCURACY_SOURCE = "accuracy"
    const val MARKER_SOURCE = "marker"
    const val HEADING_SOURCE = "heading"

    private const val OMT = "omt"
    private val ALL_ROADS = listOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service")

    fun build(mbtilesPath: String, fontsDir: String, palette: MapPalette): String = buildJsonObject {
        put("version", 8)
        put("sources", buildJsonObject {
            put(OMT, buildJsonObject {
                put("type", "vector")
                put("url", "mbtiles://$mbtilesPath")
            })
            for (id in listOf(ROUTE_SOURCE, MANEUVER_SOURCE, ACCURACY_SOURCE, MARKER_SOURCE, HEADING_SOURCE)) {
                put(id, buildJsonObject {
                    put("type", "geojson")
                    put("data", buildJsonObject {
                        put("type", "FeatureCollection")
                        put("features", JsonArray(emptyList()))
                    })
                })
            }
        })
        put("glyphs", "file://$fontsDir/{fontstack}/{range}.pbf")
        put("layers", buildJsonArray {
            add(layer("background", "background", paint = mapOf("background-color" to str(palette.background))))
            add(layer("water", "fill", OMT, "water", paint = mapOf("fill-color" to str(palette.water))))
            add(layer("park", "fill", OMT, "park",
                paint = mapOf("fill-color" to str(palette.park), "fill-opacity" to num(0.6))))
            add(layer("building", "fill", OMT, "building", minzoom = 14,
                paint = mapOf("fill-color" to str(palette.building))))

            val notTunnel = expr("!=", expr("get", "brunnel"), "tunnel")
            val tunnel = expr("==", expr("get", "brunnel"), "tunnel")
            fun roadFilter(classes: List<String>, extra: JsonElement?): JsonElement {
                val cls = expr("match", expr("get", "class"), strings(classes), JsonPrimitive(true), JsonPrimitive(false))
                return if (extra == null) cls else expr("all", cls, extra)
            }
            val round = mapOf("line-cap" to str("round"), "line-join" to str("round"))
            add(layer("road-casing", "line", OMT, "transportation",
                filter = roadFilter(ALL_ROADS, notTunnel), layout = round,
                paint = mapOf("line-color" to str(palette.casing), "line-width" to widthByZoom(13, 3.0, 18, 16.0))))
            add(layer("road-minor", "line", OMT, "transportation",
                filter = roadFilter(listOf("minor", "service"), notTunnel), layout = round,
                paint = mapOf("line-color" to str(palette.roadMinor), "line-width" to widthByZoom(13, 2.0, 18, 8.0))))
            add(layer("road-major", "line", OMT, "transportation",
                filter = roadFilter(listOf("primary", "secondary", "tertiary"), notTunnel), layout = round,
                paint = mapOf("line-color" to str(palette.roadMajor), "line-width" to widthByZoom(13, 3.0, 18, 12.0))))
            add(layer("road-motorway", "line", OMT, "transportation",
                filter = roadFilter(listOf("motorway", "trunk"), notTunnel), layout = round,
                paint = mapOf("line-color" to str(palette.roadMotorway), "line-width" to widthByZoom(13, 4.0, 18, 14.0))))
            add(layer("road-tunnel", "line", OMT, "transportation",
                filter = roadFilter(ALL_ROADS, tunnel),
                paint = mapOf(
                    "line-color" to str(palette.casing),
                    "line-width" to widthByZoom(13, 2.0, 18, 8.0),
                    "line-dasharray" to buildJsonArray { add(JsonPrimitive(2)); add(JsonPrimitive(1)) },
                )))

            val routeWidth = widthByZoom(13, 5.0, 18, 14.0)
            add(layer("route-casing", "line", ROUTE_SOURCE, layout = round,
                paint = mapOf("line-color" to str("#FFFFFF"), "line-width" to widthByZoom(13, 7.0, 18, 16.0))))
            add(layer("route", "line", ROUTE_SOURCE, layout = round,
                paint = mapOf("line-color" to str(palette.route), "line-width" to routeWidth)))
            add(layer("maneuvers", "circle", MANEUVER_SOURCE, paint = mapOf(
                "circle-radius" to num(5), "circle-color" to str("#FFFFFF"),
                "circle-stroke-color" to str(palette.route), "circle-stroke-width" to num(2))))
            add(layer("accuracy", "fill", ACCURACY_SOURCE,
                paint = mapOf("fill-color" to expr("get", "color"), "fill-opacity" to num(0.2))))
            add(layer("accuracy-outline", "line", ACCURACY_SOURCE,
                paint = mapOf("line-color" to expr("get", "color"), "line-width" to num(1.5))))
            add(layer("heading", "fill", HEADING_SOURCE, paint = mapOf("fill-color" to expr("get", "color"))))
            add(layer("marker", "circle", MARKER_SOURCE, paint = mapOf(
                "circle-radius" to num(8), "circle-color" to expr("get", "color"),
                "circle-stroke-color" to str("#FFFFFF"), "circle-stroke-width" to num(2))))

            val labelPaint = mapOf(
                "text-color" to str(palette.label), "text-halo-color" to str(palette.labelHalo),
                "text-halo-width" to num(1.5))
            add(layer("road-label", "symbol", OMT, "transportation_name",
                layout = mapOf(
                    "symbol-placement" to str("line"),
                    "text-field" to expr("coalesce", expr("get", "name:ru"), expr("get", "name")),
                    "text-font" to strings(listOf("Noto Sans Regular")),
                    "text-size" to interpolate(13, 11.0, 18, 14.0, exponential = false),
                ), paint = labelPaint))
            add(layer("place-label", "symbol", OMT, "place",
                filter = expr("match", expr("get", "class"), strings(listOf("city", "town", "suburb", "neighbourhood")),
                    JsonPrimitive(true), JsonPrimitive(false)),
                layout = mapOf(
                    "text-field" to expr("coalesce", expr("get", "name:ru"), expr("get", "name")),
                    "text-font" to strings(listOf("Noto Sans Bold")),
                    "text-size" to num(14),
                ), paint = labelPaint))
        })
    }.toString()

    private fun str(s: String): JsonElement = JsonPrimitive(s)
    private fun num(n: Number): JsonElement = JsonPrimitive(n)
    private fun strings(l: List<String>): JsonArray = JsonArray(l.map { JsonPrimitive(it) })

    private fun expr(op: String, vararg args: Any): JsonArray = buildJsonArray {
        add(JsonPrimitive(op))
        for (a in args) add(if (a is JsonElement) a else JsonPrimitive(a as String))
    }

    private fun interpolate(z0: Int, w0: Double, z1: Int, w1: Double, exponential: Boolean = true): JsonArray =
        buildJsonArray {
            add(JsonPrimitive("interpolate"))
            add(if (exponential) buildJsonArray { add(JsonPrimitive("exponential")); add(JsonPrimitive(1.5)) }
                else buildJsonArray { add(JsonPrimitive("linear")) })
            add(buildJsonArray { add(JsonPrimitive("zoom")) })
            add(JsonPrimitive(z0)); add(JsonPrimitive(w0)); add(JsonPrimitive(z1)); add(JsonPrimitive(w1))
        }

    private fun widthByZoom(z0: Int, w0: Double, z1: Int, w1: Double) = interpolate(z0, w0, z1, w1)

    private fun layer(
        id: String, type: String, source: String? = null, sourceLayer: String? = null,
        minzoom: Int? = null, filter: JsonElement? = null,
        layout: Map<String, JsonElement> = emptyMap(), paint: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        if (source != null) put("source", source)
        if (sourceLayer != null) put("source-layer", sourceLayer)
        if (minzoom != null) put("minzoom", minzoom)
        if (filter != null) put("filter", filter)
        if (layout.isNotEmpty()) put("layout", JsonObject(layout))
        put("paint", JsonObject(paint))
    }
}
