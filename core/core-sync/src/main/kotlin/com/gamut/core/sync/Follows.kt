package com.gamut.core.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.core.database.FollowDao
import com.gamut.core.database.FollowEntity
import com.gamut.core.database.PendingSyncDao
import com.gamut.core.database.PendingSyncEntity
import com.gamut.core.database.SyncTypes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Local source of truth for followed artists. Every change also marks the item dirty so SyncEngine pushes it. */
@Singleton
class FollowRepository @Inject constructor(
    private val dao: FollowDao,
    private val pending: PendingSyncDao,
) {
    /** artist keys ("deezer:123") the user follows. */
    val followedKeys: Flow<Set<String>> = dao.observeKeys().map { it.toSet() }
    fun observeAll(): Flow<List<FollowEntity>> = dao.observeAll()

    /** Backup import: follows the artist if not followed yet (keeps the existing row otherwise). True if added. */
    suspend fun importFollow(artistKey: String, name: String, imageUrl: String?, followedAt: Long): Boolean {
        if (dao.isFollowing(artistKey)) return false
        dao.add(FollowEntity(artistKey, name, imageUrl, followedAt))
        pending.put(PendingSyncEntity(type = SyncTypes.FOLLOW, itemKey = artistKey, createdAtEpochMs = System.currentTimeMillis()))
        return true
    }

    /** Follows or unfollows. Returns true when the artist is followed afterwards. */
    suspend fun toggle(artistKey: String, name: String, imageUrl: String?): Boolean {
        val now = System.currentTimeMillis()
        val wasFollowing = dao.isFollowing(artistKey)
        if (wasFollowing) dao.remove(artistKey) else dao.add(FollowEntity(artistKey, name, imageUrl, now))
        pending.put(PendingSyncEntity(type = SyncTypes.FOLLOW, itemKey = artistKey, createdAtEpochMs = now))
        return !wasFollowing
    }
}

@HiltViewModel
class FollowViewModel @Inject constructor(private val repository: FollowRepository) : ViewModel() {
    val followedKeys: StateFlow<Set<String>> =
        repository.followedKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Every followed artist, for the Library. null until the database has answered (so the screen can wait, not flash "no artists"). */
    val artists: StateFlow<List<FollowEntity>?> =
        repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(artistKey: String, name: String, imageUrl: String?) {
        viewModelScope.launch { repository.toggle(artistKey, name, imageUrl) }
    }
}