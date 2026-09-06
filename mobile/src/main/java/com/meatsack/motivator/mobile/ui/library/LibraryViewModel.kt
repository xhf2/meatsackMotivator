package com.meatsack.motivator.mobile.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meatsack.motivator.mobile.sync.PhoneSyncSender
import com.meatsack.motivator.mobile.sync.SyncResult
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).messageDao()

    val messages: StateFlow<List<Message>> = dao.getAllMessagesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * One emission per completed debounced auto-sync after a phone vote. The screen
     * surfaces only [SyncResult.Failed]; successes are silent.
     *
     * extraBufferCapacity = 1 so tryEmit never drops a result if the collector is
     * momentarily suspended; a second result overwrites the first, which is fine —
     * the latest outcome is the one worth showing.
     */
    private val _autoSyncResults = MutableSharedFlow<SyncResult>(extraBufferCapacity = 1)
    val autoSyncResults: SharedFlow<SyncResult> = _autoSyncResults

    private val editor = LibraryEditor(
        store = RoomVoteStore(dao),
        sync = { PhoneSyncSender(application).syncMessagesToWatch() },
        scope = viewModelScope,
        onSyncResult = { _autoSyncResults.tryEmit(it) },
    )

    fun voteUp(messageId: Long) = editor.voteUp(messageId)

    fun voteDown(messageId: Long) = editor.voteDown(messageId)
}
