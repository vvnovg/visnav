package io.visnav.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Only the missing-files branch: it returns before map.json is parsed, so the android.jar
// org.json stub (throws "not mocked" in local JVM tests) is never reached.
class MapDataLoaderTest {
    /** Runs [body] on a fresh temp refpack directory and always deletes it afterwards. */
    private fun withRefpack(body: (File) -> Unit) {
        val refpack = Files.createTempDirectory("refpack").toFile()
        try {
            body(refpack)
        } finally {
            refpack.deleteRecursively()
        }
    }

    @Test fun emptyRefpackReportsAllThreeFiles() {
        withRefpack { refpack ->
            val e = MapDataLoader.load(refpack).exceptionOrNull()!!
            val msg = e.message!!
            assertTrue(msg.startsWith("нет карты: "), msg)
            assertTrue("corridor.mbtiles" in msg && "Noto Sans Regular/0-255.pbf" in msg && "map.json" in msg, msg)
        }
    }

    @Test fun reportsOnlyMissingFiles() {
        withRefpack { refpack ->
            val map = File(refpack, "map")
            File(map, "fonts/Noto Sans Regular").mkdirs()
            File(map, "corridor.mbtiles").writeBytes(byteArrayOf(0))
            File(map, "fonts/Noto Sans Regular/0-255.pbf").writeBytes(byteArrayOf(0))
            val e = MapDataLoader.load(refpack).exceptionOrNull()!!
            assertEquals("нет карты: " + File(map, "map.json").path, e.message)
        }
    }

    @Test fun directoryNamedLikeFileCountsAsMissing() {
        withRefpack { refpack ->
            val map = File(refpack, "map")
            File(map, "corridor.mbtiles").mkdirs()
            File(map, "fonts/Noto Sans Regular").mkdirs()
            File(map, "fonts/Noto Sans Regular/0-255.pbf").writeBytes(byteArrayOf(0))
            File(map, "map.json").writeText("{}")
            val e = MapDataLoader.load(refpack).exceptionOrNull()!!
            assertEquals("нет карты: " + File(map, "corridor.mbtiles").path, e.message)
        }
    }
}
