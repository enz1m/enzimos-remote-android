package com.enzimos.remote

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Streaming adjustments the user made for one app on one computer, so apps that
 * need more room or a mouse stay usable the next time they are opened.
 */
@Serializable
data class AppStreamSettings(
    /** [INPUT_TOUCH] (tap, scroll, hold to drag) or [INPUT_TRACKPAD] (cursor). */
    val input: String = INPUT_TOUCH,
    /** Interface size in percent: below 100 gives the app more room, above makes it larger. */
    val sizePercent: Int = 100,
    /** [SHAPE_FILL] or a landscape ratio such as "4:3". */
    val shape: String = SHAPE_FILL,
    /** Show the on-screen game controller over the stream. */
    val controller: Boolean = false,
    /** On-screen controller size and opacity, in percent. */
    val controllerSize: Int = 100,
    val controllerOpacity: Int = 45,
    /** Moved controller groups: name → [dx, dy] as fractions of the short side. */
    val controllerLayout: Map<String, List<Float>> = emptyMap(),
) {
    val trackpad: Boolean get() = input == INPUT_TRACKPAD
    val isDefault: Boolean get() = this == AppStreamSettings()

    /** Density the app is laid out at: the phone's density scaled by the size. */
    fun density(deviceDensity: Double): Double = deviceDensity * sizePercent.coerceIn(SIZES.first(), SIZES.last()) / 100.0

    /** The largest area of [shape] that fits the available pixels. */
    fun canvas(widthPixels: Int, heightPixels: Int): Pair<Int, Int> {
        val ratio = ratio(shape) ?: return widthPixels to heightPixels
        return if (widthPixels.toDouble() / heightPixels > ratio) (heightPixels * ratio).toInt() to heightPixels
            else widthPixels to (widthPixels / ratio).toInt()
    }

    fun normalized(): AppStreamSettings = copy(
        input = if (input == INPUT_TRACKPAD) INPUT_TRACKPAD else INPUT_TOUCH,
        sizePercent = SIZES.minBy { kotlin.math.abs(it - sizePercent) },
        shape = if (shape in SHAPES) shape else SHAPE_FILL,
        controllerSize = controllerSize.coerceIn(60, 140),
        controllerOpacity = controllerOpacity.coerceIn(20, 90),
        controllerLayout = controllerLayout.filterValues { it.size == 2 && it.all { v -> v.isFinite() && kotlin.math.abs(v) < 2f } },
    )

    companion object {
        const val INPUT_TOUCH = "touch"
        const val INPUT_TRACKPAD = "trackpad"
        const val SHAPE_FILL = "fill"
        val SIZES = listOf(50, 60, 70, 80, 90, 100, 115, 130, 150)
        val SHAPES = listOf(SHAPE_FILL, "1:1", "4:3", "16:10")

        fun ratio(shape: String): Double? {
            val parts = shape.split(':').mapNotNull(String::toDoubleOrNull)
            return parts.takeIf { it.size == 2 && it.all { value -> value > 0 } }?.let { it[0] / it[1] }
        }
    }
}

/** Per computer and app; kept when the app is closed, removed with the computer. */
class AppStreamSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_stream_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun get(hostId: String, appId: String): AppStreamSettings {
        if (appId.isBlank()) return AppStreamSettings()
        val raw = prefs.getString(key(hostId, appId), null) ?: return AppStreamSettings()
        return runCatching { json.decodeFromString<AppStreamSettings>(raw).normalized() }.getOrDefault(AppStreamSettings())
    }

    fun set(hostId: String, appId: String, settings: AppStreamSettings) {
        if (appId.isBlank()) return
        val editor = prefs.edit()
        if (settings.isDefault) editor.remove(key(hostId, appId))
        else editor.putString(key(hostId, appId), json.encodeToString(AppStreamSettings.serializer(), settings.normalized()))
        editor.apply()
    }

    fun forget(hostId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$hostId/") }.forEach(editor::remove)
        editor.apply()
    }

    private fun key(hostId: String, appId: String) = "$hostId/$appId"
}
