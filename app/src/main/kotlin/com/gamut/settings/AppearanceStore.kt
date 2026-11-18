package com.gamut.settings

import android.content.Context
import com.gamut.core.designsystem.AlbumStyle
import com.gamut.core.designsystem.AppThemeMode
import com.gamut.core.designsystem.AppearanceLevel
import com.gamut.core.designsystem.AppearanceSettings
import com.gamut.core.designsystem.ArtistStyle
import com.gamut.core.designsystem.FavoritesStyle
import com.gamut.core.designsystem.FlowStyle
import com.gamut.core.designsystem.PlayerStyle
import com.gamut.core.designsystem.PlaylistStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class AppearanceStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("gamut_appearance", Context.MODE_PRIVATE)
        private val _settings = MutableStateFlow(load())
        val settings: StateFlow<AppearanceSettings> = _settings

        /** Fill strength of the grouped settings blocks, as the tint alpha they are drawn with. */
        private val _surfaceOpacity =
            MutableStateFlow(
                prefs.getFloat(KEY_SURFACE_OPACITY, DEFAULT_SURFACE_OPACITY).coerceIn(MIN_SURFACE_OPACITY, MAX_SURFACE_OPACITY),
            )
        val surfaceOpacity: StateFlow<Float> = _surfaceOpacity

        /** When on, settings blocks get a solid fill (see [surfaceColor]) instead of the see-through tint. */
        private val _surfaceSolid = MutableStateFlow(prefs.getBoolean(KEY_SURFACE_SOLID, false))
        val surfaceSolid: StateFlow<Boolean> = _surfaceSolid

        /** ARGB of the solid fill. [SURFACE_COLOR_AUTO] (0) means a soft gray picked from light / dark. */
        private val _surfaceColor = MutableStateFlow(prefs.getInt(KEY_SURFACE_COLOR, SURFACE_COLOR_AUTO))
        val surfaceColor: StateFlow<Int> = _surfaceColor

        /** Layout of the Favorites page. Kept outside [AppearanceSettings] so that type stays untouched. */
        private val _favoritesStyle = MutableStateFlow(enum(KEY_FAVORITES_STYLE, FavoritesStyle.Grouped))
        val favoritesStyle: StateFlow<FavoritesStyle> = _favoritesStyle

        fun setFavoritesStyle(value: FavoritesStyle) {
            _favoritesStyle.value = value
            prefs.edit().putString(KEY_FAVORITES_STYLE, value.name).apply()
        }

        /** Layout of the album page. Also kept outside [AppearanceSettings]. */
        private val _albumStyle = MutableStateFlow(enum(KEY_ALBUM_STYLE, AlbumStyle.Classic))
        val albumStyle: StateFlow<AlbumStyle> = _albumStyle

        fun setAlbumStyle(value: AlbumStyle) {
            _albumStyle.value = value
            prefs.edit().putString(KEY_ALBUM_STYLE, value.name).apply()
        }

        /** Layout of the artist page. Also kept outside [AppearanceSettings]. */
        private val _artistStyle = MutableStateFlow(enum(KEY_ARTIST_STYLE, ArtistStyle.Classic))
        val artistStyle: StateFlow<ArtistStyle> = _artistStyle

        fun setArtistStyle(value: ArtistStyle) {
            _artistStyle.value = value
            prefs.edit().putString(KEY_ARTIST_STYLE, value.name).apply()
        }

        /** Layout of the playlist page. Also kept outside [AppearanceSettings]. */
        private val _playlistStyle = MutableStateFlow(enum(KEY_PLAYLIST_STYLE, PlaylistStyle.Classic))
        val playlistStyle: StateFlow<PlaylistStyle> = _playlistStyle

        fun setPlaylistStyle(value: PlaylistStyle) {
            _playlistStyle.value = value
            prefs.edit().putString(KEY_PLAYLIST_STYLE, value.name).apply()
        }

        fun setSurfaceSolid(value: Boolean) {
            _surfaceSolid.value = value
            prefs.edit().putBoolean(KEY_SURFACE_SOLID, value).apply()
        }

        fun setSurfaceColor(argb: Int) {
            _surfaceColor.value = argb
            prefs.edit().putInt(KEY_SURFACE_COLOR, argb).apply()
        }

        fun setSurfaceOpacity(value: Float) {
            val next = ((value * 100).roundToInt() / 100f).coerceIn(MIN_SURFACE_OPACITY, MAX_SURFACE_OPACITY)
            _surfaceOpacity.value = next
            prefs.edit().putFloat(KEY_SURFACE_OPACITY, next).apply()
        }

        fun reset() {
            update { AppearanceSettings() }
            setSurfaceOpacity(DEFAULT_SURFACE_OPACITY)
            setSurfaceSolid(false)
            setSurfaceColor(SURFACE_COLOR_AUTO)
            setFavoritesStyle(FavoritesStyle.Grouped)
            setAlbumStyle(AlbumStyle.Classic)
            setArtistStyle(ArtistStyle.Classic)
            setPlaylistStyle(PlaylistStyle.Classic)
        }

        fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
            val next = transform(_settings.value)
            _settings.value = next
            save(next)
        }

        private inline fun <reified E : Enum<E>> enum(
            key: String,
            default: E,
        ): E =
            runCatching {
                enumValueOf<E>(prefs.getString(key, null) ?: return default)
            }.getOrDefault(default)

        private fun load(): AppearanceSettings {
            val d = AppearanceSettings()
            return AppearanceSettings(
                mode = enum("mode", d.mode),
                adaptive = prefs.getBoolean("adaptive", d.adaptive),
                preset = prefs.getString("preset", d.preset) ?: d.preset,
                flowStyle = enum("flow_style", d.flowStyle),
                flowAnimated = prefs.getBoolean("flow_animated", d.flowAnimated),
                flowIntensity = enum("flow_intensity", d.flowIntensity),
                flowSpeed = enum("flow_speed", d.flowSpeed),
                freezeWhenPaused = prefs.getBoolean("freeze_paused", d.freezeWhenPaused),
                glass = prefs.getBoolean("glass", d.glass),
                glassDensity = enum("glass_density", d.glassDensity),
                playerStyle = enum("player_style", d.playerStyle),
            )
        }

        private fun save(s: AppearanceSettings) {
            prefs
                .edit()
                .putString("mode", s.mode.name)
                .putBoolean("adaptive", s.adaptive)
                .putString("preset", s.preset)
                .putString("flow_style", s.flowStyle.name)
                .putBoolean("flow_animated", s.flowAnimated)
                .putString("flow_intensity", s.flowIntensity.name)
                .putString("flow_speed", s.flowSpeed.name)
                .putBoolean("freeze_paused", s.freezeWhenPaused)
                .putBoolean("glass", s.glass)
                .putString("glass_density", s.glassDensity.name)
                .putString("player_style", s.playerStyle.name)
                .apply()
        }

        companion object {
            private const val KEY_SURFACE_OPACITY = "surface_opacity"
            private const val KEY_SURFACE_SOLID = "surface_solid"
            private const val KEY_SURFACE_COLOR = "surface_color"
            private const val KEY_FAVORITES_STYLE = "favorites_style"
            private const val KEY_ALBUM_STYLE = "album_style"
            private const val KEY_ARTIST_STYLE = "artist_style"
            private const val KEY_PLAYLIST_STYLE = "playlist_style"
            const val SURFACE_COLOR_AUTO = 0
            const val DEFAULT_SURFACE_OPACITY = 0.06f
            const val MIN_SURFACE_OPACITY = 0f
            const val MAX_SURFACE_OPACITY = 0.4f
        }
    }