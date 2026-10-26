package com.frizovdanya.pixpet

import android.content.Context
import android.content.SharedPreferences

object PetPrefs {
    const val FILE = "pixpet_settings"

    const val SHOW = "show"
    const val SPECIES = "species"
    const val PALETTE = "color"
    const val CUSTOM_SKIN_ID = "custom_skin_id"
    const val CUSTOM_SKIN_REVISION = "custom_skin_revision"
    const val SIZE_DP = "size_dp"
    const val WANDER = "wander"
    const val PHRASES = "phrases"
    const val GRAVITY = "gravity"
    const val SHAKE = "shake"
    const val HAPTICS = "haptics"
    const val POOP = "poop"
    const val SLEEP_MINUTES = "sleep_minutes"
    const val REACT_NOTIFICATIONS = "react_notifications"
    const val DANCE = "dance"
    const val POS_X = "pos_x"
    const val POS_Y = "pos_y"
    const val PETS_COUNT = "pets_count"
    const val POOPS_CLEANED = "poops_cleaned"

    fun file(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): PetSettings = load(file(context))

    fun load(prefs: SharedPreferences): PetSettings = PetSettings(
        show = prefs.getBoolean(SHOW, false),
        species = PetSpecies.fromKey(prefs.getString(SPECIES, PetSpecies.GUPET.key)),
        palette = prefs.getInt(PALETTE, 0).coerceIn(0, PetPalettes.names.lastIndex),
        sizeDp = prefs.getFloat(SIZE_DP, 4.0f).coerceIn(3.0f, 5.0f),
        wander = prefs.getBoolean(WANDER, true),
        phrases = prefs.getBoolean(PHRASES, true),
        gravity = prefs.getBoolean(GRAVITY, false),
        shake = prefs.getBoolean(SHAKE, true),
        haptics = prefs.getBoolean(HAPTICS, true),
        poop = prefs.getBoolean(POOP, true),
        sleepMinutes = prefs.getInt(SLEEP_MINUTES, 3).coerceIn(0, 10),
        reactNotifications = prefs.getBoolean(REACT_NOTIFICATIONS, false),
        dance = prefs.getBoolean(DANCE, true),
        customSkinId = prefs.getString(CUSTOM_SKIN_ID, null),
        customSkinRevision = prefs.getLong(CUSTOM_SKIN_REVISION, 0L),
    )
}

data class PetSettings(
    val show: Boolean,
    val species: PetSpecies,
    val palette: Int,
    val sizeDp: Float,
    val wander: Boolean,
    val phrases: Boolean,
    val gravity: Boolean,
    val shake: Boolean,
    val haptics: Boolean,
    val poop: Boolean,
    val sleepMinutes: Int,
    val reactNotifications: Boolean,
    val dance: Boolean,
    val customSkinId: String?,
    val customSkinRevision: Long,
)
