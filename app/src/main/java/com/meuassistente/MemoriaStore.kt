package com.meuassistente

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MemoriaStore(context: Context) {
    private val arqFatos = File(context.filesDir, "fatos.json")
    private val arqHistorico = File(context.filesDir, "historico.json")

    fun carregarFatos(): List<String> {
        val arr = lerArray(arqFatos)
        return List(arr.length()) { arr.getString(it) }
    }

    fun salvarFatos(fatos: List<String>) {
        arqFatos.writeText(JSONArray(fatos).toString())
    }

    fun carregarHistorico(): List<Msg> {
        val arr = lerArray(arqHistorico)
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Msg(o.getString("t"), o.getBoolean("u"))
        }
    }

    fun salvarHistorico(msgs: List<Msg>) {
        val arr = JSONArray()
        msgs.takeLast(200).forEach {
            arr.put(JSONObject().put("t", it.texto).put("u", it.doUsuario))
        }
        arqHistorico.writeText(arr.toString())
    }

    private fun lerArray(f: File): JSONArray = try {
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }
}
