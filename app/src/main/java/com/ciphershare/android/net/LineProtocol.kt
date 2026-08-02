package com.ciphershare.android.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Mirrors CipherShare (desktop) Services/LineProtocol.cs. The transfer protocol mixes small
 * JSON "lines" (the header, the per-file trailer) with raw binary file bytes on the same TCP
 * stream. A buffered reader is dangerous here because it reads ahead into its own buffer and
 * can accidentally swallow bytes that belong to the next file's binary data. These two
 * functions read/write one line at a time using only the exact bytes they need, so the raw
 * binary reads that follow always start in the right place - this must stay byte-for-byte
 * compatible with the desktop side or the two apps will desync mid-transfer.
 */
object LineProtocol {

    fun writeLine(stream: OutputStream, line: String) {
        val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
        stream.write(bytes)
        stream.flush()
    }

    /** Returns null if the connection closed before any bytes of a line were read. */
    fun readLine(stream: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        val single = ByteArray(1)

        while (true) {
            val read = stream.read(single, 0, 1)
            if (read == -1) {
                return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.UTF_8.name())
            }
            if (single[0] == '\n'.code.toByte()) {
                return buffer.toString(StandardCharsets.UTF_8.name())
            }
            buffer.write(single[0].toInt())
        }
    }
}
