package com.enve.app.widgets

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.appwidget.updateAll
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.enve.core.data.model.AppMediaType
import com.enve.app.readium.ReadAloudPlaybackCoordinator
import com.enve.engine.library.LibraryFacade
import com.enve.engine.playback.PlaybackFacade
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class BookWidgetPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    playback: PlaybackFacade,
    library: LibraryFacade,
    readAloud: ReadAloudPlaybackCoordinator,
    private val imageLoader: ImageLoader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            val selection = combine(library.continueBooks, playback.queue, library.editionLinks, library.allBooks) {
                    continuing, queue, links, books -> selectWidgetBook(continuing, queue, links, books)
            }
            combine(selection, playback.nowPlaying, playback.transport, readAloud.state) { selected, now, transport, narration ->
                val book = selected.book
                val liveNarration = narration.matchesBook(book?.uniqueKey)
                val live = book != null && now?.bookKey == book.uniqueKey &&
                    transport.bookId == book.id && transport.hasMedia
                val progress = when {
                    book == null -> 0f
                    book.mediaType == AppMediaType.EBOOK || book.readAlongAvailable -> book.epubProgress ?: book.readProgress
                    live && transport.durationMs > 0 -> transport.progress
                    else -> book.progress
                }
                BookWidgetSnapshot(
                    book = book?.copy(chapters = emptyList(), audioTracks = emptyList()),
                    readerBook = selected.readerBook?.copy(chapters = emptyList(), audioTracks = emptyList()),
                    isPlaying = if (liveNarration) narration.isPlaying else live && transport.isPlaying,
                    hasLiveAudio = live || liveNarration,
                    readAlongSessionId = narration.sessionId.takeIf { liveNarration },
                    progress = (progress.coerceIn(0f, 1f) * 1000).toInt() / 1000f,
                    fromQueue = selected.fromQueue, upNext = selected.upNext,
                )
            }.distinctUntilChanged().collectLatest { snapshot ->
                val previous = BookWidgetStore.load(context)
                val sameCover = snapshot.book?.uniqueKey == previous.book?.uniqueKey &&
                    snapshot.book?.coverUrl == previous.book?.coverUrl
                val cached = previous.artworkPath?.takeIf { sameCover && File(it).isFile }
                val state = snapshot.copy(artworkPath = cached)
                BookWidgetStore.save(context, state)
                BookPlayerWidget().updateAll(context)
                snapshot.book?.coverUrl?.takeIf { cached == null }?.let { url ->
                    cacheArtwork(url)?.let { path ->
                        BookWidgetStore.save(context, state.copy(artworkPath = path))
                        BookPlayerWidget().updateAll(context)
                    }
                }
            }
        }
    }

    private suspend fun cacheArtwork(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val result = imageLoader.execute(
                ImageRequest.Builder(context).data(url).size(512).allowHardware(false).build(),
            ) as? SuccessResult ?: return@withContext null
            val file = File(context.filesDir, "widget_book_cover.jpg")
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                result.drawable.toBitmap().compress(Bitmap.CompressFormat.JPEG, 86, output)
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
            file.absolutePath
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
