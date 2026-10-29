package net.guteam.gupet

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Полные скины GUPet: настройки + опциональные PNG-кадры.
 * Встроенный «Стандартный GUPet» — виртуальный (id "builtin").
 * Пользовательские лежат в filesDir/gupet/skins/<id>/ (см. GUPetSkinStore).
 */
data class SkinInfo(
    val id: String,
    val name: String,
    val author: String,
    val builtin: Boolean = false,
    val hasSettings: Boolean = false,
    val hasFrames: Boolean = false,
)

object SkinStore {
    const val BUILTIN_ID = "builtin"

    fun list(context: Context): List<SkinInfo> {
        val mine = GUPetSkinStore.list(context).map { info ->
            SkinInfo(
                id = info.id,
                name = info.name,
                author = info.author,
                hasSettings = GUPetSkinStore.readSettingsJson(context, info.id) != null,
                hasFrames = GUPetSkinStore.hasFrames(context, info.id),
            )
        }
        return listOf(SkinInfo(BUILTIN_ID, "Стандартный GUPet", "GUTeam", builtin = true)) + mine
    }

    /** Снимок текущих настроек в формате блока settings манифеста. */
    fun snapshotCurrent(context: Context): JSONObject {
        val s = PetPrefs.load(context)
        val o = JSONObject()
            .put("species", s.species.key)
            .put("sizeDp", s.sizeDp.toDouble())
            .put("colorHex", s.colorHex ?: JSONObject.NULL)
            .put("palette", s.palette)
            .put("wander", s.wander)
            .put("phrases", s.phrases)
            .put("gravity", s.gravity)
            .put("gravityStrength", s.gravityStrength.toDouble())
            .put("shake", s.shake)
            .put("haptics", s.haptics)
            .put("edgeVibration", s.edgeVibration)
            .put("poop", s.poop)
            .put("maxPoops", s.maxPoops)
            .put("poopSize", s.poopSize.toDouble())
            .put("poopInterval", s.poopInterval.toDouble())
            .put("poopColor", s.poopColor)
            .put("poopFadeMs", s.poopFadeMs)
            .put("weight", s.weight.toDouble())
            .put("reactToPoop", s.reactToPoop)
            .put("reactToOtherPets", s.reactToOtherPets)
            .put("wakeOnPetTouch", s.wakeOnPetTouch)
            .put("hideOnAppOpen", s.hideOnAppOpen)
            .put("canHang", s.canHang)
            .put("sleepMinutes", s.sleepMinutes)
            .put("reactNotifications", s.reactNotifications)
            .put("dance", s.dance)
            .put("persistPosition", s.persistPosition)
            .put("phraseColor", s.phraseColor)
            .put("phraseFontSize", s.phraseFontSize.toDouble())
            .put("phraseBgColor", s.phraseBgColor)
            .put("phraseBgAlpha", s.phraseBgAlpha.toDouble())
            .put("phraseOutlineColor", s.phraseOutlineColor)
            .put("phraseOutlineWidth", s.phraseOutlineWidth.toDouble())
            .put("phraseDurationMs", s.phraseDurationMs)
            .put("phraseFadeMs", s.phraseFadeMs)
            .put("phraseFadeStyle", s.phraseFadeStyle)
        val arr = JSONArray()
        PetPhrasesStore.load(context).forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("text", p.text)
                    .put("context", p.context)
                    .put("weight", p.weight)
                    .put("enabled", p.enabled)
            )
        }
        o.put("phrases", arr)
        return o
    }

    /** Записать блок settings в PetPrefs (отсутствующие ключи не трогаем). */
    fun applySettingsJson(context: Context, settings: JSONObject) {
        val prefs = PetPrefs.file(context)
        val e = prefs.edit()
        fun optStr(key: String, fb: String): String = settings.optString(key, fb)
        e.putString(PetPrefs.SPECIES, PetSpecies.fromKey(settings.optString("species", prefs.getString(PetPrefs.SPECIES, "gupet"))).key)
        e.putInt(PetPrefs.PALETTE, settings.optInt("palette", prefs.getInt(PetPrefs.PALETTE, 0)))
        val colorHex = settings.optString("colorHex", null)
        if (settings.isNull("colorHex") || colorHex.isNullOrEmpty()) e.remove(PetPrefs.COLOR_HEX)
        else e.putString(PetPrefs.COLOR_HEX, colorHex)
        e.putFloat(PetPrefs.SIZE_DP, settings.optDouble("sizeDp", prefs.getFloat(PetPrefs.SIZE_DP, 4f).toDouble()).toFloat())
        e.putBoolean(PetPrefs.WANDER, settings.optBoolean("wander", prefs.getBoolean(PetPrefs.WANDER, true)))
        e.putBoolean(PetPrefs.PHRASES, settings.optBoolean("phrases", prefs.getBoolean(PetPrefs.PHRASES, true)))
        e.putBoolean(PetPrefs.GRAVITY, settings.optBoolean("gravity", prefs.getBoolean(PetPrefs.GRAVITY, false)))
        e.putFloat(PetPrefs.GRAVITY_STRENGTH, settings.optDouble("gravityStrength", prefs.getFloat(PetPrefs.GRAVITY_STRENGTH, 1f).toDouble()).toFloat())
        e.putBoolean(PetPrefs.SHAKE, settings.optBoolean("shake", prefs.getBoolean(PetPrefs.SHAKE, true)))
        e.putBoolean(PetPrefs.HAPTICS, settings.optBoolean("haptics", prefs.getBoolean(PetPrefs.HAPTICS, true)))
        e.putBoolean(PetPrefs.EDGE_VIBRATION, settings.optBoolean("edgeVibration", prefs.getBoolean(PetPrefs.EDGE_VIBRATION, true)))
        val poopFallback = prefs.getBoolean(PetPrefs.POOP, true)
        val poopValue = if (settings.has("poopEnabled")) {
            settings.optBoolean("poopEnabled", poopFallback)
        } else {
            settings.optBoolean("poop", poopFallback)
        }
        e.putBoolean(PetPrefs.POOP, poopValue)
        e.putInt(PetPrefs.MAX_POOPS, settings.optInt("maxPoops", prefs.getInt(PetPrefs.MAX_POOPS, 3)))
        e.putFloat(PetPrefs.POOP_SIZE, settings.optDouble("poopSize", prefs.getFloat(PetPrefs.POOP_SIZE, 1f).toDouble()).toFloat())
        e.putFloat(PetPrefs.POOP_INTERVAL, settings.optDouble("poopInterval", prefs.getFloat(PetPrefs.POOP_INTERVAL, 1f).toDouble()).toFloat())
        e.putString(PetPrefs.POOP_COLOR, optStr("poopColor", prefs.getString(PetPrefs.POOP_COLOR, "#8b5a2b") ?: "#8b5a2b"))
        e.putInt(PetPrefs.POOP_FADE_MS, settings.optInt("poopFadeMs", prefs.getInt(PetPrefs.POOP_FADE_MS, 500)))
        e.putFloat(PetPrefs.WEIGHT, settings.optDouble("weight", prefs.getFloat(PetPrefs.WEIGHT, 1f).toDouble()).toFloat())
        e.putBoolean(PetPrefs.REACT_TO_POOP, settings.optBoolean("reactToPoop", prefs.getBoolean(PetPrefs.REACT_TO_POOP, true)))
        e.putBoolean(PetPrefs.REACT_TO_OTHER_PETS, settings.optBoolean("reactToOtherPets", prefs.getBoolean(PetPrefs.REACT_TO_OTHER_PETS, true)))
        e.putBoolean(PetPrefs.WAKE_ON_PET_TOUCH, settings.optBoolean("wakeOnPetTouch", prefs.getBoolean(PetPrefs.WAKE_ON_PET_TOUCH, true)))
        e.putBoolean(PetPrefs.HIDE_ON_APP_OPEN, settings.optBoolean("hideOnAppOpen", prefs.getBoolean(PetPrefs.HIDE_ON_APP_OPEN, false)))
        e.putBoolean(PetPrefs.CAN_HANG, settings.optBoolean("canHang", prefs.getBoolean(PetPrefs.CAN_HANG, true)))
        e.putInt(PetPrefs.SLEEP_MINUTES, settings.optInt("sleepMinutes", prefs.getInt(PetPrefs.SLEEP_MINUTES, 3)))
        e.putBoolean(PetPrefs.REACT_NOTIFICATIONS, settings.optBoolean("reactNotifications", prefs.getBoolean(PetPrefs.REACT_NOTIFICATIONS, false)))
        e.putBoolean(PetPrefs.DANCE, settings.optBoolean("dance", prefs.getBoolean(PetPrefs.DANCE, true)))
        e.putBoolean(PetPrefs.PERSIST_POSITION, settings.optBoolean("persistPosition", prefs.getBoolean(PetPrefs.PERSIST_POSITION, true)))
        e.putString(PetPrefs.PHRASE_COLOR, optStr("phraseColor", prefs.getString(PetPrefs.PHRASE_COLOR, "#ffffff") ?: "#ffffff"))
        e.putFloat(PetPrefs.PHRASE_FONT_SIZE, settings.optDouble("phraseFontSize", prefs.getFloat(PetPrefs.PHRASE_FONT_SIZE, 14f).toDouble()).toFloat())
        e.putString(PetPrefs.PHRASE_BG_COLOR, optStr("phraseBgColor", prefs.getString(PetPrefs.PHRASE_BG_COLOR, "#0b0b0b") ?: "#0b0b0b"))
        e.putFloat(PetPrefs.PHRASE_BG_ALPHA, settings.optDouble("phraseBgAlpha", prefs.getFloat(PetPrefs.PHRASE_BG_ALPHA, 0.85f).toDouble()).toFloat())
        e.putString(PetPrefs.PHRASE_OUTLINE_COLOR, optStr("phraseOutlineColor", prefs.getString(PetPrefs.PHRASE_OUTLINE_COLOR, "#ff4d30") ?: "#ff4d30"))
        e.putFloat(PetPrefs.PHRASE_OUTLINE_WIDTH, settings.optDouble("phraseOutlineWidth", prefs.getFloat(PetPrefs.PHRASE_OUTLINE_WIDTH, 2f).toDouble()).toFloat())
        e.putInt(PetPrefs.PHRASE_DURATION_MS, settings.optInt("phraseDurationMs", prefs.getInt(PetPrefs.PHRASE_DURATION_MS, 3000)))
        e.putInt(PetPrefs.PHRASE_FADE_MS, settings.optInt("phraseFadeMs", prefs.getInt(PetPrefs.PHRASE_FADE_MS, 400)))
        e.putString(PetPrefs.PHRASE_FADE_STYLE, settings.optString("phraseFadeStyle", prefs.getString(PetPrefs.PHRASE_FADE_STYLE, "fade")))
        e.apply()
        val arr = settings.optJSONArray("phrases")
        if (arr != null) {
            val list = List(arr.length()) { i ->
                val p = arr.getJSONObject(i)
                Phrase(
                    id = p.optString("id", "p$i"),
                    text = p.optString("text", ""),
                    context = p.optString("context", "idle"),
                    weight = p.optInt("weight", 1),
                    enabled = p.optBoolean("enabled", true),
                )
            }.filter { it.text.isNotBlank() }
            PetPhrasesStore.save(context, list)
        }
    }

    /**
     * Дублировать скин: новый .gupet с уникальными id/именем.
     * builtin (без файлов) клонируется как процедурный скин:
     * прозрачный idle.png 18×13 + снимок текущих настроек.
     * Возвращает id нового скина или null. Новый скин НЕ активируется.
     */
    fun duplicate(context: Context, sourceId: String): String? {
        val existing = GUPetSkinStore.list(context)
        val srcName: String
        val srcAuthor: String
        val srcSettings: JSONObject
        val srcFrames = linkedMapOf<String, ByteArray>()
        if (sourceId == BUILTIN_ID) {
            srcName = "GUPet"
            srcAuthor = "GUTeam"
            srcSettings = snapshotCurrent(context)
        } else {
            val info = existing.firstOrNull { it.id == sourceId } ?: return null
            val folder = GUPetSkinStore.skinFolder(context, sourceId) ?: return null
            val manifest = runCatching {
                JSONObject(java.io.File(folder, "manifest.json").readText(Charsets.UTF_8))
            }.getOrNull() ?: return null
            srcName = info.name
            srcAuthor = info.author
            srcSettings = manifest.optJSONObject("settings")
                ?.let { JSONObject(it.toString()) } ?: snapshotCurrent(context)
            val framesObj = manifest.optJSONObject("frames")
            if (framesObj != null) {
                val keys = framesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val fileName = framesObj.optString(key, "")
                    if (fileName != "$key.png") continue
                    val file = java.io.File(folder, fileName)
                    if (file.isFile) srcFrames[key] = file.readBytes()
                }
            }
        }
        // Уникальное имя «<оригинал> 2», «<оригинал> 3», …
        val takenNames = existing.map { it.name }.toSet()
        var name = "$srcName 2".take(40)
        var n = 2
        while (name in takenNames) {
            n++
            name = "$srcName $n".take(40)
        }
        // Уникальный id.
        var id = "clone-${System.currentTimeMillis()}"
        while (GUPetSkinStore.skinFolder(context, id) != null) {
            id = "clone-${System.nanoTime()}"
        }
        return runCatching {
            val root = java.io.File(context.filesDir, "gupet/skins")
            if (!root.exists() && !root.mkdirs()) return null
            val folder = java.io.File(root, id)
            if (!folder.mkdirs()) return null
            try {
                val framesJson = JSONObject()
                if (sourceId == BUILTIN_ID) {
                    // Клон builtin — процедурный (species=GUPET из settings):
                    // PNG не создаём, frames пустой, рендер через PetArtwork.
                } else if (srcFrames.isEmpty()) {
                    // Заглушка: прозрачный idle.png 18×13.
                    val blank = Bitmap.createBitmap(
                        GUPetSkinStore.WIDTH, GUPetSkinStore.HEIGHT, Bitmap.Config.ARGB_8888
                    )
                    blank.eraseColor(android.graphics.Color.TRANSPARENT)
                    java.io.File(folder, "idle.png").outputStream().use { out ->
                        if (!blank.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            throw IllegalArgumentException("Не удалось записать idle.png")
                        }
                    }
                    framesJson.put("idle", "idle.png")
                } else {
                    for ((key, bytes) in srcFrames) {
                        java.io.File(folder, "$key.png").writeBytes(bytes)
                        framesJson.put(key, "$key.png")
                    }
                }
                val manifest = JSONObject()
                    .put("format", "GUPet")
                    .put("formatVersion", 1)
                    .put("version", 2)
                    .put("width", GUPetSkinStore.WIDTH)
                    .put("height", GUPetSkinStore.HEIGHT)
                    .put("id", id)
                    .put("name", name)
                    .put("author", srcAuthor.ifBlank { "Не указан" }.take(40))
                    .put("frames", framesJson)
                    .put("settings", JSONObject(srcSettings.toString()))
                java.io.File(folder, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
                id
            } catch (e: Exception) {
                folder.deleteRecursively()
                throw e
            }
        }.getOrNull()
    }

    /** Применить скин: настройки + внешний вид. Сервис подхватит сам. */
    fun applySkin(context: Context, id: String): Boolean {
        if (id == BUILTIN_ID) {
            resetPetDefaults(context)
            return true
        }
        val settings = GUPetSkinStore.readSettingsJson(context, id)
        if (settings == null) {
            // PNG-скин без блока settings (старый формат/импорт): применяем только вид.
            if (!GUPetSkinStore.hasFrames(context, id)) return false
            PetPrefs.file(context).edit()
                .putString(PetPrefs.SPECIES, PetSpecies.SKIN.key)
                .putString(PetPrefs.CUSTOM_SKIN_ID, id)
                .putString(PetPrefs.ACTIVE_SKIN, id)
                .apply()
            return true
        }
        val frames = GUPetSkinStore.hasFrames(context, id)
        applySettingsJson(context, settings)
        val prefs = PetPrefs.file(context)
        val e = prefs.edit()
        if (settings.optString("species", "") == "skin" && frames) {
            e.putString(PetPrefs.SPECIES, PetSpecies.SKIN.key)
            e.putString(PetPrefs.CUSTOM_SKIN_ID, id)
        } else {
            if (settings.optString("species", "") == "skin") {
                e.putString(PetPrefs.SPECIES, PetSpecies.GUPET.key)
            }
            e.remove(PetPrefs.CUSTOM_SKIN_ID)
        }
        e.putString(PetPrefs.ACTIVE_SKIN, id)
        e.apply()
        return true
    }

    /** Сброс к дефолтам PetPrefs (флаг показа сохраняем). */
    fun resetPetDefaults(context: Context) {
        val prefs = PetPrefs.file(context)
        val show = prefs.getBoolean(PetPrefs.SHOW, false)
        prefs.edit().clear().apply()
        prefs.edit()
            .putBoolean(PetPrefs.SHOW, show)
            .putString(PetPrefs.ACTIVE_SKIN, BUILTIN_ID)
            .apply()
        context.getSharedPreferences("gupet_phrases", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
