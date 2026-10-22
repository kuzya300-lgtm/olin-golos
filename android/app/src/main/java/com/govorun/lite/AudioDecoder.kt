package com.govorun.lite

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object AudioDecoder {
    // Декодирует любой аудиофайл (m4a, mp3) в FloatArray (16kHz, Mono)
    fun decodeToFloatArray(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
        }

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                audioTrackIndex = i
                format = trackFormat
                break
            }
        }

        if (audioTrackIndex == -1 || format == null) throw Exception("Аудиодорожка не найдена")
        extractor.selectTrack(audioTrackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var isEOS = false
        val allSamples = ArrayList<Short>()
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        while (!isEOS) {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)!!
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isEOS = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            var outIndex = codec.dequeueOutputBuffer(info, 10000)
            while (outIndex >= 0) {
                val outBuffer = codec.getOutputBuffer(outIndex)!!
                outBuffer.order(ByteOrder.LITTLE_ENDIAN)
                val shortBuffer = outBuffer.asShortBuffer()
                val chunk = ShortArray(shortBuffer.remaining())
                shortBuffer.get(chunk)
                
                // Микшируем в моно, если стерео
                if (channelCount == 2) {
                    for (i in chunk.indices step 2) {
                        if (i + 1 < chunk.size) allSamples.add(((chunk[i] + chunk[i + 1]) / 2).toShort())
                    }
                } else {
                    for (s in chunk) allSamples.add(s)
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        }
        
        codec.stop()
        codec.release()
        extractor.release()

        // Конвертируем ShortArray в FloatArray и ресемплируем до 16000 Гц
        val targetRate = 16000
        val floatSamples = FloatArray((allSamples.size * (targetRate.toFloat() / sampleRate)).roundToInt())
        for (i in floatSamples.indices) {
            val srcIndex = (i * (sampleRate.toFloat() / targetRate)).toInt()
            if (srcIndex < allSamples.size) {
                floatSamples[i] = allSamples[srcIndex] / 32768.0f
            }
        }
        return floatSamples
    }
}
