package io.visnav.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapStyleTest {
    private fun style(p: MapPalette = MapPalette.DAY): JsonObject = Json.parseToJsonElement(
        MapStyle.build("/sdcard/Android/data/io.visnav.app/files/refpack/map/corridor.mbtiles",
            "/sdcard/Android/data/io.visnav.app/files/refpack/map/fonts", p),
    ).jsonObject

    @Test fun sourcesAndGlyphs() {
        val s = style()
        assertEquals(8, s["version"]!!.jsonPrimitive.content.toInt())
        val src = s["sources"]!!.jsonObject
        assertEquals("mbtiles:///sdcard/Android/data/io.visnav.app/files/refpack/map/corridor.mbtiles",
            src["omt"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        for (id in listOf(MapStyle.ROUTE_SOURCE, MapStyle.MANEUVER_SOURCE, MapStyle.ACCURACY_SOURCE,
            MapStyle.MARKER_SOURCE, MapStyle.HEADING_SOURCE)) {
            assertEquals("geojson", src[id]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        assertEquals("file:///sdcard/Android/data/io.visnav.app/files/refpack/map/fonts/{fontstack}/{range}.pbf",
            s["glyphs"]!!.jsonPrimitive.content)
        assertTrue("sprite" !in s)
    }

    @Test fun layerOrderAndSourceLayers() {
        val layers = style()["layers"]!!.jsonArray.map { it.jsonObject }
        val ids = layers.map { it["id"]!!.jsonPrimitive.content }
        val order = listOf("background", "water", "park", "building", "road-casing-minor", "road-casing-major", "road-casing-motorway", "road-minor", "road-major",
            "road-motorway", "route-casing", "route", "maneuvers", "accuracy", "accuracy-outline", "heading", "marker",
            "road-label", "place-label")
        assertEquals(order, ids.filter { it in order })
        val byId = layers.associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals("transportation", byId["road-major"]!!["source-layer"]!!.jsonPrimitive.content)
        assertEquals("transportation_name", byId["road-label"]!!["source-layer"]!!.jsonPrimitive.content)
        val font = byId["road-label"]!!["layout"]!!.jsonObject["text-font"] as JsonArray
        assertEquals("Noto Sans Regular", font[0].jsonPrimitive.content)
    }

    @Test fun nightPaletteChangesBackground() {
        fun bg(p: MapPalette) = style(p)["layers"]!!.jsonArray[0].jsonObject["paint"]!!.jsonObject["background-color"]!!
            .jsonPrimitive.content
        assertEquals(MapPalette.DAY.background, bg(MapPalette.DAY))
        assertEquals(MapPalette.NIGHT.background, bg(MapPalette.NIGHT))
        assertTrue(MapPalette.DAY.background != MapPalette.NIGHT.background)
    }

    @Test fun pathsWithQuotesAreEscaped() {
        val s = Json.parseToJsonElement(MapStyle.build("/a\"b.mbtiles", "/f", MapPalette.DAY)).jsonObject
        assertEquals("mbtiles:///a\"b.mbtiles", s["sources"]!!.jsonObject["omt"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun styleIsSelfConsistent() {
        val s = style()
        val sources = s["sources"]!!.jsonObject
        val layers = s["layers"]!!.jsonArray.map { it.jsonObject }
        val ids = layers.map { it["id"]!!.jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
        for (l in layers) {
            val src = l["source"]?.jsonPrimitive?.content
            if (src != null) {
                assertTrue(src in sources, "unknown source $src")
                val hasSl = "source-layer" in l
                if (src == "omt") assertTrue(hasSl, "${l["id"]} needs source-layer") else assertTrue(!hasSl)
            }
            val font = l["layout"]?.jsonObject?.get("text-font")?.jsonArray
            font?.forEach { assertTrue(it.jsonPrimitive.content in setOf("Noto Sans Regular", "Noto Sans Bold")) }
        }
        val tunnel = layers.first { it["id"]!!.jsonPrimitive.content == "road-tunnel" }
        assertTrue("line-dasharray" in tunnel["paint"]!!.jsonObject)
    }
}
