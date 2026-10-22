package com.govorun.lite.ui.transcribe

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
import com.govorun.lite.AudioDecoder
import com.govorun.lite.SpeakerSegment
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TranscribeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var resultText by remember { mutableStateOf("Здесь появится текст.\n\nШаг 1: Выберите файл (m4a, wav, mp3).") }
    var isProcessing by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            resultText = "✅ Файл выбран!\n\nШаг 2: Нажмите «Распознать»."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Олин голос - Транскрибация", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { filePicker.launch("audio/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isProcessing
        ) {
            Text("📂 Выбрать аудиофайл (M4A, WAV)")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp)
        ) {
            ScrollableText(text = resultText)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onBack, enabled = !isProcessing) {
                Text("Назад")
            }
            
            Button(
                onClick = {
                    val uri = selectedUri ?: return@Button
                    isProcessing = true
                    resultText = "⏳ Шаг 1 из 3: Чтение и декодирование файла..."
                    
                    scope.launch {
                        try {
                            val text = processAudioWithDiarization(context, uri) { progress ->
                                resultText = progress
                            }
                            resultText = text
                        } catch (e: Exception) {
                            resultText = "❌ Ошибка: ${e.message}"
                        } finally {
                            isProcessing = false
                        }
                    }
                },
                enabled = selectedUri != null && !isProcessing
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("▶ Распознать")
                }
            }
        }
    }
}

@Composable
fun ScrollableText(text: String) {
    val scrollState = rememberScrollState()
    Text(
        text = text,
        modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(scrollState)
    )
}

suspend fun processAudioWithDiarization(
    context: android.content.Context,
    uri: Uri,
    onProgress: (String) -> Unit
): String = withContext(Dispatchers.IO) {
    // 1. Декодируем файл любого формата в массив чисел
    val samples = AudioDecoder.decodeToFloatArray(context, uri)
    if (samples.isEmpty()) return@withContext "Файл пустой или не удалось декодировать."
    
    withContext(Dispatchers.Main) { onProgress("⏳ Шаг 2 из 3: Определение говорящих (диаризация)...") }

    // 2. Настраиваем диаризацию
    val diarConfig = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = "diarization/segmentation.onnx")
        ),
        embedding = SpeakerEmbeddingExtractorConfig(model = "diarization/embedding.onnx"),
        clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f)
    )
    val diar = OfflineSpeakerDiarization(assetManager = context.assets, config = diarConfig)
    val segments = diar.process(samples)
    diar.release()

    if (segments.isEmpty()) {
        withContext(Dispatchers.Main) { onProgress("Не найдено сегментов. Пробую распознать весь файл...") }
        return@withContext recognizeChunk(context, samples)
    }

    // 3. Настраиваем распознавание (ASR)
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
    
    val fullText = StringBuilder()
    
    // 4. Нарезаем и распознаем каждый сегмент
    for ((index, seg) in segments.withIndex()) {
        withContext(Dispatchers.Main) { 
            onProgress("⏳ Шаг 3 из 3: Распознавание реплики ${index + 1} из ${segments.size}...") 
        }
        
        val startSample = (seg.start * 16000).toInt()
        val endSample = minOf((seg.end * 16000).toInt(), samples.size)
        val chunk = samples.sliceArray(startSample until endSample)
        
        val stream = recognizer.createStream()
        stream.acceptWaveform(chunk, 16000)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text
        stream.release()

        if (text.isNotEmpty()) {
            fullText.append("[Говорящий ${seg.speaker + 1}]: $text\n\n")
        }
    }
    
    recognizer.release()
    return@withContext fullText.toString().trim()
}

// Вспомогательная функция для распознавания целого файла (если диаризация не сработала)
suspend fun recognizeChunk(context: android.content.Context, samples: FloatArray): String {
    val config = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "gigaam-v3/encoder.int8.onnx",
                decoder = "gigaam-v3/decoder.onnx",
                joiner = "gigaam-v3/joiner.onnx"
            ),
            tokens = "gigaam-v3/tokens.txt",
            numThreads = 4,
            debug = false
        )
    )
    val recognizer = OfflineRecognizer(assetManager = context.assets, config = config)
    val stream = recognizer.createStream()
    stream.acceptWaveform(samples, 16000)
    recognizer.decode(stream)
    val result = recognizer.getResult(stream).text
    stream.release()
    recognizer.release()
    return result
}
