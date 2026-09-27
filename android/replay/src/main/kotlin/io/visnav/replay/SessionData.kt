package io.visnav.replay

import io.visnav.core.DescriptorLog
import io.visnav.core.FrameRecord
import io.visnav.core.SensorEvent
import io.visnav.core.SessionHeader
import io.visnav.core.readSensorLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SessionData(
    val header: SessionHeader,
    val frames: List<FrameRecord>,
    val sensors: List<SensorEvent>,
    val descriptors: DescriptorLog,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** prefix — путь без расширения: <prefix>.jsonl, <prefix>.sensors.jsonl, <prefix>.desc. */
        fun load(prefix: File): SessionData {
            val framesFile = File("$prefix.jsonl")
            val lines = framesFile.readLines().filter { it.isNotBlank() }
            var header: SessionHeader? = null
            val frames = ArrayList<FrameRecord>()
            for ((i, line) in lines.withIndex()) {
                val parsed = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (parsed == null) {
                    if (i == lines.lastIndex) break // обрезанная последняя строка
                    throw IllegalArgumentException("${framesFile.name}:${i + 1}: invalid JSON")
                }
                when (parsed["type"]?.jsonPrimitive?.content) {
                    "session" -> header = json.decodeFromString(SessionHeader.serializer(), line)
                    "frame" -> frames.add(json.decodeFromString(FrameRecord.serializer(), line))
                }
            }
            return SessionData(
                requireNotNull(header) { "${framesFile.name}: no session header" },
                frames,
                readSensorLog(File("$prefix.sensors.jsonl")),
                DescriptorLog.read(File("$prefix.desc")),
            )
        }
    }
}
