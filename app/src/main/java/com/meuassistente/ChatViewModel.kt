package com.meuassistente

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Msg(val texto: String, val doUsuario: Boolean)

const val PROMPT_SISTEMA =
    "Você é um assistente pessoal. Responda sempre em português do Brasil, de forma curta e clara."

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val arquivoModelo = File(app.filesDir, "modelo.litertlm")
    private var engine: Engine? = null
    private var conversa: Conversation? = null

    val mensagens = mutableStateListOf<Msg>()
    var status by mutableStateOf("")
    var pronto by mutableStateOf(false)
    var ocupado by mutableStateOf(false)
    var temModelo by mutableStateOf(arquivoModelo.exists())

    init {
        if (temModelo) viewModelScope.launch { carregarModelo() }
    }

    fun importarModelo(uri: Uri) {
        viewModelScope.launch {
            ocupado = true
            status = "Copiando o modelo... pode levar alguns minutos."
            try {
                withContext(Dispatchers.IO) {
                    val tmp = File(arquivoModelo.path + ".tmp")
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { entrada ->
                        tmp.outputStream().use { saida -> entrada.copyTo(saida, 1024 * 1024) }
                    }
                    if (arquivoModelo.exists()) arquivoModelo.delete()
                    tmp.renameTo(arquivoModelo)
                }
                temModelo = true
                carregarModelo()
            } catch (e: Exception) {
                status = "Erro ao copiar: ${e.message}"
                ocupado = false
            }
        }
    }

    private suspend fun carregarModelo() {
        ocupado = true
        pronto = false
        status = "Carregando o modelo..."
        try {
            withContext(Dispatchers.IO) {
                conversa?.close()
                engine?.close()
                val config = EngineConfig(
                    modelPath = arquivoModelo.path,
                    backend = Backend.CPU(),
                    cacheDir = getApplication<Application>().cacheDir.path
                )
                val e = Engine(config)
                e.initialize()
                engine = e
                conversa = e.createConversation(
                    ConversationConfig(systemInstruction = Contents.of(PROMPT_SISTEMA))
                )
            }
            pronto = true
            status = ""
        } catch (e: Throwable) {
            status = "Erro ao carregar o modelo: ${e.message}"
        }
        ocupado = false
    }

    fun enviar(texto: String) {
        val conv = conversa ?: return
        if (texto.isBlank() || ocupado) return
        mensagens.add(Msg(texto, true))
        mensagens.add(Msg("", false))
        val idx = mensagens.lastIndex
        ocupado = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    conv.sendMessageAsync(texto).collect { parte ->
                        val atual = mensagens[idx]
                        mensagens[idx] = atual.copy(texto = atual.texto + parte.toString())
                    }
                }
            } catch (e: Exception) {
                val atual = mensagens[idx]
                mensagens[idx] = atual.copy(texto = atual.texto + "\n[erro: ${e.message}]")
            }
            ocupado = false
        }
    }

    override fun onCleared() {
        conversa?.close()
        engine?.close()
    }
}
