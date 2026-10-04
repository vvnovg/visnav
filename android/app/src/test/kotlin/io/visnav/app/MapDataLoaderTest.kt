package io.visnav.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Only the missing-files branch: it returns before map.json is parsed, so the android.jar
// org.json stub (throws "not mocked" in local JVM tests) is never reached.
class MapDataLoaderTest {
    private fun tempRefpack(): File = Files.createTempDirectory("refpack").toFile().apply { deleteOnExit() }

    @Test fun emptyRefpackReportsAllThreeFiles() {
        val refpack = tempRefpack()
        val e = MapDataLoader.load(refpack).exceptionOrNull()!!
        val msg = e.message!!
        assertTrue(msg.startsWith("нет карты: "), msg)
        assertTrue("corridor.mbtiles" in msg && "Noto Sans Regular/0-255.pbf" in msg && "map.json" in msg, msg)
        refpack.deleteRecursively()
    }

    @Test fun reportsOnlyMissingFiles() {
        val refpack = tempRefpack()
        val map = File(refpack, "map")
        File(map, "fonts/Noto Sans Regular").mkdirs()
        File(map, "corridor.mbtiles").writeBytes(byteArrayOf(0))
        File(map, "fonts/Noto Sans Regular/0-255.pbf").writeBytes(byteArrayOf(0))
        val e = MapDataLoader.load(refpack).exceptionOrNull()!!
        assertEquals("нет карты: " + File(map, "map.json").path, e.message)
        refpack.deleteRecursively()
    }

    @Test fun directoryNamedLikeFileCountsAsMissing() {
        val refpack = tempRefpack()
        val map = File(refpack, "map")
        File(map, "corridor.mbtiles").mkdirs()
        File(map, "fonts/Noto Sans Regular").mkdirs()
        File(map, "fonts/Noto Sans Regular/0-255.pbf").writeBytes(byteArrayOf(0))
        File(map, "map.json").writeText("{}")
        val e = MapDataLoader.load(refpack).exceptionOrNull()!!
        assertEquals("нет карты: " + File(map, "corridor.mbtiles").path, e.message)
        refpack.deleteRecursively()
    }
}
