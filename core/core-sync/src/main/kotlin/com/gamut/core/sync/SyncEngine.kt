package com.gamut.core.sync

import com.gamut.core.database.FavoriteDao
import com.gamut.core.database.FavoriteEntity
import com.gamut.core.database.FollowDao
import com.gamut.core.database.FollowEntity
import com.gamut.core.database.PendingSyncDao
import com.gamut.core.database.PendingSyncEntity
import com.gamut.core.database.PlaylistDao
import com.gamut.core.database.PlaylistEntity
import com.gamut.core.database.PlaylistTrackCrossRef
import com.gamut.core.database.PlaylistTrackDao
import com.gamut.core.database.SyncStateDao
import com.gamut.core.database.SyncStateEntity
import com.gamut.core.database.SyncTypes
import com.gamut.core.database.TrackDao
import com.gamut.core.database.toEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.call.body
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sync order: push the pending queue, then pull everything changed since the per-table cursor.
 * Cursors are SERVER updated_at values (never the device clock). Conflict rules: last-write-wins for favorites and
 * playlist meta (a locally pending change is never overwritten by a pull); playlist songs merge row by row.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val supabase: SupabaseClient,
    private val pending: PendingSyncDao,
    private val state: SyncStateDao,
    private val trackDao: TrackDao,
    private val favorites: FavoriteDao,
    private val playlists: PlaylistDao,
    private val playlistTracks: PlaylistTrackDao,
    private val follows: FollowDao,
) {
    /** isrc -> server song uuid, so a song is resolved at most once per process. */
    private val songIds = ConcurrentHashMap<String, String>()

    suspend fun sync() {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return
        claimOwner(uid)
        push(uid)
        val now = System.currentTimeMillis()
        val last = state.get(LAST_SYNC)?.toLongOrNull()
        // Offline longer than the tombstone window: deletions may have been purged, so do a full resync.
        if (last != null && now - last > TOMBSTONE_WINDOW_MS) resetCursors()
        pull()
        state.put(SyncStateEntity(LAST_SYNC, now.toString()))
    }

    // ---------------------------------------------------------------- first sign-in / account switch

    /** First time this account signs in on this device (guest data -> account): queue the whole local library. */
    private suspend fun claimOwner(uid: String) {
        if (state.get(OWNER) == uid) return
        resetCursors()
        val now = System.currentTimeMillis()
        suspend fun mark(type: String, key: String, payload: String? = null) =
            pending.put(PendingSyncEntity(type = type, itemKey = key, payload = payload, createdAtEpochMs = now))
        playlists.getAll().forEach { p ->
            mark(SyncTypes.PL, p.id.toString())
            playlistTracks.isrcsFor(p.id).forEachIndexed { i, isrc -> mark(SyncTypes.PLS, "${p.id}|$isrc", i.toString()) }
        }
        favorites.all().forEach { mark(SyncTypes.FAV, it.isrc) }
        follows.all().forEach { mark(SyncTypes.FOLLOW, it.artistKey) }
        state.put(SyncStateEntity(OWNER, uid))
    }

    private suspend fun resetCursors() = CURSORS.forEach { state.put(SyncStateEntity(it, EPOCH)) }

    // ---------------------------------------------------------------- push

    private suspend fun push(uid: String) {
        val entries = pending.all()
        if (entries.isEmpty()) return
        val byType = entries.groupBy { it.type }
        val done = ArrayList<Long>()
        val now = Instant.now().toString()

        // 1. playlist deletes (soft delete = tombstone)
        byType[SyncTypes.PL_DEL].orEmpty().chunked(100).forEach { chunk ->
            val ids = chunk.map { it.itemKey }
            supabase.from("playlists").update({ set("deleted_at", now) }) { filter { isIn("id", ids) } }
            done += chunk.map { it.id }
        }

        // 2. playlist meta (assigns remoteId on first push; the upsert is idempotent, so a retry is safe)
        val playlistRows = ArrayList<PlaylistRow>()
        for (e in byType[SyncTypes.PL].orEmpty()) {
            done += e.id
            val local = playlists.get(e.itemKey.toLongOrNull() ?: continue) ?: continue
            val remote = local.remoteId ?: UUID.randomUUID().toString().also { playlists.setRemoteId(local.id, it) }
            playlistRows += PlaylistRow(
                remote, uid, local.name.trim().take(100).ifBlank { "Untitled" }, local.tags.take(500),
                Instant.ofEpochMilli(local.createdAtEpochMs).toString(),
            )
        }
        playlistRows.chunked(100).forEach { supabase.from("playlists").upsert(it) { onConflict = "id" } }

        // 3. songs must exist server-side before favorites / playlist_songs can reference them
        val favEntries = byType[SyncTypes.FAV].orEmpty()
        val plsEntries = byType[SyncTypes.PLS].orEmpty()
        val ids = ensureSongIds((favEntries.map { it.itemKey } + plsEntries.map { it.itemKey.substringAfter('|') }).toSet())

        val favUpserts = ArrayList<FavoriteRow>()
        val favRemovals = ArrayList<String>()
        for (e in favEntries) {
            done += e.id
            val songId = ids[e.itemKey] ?: continue          // unsyncable id: stays local-only
            val local = favorites.get(e.itemKey)
            if (local != null) favUpserts += FavoriteRow(uid, songId, Instant.ofEpochMilli(local.addedAtEpochMs).toString())
            else favRemovals += songId
        }
        favUpserts.chunked(200).forEach { supabase.from("favorites").upsert(it) { onConflict = "user_id,song_id" } }
        favRemovals.chunked(100).forEach { chunk ->
            supabase.from("favorites").update({ set("deleted_at", now) }) { filter { isIn("song_id", chunk) } }
        }

        // artist follows: row still present locally = follow (upsert revives a tombstone), absent = unfollow
        val followUpserts = ArrayList<FollowRow>()
        val followRemovals = HashMap<String, MutableList<String>>()
        for (e in byType[SyncTypes.FOLLOW].orEmpty()) {
            done += e.id
            val (provider, pid) = parseArtistKey(e.itemKey) ?: continue
            val local = follows.get(e.itemKey)
            if (local != null) followUpserts += FollowRow(
                uid, provider, pid, local.name.trim().take(200).ifBlank { "Unknown artist" },
                local.imageUrl?.takeIf { it.startsWith("https://") }?.take(500),
                Instant.ofEpochMilli(local.followedAtEpochMs).toString(),
            )
            else followRemovals.getOrPut(provider) { ArrayList() } += pid
        }
        followUpserts.chunked(200).forEach {
            supabase.from("artist_follows").upsert(it) { onConflict = "user_id,provider,provider_artist_id" }
        }
        followRemovals.forEach { (provider, pids) ->
            pids.chunked(100).forEach { chunk ->
                supabase.from("artist_follows").update({ set("deleted_at", now) }) {
                    filter { eq("provider", provider); isIn("provider_artist_id", chunk) }
                }
            }
        }

        val songUpserts = ArrayList<PlaylistSongRow>()
        val songRemovals = HashMap<String, MutableList<String>>()
        for (e in plsEntries) {
            val local = playlists.get(e.itemKey.substringBefore('|').toLongOrNull() ?: -1)
            if (local == null) { done += e.id; continue }                 // playlist deleted meanwhile
            val remote = local.remoteId ?: continue                       // not pushed yet: retry next time
            done += e.id
            val songId = ids[e.itemKey.substringAfter('|')] ?: continue
            val position = e.payload?.toIntOrNull()
            if (position != null) songUpserts += PlaylistSongRow(remote, songId, position)
            else songRemovals.getOrPut(remote) { ArrayList() } += songId
        }
        songUpserts.chunked(200).forEach { supabase.from("playlist_songs").upsert(it) { onConflict = "playlist_id,song_id" } }
        songRemovals.forEach { (remote, songs) ->
            songs.chunked(100).forEach { chunk ->
                supabase.from("playlist_songs").update({ set("deleted_at", now) }) {
                    filter { eq("playlist_id", remote); isIn("song_id", chunk) }
                }
            }
        }

        done.chunked(500).forEach { pending.deleteIds(it) }
    }

    /** isrc -> server song id. Cache -> songs table -> resolve-metadata Edge Function, in batches of 50. */
    private suspend fun ensureSongIds(isrcs: Set<String>): Map<String, String> {
        val parsed = isrcs.mapNotNull { isrc -> parseIsrc(isrc)?.let { isrc to it } }
        val need = parsed.filter { songIds[it.first] == null }
        need.groupBy({ it.second.first }, { it.second.second }).forEach { (provider, all) ->
            all.chunked(100).forEach { chunk ->
                supabase.from("songs").select(Columns.list("id", "provider", "provider_song_id")) {
                    filter { eq("provider", provider); isIn("provider_song_id", chunk) }
                }.decodeList<SongRef>().forEach { songIds["${it.provider}:${it.providerSongId}"] = it.id }
            }
        }
        need.filter { songIds[it.first] == null }.chunked(50).forEach { chunk ->
            val response = supabase.functions.invoke(
                "resolve-metadata", ResolveRequest(chunk.map { ResolveItem(it.second.first, it.second.second) }),
            )
            response.body<ResolveResponse>().songs.forEach { songIds["${it.provider}:${it.providerSongId}"] = it.id }
        }
        return parsed.mapNotNull { (isrc, _) -> songIds[isrc]?.let { isrc to it } }.toMap()
    }

    // ---------------------------------------------------------------- pull

    private suspend fun pull() {
        // playlists first, so playlist_songs can find their local playlist
        pageThrough<PlaylistPull>("playlists", "id,name,tags,created_at,updated_at,deleted_at", CUR_PLAYLISTS, { it.updatedAt }) { rows ->
            for (r in rows) {
                val local = playlists.getByRemoteId(r.id)
                when {
                    r.deletedAt != null -> if (local != null) {
                        playlistTracks.clear(local.id)
                        playlists.delete(local.id)
                        pending.delete(SyncTypes.PL, local.id.toString())
                        pending.deleteByPrefix(SyncTypes.PLS, "${local.id}|%")
                    }
                    local == null -> playlists.insert(
                        PlaylistEntity(name = r.name, createdAtEpochMs = ms(r.createdAt), tags = r.tags, remoteId = r.id),
                    )
                    !pending.has(SyncTypes.PL, local.id.toString()) -> playlists.updateMeta(local.id, r.name, r.tags)
                }
            }
        }

        pageThrough<PlaylistSongPull>(
            "playlist_songs", "playlist_id,position,updated_at,deleted_at,$SONG_EMBED", CUR_PLAYLIST_SONGS, { it.updatedAt },
        ) { rows ->
            trackDao.insertIgnore(rows.filter { it.deletedAt == null }.mapNotNull { it.songs?.toTrack()?.toEntity() })
            for (r in rows) {
                val song = r.songs ?: continue
                val local = playlists.getByRemoteId(r.playlistId) ?: continue
                val isrc = song.isrc()
                if (pending.has(SyncTypes.PLS, "${local.id}|$isrc")) continue   // local change not pushed yet wins
                if (r.deletedAt != null) playlistTracks.remove(local.id, isrc)
                else playlistTracks.upsert(PlaylistTrackCrossRef(local.id, isrc, r.position))
            }
        }

        pageThrough<FavoritePull>(
            "favorites", "created_at,updated_at,deleted_at,$SONG_EMBED", CUR_FAVORITES, { it.updatedAt },
        ) { rows ->
            trackDao.insertIgnore(rows.filter { it.deletedAt == null }.mapNotNull { it.songs?.toTrack()?.toEntity() })
            for (r in rows) {
                val isrc = r.songs?.isrc() ?: continue
                if (pending.has(SyncTypes.FAV, isrc)) continue
                if (r.deletedAt != null) favorites.remove(isrc) else favorites.add(FavoriteEntity(isrc, ms(r.createdAt)))
            }
        }

        pageThrough<FollowPull>(
            "artist_follows", "provider,provider_artist_id,name,artwork_url,created_at,updated_at,deleted_at",
            CUR_FOLLOWS, { it.updatedAt },
        ) { rows ->
            for (r in rows) {
                val key = "${r.provider}:${r.providerArtistId}"
                if (pending.has(SyncTypes.FOLLOW, key)) continue          // local change not pushed yet wins
                if (r.deletedAt != null) follows.remove(key) else follows.add(FollowEntity(key, r.name, r.artworkUrl, ms(r.createdAt)))
            }
        }
    }

    /** Reads every row with updated_at >= cursor (idempotent re-apply of the boundary row), then advances the cursor. */
    private suspend inline fun <reified T : Any> pageThrough(
        table: String,
        columns: String,
        cursorName: String,
        updatedAt: (T) -> String,
        apply: suspend (List<T>) -> Unit,
    ) {
        val start = state.get(cursorName) ?: EPOCH
        var max = start
        var from = 0L
        while (true) {
            val rows = supabase.from(table).select(Columns.raw(columns)) {
                filter { gte("updated_at", start) }
                order("updated_at", Order.ASCENDING)
                range(from, from + PAGE - 1)
            }.decodeList<T>()
            if (rows.isEmpty()) break
            apply(rows)
            rows.forEach { if (ms(updatedAt(it)) > ms(max)) max = updatedAt(it) }
            if (rows.size < PAGE) break
            from += PAGE
        }
        state.put(SyncStateEntity(cursorName, max))
    }

    private fun ms(timestamp: String): Long = OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()

    private companion object {
        const val EPOCH = "1970-01-01T00:00:00+00:00"
        const val PAGE = 500L
        const val OWNER = "owner"
        const val LAST_SYNC = "last_synced_ms"
        const val CUR_PLAYLISTS = "cur_playlists"
        const val CUR_PLAYLIST_SONGS = "cur_playlist_songs"
        const val CUR_FAVORITES = "cur_favorites"
        const val CUR_FOLLOWS = "cur_follows"
        val CURSORS = listOf(CUR_PLAYLISTS, CUR_PLAYLIST_SONGS, CUR_FAVORITES, CUR_FOLLOWS)
        const val TOMBSTONE_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
    }
}