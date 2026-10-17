package com.alxenavcam.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogCategory {
    DECODER, RENDER, AUDIO, OUTPUT, PERFORMANCE, ERROR
}

data class LogEntry(
    val timestamp: String,
    val category: LogCategory,
    val message: String
)

object AppLogger {
    private const val TAG = "ALXENAVCAM"
    private const val MAX_LOGS = 250
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun log(category: LogCategory, message: String, throwable: Throwable? = null) {
        val time = synchronized(dateFormat) { dateFormat.format(Date()) }
        val formattedMsg = if (throwable != null) "$message | ${throwable.localizedMessage}" else message
        val entry = LogEntry(time, category, formattedMsg)

        when (category) {
            LogCategory.ERROR -> Log.e(TAG, "[${category.name}] $formattedMsg", throwable)
            else -> Log.d(TAG, "[${category.name}] $formattedMsg")
        }

        synchronized(this) {
            val current = _logs.value.toMutableList()
            current.add(0, entry)
            if (current.size > MAX_LOGS) {
                current.removeAt(current.lastIndex)
            }
            _logs.value = current
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
