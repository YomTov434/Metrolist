/**
 * Metrolist Project (C) 2026
 * OuterTune Project Copyright (C) 2025
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.completed
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.lastfm.LastFM
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.LastFMUseSendLikes
import com.metrolist.music.constants.LastFullSyncKey
import com.metrolist.music.constants.SYNC_COOLDOWN
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.SetVideoIdEntity
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.collectLatest
import com.metrolist.music.extensions.isInternetConnected
import com.metrolist.music.extensions.isSyncEnabled
import com.metrolist.music.models.toMediaMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed class SyncOperation {
    data object FullSync : SyncOperation()
    data object LikedSongs : SyncOperation()
    data object LibrarySongs : SyncOperation()
    data object UploadedSongs : SyncOperation()
    data object LikedAlbums : SyncOperation()
    data object UploadedAlbums : SyncOperation()
    data object ArtistsSubscriptions : SyncOperation()
    data object SavedPlaylists : SyncOperation()
    data object AutoSyncPlaylists : SyncOperation()
    data class SinglePlaylist(val browseId: String, val playlistId: String) : SyncOperation()
    data class LikeSong(val song: SongEntity) : SyncOperation()
    data class SubscribeChannel(val channelId: String, val subscribe: Boolean) : SyncOperation()
}

internal fun localSongIndexesAbsentFromRemote(
    localSongIds: List<String>,
    remoteSongIds: List<String>,
): List<Int> {
    // Consume occurrences individually because playlists can deliberately contain duplicates.
    val remainingRemote = remoteSongIds.groupingBy { it }.eachCount().toMutableMap()
    return localSongIds.indices.filter { index ->
        val songId = localSongIds[index]
        val remaining = remainingRemote[songId] ?: 0
        if (remaining == 0) {
            true
        } else {
            remainingRemote[songId] = remaining - 1
            false
        }
    }
}

@Singleton
class SyncUtils @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Timber.e(throwable, "Sync coroutine exception")
        }
    }

    private val syncJob = SupervisorJob()
    private val syncScope = CoroutineScope(Dispatchers.IO + syncJob + exceptionHandler)

    private val syncChannel = Channel<SyncOperation>(Channel.BUFFERED)
    private val syncExecutionMutex = Mutex()
    private val queuedOperationKeys = ConcurrentHashMap.newKeySet<String>()

    private var lastfmSendLikes = false
    @Volatile private var cachedLastSyncEpoch: Long = 0L
    private val playlistsBeingModified = ConcurrentHashMap<String, AtomicInteger>()
    private val playlistEditMutex = Mutex()
    private var lastPlaylistEditAtMs = 0L

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val DB_OPERATION_DELAY_MS = 50L
        private const val DB_QUERY_BATCH_SIZE = 500
        private const val PLAYLIST_EDIT_THROTTLE_MS = 500L
    }
    private fun markPlaylistModifying(playlistId: String) {
        playlistsBeingModified.getOrPut(playlistId) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun unmarkPlaylistModifying(playlistId: String) {
        playlistsBeingModified[playlistId]?.let { counter ->
            if (counter.decrementAndGet() <= 0) playlistsBeingModified.remove(playlistId)
        }
    }

    private fun isPlaylistBeingModified(playlistId: String): Boolean =
        (playlistsBeingModified[playlistId]?.get() ?: 0) > 0

    private fun findSongIdsWithoutArtists(songIds: Collection<String>): Set<String> =
        songIds
            .chunked(DB_QUERY_BATCH_SIZE)
            .flatMap(database::songIdsWithoutArtists)
            .toSet()

    private suspend fun runQueuedPlaylistEdit(block: suspend () -> Unit) {
        playlistEditMutex.withLock {
            val remainingDelay = PLAYLIST_EDIT_THROTTLE_MS -
                (System.currentTimeMillis() - lastPlaylistEditAtMs)
            if (remainingDelay > 0) delay(remainingDelay)
            try {
                block()
            } finally {
                lastPlaylistEditAtMs = System.currentTimeMillis()
            }
        }
    }

    init {
        context.dataStore.data
            .map { it[LastFMUseSendLikes] ?: false }
            .distinctUntilChanged()
            .collectLatest(syncScope) {
                lastfmSendLikes = it
            }

        syncScope.launch {
            val loaded = context.dataStore.get(LastFullSyncKey, 0L)
            cachedLastSyncEpoch = maxOf(cachedLastSyncEpoch, loaded)
        }

        startProcessingQueue()
    }

    private fun startProcessingQueue() {
        syncScope.launch {
            for (operation in syncChannel) {
                try {
                    if (operation.isCoveredByFullSync() && "full" in queuedOperationKeys) {
                        Timber.d("Skipping $operation because a full sync is queued or running")
                    } else {
                        syncExecutionMutex.withLock {
                            processOperation(operation)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error processing sync operation: $operation")
                } finally {
                    operation.coalescingKey()?.let(queuedOperationKeys::remove)
                }
            }
        }
    }

    private fun enqueue(operation: SyncOperation) {
        val key = operation.coalescingKey()
        if (key != null && !queuedOperationKeys.add(key)) {
            Timber.d("Skipping duplicate sync operation: $operation")
            return
        }
        syncScope.launch {
            try {
                syncChannel.send(operation)
            } catch (e: Exception) {
                key?.let(queuedOperationKeys::remove)
                throw e
            }
        }
    }

    private fun SyncOperation.coalescingKey(): String? = when (this) {
        SyncOperation.FullSync -> "full"
        SyncOperation.LikedSongs -> "likedSongs"
        SyncOperation.LibrarySongs -> "librarySongs"
        SyncOperation.UploadedSongs -> "uploadedSongs"
        SyncOperation.LikedAlbums -> "likedAlbums"
        SyncOperation.UploadedAlbums -> "uploadedAlbums"
        SyncOperation.ArtistsSubscriptions -> "artistsSubscriptions"
        SyncOperation.SavedPlaylists -> "savedPlaylists"
        SyncOperation.AutoSyncPlaylists -> "autoSyncPlaylists"
        is SyncOperation.SinglePlaylist -> "playlist:$browseId"
        is SyncOperation.LikeSong,
        is SyncOperation.SubscribeChannel,
        -> null
    }

    private fun SyncOperation.isCoveredByFullSync(): Boolean = when (this) {
        SyncOperation.LikedSongs,
        SyncOperation.LibrarySongs,
        SyncOperation.UploadedSongs,
        SyncOperation.LikedAlbums,
        SyncOperation.UploadedAlbums,
        SyncOperation.ArtistsSubscriptions,
        SyncOperation.SavedPlaylists,
        SyncOperation.AutoSyncPlaylists,
        is SyncOperation.SinglePlaylist,
        -> true
        else -> false
    }

    private suspend fun processOperation(operation: SyncOperation) {
        when (operation) {
            is SyncOperation.FullSync -> executeFullSync()
            is SyncOperation.LikedSongs -> executeSyncLikedSongs()
            is SyncOperation.LibrarySongs -> executeSyncLibrarySongs()
            is SyncOperation.UploadedSongs -> executeSyncUploadedSongs()
            is SyncOperation.LikedAlbums -> executeSyncLikedAlbums()
            is SyncOperation.UploadedAlbums -> executeSyncUploadedAlbums()
            is SyncOperation.ArtistsSubscriptions -> executeSyncArtistsSubscriptions()
            is SyncOperation.SavedPlaylists -> executeSyncSavedPlaylists()
            is SyncOperation.AutoSyncPlaylists -> executeSyncAutoSyncPlaylists()
            is SyncOperation.SinglePlaylist -> executeSyncPlaylist(operation.browseId, operation.playlistId)
            is SyncOperation.LikeSong -> executeLikeSong(operation.song)
            is SyncOperation.SubscribeChannel -> executeSubscribeChannel(operation.channelId, operation.subscribe)
        }
    }

    private suspend fun isLoggedIn(): Boolean {
        return try {
            val cookie = context.dataStore.data
                .map { it[InnerTubeCookieKey] }
                .first()
            cookie?.let { "SAPISID" in parseCookieString(it) } ?: false
        } catch (e: Exception) {
            Timber.e(e, "Error checking login status")
            false
        }
    }

    private suspend fun <T> withRetry(
        maxRetries: Int = MAX_RETRIES,
        initialDelay: Long = INITIAL_RETRY_DELAY_MS,
        block: suspend () -> Result<T>
    ): Result<Result<T>> {
        var currentDelay = initialDelay
        repeat(maxRetries) { attempt ->
            try {
                val result = block()
                result.getOrThrow()
                return Result.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Attempt ${attempt + 1}/$maxRetries failed")
                if (attempt == maxRetries - 1) {
                    return Result.failure(e)
                }
                delay(currentDelay)
                currentDelay *= 2
            }
        }
        return Result.failure(Exception("Max retries exceeded"))
    }

    // Public API methods - Queue operations

    fun performFullSync() {
        enqueue(SyncOperation.FullSync)
    }

    suspend fun performFullSyncSuspend() {
        if (!isLoggedIn()) {
            Timber.w("Skipping full sync - user not logged in")
            return
        }
        syncExecutionMutex.withLock { executeFullSync() }
    }

    fun tryAutoSync() {
        syncScope.launch {
            if (!isLoggedIn()) {
                Timber.d("Skipping auto sync - user not logged in")
                return@launch
            }

            if (!context.isSyncEnabled() || !context.isInternetConnected()) {
                return@launch
            }

            val lastSync = context.dataStore.get(LastFullSyncKey, 0L)
            val effectiveLastSync = maxOf(lastSync, cachedLastSyncEpoch)
            val currentTime = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
            if (effectiveLastSync > 0 && (currentTime - effectiveLastSync) < SYNC_COOLDOWN) {
                return@launch
            }

            enqueue(SyncOperation.FullSync)

            val now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
            cachedLastSyncEpoch = now
            context.safeDataStoreEdit { settings ->
                settings[LastFullSyncKey] = now
            }
        }
    }

    fun likeSong(s: SongEntity) {
        enqueue(SyncOperation.LikeSong(s))
    }

    fun subscribeChannel(channelId: String, subscribe: Boolean) {
        enqueue(SyncOperation.SubscribeChannel(channelId, subscribe))
    }

    fun syncLikedSongs() {
        enqueue(SyncOperation.LikedSongs)
    }

    fun syncLibrarySongs() {
        enqueue(SyncOperation.LibrarySongs)
    }

    fun syncUploadedSongs() {
        enqueue(SyncOperation.UploadedSongs)
    }

    fun syncLikedAlbums() {
        enqueue(SyncOperation.LikedAlbums)
    }

    fun syncUploadedAlbums() {
        enqueue(SyncOperation.UploadedAlbums)
    }

    fun syncArtistsSubscriptions() {
        enqueue(SyncOperation.ArtistsSubscriptions)
    }

    fun syncSavedPlaylists() {
        enqueue(SyncOperation.SavedPlaylists)
    }

    fun syncAutoSyncPlaylists() {
        enqueue(SyncOperation.AutoSyncPlaylists)
    }

    // Suspend versions for direct calls

    suspend fun syncLikedSongsSuspend() = syncExecutionMutex.withLock { executeSyncLikedSongs() }
    suspend fun syncUploadedSongsSuspend() = syncExecutionMutex.withLock { executeSyncUploadedSongs() }
    suspend fun syncPlaylistSuspend(browseId: String, playlistId: String) =
        syncExecutionMutex.withLock {
            runQueuedPlaylistEdit { executeSyncPlaylist(browseId, playlistId) }
        }

    suspend fun clearAllLibraryData() = syncExecutionMutex.withLock {
        executeClearAllLibraryData()
    }

    private suspend fun executeClearAllLibraryData() = withContext(Dispatchers.IO) {
        Timber.d("[LOGOUT_CLEAR] Starting complete library data cleanup")
        try {

            // Clear history
            Timber.d("[LOGOUT_CLEAR] Clearing listen history and search history")
            database.clearListenHistory()
            database.clearSearchHistory()

            // Clear all tables using Room's transaction layer to ensure proper
            // InvalidationTracker notifications and avoid direct SQLite access
            // that could bypass Room's connection management.
            database.withTransaction {
                // Get all user tables from the database (auto-detect)
                val allTables = getAllUserTables()
                Timber.d("[LOGOUT_CLEAR] Found ${allTables.size} tables: $allTables")

                // Tables to skip (system tables and tables we handle specially)
                val skipTables = setOf(
                    "android_metadata",
                    "room_master_table",
                    "sqlite_sequence",
                    "search_history",  // Already cleared above
                    "listen_history"   // Already cleared above
                )

                // Tables with foreign key references - delete these first (mapping tables)
                val mappingTables = listOf(
                    "playlist_song_map",
                    "song_album_map",
                    "album_artist_map",
                    "related_song_map"
                )

                // Downloaded songs need their normalized artist rows to remain usable offline.
                val retainedDownloadTables = setOf("song", "artist", "song_artist_map")

                // Delete mapping tables first
                Timber.d("[LOGOUT_CLEAR] Deleting mapping tables")
                for (table in mappingTables) {
                    if (table in allTables) {
                        safeDeleteTable(table)
                    }
                }

                // Delete all other tables except song (handled specially to keep downloads)
                Timber.d("[LOGOUT_CLEAR] Deleting remaining tables")
                for (table in allTables) {
                    if (table in skipTables || table in mappingTables || table in retainedDownloadTables) {
                        continue
                    }
                    safeDeleteTable(table)
                }

                // Finally, delete songs but keep downloaded ones
                if ("song" in allTables) {
                    Timber.d("[LOGOUT_CLEAR] Deleting songs (keeping downloaded)")
                    database.deleteSongsNotDownloaded()
                }
                if ("artist" in allTables) {
                    database.clearArtistBookmarks()
                    database.deleteOrphanArtists()
                }
            }


            Timber.d("[LOGOUT_CLEAR] All library data cleared successfully")
        } catch (e: Exception) {
            Timber.e(e, "[LOGOUT_CLEAR] Error clearing library data")
            throw e
        }
    }

    private fun getAllUserTables(): List<String> {
        val tables = mutableListOf<String>()
        try {
            database.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    tables.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "[LOGOUT_CLEAR] Error getting table list")
        }
        return tables
    }

    private fun safeDeleteTable(tableName: String) {
        try {
            database.raw(androidx.sqlite.db.SimpleSQLiteQuery("DELETE FROM $tableName"))
            Timber.d("[LOGOUT_CLEAR] Cleared table: $tableName")
        } catch (e: Exception) {
            Timber.w("[LOGOUT_CLEAR] Table $tableName error: ${e.message}")
        }
    }

    // Private execution methods

    private suspend fun executeFullSync() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping full sync - user not logged in")
            return@withContext
        }


        try {
            // Sync in sequence to avoid overwhelming the API and database
            executeSyncLikedSongs()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncLibrarySongs()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncUploadedSongs()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncLikedAlbums()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncUploadedAlbums()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncArtistsSubscriptions()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncSavedPlaylists()
            delay(DB_OPERATION_DELAY_MS)

            Timber.d("Full sync completed successfully")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error during full sync")
        }
    }

    private suspend fun executeLikeSong(s: SongEntity) = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping likeSong - user not logged in")
            return@withContext
        }

        withRetry {
            YouTube.likeVideo(s.id, s.liked)
        }.onFailure { e ->
            Timber.e(e, "Failed to like song on YouTube: ${s.id}")
        }

        if (lastfmSendLikes) {
            try {
                val dbSong = database.song(s.id).firstOrNull()
                LastFM.setLoveStatus(
                    artist = dbSong?.artists?.joinToString { a -> a.name } ?: "",
                    track = s.title,
                    love = s.liked
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to update LastFM love status")
            }
        }
    }

    private suspend fun executeSubscribeChannel(channelId: String, subscribe: Boolean) = withContext(Dispatchers.IO) {
        Timber.d("[CHANNEL_TOGGLE] executeSubscribeChannel called: channelId=$channelId, subscribe=$subscribe")
        if (!isLoggedIn()) {
            Timber.d("[CHANNEL_TOGGLE] Skipping subscribeChannel - user not logged in")
            return@withContext
        }

        Timber.d("[CHANNEL_TOGGLE] User is logged in, calling YouTube.subscribeChannel")
        withRetry {
            YouTube.subscribeChannel(channelId, subscribe)
        }.onSuccess {
            Timber.d("[CHANNEL_TOGGLE] Successfully subscribed/unsubscribed channel: $channelId")
        }.onFailure { e ->
            Timber.e(e, "[CHANNEL_TOGGLE] Failed to subscribe/unsubscribe channel: $channelId")
        }
    }

    private suspend fun executeSyncLikedSongs() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncLikedSongs - user not logged in")
            return@withContext
        }


        withRetry {
            YouTube.playlist("LM").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteSongs = page.songs
                    val remoteIds = remoteSongs.map { it.id }.toSet()
                    val songIdsWithoutArtists = findSongIdsWithoutArtists(remoteIds)
                    val now = LocalDateTime.now()

                    database.withTransaction {
                        remoteSongs.forEachIndexed { index, song ->
                            val dbSong = songEntity(song.id)
                            val timestamp = dbSong?.likedDate ?: now.minusSeconds(index.toLong())
                            val isVideoSong = song.isVideoSong
                            if (dbSong == null) {
                                insert(song.toMediaMetadata()) {
                                    it.copy(liked = true, likedDate = timestamp, isVideo = isVideoSong)
                                }
                            } else {
                                if (song.id in songIdsWithoutArtists) {
                                    insert(song.toMediaMetadata())
                                }
                                if (!dbSong.liked || dbSong.likedDate == null || dbSong.isVideo != isVideoSong) {
                                    update(dbSong.copy(liked = true, likedDate = timestamp, isVideo = isVideoSong))
                                }
                            }
                        }
                    }

                    Timber.d("Synced ${remoteSongs.size} liked songs")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing liked songs")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch liked songs from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync liked songs after retries")
        }
    }

    private suspend fun executeSyncLibrarySongs() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncLibrarySongs - user not logged in")
            return@withContext
        }


        withRetry {
            YouTube.library("FEmusic_liked_videos").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteSongs = page.items.filterIsInstance<SongItem>().reversed()
                    val remoteIds = remoteSongs.map { it.id }.toSet()
                    val songIdsWithoutArtists = findSongIdsWithoutArtists(remoteIds)
                    val now = LocalDateTime.now()

                    database.withTransaction {
                        remoteSongs.forEachIndexed { index, song ->
                            val dbSong = songEntity(song.id)
                            val timestamp = now.minusSeconds((remoteSongs.lastIndex - index).toLong())
                            if (dbSong == null) {
                                insert(song.toMediaMetadata()) {
                                    it.withLibraryMembership(isInLibrary = true, addedAt = timestamp)
                                }
                            } else {
                                if (song.id in songIdsWithoutArtists) {
                                    insert(song.toMediaMetadata())
                                }
                                if (dbSong.inLibrary == null) {
                                    update(dbSong.withLibraryMembership(isInLibrary = true, addedAt = timestamp))
                                }
                            }
                        }
                    }

                    Timber.d("Synced ${remoteSongs.size} library songs")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing library songs")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch library songs from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync library songs after retries")
        }
    }

    private suspend fun executeSyncUploadedSongs() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncUploadedSongs - user not logged in")
            return@withContext
        }


        withRetry {
            // Uploaded songs are in Tab 1 ("Uploads"), not Tab 0 ("Library")
            YouTube.library("FEmusic_library_privately_owned_tracks", tabIndex = 1).completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteSongs = page.items.filterIsInstance<SongItem>().reversed()
                    val remoteIds = remoteSongs.map { it.id }.toSet()
                    val localSongs = database.uploadedSongEntitiesByNameAsc()
                    val songIdsWithoutArtists = findSongIdsWithoutArtists(remoteIds)

                    // Remove uploaded flag from songs no longer in remote
                    localSongs.filterNot { it.id in remoteIds }.forEach { song ->
                        try {
                            database.update(song.toggleUploaded())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to update song: ${song.id}")
                        }
                    }

                    // Sync remote songs to local database
                    remoteSongs.forEach { song ->
                        try {
                            val dbSong = database.songEntity(song.id)
                            database.withTransaction {
                                if (dbSong == null) {
                                    insert(song.toMediaMetadata()) { it.toggleUploaded() }
                                } else {
                                    if (song.id in songIdsWithoutArtists) {
                                        insert(song.toMediaMetadata())
                                    }
                                    if (!dbSong.isUploaded) {
                                        update(dbSong.copy(isUploaded = true, uploadEntityId = song.uploadEntityId))
                                    } else if (dbSong.uploadEntityId != song.uploadEntityId && song.uploadEntityId != null) {
                                        // Update uploadEntityId if it differs from remote
                                        update(dbSong.copy(uploadEntityId = song.uploadEntityId))
                                    }
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process song: ${song.id}")
                        }
                    }

                    Timber.d("Synced ${remoteSongs.size} uploaded songs")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing uploaded songs")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch uploaded songs from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync uploaded songs after retries")
        }
    }

    private suspend fun executeSyncLikedAlbums() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncLikedAlbums - user not logged in")
            return@withContext
        }


        withRetry {
            YouTube.library("FEmusic_liked_albums").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteAlbums = page.items.filterIsInstance<AlbumItem>().reversed()

                    remoteAlbums.forEach { album ->
                        try {
                            val dbAlbum = database.albumEntity(album.id)
                            if (dbAlbum == null) {
                                YouTube.album(album.browseId).onSuccess { albumPage ->
                                    database.insert(albumPage)
                                    database.albumEntity(album.id)?.let { newDbAlbum ->
                                        database.update(newDbAlbum.localToggleLike())
                                    }
                                }
                            } else if (dbAlbum.bookmarkedAt == null) {
                                database.update(dbAlbum.localToggleLike())
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process album: ${album.id}")
                        }
                    }

                    Timber.d("Synced ${remoteAlbums.size} liked albums")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing liked albums")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch liked albums from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync liked albums after retries")
        }
    }

    private suspend fun executeSyncUploadedAlbums() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncUploadedAlbums - user not logged in")
            return@withContext
        }


        withRetry {
            YouTube.library("FEmusic_library_privately_owned_releases").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteAlbums = page.items.filterIsInstance<AlbumItem>().reversed()
                    val remoteIds = remoteAlbums.map { it.id }.toSet()
                    val localAlbums = database.uploadedAlbumEntitiesByNameAsc()

                    localAlbums.filterNot { it.id in remoteIds }.forEach { album ->
                        try {
                            database.update(album.toggleUploaded())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to update album: ${album.id}")
                        }
                    }

                    remoteAlbums.forEach { album ->
                        try {
                            val dbAlbum = database.albumEntity(album.id)
                            if (dbAlbum == null) {
                                YouTube.album(album.browseId).onSuccess { albumPage ->
                                    database.insert(albumPage)
                                    database.albumEntity(album.id)?.let { newDbAlbum ->
                                        database.update(newDbAlbum.toggleUploaded())
                                    }
                                }.onFailure { reportException(it) }
                            } else if (!dbAlbum.isUploaded) {
                                database.update(dbAlbum.toggleUploaded())
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process album: ${album.id}")
                        }
                    }

                    Timber.d("Synced ${remoteAlbums.size} uploaded albums")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing uploaded albums")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch uploaded albums from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync uploaded albums after retries")
        }
    }

    private suspend fun executeSyncArtistsSubscriptions() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncArtistsSubscriptions - user not logged in")
            return@withContext
        }


        withRetry {
            YouTube.library("FEmusic_library_corpus_artists").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteArtists = page.items.filterIsInstance<ArtistItem>()
                    val remoteIds = remoteArtists.map { it.id }.toSet()
                    val localArtists = database.bookmarkedArtistEntitiesByNameAsc()

                    localArtists.filterNot { it.id in remoteIds }.forEach { artist ->
                        try {
                            database.update(artist.localToggleLike())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to update artist: ${artist.id}")
                        }
                    }

                    remoteArtists.forEach { artist ->
                        try {
                            val dbArtist = database.artistEntity(artist.id)
                            val channelId = artist.channelId ?: artist.id.takeIf { it.startsWith("UC") }

                            database.withTransaction {
                                if (dbArtist == null) {
                                    insert(
                                        ArtistEntity(
                                            id = artist.id,
                                            name = ArtistNameAliases.resolve(artist.id, artist.title),
                                            thumbnailUrl = artist.thumbnail,
                                            channelId = channelId,
                                            bookmarkedAt = LocalDateTime.now()
                                        )
                                    )
                                } else {
                                    val existing = dbArtist
                                    val needsChannelIdUpdate = existing.channelId == null && channelId != null
                                    val resolvedName = ArtistNameAliases.resolve(existing.id, artist.title)
                                    if (existing.bookmarkedAt == null || needsChannelIdUpdate ||
                                        existing.name != resolvedName || existing.thumbnailUrl != artist.thumbnail) {
                                        update(
                                            existing.copy(
                                                name = resolvedName,
                                                thumbnailUrl = artist.thumbnail,
                                                channelId = channelId ?: existing.channelId,
                                                bookmarkedAt = existing.bookmarkedAt ?: LocalDateTime.now(),
                                                lastUpdateTime = LocalDateTime.now()
                                            )
                                        )
                                    }
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process artist: ${artist.id}")
                        }
                    }

                    Timber.d("Synced ${remoteArtists.size} artist subscriptions")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing artist subscriptions")
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch artist subscriptions from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync artist subscriptions after retries")
        }
    }

    private suspend fun executeSyncSavedPlaylists() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncSavedPlaylists - user not logged in")
            return@withContext
        }


        withRetry {
            YouTube.library("FEmusic_liked_playlists").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remotePlaylists = page.items.filterIsInstance<PlaylistItem>()
                        .filterNot { it.id == "LM" || it.id == "SE" }
                        .reversed()
                        .distinctBy { it.id }
                    val remoteIds = remotePlaylists.map { it.id }.toSet()

                    executeCleanupDuplicatePlaylists()

                    val localPlaylists = database.playlistEntitiesByNameAsc().toMutableList()
                    localPlaylists.filterNot { it.browseId in remoteIds }
                        .filterNot { it.browseId == null }
                        .forEach { playlist ->
                            try {
                                database.update(playlist.localToggleLike())
                                delay(DB_OPERATION_DELAY_MS)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to update playlist: ${playlist.id}")
                            }
                        }

                    for (playlist in remotePlaylists) {
                        try {
                            var playlistEntity = localPlaylists.find { it.browseId == playlist.id }

                            if (playlistEntity == null) {
                                playlistEntity = PlaylistEntity(
                                    name = playlist.title,
                                    browseId = playlist.id,
                                    thumbnailUrl = playlist.thumbnail,
                                    isEditable = playlist.isEditable,
                                    bookmarkedAt = LocalDateTime.now(),
                                    remoteSongCount = playlist.songCountText?.let {
                                        Regex("""\d+""").find(it)?.value?.toIntOrNull()
                                    },
                                    playEndpointParams = playlist.playEndpoint?.params,
                                    shuffleEndpointParams = playlist.shuffleEndpoint?.params,
                                    radioEndpointParams = playlist.radioEndpoint?.params
                                )
                                database.insert(playlistEntity)
                                localPlaylists.add(playlistEntity)
                                Timber.d("syncSavedPlaylists: Created new playlist ${playlist.title} (${playlist.id})")
                            } else {
                                database.update(playlistEntity, playlist)
                                Timber.d("syncSavedPlaylists: Updated existing playlist ${playlist.title} (${playlist.id})")
                            }

                            if (!isPlaylistBeingModified(playlistEntity.id)) {
                                executeSyncPlaylist(playlist.id, playlistEntity.id)
                                delay(DB_OPERATION_DELAY_MS)
                            } else {
                                Timber.d("Skipping playlist ${playlist.title} — remove in progress")
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to sync playlist ${playlist.title}")
                        }
                    }

                    Timber.d("Synced ${remotePlaylists.size} saved playlists")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing saved playlists")
                }
            }.onFailure { e ->
                Timber.e(e, "syncSavedPlaylists: Failed to fetch playlists from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync saved playlists after retries")
        }
    }

    private suspend fun executeSyncAutoSyncPlaylists() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncAutoSyncPlaylists - user not logged in")
            return@withContext
        }

        try {
            val autoSyncPlaylists = database.playlistEntitiesByNameAsc()
                .filter { it.isAutoSync && it.browseId != null }

            Timber.d("syncAutoSyncPlaylists: Found ${autoSyncPlaylists.size} playlists to sync")

            autoSyncPlaylists.forEach { playlist ->
                // Skip playlists with a pending remove operation
                if (isPlaylistBeingModified(playlist.id)) {
                    Timber.d("Skipping playlist ${playlist.name} — remove in progress")
                    return@forEach
                }
                try {
                    executeSyncPlaylist(playlist.browseId!!, playlist.id)
                    delay(DB_OPERATION_DELAY_MS)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to sync playlist ${playlist.name}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error syncing auto-sync playlists")
        }
    }

    private suspend fun executeSyncPlaylist(browseId: String, playlistId: String) = withContext(Dispatchers.IO) {
        Timber.d("syncPlaylist: Starting sync for browseId=$browseId, playlistId=$playlistId")

        withRetry {
            YouTube.playlist(browseId).completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val songs = page.songs.map(SongItem::toMediaMetadata)
                    Timber.d("syncPlaylist: Fetched ${songs.size} songs from remote")

                    if (songs.isEmpty()) {
                        Timber.w("syncPlaylist: Remote playlist is empty, skipping sync")
                        return@onSuccess
                    }

                    val remoteIds = songs.map { it.id }
                    val localSongs = database.playlistSongMaps(playlistId, from = 0)
                    val localIds = localSongs.map { it.songId }
                    val songIdsWithoutArtists = database.playlistSongIdsWithoutArtists(playlistId).toSet()

                    if (remoteIds == localIds) {
                        val metadataRepairs = songs.filter {
                            it.id in songIdsWithoutArtists && it.artists.isNotEmpty()
                        }
                        if (metadataRepairs.isNotEmpty()) {
                            database.withTransaction {
                                metadataRepairs.forEach(::insert)
                            }
                        }
                        Timber.d("syncPlaylist: Local and remote are in sync, no changes needed")
                        return@onSuccess
                    }

                    Timber.d("syncPlaylist: Updating local playlist (remote: ${remoteIds.size}, local: ${localIds.size})")

                    val localIdSet = localIds.toSet()
                    val metadataInserts = songs.filter {
                        it.id !in localIdSet || it.id in songIdsWithoutArtists
                    }
                    val preservedSongs = localSongIndexesAbsentFromRemote(localIds, remoteIds)
                        .map(localSongs::get)

                    database.withTransaction {
                        database.clearPlaylist(playlistId)
                        metadataInserts.forEach(database::insert)

                        val playlistEntity = database.playlistBlocking(playlistId)
                        if (playlistEntity != null) {
                            database.addSongsToPlaylist(
                                playlistEntity,
                                songs.map { it.id to it.setVideoId }
                            )
                        }
                        preservedSongs.forEachIndexed { index, song ->
                            database.insert(
                                song.copy(
                                    id = 0,
                                    position = songs.size + index,
                                )
                            )
                        }
                    }
                    Timber.d("syncPlaylist: Successfully synced playlist")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing playlist sync")
                }
            }.onFailure { e ->
                Timber.e(e, "syncPlaylist: Failed to fetch playlist from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "syncPlaylist: Failed after retries")
        }
    }

    private suspend fun executeCleanupDuplicatePlaylists() = withContext(Dispatchers.IO) {
        try {
            val allPlaylists = database.playlistEntitiesByNameAsc()
            val browseIdGroups = allPlaylists
                .filter { it.browseId != null }
                .groupBy { it.browseId }

            for ((browseId, playlists) in browseIdGroups) {
                if (playlists.size > 1) {
                    Timber.w("Found ${playlists.size} duplicate playlists for browseId: $browseId")
                    val toKeep = playlists.maxByOrNull { it.remoteSongCount ?: 0 } ?: playlists.first()

                    playlists.filter { it.id != toKeep.id }.forEach { duplicate ->
                        try {
                            Timber.d("Removing duplicate playlist: ${duplicate.name} (${duplicate.id})")
                            database.clearPlaylist(duplicate.id)
                            database.delete(duplicate)
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to remove duplicate playlist: ${duplicate.id}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error cleaning up duplicate playlists")
        }
    }

    fun createPlaylist(
        playlist: PlaylistEntity,
        syncWithYouTube: Boolean,
        onCreated: ((String, Boolean) -> Unit)? = null,
    ) {
        syncScope.launch {
            val browseId = if (syncWithYouTube) {
                runCatching { YouTube.createPlaylist(playlist.name) }
                    .onFailure { Timber.e(it, "Failed to create playlist ${playlist.name} on YouTube") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val createdPlaylist = playlist.copy(
                browseId = browseId,
                isAutoSync = syncWithYouTube && browseId != null,
            )
            database.insert(createdPlaylist)
            withContext(Dispatchers.Main) {
                onCreated?.invoke(createdPlaylist.id, browseId != null)
            }
        }
    }

    fun scheduleAddToPlaylist(
        browseId: String,
        playlistId: String,
        songIds: List<String>,
    ) {
        if (songIds.isEmpty()) return
        markPlaylistModifying(playlistId)
        syncScope.launch {
            try {
                songIds.forEach { songId ->
                    try {
                        runQueuedPlaylistEdit {
                            YouTube.addToPlaylist(browseId, songId).getOrThrow()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to add song $songId to playlist $browseId")
                    }
                }
            } finally {
                unmarkPlaylistModifying(playlistId)
            }
        }
    }

    fun scheduleRemoveFromPlaylist(
        browseId: String,
        songId: String,
        playlistId: String,
        getSetVideoId: suspend () -> String?
    ) {
        markPlaylistModifying(playlistId)
        syncScope.launch {
            try {
                runQueuedPlaylistEdit {
                    var setVideoId = getSetVideoId()

                    if (setVideoId == null) {
                        Timber.w("scheduleRemoveFromPlaylist: setVideoId not in DB, fetching from YouTube")
                        for (attempt in 0 until 3) {
                            setVideoId = runCatching {
                                YouTube.playlist(browseId).completed().getOrThrow()
                                    .songs.lastOrNull { it.id == songId }?.setVideoId
                            }.getOrNull()
                            if (setVideoId != null) break
                            if (attempt < 2) delay(2_000L)
                        }
                    }

                    if (setVideoId == null) {
                        Timber.w("scheduleRemoveFromPlaylist: setVideoId not found on YouTube, skipping remove for songId=$songId")
                        return@runQueuedPlaylistEdit
                    }

                    YouTube.removeFromPlaylist(browseId, songId, setVideoId).getOrThrow()
                }
                delay(3_000L)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove song $songId from playlist $browseId")
            } finally {
                unmarkPlaylistModifying(playlistId)
            }
        }
    }

}
