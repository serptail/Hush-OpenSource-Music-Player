package com.gamut.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.core.database.DownloadIndex
import com.gamut.core.model.Playlist
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flowOn
import com.gamut.core.database.toDomain
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L
private const val SAME_SONG_DURATION_SLACK_MS = 5_000L

private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * "Is this the same song?" The ISRC is not enough on its own: the same recording reaches the app from different sources
 * (search, album page, Shazam, ...) and can carry a different or missing ISRC. So the same title + artist counts too,
 * as long as the lengths agree (this keeps two different songs called "Intro" apart, and live / radio-edit versions).
 */
internal fun sameSong(a: Track, b: Track): Boolean {
    if (a.isrc.isNotBlank() && a.isrc.trim().equals(b.isrc.trim(), ignoreCase = true)) return true
    if (norm(a.title) != norm(b.title) || norm(a.artist) != norm(b.artist)) return false
    return a.durationMs <= 0L || b.durationMs <= 0L || abs(a.durationMs - b.durationMs) <= SAME_SONG_DURATION_SLACK_MS
}

/** Keeps the first occurrence of every song, in order. */
internal fun List<Track>.withoutDuplicates(): List<Track> {
    val out = ArrayList<Track>(size)
    for (t in this) if (out.none { sameSong(it, t) }) out += t
    return out
}

/** Ids (as strings, like the pickers use) of the playlists in [byPlaylist] that already hold [track]. */
fun playlistIdsContaining(byPlaylist: Map<Long, List<Track>>, track: Track): Set<String> =
    byPlaylist.filterValues { list -> list.any { sameSong(it, track) } }.keys.mapTo(HashSet()) { it.toString() }

/** What a Library row shows for a playlist: the cover of the song added last, and how many songs it holds. */
data class PlaylistInfo(val coverUrl: String?, val songCount: Int, /** Every song is downloaded: shows the "available offline" mark. */ val offline: Boolean = false)

// One instance for the whole app (MainActivity shares it with every screen), and its flows start immediately:
// the small library tables are already loaded when the Library / Favorites screens first draw, so they never
// paint empty and then pop their content in.
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val downloadIndex: DownloadIndex,
    private val trackDao: com.gamut.core.database.TrackDao,
) : ViewModel() {
    /** Every downloaded song (title / artist saved by the download manager), A-Z. Feeds the Library's "Offline" tab. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val downloadedTracks: StateFlow<List<Track>> = downloadIndex.downloads
        .mapLatest { rows ->
            rows.keys.toList().chunked(400)
                .flatMap { trackDao.getByIsrcs(it) }
                .map { it.toDomain() }
                .sortedBy { it.title.lowercase() }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playlists: StateFlow<List<Playlist>> =
        repository.observePlaylists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every playlist's songs, by playlist id. Small tables, so eager like the rest. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tracksByPlaylist: StateFlow<Map<Long, List<Track>>> =
        playlists.flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf<Map<Long, List<Track>>>(emptyMap())
            } else {
                combine(
                    list.map { p -> repository.observeTracks(p.id).map { tracks -> p.id to tracks } },
                ) { pairs -> pairs.toMap() }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Per playlist (by id): cover = art of the LAST song added, plus the song count. */
    val playlistInfo: StateFlow<Map<Long, PlaylistInfo>> =
        tracksByPlaylist.combine(downloadIndex.downloads) { byPlaylist, saved ->
            byPlaylist.mapValues { (_, tracks) ->
                PlaylistInfo(
                    coverUrl = tracks.lastOrNull()?.albumArtUrl?.takeIf { it.isNotBlank() },
                    songCount = tracks.size,
                    offline = tracks.isNotEmpty() && tracks.all { it.isLocal || it.isrc in saved },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun createPlaylist(name: String) {
        if (name.isNotBlank()) viewModelScope.launch { repository.createPlaylist(name.trim()) }
    }

    /**
     * Create a playlist with its [tags] and an optional user-picked [coverUri], and fill it with [tracks], in order.
     * Without a [coverUri] the playlist falls back to the art of its last song.
     */
    fun createPlaylist(name: String, tracks: List<Track>, tags: List<String> = emptyList(), coverUri: String? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.createPlaylist(name.trim(), tags, coverUri)
            tracks.withoutDuplicates().forEach { repository.appendTrack(id, it) }
        }
    }

    // Serializes adds, so two quick taps (or two sources) on the same song cannot both pass the "already there?" check.
    private val addMutex = Mutex()
    private val _duplicateAdds = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emits the playlist name whenever an add was skipped because the song is already in it (MainActivity shows one toast). */
    val duplicateAdds: SharedFlow<String> = _duplicateAdds.asSharedFlow()

    /** THE entry point for "add to playlist" (search, artist, album, player, Shazam all come through here). Skips songs already in it. */
    fun addTrack(playlistId: Long, track: Track) {
        viewModelScope.launch {
            addMutex.withLock {
                val existing = repository.observeTracks(playlistId).first()
                if (existing.any { sameSong(it, track) }) {
                    _duplicateAdds.tryEmit(playlists.value.firstOrNull { it.id == playlistId }?.name ?: "this playlist")
                    return@withLock
                }
                repository.appendTrack(playlistId, track)
            }
        }
    }

    /** Undo of [addTrack]: takes the song (and any copy [sameSong] treats as the same) out of the playlist. */
    fun removeTrack(playlistId: Long, track: Track) {
        viewModelScope.launch {
            addMutex.withLock {
                repository.observeTracks(playlistId).first()
                    .filter { sameSong(it, track) }
                    .forEach { repository.removeTrack(playlistId, it.isrc) }
            }
        }
    }

    // One-time repair of duplicates that are already saved: every playlist, first copy kept, only rewritten if something changed.
    init {
        viewModelScope.launch {
            addMutex.withLock {
                repository.observePlaylists().first().forEach { p ->
                    val current = repository.observeTracks(p.id).first()
                    val clean = current.withoutDuplicates()
                    if (clean.size != current.size) repository.replaceTracks(p.id, clean)
                }
            }
        }
    }

    fun createPlaylistAndAdd(name: String, track: Track) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.appendTrack(repository.createPlaylist(name.trim()), track) }
    }

    val favoriteIsrcs: StateFlow<Set<String>> =
        repository.observeFavoriteIsrcs().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Just the count, derived from the ISRC flow above (one Room query instead of two). */
    val favoriteCount: StateFlow<Int> =
        favoriteIsrcs.map { it.size }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Full favorites (for the Favorites screen only). Only runs while something collects it. */
    val favorites: StateFlow<List<Track>> =
        repository.observeFavorites().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every liked song is downloaded (the "Liked songs" row shows the offline mark). */
    val likedOffline: StateFlow<Boolean> =
        combine(favorites, downloadIndex.downloads) { liked, saved -> liked.isNotEmpty() && liked.all { it.isLocal || it.isrc in saved } }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Saves a new favorites order (drag to reorder) or restores a removed favorite (undo). */
    fun setFavoriteOrder(tracks: List<Track>) {
        viewModelScope.launch { repository.setFavoriteOrder(tracks) }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { repository.toggleFavorite(track) }
    }
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
) : ViewModel() {
    private val rawId = savedStateHandle.get<String>("id")
    private val playlistId: Long? = rawId?.toLongOrNull()

    /** This playlist's own data (name, tags, custom cover). Null for the "liked" pseudo-playlist. */
    val playlist: StateFlow<Playlist?> =
        repository.observePlaylists().map { list -> list.firstOrNull { it.id == playlistId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    // "liked" is the Liked songs pseudo-playlist; it used to parse to -1 and show an empty playlist.
    val tracks: StateFlow<List<Track>> =
        (if (rawId == "liked") repository.observeFavorites() else repository.observeTracks(rawId?.toLongOrNull() ?: -1L))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Move the song at [from] to position [to] (the list is rewritten in its new order). */
    fun move(from: Int, to: Int) {
        val id = playlistId ?: return
        val list = tracks.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        viewModelScope.launch { repository.replaceTracks(id, list) }
    }

    /** Saves a new order (manage sheet: drag to reorder, or Undo after a removal). */
    fun setTracks(newTracks: List<Track>) {
        val id = playlistId ?: return
        viewModelScope.launch { repository.replaceTracks(id, newTracks) }
    }

    /** Takes one song out of the playlist (manage sheet). */
    fun removeTrack(isrc: String) {
        val id = playlistId ?: return
        viewModelScope.launch { repository.removeTrack(id, isrc) }
    }

    fun removeAt(index: Int) {
        val id = playlistId ?: return
        val list = tracks.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        viewModelScope.launch { repository.replaceTracks(id, list) }
    }

    /** Deletes this playlist; [onDone] runs once it is gone (navigate back there, not before). */
    fun delete(onDone: () -> Unit) {
        val id = playlistId ?: return
        viewModelScope.launch {
            repository.deletePlaylist(id)
            onDone()
        }
    }

    /** Edit mode "Done": name, tags and cover always; [newTracks] only when the list changed. */
    fun save(name: String, tags: List<String>, coverUri: String?, newTracks: List<Track>?) {
        val id = playlistId ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.updatePlaylist(id, name.trim(), tags, coverUri)
            if (newTracks != null) repository.replaceTracks(id, newTracks)
        }
    }
}