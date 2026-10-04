package io.visnav.app

import io.visnav.core.MapPalette
import io.visnav.core.MapStyle
import org.json.JSONObject
import java.io.File

/** Offline map data from `refpack/map/`: style builder, `bounds [minlon,minlat,maxlon,maxlat]`, attribution. */
class MapData(
    val dir: File,
    val styleJson: (night: Boolean) -> String,
    val bounds: DoubleArray,
    val attribution: String,
)

object MapDataLoader {
    fun load(refpackDir: File): Result<MapData> = runCatching {
        val dir = File(refpackDir, "map")
        val mbtiles = File(dir, "corridor.mbtiles")
        val fonts = File(dir, "fonts")
        val glyphs = File(fonts, "Noto Sans Regular/0-255.pbf")
        val meta = File(dir, "map.json")
        val missing = listOf(mbtiles, glyphs, meta).filterNot { it.isFile }
        if (missing.isNotEmpty()) error("нет карты: " + missing.joinToString { it.path })
        val json = JSONObject(meta.readText())
        val b = json.getJSONArray("bounds")
        require(b.length() == 4) { "map.json: bounds must have 4 numbers, got ${b.length()}" }
        MapData(
            dir = dir,
            styleJson = { night ->
                MapStyle.build(mbtiles.absolutePath, fonts.absolutePath, if (night) MapPalette.NIGHT else MapPalette.DAY)
            },
            bounds = DoubleArray(4) { b.getDouble(it) },
            attribution = json.getString("attribution"),
        )
    }
}
