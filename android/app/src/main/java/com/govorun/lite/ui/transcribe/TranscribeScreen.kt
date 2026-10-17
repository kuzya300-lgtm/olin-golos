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
fun TranscribeScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var resultText by remember { mutableStateOf("Здесь появится текст.\n\nШаг 1: Нажмите кнопку ниже и выберите WAV-файл.") }
    var isProcessing by remember { mutableStateOf(false) }

    // Лаунчер для выбора файла
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            resultText = "✅ Файл выбран!\n\nШаг 2: Нажмите «Далее», чтобы запустить распознавание."
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
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📂 Выбрать аудиофайл (WAV)")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp)
        ) {
            Text(text = resultText, modifier = Modifier.padding(16.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onBack) {
                Text("Назад")
            }
            
            Button(
                onClick = {
                    isProcessing = true
                    resultText = "Идет распознавание... (пока это заглушка, движок подключим на следующем шаге)"
                },
                enabled = selectedUri != null && !isProcessing
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text("Далее")
                }
            }
        }
    }
}
