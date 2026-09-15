package com.enve.app.widgets

import android.graphics.BitmapFactory
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import com.enve.core.data.model.AppMediaType
import com.enve.core.data.model.Book
import com.enve.engine.playback.PlaybackQueueItem
import com.enve.engine.library.LibraryEditionLink
import com.enve.app.readium.ReadAloudPlaybackState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun <T> widgetSnapshots(context: Context, name: String, load: () -> T) = callbackFlow {
    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(load()) }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    trySend(load())
    awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}.distinctUntilChanged()

internal fun Book.supportsListening(): Boolean =
    mediaType == AppMediaType.AUDIOBOOK || mediaType == AppMediaType.PODCAST || hasAudio || readAlongAvailable

internal fun Book.supportsReading(): Boolean =
    mediaType == AppMediaType.EBOOK || hasEbook || readAlongAvailable

internal fun ReadAloudPlaybackState.matchesBook(key: String?): Boolean =
    key != null && bookKey == key && sessionId != null && hasMediaItem

internal data class WidgetSelection(
    val book: Book?,
    val readerBook: Book?,
    val fromQueue: Boolean,
    val upNext: List<String>,
)

internal fun selectWidgetBook(
    continuing: List<Book>,
    queue: List<PlaybackQueueItem>,
    links: List<LibraryEditionLink>,
    libraryBooks: List<Book>,
): WidgetSelection {
    val book = continuing.firstOrNull() ?: queue.firstOrNull { it.book.supportsListening() }?.book
    val readerBook = book?.takeIf { it.supportsReading() } ?: book?.let { current ->
        val key = links.firstOrNull { it.audiobookKey == current.uniqueKey }?.ebookKey
        libraryBooks.firstOrNull { it.uniqueKey == key && it.supportsReading() }
    }
    return WidgetSelection(book, readerBook, continuing.isEmpty() && book != null,
        listeningQueueTitles(queue, book?.uniqueKey))
}

internal fun listeningQueueTitles(queue: List<PlaybackQueueItem>, currentKey: String?): List<String> =
    queue.map { it.book }
        .filter { it.supportsListening() && it.uniqueKey != currentKey }
        .distinctBy { it.uniqueKey }
        .take(2)
        .map { it.title }

@Composable
internal fun WidgetCover(path: String?, title: String?, width: Dp, height: Dp) {
    Box(
        GlanceModifier.width(width).height(height).cornerRadius(12.dp)
            .background(ColorProvider(Color(0xFF302821))),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = path?.let(BitmapFactory::decodeFile)
        if (bitmap != null) {
            Image(ImageProvider(bitmap), title, GlanceModifier.fillMaxSize(), ContentScale.Fit)
        }
    }
}
