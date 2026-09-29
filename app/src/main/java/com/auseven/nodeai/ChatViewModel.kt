package com.auseven.nodeai

import android.app.Application
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Role { USER, ASSISTANT }

data class ChatMessage(val role: Role, val text: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val bridge = LlamaBridge()

    private var modelPtr = 0L
    private var ctxPtr = 0L
    // Kept open for the model's lifetime: llama.cpp memory-maps the file
    // through /proc/self/fd, so the descriptor must stay valid until unload.
    private var modelPfd: ParcelFileDescriptor? = null

    val messages = mutableStateListOf<ChatMessage>()

    var status by mutableStateOf("No model loaded. Tap “Load .gguf” to pick a model.")
        private set
    var modelReady by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    init {
        bridge.backendInit()
    }

    fun loadModel(uri: Uri) {
        if (busy) return
        busy = true
        modelReady = false
        status = "Opening model…"
        viewModelScope.launch(Dispatchers.IO) {
            val name = queryDisplayName(uri) ?: "model.gguf"

            // Open the model as a file descriptor instead of copying gigabytes
            // into app storage. llama.cpp mmaps it in place via /proc/self/fd.
            val pfd = try {
                getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                Log.e(TAG, "openFileDescriptor failed", e)
                null
            }
            if (pfd == null) {
                setStatus("Could not open “$name”. Make sure it is on local storage.")
                setBusy(false)
                return@launch
            }

            // Release any previously loaded model (and its descriptor) first.
            freeNative()
            modelPfd = pfd

            setStatus("Loading $name… (this can take a moment)")
            val path = "/proc/self/fd/${pfd.fd}"
            val m = bridge.loadModel(path)
            if (m == 0L) {
                closePfd()
                setStatus("Failed to load “$name”. Is it a valid .gguf model?")
                setBusy(false)
                return@launch
            }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)
            val c = bridge.newContext(m, N_CTX, threads)
            if (c == 0L) {
                bridge.freeModel(m)
                closePfd()
                setStatus("Failed to create the inference context (out of memory?).")
                setBusy(false)
                return@launch
            }
            modelPtr = m
            ctxPtr = c
            withContext(Dispatchers.Main) {
                modelReady = true
                status = "Ready · $name"
                busy = false
            }
        }
    }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || !modelReady || busy) return

        messages.add(ChatMessage(Role.USER, text))
        messages.add(ChatMessage(Role.ASSISTANT, ""))
        val assistantIndex = messages.lastIndex
        busy = true

        val prompt = buildPrompt()
        viewModelScope.launch {
            val sb = StringBuilder()
            try {
                generateFlow(prompt).collect { token ->
                    sb.append(token)
                    messages[assistantIndex] = messages[assistantIndex].copy(text = sb.toString())
                }
                if (sb.isEmpty()) {
                    messages[assistantIndex] =
                        messages[assistantIndex].copy(text = "(no output)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "generation failed", e)
                messages[assistantIndex] =
                    messages[assistantIndex].copy(text = "(error during generation)")
            } finally {
                busy = false
            }
        }
    }

    fun clearChat() {
        if (busy) return
        messages.clear()
    }

    private fun generateFlow(prompt: String): Flow<String> = callbackFlow {
        val cb = LlamaBridge.TokenCallback { token -> trySend(token) }
        // Blocking native call; runs on the flowOn dispatcher below.
        bridge.generate(modelPtr, ctxPtr, prompt, MAX_TOKENS, cb)
        close()
        awaitClose { }
    }.buffer().flowOn(Dispatchers.Default)

    /**
     * Builds a ChatML-style prompt from the conversation so far. This matches
     * many modern instruct GGUF models (Qwen, etc.). The final empty assistant
     * message added in [send] is skipped.
     */
    private fun buildPrompt(): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append("You are a helpful assistant.")
        sb.append("<|im_end|>\n")
        for (m in messages) {
            when (m.role) {
                Role.USER -> {
                    sb.append("<|im_start|>user\n").append(m.text).append("<|im_end|>\n")
                }
                Role.ASSISTANT -> {
                    if (m.text.isNotEmpty()) {
                        sb.append("<|im_start|>assistant\n").append(m.text).append("<|im_end|>\n")
                    }
                }
            }
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return@use c.getString(idx)
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun setStatus(s: String) {
        withContext(Dispatchers.Main) { status = s }
    }

    private suspend fun setBusy(b: Boolean) {
        withContext(Dispatchers.Main) { busy = b }
    }

    private fun closePfd() {
        try {
            modelPfd?.close()
        } catch (e: Exception) {
            Log.e(TAG, "closing descriptor failed", e)
        }
        modelPfd = null
    }

    private fun freeNative() {
        if (ctxPtr != 0L) {
            bridge.freeContext(ctxPtr)
            ctxPtr = 0L
        }
        if (modelPtr != 0L) {
            bridge.freeModel(modelPtr)
            modelPtr = 0L
        }
        closePfd()
    }

    override fun onCleared() {
        super.onCleared()
        freeNative()
        bridge.backendFree()
    }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val N_CTX = 4096
        private const val MAX_TOKENS = 512
    }
}
