package com.enve.app.widgets

import com.enve.core.data.model.AppMediaType
import com.enve.core.data.model.Book
import com.enve.core.data.model.BookSource
import com.enve.engine.library.LibraryEditionLink
import com.enve.engine.playback.PlaybackQueueItem
import com.enve.engine.playback.PlaybackQueueOrigin
import com.enve.app.readium.ReadAloudPlaybackState
import org.junit.Assert.*
import org.junit.Test

class WidgetSelectionTest {
    private val ebook = book("webster", AppMediaType.EBOOK)
    private val audio = book("audio", AppMediaType.AUDIOBOOK)

    @Test fun emptyContinueAndQueueDoesNotPickAnUnstartedLibraryBook() {
        val state = selectWidgetBook(emptyList(), emptyList(), emptyList(), listOf(ebook, audio))
        assertNull(state.book)
        assertFalse(state.fromQueue)
    }

    @Test fun firstContinueBookWinsEvenWhenItIsAnEbook() {
        val state = selectWidgetBook(listOf(ebook, audio), listOf(queued(audio)), emptyList(), listOf(ebook, audio))
        assertEquals(ebook, state.book)
        assertEquals(ebook, state.readerBook)
        assertFalse(state.fromQueue)
        assertFalse(BookWidgetSnapshot(book = ebook).showsAudioControls)
        assertEquals("CONTINUE READING", BookWidgetSnapshot(book = ebook).heading)
    }

    @Test fun continueOrderIsNotReplacedByQueueOrder() {
        val state = selectWidgetBook(listOf(audio, ebook), listOf(queued(ebook)), emptyList(), listOf(ebook, audio))
        assertEquals(audio, state.book)
        assertNull(state.readerBook)
        assertTrue(BookWidgetSnapshot(book = audio).showsAudioControls)
    }

    @Test fun upNextIsTheFallbackWhenContinueIsEmpty() {
        val state = selectWidgetBook(emptyList(), listOf(queued(audio)), emptyList(), listOf(audio))
        assertEquals(audio, state.book)
        assertTrue(state.fromQueue)
        assertEquals("UP NEXT", BookWidgetSnapshot(book = audio, fromQueue = true).heading)
        assertTrue(state.upNext.isEmpty())
    }

    @Test fun plainEbookCannotBecomeAnAudioQueueItem() {
        assertNull(selectWidgetBook(emptyList(), listOf(queued(ebook)), emptyList(), listOf(ebook)).book)
        assertFalse(ebook.supportsListening())
    }

    @Test fun narratedEpubCanOpenReaderAndControlAnActiveNarration() {
        val readAlong = ebook.copy(readAlongAvailable = true)
        val state = selectWidgetBook(listOf(readAlong), emptyList(), emptyList(), listOf(readAlong))
        assertEquals(readAlong, state.readerBook)
        assertEquals("CONTINUE READ-ALONG", BookWidgetSnapshot(book = readAlong).heading)
        assertTrue(BookWidgetSnapshot(book = readAlong, hasLiveAudio = true).showsAudioControls)
        assertFalse(BookWidgetSnapshot(book = readAlong).showsAudioControls)
    }

    @Test fun audiobookWithEbookAttachmentHasBothActions() {
        val paired = audio.copy(hasEbook = true)
        val state = selectWidgetBook(listOf(paired), emptyList(), emptyList(), listOf(paired))
        assertEquals(paired, state.readerBook)
        assertTrue(BookWidgetSnapshot(book = paired).showsAudioControls)
    }

    @Test fun linkedEditionResolvesTheActualReaderBook() {
        val state = selectWidgetBook(listOf(audio), emptyList(),
            listOf(LibraryEditionLink(ebook.uniqueKey, audio.uniqueKey)), listOf(audio, ebook))
        assertEquals(audio, state.book)
        assertEquals(ebook, state.readerBook)
    }

    @Test fun queueTitlesExcludeCurrentEbooksAndDuplicates() {
        val next = audio.copy(id = "next", title = "Next")
        assertEquals(listOf("Next"), listeningQueueTitles(
            listOf(queued(ebook), queued(audio), queued(next), queued(next)), audio.uniqueKey))
    }

    @Test fun sameIdOnDifferentServersRemainsDistinct() {
        val second = audio.copy(connectionId = "other-server", title = "Other server")
        assertEquals(listOf("Other server"), listeningQueueTitles(listOf(queued(second)), audio.uniqueKey))
    }

    @Test fun narrationControlsRequireTheExactBookAndALiveSession() {
        val other = ebook.copy(connectionId = "other-server")
        val state = ReadAloudPlaybackState(sessionId = "narration", bookKey = ebook.uniqueKey, hasMediaItem = true)
        assertTrue(state.matchesBook(ebook.uniqueKey))
        assertFalse(state.matchesBook(other.uniqueKey))
        assertFalse(state.copy(hasMediaItem = false).matchesBook(ebook.uniqueKey))
        assertFalse(state.copy(sessionId = null).matchesBook(ebook.uniqueKey))
        assertFalse(state.matchesBook(null))
    }

    private fun queued(book: Book) = PlaybackQueueItem(book, PlaybackQueueOrigin.MANUAL)
    private fun book(id: String, type: AppMediaType) = Book(id = id, title = id,
        source = BookSource.LOCAL, mediaType = type)
}
