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

    // 2. Настраиваем Сегментацию (поиск пауз и смены голоса)
    val diarConfig = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = "diarization/segmentation.onnx")
        ),
        embedding = SpeakerEmbeddingExtractorConfig(model = "diarization/embedding.onnx"),
        clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f)
    )
    val diar = OfflineSpeakerDiarization(assetManager = context.assets, config = diarConfig)
    val extractor = SpeakerEmbeddingExtractor(assetManager = context.assets, config = diarConfig.embedding)

    val sb = StringBuilder()
    val globalSpeakers = mutableListOf<FloatArray>() // Глобальный список уникальных голосов

    try {
        openChunkStream(context, uri).use { stream ->
            while (true) {
                val chunk = stream.readNext()
                if (chunk.isEmpty()) break
                
                // Обновляем экран - показываем только текст без цифр
                withContext(Dispatchers.Main) {
                    onProgress(sb.toString().ifEmpty { "⏳ Слушаю и распознаю...\n\n" })
                }

                // Ищем сегменты внутри 30-секундного куска (кто когда говорил)
                val segments = diar.process(chunk)
                
                // Если сегментатор не нашел пауз (например, сплошной монолог), обрабатываем весь кусок разом
                val actualSegments = if (segments.isEmpty()) {
                    listOf(SpeakerSegment(0.0f, (chunk.size / stream.sampleRate.toFloat()), 0))
                } else segments

                for (seg in actualSegments) {
                    // Вырезаем конкретную реплику (например, с 5-й по 12-ю секунду)
                    val startSample = (seg.start * stream.sampleRate).toInt().coerceIn(0, chunk.size)
                    val endSample = (seg.end * stream.sampleRate).toInt().coerceIn(startSample, chunk.size)
                    val subChunk = chunk.sliceArray(startSample until endSample)
                    
                    if (subChunk.isEmpty()) continue

                    // А) Распознаем текст этой реплики
                    val s = recognizer.createStream()
                    s.acceptWaveform(subChunk, stream.sampleRate)
                    recognizer.decode(s)
                    val text = recognizer.getResult(s).text.trim()
                    s.release()

                    if (text.isNotEmpty()) {
                        // Б) Вытаскиваем "отпечаток" голоса из этой реплики
                        val embStream = extractor.createStream()
                        embStream.acceptWaveform(subChunk, stream.sampleRate)
                        val currentEmb = extractor.compute(embStream)
                        embStream.release()

                        // В) Сравниваем со всеми, кого уже слышали (глобально)
                        var bestSpeaker = -1
                        var maxSim = -1.0f
                        for (i in globalSpeakers.indices) {
                            val sim = cosineSimilarity(currentEmb, globalSpeakers[i])
                            if (sim > maxSim) { maxSim = sim; bestSpeaker = i }
                        }

                        // Если голос похож (порог 0.55), берем его старый номер. Иначе — новый.
                        val speakerId = if (maxSim > 0.55f) bestSpeaker + 1 else {
                            globalSpeakers.add(currentEmb)
                            globalSpeakers.size
                        }

                        sb.append("[Говорящий $speakerId]: $text\n\n")
                    }
                }
            }
        }
    } finally {
        recognizer.release()
        extractor.release()
        diar.release()
    }

    return@withContext sb.toString().trim().ifEmpty { "Текст не распознан (возможно, в файле тишина)." }
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
