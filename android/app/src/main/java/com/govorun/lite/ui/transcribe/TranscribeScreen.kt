package com.govorun.lite.ui.transcribe

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun TranscribeScreen() {
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var resultText by remember { mutableStateOf("Здесь появится текст. (Выберите WAV-файл 16kHz моно)") }
    var isProcessing by remember { mutableStateOf(false) }

    // Лаунчер для выбора файла
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            resultText = "Файл выбран. Готов к распознаванию."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Олин голос - Транскрибация", style = MaterialTheme.typography.headlineSmall)
        
        Spacer(modifier = Modifier.height(24.dp))

        // Кнопка выбора файла
        Button(
            onClick = { filePicker.launch("audio/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📂 Выбрать аудиофайл (WAV)")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Блок вывода текста
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 16.dp)
        ) {
            Text(
                text = resultText,
                modifier = Modifier.padding(16.dp)
            )
        }

        // Кнопка запуска распознавания
        Button(
            onClick = {
                if (selectedUri != null) {
                    isProcessing = true
                    resultText = "Идет распознавание... (пока это заглушка)"
                    // TODO: Здесь будет вызов recognizer.process()
                }
            },
            enabled = selectedUri != null && !isProcessing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isProcessing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Text("▶ Начать распознавание")
            }
        }
    }
}
