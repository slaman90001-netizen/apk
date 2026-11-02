package com.meuassistente

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
                Surface(Modifier.fillMaxSize()) { Raiz() }
            }
        }
    }
}

@Composable
fun Raiz(vm: ChatViewModel = viewModel()) {
    var telaMemoria by remember { mutableStateOf(false) }
    BackHandler(telaMemoria) { telaMemoria = false }
    if (telaMemoria) {
        TelaMemoria(vm) { telaMemoria = false }
    } else {
        TelaChat(vm) { telaMemoria = true }
    }
}

@Composable
fun TelaChat(vm: ChatViewModel, abrirMemoria: () -> Unit) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Meu Assistente",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = abrirMemoria) { Text("Memória (${vm.fatos.size})") }
            TextButton(onClick = { vm.novaConversa() }, enabled = !vm.ocupado) { Text("Nova") }
        }

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
        } else {
            TextButton(
                onClick = { escolher.launch(arrayOf("*/*")) },
                enabled = !vm.ocupado
            ) { Text("Trocar modelo") }
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
fun TelaMemoria(vm: ChatViewModel, voltar: () -> Unit) {
    var novo by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Memória",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = voltar) { Text("Voltar") }
        }
        Text(
            "O que o assistente sabe sobre você. No chat, você também pode dizer: \"lembre que ...\".",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(vm.fatos) { i, f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(f, Modifier.weight(1f))
                    TextButton(
                        onClick = { vm.removerFato(i) },
                        enabled = !vm.ocupado
                    ) { Text("Apagar") }
                }
                HorizontalDivider()
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = novo,
                onValueChange = { novo = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Novo fato sobre você") }
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (vm.adicionarFato(novo)) novo = "" },
                enabled = !vm.ocupado && novo.isNotBlank()
            ) { Text("Adicionar") }
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
