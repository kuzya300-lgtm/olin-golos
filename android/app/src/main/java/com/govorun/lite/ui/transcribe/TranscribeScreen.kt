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
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun TranscribeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var resultText by remember { mutableStateOf("Здесь появится текст.\n\nШаг 1: Выберите WAV-файл.") }
    var isProcessing by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            resultText = "✅ Файл выбран!\n\nШаг 2: Нажмите «Далее», чтобы распознать."
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
            Text("📂 Выбрать аудиофайл (WAV)")
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
                    resultText = "⏳ Идет распознавание... Пожалуйста, подождите."
                    
                    scope.launch {
                        try {
                            val text = processAudio(context, uri)
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
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(scrollState)
    )
}

fun copyUriToFile(context: Context, uri: Uri): File {
    val tempFile = File(context.cacheDir, "temp_audio.wav")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
        }
    }
    return tempFile
}

suspend fun processAudio(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val audioFile = copyUriToFile(context, uri)
    val waveData = WaveReader.readWave(audioFile.absolutePath)
    
    val config = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "gigaam-v3/encoder.int8.onnx",
                decoder = "gigaam-v3/decoder.onnx",
                joiner = "gigaam-v3/joiner.onnx"
            ),
            tokens = "gigaam-v3/tokens.txt",
            numThreads = 4,
            debug = true,
            provider = "cpu"
        )
    )

    val recognizer = OfflineRecognizer(assetManager = context.assets, config = config)
    val stream = recognizer.createStream()
    stream.acceptWaveform(waveData.samples, waveData.sampleRate)
    recognizer.decode(stream)
    val result = recognizer.getResult(stream)
    
    stream.release()
    recognizer.release()
    audioFile.delete()

    return@withContext result.text.ifEmpty { "Текст не распознан (тишина или слишком короткий файл)." }
}
