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
import android.util.Log
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
    var numSpeakers by remember { mutableStateOf(1) }
    var showHistory by remember { mutableStateOf(false) }
    var historyList by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) { historyList = loadHistory(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            resultText = "✅ Файл выбран. Нажмите «Распознать»."
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Олин голос — Транскрибация", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        
        Button(onClick = { picker.launch("audio/*") }, modifier = Modifier.fillMaxWidth(), enabled = !isProcessing) { 
            Text("📂 Выбрать аудиофайл") 
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1, 2, 3, 4).forEach { n ->
                val selected = numSpeakers == n
                Button(
                    onClick = { numSpeakers = n },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) { Text("$n чел.") }
            }
        }
        Spacer(Modifier.height(8.dp))

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
                        wakeLock.acquire(60 * 60 * 1000L)

                        try {
                            val finalResult = processWithVadAndDiarization(context, uri, numSpeakers) { p -> resultText = p }
                            resultText = finalResult
                            saveToHistory(context, finalResult)
                            historyList = loadHistory(context)
                            vibrate(context)
                        } catch (e: Exception) {
                            Log.e("VAD_DEBUG", "Crash in processing", e)
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
        
        Button(onClick = onBack, enabled = !isProcessing, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Назад") }
    }

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

private fun saveToHistory(context: Context, text: String) {
    if (text.startsWith("❌") || text.startsWith("Текст не распознан")) return
    val prefs = context.getSharedPreferences("transcriptions", Context.MODE_PRIVATE)
    val existing = prefs.getString("history", "[]") ?: "[]"
    try {
        val arr = JSONArray(existing)
        arr.put(text)
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
        for (i in arr.length() - 1 downTo 0) list.add(arr.getString(i))
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

private data class SpeakerSegment(
    val startSec: Float,
    val endSec: Float,
    val samples16k: FloatArray,
    val speakerId: Int
)

private suspend fun processWithVadAndDiarization(
    context: Context,
    uri: Uri,
    numSpeakers: Int,
    onProgress: (String) -> Unit
): String = withContext(Dispatchers.Default.limitedParallelism(1)) {

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
    val recognizer = try {
        OfflineRecognizer(assetManager = context.assets, config = asrConfig)
    } catch (e: Exception) {
        return@withContext "Ошибка ASR: " + e.message
    }

    val sileroConfig = SileroVadModelConfig(
        model = "silero_vad.onnx",
        threshold = 0.15f,
        minSilenceDuration = 0.35f,
        minSpeechDuration = 0.15f,
        windowSize = 512
    )
    val vadConfig = VadModelConfig(
        sileroVadModelConfig = sileroConfig,
        sampleRate = 16000,
        numThreads = 1,
        debug = false,
        provider = "cpu"
    )
    val vad = try { Vad(assetManager = context.assets, config = vadConfig) }
    catch (e: Exception) { recognizer.release(); return@withContext "Ошибка VAD: " + e.message }

    val embConfig = SpeakerEmbeddingExtractorConfig(
        model = "diarization/embedding_vox.onnx",
        numThreads = 1
    )
    val extractor = try { SpeakerEmbeddingExtractor(assetManager = context.assets, config = embConfig) }
    catch (e: Exception) { recognizer.release(); vad.release(); return@withContext "Ошибка диаризации: " + e.message }

    val manager = SpeakerEmbeddingManager(extractor.dim())

    // Загружаем эталоны из SharedPreferences
    val prefs = context.getSharedPreferences("voice_refs", Context.MODE_PRIVATE)
    val refNames = mutableListOf<String>()
    for (i in 1..4) {
        val b64 = prefs.getString("ref_$i", null) ?: continue
        try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val emb = FloatArray(fb.remaining()); fb.get(emb)
            if (manager.add("Спикер $i", emb)) refNames.add("Спикер $i")
        } catch (_: Exception) { }
    }

    data class RawSeg(val startSec: Float, val endSec: Float, val samples: FloatArray, val emb: FloatArray?)
    val rawSegs = mutableListOf<RawSeg>()

    fun collect(samples: FloatArray, startSample: Int) {
        if (samples.size < 2400) return
        var emb: FloatArray? = null
        if (samples.size >= 12800) {
            val es = extractor.createStream()
            es.acceptWaveform(samples, 16000)
            if (extractor.isReady(es)) emb = extractor.compute(es)
            es.release()
        }
        rawSegs.add(RawSeg(startSample / 16000f, (startSample + samples.size) / 16000f, samples, emb))
    }

    // пре-пост паддинг VAD
    vad.acceptWaveform(FloatArray(32000))
    try {
        openChunkStream(context, uri).use { stream ->
            while (true) {
                val chunk = stream.readNext(); if (chunk.isEmpty()) break
                val c16 = if (stream.sampleRate != 16000) resample(chunk, stream.sampleRate, 16000) else chunk
                var off = 0
                while (off < c16.size) {
                    val n = minOf(512, c16.size - off)
                    vad.acceptWaveform(c16.sliceArray(off until off + n)); off += n
                    while (true) {
                        val seg = vad.front(); if (seg.samples.isEmpty()) break
                        vad.pop(); collect(seg.samples, seg.start)
                    }
                }
            }
            vad.acceptWaveform(FloatArray(32000))
            while (true) {
                val seg = vad.front(); if (seg.samples.isEmpty()) break
                vad.pop(); collect(seg.samples, seg.start)
            }
        }
    } finally { extractor.release(); vad.release() }

    if (rawSegs.isEmpty()) { recognizer.release(); return@withContext "Речь не обнаружена." }

    // === ИДЕНТИФИКАЦИЯ СПИКЕРОВ ===
    data class Ident(val name: String, val confidence: Float)
    val ids = mutableListOf<Ident>()
    for (seg in rawSegs) {
        val emb = seg.emb
        if (emb != null && refNames.isNotEmpty()) {
            // search возвращает имя эталона, если совпадение выше порога
            val name = manager.search(emb, 0.55f) // порог из pyannoteAI (50–70)
            if (name.isNotEmpty()) {
                ids.add(Ident(name, 1.0f))
            } else {
                ids.add(Ident("Неизвестный", 0f))
            }
        } else {
            // fallback: k-means не нужен, если эталонов нет — всё "Неизвестный"
            ids.add(Ident(if (refNames.isEmpty()) "Спикер 1" else "Неизвестный", 0f))
        }
    }

    // === СКЛЕЙКА БЛОКОВ ===
    data class Block(val name: String, var start: Float, var end: Float, var samples: FloatArray)
    val blocks = mutableListOf<Block>()
    for (i in rawSegs.indices) {
        val s = rawSegs[i]; val id = ids[i]
        val last = blocks.lastOrNull()
        val gap = if (last != null) s.startSec - last.end else 999f
        val dur = if (last != null) s.endSec - last.start else 0f
        if (last != null && last.name == id.name && gap < 1.2f && dur <= 25f) {
            val m = FloatArray(last.samples.size + s.samples.size)
            System.arraycopy(last.samples, 0, m, 0, last.samples.size)
            System.arraycopy(s.samples, 0, m, last.samples.size, s.samples.size)
            last.samples = m; last.end = s.endSec
        } else blocks.add(Block(id.name, s.startSec, s.endSec, s.samples))
    }

    // === ASR ===
    val sb = StringBuilder()
    for ((idx, b) in blocks.withIndex()) {
        withContext(Dispatchers.Main) { onProgress(sb.toString().ifEmpty { "Распознаю ${idx+1}/${blocks.size}..." }) }
        val pad = FloatArray(1600)
        val p = FloatArray(pad.size + b.samples.size + pad.size)
        System.arraycopy(pad,0,p,0,pad.size)
        System.arraycopy(b.samples,0,p,pad.size,b.samples.size)
        System.arraycopy(pad,0,p,pad.size+b.samples.size,pad.size)
        val norm = normalizeAmplitude(p)
        val st = recognizer.createStream(); st.acceptWaveform(norm,16000)
        recognizer.decode(st); val text = recognizer.getResult(st).text.trim(); st.release()
        if (text.isNotEmpty()) {
            if (refNames.isNotEmpty()) sb.append("[").append(b.name).append("]: ").append(text).append("\n\n")
            else sb.append(text).append("\n\n")
        }
    }
    recognizer.release()
    return@withContext sb.toString().trim()
}

private fun normalizeAmplitude(input: FloatArray): FloatArray {
    var peak = 0f
    for (x in input) { val a = if (x < 0) -x else x; if (a > peak) peak = a }
    if (peak < 0.01f || peak > 0.85f) return input
    val gain = 0.9f / peak
    val out = FloatArray(input.size)
    for (i in input.indices) out[i] = (input[i] * gain).coerceIn(-1f, 1f)
    return out
}

private fun kMeansClustering(points: List<FloatArray>, k: Int): List<FloatArray> {
    val dim = points[0].size
    val centroids = ArrayList<FloatArray>(k)
    for (i in 0 until k) {
        val idx = (i * points.size / k).coerceAtMost(points.size - 1)
        centroids.add(points[idx].copyOf())
    }
    repeat(20) {
        val sums = Array(k) { FloatArray(dim) }
        val counts = IntArray(k)
        for (p in points) {
            val ci = nearestCentroidIndex(p, centroids)
            counts[ci]++
            for (d in 0 until dim) sums[ci][d] += p[d]
        }
        for (ci in 0 until k) {
            if (counts[ci] > 0) {
                for (d in 0 until dim) sums[ci][d] /= counts[ci]
                centroids[ci] = sums[ci]
            }
        }
    }
    return centroids
}

private fun nearestCentroidIndex(p: FloatArray, centroids: List<FloatArray>): Int {
    var best = 0
    var bestSim = -2f
    for (ci in centroids.indices) {
        val sim = cosineSimilarity(p, centroids[ci])
        if (sim > bestSim) { bestSim = sim; best = ci }
    }
    return best
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


private fun runKMeans(rawSegs: List<Any>, speakerIds: IntArray, numSpeakers: Int) {
    // Заглушка — k-means логика осталась inline ниже
}

private fun estimatePitchHz(samples: FloatArray, sampleRate: Int = 16000): Float {
    if (samples.size < 1000) return 0f
    // Удаляем DC и нормализуем
    var mean = 0f
    for (s in samples) mean += s
    mean /= samples.size
    val normalized = FloatArray(samples.size) { samples[it] - mean }

    // Автокорреляция в диапазоне 60-400 Гц
    val minLag = (sampleRate / 400.0).toInt().coerceAtLeast(10)
    val maxLag = (sampleRate / 60.0).toInt().coerceAtMost(samples.size / 2)
    if (maxLag <= minLag) return 0f

    var bestLag = 0
    var bestCorr = 0f
    var prevCorr = 0f
    var declining = false
    for (lag in minLag..maxLag) {
        var corr = 0f
        val n = samples.size - lag
        for (i in 0 until n) {
            corr += normalized[i] * normalized[i + lag]
        }
        corr /= n
        if (corr > bestCorr) {
            bestCorr = corr
            bestLag = lag
        }
    }
    if (bestLag == 0 || bestCorr <= 0.01f) return 0f
    return sampleRate.toFloat() / bestLag
}
