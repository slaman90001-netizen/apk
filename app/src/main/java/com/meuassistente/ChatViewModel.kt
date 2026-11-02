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
    "Você é um assistente pessoal. Responda sempre em português do Brasil. " +
    "Seja claro e amigável, e seja breve, a menos que peçam detalhes."

const val MAX_FATOS = 40

private val PREFIXOS_LEMBRAR = listOf(
    "lembre que ", "lembre-se que ", "lembre-se de que ", "anote que ", "memorize que "
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val memoria = MemoriaStore(app)
    private val arquivoModelo = File(app.filesDir, "modelo.litertlm")
    private var engine: Engine? = null
    private var conversa: Conversation? = null
    @Volatile private var recriar = false

    val mensagens = mutableStateListOf<Msg>()
    val fatos = mutableStateListOf<String>()
    var status by mutableStateOf("")
    var pronto by mutableStateOf(false)
    var ocupado by mutableStateOf(false)
    var temModelo by mutableStateOf(arquivoModelo.exists())

    init {
        fatos.addAll(memoria.carregarFatos())
        mensagens.addAll(memoria.carregarHistorico())
        if (temModelo) viewModelScope.launch { carregarModelo() }
    }

    // ---------- Memória ----------

    fun adicionarFato(texto: String): Boolean {
        val t = texto.trim().take(200)
        if (t.isEmpty() || fatos.size >= MAX_FATOS) return false
        fatos.add(t)
        memoria.salvarFatos(fatos)
        recriar = true
        return true
    }

    fun removerFato(i: Int) {
        if (i in fatos.indices) {
            fatos.removeAt(i)
            memoria.salvarFatos(fatos)
            recriar = true
        }
    }

    fun novaConversa() {
        if (ocupado) return
        mensagens.clear()
        memoria.salvarHistorico(mensagens)
        recriar = true
    }

    private fun promptSistema(): String {
        val sb = StringBuilder(PROMPT_SISTEMA)
        if (fatos.isNotEmpty()) {
            sb.append("\n\nO que você sabe sobre o usuário (use quando for relevante):\n")
            fatos.forEach { sb.append("- ").append(it).append("\n") }
        }
        return sb.toString()
    }

    private fun historicoInicial(): List<Message> {
        val saida = mutableListOf<Message>()
        val recentes = mensagens.takeLast(10)
        var i = 0
        while (i < recentes.size - 1) {
            val a = recentes[i]
            val b = recentes[i + 1]
            if (a.doUsuario && !b.doUsuario && a.texto.isNotBlank() && b.texto.isNotBlank()) {
                saida.add(Message.user(a.texto))
                saida.add(Message.model(b.texto))
                i += 2
            } else {
                i++
            }
        }
        return saida
    }

    private fun recriarConversa() {
        val e = engine ?: return
        conversa?.close()
        conversa = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(promptSistema()),
                initialMessages = historicoInicial()
            )
        )
        recriar = false
    }

    // ---------- Modelo ----------

    fun importarModelo(uri: Uri) {
        viewModelScope.launch {
            ocupado = true
            pronto = false
            status = "Copiando o modelo... pode levar alguns minutos."
            try {
                withContext(Dispatchers.IO) {
                    conversa?.close()
                    engine?.close()
                    conversa = null
                    engine = null
                    val tmp = File(arquivoModelo.path + ".tmp")
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { entrada ->
                        tmp.outputStream().use { saida -> entrada.copyTo(saida, 1024 * 1024) }
                    }
                    if (arquivoModelo.exists()) arquivoModelo.delete()
                    tmp.renameTo(arquivoModelo)
                    getApplication<Application>().cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                }
                temModelo = true
                carregarModelo()
            } catch (e: Exception) {
                status = "Erro ao copiar: ${e.message}. Feche e reabra o app."
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
                recriarConversa()
            }
            pronto = true
            status = ""
        } catch (e: Throwable) {
            status = "Erro ao carregar o modelo: ${e.message}"
        }
        ocupado = false
    }

    // ---------- Chat ----------

    fun enviar(texto: String) {
        val limpo = texto.trim()
        if (limpo.isEmpty() || ocupado || !pronto) return

        // Comando de memória: "lembre que ..."
        val minusculo = limpo.lowercase()
        val prefixo = PREFIXOS_LEMBRAR.firstOrNull { minusculo.startsWith(it) }
        if (prefixo != null) {
            val fato = limpo.drop(prefixo.length).trim()
            if (fato.isNotEmpty()) {
                mensagens.add(Msg(limpo, true))
                if (adicionarFato(fato)) {
                    mensagens.add(Msg("Anotado! Vou lembrar que $fato.", false))
                } else {
                    mensagens.add(
                        Msg("Minha memória está cheia (limite de $MAX_FATOS fatos). Apague algum na tela Memória.", false)
                    )
                }
                memoria.salvarHistorico(mensagens)
                return
            }
        }

        mensagens.add(Msg(limpo, true))
        mensagens.add(Msg("", false))
        val idx = mensagens.lastIndex
        ocupado = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    if (recriar) recriarConversa()
                    val conv = conversa ?: throw IllegalStateException("conversa não iniciada")
                    conv.sendMessageAsync(limpo).collect { parte ->
                        val atual = mensagens[idx]
                        mensagens[idx] = atual.copy(texto = atual.texto + parte.toString())
                    }
                }
            } catch (e: Exception) {
                val atual = mensagens[idx]
                mensagens[idx] = atual.copy(texto = atual.texto + "\n[erro: ${e.message}]")
            }
            memoria.salvarHistorico(mensagens)
            ocupado = false
        }
    }

    override fun onCleared() {
        conversa?.close()
        engine?.close()
    }
}
