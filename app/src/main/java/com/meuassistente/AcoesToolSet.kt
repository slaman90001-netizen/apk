package com.meuassistente

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.widget.Toast
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.text.SimpleDateFormat
import java.util.Locale

class AcoesToolSet(private val ctx: Context) : ToolSet {

    private fun abrir(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun avisar(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    @Tool(description = "Cria um alarme no relógio do celular para um horário do dia.")
    fun criarAlarme(
        @ToolParam(description = "Hora do alarme, de 0 a 23") hora: Int,
        @ToolParam(description = "Minuto do alarme, de 0 a 59") minuto: Int,
        @ToolParam(description = "Nome do alarme. Padrão: Alarme") descricao: String = "Alarme"
    ): Map<String, Any> {
        if (hora !in 0..23 || minuto !in 0..59) {
            return mapOf("sucesso" to false, "erro" to "horário inválido")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hora)
            putExtra(AlarmClock.EXTRA_MINUTES, minuto)
            putExtra(AlarmClock.EXTRA_MESSAGE, descricao)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val ok = abrir(intent)
        val horario = "%02d:%02d".format(hora, minuto)
        if (ok) avisar("⏰ Alarme criado para $horario")
        return mapOf("sucesso" to ok, "horario" to horario)
    }

    @Tool(description = "Inicia um timer (contagem regressiva) no relógio do celular.")
    fun criarTimer(
        @ToolParam(description = "Duração total em minutos, pode ser decimal") minutos: Double,
        @ToolParam(description = "Nome do timer. Padrão: Timer") descricao: String = "Timer"
    ): Map<String, Any> {
        val segundos = (minutos * 60).toInt()
        if (segundos < 1) {
            return mapOf("sucesso" to false, "erro" to "duração inválida")
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, segundos)
            putExtra(AlarmClock.EXTRA_MESSAGE, descricao)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val ok = abrir(intent)
        if (ok) avisar("⏳ Timer iniciado: $segundos segundos")
        return mapOf("sucesso" to ok, "segundos" to segundos)
    }

    @Tool(description = "Abre a agenda do celular com um novo evento preenchido, para o usuário confirmar.")
    fun criarEvento(
        @ToolParam(description = "Título do evento") titulo: String,
        @ToolParam(description = "Data no formato dd/MM/yyyy") dataEvento: String,
        @ToolParam(description = "Hora de início no formato HH:mm, 24 horas") hora: String,
        @ToolParam(description = "Duração em minutos. Padrão: 60") duracaoMinutos: Int = 60
    ): Map<String, Any> {
        val inicio: Long = try {
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                .parse("$dataEvento $hora")!!.time
        } catch (e: Exception) {
            return mapOf("sucesso" to false, "erro" to "data ou hora inválida")
        }
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, titulo)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, inicio)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, inicio + duracaoMinutos * 60_000L)
        }
        val ok = abrir(intent)
        return mapOf("sucesso" to ok, "titulo" to titulo)
    }
}
