package com.netha.keyboardultra

import android.content.Context
import android.content.SharedPreferences

object KeyboardPrefs {
    const val FILE = "netha_keyboard_settings"
    const val THEME = "theme"
    const val KEY_ALPHA = "key_alpha"
    const val WALLPAPER_DIM = "wallpaper_dim"
    const val WALLPAPER_URI = "wallpaper_uri"
    const val WALLPAPER_FILE = "wallpaper_file"
    const val EFFECT = "falling_effect"
    const val CLIP_HISTORY = "clipboard_history"
    const val LANGUAGE_SINHALA = "sinhala_mode"
    const val ONLINE_SUGGESTIONS = "online_suggestions"
    const val SPACE_LABEL = "spacebar_label"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun theme(context: Context): String = get(context).getString(THEME, "Dark") ?: "Dark"
    fun keyAlpha(context: Context): Int = get(context).getInt(KEY_ALPHA, 92).coerceIn(20, 100)
    fun wallpaperDim(context: Context): Int = get(context).getInt(WALLPAPER_DIM, 26).coerceIn(0, 85)
    fun effect(context: Context): String = get(context).getString(EFFECT, "None") ?: "None"
    fun onlineSuggestionsEnabled(context: Context): Boolean =
        get(context).getBoolean(ONLINE_SUGGESTIONS, true)
    fun spaceLabel(context: Context): String =
        (get(context).getString(SPACE_LABEL, "NETHA") ?: "NETHA").trim()
            .take(14).ifBlank { "NETHA" }
}
