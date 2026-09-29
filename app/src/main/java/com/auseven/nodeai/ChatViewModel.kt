package com.auseven.nodeai

import android.app.Application
import android.net.Uri
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
import java.io.File
import java.io.FileOutputStream

enum class Role { USER, ASSISTANT }

data class ChatMessage(val role: Role, val text: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val bridge = LlamaBridge()

    private var modelPtr = 0L
    private var ctxPtr = 0L

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
        status = "Copying model to app storage…"
        viewModelScope.launch(Dispatchers.IO) {
            val file = copyUriToFile(uri)
            if (file == null) {
                setStatus("Could not read the selected file.")
                setBusy(false)
                return@launch
            }
            setStatus("Loading ${file.name}…")

            freeNative()

            val m = bridge.loadModel(file.absolutePath)
            if (m == 0L) {
                setStatus("Failed to load model. Is it a valid .gguf file?")
                setBusy(false)
                return@launch
            }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)
            val c = bridge.newContext(m, N_CTX, threads)
            if (c == 0L) {
                bridge.freeModel(m)
                setStatus("Failed to create the inference context.")
                setBusy(false)
                return@launch
            }
            modelPtr = m
            ctxPtr = c
            viewModelScope.launch(Dispatchers.Main) {
                modelReady = true
                status = "Ready · ${file.name}"
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

    private fun copyUriToFile(uri: Uri): File? {
        val ctx = getApplication<Application>()
        val resolver = ctx.contentResolver
        val name = queryDisplayName(uri) ?: "model.gguf"
        val size = querySize(uri)
        val dir = File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }
        val out = File(dir, name)

        // Reuse a previously-copied model with the same name and size.
        if (out.exists() && size != null && out.length() == size) {
            return out
        }
        return try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            } ?: return null
            out
        } catch (e: Exception) {
            Log.e(TAG, "copy failed", e)
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return null
    }

    private fun querySize(uri: Uri): Long? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
            }
        }
        return null
    }

    private suspend fun setStatus(s: String) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { status = s }
    }

    private suspend fun setBusy(b: Boolean) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { busy = b }
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
