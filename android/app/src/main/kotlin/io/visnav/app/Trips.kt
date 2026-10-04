package io.visnav.app

import java.io.File

/** Выбранная поездка: имя (null — старая раскладка без trips/), каталог данных и модель. */
data class TripDirs(val name: String?, val dataDir: File, val model: File)

/**
 * Раскладка на телефоне: `<root>/model.onnx` — общая модель, `<root>/trips/<имя>/` — пакет поездки
 * (vpr-m3 pack-trip). Без `trips/` данные лежат прямо в `<root>` (старая раскладка).
 */
object TripLayout {
    /**
     * null — каталога trips/ нет (старая раскладка); иначе имена поездок (подкаталог с refpack.bin,
     * имя не начинается с "."), по алфавиту.
     */
    fun list(root: File): List<String>? {
        val trips = File(root, "trips")
        if (!trips.isDirectory) return null
        return trips.listFiles().orEmpty()
            // Каталоги на "." — не поездки: pack-trip собирает в trips/.<имя>.tmp, на телефоне это остаток прерванной копии.
            .filter { !it.name.startsWith(".") && it.isDirectory && File(it, "refpack.bin").isFile }
            .map { it.name }
            .sorted()
    }

    /** Каталог данных и модель для выбранной поездки; при исчезнувшей поездке — первая по алфавиту. */
    fun resolve(root: File, wanted: String?): Result<TripDirs> {
        val rootModel = File(root, "model.onnx")
        val names = list(root) ?: return Result.success(TripDirs(null, root, rootModel))
        if (names.isEmpty()) {
            return Result.failure(IllegalStateException(
                "Нет поездок в ${File(root, "trips").absolutePath}: положите пакет vpr-m3 pack-trip через adb push"))
        }
        val name = if (wanted != null && wanted in names) wanted else names.first()
        val dir = File(File(root, "trips"), name)
        val own = File(dir, "model.onnx")
        return Result.success(TripDirs(name, dir, if (own.isFile) own else rootModel))
    }
}
