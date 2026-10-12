package com.maxbl4.maxlauncher.settings

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A small value persisted as JSON in the app's files directory. It is read synchronously on
 * creation (the files are tiny) and written in the background after every change.
 */
class JsonFileStore<T>(
    context: Context,
    fileName: String,
    private val serializer: KSerializer<T>,
    default: () -> T,
) {
    private val file = AtomicFile(File(context.filesDir, fileName))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val _value = MutableStateFlow(read() ?: default())

    val value: StateFlow<T> = _value.asStateFlow()

    /** Applies an edit. Must be called on the main thread. */
    fun update(edit: (T) -> T) {
        val old = _value.value
        val new = edit(old)
        if (new == old) return
        _value.value = new
        scope.launch { write(new) }
    }

    private fun read(): T? = try {
        if (file.baseFile.exists()) JSON.decodeFromString(serializer, String(file.readFully())) else null
    } catch (e: Exception) {
        Log.e(TAG, "Reading ${file.baseFile.name} failed; using defaults", e)
        null
    }

    private fun write(value: T) {
        val stream = file.startWrite()
        try {
            stream.write(JSON.encodeToString(serializer, value).toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            Log.e(TAG, "Writing ${file.baseFile.name} failed", e)
            file.failWrite(stream)
        }
    }

    companion object {
        private const val TAG = "JsonFileStore"
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
