package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide channel for errors that escape their coroutines (caught by
 * [GlobalCoroutineExceptionHandler]). Instead of silently continuing, the
 * latest message is surfaced as a snackbar over whatever screen is showing
 * (see MainActivity) and the full trace stays in [CrashLog] (🐞 Report).
 */
object AppErrors {

    private val _latest = MutableStateFlow<String?>(null)

    /** Non-null when an uncaught error is waiting to be shown to the human. */
    val latest: StateFlow<String?> = _latest.asStateFlow()

    fun publish(message: String) {
        _latest.value = message
    }

    /** Called once the message has been shown. */
    fun clear() {
        _latest.value = null
    }
}
