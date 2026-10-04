package io.visnav.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripLayoutTest {
    private fun withRoot(body: (File) -> Unit) {
        val root = Files.createTempDirectory("trips").toFile()
        try {
            body(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun touch(root: File, path: String): File =
        File(root, path).also { it.parentFile?.mkdirs(); it.writeText("x") }

    @Test fun legacyWhenNoTripsDir() = withRoot { root ->
        assertNull(TripLayout.list(root))
        assertEquals(TripDirs(null, root, File(root, "model.onnx")), TripLayout.resolve(root, null).getOrThrow())
    }

    @Test fun listsOnlyDirsWithRefpack() = withRoot { root ->
        touch(root, "trips/b/refpack.bin")
        touch(root, "trips/a/refpack.bin")
        File(root, "trips/c").mkdirs()
        touch(root, "trips/x.txt")
        touch(root, "trips/.x.tmp/refpack.bin")
        assertEquals(listOf("a", "b"), TripLayout.list(root))
    }

    @Test fun resolvePicksWantedOrFirst() = withRoot { root ->
        touch(root, "trips/a/refpack.bin")
        touch(root, "trips/b/refpack.bin")
        val trips = File(root, "trips")
        assertEquals(TripDirs("b", File(trips, "b"), File(root, "model.onnx")), TripLayout.resolve(root, "b").getOrThrow())
        assertEquals("a", TripLayout.resolve(root, "gone").getOrThrow().name)
        assertEquals(File(trips, "a"), TripLayout.resolve(root, null).getOrThrow().dataDir)
        touch(root, "trips/.hidden/refpack.bin")
        File(root, "trips/empty").mkdirs()
        assertEquals("a", TripLayout.resolve(root, ".hidden").getOrThrow().name)
        assertEquals("a", TripLayout.resolve(root, "empty").getOrThrow().name)
    }

    @Test fun modelFromTripOrRoot() = withRoot { root ->
        touch(root, "trips/a/refpack.bin")
        touch(root, "trips/b/refpack.bin")
        val own = touch(root, "trips/a/model.onnx")
        assertEquals(own, TripLayout.resolve(root, "a").getOrThrow().model)
        assertEquals(File(root, "model.onnx"), TripLayout.resolve(root, "b").getOrThrow().model)
    }

    @Test fun emptyTripsDirFails() = withRoot { root ->
        File(root, "trips/c").mkdirs()
        assertEquals(emptyList(), TripLayout.list(root))
        val r = TripLayout.resolve(root, null)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("Нет поездок"))
    }
}
