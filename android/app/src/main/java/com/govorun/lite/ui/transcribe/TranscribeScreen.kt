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
            Text(resultText, modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(scroll))
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
                            resultText = processWithVadAndDiarization(context, uri) { p -> resultText = p }
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

private data class SpeakerSegment(
    val startSec: Float,
    val endSec: Float,
    val samples16k: FloatArray,
    val speakerId: Int
)

private suspend fun processWithVadAndDiarization(
    context: Context,
    uri: Uri,
    onProgress: (String) -> Unit
): String = withContext(Dispatchers.IO) {

    // ─── 1. ASR (GigaAM v3) — параметры featureDim вынесены в FeatureConfig ───
    val asrConfig = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "gigaam-v3/encoder.int8.onnx",
                decoder = "gigaam-v3/decoder.onnx",
                joiner = "gigaam-v3/joiner.onnx"
            ),
            tokens = "gigaam-v3/tokens.txt",
            numThreads = 4,
            debug = false,
            provider = "cpu",
            modelType = "nemo_transducer" // обязательно для GigaAM
        )
    )
    val recognizer = OfflineRecognizer(assetManager = context.assets, config = asrConfig)

    // ─── 2. VAD (Silero) ───
    val sileroConfig = SileroVadModelConfig(
        model = "silero_vad.onnx",
        threshold = 0.5f,
        minSilenceDuration = 0.3f,
        minSpeechDuration = 0.2f,
        windowSize = 512
    )
    val vadConfig = VadModelConfig(
        sileroVadModelConfig = sileroConfig,
        sampleRate = 16000,
        numThreads = 1,
        debug = false,
        provider = "cpu"
    )
    val vad = Vad(config = vadConfig)

    // ─── 3. Извлечение эмбеддингов ───
    val embConfig = SpeakerEmbeddingExtractorConfig(
        model = "diarization/embedding.onnx",
        numThreads = 1
    )
    val extractor = SpeakerEmbeddingExtractor(assetManager = context.assets, config = embConfig)

    val numSpeakersKnown = 2 // 2 участника диалога
    val clusteringThreshold = 1.0f

    val allSegments = mutableListOf<SpeakerSegment>()
    val globalSpeakers = mutableListOf<FloatArray>()

    try {
        openChunkStream(context, uri).use { stream ->
            while (true) {
                val chunk = stream.readNext()
                if (chunk.isEmpty()) break

                val chunk16k = if (stream.sampleRate != 16000) {
                    resample(chunk, stream.sampleRate, 16000)
                } else chunk

                var offset = 0
                while (offset < chunk16k.size) {
                    val n = minOf(512, chunk16k.size - offset)
                    val sub = chunk16k.sliceArray(offset until offset + n)
                    vad.acceptWaveform(sub)
                    offset += n

                    // Забираем готовые фразы (безопасный цикл без isEmpty)
                    while (true) {
                        val seg = vad.front() ?: break
                        val samples = seg.samples
                        val startSec = seg.start / 16000f
                        val endSec = (seg.start + samples.size) / 16000f

                        val embStream = extractor.createStream()
                        embStream.acceptWaveform(samples, 16000)
                        val emb = extractor.compute(embStream)
                        embStream.release()

                        var bestSpeaker = -1
                        var maxSim = -1.0f
                        for (i in globalSpeakers.indices) {
                            val sim = cosineSimilarity(emb, globalSpeakers[i])
                            if (sim > maxSim) { maxSim = sim; bestSpeaker = i }
                        }

                        val speakerId = when {
                            numSpeakersKnown > 0 -> {
                                if (globalSpeakers.size < numSpeakersKnown) {
                                    globalSpeakers.add(emb)
                                    globalSpeakers.size - 1
                                } else {
                                    bestSpeaker
                                }
                            }
                            else -> {
                                if (maxSim >= clusteringThreshold) bestSpeaker
                                else {
                                    globalSpeakers.add(emb)
                                    globalSpeakers.size - 1
                                }
                            }
                        }

                        allSegments.add(SpeakerSegment(startSec, endSec, samples, speakerId))
                        vad.pop()
                    }
                }
            }
        }
    } finally {
        recognizer.release()
        extractor.release()
        vad.release()
    }

    if (allSegments.isEmpty()) return@withContext "Речь не обнаружена."

    // ─── 4. Склейка соседних фраз одного спикера до ~25 сек ───
    val mergedBlocks = mutableListOf<SpeakerSegment>()
    var currentBlock: SpeakerSegment? = null

    for (seg in allSegments) {
        if (currentBlock == null) {
            currentBlock = seg
            continue
        }
        val sameSpeaker = currentBlock.speakerId == seg.speakerId
        val gap = seg.startSec - currentBlock.endSec
        val newDuration = seg.endSec - currentBlock.startSec

        if (sameSpeaker && gap < 1.5f && newDuration <= 25f) {
            val newSamples = FloatArray(currentBlock.samples16k.size + seg.samples16k.size)
            System.arraycopy(currentBlock.samples16k, 0, newSamples, 0, currentBlock.samples16k.size)
            System.arraycopy(seg.samples16k, 0, newSamples, currentBlock.samples16k.size, seg.samples16k.size)
            currentBlock = SpeakerSegment(
                currentBlock.startSec, seg.endSec, newSamples, currentBlock.speakerId
            )
        } else {
            mergedBlocks.add(currentBlock)
            currentBlock = seg
        }
    }
    currentBlock?.let { mergedBlocks.add(it) }

    // ─── 5. Распознавание ───
    val sb = StringBuilder()
    for ((idx, block) in mergedBlocks.withIndex()) {
        withContext(Dispatchers.Main) {
            onProgress(sb.toString().ifEmpty { "⏳ Слушаю и распознаю...\n\n" })
        }

        val s = recognizer.createStream()
        s.acceptWaveform(block.samples16k, 16000)
        recognizer.decode(s)
        val text = recognizer.getResult(s).text.trim()
        s.release()

        if (text.isNotEmpty()) {
            sb.append("[Говорящий ${block.speakerId + 1}]: $text\n\n")
        }
    }

    return@withContext sb.toString().trim().ifEmpty { "Текст не распознан." }
}

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
