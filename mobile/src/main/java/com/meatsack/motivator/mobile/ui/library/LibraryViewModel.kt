package com.meatsack.motivator.mobile.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meatsack.motivator.mobile.sync.PhoneSyncSender
import com.meatsack.motivator.mobile.sync.SyncResult
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).messageDao()
    private val order = FrozenOrder()

    val messages: StateFlow<List<Message>> = dao.getAllMessagesFlow()
        .map { order.apply(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * One emission per completed debounced auto-sync after a phone vote. The screen
     * surfaces only [SyncResult.Failed]; successes are silent.
     *
     * replay = 1 + DROP_OLDEST so the latest result survives until a collector sees
     * it — including a collector that attaches after the fact when the user returns
     * to this tab — and a newer result replaces an unseen older one. The screen
     * calls [consumeAutoSyncResult] after showing a result so it isn't replayed on
     * re-entry.
     */
    private val _autoSyncResults = MutableSharedFlow<SyncResult>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val autoSyncResults: SharedFlow<SyncResult> = _autoSyncResults

    /** Called by the screen once it has shown a result, so a re-entering screen doesn't replay it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumeAutoSyncResult() = _autoSyncResults.resetReplayCache()

    private val editor = LibraryEditor(
        store = RoomVoteStore(dao),
        sync = { PhoneSyncSender(application).syncMessagesToWatch() },
        scope = viewModelScope,
        onSyncResult = { _autoSyncResults.tryEmit(it) },
    )

    fun voteUp(messageId: Long) = editor.voteUp(messageId)

    fun voteDown(messageId: Long) = editor.voteDown(messageId)
}
