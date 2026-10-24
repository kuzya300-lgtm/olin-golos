package com.govorun.lite.ui.transcribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.govorun.lite.openChunkStream
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import org.json.JSONArray

@Composable
fun TranscribeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var resultText by remember { mutableStateOf("Шаг 1: выберите аудиофайл.") }
    var isProcessing by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var historyList by remember { mutableStateOf<List<String>>(emptyList()) }

    // Загружаем историю при старте
    LaunchedEffect(Unit) {
        historyList = loadHistory(context)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            resultText = "✅ Файл выбран. Нажмите «Распознать»."
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Олин голос — Транскрибация", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        
        // Кнопка выбора файла
        Button(
            onClick = { picker.launch("audio/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isProcessing
        ) { Text("📂 Выбрать аудиофайл") }
        
        Spacer(Modifier.height(8.dp))

        // Кнопки управления (Распознать, Копировать, История)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(
                enabled = selectedUri != null && !isProcessing,
                onClick = {
                    val uri = selectedUri ?: return@Button
                    isProcessing = true
                    resultText = "⏳ Слушаю и распознаю...\n\n"
                    scope.launch {
                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OlinGolos::Transcription")
                        wakeLock.acquire(60 * 60 * 1000L) // Максимум на 1 час

                        try {
                            val finalResult = processWithVadAndDiarization(context, uri) { p -> resultText = p }
                            resultText = finalResult
                            saveToHistory(context, finalResult)
                            historyList = loadHistory(context)
                            vibrate(context)
                        } catch (e: Exception) {
                            resultText = "❌ Ошибка: ${e.message}"
                        } finally {
                            wakeLock.release()
                            isProcessing = false
                        }
                    }
                }
            ) {
                if (isProcessing) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text("▶ Распознать")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = resultText.isNotBlank() && !isProcessing && !resultText.startsWith("Шаг") && !resultText.startsWith("⏳"),
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", resultText))
                    }
                ) { Text("Копировать") }

                OutlinedButton(onClick = { showHistory = true }) { Text("История") }
            }
        }

        Spacer(Modifier.height(16.dp))
        
        Card(Modifier.fillMaxWidth().weight(1f)) {
            val scroll = rememberScrollState()
            Text(resultText, modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(scroll))
        }
        
        // Кнопка Назад внизу
        Button(onClick = onBack, enabled = !isProcessing, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Назад") }
    }

    // Диалог истории
    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("История расшифровок") },
            text = {
                if (historyList.isEmpty()) Text("История пуста.")
                else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        historyList.forEach { txt ->
                            Text(
                                txt.take(100) + "...",
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", txt))
                                    showHistory = false
                                }.padding(vertical = 8.dp)
                            )
                            Divider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Закрыть") } }
        )
    }
}

// --- ЛОГИКА СОХРАНЕНИЯ И ВИБРАЦИИ ---

private fun saveToHistory(context: Context, text: String) {
    if (text.startsWith("❌") || text.startsWith("Текст не распознан")) return
    val prefs = context.getSharedPreferences("transcriptions", Context.MODE_PRIVATE)
    val existing = prefs.getString("history", "[]") ?: "[]"
    try {
        val arr = JSONArray(existing)
        arr.put(text)
        // Оставляем только последние 5
        val newArr = JSONArray()
        val start = maxOf(0, arr.length() - 5)
        for (i in start until arr.length()) newArr.put(arr.getString(i))
        prefs.edit().putString("history", newArr.toString()).apply()
    } catch (_: Exception) {}
}

private fun loadHistory(context: Context): List<String> {
    val prefs = context.getSharedPreferences("transcriptions", Context.MODE_PRIVATE)
    val existing = prefs.getString("history", "[]") ?: "[]"
    val list = mutableListOf<String>()
    try {
        val arr = JSONArray(existing)
        for (i in arr.length() - 1 downTo 0) list.add(arr.getString(i)) // новые сверху
    } catch (_: Exception) {}
    return list
}

private fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(300)
    }
}

// --- НЕЙРОСЕТЬ ---

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
            modelType = "nemo_transducer"
        )
    )
    val recognizer = OfflineRecognizer(assetManager = context.assets, config = asrConfig)

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

    val embConfig = SpeakerEmbeddingExtractorConfig(
        model = "diarization/embedding.onnx",
        numThreads = 1
    )
    val extractor = SpeakerEmbeddingExtractor(assetManager = context.assets, config = embConfig)

    val numSpeakersKnown = 2
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

    if (allSegments.isEmpty()) return@withContext "Текст не распознан (возможно, в файле тишина)."

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

    return@withContext sb.toString().trim()
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
