package com.govorun.lite.ui.transcribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.govorun.lite.openChunkStream
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
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

    // Состояние эталонов
    var refStatus by remember { mutableStateOf(BooleanArray(4) { false }) }
    var refsExpanded by remember { mutableStateOf(false) }
    var recSlot by remember { mutableStateOf(0) }
    var fileSlot by remember { mutableStateOf(0) }
    
    val prefsRefs = context.getSharedPreferences("voice_refs", Context.MODE_PRIVATE)
    LaunchedEffect(Unit) {
        refStatus = BooleanArray(4) { i -> prefsRefs.getString("ref_${i+1}", null) != null }
        historyList = loadHistory(context)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            resultText = "✅ Файл выбран. Нажмите «Распознать»."
        }
    }

    // Пикер для эталонных файлов
    val refFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && fileSlot > 0) {
            scope.launch {
                resultText = "⏳ Извлекаю эталон из файла..."
                val ok = extractReferenceFromUri(context, uri, fileSlot)
                if (ok) {
                    refStatus = BooleanArray(4) { i -> prefsRefs.getString("ref_${i+1}", null) != null }
                    resultText = "✅ Эталон для Спикера $fileSlot загружен из файла."
                } else {
                    resultText = "❌ Не удалось извлечь эталон. Файл слишком короткий или тихий."
                }
                fileSlot = 0
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Олин голос — Транскрибация", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        // === ЭТАЛОНЫ ГОЛОСОВ ===
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { refsExpanded = !refsExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("🎤 Эталоны: ${refStatus.count { it }}/4", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(if (refsExpanded) "▼" else "▶", fontSize = 12.sp)
                }
                if (refsExpanded) {
                    Spacer(Modifier.height(6.dp))
                    Text("Запишите 12 сек речи или загрузите файл (WAV/MP3).", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (numSpeakers > refStatus.count { it }) {
                        Text("⚠️ Для $numSpeakers чел. желательно $numSpeakers эталона", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                    }
                    for (slot in 1..4) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Спикер $slot: ${if (refStatus[slot - 1]) "✅" else "—"}", fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = { recSlot = slot },
                                    enabled = !isProcessing,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text("🎙", fontSize = 14.sp) }
                                OutlinedButton(
                                    onClick = { fileSlot = slot; refFilePicker.launch("audio/*") },
                                    enabled = !isProcessing,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text("📁", fontSize = 14.sp) }
                                if (refStatus[slot - 1]) {
                                    TextButton(
                                        onClick = {
                                            prefsRefs.edit().remove("ref_$slot").apply()
                                            val s = refStatus.copyOf(); s[slot - 1] = false; refStatus = s
                                        },
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                    ) { Text("×", fontSize = 16.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))

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
                        containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
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
                            val finalResult = processAudio(context, uri, numSpeakers) { p -> resultText = p }
                            resultText = finalResult
                            saveToHistory(context, finalResult)
                            historyList = loadHistory(context)
                            vibrate(context)
                        } catch (e: Throwable) {
                            Log.e("TRANSCRIBE_ERR", "Crash", e)
                            resultText = "❌ Ошибка: ${e.message ?: e.javaClass.simpleName}"
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
            title = { Text("История") },
            text = {
                if (historyList.isEmpty()) Text("Пусто.")
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    historyList.forEach { txt ->
                        Text(
                            txt.take(100) + "...", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", txt))
                                showHistory = false
                            }.padding(vertical = 8.dp)
                        )
                        Divider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Закрыть") } }
        )
    }

    if (recSlot > 0) {
        ReferenceRecorderDialog(
            slot = recSlot,
            onDismiss = { recSlot = 0 },
            onSaved = {
                refStatus = BooleanArray(4) { i -> prefsRefs.getString("ref_${i+1}", null) != null }
                recSlot = 0
            }
        )
    }
}

// =====================================================================
// ЛОГИКА ОБРАБОТКИ
// =====================================================================

private data class RawSeg(val startSec: Float, val endSec: Float, val samples: FloatArray, val emb: FloatArray?)

private suspend fun processAudio(
    context: Context, uri: Uri, numSpeakers: Int, onProgress: (String) -> Unit
): String = withContext(Dispatchers.Default.limitedParallelism(1)) {

    val asrConfig = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "gigaam-v3/encoder.int8.onnx",
                decoder = "gigaam-v3/decoder.onnx",
                joiner = "gigaam-v3/joiner.onnx"
            ),
            tokens = "gigaam-v3/tokens.txt", numThreads = 4, provider = "cpu", modelType = "nemo_transducer"
        )
    )
    val recognizer = OfflineRecognizer(assetManager = context.assets, config = asrConfig)
    var vad: Vad? = null
    var extractor: SpeakerEmbeddingExtractor? = null
    try {
        vad = Vad(assetManager = context.assets, config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx", threshold = 0.15f, minSilenceDuration = 0.35f, minSpeechDuration = 0.15f, windowSize = 512
            ),
            sampleRate = 16000, numThreads = 1, provider = "cpu"
        ))
        extractor = SpeakerEmbeddingExtractor(assetManager = context.assets, config = SpeakerEmbeddingExtractorConfig(
            model = "diarization/embedding_vox.onnx", numThreads = 1
        ))

        // === Загрузка эталонов ===
        val prefsRefs = context.getSharedPreferences("voice_refs", Context.MODE_PRIVATE)
        val refEmbs = mutableListOf<FloatArray>()
        val refNames = mutableListOf<String>()
        for (i in 1..4) {
            val b64 = prefsRefs.getString("ref_$i", null) ?: continue
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val emb = FloatArray(fb.remaining()); fb.get(emb)
                refEmbs.add(emb); refNames.add("Спикер $i")
            } catch (_: Throwable) {}
        }

        val singleSpeakerMode = numSpeakers <= 1
        val threshold = if (refNames.size == 1) 0.45f else 0.55f
        val unknownName = "Спикер " + (refNames.size + 1)

        fun identify(emb: FloatArray): String {
            if (singleSpeakerMode || refNames.isEmpty()) return "Спикер 1"
            var bestName = unknownName
            var maxSim = -1f
            for (i in refEmbs.indices) {
                val sim = cosineSimilarity(emb, refEmbs[i])
                if (sim > maxSim) { maxSim = sim; bestName = refNames[i] }
            }
            return if (maxSim >= threshold) bestName else unknownName
        }
        fun computeEmb(samples: FloatArray): FloatArray? {
            val ex = extractor ?: return null
            if (samples.size < 12800) return null
            val es = ex.createStream()
            es.acceptWaveform(samples, 16000)
            val r = if (ex.isReady(es)) ex.compute(es) else null
            es.release()
            return r
        }

        // === Сбор VAD-сегментов ===
        data class VSeg(val start: Float, val end: Float, val samples: FloatArray)
        val vsegs = mutableListOf<VSeg>()

        vad!!.acceptWaveform(FloatArray(32000))
        openChunkStream(context, uri).use { stream ->
            while (true) {
                val chunk = stream.readNext(); if (chunk.isEmpty()) break
                val c16 = if (stream.sampleRate != 16000) resample(chunk, stream.sampleRate, 16000) else chunk
                var off = 0
                while (off < c16.size) {
                    val n = minOf(512, c16.size - off)
                    vad!!.acceptWaveform(c16.sliceArray(off until off + n)); off += n
                    while (true) {
                        val seg = vad!!.front(); if (seg.samples.isEmpty()) break
                        vad!!.pop()
                        if (seg.samples.size >= 2400)
                            vsegs.add(VSeg(seg.start / 16000f, (seg.start + seg.samples.size) / 16000f, seg.samples))
                    }
                }
            }
            vad!!.acceptWaveform(FloatArray(32000))
            while (true) {
                val seg = vad!!.front(); if (seg.samples.isEmpty()) break
                vad!!.pop()
                if (seg.samples.size >= 2400)
                    vsegs.add(VSeg(seg.start / 16000f, (seg.start + seg.samples.size) / 16000f, seg.samples))
            }
        }

        if (vsegs.isEmpty()) return@withContext "Речь не обнаружена."

        // === Идентификация + SCD ===
        data class NamedBlock(val name: String, val start: Float, val end: Float, val samples: FloatArray)
        val named = mutableListOf<NamedBlock>()
        val winSamples = 24000  // 1.5 сек
        val hopSamples = 8000   // 0.5 сек
        val minForScd = 40000   // 2.5 сек

        for (seg in vsegs) {
            val doScd = !singleSpeakerMode && refNames.isNotEmpty() && seg.samples.size >= minForScd
            if (!doScd) {
                val emb = computeEmb(seg.samples)
                val name = if (emb != null) identify(emb) else "Спикер 1"
                named.add(NamedBlock(name, seg.start, seg.end, seg.samples))
                continue
            }
            // SCD по окнам
            val winEmbs = mutableListOf<FloatArray>()
            val winTimes = mutableListOf<Pair<Float, Float>>()
            var pos = 0
            while (pos + winSamples <= seg.samples.size) {
                val w = seg.samples.copyOfRange(pos, pos + winSamples)
                val e = computeEmb(w)
                if (e != null) {
                    winEmbs.add(e)
                    winTimes.add(Pair(seg.start + pos / 16000f, seg.start + (pos + winSamples) / 16000f))
                }
                pos += hopSamples
            }
            if (winEmbs.isEmpty()) {
                val emb = computeEmb(seg.samples)
                named.add(NamedBlock(if (emb != null) identify(emb) else "Спикер 1", seg.start, seg.end, seg.samples))
                continue
            }
            val labels = winEmbs.map { identify(it) }
            val smooth = labels.toMutableList()
            if (smooth.size >= 3) {
                for (i in 1 until smooth.size - 1) {
                    if (smooth[i] != smooth[i - 1] && smooth[i] == smooth[i + 1]) smooth[i - 1] = smooth[i]
                }
                for (i in smooth.size - 2 downTo 1) {
                    if (smooth[i] != smooth[i + 1] && smooth[i] == smooth[i - 1]) smooth[i + 1] = smooth[i]
                }
            }
            data class Group(val name: String, var start: Float, var end: Float)
            val groups = mutableListOf<Group>()
            for (i in smooth.indices) {
                val g = Group(smooth[i], winTimes[i].first, winTimes[i].second)
                if (groups.isNotEmpty() && groups.last().name == g.name) groups.last().end = g.end
                else groups.add(g)
            }
            val merged = mutableListOf<Group>()
            for (g in groups) {
                if (merged.isNotEmpty() && merged.last().name == g.name && g.start - merged.last().end < 0.6f) merged.last().end = g.end
                else merged.add(g)
            }
            for (g in merged) {
                val s0 = ((g.start - seg.start) * 16000).toInt().coerceIn(0, seg.samples.size)
                val s1 = ((g.end - seg.start) * 16000).toInt().coerceIn(s0, seg.samples.size)
                if (s1 - s0 < 2400) continue
                named.add(NamedBlock(g.name, g.start, g.end, seg.samples.copyOfRange(s0, s1)))
            }
        }

        if (named.isEmpty()) return@withContext "Речь не обнаружена."

        // === Склейка блоков одного спикера ===
        val blocks = mutableListOf<NamedBlock>()
        for (b in named) {
            val last = blocks.lastOrNull()
            if (last != null && last.name == b.name && b.start - last.end < 1.2f && (last.end - last.start) <= 25f) {
                val m = FloatArray(last.samples.size + b.samples.size)
                System.arraycopy(last.samples, 0, m, 0, last.samples.size)
                System.arraycopy(b.samples, 0, m, last.samples.size, b.samples.size)
                blocks[blocks.size - 1] = NamedBlock(last.name, last.start, b.end, m)
            } else blocks.add(b)
        }

        // === ASR ===
        val sb = StringBuilder()
        for ((idx, b) in blocks.withIndex()) {
            withContext(Dispatchers.Main) { onProgress(sb.toString().ifEmpty { "Распознаю ${idx + 1} из ${blocks.size}..." }) }
            val pad = FloatArray(1600)
            val padded = FloatArray(pad.size + b.samples.size + pad.size)
            System.arraycopy(pad, 0, padded, 0, pad.size)
            System.arraycopy(b.samples, 0, padded, pad.size, b.samples.size)
            System.arraycopy(pad, 0, padded, pad.size + b.samples.size, pad.size)
            val norm = normalizeAmplitude(padded)
            val st = recognizer.createStream()
            st.acceptWaveform(norm, 16000)
            recognizer.decode(st)
            val text = recognizer.getResult(st).text.trim()
            st.release()
            if (text.isNotEmpty()) {
                if (singleSpeakerMode || refNames.isEmpty()) sb.append(text).append("\n\n")
                else sb.append("[").append(b.name).append("]: ").append(text).append("\n\n")
            }
        }
        return@withContext sb.toString().trim().ifEmpty { "Текст не распознан." }
    } catch (e: Throwable) {
        Log.e("TRANSCRIBE", "processAudio", e)
        return@withContext "❌ Ошибка: " + (e.message ?: e.javaClass.simpleName)
    } finally {
        try { extractor?.release() } catch (_: Throwable) {}
        try { vad?.release() } catch (_: Throwable) {}
        try { recognizer.release() } catch (_: Throwable) {}
    }
}

@Composable
fun ReferenceRecorderDialog(slot: Int, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var pendingStart by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingStart = true else status = "❌ Нет доступа к микрофону"
    }

    val startRecording: () -> Unit = {
        isRecording = true; progress = 0f; status = "🎙 Говорите..."
        scope.launch {
            val ok = recordReference(context, slot) { p -> progress = p; status = "Запись: ${(p * 100).toInt()}%" }
            isRecording = false
            if (ok) { status = "✅ Сохранено"; kotlinx.coroutines.delay(600); onSaved() }
            else status = "❌ Ошибка записи"
        }
    }

    LaunchedEffect(pendingStart) { if (pendingStart) { pendingStart = false; startRecording() } }

    AlertDialog(
        onDismissRequest = { if (!isRecording) onDismiss() },
        title = { Text("Эталон Спикера $slot") },
        text = {
            Column {
                Text("12 сек чистой речи. Не шёпотом.", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (isRecording) { LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(4.dp)) }
                if (status.isNotEmpty()) Text(status, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                enabled = !isRecording,
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else pendingStart = true
                }
            ) { Text("▶ Записать 12 сек") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isRecording) { Text("Закрыть") } }
    )
}

private suspend fun recordReference(context: Context, slot: Int, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
    try {
        val sampleRate = 16000; val totalSamples = sampleRate * 12
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sampleRate * 2))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext false
        recorder.startRecording()
        val samples = FloatArray(totalSamples)
        val buffer = ShortArray(2048); var written = 0; var lastP = 0f
        while (written < totalSamples) {
            val n = recorder.read(buffer, 0, minOf(buffer.size, totalSamples - written))
            if (n <= 0) break
            for (i in 0 until n) samples[written + i] = buffer[i] / 32768f
            written += n
            val p = written.toFloat() / totalSamples
            if (p - lastP > 0.05f) { lastP = p; onProgress(p) }
        }
        recorder.stop(); recorder.release()
        if (written < sampleRate * 5) return@withContext false

        val winSize = sampleRate * 8
        val trimmed = if (written <= winSize) samples.copyOf(written) else {
            var bestStart = 0; var bestE = 0f; var s = 0
            while (s + winSize <= written) {
                var e = 0f; var i = s
                while (i < s + winSize) { e += samples[i] * samples[i]; i += 64 }
                if (e > bestE) { bestE = e; bestStart = s }
                s += sampleRate / 4
            }
            samples.copyOfRange(bestStart, bestStart + winSize)
        }
        saveEmbeddingToPrefs(context, trimmed, slot)
        true
    } catch (e: Exception) { Log.e("REF_REC", "err", e); false }
}

private suspend fun extractReferenceFromUri(context: Context, uri: Uri, slot: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        openChunkStream(context, uri).use { stream ->
            val targetSize = 16000 * 10 // 10 сек
            val samples = mutableListOf<Float>()
            while (samples.size < targetSize) {
                val chunk = stream.readNext()
                if (chunk.isEmpty()) break
                val c16 = if (stream.sampleRate != 16000) resample(chunk, stream.sampleRate, 16000) else chunk
                for (s in c16) { samples.add(s); if (samples.size >= targetSize) break }
            }
            if (samples.size < 16000 * 2) return@withContext false // меньше 2 сек — не годится
            saveEmbeddingToPrefs(context, samples.toFloatArray(), slot)
            true
        }
    } catch (e: Exception) { Log.e("REF_FILE", "err", e); false }
}

private fun saveEmbeddingToPrefs(context: Context, samples: FloatArray, slot: Int) {
    val ext = SpeakerEmbeddingExtractor(context.assets, SpeakerEmbeddingExtractorConfig("diarization/embedding_vox.onnx", 1))
    val st = ext.createStream(); st.acceptWaveform(samples, 16000)
    val emb = ext.compute(st)
    st.release(); ext.release()
    val bb = java.nio.ByteBuffer.allocate(emb.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    bb.asFloatBuffer().put(emb)
    val b64 = android.util.Base64.encodeToString(bb.array(), android.util.Base64.DEFAULT)
    context.getSharedPreferences("voice_refs", Context.MODE_PRIVATE).edit().putString("ref_$slot", b64).apply()
}

// =====================================================================
// УТИЛИТЫ
// =====================================================================

private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
    if (fromRate == toRate) return input
    val ratio = fromRate.toDouble() / toRate.toDouble()
    val outSize = (input.size / ratio).toInt()
    val output = FloatArray(outSize)
    for (i in 0 until outSize) {
        val srcPos = i * ratio; val left = srcPos.toInt()
        val right = (left + 1).coerceAtMost(input.size - 1)
        val frac = srcPos - left
        output[i] = (input[left] * (1.0 - frac) + input[right] * frac).toFloat()
    }
    return output
}

private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0.0f; var na = 0.0f; var nb = 0.0f
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    if (na == 0f || nb == 0f) return 0f
    return dot / (sqrt(na) * sqrt(nb))
}

private fun saveToHistory(context: Context, text: String) {
    if (text.startsWith("❌") || text.startsWith("Текст не распознан")) return
    val prefs = context.getSharedPreferences("transcriptions", Context.MODE_PRIVATE)
    val existing = prefs.getString("history", "[]") ?: "[]"
    try {
        val arr = JSONArray(existing); arr.put(text)
        val newArr = JSONArray(); val start = maxOf(0, arr.length() - 5)
        for (i in start until arr.length()) newArr.put(arr.getString(i))
        prefs.edit().putString("history", newArr.toString()).apply()
    } catch (_: Exception) {}
}

private fun loadHistory(context: Context): List<String> {
    val prefs = context.getSharedPreferences("transcriptions", Context.MODE_PRIVATE)
    val existing = prefs.getString("history", "[]") ?: "[]"
    val list = mutableListOf<String>()
    try { val arr = JSONArray(existing); for (i in arr.length() - 1 downTo 0) list.add(arr.getString(i)) } catch (_: Exception) {}
    return list
}

private fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else { @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    else { @Suppress("DEPRECATION") vibrator.vibrate(300) }
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
