package com.govorun.lite.ui.transcribe

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.k2fsa.sherpa.onnx.*
import com.govorun.lite.runSpeakerDiarization
import com.govorun.lite.SpeakerSegment

class TranscribeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TranscribeScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var transcribedText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            fileName = getFileNameFromUri(context, it) ?: "audio_file"
            transcribedText = ""
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Олин голос: Транскрибация", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { filePickerLauncher.launch("audio/*") }) { Text("Выбрать аудиофайл") }
        if (fileName.isNotEmpty()) { Text("Выбран: $fileName") }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                selectedFileUri?.let { uri ->
                    isProcessing = true
                    transcribedText = ""
                    progressText = "Подготовка..."
                    scope.launch {
                        try {
                            val wavFile = prepareAudioFile(context, uri) { progressText = it }
                            progressText = "Определение спикеров..."
                            val segs = runDiarization(context, wavFile.absolutePath)
                            progressText = "Распознавание речи..."
                            val text = runASR(context, wavFile.absolutePath)
                            transcribedText = formatResult(text, segs)
                            progressText = "Готово!"
                        } catch (e: Exception) {
                            transcribedText = "Ошибка: ${e.message}"
                            progressText = "Ошибка"
                        } finally {
                            isProcessing = false
                        }
                    }
                }
            },
            enabled = selectedFileUri != null && !isProcessing
        ) {
            Text(if (isProcessing) "Обработка..." else "Распознать с разделением по спикерам")
        }

        if (progressText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(progressText, style = MaterialTheme.typography.bodySmall)
        }

        if (transcribedText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Результат:", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = transcribedText,
                onValueChange = { transcribedText = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                readOnly = false,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
            )
            Button(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Text", transcribedText))
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Копировать текст") }
        }
    }
}

suspend fun prepareAudioFile(context: android.content.Context, uri: Uri, onProgress: (String) -> Unit): File = withContext(Dispatchers.IO) {
    onProgress("Копирование и конвертация...")
    val tempFile = File(context.cacheDir, "temp_in_audio")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
    }
    val wavFile = File(context.cacheDir, "temp_audio.wav")
    // Временное решение: копируем файл как есть (ожидаем, что на входе уже WAV)
    tempFile.copyTo(wavFile, overwrite = true)
    tempFile.delete()
    return@withContext wavFile
}

suspend fun runDiarization(context: android.content.Context, wavPath: String): List<SpeakerSegment> = withContext(Dispatchers.IO) {
    val segModel = File(context.filesDir, "models/diarization/segmentation.onnx").absolutePath
    val embModel = File(context.filesDir, "models/diarization/embedding.onnx").absolutePath
    return@withContext runSpeakerDiarization(wavPath, segModel, embModel)
}

suspend fun runASR(context: android.content.Context, wavPath: String): String = withContext(Dispatchers.IO) {
    val modelDir = File(context.filesDir, "models/gigaam-v3")
    val config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                decoder = File(modelDir, "decoder.onnx").absolutePath,
                joiner = File(modelDir, "joiner.onnx").absolutePath
            ),
            tokens = File(modelDir, "tokens.txt").absolutePath,
            modelType = "nemo_transducer",
            numThreads = 4
        ),
        decodingMethod = "greedy_search"
    )
    val recognizer = OfflineRecognizer(config)
    val waveData = WaveReader.readWaveFromFile(wavPath)
    val stream = recognizer.createStream()
    stream.acceptWaveform(waveData, 16000)
    recognizer.decode(stream)
    val text = recognizer.getResult(stream).text
    stream.release()
    recognizer.release()
    return@withContext text
}

fun formatResult(text: String, segments: List<SpeakerSegment>): String {
    if (segments.isEmpty()) return text
    val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
    if (sentences.isEmpty()) return text
    val sb = StringBuilder()
    var currentSpeaker = segments.first().speaker
    var segIdx = 0
    sentences.forEach { sentence ->
        sb.append("Спикер ${currentSpeaker + 1}: $sentence\n\n")
        if (segIdx < segments.size - 1 && sentence.length > 20) {
            segIdx++
            currentSpeaker = segments[segIdx].speaker
        }
    }
    return sb.toString()
}

fun getFileNameFromUri(context: android.content.Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) result = cursor.getString(idx)
            }
        }
    }
    return result ?: uri.path?.substringAfterLast('/')
}
