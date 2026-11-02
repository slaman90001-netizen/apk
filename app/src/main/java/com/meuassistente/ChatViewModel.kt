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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    @Volatile private var usarFerramentas = true
    @Volatile private var criadaEm = 0L

    val mensagens = mutableStateListOf<Msg>()
    val fatos = mutableStateListOf<String>()
    var status by mutableStateOf("")
    var pronto by mutableStateOf(false)
    var ocupado by mutableStateOf(false)
    var temModelo by mutableStateOf(arquivoModelo.exists())

    init {
        fatos.addAll(memoria.carregarFatos())
        mensagens.addAll(memoria.carregarHistorico())
        instalarRegistroDeErros()
        if (temModelo) viewModelScope.launch { carregarModelo() }
    }

    // ---------- Registro de erros ----------

    private fun resumoErro(e: Throwable): String {
        val sb = StringBuilder()
        var c: Throwable? = e
        var n = 0
        while (c != null && n < 4) {
            sb.append(c.javaClass.name).append(": ").append(c.message).append("\n")
            c.stackTrace.take(4).forEach { sb.append("  at ").append(it).append("\n") }
            c = c.cause
            n++
        }
        return sb.toString().take(1200)
    }

    private fun instalarRegistroDeErros() {
        val arq = File(getApplication<Application>().filesDir, "ultimo_erro.txt")
        if (arq.exists()) {
            val t = try { arq.readText() } catch (e: Exception) { "" }
            arq.delete()
            if (t.isNotBlank()) {
                mensagens.add(Msg("⚠️ O app fechou por um erro na última vez:\n" + t, false))
            }
        }
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            try {
                arq.writeText(resumoErro(e))
            } catch (ex: Exception) {
            }
            anterior?.uncaughtException(th, e)
        }
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
        val agora = SimpleDateFormat("EEEE, dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())
        sb.append("\n\nData e hora atuais: ").append(agora).append(".")
        if (usarFerramentas) {
            sb.append("\nVocê tem ferramentas para criar alarmes, timers e eventos na agenda. ")
            sb.append("Use-as somente quando o usuário pedir isso.")
        }
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
            if (a.doUsuario && !b.doUsuario && a.texto.isNotBlank() && b.texto.isNotBlank() &&
                !b.texto.startsWith("⚠️")
            ) {
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
        val sistema = Contents.of(promptSistema())
        val historico = historicoInicial()
        conversa = if (usarFerramentas) {
            try {
                e.createConversation(
                    ConversationConfig(
                        systemInstruction = sistema,
                        initialMessages = historico,
                        tools = listOf(tool(AcoesToolSet(getApplication<Application>().applicationContext)))
                    )
                )
            } catch (ex: Throwable) {
                usarFerramentas = false
                e.createConversation(
                    ConversationConfig(systemInstruction = sistema, initialMessages = historico)
                )
            }
        } else {
            e.createConversation(
                ConversationConfig(systemInstruction = sistema, initialMessages = historico)
            )
        }
        criadaEm = System.currentTimeMillis()
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
                    // Recria a conversa se a memória mudou ou se a data/hora do prompt ficou velha
                    if (recriar || System.currentTimeMillis() - criadaEm > 30 * 60 * 1000L) {
                        recriarConversa()
                    }
                    val conv = conversa ?: throw IllegalStateException("conversa não iniciada")
                    conv.sendMessageAsync(limpo).collect { parte ->
                        val atual = mensagens[idx]
                        mensagens[idx] = atual.copy(texto = atual.texto + parte.toString())
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val atual = mensagens[idx]
                mensagens[idx] = atual.copy(texto = atual.texto + "\n[erro: ${e.message}]")
                if (usarFerramentas) {
                    usarFerramentas = false
                    recriar = true
                    status = "Ferramentas desativadas após um erro. Alarmes e timers por conversa não funcionam até reabrir o app."
                }
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
