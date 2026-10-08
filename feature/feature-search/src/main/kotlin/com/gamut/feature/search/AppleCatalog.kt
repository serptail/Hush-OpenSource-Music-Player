package com.gamut.feature.search

import com.gamut.core.common.TextMatch
import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import com.gamut.core.network.AppleLookupItem
import com.gamut.core.network.DeezerApi
import com.gamut.core.network.DeezerArtistDto
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
    /** Full artists (with every song). Concurrent: the overview and the song load run on different coroutines. */
    private val cache = ConcurrentHashMap<Long, ArtistDetails>()
    /** Overview-only artists (top songs + album list), so reopening a page is instant even before songs finish. */
    private val overviewCache = ConcurrentHashMap<Long, ArtistDetails>()
    /** Normalised artist name -> verified photo url, so the album screen reuses what Discover already verified. */
    private val photoByName = ConcurrentHashMap<String, String>()
    /** lookup key -> verified Deezer photo url ("" = checked, none found). */
    private val photoCache = ConcurrentHashMap<String, String>()
    /** Normalised artist name -> fan count of the verified Deezer artist; feeds the verified badge. */
    private val fansByName = ConcurrentHashMap<String, Long>()
    /** cache key -> number of album titles a "no photo" answer was checked against (a negative is only final for that much evidence). */
    private val noPhotoChecked = ConcurrentHashMap<String, Int>()
    /** Apple artist id -> related artists, so the Similar artists row is not fetched twice. */
    private val similarCache = ConcurrentHashMap<Long, List<DiscoverItem>>()

    suspend fun findArtistId(name: String): Long? = withTimeoutOrNull(5_000) {
        val wanted = TextMatch.primaryArtist(name)
        val hits = runCatching { itunes.searchArtists(name).results }.getOrDefault(emptyList())
            .filter { it.artistId != null }
        (hits.firstOrNull { TextMatch.norm(it.artistName.orEmpty()) == wanted } ?: hits.firstOrNull())?.artistId
    }

    /**
     * Top artist match for a search query. Uses the shared [SearchRanking.nameMatch] rule (exact name, or the last word
     * still being typed on a 3+ character query) instead of a bare startsWith, which let "qm" match anything starting
     * with "qm". Exact matches beat prefix matches; Apple's own order breaks ties. Whether the match is well known
     * enough to deserve the card is decided afterwards by [SearchRanking.showArtistCard] (it needs the Deezer fan count
     * and the song results, which this repository does not have).
     */
    suspend fun searchArtistCard(query: String): DiscoverItem? = withTimeoutOrNull(4_000) {
        if (TextMatch.norm(query).length < 2) return@withTimeoutOrNull null
        val hit = runCatching { itunes.searchArtists(query, limit = 5).results }
            .getOrDefault(emptyList())
            .filter { it.artistId != null }
            .map { it to SearchRanking.nameMatch(query, it.artistName.orEmpty()) }
            .filter { (_, match) -> match > 0.0 }
            .maxByOrNull { (_, match) -> match }
            ?.first ?: return@withTimeoutOrNull null
        artistCard(hit.artistId!!, hit.artistName.orEmpty())
    }

    /** Circle card for Discover: a verified Deezer photo, falling back to their newest album cover. */
    suspend fun artistCard(id: Long, fallbackName: String): DiscoverItem? {
        val rows = runCatching { itunes.lookupAll(id.toString(), "album", 5).results }.getOrNull() ?: return null
        val albums = rows.filter { it.wrapperType == "collection" }
        val name = rows.firstOrNull { it.wrapperType == "artist" }?.artistName ?: fallbackName
        val photo = artistPhoto(id, name, albums.mapNotNull { it.collectionName })
        val art = photo ?: albums.firstOrNull()?.artworkUrl100.big()
        // The fan count was recorded while the photo was being verified (same Deezer artist), so it costs no extra call.
        return DiscoverItem(
            id = "artist:$id", title = name, subtitle = "Artist", imageUrl = art, artistId = id,
            followers = fansByName[TextMatch.norm(name)],
        )
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
        photoByName[TextMatch.norm(name)] ?: cachedPhoto("id:$appleId", name, appleAlbumTitles)

    /** Same verification, for screens that only know the artist by name plus one of their albums. */
    suspend fun artistPhotoFor(name: String, albumTitle: String): String? {
        photoByName[TextMatch.norm(name)]?.let { return it }
        return cachedPhoto("n:${TextMatch.norm(name)}|${TextMatch.norm(albumTitle)}", name, listOf(albumTitle))
    }

    private suspend fun cachedPhoto(key: String, name: String, appleAlbumTitles: List<String>): String? {
        photoCache[key]?.let { return it }
        if (name.isBlank()) return null
        val titles = appleAlbumTitles.count { it.isNotBlank() }
        // Already checked with the same or more evidence: don't ask Deezer again.
        if ((noPhotoChecked[key] ?: -1) >= titles) return null
        val outcome = withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
            runCatching { findDeezerPhoto(name, appleAlbumTitles) }
                .onFailure { if (it is CancellationException) throw it }
        } ?: return null // timeout: not cached, so a retry works
        outcome.onSuccess { url ->
            if (url != null) {
                photoCache[key] = url
                photoByName[TextMatch.norm(name)] = url
                noPhotoChecked.remove(key)
            } else {
                // A "none found" only counts for the evidence it had; a later check with more albums may still succeed.
                noPhotoChecked[key] = maxOf(noPhotoChecked[key] ?: 0, titles)
            }
        }
        return outcome.getOrNull()
    }

    private suspend fun findDeezerPhoto(name: String, appleAlbumTitles: List<String>): String? {
        val artist = verifiedDeezerArtist(name, appleAlbumTitles) ?: return null
        val fans: Long? = artist.nbFan
        if (fans != null) fansByName[TextMatch.norm(name)] = fans
        return artist.photo()
    }

    /** The Deezer artist that is verifiably the same person as the Apple artist (see [artistPhoto] for the rules). */
    private suspend fun verifiedDeezerArtist(name: String, appleAlbumTitles: List<String>): DeezerPhotoArtist? {
        val wanted = TextMatch.norm(name)
        val candidates = deezer.searchArtistPhotos(name).data
            .filter { TextMatch.norm(it.name) == wanted }
            .sortedByDescending { it.nbFan }
        val known = appleAlbumTitles.map { TextMatch.norm(it) }.filter { it.isNotBlank() }
        if (known.isEmpty()) return candidates.singleOrNull()
        for (candidate in candidates.take(3)) {
            val theirs = deezer.artistAlbumTitles(candidate.id).data.map { TextMatch.norm(it.title) }
            if (theirs.any { t -> t.isNotBlank() && known.any { k -> sameAlbum(t, k) } }) return candidate
        }
        // Album lists can be paged/incomplete on Deezer's side. An exact-name candidate with 5x the fans of the
        // runner-up (or the only one) is the same artist in practice.
        val top = candidates.firstOrNull() ?: return null
        val second = candidates.getOrNull(1)
        return top.takeIf { second == null || (top.nbFan ?: 0L) >= (second.nbFan ?: 0L) * 5 }
    }

    /** Equal, or one is the other plus an edition suffix ("Habibti" vs "Habibti (FOMO)"). */
    private fun sameAlbum(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)))

    /** Deezer serves a grey silhouette for photo-less artists; its url has an empty hash (".../artist//..."). */
    private fun DeezerPhotoArtist.photo(): String? =
        (pictureXl ?: pictureBig)?.takeIf { it.isNotBlank() && !it.contains("/artist//") }

    /**
     * Artists related to [base]: Deezer's "related" list for the verified Deezer artist. Because it starts from the
     * verified artist, the names are right; tapping one opens the page by name (the Apple lookup happens then, not for
     * every card up front, which keeps iTunes' rate limit untouched). Photo-less (silhouette) artists are dropped.
     */
    suspend fun similarArtists(base: ArtistDetails): List<DiscoverItem> {
        similarCache[base.id]?.let { return it }
        val found = withTimeoutOrNull(SIMILAR_TIMEOUT_MS) {
            try {
                loadSimilar(base)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }.orEmpty()
        if (found.isNotEmpty()) similarCache[base.id] = found // never cache a failure
        return found
    }

    private suspend fun loadSimilar(base: ArtistDetails): List<DiscoverItem> {
        val self = verifiedDeezerArtist(base.name, base.albums.map { it.title }.take(40)) ?: return emptyList()
        val ownName = TextMatch.norm(base.name)
        return deezer.relatedArtists(self.id).data
            .filter { it.id != self.id && TextMatch.norm(it.name) != ownName }
            .mapNotNull { related ->
                val photo = related.photo() ?: return@mapNotNull null
                DiscoverItem(
                    id = "similar:${related.id}", title = related.name, subtitle = "Artist", imageUrl = photo,
                    followers = related.fanCount,
                )
            }
            .take(SIMILAR_LIMIT)
    }

    private fun DeezerArtistDto.photo(): String? =
        (pictureXl ?: pictureBig)?.takeIf { it.isNotBlank() && !it.contains("/artist//") }

    /** Swaps the album-cover fallback for the verified portrait when one is already known. */
    private fun ArtistDetails.withKnownPhoto(): ArtistDetails =
        photoByName[TextMatch.norm(name)]?.let { copy(imageUrl = it) } ?: this

    /**
     * Fast path for the artist page: top songs + album list, two calls that run in parallel. The portrait and the
     * full discography are loaded afterwards by [artistPhoto] and [artistSongs] so the page never waits for them.
     */
    suspend fun artistOverview(id: Long): ArtistDetails? {
        (cache[id] ?: overviewCache[id])?.let { return it.withKnownPhoto() }
        return withTimeoutOrNull(10_000) {
            runCatching {
                coroutineScope {
                    val topJob = async { itunes.lookupAll(id.toString(), "song", 50).results }
                    val rows = itunes.lookupAll(id.toString(), "album", 200).results
                    val albums = rows.filter { it.wrapperType == "collection" && it.collectionId != null }
                        .distinctBy { TextMatch.norm(it.collectionName.orEmpty()) }
                        .sortedByDescending { it.releaseDate.orEmpty() }
                    val top = topJob.await().filter { it.kind == "song" }
                    ArtistDetails(
                        id = id,
                        name = rows.firstOrNull { it.wrapperType == "artist" }?.artistName
                            ?: top.firstOrNull()?.artistName.orEmpty(),
                        imageUrl = (top.firstOrNull()?.artworkUrl100 ?: albums.firstOrNull()?.artworkUrl100).big(),
                        popular = top.take(10).map { it.toTrack() },
                        songs = top.map { it.toTrack() },
                        albums = albums.map {
                            DiscoverItem(
                                id = "apple-album:${it.collectionId}",
                                title = it.collectionName.orEmpty(),
                                subtitle = it.releaseDate.orEmpty().take(4),
                                imageUrl = it.artworkUrl100.big(),
                            )
                        },
                        songsLoaded = false,
                    )
                }
            }.getOrNull()
        }?.withKnownPhoto()?.also { overviewCache[id] = it }
    }

    /**
     * The slow part: every song of every album. Albums are newest-first, so the newest songs come from the first
     * batches. 6 albums per call (was 4) means fewer requests, which matters more than parallelism because iTunes
     * rate-limits aggressively.
     */
    suspend fun artistSongs(base: ArtistDetails): ArtistDetails {
        cache[base.id]?.let { return it }
        val albumIds = base.albums.mapNotNull { it.id.removePrefix("apple-album:").toLongOrNull() }
        val gate = Semaphore(2)
        val all = withTimeoutOrNull(40_000) {
            coroutineScope {
                albumIds.chunked(6).map { batch ->
                    async {
                        gate.withPermit {
                            runCatching {
                                itunes.lookupAll(batch.joinToString(","), "song", 200).results
                            }.getOrDefault(emptyList())
                        }
                    }
                }.awaitAll().flatten()
            }
        }.orEmpty()
        val rest = all.filter { it.kind == "song" && it.artistId == base.id }
            .sortedByDescending { it.releaseDate.orEmpty() }
        val restTracks = rest.map { it.toTrack() }
        val full = base.copy(
            songs = (base.songs + restTracks).distinctBy { TextMatch.norm(it.title) },
            latestSongs = restTracks.distinctBy { TextMatch.norm(it.title) }.take(30),
            songsLoaded = true,
        )
        if (all.isNotEmpty()) cache[base.id] = full // never cache a total failure
        return full
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
private const val SIMILAR_TIMEOUT_MS = 8_000L
private const val SIMILAR_LIMIT = 12

internal fun String?.big(): String = this.orEmpty().replace(Regex("/\\d+x\\d+bb\\."), "/600x600bb.")