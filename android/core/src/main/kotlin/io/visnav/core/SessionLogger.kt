package io.visnav.core

import java.io.Closeable
import java.io.File

/** JSONL-журнал сессии: заголовок, затем по строке на кадр. Сбрасывается на диск каждые 10 кадров и при закрытии. */
class SessionLogger(file: File) : Closeable {
    private val writer = file.bufferedWriter()
    private var frames = 0

    fun header(h: SessionHeader) {
        writer.write(LogJson.line(h)); writer.newLine(); writer.flush()
    }

    fun frame(r: FrameRecord) {
        writer.write(LogJson.line(r)); writer.newLine()
        if (++frames % 10 == 0) writer.flush()
    }

    override fun close() {
        writer.flush(); writer.close()
    }
}
