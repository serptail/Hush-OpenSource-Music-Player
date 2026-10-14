package com.gamut.core.sync

import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import com.gamut.feature.library.LibraryRepository
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class ExportTrack(
    val id: String,                 // "<provider>:<provider_song_id>", e.g. "deezer:987654"
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val artworkUrl: String? = null,
)
@Serializable data class ExportFavorite(val track: ExportTrack, val addedAt: Long)
@Serializable data class ExportFollow(val id: String, val name: String, val artworkUrl: String? = null, val followedAt: Long)
@Serializable data class ExportPlaylist(val name: String, val tags: List<String> = emptyList(), val tracks: List<ExportTrack> = emptyList())
@Serializable
data class ExportDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val favorites: List<ExportFavorite>,
    val playlists: List<ExportPlaylist>,
    val follows: List<ExportFollow> = emptyList(),      // added in schema 2; v1 backups simply have none
    val settings: JsonObject = JsonObject(emptyMap()),   // reserved
) { companion object { const val SCHEMA_VERSION = 2 } }

@Serializable private data class Envelope(val enc: Int, val salt: String, val iv: String, val data: String)

data class ImportResult(val favorites: Int, val playlists: Int, val follows: Int = 0)

/** JSON backup the user keeps anywhere (system file picker). Import is merge-safe, validated and size-capped. */
class DataExporter @Inject constructor(
    private val library: LibraryRepository,
    private val followRepository: FollowRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(password: CharArray?): ByteArray {
        val favs = library.observeFavorites().first()
        val doc = ExportDocument(
            exportedAt = System.currentTimeMillis(),
            favorites = favs.mapIndexed { i, t -> ExportFavorite(t.toExport(), System.currentTimeMillis() - i) },
            playlists = library.observePlaylists().first().map { p ->
                ExportPlaylist(p.name, p.tags, library.observeTracks(p.id).first().map { it.toExport() })
            },
            follows = followRepository.observeAll().first().map { ExportFollow(it.artistKey, it.name, it.imageUrl, it.followedAtEpochMs) },
        )
        val plain = json.encodeToString(ExportDocument.serializer(), doc).toByteArray()
        return if (password == null || password.isEmpty()) plain else encrypt(plain, password)
    }

    suspend fun import(bytes: ByteArray, password: CharArray?): ImportResult {
        require(bytes.size <= MAX_BYTES) { "File is too large" }
        var root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        if (root.containsKey("enc")) {
            require(password != null && password.isNotEmpty()) { "This backup is encrypted: enter its password" }
            root = json.parseToJsonElement(decrypt(json.decodeFromJsonElement(Envelope.serializer(), root), password).decodeToString()).jsonObject
        }
        val doc = json.decodeFromJsonElement(ExportDocument.serializer(), root)
        require(doc.schemaVersion in 1..ExportDocument.SCHEMA_VERSION) { "Unsupported backup version ${doc.schemaVersion}" }
        require(doc.favorites.size <= 20_000 && doc.playlists.size <= 500) { "Too many items" }
        require(doc.playlists.all { it.tracks.size <= 5_000 }) { "A playlist has too many songs" }
        require(doc.follows.size <= 5_000) { "Too many followed artists" }

        var favs = 0
        doc.favorites.forEach { f ->
            f.track.toTrackOrNull()?.let { library.addFavorite(it, f.addedAt); favs++ }
        }
        val existing = library.observePlaylists().first()
        var pls = 0
        doc.playlists.forEach { p ->
            val name = p.name.trim().take(100).ifBlank { return@forEach }
            val id = existing.firstOrNull { it.name == name }?.id ?: library.createPlaylist(name, p.tags.take(20))
            p.tracks.forEach { t -> t.toTrackOrNull()?.let { library.appendTrack(id, it) } }   // dedupes by id
            pls++
        }

        var fol = 0
        doc.follows.forEach { f ->
            if (parseArtistKey(f.id) == null) return@forEach
            val name = f.name.trim().take(200).ifBlank { return@forEach }
            val art = f.artworkUrl?.takeIf { it.startsWith("https://") }?.take(500)
            if (followRepository.importFollow(f.id, name, art, f.followedAt)) fol++
        }
        return ImportResult(favs, pls, fol)
    }

    /** Reads at most [MAX_BYTES] so a huge or malicious file cannot exhaust memory. */
    fun readLimited(stream: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            require(out.size() <= MAX_BYTES) { "File is too large" }
        }
        return out.toByteArray()
    }

    private fun Track.toExport() = ExportTrack(isrc, title, artist, album, durationMs, albumArtUrl)

    private fun ExportTrack.toTrackOrNull(): Track? {
        if (parseIsrc(id) == null) return null
        val yt = id.startsWith("youtube:")
        return Track(
            isrc = id, title = title.take(300), artist = artist.take(200), album = album?.take(300), durationMs = durationMs.coerceAtLeast(0),
            albumArtUrl = artworkUrl?.takeIf { it.startsWith("https://") }?.take(500),
            source = if (yt) TrackSource.YOUTUBE else TrackSource.ITUNES,
            youtubeVideoId = if (yt) id.removePrefix("youtube:") else null,
        )
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, 210_000, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        val b64 = Base64.getEncoder()
        val env = Envelope(1, b64.encodeToString(salt), b64.encodeToString(iv), b64.encodeToString(cipher.doFinal(plain)))
        return json.encodeToString(Envelope.serializer(), env).toByteArray()
    }

    private fun decrypt(env: Envelope, password: CharArray): ByteArray {
        val b64 = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(password, b64.decode(env.salt)), GCMParameterSpec(128, b64.decode(env.iv))) }
        return try { cipher.doFinal(b64.decode(env.data)) } catch (e: Exception) { throw IllegalArgumentException("Wrong password or damaged file") }
    }

    private companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}