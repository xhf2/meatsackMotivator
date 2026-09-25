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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).messageDao()
    private val order = FrozenOrder()

    /** Every row, in frozen display order. Single Room observer; everything else derives from it. */
    private val allMessages: StateFlow<List<Message>> = dao.getAllMessagesFlow()
        .map { order.apply(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow(LibraryFilter.ACTIVE)

    /** Selected chip. Screen state only: resets to ACTIVE when the ViewModel is recreated. */
    val filter: StateFlow<LibraryFilter> = _filter

    val counts: StateFlow<Map<LibraryFilter, Int>> = allMessages
        .map { LibraryFilters.counts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryFilters.counts(emptyList()))

    /** Rows for the selected chip. Filter is applied *after* FrozenOrder so chips never reorder. */
    val messages: StateFlow<List<Message>> = combine(allMessages, _filter) { all, f ->
        LibraryFilters.apply(all, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(f: LibraryFilter) {
        _filter.value = f
    }

    /**
     * One emission per completed debounced auto-sync after a phone mutation. The screen
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
        store = RoomLibraryStore(dao),
        sync = { PhoneSyncSender(application).syncMessagesToWatch() },
        scope = viewModelScope,
        onSyncResult = { _autoSyncResults.tryEmit(it) },
    )

    fun voteUp(messageId: Long) = editor.voteUp(messageId)

    fun voteDown(messageId: Long) = editor.voteDown(messageId)

    fun archive(messageId: Long) = editor.archive(messageId)

    fun unarchive(messageId: Long) = editor.unarchive(messageId)

    fun delete(messageId: Long) = editor.delete(listOf(messageId))

    /** Bulk delete of every currently-retired row. The screen confirms before calling this. */
    fun deleteAllRetired() =
        editor.delete(LibraryFilters.apply(allMessages.value, LibraryFilter.RETIRED).map { it.id })
}
