package se.jferm.bookdriver.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.drive.DriveApi
import java.io.File

/** Builds the ExoPlayer used by [PlaybackService]: Drive streaming through a local LRU cache. */
@OptIn(UnstableApi::class)
object PlayerFactory {
    const val SKIP_BACK_MS = 10_000L
    const val SKIP_FORWARD_MS = 30_000L
    private const val CACHE_BYTES = 768L * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    /** One SimpleCache per process, ever (it locks its folder). */
    fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
            StandaloneDatabaseProvider(context),
        ).also { cache = it }
    }

    /** Drop all cached audio, e.g. when the account signs out. Call off the main thread. */
    fun clearCache(context: Context) {
        val c = cache(context)
        c.keys.toList().forEach { runCatching { c.removeResource(it) } }
    }

    /**
     * Media3 pauses SPEECH content on a "may duck" focus loss and lowers the volume for
     * anything else. Cars grab transient focus a lot (often silently), and those pauses show
     * up as 0.5–2 s dropouts, so by default we present as music and get ducked instead.
     */
    fun audioAttributes(pauseForOtherSounds: Boolean): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(if (pauseForOtherSounds) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

    // Spoken word is low bitrate: keep minutes, not seconds, buffered so tunnels and patchy
    // mobile data in a car don't stall playback. 10 min at 128 kb/s is under 10 MB.
    private fun loadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 3 * 60_000,
            /* maxBufferMs = */ 10 * 60_000,
            /* bufferForPlaybackMs = */ 2_500,
            /* bufferForPlaybackAfterRebufferMs = */ 5_000,
        )
        .setTargetBufferBytes(32 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    fun create(context: Context, http: OkHttpClient, pauseForOtherSounds: Boolean = false): ExoPlayer {
        // Streamed bytes land in the cache, so rewinds and re-opens (and the moov box at
        // the end of a big .m4b) are served locally.
        val dataSource = CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(audioAttributes(pauseForOtherSounds), /* handleAudioFocus = */ true)
            .setLoadControl(loadControl())
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SKIP_BACK_MS)
            .setSeekForwardIncrementMs(SKIP_FORWARD_MS)
            .build()
    }
}

/** Media IDs: "book:<id>" is a whole book (browse / resolve); "<id>#<n>" is track n of it. */
object BookMedia {
    const val BOOK_PREFIX = "book:"
    const val ROOT_ID = "root"

    fun trackId(bookId: String, index: Int) = "$bookId#$index"

    /** (bookId, trackIndex) from a track media ID. */
    fun parse(mediaId: String?): Pair<String, Int>? {
        if (mediaId == null) return null
        val hash = mediaId.lastIndexOf('#')
        if (hash <= 0) return null
        return mediaId.substring(0, hash) to (mediaId.substring(hash + 1).toIntOrNull() ?: return null)
    }

    fun coverUri(coversDir: File, book: Book): Uri? =
        book.coverFile?.let { File(coversDir, it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it) }

    fun trackItems(book: Book, drive: DriveApi, coversDir: File): List<MediaItem> {
        val art = coverUri(coversDir, book)
        return book.tracks.mapIndexed { i, t ->
            MediaItem.Builder()
                .setMediaId(trackId(book.id, i))
                .setUri(drive.mediaUrl(t.fileId))
                .setCustomCacheKey(t.fileId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.title)
                        .setArtist(book.author)
                        .setAlbumTitle(book.title)
                        .setArtworkUri(art)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                        .build()
                )
                .build()
        }
    }

    fun browseItem(book: Book): MediaItem =
        MediaItem.Builder()
            .setMediaId(BOOK_PREFIX + book.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(book.author)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .build()
            )
            .build()

    fun root(): MediaItem =
        MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Library")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                    .build()
            )
            .build()
}
