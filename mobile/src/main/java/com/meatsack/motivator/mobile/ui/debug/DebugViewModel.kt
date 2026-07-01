package com.meatsack.motivator.mobile.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meatsack.motivator.mobile.sync.PhoneDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the temporary Debug screen: exposes the watch's diagnostic log (received over
 * `/diagnostics`) newest-first, with refresh and clear. Read on the IO dispatcher so file
 * access never blocks the main thread.
 *
 * Temporary — delete with the rest of the diagnostics pipe
 * (docs/debug/triggering-investigation.md).
 */
class DebugViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PhoneDiagnostics.store(application)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _lines.value = store.read().asReversed()
        }
    }

    fun clear() {
        viewModelScope.launch(Dispatchers.IO) {
            store.clear()
            _lines.value = emptyList()
        }
    }
}
