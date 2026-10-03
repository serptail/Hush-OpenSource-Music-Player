package com.gamut.feature.search

import com.gamut.core.common.TextMatch
import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import com.gamut.core.network.AppleLookupItem
import com.gamut.core.network.DeezerApi
import com.gamut.core.network.DeezerPhotoArtist
import com.gamut.core.network.ItunesApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Keyless Apple (iTunes) catalogue: artists, full discographies, all songs. */
@Singleton
class AppleCatalogRepository @Inject constructor(
    private val itunes: ItunesApi,
    private val deezer: DeezerApi,
) {
    private val cache = mutableMapOf<Long, ArtistDetails>()
    /** lookup key -> verified Deezer photo url ("" = checked, none found). */
    private val photoCache = ConcurrentHashMap<String, String>()

    suspend fun findArtistId(name: String): Long? = withTimeoutOrNull(5_000) {
        val wanted = TextMatch.primaryArtist(name)
        val hits = runCatching { itunes.searchArtists(name).results }.getOrDefault(emptyList())
            .filter { it.artistId != null }
        (hits.firstOrNull { TextMatch.norm(it.artistName.orEmpty()) == wanted } ?: hits.firstOrNull())?.artistId
    }

    /** Top artist match for a search query: the name must equal or start with what was typed (no random namesakes). */
    suspend fun searchArtistCard(query: String): DiscoverItem? = withTimeoutOrNull(4_000) {
        val q = TextMatch.norm(query)
        if (q.length < 2) return@withTimeoutOrNull null
        val hit = runCatching { itunes.searchArtists(query, limit = 5).results }
            .getOrDefault(emptyList())
            .firstOrNull {
                it.artistId != null &&
                    TextMatch.norm(it.artistName.orEmpty()).let { n -> n == q || n.startsWith(q) }
            } ?: return@withTimeoutOrNull null
        artistCard(hit.artistId!!, hit.artistName.orEmpty())
    }

    /** Circle card for Discover: a verified Deezer photo, falling back to their newest album cover. */
    suspend fun artistCard(id: Long, fallbackName: String): DiscoverItem? {
        val rows = runCatching { itunes.lookupAll(id.toString(), "album", 5).results }.getOrNull() ?: return null
        val albums = rows.filter { it.wrapperType == "collection" }
        val name = rows.firstOrNull { it.wrapperType == "artist" }?.artistName ?: fallbackName
        val photo = artistPhoto(id, name, albums.mapNotNull { it.collectionName })
        val art = photo ?: albums.firstOrNull()?.artworkUrl100.big()
        return DiscoverItem(id = "artist:$id", title = name, subtitle = "Artist", imageUrl = art, artistId = id)
    }

    /**
     * A real artist photo from Deezer, or null. Never guesses between namesakes:
     *  1. the Deezer name must equal the Apple name (normalised) -- no prefix/fuzzy matches;
     *  2. the Deezer artist's discography must share at least one album title with the Apple artist;
     *  3. only the 3 most-followed exact-name candidates are checked.
     * With no known Apple albums to compare, a photo is used only if exactly one Deezer artist has that name.
     * Anything unverified returns null so the caller falls back to album art instead of showing the wrong person.
     */
    suspend fun artistPhoto(appleId: Long, name: String, appleAlbumTitles: List<String>): String? =
        cachedPhoto("id:$appleId", name, appleAlbumTitles)

    /** Same verification, for screens that only know the artist by name plus one of their albums. */
    suspend fun artistPhotoFor(name: String, albumTitle: String): String? =
        cachedPhoto("n:${TextMatch.norm(name)}|${TextMatch.norm(albumTitle)}", name, listOf(albumTitle))

    private suspend fun cachedPhoto(key: String, name: String, appleAlbumTitles: List<String>): String? {
        photoCache[key]?.let { return it.ifEmpty { null } }
        if (name.isBlank()) return null
        val outcome = withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
            runCatching { findDeezerPhoto(name, appleAlbumTitles) }
                .onFailure { if (it is CancellationException) throw it }
        } ?: return null
        outcome.onSuccess { photoCache[key] = it.orEmpty() } // cache definitive answers only, not failures
        return outcome.getOrNull()
    }

    private suspend fun findDeezerPhoto(name: String, appleAlbumTitles: List<String>): String? {
        val wanted = TextMatch.norm(name)
        val candidates = deezer.searchArtistPhotos(name).data
            .filter { TextMatch.norm(it.name) == wanted }
            .sortedByDescending { it.nbFan }
        val known = appleAlbumTitles.map { TextMatch.norm(it) }.filter { it.isNotBlank() }
        if (known.isEmpty()) return candidates.singleOrNull()?.photo()
        for (candidate in candidates.take(3)) {
            val theirs = deezer.artistAlbumTitles(candidate.id).data.map { TextMatch.norm(it.title) }
            if (theirs.any { t -> t.isNotBlank() && known.any { k -> sameAlbum(t, k) } }) return candidate.photo()
        }
        return null
    }

    /** Equal, or one is the other plus an edition suffix ("Habibti" vs "Habibti (FOMO)"). */
    private fun sameAlbum(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)))

    /** Deezer serves a grey silhouette for photo-less artists; its url has an empty hash (".../artist//..."). */
    private fun DeezerPhotoArtist.photo(): String? =
        (pictureXl ?: pictureBig)?.takeIf { it.isNotBlank() && !it.contains("/artist//") }

    suspend fun artist(id: Long): ArtistDetails? {
        cache[id]?.let { return it }
        return withTimeoutOrNull(40_000) {
            runCatching {
                coroutineScope {
                    val topJob = async { itunes.lookupAll(id.toString(), "song", 50).results }
                    val rows = itunes.lookupAll(id.toString(), "album", 200).results
                    val name = rows.firstOrNull { it.wrapperType == "artist" }?.artistName.orEmpty()
                    val albums = rows.filter { it.wrapperType == "collection" && it.collectionId != null }
                        .distinctBy { TextMatch.norm(it.collectionName.orEmpty()) }
                        .sortedByDescending { it.releaseDate.orEmpty() }

                    val photoJob = async {
                        artistPhoto(
                            appleId = id,
                            name = name.ifBlank { topJob.await().firstOrNull()?.artistName.orEmpty() },
                            appleAlbumTitles = albums.mapNotNull { it.collectionName }.take(40),
                        )
                    }

                    val gate = Semaphore(2)
                    val all = albums.chunked(4).map { batch ->
                        async {
                            gate.withPermit {
                                runCatching {
                                    itunes.lookupAll(batch.joinToString(",") { it.collectionId.toString() }, "song", 200).results
                                }.getOrDefault(emptyList())
                            }
                        }
                    }.awaitAll().flatten()

                    val top = topJob.await().filter { it.kind == "song" }
                    val rest = all.filter { it.kind == "song" && it.artistId == id }
                        .sortedByDescending { it.releaseDate.orEmpty() }
                    val songs = (top + rest).distinctBy { TextMatch.norm(it.trackName.orEmpty()) }
                    val latest = rest.distinctBy { TextMatch.norm(it.trackName.orEmpty()) }.take(30)

                    ArtistDetails(
                        id = id,
                        name = name.ifBlank { top.firstOrNull()?.artistName.orEmpty() },
                        imageUrl = photoJob.await() ?: (top.firstOrNull()?.artworkUrl100 ?: albums.firstOrNull()?.artworkUrl100).big(),
                        popular = top.take(10).map { it.toTrack() },
                        songs = songs.map { it.toTrack() },
                        latestSongs = latest.map { it.toTrack() },
                        albums = albums.map {
                            DiscoverItem(
                                id = "apple-album:${it.collectionId}",
                                title = it.collectionName.orEmpty(),
                                subtitle = it.releaseDate.orEmpty().take(4),
                                imageUrl = it.artworkUrl100.big(),
                            )
                        },
                    )
                }
            }.getOrNull()
        }?.also { cache[id] = it }
    }

    private fun AppleLookupItem.toTrack() = Track(
        isrc = "apple:$trackId",
        title = trackName.orEmpty(),
        artist = artistName.orEmpty(),
        album = collectionName,
        durationMs = trackTimeMillis ?: 0L,
        albumArtUrl = artworkUrl100.big(),
        source = TrackSource.ITUNES,
    )
}

private const val PHOTO_TIMEOUT_MS = 6_000L

internal fun String?.big(): String = this.orEmpty().replace(Regex("/\\d+x\\d+bb\\."), "/600x600bb.")