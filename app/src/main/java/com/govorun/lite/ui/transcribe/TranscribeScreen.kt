package com.govorun.lite.ui.transcribe

import android.app.Activity
import android.content.Intent
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
import java.io.InputStream
import com.k2fsa.sherpa.onnx.*
import com.govorun.lite.transcriber.OfflineTranscriber

class TranscribeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TranscribeScreen()
            }
        }
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

    // Лаунчер для выбора файла
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
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Транскрибация аудиофайла",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Кнопка выбора файла
        Button(onClick = { filePickerLauncher.launch("audio/*") }) {
            Text("Выбрать аудиофайл")
        }

        if (fileName.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Выбран: $fileName", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Кнопка запуска транскрибации
        Button(
            onClick = {
                selectedFileUri?.let { uri ->
                    isProcessing = true
                    transcribedText = ""
                    progressText = "Подготовка..."
                    scope.launch {
                        try {
                            val text = transcribeAudioFile(context, uri) { progress ->
                                progressText = progress
                            }
                            transcribedText = text
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
            Text(if (isProcessing) "Обработка..." else "Транскрибировать")
        }

        if (progressText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = progressText, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Поле с результатом
        if (transcribedText.isNotEmpty()) {
            Text(text = "Результат:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = transcribedText,
                onValueChange = { transcribedText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
                readOnly = false,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Transcribed Text", transcribedText))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Копировать текст")
            }
        }
    }
}

/**
 * Основная функция транскрибации.
 * 1. Копирует выбранный файл во временную директорию.
 * 2. Конвертирует аудио в WAV (16kHz, mono, PCM 16-bit) с помощью FFmpeg.
 * 3. Инициализирует OfflineRecognizer и распознаёт текст.
 */
suspend fun transcribeAudioFile(
    context: android.content.Context,
    uri: Uri,
    onProgress: (String) -> Unit
): String = withContext(Dispatchers.IO) {
    onProgress("Копирование файла...")
    val tempFile = File(context.cacheDir, "temp_input_audio")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
        }
    } ?: throw Exception("Не удалось открыть файл")

    onProgress("Конвертация в WAV (16kHz, mono)...")
    val wavFile = File(context.cacheDir, "temp_audio.wav")
    val ffmpegCmd = arrayOf(
        "ffmpeg", "-y", "-i", tempFile.absolutePath,
        "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
        wavFile.absolutePath
    )
    val ffmpegProcess = ProcessBuilder(*ffmpegCmd).redirectErrorStream(true).start()
    ffmpegProcess.waitFor()
    if (ffmpegProcess.exitValue() != 0) {
        throw Exception("FFmpeg failed with exit code ${ffmpegProcess.exitValue()}")
    }

    onProgress("Загрузка модели и распознавание...")
    val recognizer = createRecognizer(context)
    try {
        val waveData = WaveReader.readWaveFromFile(wavFile.absolutePath)
        val stream = recognizer.createStream()
        stream.acceptWaveform(waveData.samples, waveData.sampleRate)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream).text
        stream.release()

        tempFile.delete()
        wavFile.delete()
        return@withContext result
    } finally {
        recognizer.release()
    }
}

fun createRecognizer(context: android.content.Context): OfflineRecognizer {
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
    return OfflineRecognizer(config)
}

fun getFileNameFromUri(context: android.content.Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex != -1) {
                    result = cursor.getString(columnIndex)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path?.substringAfterLast('/')
    }
    return result
}
