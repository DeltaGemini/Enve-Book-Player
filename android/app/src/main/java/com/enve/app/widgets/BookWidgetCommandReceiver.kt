package com.enve.app.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.enve.engine.library.LibraryFacade
import com.enve.engine.playback.PlaybackFacade
import com.enve.app.readium.ReadAloudPlaybackCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BookWidgetCommandReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PlaybackEntryPoint {
        fun playback(): PlaybackFacade
        fun library(): LibraryFacade
        fun readAloud(): ReadAloudPlaybackCoordinator
    }

    override fun onReceive(context: Context, intent: Intent) {
        val expectedKey = intent.getStringExtra("book_key") ?: return
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, PlaybackEntryPoint::class.java)
        val playback = entry.playback()
        val command = intent.getStringExtra("command")
        intent.getStringExtra("read_along_session")?.let { sessionId ->
            val readAloud = entry.readAloud()
            val state = readAloud.state.value
            if (command == "toggle" && state.sessionId == sessionId && state.matchesBook(expectedKey)) {
                if (state.isPlaying) readAloud.pause(sessionId) else readAloud.play(sessionId)
            }
            return
        }
        if (playback.nowPlaying.value?.bookKey == expectedKey && playback.transport.value.hasMedia) {
            when (command) {
                "back" -> playback.skipBackward()
                "toggle" -> playback.togglePlayPause()
                "forward" -> playback.skipForward()
            }
        } else if (command == "toggle") {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                try {
                    val book = entry.library().bookByKeyFlow(expectedKey).first()
                        ?.takeIf { it.supportsListening() } ?: return@launch
                    if (playback.queue.value.any { it.book.uniqueKey == expectedKey }) {
                        playback.playQueued(expectedKey)
                    } else {
                        playback.open(book)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
