package io.visnav.app

import io.visnav.core.MapPalette
import io.visnav.core.MapStyle
import org.json.JSONException
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
    fun load(refpackDir: File): Result<MapData> {
        val dir = File(refpackDir, "map")
        val mbtiles = File(dir, "corridor.mbtiles")
        val fonts = File(dir, "fonts")
        val glyphs = File(fonts, "Noto Sans Regular/0-255.pbf")
        val meta = File(dir, "map.json")
        val missing = listOf(mbtiles, glyphs, meta).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            return Result.failure(IllegalStateException("нет карты: " + missing.joinToString { it.path }))
        }
        val (bounds, attribution) = try {
            parseMeta(meta.readText())
        } catch (e: JSONException) {
            return Result.failure(IllegalStateException("map.json: ${e.message}", e))
        } catch (e: IllegalArgumentException) {
            return Result.failure(IllegalStateException("map.json: ${e.message}", e))
        } catch (e: java.io.IOException) {
            return Result.failure(IllegalStateException("map.json: ${e.message}", e))
        }
        return Result.success(
            MapData(
                dir = dir,
                styleJson = { night ->
                    MapStyle.build(mbtiles.absolutePath, fonts.absolutePath, if (night) MapPalette.NIGHT else MapPalette.DAY)
                },
                bounds = bounds,
                attribution = attribution,
            ),
        )
    }

    private fun parseMeta(text: String): Pair<DoubleArray, String> {
        val json = JSONObject(text)
        val b = json.getJSONArray("bounds")
        require(b.length() == 4) { "bounds must have 4 numbers, got ${b.length()}" }
        val bounds = DoubleArray(4) { b.getDouble(it) }
        require(bounds.all { it.isFinite() }) { "bounds must be finite: ${bounds.toList()}" }
        require(bounds[0] < bounds[2] && bounds[1] < bounds[3]) {
            "bounds must be [minlon,minlat,maxlon,maxlat] with min < max: ${bounds.toList()}"
        }
        return bounds to json.getString("attribution")
    }
}
