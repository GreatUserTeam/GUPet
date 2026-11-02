package net.guteam.gupet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Phrase(
    val id: String,
    val text: String,
    val context: String,
    val weight: Int = 1,
    val enabled: Boolean = true,
)

object PetPhrasesStore {
    private const val FILE = "gupet_phrases"
    private const val KEY_JSON = "phrases_json"

    fun load(context: Context): List<Phrase> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
        if (raw.isNullOrBlank()) return defaultPhrases()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Phrase(
                    id = o.optString("id", "p$i"),
                    text = o.optString("text", ""),
                    context = o.optString("context", "idle"),
                    weight = o.optInt("weight", 1).coerceIn(1, 10),
                    enabled = o.optBoolean("enabled", true),
                )
            }.filter { it.text.isNotBlank() }
        }.getOrDefault(defaultPhrases())
    }

    fun save(context: Context, list: List<Phrase>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("text", p.text)
                    .put("context", p.context)
                    .put("weight", p.weight.coerceIn(1, 10))
                    .put("enabled", p.enabled)
            )
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, arr.toString()).apply()
    }

    fun defaultPhrases(): List<Phrase> {
        val out = mutableListOf<Phrase>()
        // Подмешиваем встроенные тексты + минимум своих на каждый контекст.
        val seen = mutableSetOf<String>()
        OverlayPetService.PHRASES.forEach { (context, texts) ->
            texts.forEachIndexed { i, text ->
                out.add(Phrase(id = "$context-$i", text = text, context = context))
                seen.add(context)
            }
        }
        val extra = mapOf(
            "idle" to listOf("Тянусь к новым пикселям", "Сегодня отличный день", "Мурчу потихоньку"),
            "walk" to listOf("Топ-топ!", "Иду гулять", "Куда пойдём?"),
            "pet" to listOf("Ещё гладь!", "Мурр", "Приятно!"),
            "sleep" to listOf("Сплю…", "Тсс…", "Сладких снов"),
            "shake" to listOf("Осторожно!", "Трясёт!", "Держусь!"),
            "hang" to listOf("Вишу!", "Не упасть бы", "Высоко!"),
            "dance" to listOf("Танцуем!", "Ля-ля-ля", "В ритме!"),
        )
        extra.forEach { (context, texts) ->
            val have = out.count { it.context == context }
            texts.forEachIndexed { i, text ->
                if (have + i < 3 || context == "idle" && have + i < 10) {
                    out.add(Phrase(id = "$context-x$i", text = text, context = context))
                }
            }
        }
        return out
    }
}
