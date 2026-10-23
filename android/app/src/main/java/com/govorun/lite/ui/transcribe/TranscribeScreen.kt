package com.govorun.lite.ui.transcribe

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.govorun.lite.openChunkStream
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

@Composable
fun TranscribeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var resultText by remember { mutableStateOf("Шаг 1: выберите аудиофайл.") }
    var isProcessing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            resultText = "✅ Файл выбран. Нажмите «Распознать»."
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Олин голос — Транскрибация", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { picker.launch("audio/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isProcessing
        ) { Text("📂 Выбрать аудиофайл") }
        Spacer(Modifier.height(16.dp))
        
        Card(Modifier.fillMaxWidth().weight(1f)) {
            val scroll = rememberScrollState()
            Text(
                text = resultText, 
                modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(scroll)
            )
        }
        
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onBack, enabled = !isProcessing) { Text("Назад") }
            Button(
                enabled = selectedUri != null && !isProcessing,
                onClick = {
                    val uri = selectedUri ?: return@Button
                    isProcessing = true
                    resultText = "⏳ Слушаю и распознаю...\n\n"
                    scope.launch {
                        try {
                            resultText = processAudioSmart(context, uri) { p -> resultText = p }
                        } catch (e: Exception) {
                            resultText = "❌ Ошибка: ${e.message}"
                        } finally {
                            isProcessing = false
                        }
                    }
                }
            ) {
                if (isProcessing) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text("▶ Распознать")
            }
        }
    }
}

private suspend fun processAudioSmart(
    context: Context,
    uri: Uri,
    onProgress: (String) -> Unit
): String = withContext(Dispatchers.IO) {
    // 1. Настраиваем распознавание текста (ASR)
    val asrConfig = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "gigaam-v3/encoder.int8.onnx",
                decoder = "gigaam-v3/decoder.onnx",
                joiner = "gigaam-v3/joiner.onnx"
            ),
            tokens = "gigaam-v3/tokens.txt",
            numThreads = 4,
            debug = false,
            provider = "cpu"
        )
    )
    val recognizer = OfflineRecognizer(assetManager = context.assets, config = asrConfig)

    // 2. Настраиваем Диаризацию (поиск смены голоса)
    val embConfig = SpeakerEmbeddingExtractorConfig(
        model = "diarization/embedding.onnx",
        numThreads = 1
    )
    val extractor = SpeakerEmbeddingExtractor(assetManager = context.assets, config = embConfig)

    val diarConfig = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = "diarization/segmentation.onnx")
        ),
        embedding = embConfig,
        clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f)
    )
    val diar = OfflineSpeakerDiarization(assetManager = context.assets, config = diarConfig)

    val sb = StringBuilder()
    val globalSpeakers = mutableListOf<FloatArray>()

    try {
        openChunkStream(context, uri).use { stream ->
            while (true) {
                val chunk = stream.readNext()
                if (chunk.isEmpty()) break
                
                withContext(Dispatchers.Main) {
                    onProgress(sb.toString().ifEmpty { "⏳ Слушаю и распознаю...\n\n" })
                }

                // Sherpa-ONNX диаризация работает ТОЛЬКО с 16 кГц. 
                // Делаем ресемплинг на лету, если файл 44.1кГц или 48кГц.
                val chunk16k = if (stream.sampleRate != 16000) {
                    resample(chunk, stream.sampleRate, 16000)
                } else chunk

                val segments = diar.process(chunk16k)

                if (segments.isEmpty()) {
                    // Если не нашли пауз/смены голоса — считаем, что это один сплошной кусок
                    processSegment(chunk, stream.sampleRate, chunk16k, recognizer, extractor, globalSpeakers, sb)
                } else {
                    for (seg in segments) {
                        val startSec = seg.start
                        val endSec = seg.end
                        
                        val startSampleOrig = (startSec * stream.sampleRate).toInt().coerceIn(0, chunk.size)
                        val endSampleOrig = (endSec * stream.sampleRate).toInt().coerceIn(startSampleOrig, chunk.size)
                        
                        val startSample16k = (startSec * 16000).toInt().coerceIn(0, chunk16k.size)
                        val endSample16k = (endSec * 16000).toInt().coerceIn(startSample16k, chunk16k.size)

                        if (startSampleOrig >= endSampleOrig) continue

                        val subChunkOrig = chunk.sliceArray(startSampleOrig until endSampleOrig)
                        val subChunk16k = chunk16k.sliceArray(startSample16k until endSample16k)

                        processSegment(subChunkOrig, stream.sampleRate, subChunk16k, recognizer, extractor, globalSpeakers, sb)
                    }
                }
            }
        }
    } finally {
        recognizer.release()
        extractor.release()
        diar.release()
    }

    return@withContext sb.toString().trim().ifEmpty { "Текст не распознан." }
}

private fun processSegment(
    audioOrig: FloatArray, origRate: Int,
    audio16k: FloatArray,
    recognizer: OfflineRecognizer,
    extractor: SpeakerEmbeddingExtractor,
    globalSpeakers: MutableList<FloatArray>,
    sb: StringBuilder
) {
    // А) Распознаем текст
    val s = recognizer.createStream()
    s.acceptWaveform(audioOrig, origRate) // ASR сам ресемплит под модель
    recognizer.decode(s)
    val text = recognizer.getResult(s).text.trim()
    s.release()

    if (text.isNotEmpty()) {
        // Б) Вытаскиваем "отпечаток" голоса
        val embStream = extractor.createStream()
        embStream.acceptWaveform(audio16k, 16000)
        val emb = extractor.compute(embStream)
        embStream.release()

        // В) Сравниваем с глобальным списком говорящих
        var bestSpeaker = -1
        var maxSim = -1.0f
        for (i in globalSpeakers.indices) {
            val sim = cosineSimilarity(emb, globalSpeakers[i])
            if (sim > maxSim) { maxSim = sim; bestSpeaker = i }
        }

        val speakerId = if (maxSim > 0.55f) bestSpeaker + 1 else {
            globalSpeakers.add(emb)
            globalSpeakers.size
        }

        sb.append("[Говорящий $speakerId]: $text\n\n")
    }
}

// Встроенный ресемплер (перевод частоты) без сторонних библиотек
private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
    if (fromRate == toRate) return input
    val ratio = fromRate.toDouble() / toRate.toDouble()
    val outSize = (input.size / ratio).toInt()
    val output = FloatArray(outSize)
    for (i in 0 until outSize) {
        val srcPos = i * ratio
        val left = srcPos.toInt()
        val right = (left + 1).coerceAtMost(input.size - 1)
        val frac = srcPos - left
        output[i] = (input[left] * (1.0 - frac) + input[right] * frac).toFloat()
    }
    return output
}

private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0.0f; var normA = 0.0f; var normB = 0.0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    if (normA == 0f || normB == 0f) return 0f
    return dot / (sqrt(normA) * sqrt(normB))
}
