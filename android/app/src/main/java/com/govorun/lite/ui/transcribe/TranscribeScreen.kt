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

@Composable
fun TranscribeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var resultText by remember { mutableStateOf("Шаг 1: выберите WAV / MP3 / M4A.") }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onBack, enabled = !isProcessing) { Text("Назад") }
            Button(
                enabled = selectedUri != null && !isProcessing,
                onClick = {
                    val uri = selectedUri ?: return@Button
                    isProcessing = true
                    resultText = "⏳ Запуск…"
                    scope.launch {
                        try {
                            resultText = processAudioStreaming(context, uri) { p -> resultText = p }
                        } catch (e: Exception) {
                            resultText = "❌ ${e.message}"
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

private suspend fun processAudioStreaming(
    context: Context,
    uri: Uri,
    onProgress: (String) -> Unit
): String = withContext(Dispatchers.IO) {
    val config = OfflineRecognizerConfig(
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
    val recognizer = OfflineRecognizer(assetManager = context.assets, config = config)
    val sb = StringBuilder()
    var chunkIdx = 0
    try {
        openChunkStream(context, uri).use { stream ->
            onProgress("⏳ Частота: ${stream.sampleRate} Гц. Поехали…")
            while (true) {
                val chunk = stream.readNext()
                if (chunk.isEmpty()) break
                chunkIdx++
                onProgress("⏳ Чанк $chunkIdx — ${chunk.size} сэмплов…")
                val s = recognizer.createStream()
                s.acceptWaveform(chunk, stream.sampleRate)   // sherpa сам ресемплит
                recognizer.decode(s)
                val text = recognizer.getResult(s).text.trim()
                s.release()
                if (text.isNotEmpty()) sb.append(text).append(' ')
            }
        }
    } finally {
        recognizer.release()
    }
    sb.toString().trim().ifEmpty { "Текст не распознан (тишина или слишком короткий файл)." }
}
