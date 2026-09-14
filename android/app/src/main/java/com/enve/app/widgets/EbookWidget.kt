package com.enve.app.widgets

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.enve.app.MainActivity
import com.enve.app.ui.screens.ComicReaderActivity
import com.enve.app.ui.screens.EbookReaderActivity
import com.enve.app.ui.screens.PdfReaderActivity
import com.enve.core.data.model.AppMediaType
import com.enve.core.data.model.Book
import com.enve.core.data.model.BookSource
import com.enve.engine.library.LibraryEditionLink
import com.enve.engine.library.LibraryFacade
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

private data class EbookWidgetSnapshot(
    val bookId: String?,
    val sourceName: String?,
    val connectionId: String?,
    val mediaTypeName: String?,
    val hasEbook: Boolean,
    val primaryFileType: String?,
    val title: String?,
    val author: String?,
    val epubProgress: Float?,
    val readProgress: Float,
    val epubLocator: String?,
    val readAlongAvailable: Boolean,
    val lastReadTime: Long,
    val coverUrl: String?,
    val artworkPath: String?,
) {
    fun toBookOrNull(): Book? {
        val id = bookId ?: return null
        val source = sourceName?.let { runCatching { BookSource.valueOf(it) }.getOrNull() } ?: return null
        return Book(
            id = id,
            title = title.orEmpty(),
            author = author,
            source = source,
            mediaType = mediaTypeName?.let { runCatching { AppMediaType.valueOf(it) }.getOrNull() } ?: AppMediaType.EBOOK,
            connectionId = connectionId,
            epubProgress = epubProgress,
            epubLocator = epubLocator,
            readAlongAvailable = readAlongAvailable,
            hasEbook = hasEbook,
            primaryFileType = primaryFileType,
            readProgress = readProgress,
            lastReadTime = lastReadTime,
        )
    }
}

private object EbookWidgetStore {
    private const val PREFS = "enve_ebook_widget"

    fun save(context: Context, snapshot: EbookWidgetSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("book_id", snapshot.bookId)
            .putString("source", snapshot.sourceName)
            .putString("connection_id", snapshot.connectionId)
            .putString("media_type", snapshot.mediaTypeName)
            .putBoolean("has_ebook", snapshot.hasEbook)
            .putString("primary_file_type", snapshot.primaryFileType)
            .putString("title", snapshot.title)
            .putString("author", snapshot.author)
            .apply {
                if (snapshot.epubProgress != null) putFloat("epub_progress", snapshot.epubProgress) else remove("epub_progress")
            }
            .putFloat("read_progress", snapshot.readProgress)
            .putString("epub_locator", snapshot.epubLocator)
            .putBoolean("read_along", snapshot.readAlongAvailable)
            .putLong("last_read_time", snapshot.lastReadTime)
            .putString("cover_url", snapshot.coverUrl)
            .apply()
    }

    fun saveArtwork(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("artwork", path).apply()
    }

    fun clearArtwork(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("artwork").apply()
    }

    fun load(context: Context): EbookWidgetSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return EbookWidgetSnapshot(
            bookId = prefs.getString("book_id", null),
            sourceName = prefs.getString("source", null),
            connectionId = prefs.getString("connection_id", null),
            mediaTypeName = prefs.getString("media_type", null),
            hasEbook = prefs.getBoolean("has_ebook", false),
            primaryFileType = prefs.getString("primary_file_type", null),
            title = prefs.getString("title", null),
            author = prefs.getString("author", null),
            epubProgress = if (prefs.contains("epub_progress")) prefs.getFloat("epub_progress", 0f) else null,
            readProgress = prefs.getFloat("read_progress", 0f),
            epubLocator = prefs.getString("epub_locator", null),
            readAlongAvailable = prefs.getBoolean("read_along", false),
            lastReadTime = prefs.getLong("last_read_time", 0L),
            coverUrl = prefs.getString("cover_url", null),
            artworkPath = prefs.getString("artwork", null),
        )
    }
}

private fun readingBooks(books: List<Book>, links: List<LibraryEditionLink>): List<Book> {
    val linkedAudioToEbook = links.associate { it.audiobookKey to it.ebookKey }
    val presentKeys = books.mapTo(HashSet()) { it.uniqueKey }
    val reading = ArrayList<Book>()
    for (book in books) {
        val forcedReading = book.readAlongAvailable || book.uniqueKey in linkedAudioToEbook
        if (book.mediaType == AppMediaType.AUDIOBOOK && !forcedReading) continue
        val pairedEbook = linkedAudioToEbook[book.uniqueKey]
        if (pairedEbook != null && pairedEbook in presentKeys) continue
        reading.add(book)
    }
    return reading
}

@Singleton
class EbookWidgetPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    library: LibraryFacade,
    private val imageLoader: ImageLoader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            combine(library.continueBooks, library.editionLinks) { books, links -> readingBooks(books, links).firstOrNull() }
                .distinctUntilChangedBy { book ->
                    listOf(book?.uniqueKey, book?.title, book?.author, book?.epubProgress, book?.coverUrl)
                }
                .collect { book ->
                    val previous = EbookWidgetStore.load(context)
                    val previousKey = previous.bookId?.let { id -> "${previous.connectionId ?: previous.sourceName}:$id" }
                    val coverChanged = book?.coverUrl != previous.coverUrl || book?.uniqueKey != previousKey
                    val snapshot = EbookWidgetSnapshot(
                        bookId = book?.id,
                        sourceName = book?.source?.name,
                        connectionId = book?.connectionId,
                        mediaTypeName = book?.mediaType?.name,
                        hasEbook = book?.hasEbook ?: false,
                        primaryFileType = book?.primaryFileType,
                        title = book?.title,
                        author = book?.author,
                        epubProgress = book?.epubProgress,
                        readProgress = book?.readProgress ?: 0f,
                        epubLocator = book?.epubLocator,
                        readAlongAvailable = book?.readAlongAvailable ?: false,
                        lastReadTime = book?.lastReadTime ?: 0L,
                        coverUrl = book?.coverUrl,
                        artworkPath = if (coverChanged) null else previous.artworkPath,
                    )
                    if (coverChanged) EbookWidgetStore.clearArtwork(context)
                    EbookWidgetStore.save(context, snapshot)
                    EbookWidget().updateAll(context)
                    if (snapshot.coverUrl != null && coverChanged) cacheArtwork(snapshot.coverUrl)
                }
        }
    }

    private suspend fun cacheArtwork(url: String) {
        try {
            val result = imageLoader.execute(
                ImageRequest.Builder(context).data(url).size(512).allowHardware(false).build(),
            ) as? SuccessResult ?: return
            val file = File(context.filesDir, "widget_ebook_cover.jpg")
            FileOutputStream(file).use { output ->
                result.drawable.toBitmap().compress(Bitmap.CompressFormat.JPEG, 86, output)
            }
            EbookWidgetStore.saveArtwork(context, file.absolutePath)
            EbookWidget().updateAll(context)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }
}

private fun Context.readerIntentFor(book: Book): Intent {
    val readerFormat = when {
        (book.source == BookSource.STORYTELLER || book.source == BookSource.LOCAL) && book.readAlongAvailable -> "READALOUD"
        book.mediaType == AppMediaType.AUDIOBOOK && book.hasEbook -> "EPUB"
        else -> book.primaryFileType
    }
    return when (readerFormat?.uppercase()) {
        "PDF" -> PdfReaderActivity.createIntent(
            context = this,
            bookId = book.id,
            bookSource = book.source,
            connectionId = book.connectionId,
            title = book.title,
            author = book.author ?: "",
            locator = book.epubLocator,
        ).apply { putExtra(PdfReaderActivity.EXTRA_HEARTH_CHROME, true) }
        "CBZ", "CBX", "CBR" -> ComicReaderActivity.createIntent(
            context = this,
            bookId = book.id,
            bookSource = book.source,
            connectionId = book.connectionId,
            title = book.title,
            author = book.author ?: "",
            format = readerFormat,
            locator = book.epubLocator,
        ).apply { putExtra(ComicReaderActivity.EXTRA_HEARTH_CHROME, true) }
        else -> EbookReaderActivity.createIntent(
            context = this,
            bookId = book.id,
            bookSource = book.source,
            connectionId = book.connectionId,
            title = book.title,
            author = book.author ?: "",
            bookFormat = readerFormat,
            epubLocator = book.epubLocator,
            epubProgress = book.epubProgress ?: book.readProgress,
            lastReadTime = book.lastReadTime,
        ).apply { putExtra(EbookReaderActivity.EXTRA_HEARTH_CHROME, true) }
    }
}

private val bg = ColorProvider(Color(0xFF191512))
private val surface = ColorProvider(Color(0xFF302821))
private val text = ColorProvider(Color(0xFFF3EBDD))
private val secondary = ColorProvider(Color(0xFFB9AA98))
private val ember = ColorProvider(Color(0xFFF5921A))

private val coverArtworkWide = DpSize(80.dp, 120.dp)
private val coverArtworkLarge = DpSize(96.dp, 144.dp)

class EbookWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = EbookWidgetStore.load(context)
        provideContent {
            val size = LocalSize.current
            when {
                size.width < 180.dp -> Compact(context, snapshot, size)
                size.height < 180.dp -> Wide(context, snapshot, size)
                else -> Large(context, snapshot, size)
            }
        }
    }

    @Composable
    private fun Compact(context: Context, state: EbookWidgetSnapshot, size: DpSize) {
        Box(GlanceModifier.fillMaxSize().background(bg).cornerRadius(24.dp).clickable(openAction(context, state))) {
            Artwork(state, size)
            Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
                Spacer(GlanceModifier.height(74.dp))
                Text(state.title ?: "Continue Reading", style = TextStyle(text, 13.sp, FontWeight.Bold), maxLines = 1)
                Spacer(GlanceModifier.height(6.dp))
                Progress(state, size.width - 24.dp, showLabel = false)
            }
        }
    }

    @Composable
    private fun Wide(context: Context, state: EbookWidgetSnapshot, size: DpSize) {
        Row(GlanceModifier.fillMaxSize().background(bg).cornerRadius(24.dp).padding(14.dp).clickable(openAction(context, state))) {
            Artwork(state, coverArtworkWide, 16.dp)
            Spacer(GlanceModifier.width(14.dp))
            Column {
                Text("CURRENTLY READING", style = TextStyle(ember, 10.sp, FontWeight.Bold))
                Text(state.title ?: "Nothing being read", style = TextStyle(text, 16.sp, FontWeight.Bold), maxLines = 1)
                Text(state.author ?: "Open Enve to start a book", style = TextStyle(secondary, 11.sp), maxLines = 1)
                Spacer(GlanceModifier.height(8.dp))
                Progress(state, size.width - 28.dp - coverArtworkWide.width - 14.dp)
            }
        }
    }

    @Composable
    private fun Large(context: Context, state: EbookWidgetSnapshot, size: DpSize) {
        Column(GlanceModifier.fillMaxSize().background(bg).cornerRadius(24.dp).padding(16.dp).clickable(openAction(context, state))) {
            Row {
                Artwork(state, coverArtworkLarge, 18.dp)
                Spacer(GlanceModifier.width(14.dp))
                Column {
                    Text("CURRENTLY READING", style = TextStyle(ember, 10.sp, FontWeight.Bold))
                    Text(state.title ?: "Nothing being read", style = TextStyle(text, 17.sp, FontWeight.Bold), maxLines = 2)
                    Text(state.author ?: "Open Enve to start a book", style = TextStyle(secondary, 11.sp), maxLines = 1)
                }
            }
            Spacer(GlanceModifier.height(10.dp))
            Progress(state, size.width - 32.dp)
        }
    }

    @Composable
    private fun Artwork(state: EbookWidgetSnapshot, boxSize: DpSize, cornerRadius: Dp = 0.dp) {
        val boxModifier = GlanceModifier.width(boxSize.width).height(boxSize.height).cornerRadius(cornerRadius).background(surface)
        val bitmap = state.artworkPath?.let(BitmapFactory::decodeFile)
        if (bitmap == null) {
            Box(boxModifier) {}
            return
        }
        val naturalHeightAtFullWidth = (boxSize.width.value * bitmap.height / bitmap.width).dp
        Box(boxModifier) {
            Image(ImageProvider(bitmap), null, GlanceModifier.width(boxSize.width).height(naturalHeightAtFullWidth), ContentScale.FillBounds)
        }
    }

    @Composable
    private fun Progress(state: EbookWidgetSnapshot, width: Dp, showLabel: Boolean = true) {
        val fraction = (state.epubProgress ?: state.readProgress).coerceIn(0f, 1f)
        Box(GlanceModifier.width(width).height(4.dp).cornerRadius(2.dp).background(surface)) {
            Box(GlanceModifier.width(width * fraction).height(4.dp).cornerRadius(2.dp).background(ember)) {}
        }
        if (showLabel) {
            Spacer(GlanceModifier.height(4.dp))
            Text("${(fraction * 100).toInt()}% read", style = TextStyle(secondary, 11.sp, FontWeight.Medium))
        }
    }

    private fun openAction(context: Context, state: EbookWidgetSnapshot) = state.toBookOrNull()
        ?.let { book -> actionStartActivity(context.readerIntentFor(book)) }
        ?: actionStartActivity<MainActivity>()
}

class EbookWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EbookWidget()
}
