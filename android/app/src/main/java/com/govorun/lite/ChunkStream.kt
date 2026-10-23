package com.govorun.lite

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteOrder

/** Отдаёт аудио порциями (FloatArray моно) без загрузки всего файла в RAM. */
interface ChunkStream : Closeable {
    val sampleRate: Int
    /** Возвращает очередную порцию (~30 сек) или пустой массив в конце. */
    fun readNext(): FloatArray
}

/** Открывает нужный ридер по MIME/расширению. */
fun openChunkStream(context: Context, uri: Uri): ChunkStream {
    val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
    val name = queryDisplayName(context, uri).lowercase()
    val isWav = mime in setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave") ||
                name.endsWith(".wav")
    return if (isWav) {
        val ins = context.contentResolver.openInputStream(uri)
            ?: error("Не удалось открыть поток")
        WavChunkStream(ins)
    } else {
        MediaCodecChunkStream(context, uri)
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "" else ""
        } ?: ""
    } catch (_: Exception) { "" }
}

/** Потоковое чтение WAV (16-bit PCM). Не грузит файл целиком. */
class WavChunkStream(input: InputStream) : ChunkStream {
    private val s = BufferedInputStream(input, 64 * 1024)
    override val sampleRate: Int
    private val channels: Int
    private val totalDataBytes: Long
    private var bytesRead = 0L
    private val targetFrames = 30 * 16000  // ~30 сек при 16к, или эквивалент по времени

    init {
        val h = ByteArray(12)
        readFully(s, h)
        require(String(h, 0, 4) == "RIFF" && String(h, 8, 4) == "WAVE") { "Не WAV-файл" }
        var sr = 0; var ch = 0; var bits = 0
        var dSize = 0L
        while (true) {
            val cid = ByteArray(4); readFully(s, cid)
            val id = String(cid)
            val sz = readIntLE(s)
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(sz); readFully(s, fmt)
                    ch = readShortLE(fmt, 2).toInt() and 0xFFFF
                    sr = readIntLE(fmt, 4)
                    bits = readShortLE(fmt, 14).toInt() and 0xFFFF
                }
                "data" -> { dSize = sz.toLong() and 0xFFFFFFFFL; break }
                else -> skipFully(s, sz.toLong())
            }
            if (sz % 2 == 1) skipFully(s, 1)
        }
        require(bits == 16) { "Поддерживается только WAV 16-bit PCM (у тебя $bits бит)" }
        sampleRate = sr
        channels = ch
        totalDataBytes = dSize
    }

    override fun readNext(): FloatArray {
        if (bytesRead >= totalDataBytes) return FloatArray(0)
        val frameBytes = channels * 2
        val framesAvail = ((totalDataBytes - bytesRead) / frameBytes).toInt()
        // Ограничиваем ~30 сек по времени (по исходной частоте)
        val maxFrames = (30.0 * sampleRate).toInt().coerceAtLeast(1)
        val toRead = minOf(maxFrames, framesAvail)
        val bytes = ByteArray(toRead * frameBytes)
        readFully(s, bytes)
        bytesRead += bytes.size

        val out = FloatArray(toRead)
        for (i in 0 until toRead) {
            var sum = 0
            for (c in 0 until channels) {
                val off = (i * channels + c) * 2
                val sample = ((bytes[off + 1].toInt() shl 8) or (bytes[off].toInt() and 0xFF)).toShort()
                sum += sample.toInt()
            }
            out[i] = (sum.toFloat() / channels) / 32768f
        }
        return out
    }

    override fun close() = s.close()
}

/** Потоковое декодирование MP3/M4A/… через MediaCodec, накопление в ~30-сек буфер. */
class MediaCodecChunkStream(context: Context, uri: Uri) : ChunkStream {
    private val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Не удалось открыть файл")
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    override val sampleRate: Int
    private val channels: Int
    private val info = MediaCodec.BufferInfo()
    private var eos = false

    // Аккумулятор для склейки мелких выходов кодекa в ~30-сек куски
    private var pending = FloatArray(0)
    private val targetFrames: Int

    init {
        extractor.setDataSource(pfd.fileDescriptor)
        var trackIdx = -1
        var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIdx = i; fmt = f; break
            }
        }
        require(trackIdx >= 0 && fmt != null) { "Аудиодорожка не найдена" }
        extractor.selectTrack(trackIdx)
        sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        targetFrames = (30.0 * sampleRate).toInt()
        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
    }

    override fun readNext(): FloatArray {
        while (pending.size < targetFrames && !eos) {
            feedInput()
            val decoded = drainOne() ?: continue
            if (decoded.isNotEmpty()) appendPending(decoded)
        }
        if (pending.isEmpty()) return FloatArray(0)
        val out = pending
        pending = FloatArray(0)
        return out
    }

    private fun feedInput() {
        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx < 0) return
        val buf = codec.getInputBuffer(inIdx)!!
        val sz = extractor.readSampleData(buf, 0)
        if (sz < 0) {
            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } else {
            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun drainOne(): FloatArray? {
        val outIdx = codec.dequeueOutputBuffer(info, 10_000)
        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) return null
        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return null
        if (outIdx < 0) return null
        if (info.size <= 0) {
            codec.releaseOutputBuffer(outIdx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
            return null
        }
        val outBuf = codec.getOutputBuffer(outIdx)!!
        outBuf.order(ByteOrder.LITTLE_ENDIAN)
        val shorts = ShortArray(info.size / 2)
        outBuf.asShortBuffer().get(shorts)
        codec.releaseOutputBuffer(outIdx, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true

        val frames = shorts.size / channels
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += shorts[i * channels + c]
            mono[i] = (sum.toFloat() / channels) / 32768f
        }
        return mono
    }

    private fun appendPending(add: FloatArray) {
        val combined = FloatArray(pending.size + add.size)
        System.arraycopy(pending, 0, combined, 0, pending.size)
        System.arraycopy(add, 0, combined, pending.size, add.size)
        pending = combined
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        extractor.release()
        pfd.close()
    }
}

// --- helpers ---
private fun readFully(s: InputStream, buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = s.read(buf, off, buf.size - off)
        if (n < 0) throw EOFException()
        off += n
    }
}
private fun skipFully(s: InputStream, n: Long) {
    var left = n
    while (left > 0) {
        val k = s.skip(left)
        if (k <= 0) break
        left -= k
    }
}
private fun readIntLE(s: InputStream): Int {
    val b = ByteArray(4); readFully(s, b)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
           ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
}
private fun readIntLE(a: ByteArray, off: Int): Int =
    (a[off].toInt() and 0xFF) or ((a[off + 1].toInt() and 0xFF) shl 8) or
    ((a[off + 2].toInt() and 0xFF) shl 16) or ((a[off + 3].toInt() and 0xFF) shl 24)
private fun readShortLE(a: ByteArray, off: Int): Short =
    ((a[off].toInt() and 0xFF) or ((a[off + 1].toInt() and 0xFF) shl 8)).toShort()
