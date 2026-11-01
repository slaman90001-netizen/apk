package com.meuassistente

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Chat() }
            }
        }
    }
}

@Composable
fun Chat(vm: ChatViewModel = viewModel()) {
    val escolher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importarModelo(uri) }

    var texto by remember { mutableStateOf("") }
    val lista = rememberLazyListState()

    LaunchedEffect(vm.mensagens.size, vm.mensagens.lastOrNull()?.texto) {
        if (vm.mensagens.isNotEmpty()) lista.scrollToItem(vm.mensagens.lastIndex)
    }

    Column(
        Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(12.dp)
    ) {
        Text("Meu Assistente", style = MaterialTheme.typography.titleLarge)

        if (vm.status.isNotEmpty()) {
            Text(
                vm.status,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        if (!vm.temModelo) {
            Button(
                onClick = { escolher.launch(arrayOf("*/*")) },
                enabled = !vm.ocupado
            ) { Text("Escolher arquivo do modelo (.litertlm)") }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = lista) {
            items(vm.mensagens) { m -> Balao(m) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensagem") },
                enabled = vm.pronto
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.enviar(texto); texto = "" },
                enabled = vm.pronto && !vm.ocupado && texto.isNotBlank()
            ) { Text("Enviar") }
        }
    }
}

@Composable
fun Balao(m: Msg) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (m.doUsuario) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (m.doUsuario) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(if (m.texto.isEmpty()) "..." else m.texto, Modifier.padding(12.dp))
        }
    }
}
