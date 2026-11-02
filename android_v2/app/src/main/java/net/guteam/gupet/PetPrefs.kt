package net.guteam.gupet

import android.content.Context
import android.content.SharedPreferences

object PetPrefs {
    const val FILE = "pixpet_settings"

    const val SHOW = "show"
    const val SPECIES = "species"
    const val PALETTE = "color"
    const val COLOR_HEX = "color_hex"
    const val CUSTOM_SKIN_ID = "custom_skin_id"
    const val CUSTOM_SKIN_REVISION = "custom_skin_revision"
    const val SIZE_DP = "size_dp"
    const val WANDER = "wander"
    const val PHRASES = "phrases"
    const val GRAVITY = "gravity"
    const val SHAKE = "shake"
    const val HAPTICS = "haptics"
    const val EDGE_VIBRATION = "edge_vibration"
    const val POOP = "poop"
    const val MAX_POOPS = "max_poops"
    const val POOP_SIZE = "poop_size"
    const val POOP_INTERVAL = "poop_interval"
    const val POOP_COLOR = "poop_color"
    const val POOP_FADE_MS = "poop_fade_ms"
    const val WEIGHT = "weight"
    const val REACT_TO_POOP = "react_to_poop"
    const val REACT_TO_OTHER_PETS = "react_to_other_pets"
    const val WAKE_ON_PET_TOUCH = "wake_on_pet_touch"
    const val HIDE_ON_APP_OPEN = "hide_on_app_open"
    const val CAN_HANG = "can_hang"
    const val SLEEP_MINUTES = "sleep_minutes"
    const val REACT_NOTIFICATIONS = "react_notifications"
    const val DANCE = "dance"
    const val POS_X = "pos_x"
    const val POS_Y = "pos_y"
    const val PERSIST_POSITION = "persist_position"
    const val LAST_POS_X = "last_pos_x"
    const val LAST_POS_Y = "last_pos_y"
    const val ACTIVE_SKIN = "active_skin_id"
    const val ACTIVE_SKIN_IDS = "active_skin_ids"
    const val PETS_COLLIDE = "pets_collide"
    const val PHRASE_COLOR = "phrase_color"
    const val PHRASE_FONT_SIZE = "phrase_font_size"
    const val PHRASE_BG_COLOR = "phrase_bg_color"
    const val PHRASE_BG_ALPHA = "phrase_bg_alpha"
    const val PHRASE_OUTLINE_COLOR = "phrase_outline_color"
    const val PHRASE_OUTLINE_WIDTH = "phrase_outline_width"
    const val PHRASE_DURATION_MS = "phrase_duration_ms"
    const val PHRASE_FADE_MS = "phrase_fade_ms"
    const val PHRASE_FADE_STYLE = "phrase_fade_style"
    const val GRAVITY_STRENGTH = "gravity_strength"
    const val SLIDE_FRICTION = "slide_friction"
    const val PETS_COUNT = "pets_count"
    const val POOPS_CLEANED = "poops_cleaned"

    fun file(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): PetSettings = load(file(context))

    fun load(prefs: SharedPreferences): PetSettings = PetSettings(
        show = prefs.getBoolean(SHOW, false),
        species = PetSpecies.fromKey(prefs.getString(SPECIES, PetSpecies.GUPET.key)),
        palette = prefs.getInt(PALETTE, 0).coerceIn(0, PetPalettes.names.lastIndex),
        colorHex = prefs.getString(COLOR_HEX, null)?.takeIf { it.isNotBlank() },
        sizeDp = prefs.getFloat(SIZE_DP, 4.0f).coerceIn(1.0f, 15.0f),
        wander = prefs.getBoolean(WANDER, true),
        phrases = prefs.getBoolean(PHRASES, true),
        gravity = prefs.getBoolean(GRAVITY, false),
        shake = prefs.getBoolean(SHAKE, true),
        haptics = prefs.getBoolean(HAPTICS, true),
        edgeVibration = prefs.getBoolean(EDGE_VIBRATION, true),
        poop = prefs.getBoolean(POOP, true),
        maxPoops = prefs.getInt(MAX_POOPS, 3).coerceIn(1, 100),
        poopSize = prefs.getFloat(POOP_SIZE, 1.0f).coerceIn(0.5f, 3.0f),
        poopInterval = prefs.getFloat(POOP_INTERVAL, 1.0f).coerceIn(0.25f, 4.0f),
        poopColor = prefs.getString(POOP_COLOR, "#8b5a2b")?.takeIf { it.isNotBlank() } ?: "#8b5a2b",
        poopFadeMs = prefs.getInt(POOP_FADE_MS, 500).coerceIn(0, 3000),
        weight = prefs.getFloat(WEIGHT, 1.0f).coerceIn(0.1f, 5.0f),
        reactToPoop = prefs.getBoolean(REACT_TO_POOP, true),
        reactToOtherPets = prefs.getBoolean(REACT_TO_OTHER_PETS, true),
        wakeOnPetTouch = prefs.getBoolean(WAKE_ON_PET_TOUCH, true),
        hideOnAppOpen = prefs.getBoolean(HIDE_ON_APP_OPEN, false),
        canHang = prefs.getBoolean(CAN_HANG, true),
        sleepMinutes = prefs.getInt(SLEEP_MINUTES, 3).coerceIn(0, 10),
        reactNotifications = prefs.getBoolean(REACT_NOTIFICATIONS, false),
        dance = prefs.getBoolean(DANCE, true),
        persistPosition = prefs.getBoolean(PERSIST_POSITION, true),
        phraseColor = prefs.getString(PHRASE_COLOR, "#ffffff")?.takeIf { it.isNotBlank() } ?: "#ffffff",
        phraseFontSize = prefs.getFloat(PHRASE_FONT_SIZE, 14f).coerceIn(8f, 48f),
        phraseBgColor = prefs.getString(PHRASE_BG_COLOR, "#0b0b0b")?.takeIf { it.isNotBlank() } ?: "#0b0b0b",
        phraseBgAlpha = prefs.getFloat(PHRASE_BG_ALPHA, 0.85f).coerceIn(0f, 1f),
        phraseOutlineColor = prefs.getString(PHRASE_OUTLINE_COLOR, "#ff4d30")?.takeIf { it.isNotBlank() } ?: "#ff4d30",
        phraseOutlineWidth = prefs.getFloat(PHRASE_OUTLINE_WIDTH, 2f).coerceIn(0f, 12f),
        phraseDurationMs = prefs.getInt(PHRASE_DURATION_MS, 3000).coerceIn(500, 15000),
        phraseFadeMs = prefs.getInt(PHRASE_FADE_MS, 400).coerceIn(0, 3000),
        phraseFadeStyle = prefs.getString(PHRASE_FADE_STYLE, "fade")?.takeIf { it == "fade" || it == "slideUp" || it == "scale" || it == "none" } ?: "fade",
        gravityStrength = prefs.getFloat(GRAVITY_STRENGTH, 1.0f).coerceIn(0f, 2f),
        slideFriction = prefs.getFloat(SLIDE_FRICTION, 1.0f).coerceIn(0.1f, 3.0f),
        customSkinId = prefs.getString(CUSTOM_SKIN_ID, null),
        customSkinRevision = prefs.getLong(CUSTOM_SKIN_REVISION, 0L),
        activeSkinIds = loadActiveSkinIds(prefs),
        petsCollide = prefs.getBoolean(PETS_COLLIDE, true),
    )

    /** Выбранные скины (мультипитомец). Default: ['builtin'], минимум 1. */
    fun loadActiveSkinIds(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(ACTIVE_SKIN_IDS, null)
        val ids = runCatching {
            val arr = org.json.JSONArray(raw ?: "")
            List(arr.length()) { i -> arr.optString(i, "").trim() }
        }.getOrDefault(emptyList()).filter { it.isNotBlank() }.distinct()
        if (ids.isNotEmpty()) return ids
        // Обратная совместимость со старым одиночным выбором.
        val single = prefs.getString(ACTIVE_SKIN, null)?.trim()
        if (!single.isNullOrBlank()) return listOf(single)
        return listOf("builtin")
    }

    fun saveActiveSkinIds(prefs: SharedPreferences, ids: List<String>) {
        val clean = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            .ifEmpty { listOf("builtin") }
        prefs.edit()
            .putString(ACTIVE_SKIN_IDS, org.json.JSONArray(clean).toString())
            // Legacy-поля = первый выбранный (для старого кода/сервиса).
            .putString(ACTIVE_SKIN, clean.first())
            .apply()
    }

    /**
     * Настройки питомца из блока settings скина (.gupet).
     * Отсутствующие ключи (старые скины) добираются из PetPrefs — миграция 1.4.
     */
    fun settingsFromJson(prefs: SharedPreferences, json: org.json.JSONObject): PetSettings {
        val base = load(prefs)
        fun optStr(key: String, fb: String): String = json.optString(key, fb)
        val colorHex = json.optString("colorHex", null)
        return base.copy(
            species = PetSpecies.fromKey(json.optString("species", base.species.key)),
            palette = json.optInt("palette", base.palette),
            colorHex = if (json.isNull("colorHex") || colorHex.isNullOrEmpty()) null else colorHex,
            sizeDp = json.optDouble("sizeDp", base.sizeDp.toDouble()).toFloat().coerceIn(1.0f, 15.0f),
            wander = json.optBoolean("wander", base.wander),
            phrases = json.optBoolean("phrases", base.phrases),
            gravity = json.optBoolean("gravity", base.gravity),
            gravityStrength = json.optDouble("gravityStrength", base.gravityStrength.toDouble()).toFloat().coerceIn(0f, 2f),
            shake = json.optBoolean("shake", base.shake),
            haptics = json.optBoolean("haptics", base.haptics),
            edgeVibration = json.optBoolean("edgeVibration", base.edgeVibration),
            poop = json.optBoolean("poop", json.optBoolean("poopEnabled", base.poop)),
            maxPoops = json.optInt("maxPoops", base.maxPoops).coerceIn(1, 100),
            poopSize = json.optDouble("poopSize", base.poopSize.toDouble()).toFloat().coerceIn(0.5f, 3.0f),
            poopInterval = json.optDouble("poopInterval", base.poopInterval.toDouble()).toFloat().coerceIn(0.25f, 4.0f),
            poopColor = optStr("poopColor", base.poopColor).takeIf { it.isNotBlank() } ?: base.poopColor,
            poopFadeMs = json.optInt("poopFadeMs", base.poopFadeMs).coerceIn(0, 3000),
            weight = json.optDouble("weight", base.weight.toDouble()).toFloat().coerceIn(0.1f, 5.0f),
            reactToPoop = json.optBoolean("reactToPoop", base.reactToPoop),
            reactToOtherPets = json.optBoolean("reactToOtherPets", base.reactToOtherPets),
            wakeOnPetTouch = json.optBoolean("wakeOnPetTouch", base.wakeOnPetTouch),
            hideOnAppOpen = json.optBoolean("hideOnAppOpen", base.hideOnAppOpen),
            canHang = json.optBoolean("canHang", base.canHang),
            sleepMinutes = json.optInt("sleepMinutes", base.sleepMinutes).coerceIn(0, 10),
            reactNotifications = json.optBoolean("reactNotifications", base.reactNotifications),
            dance = json.optBoolean("dance", base.dance),
            persistPosition = json.optBoolean("persistPosition", base.persistPosition),
            phraseColor = optStr("phraseColor", base.phraseColor).takeIf { it.isNotBlank() } ?: base.phraseColor,
            phraseFontSize = json.optDouble("phraseFontSize", base.phraseFontSize.toDouble()).toFloat().coerceIn(8f, 48f),
            phraseBgColor = optStr("phraseBgColor", base.phraseBgColor).takeIf { it.isNotBlank() } ?: base.phraseBgColor,
            phraseBgAlpha = json.optDouble("phraseBgAlpha", base.phraseBgAlpha.toDouble()).toFloat().coerceIn(0f, 1f),
            phraseOutlineColor = optStr("phraseOutlineColor", base.phraseOutlineColor).takeIf { it.isNotBlank() } ?: base.phraseOutlineColor,
            phraseOutlineWidth = json.optDouble("phraseOutlineWidth", base.phraseOutlineWidth.toDouble()).toFloat().coerceIn(0f, 12f),
            phraseDurationMs = json.optInt("phraseDurationMs", base.phraseDurationMs).coerceIn(500, 15000),
            phraseFadeMs = json.optInt("phraseFadeMs", base.phraseFadeMs).coerceIn(0, 3000),
            phraseFadeStyle = json.optString("phraseFadeStyle", base.phraseFadeStyle)
                .takeIf { it == "fade" || it == "slideUp" || it == "scale" || it == "none" } ?: base.phraseFadeStyle,
            slideFriction = json.optDouble("slideFriction", base.slideFriction.toDouble()).toFloat().coerceIn(0.1f, 3.0f),
        )
    }
}

data class PetSettings(
    val show: Boolean,
    val species: PetSpecies,
    val palette: Int,
    val colorHex: String?,
    val sizeDp: Float,
    val wander: Boolean,
    val phrases: Boolean,
    val gravity: Boolean,
    val shake: Boolean,
    val haptics: Boolean,
    val edgeVibration: Boolean,
    val poop: Boolean,
    val maxPoops: Int,
    val poopSize: Float,
    val poopInterval: Float,
    val poopColor: String,
    val poopFadeMs: Int,
    val weight: Float,
    val reactToPoop: Boolean,
    val reactToOtherPets: Boolean,
    val wakeOnPetTouch: Boolean,
    val hideOnAppOpen: Boolean,
    val canHang: Boolean,
    val sleepMinutes: Int,
    val reactNotifications: Boolean,
    val dance: Boolean,
    val persistPosition: Boolean,
    val phraseColor: String,
    val phraseFontSize: Float,
    val phraseBgColor: String,
    val phraseBgAlpha: Float,
    val phraseOutlineColor: String,
    val phraseOutlineWidth: Float,
    val phraseDurationMs: Int,
    val phraseFadeMs: Int,
    val phraseFadeStyle: String,
    val gravityStrength: Float,
    val slideFriction: Float,
    val customSkinId: String?,
    val customSkinRevision: Long,
    val activeSkinIds: List<String>,
    val petsCollide: Boolean,
)
