package com.gamut.feature.resolver.lossless

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sources the listener added (addon servers, module indexes, JioSaavn), in the order they are tried,
 * and whether to prefer lossless streams.
 *
 * Gamut ships no addon, no module index and no module: the listener supplies their own.
 */
@Singleton
class LosslessSettings
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        data class State(
            val preferLossless: Boolean = false,
            val sources: List<SourceConfig> = emptyList(),
        )

        private val prefs = context.applicationContext.getSharedPreferences("lossless_sources", Context.MODE_PRIVATE)
        private val _state = MutableStateFlow(load())
        val state: StateFlow<State> = _state.asStateFlow()

        fun setPreferLossless(on: Boolean) = edit { it.copy(preferLossless = on) }

        /** Adds an addon server. [url] may be the addon's base or its `manifest.json`. */
        fun addAddon(
            url: String,
            label: String = "",
        ): Boolean = add(SourceKind.ADDON, url, label)

        /** Adds a module-index URL. */
        fun addModuleIndex(
            url: String,
            label: String = "",
        ): Boolean = add(SourceKind.MODULE, url, label)

        /** Kept for callers that predate addons: a bare URL is treated as an addon. */
        fun addSource(
            baseUrl: String,
            label: String = "",
        ): Boolean = addAddon(baseUrl, label)

        fun setJioSaavn(on: Boolean) =
            edit { s ->
                val without = s.sources.filterNot { it.kind == SourceKind.JIOSAAVN }
                s.copy(sources = if (on) without + SourceConfig(JIOSAAVN_ID, kind = SourceKind.JIOSAAVN) else without)
            }

        /** Moves a source one place earlier (-1) or later (+1). Sources are tried in this order. */
        fun move(
            id: String,
            by: Int,
        ) = edit { s ->
            val list = s.sources.toMutableList()
            val from = list.indexOfFirst { it.id == id }
            val to = from + by
            if (from < 0 || to !in list.indices) return@edit s
            list.add(to, list.removeAt(from))
            s.copy(sources = list)
        }

        private fun add(
            kind: SourceKind,
            raw: String,
            label: String,
        ): Boolean {
            val url = raw.trim().removeSuffix("/manifest.json").trimEnd('/')
            if (!url.startsWith("https://") && !url.startsWith("http://")) return false
            if (_state.value.sources.any { it.baseUrl == url }) return false
            edit { it.copy(sources = it.sources + SourceConfig(UUID.randomUUID().toString(), label.trim(), url, kind)) }
            return true
        }

        fun removeSource(id: String) = edit { s -> s.copy(sources = s.sources.filterNot { it.id == id }) }

        @Synchronized
        private fun edit(change: (State) -> State) {
            val next = change(_state.value)
            if (next == _state.value) return
            _state.value = next
            prefs
                .edit()
                .putBoolean(K_PREFER, next.preferLossless)
                .putString(
                    K_SOURCES,
                    JSONArray(
                        next.sources.map {
                            JSONObject().put("id", it.id).put("label", it.label).put("url", it.baseUrl).put("kind", it.kind.name)
                        },
                    ).toString(),
                ).apply()
        }

        private fun load(): State {
            val sources =
                runCatching {
                    val arr = JSONArray(prefs.getString(K_SOURCES, "[]"))
                    (0 until arr.length()).mapNotNull {
                        val o = arr.getJSONObject(it)
                        val kind = runCatching { SourceKind.valueOf(o.optString("kind")) }.getOrDefault(SourceKind.MODULE)
                        val url = o.optString("url")
                        if (url.isBlank() && kind.needsUrl) {
                            null
                        } else {
                            SourceConfig(o.optString("id").ifBlank { UUID.randomUUID().toString() }, o.optString("label"), url, kind)
                        }
                    }
                }.getOrDefault(emptyList())
            return State(prefs.getBoolean(K_PREFER, false), sources)
        }

        private companion object {
            const val JIOSAAVN_ID = "jiosaavn"
            const val K_PREFER = "prefer_lossless"
            const val K_SOURCES = "sources"
        }
    }
