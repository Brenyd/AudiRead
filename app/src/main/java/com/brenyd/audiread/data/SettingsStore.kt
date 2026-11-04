package com.brenyd.audiread.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

object SettingsStore {
    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _reader = MutableStateFlow(ReaderSettings())
    private val _playback = MutableStateFlow(PlaybackSettings())
    val reader: StateFlow<ReaderSettings> = _reader.asStateFlow()
    val playback: StateFlow<PlaybackSettings> = _playback.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.getString("reader", null)?.let { s ->
            runCatching { _reader.value = json.decodeFromString(ReaderSettings.serializer(), s) }
        }
        prefs.getString("playback", null)?.let { s ->
            runCatching { _playback.value = json.decodeFromString(PlaybackSettings.serializer(), s) }
        }
    }

    fun setReader(s: ReaderSettings) {
        _reader.value = s
        prefs.edit().putString("reader", json.encodeToString(ReaderSettings.serializer(), s)).apply()
    }

    fun setPlayback(s: PlaybackSettings) {
        _playback.value = s
        prefs.edit().putString("playback", json.encodeToString(PlaybackSettings.serializer(), s)).apply()
    }
}
